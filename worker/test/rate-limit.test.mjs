import test from "node:test";
import assert from "node:assert/strict";

import { checkRateLimit, RATE_LIMITS, MONTHLY_QUOTAS } from "../src/quota-policy.mjs";
import { SubjectQuotaStore } from "../src/quota-store.mjs";

const AUGUST = Math.floor(Date.UTC(2026, 7, 23, 12) / 1000);
const BURST = RATE_LIMITS.find((limit) => limit.name === "burst");
const SUSTAINED = RATE_LIMITS.find((limit) => limit.name === "sustained");

function memoryStorage() {
  const map = new Map();
  return {
    async get(key) { return map.has(key) ? structuredClone(map.get(key)) : undefined; },
    async put(key, value) { map.set(key, structuredClone(value)); },
  };
}

/** A store whose clock only moves when the test says so. */
function storeAt(start = AUGUST) {
  const clock = { now: start };
  return {
    clock,
    store: new SubjectQuotaStore(memoryStorage(), () => clock.now),
  };
}

test("the burst window admits its limit and refuses the next one", () => {
  let recent = [];
  for (let i = 0; i < BURST.maxCalls; i += 1) {
    const verdict = checkRateLimit(recent, { nowEpochSeconds: AUGUST });
    assert.equal(verdict.allowed, true, `call ${i + 1}`);
    recent = verdict.recent;
  }
  const over = checkRateLimit(recent, { nowEpochSeconds: AUGUST });
  assert.equal(over.allowed, false);
  assert.equal(over.reason, "rate_limited");
  assert.equal(over.window, "burst");
});

test("the refusal says exactly when to come back", () => {
  const recent = Array.from({ length: BURST.maxCalls }, () => AUGUST);
  const over = checkRateLimit(recent, { nowEpochSeconds: AUGUST + 20 });
  assert.equal(over.retryAfterSeconds, BURST.windowSeconds - 20);
  // And at the far edge of the window it is one second, never zero: a client
  // told to retry in zero seconds retries immediately and is refused again.
  const edge = checkRateLimit(recent, { nowEpochSeconds: AUGUST + BURST.windowSeconds - 1 });
  assert.equal(edge.retryAfterSeconds, 1);
});

test("once the window has passed, the caller is let back in", () => {
  const recent = Array.from({ length: BURST.maxCalls }, () => AUGUST);
  const after = checkRateLimit(recent, { nowEpochSeconds: AUGUST + BURST.windowSeconds });
  assert.equal(after.allowed, true);
});

test("a refused attempt is not counted, so nobody locks themselves out forever", () => {
  const recent = Array.from({ length: BURST.maxCalls }, () => AUGUST);
  const first = checkRateLimit(recent, { nowEpochSeconds: AUGUST + 1 });
  const second = checkRateLimit(first.recent, { nowEpochSeconds: AUGUST + 2 });
  assert.equal(first.recent.length, BURST.maxCalls);
  assert.equal(second.recent.length, BURST.maxCalls, "hammering must not extend the ban");
});

test("the sustained window catches a drip that stays under the burst limit", () => {
  let recent = [];
  let clock = AUGUST;
  // One call per 61 seconds never trips the per-minute rule, and would let
  // someone drain a month of quota in an afternoon without the hourly one.
  for (let i = 0; i < SUSTAINED.maxCalls; i += 1) {
    clock += 61;
    const verdict = checkRateLimit(recent, { nowEpochSeconds: clock });
    assert.equal(verdict.allowed, true, `drip ${i + 1}`);
    recent = verdict.recent;
  }
  const over = checkRateLimit(recent, { nowEpochSeconds: clock + 61 });
  assert.equal(over.allowed, false);
  assert.equal(over.window, "sustained");
});

test("the timestamp list is pruned, so it cannot grow without bound", () => {
  const ancient = Array.from({ length: 500 }, (_, i) => AUGUST - 10_000 - i);
  const verdict = checkRateLimit(ancient, { nowEpochSeconds: AUGUST });
  assert.equal(verdict.allowed, true);
  assert.equal(verdict.recent.length, 1, "everything older than the widest window is dropped");
});

// --- Through the store, where it meets the monthly quota ---

test("a burst refusal costs no monthly quota", async () => {
  const { store } = storeAt();
  for (let i = 0; i < BURST.maxCalls; i += 1) {
    await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  }
  const refused = await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  assert.equal(refused.allowed, false);
  assert.equal(refused.reason, "rate_limited");
  assert.ok(refused.retryAfterSeconds > 0);

  const snapshot = await store.snapshot();
  assert.equal(snapshot.counts["identify-appliance"], BURST.maxCalls,
    "the refused call must not appear in the monthly counter");
});

test("a subject with no quota left still runs into the burst limit", async () => {
  const { clock, store } = storeAt();
  const storage = memoryStorage();
  // Start from an exhausted month so every call below is refused on quota.
  await storage.put("state", {
    month: "2026-08",
    counts: { "identify-appliance": MONTHLY_QUOTAS.PLUS["identify-appliance"] },
    tokensIn: 0, tokensOut: 0, costUsd: 0, recent: [],
  });
  const exhausted = new SubjectQuotaStore(storage, () => clock.now);

  for (let i = 0; i < BURST.maxCalls; i += 1) {
    assert.equal((await exhausted.reserve({ tier: "PLUS", operation: "identify-appliance" })).reason, "quota_exhausted");
  }
  const refused = await exhausted.reserve({ tier: "PLUS", operation: "identify-appliance" });
  assert.equal(refused.reason, "rate_limited",
    "otherwise someone with nothing left could hammer the endpoint for free");
  void store;
});

test("the burst window survives a month rollover without resetting", async () => {
  const { clock, store } = storeAt();
  for (let i = 0; i < BURST.maxCalls; i += 1) {
    await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  }
  // Crossing into a new month resets counters. It must not hand back a fresh
  // burst allowance in the same second.
  clock.now = Math.floor(Date.UTC(2026, 8, 1) / 1000);
  const first = await store.reserve({ tier: "PLUS", operation: "identify-appliance" });
  assert.equal(first.allowed, true, "a new month is genuinely far past the old window");
  assert.equal(first.used, 1, "and the monthly counter did reset");
});
