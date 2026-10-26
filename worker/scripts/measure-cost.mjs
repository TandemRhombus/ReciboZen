// One real call per intent, built exactly as the Worker builds it, and the
// tokens and dollars it actually cost. This is how docs/PLAN_IA.md §3 gets a
// measured number instead of an estimate.
//
//   ANTHROPIC_API_KEY=sk-ant-... node scripts/measure-cost.mjs
//
// It spends real money — about a tenth of a US cent for both calls — and it
// does not go through the quota or the ceiling, so it is for a person at a
// terminal, never for CI.

import Anthropic from "@anthropic-ai/sdk";

import { INTENTS } from "../src/intents/index.mjs";
import { costUsd, usageFromResponse } from "../src/pricing.mjs";

// Shaped like what the app sends, so the prompt is the size production sees.
const SAMPLE_BODY = {
  "identify-appliance": { brand: "Mabe", model: "RMS510IAMRE0", category: "KITCHEN" },
  "suggest-savings": {
    periodDays: 60,
    kwhProjected: 520,
    dacRisk: "APPROACHING",
    appliances: [
      { name: "Minisplit recamara", category: "CLIMATE", brand: "Mirage", model: "Magnum 19", kwhPeriod: 210 },
      { name: "Refrigerador", category: "KITCHEN", brand: "Mabe", model: "RMS510IAMRE0", kwhPeriod: 95 },
      { name: "Boiler", category: "WATER", kwhPeriod: 80 },
      { name: "Television sala", category: "ENTERTAINMENT", brand: "Samsung", kwhPeriod: 40 },
      { name: "Lavadora", category: "LAUNDRY", kwhPeriod: 25 },
    ],
  },
};

const client = new Anthropic();

for (const [name, intent] of Object.entries(INTENTS)) {
  const checked = intent.validate(SAMPLE_BODY[name]);
  if (!checked.ok) throw new Error(`${name}: sample body is invalid: ${JSON.stringify(checked)}`);
  const request = intent.buildRequest(checked.input);
  const response = await client.messages.create(request);
  const usage = usageFromResponse(request.model, response.usage);
  console.log(JSON.stringify({
    intent: name,
    model: response.model,
    stopReason: response.stop_reason,
    // A thinking block here means thinking is costing tokens the model does not plan for.
    blocks: response.content.map((block) => block.type),
    tokensIn: usage.tokensIn,
    tokensOut: usage.tokensOut,
    cachedTokensIn: usage.cachedTokensIn,
    cacheWriteTokens: usage.cacheWriteTokens,
    costUsd: costUsd(usage),
  }));
}
