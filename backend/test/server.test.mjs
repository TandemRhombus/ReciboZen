import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { buildRequestHandler } from "../src/server.mjs";
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

test("readiness distinguishes process health from billing configuration", async () => {
  const response = await fetch(`${baseUrl}/readyz`);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    status: "ready",
    entitlementKeyConfigured: true,
    playBillingConfigured: false,
  });
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
    body: JSON.stringify({ productId: "INVALID PRODUCT", purchaseToken: "secret", integrityToken: "secret" }),
  });
  assert.equal(response.status, 400);
  const responseText = await response.text();
  assert.match(responseText, /invalid_product_id/);
  assert.doesNotMatch(responseText, /secret/);
});

test("unknown routes and query strings are rejected", async () => {
  const missing = await fetch(`${baseUrl}/not-real`);
  assert.equal(missing.status, 404);
  const query = await fetch(`${baseUrl}/healthz?token=do-not-log-this`);
  assert.equal(query.status, 400);
});
