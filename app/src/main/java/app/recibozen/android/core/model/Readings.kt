package app.recibozen.android.core.model

enum class ReadingMethod { DIAL, MANUAL, PHOTO, PDF }

data class HistoryReadingEntry(
    val kwh: Int,
    val dateLabel: String,
    val method: ReadingMethod,
    val note: String,
    val timestampEpochMillis: Long? = null,
    /**
     * How many times the register wrapped past its maximum between the previous
     * reading and this one, as attested by the household when the app asked.
     *
     * Null means nobody attested anything, which is the normal case and is not
     * the same as zero. Zero is testimony ("it did not wrap"); null is silence.
     * Only a stored attestation lets a later screen reproduce the consumption
     * the user already confirmed instead of asking again or, worse, quietly
     * going back to reading the wrap as a reading that went backwards.
     */
    val wrapsSincePrevious: Int? = null,
)

enum class HistoryUnit { MXN, KWH }

data class BillPeriod(
    val monthLabel: String,
    val amountMxn: Int,
    val isProjected: Boolean = false,
    val consumptionKwh: Int = 0,
    val periodStartEpochDay: Long? = null,
    val periodEndEpochDay: Long? = null,
    /** Present for a detailed imported bill; historical summary rows may not carry it. */
    val tariffCode: String? = null,
)

data class PastPeriodComparison(
    val periodLabel: String,
    val previousEstimateMxn: Int,
    val actualBillMxn: Int,
    val note: String,
)
