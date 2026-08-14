package com.recibozen.app.feature.period

/** Values derived from imported bills and confirmed meter readings. */
data class PeriodAnalysisUiState(
    val hasUsageData: Boolean = false,
    val lastReadingKwh: Int = 0,
    val lastReadingDateLabel: String = "",
    val observedKwh: Int = 0,
    val observedDays: Int = 0,
    val billingDays: Int = 0,
    val daysRemaining: Int = 0,
    val costToDateMxn: Int = 0,
    val goalKwh: Int = 0,
    val projectedKwh: Int = 0,
    val currentPeriodLabel: String = "",
    val currentPeriodDateRange: String = "",
    val previousPeriodLabel: String = "",
    val previousBillKwh: Int = 0,
)
