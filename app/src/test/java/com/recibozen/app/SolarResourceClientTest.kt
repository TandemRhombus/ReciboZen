package com.recibozen.app

import com.recibozen.app.core.solar.parseBackendSolarResource
import com.recibozen.app.core.solar.parseNasaPowerSolarResource
import com.recibozen.app.core.solar.formatSolarCoordinate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SolarResourceClientTest {
    @Test
    fun coordinatesAreFormattedAsRealUrlNumbersAndNeverAsFormatTokens() {
        assertEquals("19.4326", formatSolarCoordinate(19.432608))
        assertEquals("-99.13", formatSolarCoordinate(-99.1332, decimals = 2))
    }

    @Test
    fun parsesNormalizedBackendPayload() {
        val json = JSONObject()
            .put("latitude", 19.43)
            .put("longitude", -99.13)
            .put("monthlyDailyAverage", JSONArray(List(12) { 5.0 }))
            .put("source", JSONObject().put("name", "NASA POWER"))

        val resource = parseBackendSolarResource(json)

        assertEquals(12, resource.monthlyDailyAverage.size)
        assertEquals("NASA POWER", resource.sourceName)
    }

    @Test
    fun parsesDirectNasaPowerPayload() {
        val values = JSONObject().apply {
            listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
                .forEachIndexed { index, month -> put(month, 4.5 + index / 10.0) }
            put("ANN", 5.05)
        }
        val json = JSONObject().put(
            "properties",
            JSONObject().put("parameter", JSONObject().put("ALLSKY_SFC_SW_DWN", values)),
        )

        val resource = parseNasaPowerSolarResource(json, 19.43, -99.13)

        assertEquals(12, resource.monthlyDailyAverage.size)
        assertEquals("NASA POWER · HTTPS directo", resource.sourceName)
    }
}
