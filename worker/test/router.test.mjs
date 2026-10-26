import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";

import worker from "../src/index.mjs";
import { SubjectQuotaStore, GlobalSpendStore } from "../src/quota-store.mjs";
import { EntitlementLeaseIssuer } from "../../backend/src/entitlement-lease.mjs";

const AUDIENCE = "app.recibozen.android";
const keyPair = generateKeyPairSync("ec", { namedCurve: "P-256" });

function memoryStorage() {
  const map = new Map();
  return {
    async get(key) { return map.has(key) ? structuredClone(map.get(key)) : undefined; },
    async put(key, value) { map.set(key, structuredClone(value)); },
  };
}

// Stands in for the Durable Object namespaces: same RPC surface, same stores,
// backed by memory instead of the runtime.
function quotaBindings(clock = () => Math.floor(Date.now() / 1000)) {
  const subjects = new Map();
  const globalStore = new GlobalSpendStore(memoryStorage(), clock);
  return {
    SUBJECT_QUOTA: {
      getByName(name) {
        if (!subjects.has(name)) subjects.set(name, new SubjectQuotaStore(memoryStorage(), clock));
        return subjects.get(name);
      },
    },
    GLOBAL_SPEND: { getByName: () => globalStore },
  };
}

const baseEnv = {
  LEASE_AUDIENCE: AUDIENCE,
  ENTITLEMENT_PUBLIC_KEY_B64: keyPair.publicKey.export({ type: "spki", format: "der" }).toString("base64"),
  MONTHLY_CEILING_USD: "20",
};
const env = { ...baseEnv, ...quotaBindings() };

const issuer = new EntitlementLeaseIssuer({
  privateKeyPem: keyPair.privateKey.export({ type: "pkcs8", format: "pem" }),
  audience: AUDIENCE,
});
const lease = issuer.issue({
  subject: "install-42",
  tier: "PLUS",
  purchaseExpiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
});

const call = (path, { method = "POST", token, environment = env } = {}) =>
  worker.fetch(
    new Request(`https://ia-recibozen.simapp.uk${path}`, {
      method,
      headers: token ? { authorization: `Bearer ${token}` } : {},
    }),
    environment,
  );

test("health reports what is configured without revealing it", async () => {
  const response = await call("/healthz", { method: "GET" });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body, {
    status: "ok", leaseKeyConfigured: true, aiConfigured: false, ceilingUsd: 20,
    intents: ["identify-appliance", "suggest-savings"],
  });
});

test("an AI route without a lease is unauthorized, not forbidden", async () => {
  const response = await call("/v1/ai/whoami");
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "missing");
});

test("a lease from the wrong key is forbidden", async () => {
  const stranger = new EntitlementLeaseIssuer({
    privateKeyPem: generateKeyPairSync("ec", { namedCurve: "P-256" }).privateKey.export({ type: "pkcs8", format: "pem" }),
    audience: AUDIENCE,
  });
  const forged = stranger.issue({
    subject: "install-42",
    tier: "PLUS",
    purchaseExpiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600,
  });
  const response = await call("/v1/ai/whoami", { token: forged });
  assert.equal(response.status, 403);
  assert.equal((await response.json()).error, "invalid_signature");
});

test("a valid lease passes and the Worker reads the signed subject", async () => {
  const response = await call("/v1/ai/whoami", { token: lease });
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.subject, "install-42");
  assert.equal(body.tier, "PLUS");
});

test("a bare token without the Bearer scheme is not accepted", async () => {
  const response = await worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/whoami", { method: "POST", headers: { authorization: lease } }),
    env,
  );
  assert.equal(response.status, 401);
});

test("an unconfigured Worker fails closed and says so", async () => {
  const response = await call("/v1/ai/whoami", { token: lease, environment: { LEASE_AUDIENCE: AUDIENCE } });
  assert.equal(response.status, 503);
});

test("AI routes reject non-POST before looking at the lease", async () => {
  assert.equal((await call("/v1/ai/whoami", { method: "GET" })).status, 405);
});

test("an authorized call to a route that does not exist yet is 501, not a silent 200", async () => {
  assert.equal((await call("/v1/ai/explain-bill", { token: lease })).status, 501);
});

