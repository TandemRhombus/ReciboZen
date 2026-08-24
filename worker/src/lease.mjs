// Verifies the same entitlement lease the Android client verifies, using the
// only crypto API a Worker has: WebCrypto.
//
// Token format, produced by backend/src/entitlement-lease.mjs:
//   base64url("1|audience|subject|tier|issued|expires|minVersion|tokenId").base64url(signature)
//
// The client (EntitlementLeaseVerifier.kt) checks the lease against a subject
// it already knows. The Worker has no such expectation: here the signed
// subject IS the identity, and it becomes the quota key.

const SCHEMA_VERSION = "1";
const FIELD_COUNT = 8;
const MAX_CLOCK_SKEW_SECONDS = 5 * 60;
const MAX_LEASE_LIFETIME_SECONDS = 8 * 24 * 60 * 60;
const MAX_TOKEN_CHARACTERS = 4096;
const MAX_PAYLOAD_BYTES = 1024;
const MAX_SUBJECT_CHARACTERS = 256;
const MAX_TOKEN_ID_CHARACTERS = 128;
const PAID_TIERS = new Set(["PLUS", "FAMILY"]);
const P256_COORDINATE_BYTES = 32;
const RAW_SIGNATURE_BYTES = 2 * P256_COORDINATE_BYTES;

export const LeaseFailure = {
  NOT_CONFIGURED: "not_configured",
  MISSING: "missing",
  MALFORMED: "malformed",
  WRONG_AUDIENCE: "wrong_audience",
  INVALID_TIER: "invalid_tier",
  INVALID_TIME_WINDOW: "invalid_time_window",
  INVALID_SIGNATURE: "invalid_signature",
};

