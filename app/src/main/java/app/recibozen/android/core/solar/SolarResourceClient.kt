package app.recibozen.android.core.solar

import app.recibozen.android.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SolarResource(
    val latitude: Double,
    val longitude: Double,
    val monthlyDailyAverage: List<Double>,
    val sourceName: String,
)

/**
 * Loads solar climatology through ReciboZen's HTTPS backend. The direct NASA
 * HTTPS route is a temporary availability fallback while older backend
 * deployments are being rolled forward; cleartext HTTP is never permitted.
 */
class SolarResourceClient {
    suspend fun load(latitude: Double, longitude: Double): SolarResource = withContext(Dispatchers.IO) {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "La latitud solar no es válida." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "La longitud solar no es válida." }
        val lat = formatSolarCoordinate(latitude)
        val lon = formatSolarCoordinate(longitude)
        val backendUrl = URL("${BuildConfig.API_BASE_URL.trimEnd('/')}/v1/solar/resource/$lat/$lon")
        require(backendUrl.protocol.equals("https", ignoreCase = true)) { "La API solar requiere HTTPS." }

        runCatching { parseBackendSolarResource(downloadJson(backendUrl)) }
            .getOrElse {
                // NASA POWER climatology is public and official. Round to
                // roughly 1 km before sending coordinates directly.
                val nasaLat = formatSolarCoordinate(latitude, decimals = 2)
                val nasaLon = formatSolarCoordinate(longitude, decimals = 2)
                val nasaUrl = URL(
                    "$NASA_POWER_URL?parameters=ALLSKY_SFC_SW_DWN&community=RE" +
                        "&longitude=$nasaLon&latitude=$nasaLat&format=JSON",
                )
                parseNasaPowerSolarResource(downloadJson(nasaUrl), nasaLat.toDouble(), nasaLon.toDouble())
            }
    }

    private fun downloadJson(url: URL): JSONObject {
        require(url.protocol.equals("https", ignoreCase = true)) { "La consulta solar requiere HTTPS." }
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val status = connection.responseCode
            if (status != 200) error("El servicio solar respondió con código $status.")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "La respuesta solar es demasiado grande." }
                    output.write(buffer, 0, count)
                }
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256_000
        const val NASA_POWER_URL = "https://power.larc.nasa.gov/api/temporal/climatology/point"
    }
}

internal fun formatSolarCoordinate(value: Double, decimals: Int = 4): String {
    require(value.isFinite())
    require(decimals in 0..6)
    return String.format(Locale.US, "%.${decimals}f", value)
}

internal fun parseBackendSolarResource(json: JSONObject): SolarResource {
    val values = json.getJSONArray("monthlyDailyAverage")
    require(values.length() == 12)
    val monthly = List(12) { values.getDouble(it) }
    require(monthly.all { it > 0.0 && it.isFinite() })
    return SolarResource(
        latitude = json.getDouble("latitude"),
        longitude = json.getDouble("longitude"),
        monthlyDailyAverage = monthly,
        sourceName = json.getJSONObject("source").getString("name"),
    )
}

internal fun parseNasaPowerSolarResource(json: JSONObject, latitude: Double, longitude: Double): SolarResource {
    val parameter = json.getJSONObject("properties")
        .getJSONObject("parameter")
        .getJSONObject("ALLSKY_SFC_SW_DWN")
    val monthly = NASA_MONTH_KEYS.map(parameter::getDouble)
    require(monthly.all { it > 0.0 && it.isFinite() })
    return SolarResource(
        latitude = latitude,
        longitude = longitude,
        monthlyDailyAverage = monthly,
        sourceName = "NASA POWER · HTTPS directo",
    )
}

private val NASA_MONTH_KEYS = listOf(
    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC",
)
