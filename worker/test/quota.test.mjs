import test from "node:test";
import assert from "node:assert/strict";

import {
  checkSubjectQuota, recordSubjectUsage, checkGlobalCeiling, recordGlobalSpend,
  monthKey, nextMonthStart, MONTHLY_QUOTAS,
} from "../src/quota-policy.mjs";
import { SubjectQuotaStore, GlobalSpendStore } from "../src/quota-store.mjs";
import { costUsd } from "../src/pricing.mjs";

const AUGUST = Math.floor(Date.UTC(2026, 7, 23, 12) / 1000);
const SEPTEMBER = Math.floor(Date.UTC(2026, 8, 1) / 1000);

// A stand-in for Durable Object storage. Structured-clones on write so a test
// cannot pass by accidentally mutating the object it already holds.
function memoryStorage() {
  const map = new Map();
  return {
    async get(key) { return map.has(key) ? structuredClone(map.get(key)) : undefined; },
    async put(key, value) { map.set(key, structuredClone(value)); },
  };
}

test("the month key and reset boundary are UTC", () => {
  assert.equal(monthKey(AUGUST), "2026-08");
  assert.equal(nextMonthStart(AUGUST), SEPTEMBER);
  assert.equal(monthKey(nextMonthStart(Math.floor(Date.UTC(2026, 11, 15) / 1000))), "2027-01");
});

test("a fresh subject is allowed and starts counting at one", () => {
  const verdict = checkSubjectQuota(undefined, { tier: "PLUS", operation: "identify-appliance", nowEpochSeconds: AUGUST });
  assert.equal(verdict.allowed, true);
  assert.equal(verdict.used, 1);
  assert.equal(verdict.limit, MONTHLY_QUOTAS.PLUS["identify-appliance"]);
});

test("the last call inside the quota is allowed and the next one is not", () => {
  const limit = MONTHLY_QUOTAS.PLUS["explain-bill"];
  let state = { month: "2026-08", counts: { "explain-bill": limit - 1 }, tokensIn: 0, tokensOut: 0, costUsd: 0 };
  const last = checkSubjectQuota(state, { tier: "PLUS", operation: "explain-bill", nowEpochSeconds: AUGUST });
  assert.equal(last.allowed, true);
  assert.equal(last.used, limit);
  const over = checkSubjectQuota(last.state, { tier: "PLUS", operation: "explain-bill", nowEpochSeconds: AUGUST });
  assert.equal(over.allowed, false);
  assert.equal(over.reason, "quota_exhausted");
  assert.equal(over.resetsAtEpochSeconds, SEPTEMBER);
});

test("operations do not borrow from each other", () => {
  const exhausted = { month: "2026-08", counts: { "identify-appliance": 150 }, tokensIn: 0, tokensOut: 0, costUsd: 0 };
  assert.equal(checkSubjectQuota(exhausted, { tier: "PLUS", operation: "identify-appliance", nowEpochSeconds: AUGUST }).allowed, false);
  assert.equal(checkSubjectQuota(exhausted, { tier: "PLUS", operation: "explain-bill", nowEpochSeconds: AUGUST }).allowed, true);
});

test("a new month resets the counters without anything having to sweep them", () => {
  const exhausted = { month: "2026-08", counts: { "identify-appliance": 150 }, tokensIn: 999, tokensOut: 999, costUsd: 9 };
  const next = checkSubjectQuota(exhausted, { tier: "PLUS", operation: "identify-appliance", nowEpochSeconds: SEPTEMBER });
  assert.equal(next.allowed, true);
  assert.equal(next.used, 1);
  assert.equal(next.state.month, "2026-09");
  assert.equal(next.state.costUsd, 0);
});

test("FAMILY gets more than PLUS, and an unknown tier gets nothing", () => {
  assert.ok(MONTHLY_QUOTAS.FAMILY["identify-appliance"] > MONTHLY_QUOTAS.PLUS["identify-appliance"]);
  assert.equal(checkSubjectQuota(undefined, { tier: "FREE", operation: "identify-appliance", nowEpochSeconds: AUGUST }).reason, "unknown_tier");
  assert.equal(checkSubjectQuota(undefined, { tier: "PLUS", operation: "jailbreak", nowEpochSeconds: AUGUST }).reason, "unknown_operation");
});

