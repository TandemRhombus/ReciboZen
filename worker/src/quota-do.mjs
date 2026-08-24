// The only file that touches the Workers runtime. Every method is a thin
// delegation to a store that is tested in plain Node.
import { DurableObject } from "cloudflare:workers";

import { SubjectQuotaStore, GlobalSpendStore } from "./quota-store.mjs";

const seconds = () => Math.floor(Date.now() / 1000);

/** One instance per lease subject: the textbook per-entity Durable Object. */
export class SubjectQuota extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.store = new SubjectQuotaStore(ctx.storage, seconds);
  }

  reserve(request) { return this.store.reserve(request); }
  record(usage) { return this.store.record(usage); }
  snapshot() { return this.store.snapshot(); }
}

/**
 * A single instance for the whole Worker — the one place this design accepts
 * the "global Durable Object" anti-pattern, because a spend ceiling has to be
 * one serialization point or it is not a ceiling.
 *
 * It costs two short storage round-trips per AI call, against an Anthropic
 * request measured in seconds, so it is nowhere near the constraint at this
 * scale. When it becomes one, shard it: N instances named `global-0..N-1`, each
 * holding `ceilingUsd / N`. The math still bounds total spend, and contention
 * drops by N.
 */
export class GlobalSpend extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.store = new GlobalSpendStore(ctx.storage, seconds);
  }

  check(request) { return this.store.check(request); }
  record(spend) { return this.store.record(spend); }
  snapshot() { return this.store.snapshot(); }
}
