package app.recibozen.android.core.model

data class ElectricServiceIdentity(
    val id: String = "",
    val alias: String,
    val serviceNumber: String = "",
    val rmu: String = "",
    val meterNumber: String = "",
    val tariffCode: String = "",
    val billingCycle: String = "",
    val isActive: Boolean = true,
)

enum class EntitlementTier { FREE, PLUS, FAMILY }

object EntitlementPolicy {
    fun activeServiceLimit(tier: EntitlementTier): Int = when (tier) {
        EntitlementTier.FREE -> 1
        EntitlementTier.PLUS -> 2
        EntitlementTier.FAMILY -> 5
    }
}

data class ServiceCreationResult(
    val accepted: Boolean,
    val serviceId: String? = null,
    val requiredTier: EntitlementTier? = null,
)

object ElectricServiceValidator {
    const val SERVICE_NUMBER_LENGTH = 12

    fun normalizeServiceNumber(raw: String): String = raw.filter(Char::isDigit).take(SERVICE_NUMBER_LENGTH)

    fun isValidServiceNumber(raw: String): Boolean {
        val normalized = normalizeServiceNumber(raw)
        return raw.isBlank() || normalized.length == SERVICE_NUMBER_LENGTH
    }

    fun normalizeRmu(raw: String): String = raw
        .uppercase()
        .filter { it.isLetterOrDigit() }
        .take(27)

    fun normalizeMeterNumber(raw: String): String = raw
        .uppercase()
        .filter { it.isLetterOrDigit() || it == '-' }
        .take(32)
}
