import { createPrivateKey, randomUUID, sign } from "node:crypto";

const MAX_LEASE_SECONDS = 24 * 60 * 60;

export class EntitlementLeaseIssuer {
  constructor({ privateKeyPem, audience, now = () => Math.floor(Date.now() / 1000) }) {
    this.privateKey = createPrivateKey(privateKeyPem);
    this.audience = audience;
    this.now = now;
  }

  issue({ subject, tier, purchaseExpiresAtEpochSeconds }) {
    const issuedAt = this.now();
    const expiresAt = Math.min(purchaseExpiresAtEpochSeconds, issuedAt + MAX_LEASE_SECONDS);
    if (expiresAt <= issuedAt) throw new Error("Cannot issue an expired entitlement lease");
    const payload = Buffer.from(
      ["1", this.audience, subject, tier, issuedAt, expiresAt, "1", randomUUID()].join("|"),
      "utf8",
    );
    const signature = sign("sha256", payload, this.privateKey);
    return `${payload.toString("base64url")}.${signature.toString("base64url")}`;
  }
}
