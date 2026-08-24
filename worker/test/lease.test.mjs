import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, sign } from "node:crypto";

import { verifyEntitlementLease, toRawEcdsaSignature, LeaseFailure } from "../src/lease.mjs";
// The real issuer, not an imitation of it: if backend/src/entitlement-lease.mjs
// ever changes its signature encoding or field order, these tests break here.
import { EntitlementLeaseIssuer } from "../../backend/src/entitlement-lease.mjs";

const AUDIENCE = "app.recibozen.android";
const NOW = 1_770_000_000;

const keyPair = generateKeyPairSync("ec", { namedCurve: "P-256" });
const otherKeyPair = generateKeyPairSync("ec", { namedCurve: "P-256" });
const PUBLIC_KEY_B64 = keyPair.publicKey.export({ type: "spki", format: "der" }).toString("base64");

const issuer = new EntitlementLeaseIssuer({
  privateKeyPem: keyPair.privateKey.export({ type: "pkcs8", format: "pem" }),
  audience: AUDIENCE,
  now: () => NOW,
});

const issue = (overrides = {}) => issuer.issue({
  subject: "subject-under-test",
  tier: "PLUS",
  purchaseExpiresAtEpochSeconds: NOW + 3600,
  ...overrides,
});

const verify = (token, at = NOW) => verifyEntitlementLease(token, {
  publicKeyBase64: PUBLIC_KEY_B64,
  expectedAudience: AUDIENCE,
  nowEpochSeconds: () => at,
});

const base64url = (bytes) => Buffer.from(bytes).toString("base64url");

function forge(fields, { privateKey = keyPair.privateKey, mangleSignature } = {}) {
  const payload = Buffer.from(fields.join("|"), "utf8");
  let signature = sign("sha256", payload, privateKey);
  if (mangleSignature) signature = mangleSignature(signature);
  return `${payload.toString("base64url")}.${base64url(signature)}`;
}

const validFields = () => ["1", AUDIENCE, "subject-under-test", "PLUS", `${NOW}`, `${NOW + 3600}`, "1", "token-id"];

test("accepts a lease produced by the real backend issuer", async () => {
  const result = await verify(issue());
  assert.equal(result.ok, true);
  assert.equal(result.lease.subject, "subject-under-test");
  assert.equal(result.lease.tier, "PLUS");
  assert.equal(result.lease.expiresAt, NOW + 3600);
});

test("the backend really does sign in DER, so the raw conversion is load-bearing", async () => {
  const signature = Buffer.from(issue().split(".")[1], "base64url");
  assert.equal(signature[0], 0x30, "expected a DER SEQUENCE from the issuer");
  assert.notEqual(signature.length, 64, "expected DER, not raw r||s");
  assert.equal(toRawEcdsaSignature(new Uint8Array(signature)).length, 64);
});

test("accepts a raw r||s signature too, so a future issuer change does not lock clients out", async () => {
  const payload = Buffer.from(validFields().join("|"), "utf8");
  const raw = sign("sha256", payload, { key: keyPair.privateKey, dsaEncoding: "ieee-p1363" });
  const result = await verify(`${payload.toString("base64url")}.${base64url(raw)}`);
  assert.equal(result.ok, true);
});

test("rejects a lease minted for another app", async () => {
  const fields = validFields();
  fields[1] = "app.impostor.android";
  assert.equal((await verify(forge(fields))).failure, LeaseFailure.WRONG_AUDIENCE);
});

test("rejects the free tier: a signed lease is not by itself a paid one", async () => {
  const fields = validFields();
  fields[3] = "FREE";
  assert.equal((await verify(forge(fields))).failure, LeaseFailure.INVALID_TIER);
});

test("rejects an expired lease", async () => {
  assert.equal((await verify(issue(), NOW + 3601)).failure, LeaseFailure.INVALID_TIME_WINDOW);
});

test("rejects a lease issued beyond the clock skew allowance", async () => {
  assert.equal((await verify(issue(), NOW - 301)).failure, LeaseFailure.INVALID_TIME_WINDOW);
});

test("rejects a lease that outlives the eight-day format ceiling", async () => {
  const fields = validFields();
  fields[5] = `${NOW + 9 * 24 * 60 * 60}`;
  assert.equal((await verify(forge(fields))).failure, LeaseFailure.INVALID_TIME_WINDOW);
});

test("rejects a payload edited after signing", async () => {
  const [payload, signature] = issue().split(".");
  const fields = Buffer.from(payload, "base64url").toString("utf8").split("|");
  fields[3] = "FAMILY";
  const edited = Buffer.from(fields.join("|"), "utf8").toString("base64url");
  assert.equal((await verify(`${edited}.${signature}`)).failure, LeaseFailure.INVALID_SIGNATURE);
});

test("rejects a lease signed by a key that is not ours", async () => {
  const token = forge(validFields(), { privateKey: otherKeyPair.privateKey });
  assert.equal((await verify(token)).failure, LeaseFailure.INVALID_SIGNATURE);
});

