package com.recibozen.app.core.model

enum class PlanActionStatus { SUGGESTED, TRACKING, VERIFIED }

/**
 * One savings action, always tied to an appliance the household registered.
 * Built by the advice engine on every read; only [PlanActionStatus.TRACKING]
 * membership is persisted.
 */
data class PlanAction(
    val id: String,
    val title: String,
    val savingsRangeLabel: String,
    val effort: EffortLevel,
    val confidence: ConfidenceLevel,
    val status: PlanActionStatus,
    val estimatedCutMxn: Int,
    /** Appliance this action belongs to, so the card can link to its detail. */
    val applianceId: String = "",
    /** What to actually do, in the user's words. */
    val description: String = "",
    /** Set only when an official manufacturer guide backs the exact steps. */
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val trackingSinceLabel: String? = null,
    val trackingNote: String? = null,
    val verifiedSinceLabel: String? = null,
    val verifiedNote: String? = null,
)
