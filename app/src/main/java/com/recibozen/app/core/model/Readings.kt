package com.recibozen.app.core.model

enum class ReadingMethod { DIAL, MANUAL, PHOTO }

data class HistoryReadingEntry(
    val kwh: Int,
    val dateLabel: String,
    val method: ReadingMethod,
    val note: String,
    val timestampEpochMillis: Long? = null,
)

enum class HistoryUnit { MXN, KWH }

data class BillPeriod(
    val monthLabel: String,
    val amountMxn: Int,
    val isProjected: Boolean = false,
    val consumptionKwh: Int = 0,
)

data class PastPeriodComparison(
    val periodLabel: String,
    val previousEstimateMxn: Int,
    val actualBillMxn: Int,
    val note: String,
)
