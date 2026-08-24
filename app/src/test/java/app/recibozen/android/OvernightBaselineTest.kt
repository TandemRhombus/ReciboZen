package app.recibozen.android

import app.recibozen.android.core.calculation.MeterRegister
import app.recibozen.android.core.calculation.OvernightBaselineCalculator
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Prueba nocturna" used to be a button that led nowhere: the card always said
 * there was no saved result because nothing ever computed one.
 */
class OvernightBaselineTest {
    private val zone: ZoneId = ZoneId.of("America/Mexico_City")

    private fun reading(day: LocalDate, hour: Int, kwh: Int, method: ReadingMethod = ReadingMethod.MANUAL) =
        HistoryReadingEntry(
            kwh = kwh,
            dateLabel = "$day ${hour}h",
            method = method,
            note = "",
            timestampEpochMillis = LocalDateTime.of(day, java.time.LocalTime.of(hour, 0))
                .atZone(zone).toInstant().toEpochMilli(),
        )

    /** A fridge billed by period energy: 60 kWh over 30 days ≈ 83 W around the clock. */
    private val fridge = Appliance(
        id = "fridge",
        name = "Refrigerador",
        room = "Cocina",
        costMxn = 0,
        kwh = 60,
        sharePercent = 0,
        hoursPerWeek = null,
        confidence = ConfidenceLevel.HIGH,
        usageModel = ApplianceUsageModel.PERIOD_KWH,
        unitKwh = 60.0,
        referencePeriodDays = 30,
    )

    /** A TV used 28 h a week that the user leaves in standby at 10 W. */
    private val television = Appliance(
        id = "tv",
        name = "Televisión",
        room = "Sala",
        costMxn = 0,
        kwh = 30,
        sharePercent = 0,
        hoursPerWeek = 28,
        confidence = ConfidenceLevel.MEDIUM,
        usageModel = ApplianceUsageModel.POWER_SCHEDULE,
        powerWatts = 120,
        standbyWatts = 10,
        standbyEnabled = true,
        unitKwh = 30.0,
        referencePeriodDays = 30,
    )

    @Test
    fun theBaseLoadIsTheAlwaysOnPowerPlusTheDeclaredStandby() {
        // 60 kWh / 30 días / 24 h = 83.3 W del refrigerador, más 10 W de espera.
        assertEquals(93.3, OvernightBaselineCalculator.baseWatts(listOf(fridge, television)), 0.2)
    }

    /** A five-dial register, i.e. the household that wraps at 99,999. */
    private val fiveDial = MeterRegister(capacityKwh = 100_000, multiplier = 1)

    @Test
    fun aScheduledApplianceWithoutDeclaredStandbyAddsNothingOvernight() {
        val noStandby = television.copy(standbyEnabled = false, standbyWatts = null)
        assertEquals(83.3, OvernightBaselineCalculator.baseWatts(listOf(fridge, noStandby)), 0.2)
    }

