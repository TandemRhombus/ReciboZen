package com.recibozen.app.core.model

enum class OpportunityLevel { HIGH, MEDIUM, NONE }

enum class ApplianceCategory {
    CLIMATE,
    KITCHEN,
    LAUNDRY,
    ENTERTAINMENT,
    COMPUTING,
    LIGHTING,
    WATER,
    PERSONAL_CARE,
    OTHER,
}

/** How the period consumption is calculated for one appliance entry. */
enum class ApplianceUsageModel {
    /** Power (W) multiplied by the weekly schedule. */
    POWER_SCHEDULE,
    /** Energy entered for one unit over the whole billing period. */
    PERIOD_KWH,
}

/** Provenance shown to the user; an estimate must never look like a measurement. */
enum class ApplianceDataSource {
    CATALOG,
    MANUFACTURER_LABEL,
    USER_ESTIMATE,
    USER_MEASURED,
    RECEIPT_INFERENCE,
}

data class HouseSpace(
    val id: String,
    val name: String,
)

object HouseSpaceDefaults {
    const val HOME_ID = "home"

    val items = listOf(
        HouseSpace(HOME_ID, "Toda la casa"),
        HouseSpace("kitchen", "Cocina"),
        HouseSpace("living_room", "Sala"),
        HouseSpace("bedroom", "Recámara"),
        HouseSpace("bathroom", "Baño"),
        HouseSpace("laundry", "Lavandería"),
    )
}

/**
 * One appliance card, either static (Refrigerador) or session-adjustable
 * (PlayStation 5's hours/week, wired through [ReciboZenRepository]).
 */
data class Appliance(
    val id: String,
    val name: String,
    val room: String,
    val costMxn: Int,
    val kwh: Int,
    val sharePercent: Int,
    val hoursPerWeek: Int?,
    val confidence: ConfidenceLevel,
    val opportunity: OpportunityLevel = OpportunityLevel.NONE,
    val isEssential: Boolean = false,
    val usageNote: String? = null,
    val activeCostMxn: Int? = null,
    val standbyCostMxn: Int? = null,
    val standbySharePercent: Int? = null,
    val previousPeriodDeltaPercent: Int? = null,
    val calculationNote: String? = null,
    val category: ApplianceCategory = ApplianceCategory.OTHER,
    val powerWatts: Int? = null,
    val standbyWatts: Int? = null,
    /** Whether the user actually leaves this appliance connected in standby. */
    val standbyEnabled: Boolean = false,
    val dataSource: ApplianceDataSource = ApplianceDataSource.RECEIPT_INFERENCE,
    /** Number of equal units represented by this single row (for example, 20 bulbs). */
    val quantity: Int = 1,
    val spaceId: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val usageModel: ApplianceUsageModel = ApplianceUsageModel.POWER_SCHEDULE,
    /** Unrounded kWh for one unit in [referencePeriodDays]. */
    val unitKwh: Double? = null,
    val referencePeriodDays: Int = 30,
    val referenceAnnualKwh: Double? = null,
    val referenceAnnualKwhMin: Double? = null,
    val referenceAnnualKwhMax: Double? = null,
    /** null means the source did not assert a target market. */
    val marketMexicoVerified: Boolean? = null,
    val isModelFamily: Boolean = false,
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
)
