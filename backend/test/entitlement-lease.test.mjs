import assert from "node:assert/strict";
import { generateKeyPairSync, verify } from "node:crypto";
import { test } from "node:test";
import { EntitlementLeaseIssuer } from "../src/entitlement-lease.mjs";

test("issuer creates a short ECDSA lease compatible with the Android verifier format", () => {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const now = 1_800_000_000;
  const issuer = new EntitlementLeaseIssuer({
    privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }),
    audience: "app.recibozen.android",
    now: () => now,
  });
  const token = issuer.issue({
    subject: "install-subject-1234",
    tier: "PLUS",
    purchaseExpiresAtEpochSeconds: now + 30 * 24 * 60 * 60,
  });
  const [payloadPart, signaturePart] = token.split(".");
  const payload = Buffer.from(payloadPart, "base64url");
  const fields = payload.toString("utf8").split("|");
  assert.equal(fields.length, 8);
  assert.deepEqual(fields.slice(0, 4), ["1", "app.recibozen.android", "install-subject-1234", "PLUS"]);
  assert.equal(Number(fields[4]), now);
  assert.equal(Number(fields[5]), now + 24 * 60 * 60);
  assert.equal(verify("sha256", payload, publicKey, Buffer.from(signaturePart, "base64url")), true);
});
