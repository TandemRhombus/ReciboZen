package com.recibozen.app.core.model

enum class PlanActionStatus { SUGGESTED, TRACKING, VERIFIED }

data class PlanAction(
    val id: String,
    val title: String,
    val savingsRangeLabel: String,
    val effort: EffortLevel,
    val confidence: ConfidenceLevel,
    val status: PlanActionStatus,
    val estimatedCutMxn: Int,
    val trackingSinceLabel: String? = null,
    val trackingNote: String? = null,
    val verifiedSinceLabel: String? = null,
    val verifiedNote: String? = null,
)
