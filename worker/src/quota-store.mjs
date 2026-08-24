// Everything the Durable Objects actually do, against a storage interface of
// `get(key)` / `put(key, value)`. Keeping it here — instead of inside a class
// that extends `cloudflare:workers` — is what lets `node --test` cover it.
//
// Atomicity note: Durable Object input gates block other requests while a
// storage operation is in flight, so read-then-write here cannot interleave.
// That guarantee ends the moment a `fetch()` sits between the read and the
// write, which is exactly why quota is reserved BEFORE the model is called and
// usage is recorded after, as two separate trips.

import {
  checkSubjectQuota, recordSubjectUsage, emptySubjectState, checkRateLimit,
  checkGlobalCeiling, recordGlobalSpend, emptyGlobalState, monthKey,
} from "./quota-policy.mjs";

const SUBJECT_KEY = "state";
const GLOBAL_KEY = "state";

export class SubjectQuotaStore {
  constructor(storage, nowEpochSeconds) {
    this.storage = storage;
    this.now = nowEpochSeconds;
  }

  async reserve({ tier, operation }) {
    const nowEpochSeconds = this.now();
    const state = await this.storage.get(SUBJECT_KEY);

    // Burst first: a caller already over the per-minute window should be turned
    // away before the monthly counter is even consulted, and should not spend a
    // unit of quota to learn it.
    const rate = checkRateLimit(state?.recent, { nowEpochSeconds });
    if (!rate.allowed) {
      const base = state ?? emptySubjectState(monthKey(nowEpochSeconds));
      await this.storage.put(SUBJECT_KEY, { ...base, recent: rate.recent });
      return {
        allowed: false,
        reason: rate.reason,
        window: rate.window,
        retryAfterSeconds: rate.retryAfterSeconds,
      };
    }

    const verdict = checkSubjectQuota(state, { tier, operation, nowEpochSeconds });
    // The attempt joins the burst window even when the monthly quota refused
    // it. Otherwise a subject with nothing left could hammer the endpoint for
    // free forever, and the burst limit would protect only the people who
    // still had quota to lose.
    const next = { ...verdict.state, recent: rate.recent };
    // Persist before answering, always: a rejection that rolled the month over
    // has to survive too, or the next call re-reads a stale month.
    await this.storage.put(SUBJECT_KEY, next);
    const { state: _discarded, ...answer } = verdict;
    return answer;
  }

  async record({ tokensIn, tokensOut, costUsd }) {
    const nowEpochSeconds = this.now();
    const state = await this.storage.get(SUBJECT_KEY);
    const next = recordSubjectUsage(state, { tokensIn, tokensOut, costUsd, nowEpochSeconds });
    await this.storage.put(SUBJECT_KEY, next);
    return next;
  }

  async snapshot() {
    return (await this.storage.get(SUBJECT_KEY)) ?? emptySubjectState(monthKey(this.now()));
  }
}

export class GlobalSpendStore {
  constructor(storage, nowEpochSeconds) {
    this.storage = storage;
    this.now = nowEpochSeconds;
  }

  async check({ ceilingUsd }) {
    const nowEpochSeconds = this.now();
    const state = await this.storage.get(GLOBAL_KEY);
    const verdict = checkGlobalCeiling(state, { ceilingUsd, nowEpochSeconds });
    await this.storage.put(GLOBAL_KEY, verdict.state);
    const { state: _discarded, ...answer } = verdict;
    return answer;
  }

  async record({ costUsd }) {
    const nowEpochSeconds = this.now();
    const state = await this.storage.get(GLOBAL_KEY);
    const next = recordGlobalSpend(state, { costUsd, nowEpochSeconds });
    await this.storage.put(GLOBAL_KEY, next);
    return next;
  }

  async snapshot() {
    return (await this.storage.get(GLOBAL_KEY)) ?? emptyGlobalState(monthKey(this.now()));
  }
}
