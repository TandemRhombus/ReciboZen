package com.recibozen.app.core.security

import com.recibozen.app.core.model.EntitlementTier
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class VerifiedEntitlementLease(
    val subject: String,
    val tier: EntitlementTier,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val minimumVersionCode: Long,
    val tokenId: String,
)

enum class EntitlementLeaseFailure {
    NOT_CONFIGURED,
    MISSING,
    MALFORMED,
    WRONG_AUDIENCE,
    WRONG_SUBJECT,
    INVALID_TIER,
    INVALID_TIME_WINDOW,
    APP_UPDATE_REQUIRED,
    INVALID_SIGNATURE,
    UNTRUSTED_INSTALL,
}

sealed interface EntitlementLeaseVerification {
    data class Accepted(val lease: VerifiedEntitlementLease) : EntitlementLeaseVerification
    data class Rejected(val failure: EntitlementLeaseFailure) : EntitlementLeaseVerification
}

/**
 * Verifies short-lived premium leases signed by a backend-held EC private key.
 * The APK contains only the public key. Token format:
 * base64url("1|audience|subject|tier|issued|expires|minVersion|tokenId").base64url(signature)
 */
class EntitlementLeaseVerifier(
    private val publicKeyBase64: String,
    private val expectedAudience: String,
    private val currentVersionCode: Long,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    fun verify(token: String?, expectedSubject: String): EntitlementLeaseVerification {
        if (publicKeyBase64.isBlank()) return rejected(EntitlementLeaseFailure.NOT_CONFIGURED)
        if (token.isNullOrBlank()) return rejected(EntitlementLeaseFailure.MISSING)
        if (token.length > MAX_TOKEN_CHARACTERS) return rejected(EntitlementLeaseFailure.MALFORMED)
        val pieces = token.split('.')
        if (pieces.size != 2) return rejected(EntitlementLeaseFailure.MALFORMED)

        val payloadBytes = decodeUrl(pieces[0]) ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        val signatureBytes = decodeUrl(pieces[1]) ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        if (payloadBytes.size > MAX_PAYLOAD_BYTES || signatureBytes.size !in ECDSA_SIGNATURE_SIZE_RANGE) {
            return rejected(EntitlementLeaseFailure.MALFORMED)
        }
        val fields = payloadBytes.toString(Charsets.UTF_8).split('|')
        if (fields.size != FIELD_COUNT || fields[0] != SCHEMA_VERSION) {
            return rejected(EntitlementLeaseFailure.MALFORMED)
        }
        if (fields[1] != expectedAudience) return rejected(EntitlementLeaseFailure.WRONG_AUDIENCE)
        if (expectedSubject.isBlank() || expectedSubject.length > MAX_SUBJECT_CHARACTERS ||
            fields[2] != expectedSubject
        ) {
            return rejected(EntitlementLeaseFailure.WRONG_SUBJECT)
        }
        val tier = runCatching { EntitlementTier.valueOf(fields[3]) }.getOrNull()
            ?.takeIf { it != EntitlementTier.FREE }
            ?: return rejected(EntitlementLeaseFailure.INVALID_TIER)
        val issuedAt = fields[4].toLongOrNull() ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        val expiresAt = fields[5].toLongOrNull() ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        val minimumVersion = fields[6].toLongOrNull() ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        val tokenId = fields[7].takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_ID_CHARACTERS }
            ?: return rejected(EntitlementLeaseFailure.MALFORMED)
        val now = nowEpochSeconds()
        if (issuedAt > now + MAX_CLOCK_SKEW_SECONDS || expiresAt <= now ||
            expiresAt <= issuedAt || expiresAt - issuedAt > MAX_LEASE_LIFETIME_SECONDS
        ) {
            return rejected(EntitlementLeaseFailure.INVALID_TIME_WINDOW)
        }
        if (minimumVersion < 1L) return rejected(EntitlementLeaseFailure.MALFORMED)
        if (minimumVersion > currentVersionCode) {
            return rejected(EntitlementLeaseFailure.APP_UPDATE_REQUIRED)
        }
        if (!verifySignature(payloadBytes, signatureBytes)) {
            return rejected(EntitlementLeaseFailure.INVALID_SIGNATURE)
        }
        return EntitlementLeaseVerification.Accepted(
            VerifiedEntitlementLease(
                subject = fields[2],
                tier = tier,
                issuedAtEpochSeconds = issuedAt,
                expiresAtEpochSeconds = expiresAt,
                minimumVersionCode = minimumVersion,
                tokenId = tokenId,
            ),
        )
    }

    private fun verifySignature(payload: ByteArray, signatureBytes: ByteArray): Boolean = runCatching {
        val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        if (publicKey !is ECPublicKey || publicKey.params.curve.field.fieldSize != P_256_FIELD_SIZE_BITS) {
            return@runCatching false
        }
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload)
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    private fun decodeUrl(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrNull()

    private fun rejected(failure: EntitlementLeaseFailure) =
        EntitlementLeaseVerification.Rejected(failure)

    private companion object {
        const val SCHEMA_VERSION = "1"
        const val FIELD_COUNT = 8
        const val MAX_CLOCK_SKEW_SECONDS = 5 * 60L
        const val MAX_LEASE_LIFETIME_SECONDS = 8 * 24 * 60 * 60L
        const val MAX_TOKEN_CHARACTERS = 4_096
        const val MAX_PAYLOAD_BYTES = 1_024
        const val MAX_SUBJECT_CHARACTERS = 256
        const val MAX_TOKEN_ID_CHARACTERS = 128
        const val P_256_FIELD_SIZE_BITS = 256
        val ECDSA_SIGNATURE_SIZE_RANGE = 64..160
    }
}
