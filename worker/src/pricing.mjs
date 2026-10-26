// USD per million tokens, as published on 2026-10-26. docs/PLAN_IA.md §3
// carries the arithmetic this table feeds.
//
// This is what turns tokens into the number the global ceiling compares
// against, so a stale entry here means the ceiling protects the wrong amount.
// Re-check it whenever a model is added.
export const MODEL_PRICING = {
  "claude-haiku-5-5": {
    inputPerMTok: 0.1,
    outputPerMTok: 0.5,
    // A cache hit is billed at 10% of input. A cache *write* is billed at 125%
    // — writing the entry costs more than sending the tokens plainly, and it
    // pays for itself on the second call. Modelling the write as ordinary
    // input would under-report spend on every cold start.
    cachedInputPerMTok: 0.01,
    cacheWritePerMTok: 0.125,
    // Haiku 5.5 has a second rate card, five times the first, for prompts over
    // 100,000 tokens. The prompt counts every input token, cache reads and
    // writes included, and the whole request moves to the higher card — not
    // just the tokens past the line. No intent here comes near it, but the
    // ceiling must not assume that.
    longPrompt: {
      aboveTokens: 100_000,
      inputPerMTok: 0.5,
      outputPerMTok: 2.5,
      cachedInputPerMTok: 0.05,
      cacheWritePerMTok: 0.625,
    },
  },
  // No intent calls this any more. It stays so that spend booked during a
  // rollback is still priced; an unknown model costs nothing to the ceiling.
  "claude-haiku-4-5": {
    inputPerMTok: 1.0,
    outputPerMTok: 5.0,
    cachedInputPerMTok: 0.1,
    cacheWritePerMTok: 1.25,
  },
};

export function costUsd({ model, tokensIn = 0, tokensOut = 0, cachedTokensIn = 0, cacheWriteTokens = 0 }) {
  const price = MODEL_PRICING[model];
  if (!price) return null;
  const promptTokens = tokensIn + cachedTokensIn + cacheWriteTokens;
  const card = price.longPrompt && promptTokens > price.longPrompt.aboveTokens ? price.longPrompt : price;
  return (
    cachedTokensIn * card.cachedInputPerMTok +
    cacheWriteTokens * card.cacheWritePerMTok +
    tokensIn * card.inputPerMTok +
    tokensOut * card.outputPerMTok
  ) / 1e6;
}

/** Maps an Anthropic `usage` object onto the shape `costUsd` expects. */
export function usageFromResponse(model, usage) {
  return {
    model,
    tokensIn: usage?.input_tokens ?? 0,
    tokensOut: usage?.output_tokens ?? 0,
    cachedTokensIn: usage?.cache_read_input_tokens ?? 0,
    cacheWriteTokens: usage?.cache_creation_input_tokens ?? 0,
  };
}

/** What to record when the call never reached the model: nothing was billed. */
export function noUsage(model) {
  return { model, tokensIn: 0, tokensOut: 0, cachedTokensIn: 0, cacheWriteTokens: 0 };
}
