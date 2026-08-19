import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { test } from "node:test";
import {
  GooglePlayPurchaseVerifier,
  GoogleServiceAccountTokenProvider,
  PurchaseVerificationError,
  obfuscatedSubject,
  purchaseRequestHash,
} from "../src/google-play.mjs";

test("service account provider creates and caches an OAuth assertion without exposing credentials", async () => {
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  let calls = 0;
  const provider = new GoogleServiceAccountTokenProvider({
    credentials: {
      client_email: "recibozen@example.iam.gserviceaccount.com",
      private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
    },
    now: () => 1_800_000_000_000,
    fetchImpl: async (_url, options) => {
      calls += 1;
      const assertion = new URLSearchParams(options.body).get("assertion");
      assert.equal(assertion.split(".").length, 3);
      assert.doesNotMatch(assertion, /PRIVATE|recibozen@example/);
      return new Response(JSON.stringify({ access_token: "google-access-token", expires_in: 3600 }), { status: 200 });
    },
  });
  assert.equal(await provider.accessToken(), "google-access-token");
  assert.equal(await provider.accessToken(), "google-access-token");
  assert.equal(calls, 1);
});

test("purchase request hash binds every security-sensitive field", () => {
  const input = {
    subject: "install-subject-1234",
    productId: "recibozen_plus_monthly",
    purchaseToken: "purchase-token",
  };
  const baseline = purchaseRequestHash(input);
  assert.notEqual(baseline, purchaseRequestHash({ ...input, subject: "other-install" }));
  assert.notEqual(baseline, purchaseRequestHash({ ...input, productId: "recibozen_family_monthly" }));
  assert.notEqual(baseline, purchaseRequestHash({ ...input, purchaseToken: "other-token" }));
});

test("obfuscated account identifier is stable and Play-compatible", () => {
  const value = obfuscatedSubject("install-subject-1234");
  assert.equal(value, obfuscatedSubject("install-subject-1234"));
  assert.ok(value.length <= 64);
  assert.match(value, /^[A-Za-z0-9_-]+$/);
});

test("verifier requires fresh integrity, matching account and active Google subscription", async () => {
  const now = Date.parse("2026-08-15T12:00:00Z");
  const input = {
    subject: "install-subject-1234",
    productId: "recibozen_plus_monthly",
    purchaseToken: "purchase-token-that-is-long-enough",
    integrityToken: "integrity-token-that-is-long-enough",
  };
  let acknowledgementCalls = 0;
  const verifier = new GooglePlayPurchaseVerifier({
    packageName: "app.recibozen.android",
    productTiers: { recibozen_plus_monthly: "PLUS" },
    expectedCertificateDigests: ["release-cert-digest"],
    tokenProvider: { async accessToken() { return "access-token"; } },
    now: () => now,
    fetchImpl: async (url, options = {}) => {
      if (url.includes(":decodeIntegrityToken")) {
        return new Response(JSON.stringify({
          tokenPayloadExternal: {
            requestDetails: {
              requestPackageName: "app.recibozen.android",
              requestHash: purchaseRequestHash(input),
              timestampMillis: String(now - 5_000),
            },
            appIntegrity: {
              appRecognitionVerdict: "PLAY_RECOGNIZED",
              packageName: "app.recibozen.android",
              certificateSha256Digest: ["release-cert-digest"],
            },
            accountDetails: { appLicensingVerdict: "LICENSED" },
            deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
          },
        }), { status: 200, headers: { "content-type": "application/json" } });
      }
      if (url.includes("/subscriptionsv2/tokens/")) {
        return new Response(JSON.stringify({
          subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
          acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
          externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedSubject(input.subject) },
          lineItems: [{ productId: input.productId, expiryTime: "2026-09-15T12:00:00Z" }],
        }), { status: 200, headers: { "content-type": "application/json" } });
      }
      if (url.endsWith(":acknowledge") && options.method === "POST") {
        acknowledgementCalls += 1;
        return new Response("{}", { status: 200, headers: { "content-type": "application/json" } });
      }
      throw new Error(`Unexpected URL ${url}`);
    },
  });

  assert.deepEqual(await verifier.verify(input), {
    tier: "PLUS",
    expiresAtEpochSeconds: Date.parse("2026-09-15T12:00:00Z") / 1000,
  });
  assert.equal(acknowledgementCalls, 1);
});

test("verifier rejects a purchase bound to another installation", async () => {
  const now = Date.parse("2026-08-15T12:00:00Z");
  const input = {
    subject: "install-subject-1234",
    productId: "recibozen_plus_monthly",
    purchaseToken: "purchase-token-that-is-long-enough",
    integrityToken: "integrity-token-that-is-long-enough",
  };
  const verifier = new GooglePlayPurchaseVerifier({
    packageName: "app.recibozen.android",
    productTiers: { recibozen_plus_monthly: "PLUS" },
    expectedCertificateDigests: ["release-cert-digest"],
    tokenProvider: { async accessToken() { return "access-token"; } },
    now: () => now,
    fetchImpl: async (url) => {
      if (url.includes(":decodeIntegrityToken")) {
        return new Response(JSON.stringify({ tokenPayloadExternal: {
          requestDetails: { requestPackageName: "app.recibozen.android", requestHash: purchaseRequestHash(input), timestampMillis: String(now) },
          appIntegrity: { appRecognitionVerdict: "PLAY_RECOGNIZED", packageName: "app.recibozen.android", certificateSha256Digest: ["release-cert-digest"] },
          accountDetails: { appLicensingVerdict: "LICENSED" },
          deviceIntegrity: { deviceRecognitionVerdict: ["MEETS_DEVICE_INTEGRITY"] },
        } }), { status: 200 });
      }
      return new Response(JSON.stringify({
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
        externalAccountIdentifiers: { obfuscatedExternalAccountId: obfuscatedSubject("different-install") },
        lineItems: [{ productId: input.productId, expiryTime: "2026-09-15T12:00:00Z" }],
      }), { status: 200 });
    },
  });

  await assert.rejects(
    verifier.verify(input),
    (error) => error instanceof PurchaseVerificationError && error.code === "purchase_subject_mismatch",
  );
});
