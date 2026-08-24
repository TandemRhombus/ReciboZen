import Anthropic from "@anthropic-ai/sdk";

import { usageFromResponse, noUsage } from "../pricing.mjs";

// The only place the API key is read. It arrives as a Worker secret, so it
// exists nowhere else: not in the APK, not in the repo, not in wrangler.toml.

/**
 * Wraps one typed intent into the runner shape the router calls:
 * `(input) -> { ok, body | error, status, usage }`.
 *
 * `usage` is always present, even on failure, because the router books spend
 * from it either way. A failed call that burned tokens still burned them, and
 * reporting zero is how a ceiling quietly stops being a ceiling.
 */
export function createAnthropicRunner(intent, { apiKey, client } = {}) {
  const anthropic = client ?? new Anthropic({ apiKey });

  return async function run(input) {
    const request = intent.buildRequest(input);
    let response;
    try {
      response = await anthropic.messages.create(request);
    } catch (error) {
      // A request rejected before generation bills nothing; one that failed
      // mid-stream is not recoverable here, and the SDK does not hand back a
      // partial usage, so zero is the honest floor rather than a guess.
      return {
        ok: false,
        error: errorCode(error),
        status: retryable(error) ? 503 : 502,
        usage: noUsage(request.model),
      };
    }

    const usage = usageFromResponse(request.model, response.usage);
    const toolUse = response.content?.find((block) => block.type === "tool_use");
    if (!toolUse) {
      // Forced tool_choice should make this impossible. It is still checked,
      // because "should be impossible" is not a guarantee that costs nothing
      // to verify — and free text reaching the app would be exactly the
      // failure this whole layer exists to prevent.
      return { ok: false, error: "model_did_not_use_tool", status: 502, usage };
    }

    // The validated request travels with the answer so an intent can check
    // the reply against what was actually asked — an index that points at an
    // appliance nobody sent is not a valid answer, however well formed.
    const parsed = intent.parseOutput(toolUse.input, { input });
    if (!parsed.ok) {
      // A model that answered correctly and simply had nothing to say is not a
      // failing upstream. Calling that 502 tells the app to retry a request
      // that will fail identically forever, and hides the one thing the user
      // could actually act on: the model number may be wrong.
      const noAnswer = parsed.error === "model_output_empty" || parsed.error === "model_output_unusable";
      return {
        ok: false,
        error: noAnswer ? "could_not_estimate" : parsed.error,
        status: noAnswer ? 422 : 502,
        usage,
      };
    }
    return { ok: true, body: parsed.body, usage };
  };
}

function errorCode(error) {
  if (error instanceof Anthropic.RateLimitError) return "provider_rate_limited";
  if (error instanceof Anthropic.AuthenticationError) return "provider_unauthorized";
  if (error instanceof Anthropic.APIConnectionError) return "provider_unreachable";
  if (error instanceof Anthropic.APIStatusError) return "provider_error";
  return "provider_failed";
}

function retryable(error) {
  return error instanceof Anthropic.RateLimitError ||
    error instanceof Anthropic.APIConnectionError ||
    (error instanceof Anthropic.APIStatusError && error.status >= 500);
}
