package com.recibozen.app.core.tariff

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.recibozen.app.BuildConfig
import com.recibozen.app.data.local.ReciboZenDatabase
import com.recibozen.app.data.local.ReferenceDatasetEntity
import com.recibozen.app.data.local.TariffRateReferenceEntity
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TariffSyncWorker(appContext: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val payload = downloadCatalog()
            persist(payload)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )
    }

    private fun downloadCatalog(): JSONObject {
        val connection = (URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/v1/tariffs/current")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) error("Tariff catalog HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) error("Tariff catalog is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_RESPONSE_BYTES) error("Tariff catalog is too large")
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun persist(root: JSONObject) {
        require(root.optInt("schemaVersion") == 1)
        val effective = root.getString("effectiveYearMonth").also { require(YEAR_MONTH.matches(it)) }
        val sha256 = root.getString("sha256").also { require(SHA256.matches(it)) }
        val datasetId = "cfe-tariffs-$effective"
        val sourceUrl = root.optJSONObject("source")?.optString("url").orEmpty()
        val rows = buildList {
            val domestic = root.getJSONArray("domestic")
            repeat(domestic.length()) { index ->
                val item = domestic.getJSONObject(index)
                val code = item.getString("tariffCode")
                val region = item.optInt("summerStartMonth", 0).takeIf { it > 0 }?.let { "SUMMER_START_$it" }
                val season = item.optString("season", "NONE")
                val blocks = item.getJSONArray("blocks")
                repeat(blocks.length()) { blockIndex ->
                    val block = blocks.getJSONObject(blockIndex)
                    add(
                        TariffRateReferenceEntity(
                            id = "$datasetId-$code-${region ?: "NONE"}-$season-$blockIndex",
                            datasetId = datasetId,
                            tariffCode = code,
                            regionCode = region,
                            effectiveYearMonth = effective,
                            season = season,
                            blockOrder = blockIndex,
                            blockLabel = block.getString("label"),
                            limitKwh = block.optInt("limitKwh").takeIf { !block.isNull("limitKwh") },
                            rateMxnPerKwh = block.getDouble("rateMxnPerKwh"),
                            sourceUrl = item.optString("sourceUrl", sourceUrl),
                        ),
                    )
                }
            }
            val dac = root.getJSONArray("dac")
            repeat(dac.length()) { index ->
                val item = dac.getJSONObject(index)
                val region = item.getString("region")
                listOf(
                    "SUMMER" to item.getDouble("summerRateMxnPerKwh"),
                    "OUT_OF_SUMMER" to item.getDouble("outOfSummerRateMxnPerKwh"),
                ).forEachIndexed { order, (season, rate) ->
                    add(
                        TariffRateReferenceEntity(
                            id = "$datasetId-DAC-$region-$season",
                            datasetId = datasetId,
                            tariffCode = "DAC",
                            regionCode = region,
                            effectiveYearMonth = effective,
                            season = season,
                            blockOrder = order,
                            blockLabel = "Energía",
                            limitKwh = null,
                            rateMxnPerKwh = rate,
                            fixedChargeMxn = item.getDouble("fixedChargeMxn"),
                            sourceUrl = item.optString("sourceUrl", sourceUrl),
                        ),
                    )
                }
            }
        }
        require(rows.isNotEmpty())
        val database = ReciboZenDatabase.get(applicationContext)
        database.withTransaction {
            database.dao().upsertDataset(
                ReferenceDatasetEntity(
                    id = datasetId,
                    kind = "CFE_TARIFFS",
                    version = effective,
                    sourceName = "CFE",
                    sha256 = sha256,
                    schemaVersion = 1,
                    rowCount = rows.size,
                    importedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            database.dao().insertTariffReferences(rows)
        }
    }

    companion object {
        private const val UNIQUE_WORK = "recibozen-cfe-tariff-sync"
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private val YEAR_MONTH = Regex("\\d{4}-\\d{2}")
        private val SHA256 = Regex("[a-f0-9]{64}")

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TariffSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
