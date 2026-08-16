import {
  createHash,
  createPublicKey,
  randomUUID,
} from "node:crypto";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";
import { EntitlementLeaseIssuer } from "./entitlement-lease.mjs";
import {
  GooglePlayPurchaseVerifier,
  GoogleServiceAccountTokenProvider,
  PurchaseVerificationError,
} from "./google-play.mjs";
import { SolarResourceService } from "./solar-resource.mjs";
import { TariffCatalogService } from "./tariff-catalog.mjs";

const SERVICE_NAME = "recibozen-api";
const SERVICE_VERSION = "0.1.0";
const MAX_JSON_BYTES = 64 * 1024;
const PRODUCT_ID_PATTERN = /^[a-z0-9._-]{1,128}$/;
const SUBJECT_PATTERN = /^[a-zA-Z0-9._:-]{8,128}$/;

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function loadEntitlementPublicKey(path) {
  if (!path) return null;
  const pem = readFileSync(path, "utf8");
  const der = createPublicKey(pem).export({ type: "spki", format: "der" });
  return {
    algorithm: "ECDSA_P256_SHA256",
    keyId: createHash("sha256").update(der).digest("base64url").slice(0, 22),
    publicKeyBase64: der.toString("base64"),
  };
}

function sendJson(response, status, body, requestId) {
  const encoded = Buffer.from(`${JSON.stringify(body)}\n`, "utf8");
  response.writeHead(status, {
    "Cache-Control": "no-store",
    "Content-Length": encoded.length,
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
    "Content-Type": "application/json; charset=utf-8",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Request-Id": requestId,
  });
  response.end(encoded);
}

async function readJson(request) {
  const contentType = request.headers["content-type"] ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "unsupported_media_type", "Content-Type must be application/json");
  }

  const declaredLength = Number(request.headers["content-length"] ?? 0);
  if (Number.isFinite(declaredLength) && declaredLength > MAX_JSON_BYTES) {
    throw new HttpError(413, "payload_too_large", "Request body is too large");
  }

  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_JSON_BYTES) {
      throw new HttpError(413, "payload_too_large", "Request body is too large");
    }
    chunks.push(chunk);
  }
  if (size === 0) throw new HttpError(400, "invalid_json", "A JSON body is required");

  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "invalid_json", "The request body is not valid JSON");
  }
}

function validateExchangeRequest(body) {
  if (!body || Array.isArray(body) || typeof body !== "object") {
    throw new HttpError(400, "invalid_request", "A JSON object is required");
  }
  if (typeof body.productId !== "string" || !PRODUCT_ID_PATTERN.test(body.productId)) {
    throw new HttpError(400, "invalid_product_id", "productId is invalid");
  }
  if (typeof body.subject !== "string" || !SUBJECT_PATTERN.test(body.subject)) {
    throw new HttpError(400, "invalid_subject", "subject is invalid");
  }
  if (typeof body.purchaseToken !== "string" || body.purchaseToken.length < 20 || body.purchaseToken.length > 4096) {
    throw new HttpError(400, "invalid_purchase_token", "purchaseToken is invalid");
  }
  if (typeof body.integrityToken !== "string" || body.integrityToken.length < 20 || body.integrityToken.length > 48 * 1024) {
    throw new HttpError(400, "invalid_integrity_token", "integrityToken is invalid");
  }
}

