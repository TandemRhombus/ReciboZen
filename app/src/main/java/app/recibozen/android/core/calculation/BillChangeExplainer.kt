package app.recibozen.android.core.calculation

import app.recibozen.android.core.model.BillPeriod
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Why one bill cost more than the previous one, in pesos, without a single
 * token of AI.
 *
 * "Consumiste más" is almost never the whole answer. A bimonthly period can be
 * five days longer than the last one, a progressive tariff can push the same
 * habits into a costlier block, and the summer rate can end mid-period. Each of
 * those is a different thing to do about it, so each gets its own line.
 *
 * The decomposition is sequential and exact: every peso of the difference lands
 * in exactly one cause, and whatever the engine cannot reproduce is reported as
 * unexplained instead of being spread over the others.
 */
enum class BillChangeFactor {
    /** The period simply covered more (or fewer) days. */
    PERIOD_LENGTH,

    /** Real change in daily usage, priced at what the previous kWh already cost. */
    DAILY_USAGE,

    /** The extra kWh crossed into a costlier block of the progressive tariff. */
    TARIFF_STEP,

    /** Different rates: tariff change, season change, or a rate update. */
    TARIFF_RATES,

    /** Difference between the modelled bill and the amount actually charged. */
    UNEXPLAINED,
}

data class BillChangeComponent(
    val factor: BillChangeFactor,
    val amountMxn: Int,
    /** The number behind the line: days, kWh, or MXN/kWh, depending on the factor. */
    val magnitude: Double,
)

data class BillChangeExplanation(
    val previousLabel: String,
    val currentLabel: String,
    val previousAmountMxn: Int,
    val currentAmountMxn: Int,
    val previousKwh: Int,
    val currentKwh: Int,
    val previousDays: Int?,
    val currentDays: Int?,
    /** Non-negligible causes, largest absolute effect first. */
    val components: List<BillChangeComponent>,
    /**
     * The two bills were issued under different tariffs. Until historical rates
     * are available for both, the rate effect cannot be separated from the rest,
     * so the screen says so rather than attributing it to consumption.
     */
    val tariffCodeChanged: Boolean,
) {
    val deltaMxn: Int get() = currentAmountMxn - previousAmountMxn
    val increased: Boolean get() = deltaMxn > 0

    /** True when the daily rhythm did not change and the period length did. */
    val isMostlyPeriodLength: Boolean
        get() = components.firstOrNull()?.factor == BillChangeFactor.PERIOD_LENGTH
}

object BillChangeExplainer {
    /** Below this the line is noise on a bill, not an explanation. */
    private const val MIN_RELEVANT_MXN = 5

