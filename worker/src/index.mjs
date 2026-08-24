import { verifyEntitlementLease, LeaseFailure } from "./lease.mjs";
import { costUsd } from "./pricing.mjs";
import { INTENTS } from "./intents/index.mjs";
import { createAnthropicRunner } from "./provider/anthropic.mjs";

// Layers 1-5 and 8 of docs/PLAN_IA.md. Never imports `cloudflare:workers`: the
// Durable Object classes live in quota-do.mjs and are re-exported by
// worker.mjs, which keeps this router testable under plain Node.

const MAX_BODY_BYTES = 4096;

const FAILURE_STATUS = {
  [LeaseFailure.MISSING]: 401,
  [LeaseFailure.NOT_CONFIGURED]: 503,
};

function json(body, status, extraHeaders = {}) {
  return new Response(`${JSON.stringify(body)}\n`, {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...extraHeaders,
    },
  });
}

function bearerToken(request) {
  const match = /^Bearer (.+)$/.exec(request.headers.get("authorization") ?? "");
  return match ? match[1] : "";
}

export async function authorize(request, env) {
  const result = await verifyEntitlementLease(bearerToken(request), {
    publicKeyBase64: env.ENTITLEMENT_PUBLIC_KEY_B64,
    expectedAudience: env.LEASE_AUDIENCE,
  });
  if (result.ok) return result;
  return { ok: false, response: json({ error: result.failure }, FAILURE_STATUS[result.failure] ?? 403) };
}

// One client per isolate rather than one per request: constructing it is not
// free, and the key never changes for the life of the isolate.
const runners = new Map();

function resolveRunner(env, operation, intent) {
  if (env.MODEL_RUNNER?.[operation]) return env.MODEL_RUNNER[operation];
  if (!env.ANTHROPIC_API_KEY) return null;
  if (!runners.has(operation)) {
    runners.set(operation, createAnthropicRunner(intent, { apiKey: env.ANTHROPIC_API_KEY }));
  }
  return runners.get(operation);
}

async function readBody(request) {
  const text = await request.text();
  if (text.length > MAX_BODY_BYTES) return { ok: false, error: "body_too_large" };
  if (text.length === 0) return { ok: true, body: {} };
  try {
    return { ok: true, body: JSON.parse(text) };
  } catch {
    return { ok: false, error: "invalid_json" };
  }
}

/**
 * The order here is the whole point. The ceiling and the quota are both checked
 * BEFORE the model is called, because Durable Object input gates stop
 * protecting a read-then-write the moment a `fetch()` sits in the middle: two
 * simultaneous requests would both read the same stale counter and both decide
 * they were under the limit.
 *
 * The cost is that a call which fails downstream still spent one unit of quota.
 * That is the right trade: the alternative spends real money on every failed
 * call and lets a burst walk straight through the ceiling.
 */
async function spendGuardedCall(env, { operation, lease, run }) {
  const ceilingUsd = Number(env.MONTHLY_CEILING_USD);
  const globalSpend = env.GLOBAL_SPEND.getByName("global");
  const ceiling = await globalSpend.check({ ceilingUsd });
  if (!ceiling.allowed) {
    return json({
      error: ceiling.reason,
      spentUsd: ceiling.spentUsd,
      resetsAtEpochSeconds: ceiling.resetsAtEpochSeconds,
    }, 503);
  }

  const quota = env.SUBJECT_QUOTA.getByName(lease.subject);
  const reservation = await quota.reserve({ tier: lease.tier, operation });
  if (!reservation.allowed) {
    if (reservation.reason === "rate_limited") {
      // Retry-After is the standard way to say this, so a client can back off
      // correctly without anyone having to read our documentation first.
      return json({
        error: reservation.reason,
        operation,
        window: reservation.window,
        retryAfterSeconds: reservation.retryAfterSeconds,
      }, 429, { "retry-after": String(reservation.retryAfterSeconds) });
    }
    return json({
      error: reservation.reason,
      operation,
      used: reservation.used,
      limit: reservation.limit,
      resetsAtEpochSeconds: reservation.resetsAtEpochSeconds,
    }, reservation.reason === "quota_exhausted" ? 429 : 400);
  }

  const result = await run();

  // Usage is recorded even when the model answered badly, because the tokens
  // were billed either way. Reporting zero spend for a failed call is how a
  // ceiling silently stops being a ceiling.
  const usd = costUsd(result.usage) ?? 0;
  await Promise.all([
    quota.record({ tokensIn: result.usage.tokensIn, tokensOut: result.usage.tokensOut, costUsd: usd }),
    globalSpend.record({ costUsd: usd }),
  ]);

  if (!result.ok) return json({ error: result.error }, result.status ?? 502);
  return json({
    ...result.body,
    quota: { operation, used: reservation.used, limit: reservation.limit, resetsAtEpochSeconds: reservation.resetsAtEpochSeconds },
  }, 200);
}

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);

    if (request.method === "GET" && pathname === "/healthz") {
      return json({
        status: "ok",
        leaseKeyConfigured: Boolean(env.ENTITLEMENT_PUBLIC_KEY_B64) && Boolean(env.LEASE_AUDIENCE),
        aiConfigured: Boolean(env.ANTHROPIC_API_KEY),
        ceilingUsd: Number(env.MONTHLY_CEILING_USD) || 0,
        intents: Object.keys(INTENTS),
      }, 200);
    }

    if (!pathname.startsWith("/v1/ai/")) return json({ error: "not_found" }, 404);
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

    const authorized = await authorize(request, env);
    if (!authorized.ok) return authorized.response;
    const { lease } = authorized;

    if (pathname === "/v1/ai/whoami") {
      const { subject, tier, expiresAt, tokenId } = lease;
      return json({ subject, tier, expiresAt, tokenId }, 200);
    }

    // Reports what this subject has spent without spending anything. Costs one
    // storage read and no quota, so the app can show a usage meter freely.
    if (pathname === "/v1/ai/usage") {
      const used = await env.SUBJECT_QUOTA.getByName(lease.subject).snapshot();
      // Built field by field rather than spread: the stored state also holds
      // the rate limiter's timestamps, which are an implementation detail the
      // app has no use for and no business seeing.
      return json({
        tier: lease.tier,
        month: used.month,
        counts: used.counts,
        tokensIn: used.tokensIn,
        tokensOut: used.tokensOut,
        costUsd: used.costUsd,
      }, 200);
    }

    const operation = pathname.slice("/v1/ai/".length);
    const intent = INTENTS[operation];
    if (!intent) return json({ error: "not_implemented", operation }, 501);

    // Everything below happens before a single unit of quota is reserved. A
    // malformed request, or a Worker with no key, must never cost the user
    // something the app was never going to deliver.
    const parsed = await readBody(request);
    if (!parsed.ok) return json({ error: parsed.error }, 400);

    const validated = intent.validate(parsed.body);
    if (!validated.ok) {
      return json({ error: validated.error, field: validated.field, reason: validated.reason }, 400);
    }

    const run = resolveRunner(env, operation, intent);
    if (!run) return json({ error: "ai_not_configured" }, 503);

    return spendGuardedCall(env, { operation, lease, run: () => run(validated.input) });
  },
};
