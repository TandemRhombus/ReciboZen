import { createHash, createSign } from "node:crypto";

const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const PLAY_INTEGRITY_SCOPE = "https://www.googleapis.com/auth/playintegrity";
const ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const ALLOWED_SUBSCRIPTION_STATES = new Set([
  "SUBSCRIPTION_STATE_ACTIVE",
  "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
  "SUBSCRIPTION_STATE_CANCELED",
]);
const MAX_INTEGRITY_AGE_MILLIS = 2 * 60 * 1000;

export class PurchaseVerificationError extends Error {
  constructor(code, message, status = 403) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

const base64url = (value) => Buffer.from(value).toString("base64url");

export function purchaseRequestHash({ subject, productId, purchaseToken }) {
  return createHash("sha256")
    .update(`1|${subject}|${productId}|${purchaseToken}`, "utf8")
    .digest("base64url");
}

/**
 * The identity every AI quota is counted against, derived from the purchase.
 *
 * It must not come from the client. The app generates its install subject with
 * UUID.randomUUID() and keeps it in SharedPreferences, so "clear app data" used
 * to produce a brand-new identity — and with it a brand-new monthly allowance —
 * off a single subscription. Two taps in Android settings, repeatable, with a
 * genuine install that passes Play Integrity every time.
 *
 * The purchase token is the one value in that exchange the client cannot invent.
 * If it rotates on a renewal the subject rotates with it, which costs nothing:
 * the allowance resets monthly anyway.
 */
export function quotaSubjectFor(purchaseToken) {
  return createHash("sha256").update(`v1|${purchaseToken}`, "utf8").digest("base64url").slice(0, 43);
}

export function obfuscatedSubject(subject) {
  return createHash("sha256").update(subject, "utf8").digest("base64url");
}

export class GoogleServiceAccountTokenProvider {
  constructor({ credentials, fetchImpl = fetch, now = () => Date.now() }) {
    this.credentials = credentials;
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.cached = null;
  }

  async accessToken() {
    if (this.cached && this.cached.expiresAtMillis - this.now() > 60_000) return this.cached.token;
    const issuedAt = Math.floor(this.now() / 1000);
    const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = base64url(JSON.stringify({
      iss: this.credentials.client_email,
      scope: `${PLAY_INTEGRITY_SCOPE} ${ANDROID_PUBLISHER_SCOPE}`,
      aud: TOKEN_ENDPOINT,
      iat: issuedAt,
      exp: issuedAt + 3600,
    }));
    const unsigned = `${header}.${claims}`;
    const signature = createSign("RSA-SHA256").update(unsigned).end().sign(this.credentials.private_key, "base64url");
    const response = await this.fetchImpl(TOKEN_ENDPOINT, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion: `${unsigned}.${signature}`,
      }),
    });
    if (!response.ok) throw new PurchaseVerificationError("google_auth_failed", "Google Play verification is temporarily unavailable", 503);
    const body = await response.json();
    if (typeof body.access_token !== "string") throw new PurchaseVerificationError("google_auth_failed", "Google Play verification is temporarily unavailable", 503);
    this.cached = {
      token: body.access_token,
      expiresAtMillis: this.now() + Number(body.expires_in ?? 3600) * 1000,
    };
    return this.cached.token;
  }
}

export class GooglePlayPurchaseVerifier {
  constructor({ packageName, productTiers, expectedCertificateDigests, tokenProvider, fetchImpl = fetch, now = () => Date.now() }) {
    this.packageName = packageName;
    this.productTiers = productTiers;
    this.expectedCertificateDigests = new Set(expectedCertificateDigests);
    this.tokenProvider = tokenProvider;
    this.fetchImpl = fetchImpl;
    this.now = now;
  }

