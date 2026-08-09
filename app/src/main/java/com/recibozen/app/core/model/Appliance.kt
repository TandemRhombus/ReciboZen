package com.recibozen.app.core.model

enum class OpportunityLevel { HIGH, MEDIUM, NONE }

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
)