test("anything outside /v1/ai is not found", async () => {
  assert.equal((await call("/v1/tariffs/current", { method: "GET" })).status, 404);
});

// --- Layers 3, 4 and 8 through the router ---

const USAGE = { model: "claude-haiku-5-5", tokensIn: 1930, tokensOut: 310 };

function envWithModel(runner, overrides = {}) {
  return {
    ...baseEnv,
    ...quotaBindings(),
    MODEL_RUNNER: { "identify-appliance": runner },
    ...overrides,
  };
}

const VALID_INPUT = { brand: "Mabe", model: "RMS510IAMRE0", category: "KITCHEN" };

const identify = (environment, token = lease, body = VALID_INPUT) =>
  worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/identify-appliance", {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(body),
    }),
    environment,
  );

test("a successful call answers with what is left of the quota", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: { watts: 120 }, usage: USAGE }));
  const response = await identify(environment);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.watts, 120);
  assert.equal(body.quota.used, 1);
  assert.equal(body.quota.limit, 150);
});

test("tokens and cost land in the subject's ledger", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: {}, usage: USAGE }));
  await identify(environment);
  await identify(environment);
  const snapshot = await environment.SUBJECT_QUOTA.getByName("install-42").snapshot();
  assert.equal(snapshot.tokensIn, 3860);
  assert.equal(snapshot.tokensOut, 620);
  assert.ok(snapshot.costUsd > 0, "a real call must not record zero cost");
});

test("the quota runs out and the answer says when it comes back", async () => {
  // A clock that advances past both rate-limit windows, so this reaches the
  // monthly ceiling instead of stopping at the burst one.
  let clock = Math.floor(Date.now() / 1000);
  const environment = { ...baseEnv, ...quotaBindings(() => (clock += 100)), MODEL_RUNNER: { "identify-appliance": async () => ({ ok: true, body: {}, usage: USAGE }) } };
  const quota = environment.SUBJECT_QUOTA.getByName("install-42");
  for (let i = 0; i < 150; i += 1) await quota.reserve({ tier: "PLUS", operation: "identify-appliance" });
  const response = await identify(environment);
  assert.equal(response.status, 429);
  const body = await response.json();
  assert.equal(body.error, "quota_exhausted");
  assert.ok(body.resetsAtEpochSeconds > Math.floor(Date.now() / 1000));
});

test("the global ceiling switches the AI off for everyone, not just the spender", async () => {
  let called = 0;
  const environment = envWithModel(async () => { called += 1; return { ok: true, body: {}, usage: USAGE }; },
    { MONTHLY_CEILING_USD: "0.0005" });
  assert.equal((await identify(environment)).status, 200);   // ~$0.00035, still under
  assert.equal((await identify(environment)).status, 200);   // ~$0.0007, now over
  const blocked = await identify(environment);
  assert.equal(blocked.status, 503);
  assert.equal((await blocked.json()).error, "global_ceiling_reached");
  assert.equal(called, 2, "the model must not be called once the ceiling is reached");

  // A different subject with untouched quota is stopped just the same.
  const other = new EntitlementLeaseIssuer({
    privateKeyPem: keyPair.privateKey.export({ type: "pkcs8", format: "pem" }),
    audience: AUDIENCE,
  }).issue({ subject: "install-99", tier: "FAMILY", purchaseExpiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 });
  assert.equal((await identify(environment, other)).status, 503);
});

test("a Worker with no ceiling configured refuses to spend at all", async () => {
  let called = 0;
  const environment = envWithModel(async () => { called += 1; return { ok: true, body: {}, usage: USAGE }; },
    { MONTHLY_CEILING_USD: "" });
  const response = await identify(environment);
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "ceiling_not_configured");
  assert.equal(called, 0);
});

test("a model that fails still books the tokens it burned", async () => {
  const environment = envWithModel(async () => ({ ok: false, error: "upstream_error", status: 502, usage: USAGE }));
  const response = await identify(environment);
  assert.equal(response.status, 502);
  const snapshot = await environment.SUBJECT_QUOTA.getByName("install-42").snapshot();
  assert.ok(snapshot.costUsd > 0, "a failed call that consumed tokens must not report zero");
  assert.equal((await environment.GLOBAL_SPEND.getByName("global").snapshot()).calls, 1);
});

