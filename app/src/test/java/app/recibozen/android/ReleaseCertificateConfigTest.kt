package app.recibozen.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Play App Signing fingerprints in gradle.properties.
 *
 * [app.recibozen.android.core.security.AppInstallTrustChecker] drops any entry
 * that is not exactly 64 hex characters after normalisation, and then requires
 * that *every* certificate the installed APK carries is in what survives. A
 * single typo therefore does not fail loudly: it silently shrinks the allowed
 * set, and the release build rejects its own premium tier on real devices with
 * nothing in the logs to explain why.
 *
 * Play signs this app with two certificates -- the classic key and the
 * post-quantum one -- so both must survive. These are public certificate
 * fingerprints, not secrets.
 */
class ReleaseCertificateConfigTest {

    @Test
    fun bothPlayAppSigningFingerprintsSurviveTheSameNormalisationTheAppApplies() {
        val accepted = BuildConfig.EXPECTED_RELEASE_CERT_SHA256
            .split(',', ';')
            .map(::normalize)
            .filter { it.length == SHA_256_HEX_LENGTH }

        assertEquals(
            "gradle.properties must list exactly the two Play App Signing SHA-256 fingerprints",
            2,
            accepted.size,
        )
        assertTrue(
            "every fingerprint must be uppercase hexadecimal",
            accepted.all { fingerprint -> fingerprint.all { it in '0'..'9' || it in 'A'..'F' } },
        )
        assertEquals("the two fingerprints must be different", 2, accepted.toSet().size)
    }

    /** Mirrors `AppInstallTrustChecker.normalizeFingerprint`. */
    private fun normalize(value: String): String = value
        .replace(":", "")
        .replace(" ", "")
        .uppercase()

    private companion object {
        const val SHA_256_HEX_LENGTH = 64
    }
}
