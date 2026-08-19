package app.recibozen.android

import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.security.EntitlementLeaseFailure
import app.recibozen.android.core.security.EntitlementLeaseVerification
import app.recibozen.android.core.security.EntitlementLeaseVerifier
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementLeaseVerifierTest {
    private val now = 1_800_000_000L
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val verifier = EntitlementLeaseVerifier(
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        expectedAudience = "app.recibozen.android",
        currentVersionCode = 12,
        nowEpochSeconds = { now },
    )

    @Test fun acceptsValidShortLivedLeaseBoundToSubject() {
        val result = verifier.verify(signedToken(), SUBJECT)

        assertTrue(result is EntitlementLeaseVerification.Accepted)
        assertEquals(
            EntitlementTier.PLUS,
            (result as EntitlementLeaseVerification.Accepted).lease.tier,
        )
    }

    @Test fun rejectsLeaseCopiedFromAnotherAccount() {
        val result = verifier.verify(signedToken(), "account_hash_other")

        assertRejected(result, EntitlementLeaseFailure.WRONG_SUBJECT)
    }

    @Test fun rejectsExpiredLease() {
        val result = verifier.verify(
            signedToken(issuedAt = now - 10_000, expiresAt = now - 1),
            SUBJECT,
        )

        assertRejected(result, EntitlementLeaseFailure.INVALID_TIME_WINDOW)
    }

    @Test fun rejectsPayloadChangedAfterServerSignature() {
        val originalPayload = payload(tier = "PLUS")
        val forgedPayload = payload(tier = "FAMILY")
        val forgedToken = "${encode(forgedPayload.toByteArray())}.${encode(sign(originalPayload.toByteArray()))}"

        assertRejected(verifier.verify(forgedToken, SUBJECT), EntitlementLeaseFailure.INVALID_SIGNATURE)
    }

    @Test fun rejectsOversizedUntrustedInputBeforeDecoding() {
        val oversized = "A".repeat(4_097)

        assertRejected(verifier.verify(oversized, SUBJECT), EntitlementLeaseFailure.MALFORMED)
    }

    private fun signedToken(
        issuedAt: Long = now - 60,
        expiresAt: Long = now + 7 * 24 * 60 * 60,
    ): String {
        val payload = payload(issuedAt = issuedAt, expiresAt = expiresAt).toByteArray()
        return "${encode(payload)}.${encode(sign(payload))}"
    }

    private fun payload(
        tier: String = "PLUS",
        issuedAt: Long = now - 60,
        expiresAt: Long = now + 7 * 24 * 60 * 60,
    ) = "1|app.recibozen.android|$SUBJECT|$tier|$issuedAt|$expiresAt|1|lease_123"

    private fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyPair.private)
        update(payload)
        sign()
    }

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun assertRejected(result: EntitlementLeaseVerification, expected: EntitlementLeaseFailure) {
        assertTrue(result is EntitlementLeaseVerification.Rejected)
        assertEquals(expected, (result as EntitlementLeaseVerification.Rejected).failure)
    }

    private companion object {
        const val SUBJECT = "account_hash_123"
    }
}
