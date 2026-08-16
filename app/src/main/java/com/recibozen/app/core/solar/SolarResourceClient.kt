package com.recibozen.app.core.solar

import com.recibozen.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SolarResource(
    val latitude: Double,
    val longitude: Double,
    val monthlyDailyAverage: List<Double>,
    val sourceName: String,
)

class SolarResourceClient {
    suspend fun load(latitude: Double, longitude: Double): SolarResource = withContext(Dispatchers.IO) {
        val lat = String.format(java.util.Locale.US, "%.4f", latitude)
        val lon = String.format(java.util.Locale.US, "%.4f", longitude)
        val connection = (URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/v1/solar/resource/$lat/$lon")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != 200) error("Solar resource HTTP ${connection.responseCode}")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "Solar resource response is too large" }
                    output.write(buffer, 0, count)
                }
            }
            val json = JSONObject(output.toString(Charsets.UTF_8.name()))
            val values = json.getJSONArray("monthlyDailyAverage")
            require(values.length() == 12)
            SolarResource(
                latitude = json.getDouble("latitude"),
                longitude = json.getDouble("longitude"),
                monthlyDailyAverage = List(12) { values.getDouble(it) },
                sourceName = json.getJSONObject("source").getString("name"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256_000
    }
}