    /**
     * @param previousEngine tariff in force during [previous]
     * @param currentEngine tariff in force during [current]; the same instance
     * when nothing about the tariff changed.
     */
    fun explain(
        previous: BillPeriod,
        current: BillPeriod,
        previousEngine: TariffEngine,
        currentEngine: TariffEngine = previousEngine,
    ): BillChangeExplanation? {
        if (previous.isProjected || current.isProjected) return null
        if (previous.consumptionKwh <= 0 || current.consumptionKwh <= 0) return null
        if (previous.amountMxn <= 0 || current.amountMxn <= 0) return null

        val previousDays = previous.billingDays()
        val currentDays = current.billingDays()

        // Step 1 — stretch the previous bill to the current period length at the
        // same daily rhythm. Everything after this compares like with like.
        val lengthAdjustedKwh = if (previousDays != null && currentDays != null && previousDays > 0) {
            (previous.consumptionKwh.toDouble() * currentDays / previousDays).roundToInt()
        } else {
            previous.consumptionKwh
        }

        val atPreviousTariff = { kwh: Int -> previousEngine.estimate(kwh).exactTotalMxn }
        val basePrevious = atPreviousTariff(previous.consumptionKwh)
        val baseLengthAdjusted = atPreviousTariff(lengthAdjustedKwh)
        val baseCurrentUsage = atPreviousTariff(current.consumptionKwh)
        val modelledCurrent = currentEngine.estimate(current.consumptionKwh).exactTotalMxn

        val periodLengthMxn = baseLengthAdjusted - basePrevious
        val usageMxn = baseCurrentUsage - baseLengthAdjusted
        val tariffRatesMxn = modelledCurrent - baseCurrentUsage

        // Split the usage effect: the kWh you added would have cost this much at
        // the price you were already paying; anything above that is the tariff
        // block moving under your feet. The reference rate is taken at the lower
        // of the two levels so that a bill going down does not produce a
        // "cheaper block" line with the opposite sign to the saving itself.
        val extraKwh = current.consumptionKwh - lengthAdjustedKwh
        val rateAlreadyPaid = previousEngine.marginalRateMxnPerKwh(
            minOf(lengthAdjustedKwh, current.consumptionKwh),
        )
        val taxFactor = taxFactorOf(previousEngine)
        val atSameRateMxn = extraKwh * rateAlreadyPaid * taxFactor
        val stepMxn = usageMxn - atSameRateMxn

        val modelledDelta = periodLengthMxn + usageMxn + tariffRatesMxn
        val actualDelta = (current.amountMxn - previous.amountMxn).toDouble()
        val unexplainedMxn = actualDelta - modelledDelta

        val components = listOf(
            BillChangeComponent(
                factor = BillChangeFactor.PERIOD_LENGTH,
                amountMxn = periodLengthMxn.roundToInt(),
                magnitude = ((currentDays ?: 0) - (previousDays ?: 0)).toDouble(),
            ),
            BillChangeComponent(
                factor = BillChangeFactor.DAILY_USAGE,
                amountMxn = atSameRateMxn.roundToInt(),
                magnitude = extraKwh.toDouble(),
            ),
            BillChangeComponent(
                factor = BillChangeFactor.TARIFF_STEP,
                amountMxn = stepMxn.roundToInt(),
                magnitude = rateAlreadyPaid,
            ),
            BillChangeComponent(
                factor = BillChangeFactor.TARIFF_RATES,
                amountMxn = tariffRatesMxn.roundToInt(),
                magnitude = 0.0,
            ),
            BillChangeComponent(
                factor = BillChangeFactor.UNEXPLAINED,
                amountMxn = unexplainedMxn.roundToInt(),
                magnitude = 0.0,
            ),
        ).filter { abs(it.amountMxn) >= MIN_RELEVANT_MXN }
            .sortedByDescending { abs(it.amountMxn) }

        if (components.isEmpty()) return null

        return BillChangeExplanation(
            previousLabel = previous.monthLabel,
            currentLabel = current.monthLabel,
            previousAmountMxn = previous.amountMxn,
            currentAmountMxn = current.amountMxn,
            previousKwh = previous.consumptionKwh,
            currentKwh = current.consumptionKwh,
            previousDays = previousDays,
            currentDays = currentDays,
            components = components,
            tariffCodeChanged = previous.tariffCode != null &&
                current.tariffCode != null &&
                !previous.tariffCode.equals(current.tariffCode, ignoreCase = true),
        )
    }

    /**
     * VAT applies on top of the energy charge, so a kWh priced by the block rate
     * lands on the bill multiplied by it. Recovered from the engine instead of
     * hardcoded, because a DAC bill and a subsidised one do not share it.
     */
    private fun taxFactorOf(engine: TariffEngine): Double {
        val zero = engine.estimate(0)
        val hundred = engine.estimate(100)
        val energyDelta = hundred.energyChargeMxn - zero.energyChargeMxn
        if (energyDelta <= 0.0) return 1.0
        return (hundred.exactTotalMxn - zero.exactTotalMxn) / energyDelta
    }

    private fun BillPeriod.billingDays(): Int? {
        val start = periodStartEpochDay ?: return null
        val end = periodEndEpochDay ?: return null
        return (end - start).toInt().takeIf { it in 1..400 }
    }
}
