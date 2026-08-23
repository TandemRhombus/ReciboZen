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
    /**
     * Set when CFE replaced the physical meter between the previous reading and
     * this one. Null for every ordinary reading.
     *
     * This is the fifth thing a reading that goes backwards can mean, and the
     * only one the app could not record. Until it could, a household whose meter
     * was swapped -- which CFE has been doing at scale as it moves people to
     * digital registers -- was broken permanently: every later reading compared
     * against a history from a device that no longer exists.
     */
    val meterReplacement: MeterReplacement? = null,
)

/**
 * A meter swap, described by the two readings that bridge it.
 *
 * A replacement is not a rollover and must never be treated as one. A rollover
 * is one register passing its maximum, so the capacity closes the arithmetic. A
 * replacement is two different registers, and nothing about the new one's value
 * can be derived from the old one's: consumption across the swap is what the
 * removed meter still had to give plus what the installed meter has counted
 * since.
 *
 * Both fields are nullable and neither is ever defaulted. A new meter usually
 * starts at zero, and "usually" is not a reading: refurbished units are
 * installed with a nonzero register, and assuming zero on one of those invents
 * however much it already showed. The app asks, pre-fills nothing it did not
 * hear, and reports the interval as undetermined until it is told.
 */
data class MeterReplacement(
    /** Last value read off the meter that was removed, if the household knows it. */
    val previousMeterFinalKwh: Int? = null,
    /** Value the installed meter started at. Usually zero, never assumed to be. */
    val newMeterStartKwh: Int? = null,
) {
    /** True once both boundary readings are known and consumption can be closed. */
    val isComplete: Boolean get() = previousMeterFinalKwh != null && newMeterStartKwh != null
}

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
