package app.recibozen.android.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Resolves the device's city name entirely on-device via [Geocoder] — no API
 * key, no network call we control, no third-party service.
 *
 * Deliberately city-only: the CFE tariff code is assigned per *locality* by
 * SHCP from Conagua temperature records, so it cannot be derived reliably from
 * a city name. Guessing it here would silently mis-bill the user, so the tariff
 * stays an explicit choice.
 */
class CityLocator(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns the detected city, or null if unavailable. Never throws. */
    suspend fun detectCity(): String? = runCatching {
        if (!hasPermission()) return null
        if (!Geocoder.isPresent()) return null

        val location = lastKnownLocation() ?: return null
        val geocoder = Geocoder(context, mexicoSpanishLocale())
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(
                    location.first,
                    location.second,
                    1,
                    singleAddressListener { address ->
                        if (continuation.isActive) continuation.resume(address)
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.first, location.second, 1)?.firstOrNull()
            }
        }
        address?.cityName()
    }.getOrNull()

    /** Forward-geocodes the editable city/state text for solar-resource lookup. */
    suspend fun coordinatesFor(place: String): Pair<Double, Double>? = runCatching {
        if (place.isBlank() || !Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, mexicoSpanishLocale())
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(
                    "$place, México",
                    1,
                    singleAddressListener { address ->
                        if (continuation.isActive) continuation.resume(address)
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName("$place, México", 1)?.firstOrNull()
            }
        }
        address?.let { it.latitude to it.longitude }
    }.getOrNull()

    /**
     * Coarse last-known fix. Avoids pulling in play-services-location for a
     * one-shot, low-stakes autofill the user can overwrite anyway.
     */
    private fun lastKnownLocation(): Pair<Double, Double>? {
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!coarseGranted && !fineGranted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        return providers.firstNotNullOfOrNull { provider ->
            runCatching {
                if (!manager.isProviderEnabled(provider)) null
                else manager.getLastKnownLocation(provider)?.let { it.latitude to it.longitude }
            }.getOrNull()
        }
    }

    private fun Address.cityName(): String? =
        locality?.takeIf { it.isNotBlank() }
            ?: subAdminArea?.takeIf { it.isNotBlank() }
            ?: adminArea?.takeIf { it.isNotBlank() }

    private fun mexicoSpanishLocale(): Locale = Locale.Builder()
        .setLanguage("es")
        .setRegion("MX")
        .build()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun singleAddressListener(onResult: (Address?) -> Unit): Geocoder.GeocodeListener =
        object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) = onResult(addresses.firstOrNull())

            override fun onError(errorMessage: String?) = onResult(null)
        }
}
