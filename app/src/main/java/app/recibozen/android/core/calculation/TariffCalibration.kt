package app.recibozen.android.core.calculation

/**
 * Derives a real [TariffRateSnapshot] from the tier charges printed on the
 * user's own CFE bill.
 *
 * The backend now synchronizes CFE's dated reference catalog as well. A bill is
 * still the authoritative pricing input for this household because seasonal
 * tariffs depend on the locality's summer start and DAC depends on its region;
 * the imported rows preserve that exact context and re-calibrate on every bill.
 */
object TariffCalibration {

    /** Tier labels emitted by [app.recibozen.android.core.importer.CfeBillTextParser], in billing order. */
    private val TIER_ORDER = listOf("BASICO", "INTERMEDIO", "EXCEDENTE")

    /** Minimal view of a parsed/persisted charge row, so this stays free of Room and parser types. */
    data class CalibrationCharge(
        val type: String,
        val season: String,
        val quantityKwh: Double?,
        val unitRateMxn: Double?,
        val amountMxn: Double? = null,
    )

    /** How much of the resulting snapshot came from the bill vs. the generic fallback. */
    enum class Coverage {
        /** Every tier's rate came from the bill and the open-ended tier was reached. */
        FULL,

        /** Some tiers came from the bill; the rest fall back to the generic blocks. */
        PARTIAL,

        /** Nothing usable on the bill; the generic blocks are used as-is. */
        NONE,
    }

    data class Result(val snapshot: TariffRateSnapshot, val coverage: Coverage)

    /**
     * Builds a snapshot from [charges].
     *
     * Threshold reasoning: a tier's printed `quantityKwh` is only its *real*
     * limit when a later tier also appears — that proves the earlier tier was
     * exhausted. So limits are the running total of every tier that has a
     * successor; the last tier present keeps the fallback's limit unless it is
     * the open-ended one.
     *
     * Season caveat: a bimonthly bill can bill part of the period at summer
     * rates and part outside of it. This picks the season with the most kWh and
     * ignores the other, which is why [Coverage.FULL] still means "good
     * estimate", never "official quote".
     */
    fun fromCharges(
        tariffCode: String,
        effectivePeriod: String,
        charges: List<CalibrationCharge>,
        sourceBillId: String? = null,
        fallbackBlocks: List<TariffBlock> = TariffEngine.DEFAULT_1C_BIMONTHLY_BLOCKS,
        fixedChargeMxn: Double = DEFAULT_FIXED_CHARGE_MXN,
        taxRate: Double = DEFAULT_TAX_RATE,
    ): Result {
        if (TariffCatalog.normalize(tariffCode) == "DAC") {
            return fromDacCharges(
                tariffCode = tariffCode,
                effectivePeriod = effectivePeriod,
                charges = charges,
                sourceBillId = sourceBillId,
                fallbackBlocks = fallbackBlocks,
                fixedChargeMxn = fixedChargeMxn,
                taxRate = taxRate,
            )
        }
        val usable = charges.filter { charge ->
            charge.type.uppercase() in TIER_ORDER &&
                (charge.unitRateMxn ?: 0.0) > 0.0
        }
        if (usable.isEmpty()) {
            return Result(
                TariffRateSnapshot(tariffCode, effectivePeriod, fallbackBlocks, fixedChargeMxn, taxRate, sourceBillId),
                Coverage.NONE,
            )
        }

        val dominantSeason = usable
            .groupBy { it.season }
            .maxByOrNull { (_, rows) -> rows.sumOf { it.quantityKwh ?: 0.0 } }
            ?.key

        // One row per tier: same tier can repeat across seasons on one bill.
        val byTier = usable
            .filter { it.season == dominantSeason }
            .associateBy { it.type.uppercase() }

        val blocks = mutableListOf<TariffBlock>()
        var runningLimit = 0
        var calibratedTiers = 0
        var reachedOpenEnded = false
        var missingEarlierTier = false

        TIER_ORDER.forEachIndexed { index, tier ->
            val fallback = fallbackBlocks.getOrNull(index)
            val charge = byTier[tier]
            val isOpenEnded = index == TIER_ORDER.lastIndex
            val hasSuccessor = TIER_ORDER.drop(index + 1).any { byTier.containsKey(it) }

            if (charge == null || missingEarlierTier) {
                // Tier absent from the bill: keep the generic block untouched.
                if (fallback != null) blocks += fallback
                missingEarlierTier = true
                return@forEachIndexed
            }

            calibratedTiers++
            val rate = charge.unitRateMxn ?: return@forEachIndexed
            val candidateLimit = when {
                isOpenEnded -> null
                hasSuccessor -> {
                    runningLimit += (charge.quantityKwh ?: 0.0).toInt()
                    runningLimit
                }
                // Last tier on the bill but not the open-ended one: the user
                // never crossed it, so its true ceiling is unknown.
                else -> fallback?.limitKwh
            }
            val previousBlockLimit = blocks.lastOrNull()?.limitKwh ?: 0
            val limit = candidateLimit?.takeIf { it > previousBlockLimit } ?: if (isOpenEnded) null else fallback?.limitKwh
            if (!isOpenEnded && (limit == null || limit <= previousBlockLimit)) {
                // An OCR gap or malformed quantity would make a progressive
                // threshold move backwards. Fall back from this tier upward.
                blocks += fallback ?: return@forEachIndexed
                calibratedTiers--
                missingEarlierTier = true
                return@forEachIndexed
            }
            if (isOpenEnded) reachedOpenEnded = true
            blocks += TariffBlock(limitKwh = limit, rateMxnPerKwh = rate)
        }

        val coverage = when {
            calibratedTiers == TIER_ORDER.size && reachedOpenEnded -> Coverage.FULL
            calibratedTiers > 0 -> Coverage.PARTIAL
            else -> Coverage.NONE
        }
        return Result(
            TariffRateSnapshot(tariffCode, effectivePeriod, blocks, fixedChargeMxn, taxRate, sourceBillId),
            coverage,
        )
    }