export function buildRequestHandler({
  entitlementKey = null,
  purchaseVerifier = null,
  leaseIssuer = null,
  tariffCatalog = null,
  solarResource = null,
  logger = console,
} = {}) {
  return async function requestHandler(request, response) {
    const requestId = randomUUID();
    let status = 500;
    let pathname = "/invalid";
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      pathname = url.pathname;
      if (url.search) throw new HttpError(400, "query_not_allowed", "Query parameters are not accepted");

      if (request.method === "GET" && pathname === "/healthz") {
        status = 200;
        sendJson(response, status, {
          status: "ok",
          service: SERVICE_NAME,
          version: SERVICE_VERSION,
        }, requestId);
        return;
      }

      if (request.method === "GET" && pathname === "/readyz") {
        const billingReady = Boolean(purchaseVerifier && leaseIssuer);
        status = entitlementKey && billingReady ? 200 : 503;
        sendJson(response, status, {
          status: entitlementKey && billingReady ? "ready" : "not_ready",
          entitlementKeyConfigured: Boolean(entitlementKey),
          playBillingConfigured: billingReady,
        }, requestId);
        return;
      }

      if (request.method === "GET" && pathname === "/.well-known/recibozen-entitlement-key") {
        if (!entitlementKey) {
          throw new HttpError(503, "entitlement_key_not_configured", "Entitlement verification key is unavailable");
        }
        status = 200;
        sendJson(response, status, {
          schemaVersion: 1,
          algorithm: entitlementKey.algorithm,
          keyId: entitlementKey.keyId,
          publicKeyBase64: entitlementKey.publicKeyBase64,
          usage: "Build-time trust anchor; never bootstrap trust from this endpoint at runtime",
        }, requestId);
        return;
      }

      if (request.method === "GET" && pathname === "/v1/tariffs/current") {
        const catalog = tariffCatalog?.current();
        if (!catalog) throw new HttpError(503, "tariff_catalog_unavailable", "The verified CFE tariff catalog is not available");
        status = 200;
        sendJson(response, status, catalog, requestId);
        return;
      }

      // Accept decimal commas as well as decimal points. Older Android builds
      // formatted coordinates with the device locale (for example 19,4326),
      // which made an otherwise valid Mexican location miss this route and
      // later caused NASA POWER to answer 422 in the direct fallback.
      const solarMatch = /^\/v1\/solar\/resource\/(-?\d+(?:[.,]\d+)?)\/(-?\d+(?:[.,]\d+)?)$/.exec(pathname);
      if (request.method === "GET" && solarMatch) {
        if (!solarResource) throw new HttpError(503, "solar_resource_unavailable", "Solar resource provider is unavailable");
        try {
          const coordinate = (value) => Number(value.replace(",", "."));
          const resource = await solarResource.forPoint(coordinate(solarMatch[1]), coordinate(solarMatch[2]));
          status = 200;
          sendJson(response, status, resource, requestId);
        } catch (error) {
          if (error.message === "invalid_coordinates") throw new HttpError(400, "invalid_coordinates", "Coordinates are invalid");
          throw new HttpError(503, "solar_resource_upstream_unavailable", "NASA POWER data is temporarily unavailable");
        }
        return;
      }

      if (request.method === "POST" && pathname === "/v1/entitlements/exchange") {
        const input = await readJson(request);
        validateExchangeRequest(input);
        if (!purchaseVerifier || !leaseIssuer) {
          throw new HttpError(
            503,
            "play_verification_not_configured",
            "Premium activation remains disabled until Google Play verification is configured",
          );
        }
        const verified = await purchaseVerifier.verify(input);
        const entitlementLease = leaseIssuer.issue({
          subject: input.subject,
          tier: verified.tier,
          purchaseExpiresAtEpochSeconds: verified.expiresAtEpochSeconds,
        });
        status = 200;
        sendJson(response, status, {
          schemaVersion: 1,
          tier: verified.tier,
          entitlementLease,
        }, requestId);
        return;
      }

      throw new HttpError(404, "not_found", "Route not found");
    } catch (error) {
      const safeError = error instanceof HttpError
        ? error
        : error instanceof PurchaseVerificationError
          ? new HttpError(error.status, error.code, error.message)
          : new HttpError(500, "internal_error", "An internal error occurred");
      status = safeError.status;
      if (!response.headersSent) {
        sendJson(response, status, { error: safeError.code, message: safeError.message }, requestId);
      } else {
        response.destroy();
      }
      if (!(error instanceof HttpError) && !(error instanceof PurchaseVerificationError)) {
        logger.error({ requestId, error: error?.message ?? "unknown" });
      }
    } finally {
      const logPath = pathname.startsWith("/v1/solar/resource/")
        ? "/v1/solar/resource/:latitude/:longitude"
        : pathname;
      logger.info({ requestId, method: request.method, path: logPath, status });
    }
  };
}