    @Test
    fun aNightWithMoreConsumptionThanTheInventoryExplainsIsFlagged() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(
            reading(day, 23, 10_000),
            // 8 horas después el medidor subió 2 kWh: 250 W constantes.
            reading(day.plusDays(1), 7, 10_002),
        )

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge, television), fiveDial, zone)

        assertNotNull(result)
        assertEquals(8.0, result!!.hours, 0.01)
        assertEquals(2.0, result.observedKwh, 0.01)
        assertEquals(250.0, result.observedWatts, 1.0)
        // 93.3 W × 8 h = 0.75 kWh justificados.
        assertEquals(0.75, result.expectedKwh, 0.02)
        assertTrue("debe quedar consumo sin explicar", result.unexplainedKwh > 1.2)
    }

    @Test
    fun aNightThatMatchesTheInventoryReportsNothingUnexplained() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(
            reading(day, 22, 10_000),
            // 93 W × 9 h ≈ 0.84 kWh, que redondeado al kWh del medidor es 1.
            reading(day.plusDays(1), 7, 10_001),
        )

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge, television), fiveDial, zone)

        assertNotNull(result)
        assertTrue("el sobrante debe ser pequeño", result!!.unexplainedKwh < 0.3)
    }

    @Test
    fun withoutAnInventoryThereIsNothingToCompareAgainst() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 23, 10_000), reading(day.plusDays(1), 7, 10_002))

        assertNull(OvernightBaselineCalculator.evaluate(readings, emptyList(), fiveDial, zone))
    }

    @Test
    fun twoDaytimeReadingsAreNotAnOvernightTest() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 9, 10_000), reading(day, 17, 10_002))

        assertNull(OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fiveDial, zone))
    }

    @Test
    fun readingsImportedFromAPdfAreNeverUsedAsAnOvernightPair() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(
            reading(day, 23, 10_000, ReadingMethod.PDF),
            reading(day.plusDays(1), 7, 10_002, ReadingMethod.PDF),
        )

        assertNull(OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fiveDial, zone))
    }

    @Test
    fun aMeterThatWentBackwardsIsIgnoredInsteadOfReportingNegativeConsumption() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 23, 10_005), reading(day.plusDays(1), 7, 10_000))

        assertNull(OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fiveDial, zone))
    }

    @Test
    fun theMostRecentNightWins() {
        val first = LocalDate.of(2026, 8, 1)
        val second = LocalDate.of(2026, 8, 10)
        val readings = listOf(
            reading(first, 23, 10_000),
            reading(first.plusDays(1), 7, 10_005),
            reading(second, 23, 10_100),
            reading(second.plusDays(1), 7, 10_101),
        )

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fiveDial, zone)

        assertNotNull(result)
        assertEquals(1.0, result!!.observedKwh, 0.01)
    }

    // --- A night the register wrapped is a night like any other ---

    @Test
    fun aNightInWhichTheRegisterWrappedIsMeasuredOnceTheHouseholdAttestedIt() {
        // Four-dial meter at 9,998 going to 0,003 overnight. That is five kWh --
        // an entirely ordinary night. The old `to.kwh >= from.kwh` guard threw
        // the pair away and the card simply said there was no test.
        val fourDial = MeterRegister(capacityKwh = 10_000, multiplier = 1)
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(
            reading(day, 23, 9_998),
            reading(day.plusDays(1), 7, 3).copy(wrapsSincePrevious = 1),
        )

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fourDial, zone)

        assertNotNull("la noche no debe descartarse en silencio", result)
        assertEquals(5.0, result!!.observedKwh, 0.01)
        assertTrue("el número se apoya en el testimonio, y hay que decirlo", result.restsOnAttestation)
    }

    @Test
    fun aWrapNobodyAttestedIsStillNotAssumed() {
        // Same readings, no attestation. Refusing is correct: the app cannot
        // tell a wrap from a mis-capture on its own, and inventing one here
        // would report a phantom-consumption figure nobody can defend.
        val fourDial = MeterRegister(capacityKwh = 10_000, multiplier = 1)
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 23, 9_998), reading(day.plusDays(1), 7, 3))

        assertNull(OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fourDial, zone))
    }

    @Test
    fun theMultiplierReachesTheOvernightFigure() {
        val withCt = MeterRegister(capacityKwh = 100_000, multiplier = 20)
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 23, 10_000), reading(day.plusDays(1), 7, 10_002))

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge), withCt, zone)

        // Two register units at x20 is 40 billed kWh overnight, not 2.
        assertNotNull(result)
        assertEquals(40.0, result!!.observedKwh, 0.01)
        assertEquals(false, result.restsOnAttestation)
    }

    @Test
    fun anOrdinaryNightDoesNotClaimToRestOnTestimony() {
        val day = LocalDate.of(2026, 8, 10)
        val readings = listOf(reading(day, 23, 10_000), reading(day.plusDays(1), 7, 10_002))

        val result = OvernightBaselineCalculator.evaluate(readings, listOf(fridge), fiveDial, zone)

        assertEquals(false, result!!.restsOnAttestation)
    }
}
