import test from "node:test";
import assert from "node:assert/strict";

import { INTENTS } from "../src/intents/index.mjs";
import { MODEL_PRICING } from "../src/pricing.mjs";
import { createAnthropicRunner } from "../src/provider/anthropic.mjs";

// One valid request per intent, so every rule below is checked on every route.
const SAMPLE_INPUT = {
  "identify-appliance": { brand: "Mabe", model: "RMS510IAMRE0", category: "KITCHEN" },
  "suggest-savings": {
    appliances: [{ name: "Refrigerador", category: "KITCHEN", kwhPeriod: 90 }],
    periodDays: 60,
    kwhProjected: 300,
    dacRisk: "NONE",
  },
};

const requests = () => Object.entries(INTENTS).map(([name, intent]) => [name, intent.buildRequest(SAMPLE_INPUT[name])]);

test("every intent has a sample input here, so none escapes these checks", () => {
  assert.deepEqual(Object.keys(SAMPLE_INPUT).sort(), Object.keys(INTENTS).sort());
});

test("every model an intent calls has a price, or the ceiling would book it as free", () => {
  for (const [name, request] of requests()) assert.ok(MODEL_PRICING[request.model], `${name}: ${request.model}`);
});

test("every request is one Haiku 5.5 accepts, and bills only the answer", () => {
  for (const [name, request] of requests()) {
    assert.equal(request.model, "claude-haiku-5-5", name);
    // Thinking is on by default and billed as output; the cost model has none.
    assert.deepEqual(request.thinking, { type: "disabled" }, name);
    // `disabled` is a 400 above `high`.
    assert.ok(["low", "medium", "high"].includes(request.output_config?.effort), name);
    // Each of these is a 400 on this model.
    for (const field of ["temperature", "top_p", "top_k"]) assert.equal(field in request, false, `${name}: ${field}`);
    assert.equal(JSON.stringify(request).includes("budget_tokens"), false, name);
    assert.equal(request.messages.at(-1).role, "user", `${name}: a trailing assistant turn is a prefill`);
  }
});

test("a refusal is could-not-estimate, not a 502 the app would retry, and its tokens are booked", async () => {
  const fakeClient = {
    messages: {
      create: async () => ({
        stop_reason: "refusal",
        stop_details: { type: "refusal", category: "general_harms", explanation: null },
        content: [],
        usage: { input_tokens: 1900, output_tokens: 3 },
      }),
    },
  };
  for (const [name, intent] of Object.entries(INTENTS)) {
    const result = await createAnthropicRunner(intent, { client: fakeClient })(SAMPLE_INPUT[name]);
    assert.equal(result.ok, false, name);
    assert.equal(result.status, 422, name);
    assert.equal(result.error, "model_refused", name);
    assert.equal(result.usage.tokensIn, 1900, `${name}: a declined call was still billed`);
    assert.equal(result.usage.model, "claude-haiku-5-5", name);
  }
});