export function startServer({
  port,
  entitlementKey,
  purchaseVerifier = null,
  leaseIssuer = null,
  tariffCatalog = null,
  solarResource = null,
  logger = console,
}) {
  const server = createServer(buildRequestHandler({
    entitlementKey, purchaseVerifier, leaseIssuer, tariffCatalog, solarResource, logger,
  }));
  server.headersTimeout = 5_000;
  server.requestTimeout = 10_000;
  server.keepAliveTimeout = 5_000;
  server.maxHeadersCount = 50;
  server.listen(port, "0.0.0.0", () => logger.info({ event: "listening", service: SERVICE_NAME, port }));
  return server;
}

async function run() {
  const port = Number(process.env.PORT ?? "8080");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT must be a valid TCP port");
  const entitlementKey = loadEntitlementPublicKey(process.env.ENTITLEMENT_PUBLIC_KEY_FILE);
  const privateKeyPem = process.env.ENTITLEMENT_PRIVATE_KEY_FILE
    ? readFileSync(process.env.ENTITLEMENT_PRIVATE_KEY_FILE, "utf8")
    : null;
  const serviceAccount = process.env.GOOGLE_SERVICE_ACCOUNT_FILE
    ? JSON.parse(readFileSync(process.env.GOOGLE_SERVICE_ACCOUNT_FILE, "utf8"))
    : null;
  const productTiers = process.env.PREMIUM_PRODUCTS_JSON
    ? JSON.parse(process.env.PREMIUM_PRODUCTS_JSON)
    : null;
  if (productTiers && (Array.isArray(productTiers) || typeof productTiers !== "object" ||
    Object.entries(productTiers).some(([productId, tier]) => !PRODUCT_ID_PATTERN.test(productId) || !["PLUS", "FAMILY"].includes(tier)))) {
    throw new Error("PREMIUM_PRODUCTS_JSON must map valid product IDs only to PLUS or FAMILY");
  }
  const expectedCertificateDigests = (process.env.PLAY_CERTIFICATE_SHA256_DIGESTS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  const packageName = process.env.ANDROID_PACKAGE_NAME ?? "com.recibozen.app";
  if (privateKeyPem && entitlementKey) {
    const privateDerivedDer = createPublicKey(privateKeyPem).export({ type: "spki", format: "der" });
    const privateDerivedKeyId = createHash("sha256").update(privateDerivedDer).digest("base64url").slice(0, 22);
    if (privateDerivedKeyId !== entitlementKey.keyId) {
      throw new Error("Entitlement private and public keys do not match");
    }
  }
  const billingConfigured = Boolean(
    privateKeyPem && serviceAccount?.client_email && serviceAccount?.private_key &&
    productTiers && Object.keys(productTiers).length > 0 && expectedCertificateDigests.length > 0,
  );
  const tokenProvider = billingConfigured
    ? new GoogleServiceAccountTokenProvider({ credentials: serviceAccount })
    : null;
  const purchaseVerifier = billingConfigured
    ? new GooglePlayPurchaseVerifier({ packageName, productTiers, expectedCertificateDigests, tokenProvider })
    : null;
  const leaseIssuer = billingConfigured
    ? new EntitlementLeaseIssuer({ privateKeyPem, audience: packageName })
    : null;
  const tariffCatalog = new TariffCatalogService({
    storePath: process.env.TARIFF_CATALOG_FILE ?? "/var/lib/recibozen/tariff-catalog.json",
  });
  await tariffCatalog.initialize();
  const solarResource = new SolarResourceService();
  const server = startServer({ port, entitlementKey, purchaseVerifier, leaseIssuer, tariffCatalog, solarResource });
  const shutdown = (signal) => {
    console.info({ event: "shutdown", signal });
    tariffCatalog.stop();
    server.close((error) => process.exit(error ? 1 : 0));
    setTimeout(() => process.exit(1), 8_000).unref();
  };
  process.once("SIGINT", () => shutdown("SIGINT"));
  process.once("SIGTERM", () => shutdown("SIGTERM"));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run().catch((error) => {
    console.error({ event: "startup_failed", error: error.message });
    process.exit(1);
  });
}
