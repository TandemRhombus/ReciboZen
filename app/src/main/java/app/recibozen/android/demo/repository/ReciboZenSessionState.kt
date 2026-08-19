package app.recibozen.android.demo.repository

import app.recibozen.android.core.designsystem.AppThemeMode
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.HistoryUnit
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.core.model.HouseSpaceDefaults

/** User-owned state. The production repository persists this object with DataStore. */
data class ReciboZenSessionState(
    val isHydrated: Boolean = true,
    val onboardingCompleted: Boolean = false,
    /** The guided tour was finished or skipped. Replayable from Perfil. */
    val tutorialCompleted: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val userName: String = "",
    val householdName: String = "",
    val serviceAlias: String = "",
    val serviceNumber: String = "",
    val rmu: String = "",
    val meterNumber: String = "",
    val activeServiceId: String = "",
    val entitlementTier: EntitlementTier = EntitlementTier.FREE,
    val location: String = "",
    val tariff: String = "",
    /** Last domestic tariff for each service, needed to calculate a DAC exit target. */
    val dacReferenceTariffsByService: Map<String, String> = emptyMap(),
    val billingCycle: String = "",
    /**
     * Goal, meter type and city belong to the supply, not to the person: a Plus
     * or Familiar household has one of each per service, and the city feeds the
     * solar study. The active service's value is mirrored into [goalMxn],
     * [meterType] and [location] so every screen keeps reading one field.
     */
    val goalMxnByService: Map<String, Int> = emptyMap(),
    val meterTypeByService: Map<String, String> = emptyMap(),
    val locationByService: Map<String, String> = emptyMap(),
    val goalMxn: Int = 0,
    val meterType: String = "",
    val applianceHoursPerWeek: Map<String, Int> = emptyMap(),
    val customAppliances: List<Appliance> = emptyList(),
    val houseSpaces: List<HouseSpace> = HouseSpaceDefaults.items,
    val savedSimulatorScenario: Boolean = false,
    val simulatorScenarioName: String = "",
    val simulatorScenarioWatts: Int = 0,
    val simulatorScenarioHoursPerWeek: Double = 0.0,
    /**
     * Ids of the savings actions the household decided to follow. The action
     * list itself is derived from the appliance inventory on every read, so
     * only the decision is persisted, never a snapshot of the advice.
     */
    val trackedActionIds: Set<String> = emptySet(),
    val dialAngles: List<Float> = listOf(66.3f, 56.7f, 153.4f, 266.4f, 216f),
    val dialDirections: List<Boolean> = listOf(true, false, true, false, true),
    val dialSelectedIndex: Int = 0,
    val dialVisitedFaces: Set<Int> = setOf(0),
    val dialUnsure: Boolean = false,
    val solarScenarioIndex: Int = 0,
    /** Extra annual demand the user wants the solar design to support. */
    val solarDemandGrowthPercent: Int = 0,
    /** Annual kWh of future loads not yet present in bills or meter readings. */
    val solarPlannedLoadsKwh: Map<String, Int> = emptyMap(),
    val historyUnit: HistoryUnit = HistoryUnit.MXN,
    val onboardingStep: Int = 0,
    val lastConfirmedReadingKwh: Int = 0,
    val readings: List<HistoryReadingEntry> = emptyList(),
    val manualReadingDigits: List<Int> = listOf(0, 0, 0, 0, 0),
    val pendingReadingKwh: Int? = null,
    val pendingReadingMethod: app.recibozen.android.core.model.ReadingMethod? = null,
)

/** Result of the demo "846 − tracked-action cuts" projection, recomputed on every plan change. */
data class BillProjection(
    val projectedMxn: Int,
    val rangeLowMxn: Int,
    val rangeHighMxn: Int,
    val isWithinGoal: Boolean,
    val planProgressFraction: Float,
    val hasUsageEvidence: Boolean = false,
)

