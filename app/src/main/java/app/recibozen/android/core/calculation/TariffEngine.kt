package app.recibozen.android.core.calculation

import kotlin.math.roundToInt

data class TariffBlock(val limitKwh: Int?, val rateMxnPerKwh: Double)

data class TariffEstimate(
    val consumptionKwh: Int,
    val energyChargeMxn: Double,
    val fixedChargeMxn: Double,
    val taxMxn: Double,
    val totalMxn: Int,
) {
    /**
     * Unrounded total. Explaining the change between two bills subtracts four of
     * these from one another, and rounding each one first leaves pesos that
     * belong to no cause.
     */
    val exactTotalMxn: Double get() = energyChargeMxn + fixedChargeMxn + taxMxn
}

/** Progressive electricity estimator with replaceable tariff inputs. */
class TariffEngine(
    private val blocks: List<TariffBlock> = DEFAULT_1C_BIMONTHLY_BLOCKS,
    private val fixedChargeMxn: Double = 55.0,
    private val taxRate: Double = 0.16,
) {
    fun estimate(consumptionKwh: Int): TariffEstimate {
        var remaining = consumptionKwh.coerceAtLeast(0)
        var previousLimit = 0
        var energyCharge = 0.0
        blocks.forEach { block ->
            if (remaining <= 0) return@forEach
            val capacity = block.limitKwh?.let { (it - previousLimit).coerceAtLeast(0) } ?: remaining
            val units = minOf(remaining, capacity)
            energyCharge += units * block.rateMxnPerKwh
            remaining -= units
            previousLimit = block.limitKwh ?: previousLimit
        }
        val taxable = energyCharge + fixedChargeMxn
        val tax = taxable * taxRate
        return TariffEstimate(
            consumptionKwh = consumptionKwh.coerceAtLeast(0),
            energyChargeMxn = energyCharge,
            fixedChargeMxn = fixedChargeMxn,
            taxMxn = tax,
            totalMxn = (taxable + tax).roundToInt(),
        )
    }

    /**
     * Price of the next kWh at a given consumption level. Used to separate "you
     * used more" from "what you used got more expensive", which is the part of a
     * progressive tariff nobody sees coming.
     */
    fun marginalRateMxnPerKwh(consumptionKwh: Int): Double {
        val level = consumptionKwh.coerceAtLeast(0)
        blocks.forEach { block ->
            val limit = block.limitKwh ?: return block.rateMxnPerKwh
            if (level < limit) return block.rateMxnPerKwh
        }
        return blocks.lastOrNull()?.rateMxnPerKwh ?: 0.0
    }

    companion object {
        // Local baseline for a bimonthly domestic 1C-style estimate. It is
        // configuration, not an assertion that these are current official rates.
        val DEFAULT_1C_BIMONTHLY_BLOCKS = listOf(
            TariffBlock(limitKwh = 150, rateMxnPerKwh = 1.08),
            TariffBlock(limitKwh = 430, rateMxnPerKwh = 1.32),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 3.94),
        )
    }
}
