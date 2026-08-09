package com.recibozen.app.core.calculation

import kotlin.math.roundToInt

data class TariffBlock(val limitKwh: Int?, val rateMxnPerKwh: Double)

data class TariffEstimate(
    val consumptionKwh: Int,
    val energyChargeMxn: Double,
    val fixedChargeMxn: Double,
    val taxMxn: Double,
    val totalMxn: Int,
)

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