    private fun fromDacCharges(
        tariffCode: String,
        effectivePeriod: String,
        charges: List<CalibrationCharge>,
        sourceBillId: String?,
        fallbackBlocks: List<TariffBlock>,
        fixedChargeMxn: Double,
        taxRate: Double,
    ): Result {
        val candidates = charges.filter { charge ->
            charge.type.uppercase() in setOf("ENERGY", "ENERGIA", "BASICO", "INTERMEDIO", "EXCEDENTE") &&
                (charge.quantityKwh ?: 0.0) > 0.0 &&
                ((charge.unitRateMxn ?: 0.0) > 0.0 || (charge.amountMxn ?: 0.0) > 0.0)
        }
        // Prefer the explicit DAC energy rows if a future document contains
        // both a summary and tier-like labels, so energy is never counted twice.
        val explicitEnergy = candidates.filter { it.type.uppercase() in setOf("ENERGY", "ENERGIA") }
        val energyRows = explicitEnergy.ifEmpty { candidates }
        if (energyRows.isEmpty()) {
            return Result(
                TariffRateSnapshot(tariffCode, effectivePeriod, fallbackBlocks, fixedChargeMxn, taxRate, sourceBillId),
                Coverage.NONE,
            )
        }
        var runningLimit = 0
        val blocks = energyRows.mapIndexed { index, charge ->
            val quantity = charge.quantityKwh ?: 0.0
            val rate = charge.unitRateMxn ?: ((charge.amountMxn ?: 0.0) / quantity)
            runningLimit += quantity.toInt()
            TariffBlock(
                limitKwh = runningLimit.takeIf { index < energyRows.lastIndex },
                rateMxnPerKwh = rate,
            )
        }
        val billFixedCharge = charges.firstOrNull { it.type.uppercase() == "FIXED" }
            ?.amountMxn
            ?.takeIf { it >= 0.0 }
            ?: fixedChargeMxn
        return Result(
            TariffRateSnapshot(tariffCode, effectivePeriod, blocks, billFixedCharge, taxRate, sourceBillId),
            Coverage.FULL,
        )
    }

    const val DEFAULT_FIXED_CHARGE_MXN = 55.0
    const val DEFAULT_TAX_RATE = 0.16
}
