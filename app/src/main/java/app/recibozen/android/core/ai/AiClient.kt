package app.recibozen.android.core.ai

import app.recibozen.android.BuildConfig
import app.recibozen.android.core.model.ApplianceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every way a call to the AI Worker can end, as something the UI can act on.
 *
 * The Worker distinguishes these deliberately, so collapsing them back into one
 * error would throw away the only thing that tells the household whether to
 * wait, to correct what they typed, or to stop trying this month.
 */
sealed interface AiOutcome<out T> {
    data class Success<T>(val value: T) : AiOutcome<T>

    /** Premium is not active, or the lease expired. Send them to the paywall. */
    data object NotEntitled : AiOutcome<Nothing>

    /** Monthly allowance spent. [resetsAtEpochSeconds] is when it returns. */
    data class QuotaExhausted(val used: Int, val limit: Int, val resetsAtEpochSeconds: Long) : AiOutcome<Nothing>

    /** Too fast. Wait [retryAfterSeconds] and it works again. */
    data class TooFast(val retryAfterSeconds: Int) : AiOutcome<Nothing>

    /** The model answered but knew nothing useful. Usually a wrong model number. */
    data object CouldNotEstimate : AiOutcome<Nothing>

    /** What we sent was rejected before any allowance was spent. A bug on our side. */
    data class InvalidRequest(val field: String?) : AiOutcome<Nothing>

    /** AI is off: unconfigured, or the monthly spending ceiling was reached. */
    data object TemporarilyUnavailable : AiOutcome<Nothing>

    /** Network, timeout, or an answer we could not read. Retryable. */
    data object Unavailable : AiOutcome<Nothing>
}

/**
 * Talks to the AI Worker.
 *
 * Note what this class cannot do: it cannot send a prompt. It sends brand,
 * model and category, or an inventory in kWh, and nothing else. The prompt, the
 * model and the output schema all live on the server, which is what stops this
 * from becoming a text box that spends the developer's money.
 */
class AiClient(
    private val baseUrl: String = BuildConfig.AI_BASE_URL,
    private val signedLease: () -> String?,
    private val nowEpochDay: () -> Long = { System.currentTimeMillis() / MILLIS_PER_DAY },
) {

    suspend fun identifyAppliance(
        brand: String,
        model: String,
        category: ApplianceCategory,
    ): AiOutcome<ApplianceEnergySuggestion> = post(
        path = "identify-appliance",
        payload = JSONObject()
            .put("brand", brand)
            .put("model", model)
            .put("category", AiResponses.categoryName(category)),
    ) { body -> AiResponses.parseApplianceEnergy(body, nowEpochDay()) }

    /**
     * [appliances] must already carry consumption computed by our own engine,
     * highest first, and the server never receives a peso: it answers in
     * percentages of one appliance's consumption and TariffEngine turns those
     * into money.
     */
    suspend fun suggestSavings(
        appliances: List<AiApplianceInput>,
        periodDays: Int,
        kwhProjected: Double,
        dacRisk: AiDacRisk,
    ): AiOutcome<SavingsPlan> {
        if (appliances.isEmpty()) return AiOutcome.InvalidRequest("appliances")
        val array = JSONArray()
        for (appliance in appliances.take(MAX_APPLIANCES)) {
            array.put(
                JSONObject()
                    .put("name", appliance.name)
                    .put("category", AiResponses.categoryName(appliance.category))
                    .put("kwhPeriod", appliance.kwhPeriod)
                    .apply {
                        appliance.brand?.let { put("brand", it) }
                        appliance.model?.let { put("model", it) }
                    },
            )
        }
        val sent = minOf(appliances.size, MAX_APPLIANCES)
        return post(
            path = "suggest-savings",
            payload = JSONObject()
                .put("appliances", array)
                .put("periodDays", periodDays)
                .put("kwhProjected", kwhProjected)
                .put("dacRisk", dacRisk.name),
        ) { body -> AiResponses.parseSavingsPlan(body, sent) }
    }

    private suspend fun <T> post(
        path: String,
        payload: JSONObject,
        parse: (JSONObject) -> T?,
    ): AiOutcome<T> = withContext(Dispatchers.IO) {
        val lease = signedLease()
        // Checked before opening a socket: without a lease the Worker would
        // answer 401 anyway, and the household deserves the paywall rather than
        // a spinner.
        if (lease.isNullOrBlank()) return@withContext AiOutcome.NotEntitled

        val connection = runCatching {
            (URL("${baseUrl.trimEnd('/')}/v1/ai/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $lease")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        }.getOrElse { return@withContext AiOutcome.Unavailable }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderFieldInt("Retry-After", 0)
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            val body = runCatching { JSONObject(text.orEmpty()) }.getOrNull()

            if (status in 200..299) {
                val parsed = body?.let(parse) ?: return@withContext AiOutcome.CouldNotEstimate
                AiOutcome.Success(parsed)
            } else {
                outcomeFor(status, body, retryAfter)
            }
        } catch (_: Exception) {
            AiOutcome.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    private fun outcomeFor(status: Int, body: JSONObject?, retryAfterHeader: Int): AiOutcome<Nothing> {
        val error = body?.optString("error").orEmpty()
        return when {
            status == 401 || status == 403 -> AiOutcome.NotEntitled
            status == 422 -> AiOutcome.CouldNotEstimate
            status == 400 -> AiOutcome.InvalidRequest(body?.optString("field")?.takeIf { it.isNotBlank() })
            status == 429 && error == "rate_limited" -> AiOutcome.TooFast(
                // Trust the header first: it is the standard channel and the
                // one a proxy in between is allowed to rewrite.
                retryAfterSeconds = retryAfterHeader.takeIf { it > 0 }
                    ?: body?.optInt("retryAfterSeconds", DEFAULT_RETRY_SECONDS)
                    ?: DEFAULT_RETRY_SECONDS,
            )
            status == 429 -> AiOutcome.QuotaExhausted(
                used = body?.optInt("used", 0) ?: 0,
                limit = body?.optInt("limit", 0) ?: 0,
                resetsAtEpochSeconds = body?.optLong("resetsAtEpochSeconds", 0L) ?: 0L,
            )
            status == 501 || status == 503 -> AiOutcome.TemporarilyUnavailable
            else -> AiOutcome.Unavailable
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val DEFAULT_RETRY_SECONDS = 60
        const val MAX_APPLIANCES = 12
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** One appliance as the savings intent expects it: consumption already computed. */
data class AiApplianceInput(
    val name: String,
    val category: ApplianceCategory,
    val kwhPeriod: Double,
    val brand: String? = null,
    val model: String? = null,
)

/** Mirrors the DAC risk the app already knows, so the advice can be urgent when it should be. */
enum class AiDacRisk { NONE, APPROACHING, AT_RISK, IN_DAC }