  async verify(input) {
    const tier = this.productTiers[input.productId];
    if (!tier) throw new PurchaseVerificationError("unknown_product", "This Premium product is not recognized");
    const accessToken = await this.tokenProvider.accessToken();
    const integrity = await this.#decodeIntegrity(input.integrityToken, accessToken);
    this.#verifyIntegrity(integrity, input);
    const purchase = await this.#getSubscription(input.purchaseToken, accessToken);
    const expiresAtMillis = this.#verifySubscription(purchase, input);
    if (purchase.acknowledgementState === "ACKNOWLEDGEMENT_STATE_PENDING") {
      await this.#acknowledgeSubscription(input.productId, input.purchaseToken, accessToken);
    }
    return {
      tier,
      expiresAtEpochSeconds: Math.floor(expiresAtMillis / 1000),
      quotaSubject: quotaSubjectFor(input.purchaseToken),
    };
  }

  async #decodeIntegrity(integrityToken, accessToken) {
    const response = await this.fetchImpl(
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.packageName)}:decodeIntegrityToken`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify({ integrityToken }),
      },
    );
    if (!response.ok) throw new PurchaseVerificationError("integrity_decode_failed", "App integrity could not be verified");
    return (await response.json()).tokenPayloadExternal;
  }

  #verifyIntegrity(payload, input) {
    const request = payload?.requestDetails;
    const app = payload?.appIntegrity;
    const account = payload?.accountDetails;
    const deviceVerdicts = payload?.deviceIntegrity?.deviceRecognitionVerdict ?? [];
    const timestamp = Number(request?.timestampMillis);
    const expectedHash = purchaseRequestHash(input);
    const certificateDigests = new Set(app?.certificateSha256Digest ?? []);
    const certificateAccepted = [...certificateDigests].some((digest) => this.expectedCertificateDigests.has(digest));
    if (request?.requestPackageName !== this.packageName || request?.requestHash !== expectedHash ||
      !Number.isFinite(timestamp) || Math.abs(this.now() - timestamp) > MAX_INTEGRITY_AGE_MILLIS ||
      app?.appRecognitionVerdict !== "PLAY_RECOGNIZED" || app?.packageName !== this.packageName ||
      !certificateAccepted || account?.appLicensingVerdict !== "LICENSED" ||
      !deviceVerdicts.includes("MEETS_DEVICE_INTEGRITY")) {
      throw new PurchaseVerificationError("integrity_rejected", "This app installation did not pass integrity verification");
    }
  }

  async #getSubscription(purchaseToken, accessToken) {
    const response = await this.fetchImpl(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,
      { headers: { authorization: `Bearer ${accessToken}`, accept: "application/json" } },
    );
    if (!response.ok) throw new PurchaseVerificationError("purchase_not_found", "Google Play did not confirm this purchase");
    return response.json();
  }

  #verifySubscription(purchase, input) {
    const matchingItems = (purchase.lineItems ?? []).filter((item) => item.productId === input.productId);
    const expiryTimes = matchingItems.map((item) => Date.parse(item.expiryTime)).filter(Number.isFinite);
    const expiresAtMillis = expiryTimes.length ? Math.max(...expiryTimes) : 0;
    if (!ALLOWED_SUBSCRIPTION_STATES.has(purchase.subscriptionState) || expiresAtMillis <= this.now()) {
      throw new PurchaseVerificationError("subscription_inactive", "This subscription is not active");
    }
    // Deliberately NOT compared against the caller install any more.
    //
    // The app stamps setObfuscatedAccountId with a hash of its local subject at
    // purchase time, and that subject is a UUID in SharedPreferences. Requiring
    // it to match meant a household that cleared app data — or moved to a new
    // phone, or added a tablet — could never activate again: the stamp on the
    // purchase is permanent and the local UUID is not. A Play subscription
    // belongs to a Google account, not to one installation, so that check was
    // refusing people their own purchase.
    //
    // What still has to hold is unchanged and is where the real assurance lives:
    // the purchase token resolves to an active subscription for this package in
    // Play, the product is one of ours, and Play Integrity says the caller is a
    // genuine install of our signed app. A token alone is useless without those.
    return expiresAtMillis;
  }

  async #acknowledgeSubscription(productId, purchaseToken, accessToken) {
    const response = await this.fetchImpl(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(this.packageName)}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: "{}",
      },
    );
    if (!response.ok) throw new PurchaseVerificationError("purchase_acknowledgement_failed", "The purchase was verified but could not be acknowledged", 503);
  }
}
