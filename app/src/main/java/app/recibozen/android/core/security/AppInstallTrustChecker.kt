package app.recibozen.android.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import app.recibozen.android.BuildConfig
import java.security.MessageDigest
import java.util.Locale

data class AppInstallTrust(
    val releaseCertificateConfigured: Boolean,
    val signingCertificateAccepted: Boolean,
    val officialInstallerAccepted: Boolean,
    val debuggerDetected: Boolean,
    val premiumEnvironmentAccepted: Boolean,
)

/**
 * Local tamper signal used only as defense in depth. A determined attacker can
 * patch client bytecode, so the backend must still validate Play Integrity and
 * the purchase before it issues any premium entitlement.
 */
class AppInstallTrustChecker(private val context: Context) {
    fun evaluate(): AppInstallTrust {
        val allowedCertificates = BuildConfig.EXPECTED_RELEASE_CERT_SHA256
            .split(',', ';')
            .map(::normalizeFingerprint)
            .filter { it.length == SHA_256_HEX_LENGTH }
            .toSet()
        val installedCertificates = currentSigningCertificates()
        val certificateConfigured = allowedCertificates.isNotEmpty()
        val certificateAccepted = !BuildConfig.PREMIUM_SECURITY_ENFORCED ||
            (certificateConfigured && installedCertificates.isNotEmpty() &&
                installedCertificates.all(allowedCertificates::contains))
        val installerAccepted = !BuildConfig.OFFICIAL_INSTALL_REQUIRED ||
            installSourcePackage() == PLAY_STORE_PACKAGE
        val debuggerDetected = Debug.isDebuggerConnected() ||
            Debug.waitingForDebugger() ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val environmentAccepted = if (BuildConfig.PREMIUM_SECURITY_ENFORCED) {
            certificateAccepted && installerAccepted && !debuggerDetected
        } else {
            true
        }
        return AppInstallTrust(
            releaseCertificateConfigured = certificateConfigured,
            signingCertificateAccepted = certificateAccepted,
            officialInstallerAccepted = installerAccepted,
            debuggerDetected = debuggerDetected,
            premiumEnvironmentAccepted = environmentAccepted,
        )
    }

    private fun currentSigningCertificates(): Set<String> = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        packageInfo.signingInfo
            ?.apkContentsSigners
            .orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            }
            .toSet()
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION")
    private fun installSourcePackage(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private fun normalizeFingerprint(value: String): String = value
        .replace(":", "")
        .replace(" ", "")
        .uppercase(Locale.ROOT)

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        const val SHA_256_HEX_LENGTH = 64
    }
}
