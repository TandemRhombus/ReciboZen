package app.recibozen.android.core.ai

import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import org.json.JSONObject

/**
 * What is left of a monthly allowance after a call, as the Worker reports it.
 * Shown to the household so the meter is never a surprise.
 */
data class AiQuota(
    val operation: String,
    val used: Int,
    val limit: Int,
    val resetsAtEpochSeconds: Long,
)

/**
 * An appliance the model was asked about.
 *
 * [evidence] arrives already shaped for [DeviceEvidence], which is the whole
 * point: an AI answer enters the inventory through the same door as a NOM label
 * or a metered reading, and loses to both by construction.
 */
data class ApplianceEnergySuggestion(
    val recognized: Boolean,
    val displayName: String,
    val assumption: String,
    val marketMexicoVerified: Boolean?,
    val evidence: List<DeviceEvidence>,
    val quota: AiQuota?,
)

/** One thing the household can change, with the figure supplied by the server's table. */
data class SavingsAction(
    val applianceIndex: Int,
    val kind: SavingsActionKind,
    val title: String,
    val howTo: String,
    val estimatedReductionPercent: Double,
    val effort: SavingsEffort,
    val comfortImpact: ComfortImpact,
    /** Null when the action is not about a thermostat. */
    val targetSetting: TargetSetting?,
)

data class TargetSetting(val text: String, val isSafetyLimit: Boolean)

/** A setting is changed once and keeps paying; a habit is re-decided every day. */
enum class SavingsActionKind { SETTING, MAINTENANCE, HABIT }

enum class SavingsEffort { ONE_TIME, DAILY }

enum class ComfortImpact { NONE, MINOR, NOTICEABLE }

data class SavingsPlan(
    val summary: String,
    val actions: List<SavingsAction>,
    val quota: AiQuota?,
)

/**
 * Parses the Worker's answers.
 *
 * Every field is read explicitly and anything unrecognised is dropped rather
 * than guessed. The Worker already rebuilds its own output field by field, so
 * this is the second pass — deliberately. The Worker is ours today; the network
 * between it and this phone never is, and a client that trusts whatever arrives
 * has no way to notice the day that stops being true.
 */
object AiResponses {

    fun parseApplianceEnergy(body: JSONObject, todayEpochDay: Long): ApplianceEnergySuggestion? {
        val displayName = body.optString("displayName").takeIf { it.isNotBlank() } ?: return null
        val rawEvidence = body.optJSONArray("evidence") ?: return null
        val evidence = (0 until rawEvidence.length())
            .mapNotNull { rawEvidence.optJSONObject(it) }
            .mapNotNull { parseEvidence(it, todayEpochDay) }
        if (evidence.isEmpty()) return null

        return ApplianceEnergySuggestion(
            recognized = body.optBoolean("recognized", false),
            displayName = displayName,
            assumption = body.optString("assumption"),
            marketMexicoVerified = body.optBooleanOrNull("marketMexicoVerified"),
            evidence = evidence,
            quota = parseQuota(body.optJSONObject("quota")),
        )
    }

    private fun parseEvidence(entry: JSONObject, todayEpochDay: Long): DeviceEvidence? {
        val metric = enumOrNull<EvidenceMetric>(entry.optString("metric")) ?: return null
        val methodology = enumOrNull<EvidenceMethodology>(entry.optString("methodology")) ?: return null
        // Only the two a model can honestly claim. NOM_LABEL-grade methodologies
        // are refused here even if the server sends them.
        if (methodology != EvidenceMethodology.MODELED && methodology != EvidenceMethodology.NAMEPLATE) return null
        val value = entry.optDouble("value", Double.NaN)
        if (!value.isFinite() || value <= 0.0) return null

        val low = entry.optDouble("rangeMinValue", Double.NaN)
        val high = entry.optDouble("rangeMaxValue", Double.NaN)
        val hasUsableRange = low.isFinite() && high.isFinite() && low > 0.0 && low <= value && value <= high

        return DeviceEvidence(
            metric = metric,
            value = value,
            // Stamped here, never read from the response. If the field ever
            // arrived saying NOM_LABEL, believing it would let a guess outrank a
            // measurement the household took with its own hands.
            source = EvidenceSource.AI_SUGGESTION,
            methodology = methodology,
            retrievedAtEpochDay = todayEpochDay,
            rangeMinValue = low.takeIf { hasUsableRange },
            rangeMaxValue = high.takeIf { hasUsableRange },
            sourceLabel = null,
            sourceUrl = null,
            marketMexicoVerified = entry.optBooleanOrNull("marketMexicoVerified"),
        )
    }

    fun parseSavingsPlan(body: JSONObject, applianceCount: Int): SavingsPlan? {
        val summary = body.optString("summary").takeIf { it.isNotBlank() } ?: return null
        val raw = body.optJSONArray("recommendations") ?: return null
        val actions = (0 until raw.length())
            .mapNotNull { raw.optJSONObject(it) }
            .mapNotNull { parseAction(it, applianceCount) }
        if (actions.isEmpty()) return null
        return SavingsPlan(summary, actions, parseQuota(body.optJSONObject("quota")))
    }

    private fun parseAction(entry: JSONObject, applianceCount: Int): SavingsAction? {
        val index = entry.optInt("applianceIndex", -1)
        // An action pointing at an appliance we did not send cannot be shown
        // against anything, and guessing which one it meant would be worse.
        if (index < 0 || index >= applianceCount) return null
        val kind = enumOrNull<SavingsActionKind>(entry.optString("kind")) ?: return null
        val effort = enumOrNull<SavingsEffort>(entry.optString("effort")) ?: return null
        val comfort = enumOrNull<ComfortImpact>(entry.optString("comfortImpact")) ?: return null
        val title = entry.optString("title").takeIf { it.isNotBlank() } ?: return null
        val howTo = entry.optString("howTo").takeIf { it.isNotBlank() } ?: return null
        val percent = entry.optDouble("estimatedReductionPercent", Double.NaN)
        if (!percent.isFinite() || percent < MIN_REDUCTION_PERCENT || percent > MAX_REDUCTION_PERCENT) return null

        return SavingsAction(
            applianceIndex = index,
            kind = kind,
            title = title,
            howTo = howTo,
            estimatedReductionPercent = percent,
            effort = effort,
            comfortImpact = comfort,
            targetSetting = entry.optJSONObject("target")?.let { target ->
                val text = target.optString("text").takeIf { it.isNotBlank() } ?: return@let null
                TargetSetting(text = text, isSafetyLimit = target.optBoolean("safetyLimit", false))
            },
        )
    }

    private fun parseQuota(quota: JSONObject?): AiQuota? {
        if (quota == null) return null
        val limit = quota.optInt("limit", 0)
        if (limit <= 0) return null
        return AiQuota(
            operation = quota.optString("operation"),
            used = quota.optInt("used", 0),
            limit = limit,
            resetsAtEpochSeconds = quota.optLong("resetsAtEpochSeconds", 0L),
        )
    }

    /** The categories the Worker accepts, which are this app's own. */
    fun categoryName(category: ApplianceCategory): String = category.name

    private const val MIN_REDUCTION_PERCENT = 1.0
    private const val MAX_REDUCTION_PERCENT = 60.0
}

private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
    runCatching { enumValueOf<T>(value) }.getOrNull()

/** [JSONObject.optBoolean] cannot tell false from absent, and here that matters. */
private fun JSONObject.optBooleanOrNull(name: String): Boolean? =
    if (!has(name) || isNull(name)) null else optBoolean(name)