// These build DER by hand instead of signing, because a random key produces a
// random r: half the time it already carries a 0x00 sign byte, and then a
// bloated encoding is caught by the length check rather than by the
// canonical-form check the test claims to be exercising.
const derSequence = (...integers) => {
  const body = Buffer.concat(integers.map((bytes) => Buffer.concat([Buffer.from([0x02, bytes.length]), Buffer.from(bytes)])));
  return new Uint8Array(Buffer.concat([Buffer.from([0x30, body.length]), body]));
};
const bytesOf = (fill, length = 32) => Buffer.alloc(length, fill);

test("unpacks a canonical DER signature into r||s", () => {
  const raw = toRawEcdsaSignature(derSequence(bytesOf(0x11), bytesOf(0x22)));
  assert.deepEqual(Buffer.from(raw.subarray(0, 32)), bytesOf(0x11));
  assert.deepEqual(Buffer.from(raw.subarray(32)), bytesOf(0x22));
});

test("left-pads a short r to 32 bytes", () => {
  const raw = toRawEcdsaSignature(derSequence(bytesOf(0x33, 31), bytesOf(0x44)));
  assert.equal(raw[0], 0x00);
  assert.equal(raw[1], 0x33);
  assert.deepEqual(Buffer.from(raw.subarray(1, 32)), bytesOf(0x33, 31));
});

test("keeps the sign byte a high r legitimately needs", () => {
  const high = Buffer.concat([Buffer.from([0x00]), bytesOf(0xff)]);
  assert.deepEqual(Buffer.from(toRawEcdsaSignature(derSequence(high, bytesOf(0x22))).subarray(0, 32)), bytesOf(0xff));
});

test("rejects a redundant leading zero, so one signature has one spelling", () => {
  // r is 0x11..., its top bit is clear, so this 0x00 carries no meaning.
  assert.equal(toRawEcdsaSignature(derSequence(Buffer.concat([Buffer.from([0x00]), bytesOf(0x11)]), bytesOf(0x22))), null);
});

test("rejects an r that is encoded as negative", () => {
  assert.equal(toRawEcdsaSignature(derSequence(bytesOf(0xff), bytesOf(0x22))), null);
});

test("rejects content past the end of the two integers", () => {
  assert.equal(toRawEcdsaSignature(derSequence(bytesOf(0x11), bytesOf(0x22), bytesOf(0x33))), null);
});

test("rejects a sequence whose declared length disagrees with its bytes", () => {
  const der = derSequence(bytesOf(0x11), bytesOf(0x22));
  const truncated = der.slice(0, der.length - 1);
  assert.equal(toRawEcdsaSignature(truncated), null);
  const trailing = new Uint8Array([...der, 0x00]);
  assert.equal(toRawEcdsaSignature(trailing), null);
});

test("rejects the long form of the length header", () => {
  const body = Buffer.concat([Buffer.from([0x02, 32]), bytesOf(0x11), Buffer.from([0x02, 32]), bytesOf(0x22)]);
  const longForm = new Uint8Array(Buffer.concat([Buffer.from([0x30, 0x81, body.length]), body]));
  assert.equal(toRawEcdsaSignature(longForm), null);
});

test("rejects an empty integer", () => {
  assert.equal(toRawEcdsaSignature(derSequence(Buffer.alloc(0), bytesOf(0x22))), null);
});

test("rejects an r wider than the curve", () => {
  assert.equal(toRawEcdsaSignature(derSequence(bytesOf(0x11, 33), bytesOf(0x22))), null);
});

test("rejects timestamps the Android client would have thrown away", async () => {
  for (const badTimestamp of ["1e9", " 1770000000", "+1770000000", "17700000000000000000000"]) {
    const fields = validFields();
    fields[4] = badTimestamp;
    assert.equal((await verify(forge(fields))).failure, LeaseFailure.MALFORMED, badTimestamp);
  }
});

test("rejects malformed shapes", async () => {
  const fields = validFields();
  assert.equal((await verify(null)).failure, LeaseFailure.MISSING);
  assert.equal((await verify("")).failure, LeaseFailure.MISSING);
  assert.equal((await verify("no-dot-here")).failure, LeaseFailure.MALFORMED);
  assert.equal((await verify("a.b.c")).failure, LeaseFailure.MALFORMED);
  assert.equal((await verify(forge(fields.slice(0, 7)))).failure, LeaseFailure.MALFORMED);
  assert.equal((await verify(forge(["2", ...fields.slice(1)]))).failure, LeaseFailure.MALFORMED);
  const blankSubject = validFields();
  blankSubject[2] = "";
  assert.equal((await verify(forge(blankSubject))).failure, LeaseFailure.MALFORMED);
});

test("refuses to verify when the Worker has no key configured", async () => {
  const result = await verifyEntitlementLease(issue(), { publicKeyBase64: "", expectedAudience: AUDIENCE });
  assert.equal(result.failure, LeaseFailure.NOT_CONFIGURED);
});
