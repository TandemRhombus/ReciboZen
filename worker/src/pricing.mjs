// USD per million tokens, as published in August 2026. docs/PLAN_IA.md §3
// carries the arithmetic this table feeds.
//
// This is what turns tokens into the number the global ceiling compares
// against, so a stale entry here means the ceiling protects the wrong amount.
// Re-check it whenever a model is added.
export const MODEL_PRICING = {
  "claude-haiku-4-5": {
    inputPerMTok: 1.0,
    outputPerMTok: 5.0,
    // A cache hit is billed at 10% of input. A cache *write* is billed at 125%
    // — writing the entry costs more than sending the tokens plainly, and it
    // pays for itself on the second call. Modelling the write as ordinary
    // input would under-report spend on every cold start.
    cachedInputPerMTok: 0.1,
    cacheWritePerMTok: 1.25,
  },
};

export function costUsd({ model, tokensIn = 0, tokensOut = 0, cachedTokensIn = 0, cacheWriteTokens = 0 }) {
  const price = MODEL_PRICING[model];
  if (!price) return null;
  return (
    cachedTokensIn * price.cachedInputPerMTok +
    cacheWriteTokens * price.cacheWritePerMTok +
    tokensIn * price.inputPerMTok +
    tokensOut * price.outputPerMTok
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