/** The evidence that currently drives the headline projection. */
enum class EnergyForecastBasis {
    NONE,
    /** A closed CFE bill is the only measured reference available. */
    CLOSED_BILL,
    /** Declared appliance schedules are the only source for the next period. */
    APPLIANCES,
    /** A live meter pace is primary; appliance usage is kept as a comparison. */
    METER,
}

data class EnergySummary(
    val observedKwh: Int,
    val observedDays: Int,
    val billingDays: Int,
    val daysRemaining: Int,
    val dailyAverageKwh: Double,
    val projectedKwh: Int,
    val costToDateMxn: Int,
    val projectedCostMxn: Int,
    val hasUsageEvidence: Boolean = false,
    /** Projection obtained only from the current period's meter pace. */
    val meterProjectedKwh: Int = 0,
    /** Bottom-up projection from the appliances and schedules declared by the user. */
    val applianceProjectedKwh: Int = 0,
    val applianceProjectedCostMxn: Int = 0,
    val forecastBasis: EnergyForecastBasis = EnergyForecastBasis.NONE,
    /**
     * The billing period the household is living right now, chained forward from
     * the latest imported bill. Single source for every "period" label and for
     * [daysRemaining], so Home, Análisis del periodo and DAC cannot disagree.
     */
    val currentPeriod: app.recibozen.android.core.calculation.CurrentBillingPeriod? = null,
) {
    val hasCurrentMeterProjection: Boolean
        get() = forecastBasis == EnergyForecastBasis.METER

    val hasApplianceProjection: Boolean
        get() = applianceProjectedKwh > 0

    /**
     * How much of the billing period [observedKwh] actually covers.
     *
     * Appliance consumption is expressed per full period, so comparing it
     * against a mid-period reading (say 3 days in) would make the appliances
     * look like they explain everything and leave "sin identificar" at 0.
     * Scale appliance kWh by this before comparing against [observedKwh].
     * It is 1.0 when the evidence is a closed bill, where both already share
     * the same window.
     */
    val observedWindowFraction: Double
        get() = when {
            billingDays <= 0 || observedDays <= 0 -> 1.0
            else -> (observedDays.toDouble() / billingDays).coerceIn(0.0, 1.0)
        }
}

/**
 * Tariff rates in force for the estimate, derived from the user's own bill.
 * [coverage] says how much of it is real vs. generic fallback, so screens can
 * be honest about it instead of presenting every number as equally solid.
 */
data class TariffCalibrationState(
    val snapshot: app.recibozen.android.core.calculation.TariffRateSnapshot,
    val coverage: app.recibozen.android.core.calculation.TariffCalibration.Coverage,
    val periodLabel: String,
) {
    val isCalibrated: Boolean
        get() = coverage != app.recibozen.android.core.calculation.TariffCalibration.Coverage.NONE
}

/**
 * Where the rates behind the estimate came from, in decreasing order of how
 * closely they match this household.
 */
enum class TariffRateOrigin {
    /** Tier rates read from the household's own CFE bill. */
    BILL,

    /** CFE's published catalog, synced by the backend and stored locally. */
    REMOTE_CATALOG,

    /** Built-in generic blocks. An order of magnitude, never a quote. */
    GENERIC,
}

/**
 * Rates published by CFE for one tariff, synced by the backend and kept in
 * Room. Seasonal tariffs carry two sets; without a verified summer start for
 * the locality we do not guess which one applies today.
 */
data class RemoteTariffReference(
    val tariffCode: String,
    val effectiveYearMonth: String,
    val season: String,
    val blocks: List<app.recibozen.android.core.calculation.TariffBlock>,
    val fixedChargeMxn: Double?,
    val sourceUrl: String?,
) {
    val isSeasonAmbiguous: Boolean get() = season != "NONE"
}

/** Result of interpreting the 5 dial faces into a meter reading. */
data class DialReadingResult(
    val digits: List<Int>,
    val valueKwh: Int,
    val consumptionSinceLast: Int,
    val averagePerDay: Double,
    val isAbnormal: Boolean,
    val warningMessage: String?,
)