function decodeBase64Url(value) {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) return null;
  const padded = value.replaceAll("-", "+").replaceAll("_", "/")
    + "=".repeat((4 - (value.length % 4)) % 4);
  try {
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

function decodeBase64(value) {
  try {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

// Reads one DER INTEGER and returns it left-padded to 32 bytes, or null when the
// encoding is not canonical. Strictness is deliberate: accepting sloppy DER
// would make the same signature representable several ways, and a token that
// has more than one valid encoding is a token that defeats replay bookkeeping.
function readDerInteger(bytes, offset) {
  if (bytes[offset] !== 0x02) return null;
  const length = bytes[offset + 1];
  if (length === undefined || length === 0 || length > 33) return null;
  const start = offset + 2;
  const end = start + length;
  if (end > bytes.length) return null;
  const value = bytes.subarray(start, end);
  if (value[0] & 0x80) return null;                       // negative: not a valid r/s
  if (value[0] === 0x00 && length > 1 && !(value[1] & 0x80)) return null;  // redundant padding
  const stripped = value[0] === 0x00 ? value.subarray(1) : value;
  if (stripped.length === 0 || stripped.length > P256_COORDINATE_BYTES) return null;
  const padded = new Uint8Array(P256_COORDINATE_BYTES);
  padded.set(stripped, P256_COORDINATE_BYTES - stripped.length);
  return { value: padded, next: end };
}

// Node's sign("sha256", data, ecKey) emits ECDSA in DER. Java's
// SHA256withECDSA reads DER, so the Android client never noticed. WebCrypto
// only accepts raw r||s and answers `false` — not an error — for DER input.
export function toRawEcdsaSignature(signature) {
  if (signature.length === RAW_SIGNATURE_BYTES) return signature;
  if (signature[0] !== 0x30) return null;
  const r = readDerInteger(signature, 2);
  if (!r) return null;
  const s = readDerInteger(signature, r.next);
  if (!s || s.next !== signature.length) return null;
  const raw = new Uint8Array(RAW_SIGNATURE_BYTES);
  raw.set(r.value, 0);
  raw.set(s.value, P256_COORDINATE_BYTES);
  return raw;
}

const importedKeys = new Map();

async function importPublicKey(publicKeyBase64) {
  const cached = importedKeys.get(publicKeyBase64);
  if (cached !== undefined) return cached;
  const spki = decodeBase64(publicKeyBase64);
  const key = spki
    ? await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"])
        .catch(() => null)
    : null;
  importedKeys.set(publicKeyBase64, key);
  return key;
}

function rejected(failure) {
  return { ok: false, failure };
}

export async function verifyEntitlementLease(token, options) {
  const { publicKeyBase64, expectedAudience, nowEpochSeconds = () => Math.floor(Date.now() / 1000) } = options;
  if (!publicKeyBase64 || !expectedAudience) return rejected(LeaseFailure.NOT_CONFIGURED);
  if (typeof token !== "string" || token.length === 0) return rejected(LeaseFailure.MISSING);
  if (token.length > MAX_TOKEN_CHARACTERS) return rejected(LeaseFailure.MALFORMED);

  const pieces = token.split(".");
  if (pieces.length !== 2) return rejected(LeaseFailure.MALFORMED);
  const payloadBytes = decodeBase64Url(pieces[0]);
  const signatureBytes = decodeBase64Url(pieces[1]);
  if (!payloadBytes || !signatureBytes) return rejected(LeaseFailure.MALFORMED);
  if (payloadBytes.length === 0 || payloadBytes.length > MAX_PAYLOAD_BYTES) return rejected(LeaseFailure.MALFORMED);

  const fields = new TextDecoder().decode(payloadBytes).split("|");
  if (fields.length !== FIELD_COUNT || fields[0] !== SCHEMA_VERSION) return rejected(LeaseFailure.MALFORMED);
  if (fields[1] !== expectedAudience) return rejected(LeaseFailure.WRONG_AUDIENCE);

  const subject = fields[2];
  if (!subject || subject.length > MAX_SUBJECT_CHARACTERS) return rejected(LeaseFailure.MALFORMED);
  const tier = fields[3];
  if (!PAID_TIERS.has(tier)) return rejected(LeaseFailure.INVALID_TIER);

  const issuedAt = parseEpochSeconds(fields[4]);
  const expiresAt = parseEpochSeconds(fields[5]);
  const minimumVersionCode = parseEpochSeconds(fields[6]);
  if (issuedAt === null || expiresAt === null || minimumVersionCode === null) return rejected(LeaseFailure.MALFORMED);
  if (minimumVersionCode < 1) return rejected(LeaseFailure.MALFORMED);
  const tokenId = fields[7];
  if (!tokenId || tokenId.length > MAX_TOKEN_ID_CHARACTERS) return rejected(LeaseFailure.MALFORMED);

  const now = nowEpochSeconds();
  if (issuedAt > now + MAX_CLOCK_SKEW_SECONDS || expiresAt <= now || expiresAt <= issuedAt ||
      expiresAt - issuedAt > MAX_LEASE_LIFETIME_SECONDS) {
    return rejected(LeaseFailure.INVALID_TIME_WINDOW);
  }

  const raw = toRawEcdsaSignature(signatureBytes);
  if (!raw) return rejected(LeaseFailure.MALFORMED);
  const key = await importPublicKey(publicKeyBase64);
  if (!key) return rejected(LeaseFailure.NOT_CONFIGURED);
  const valid = await crypto.subtle
    .verify({ name: "ECDSA", hash: "SHA-256" }, key, raw, payloadBytes)
    .catch(() => false);
  if (!valid) return rejected(LeaseFailure.INVALID_SIGNATURE);

  return { ok: true, lease: { subject, tier, issuedAt, expiresAt, minimumVersionCode, tokenId } };
}

// The client parses these with toLongOrNull(), which rejects "1e3", " 7" and
// "+7". Number() would accept all three, so the Worker would read a payload
// the client would have thrown away.
function parseEpochSeconds(value) {
  if (!/^-?\d{1,18}$/.test(value)) return null;
  return Number(value);
}