test("usage accumulates tokens and cost", () => {
  let state = recordSubjectUsage(undefined, { tokensIn: 1200, tokensOut: 250, costUsd: 0.0014, nowEpochSeconds: AUGUST });
  state = recordSubjectUsage(state, { tokensIn: 1200, tokensOut: 250, costUsd: 0.0014, nowEpochSeconds: AUGUST });
  assert.equal(state.tokensIn, 2400);
  assert.equal(state.tokensOut, 500);
  assert.equal(state.costUsd, 0.0028);
});

test("the global ceiling stops everyone once it is reached", () => {
  const under = checkGlobalCeiling({ month: "2026-08", costUsd: 19.99, calls: 5 }, { ceilingUsd: 20, nowEpochSeconds: AUGUST });
  assert.equal(under.allowed, true);
  const at = checkGlobalCeiling({ month: "2026-08", costUsd: 20, calls: 5 }, { ceilingUsd: 20, nowEpochSeconds: AUGUST });
  assert.equal(at.allowed, false);
  assert.equal(at.reason, "global_ceiling_reached");
});

test("no ceiling configured means closed, not unlimited", () => {
  for (const ceilingUsd of [undefined, 0, NaN, -5]) {
    const verdict = checkGlobalCeiling(undefined, { ceilingUsd, nowEpochSeconds: AUGUST });
    assert.equal(verdict.allowed, false, `${ceilingUsd}`);
    assert.equal(verdict.reason, "ceiling_not_configured");
  }
});

test("global spend rolls over with the month", () => {
  const august = recordGlobalSpend({ month: "2026-08", costUsd: 19.9, calls: 100 }, { costUsd: 0.5, nowEpochSeconds: AUGUST });
  assert.equal(august.costUsd, 20.4);
  const september = recordGlobalSpend(august, { costUsd: 0.5, nowEpochSeconds: SEPTEMBER });
  assert.equal(september.costUsd, 0.5);
  assert.equal(september.calls, 1);
});

test("the store persists a rejection, so a month rollover is not re-decided", async () => {
  const storage = memoryStorage();
  const store = new SubjectQuotaStore(storage, () => AUGUST);
  await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  assert.equal((await store.snapshot()).counts["identify-appliance"], 1);
  const second = new SubjectQuotaStore(storage, () => AUGUST);
  assert.equal((await second.reserve({ tier: "PLUS", operation: "identify-appliance" })).used, 2);
});

test("the store never hands its internal state back to the caller", async () => {
  const store = new SubjectQuotaStore(memoryStorage(), () => AUGUST);
  const verdict = await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  assert.equal(verdict.state, undefined);
  assert.deepEqual(Object.keys(verdict).sort(), ["allowed", "limit", "resetsAtEpochSeconds", "used"]);
});

test("sequential reservations count every one of them", async () => {
  // 100 seconds apart: slower than the burst window and slower than the
  // sustained one, so this exercises the monthly quota rather than the rate
  // limiter. Firing them in the same second is something no person can do.
  let clock = AUGUST;
  const store = new SubjectQuotaStore(memoryStorage(), () => (clock += 100));
  const limit = MONTHLY_QUOTAS.PLUS["explain-bill"];
  let allowed = 0;
  for (let i = 0; i < limit + 5; i += 1) {
    if ((await store.reserve({ tier: "PLUS", operation: "explain-bill" })).allowed) allowed += 1;
  }
  assert.equal(allowed, limit, "exactly the quota, no more and no fewer");
});

test("the global store accumulates across calls", async () => {
  const store = new GlobalSpendStore(memoryStorage(), () => AUGUST);
  assert.equal((await store.check({ ceilingUsd: 0.01 })).allowed, true);
  await store.record({ costUsd: 0.006 });
  await store.record({ costUsd: 0.006 });
  assert.equal((await store.check({ ceilingUsd: 0.01 })).allowed, false);
  assert.equal((await store.snapshot()).calls, 2);
});

test("the price table turns tokens into the dollars the ceiling compares against", () => {
  const identify = costUsd({ model: "claude-haiku-4-5", cachedTokensIn: 1200, tokensIn: 30, tokensOut: 250 });
  assert.ok(Math.abs(identify - 0.0014) < 0.00001, `${identify}`);
  assert.equal(costUsd({ model: "un-modelo-que-no-existe", tokensOut: 1e9 }), null);
});