test("reading your own usage costs no quota", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: {}, usage: USAGE }));
  await identify(environment);
  const response = await worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/usage", { method: "POST", headers: { authorization: `Bearer ${lease}` } }),
    environment,
  );
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.counts["identify-appliance"], 1, "the meter reads one, not two");
  assert.equal(body.tier, "PLUS");
});

test("a route with no model behind it costs no quota either", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: {}, usage: USAGE }));
  const response = await worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/explain-bill", { method: "POST", headers: { authorization: `Bearer ${lease}` } }),
    environment,
  );
  assert.equal(response.status, 501);
  const snapshot = await environment.SUBJECT_QUOTA.getByName("install-42").snapshot();
  assert.deepEqual(snapshot.counts, {}, "a 501 must not charge the user");
});

test("a malformed intent is refused without touching the quota or the model", async () => {
  let called = 0;
  const environment = envWithModel(async () => { called += 1; return { ok: true, body: {}, usage: USAGE }; });
  for (const body of [{}, { brand: "Mabe" }, { ...VALID_INPUT, category: "NUCLEAR" }, { ...VALID_INPUT, brand: "a\nb" }]) {
    const response = await identify(environment, lease, body);
    assert.equal(response.status, 400, JSON.stringify(body));
    assert.equal((await response.json()).error, "invalid_input");
  }
  assert.equal(called, 0, "the model must never see a request that failed validation");
  const snapshot = await environment.SUBJECT_QUOTA.getByName("install-42").snapshot();
  assert.deepEqual(snapshot.counts, {}, "a rejected request must not cost the user a unit of quota");
});

test("a body that is not JSON, or is oversized, is refused the same way", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: {}, usage: USAGE }));
  const send = (body) => worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/identify-appliance", {
      method: "POST", headers: { authorization: `Bearer ${lease}` }, body,
    }), environment);
  assert.equal((await send("{no es json")).status, 400);
  assert.equal((await send(JSON.stringify({ ...VALID_INPUT, padding: "x".repeat(5000) }))).status, 400);
  const snapshot = await environment.SUBJECT_QUOTA.getByName("install-42").snapshot();
  assert.deepEqual(snapshot.counts, {});
});

test("a burst is turned away with 429 and a Retry-After the client can obey", async () => {
  let called = 0;
  const environment = envWithModel(async () => { called += 1; return { ok: true, body: {}, usage: USAGE }; });
  for (let i = 0; i < 6; i += 1) {
    assert.equal((await identify(environment)).status, 200, `call ${i + 1}`);
  }
  const blocked = await identify(environment);
  assert.equal(blocked.status, 429);
  const retryAfter = Number(blocked.headers.get("retry-after"));
  assert.ok(retryAfter > 0 && retryAfter <= 60, `retry-after was ${retryAfter}`);
  const body = await blocked.json();
  assert.equal(body.error, "rate_limited");
  assert.equal(body.window, "burst");
  assert.equal(called, 6, "the model must not be called for a rate-limited request");
});

test("a model that simply did not know is 422, not a 502 the app would retry forever", async () => {
  const environment = envWithModel(async () => ({ ok: false, error: "could_not_estimate", status: 422, usage: USAGE }));
  const response = await identify(environment);
  assert.equal(response.status, 422);
  assert.equal((await response.json()).error, "could_not_estimate");
});

test("usage never leaks the rate limiter's timestamps", async () => {
  const environment = envWithModel(async () => ({ ok: true, body: {}, usage: USAGE }));
  await identify(environment);
  const response = await worker.fetch(
    new Request("https://ia-recibozen.simapp.uk/v1/ai/usage", { method: "POST", headers: { authorization: `Bearer ${lease}` } }),
    environment,
  );
  const body = await response.json();
  assert.equal(body.recent, undefined, "the burst window is ours, not the app's");
  assert.deepEqual(Object.keys(body).sort(), ["costUsd", "counts", "month", "tier", "tokensIn", "tokensOut"]);
});
