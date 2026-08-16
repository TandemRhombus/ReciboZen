import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { buildRequestHandler, RateLimiter } from "../src/server.mjs";
import { createServer } from "node:http";

const entitlementKey = {
  algorithm: "ECDSA_P256_SHA256",
  keyId: "test-key",
  publicKeyBase64: "test-public-key",
};
const logger = { info() {}, error() {} };
let baseUrl;
let server;

before(async () => {
  server = createServer(buildRequestHandler({ entitlementKey, logger }));
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
});

test("health endpoint exposes no operational secrets", async () => {
  const response = await fetch(`${baseUrl}/healthz`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    status: "ok",
    service: "recibozen-api",
    version: "0.1.0",
  });
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.ok(response.headers.get("x-request-id"));
});

test("readiness reports not_ready when no data service can answer", async () => {
  const response = await fetch(`${baseUrl}/readyz`);
  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    status: "not_ready",
    entitlementKeyConfigured: true,
    playBillingConfigured: false,
    tariffCatalogAvailable: false,
    solarResourceConfigured: false,
  });
});

test("readiness stays ready when only premium activation is unconfigured", async () => {
  const readyHandler = buildRequestHandler({
    entitlementKey,
    logger,
    tariffCatalog: { current: () => ({ schemaVersion: 1 }) },
  });
  const readyServer = createServer(readyHandler);
  await new Promise((resolve) => readyServer.listen(0, "127.0.0.1", resolve));
  try {
    const url = `http://127.0.0.1:${readyServer.address().port}/readyz`;
    const response = await fetch(url);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.status, "ready");
    assert.equal(body.playBillingConfigured, false);
    assert.equal(body.tariffCatalogAvailable, true);
  } finally {
    await new Promise((resolve, reject) => readyServer.close((error) => error ? reject(error) : resolve()));
  }
});

test("public solar route refuses a client that exceeds its per-minute budget", async () => {
  const solarResource = { forPoint: async () => ({ schemaVersion: 1, monthlyDailyAverage: new Array(12).fill(5) }) };
  const limitedServer = createServer(buildRequestHandler({
    entitlementKey,
    logger,
    solarResource,
    rateLimiter: new RateLimiter({ windowMs: 60_000 }),
  }));
  await new Promise((resolve) => limitedServer.listen(0, "127.0.0.1", resolve));
  try {
    const url = `http://127.0.0.1:${limitedServer.address().port}/v1/solar/resource/20.97/-89.62`;
    const statuses = [];
    for (let attempt = 0; attempt < 22; attempt += 1) {
      statuses.push((await fetch(url)).status);
    }
    assert.equal(statuses.filter((value) => value === 200).length, 20);
    assert.ok(statuses.includes(429));
  } finally {
    await new Promise((resolve, reject) => limitedServer.close((error) => error ? reject(error) : resolve()));
  }
});

test("well-known endpoint publishes only the public trust anchor", async () => {
  const response = await fetch(`${baseUrl}/.well-known/recibozen-entitlement-key`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.keyId, "test-key");
  assert.equal(body.publicKeyBase64, "test-public-key");
  assert.doesNotMatch(JSON.stringify(body), /private/i);
});

test("premium exchange stays fail-closed with plausible client input", async () => {
  const response = await fetch(`${baseUrl}/v1/entitlements/exchange`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      subject: "install-subject-1234",
      productId: "recibozen_plus_monthly",
      purchaseToken: "purchase-token-that-is-long-enough",
      integrityToken: "integrity-token-that-is-long-enough",
    }),
  });
  assert.equal(response.status, 503);
  assert.equal((await response.json()).error, "play_verification_not_configured");
});

test("exchange rejects malformed requests without reflecting tokens", async () => {
  const response = await fetch(`${baseUrl}/v1/entitlements/exchange`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ subject: "install-subject-1234", productId: "INVALID PRODUCT", purchaseToken: "secret", integrityToken: "secret" }),
  });
  assert.equal(response.status, 400);
  const responseText = await response.text();
  assert.match(responseText, /invalid_product_id/);
  assert.doesNotMatch(responseText, /secret/);
});

test("verified purchase receives only a backend-signed lease", async () => {
  const verifiedHandler = buildRequestHandler({
    entitlementKey,
    logger,
    purchaseVerifier: {
      async verify(input) {
        assert.equal(input.subject, "install-subject-1234");
        return { tier: "PLUS", expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 };
      },
    },
    leaseIssuer: {
      issue(input) {
        assert.equal(input.tier, "PLUS");
        return "signed.lease";
      },
    },
  });
  const isolated = createServer(verifiedHandler);
  await new Promise((resolve) => isolated.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${isolated.address().port}`;
  try {
    const response = await fetch(`${url}/v1/entitlements/exchange`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        subject: "install-subject-1234",
        productId: "recibozen_plus_monthly",
        purchaseToken: "purchase-token-that-is-long-enough",
        integrityToken: "integrity-token-that-is-long-enough",
      }),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      schemaVersion: 1,
      tier: "PLUS",
      entitlementLease: "signed.lease",
    });
  } finally {
    await new Promise((resolve, reject) => isolated.close((error) => error ? reject(error) : resolve()));
  }
});

test("unknown routes and query strings are rejected", async () => {
  const missing = await fetch(`${baseUrl}/not-real`);
  assert.equal(missing.status, 404);
  const query = await fetch(`${baseUrl}/healthz?token=do-not-log-this`);
  assert.equal(query.status, 400);
});
