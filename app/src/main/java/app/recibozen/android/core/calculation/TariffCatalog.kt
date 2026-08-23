package app.recibozen.android.core.calculation

enum class TariffAudience { HOME, BUSINESS }

enum class TariffCalculationSupport { PROGRESSIVE_ENERGY, RECEIPT_CALIBRATION_REQUIRED }

data class TariffDefinition(
    val code: String,
    val name: String,
    val audience: TariffAudience,
    val seasonal: Boolean,
    val highConsumptionLimitKwhPerMonth: Int? = null,
    val calculationSupport: TariffCalculationSupport = TariffCalculationSupport.PROGRESSIVE_ENERGY,
)

/** Stable tariff identifiers. Dated CFE reference rates are synced separately; receipts preserve locality context. */
object TariffCatalog {
    val supported = listOf(
        TariffDefinition("1", "Doméstica", TariffAudience.HOME, seasonal = false, highConsumptionLimitKwhPerMonth = 250),
        TariffDefinition("1A", "Doméstica cálida 25 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 300),
        TariffDefinition("1B", "Doméstica cálida 28 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 400),
        TariffDefinition("1C", "Doméstica cálida 30 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 850),
        TariffDefinition("1D", "Doméstica cálida 31 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 1_000),
        TariffDefinition("1E", "Doméstica cálida 32 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 2_000),
        TariffDefinition("1F", "Doméstica cálida 33 °C", TariffAudience.HOME, seasonal = true, highConsumptionLimitKwhPerMonth = 2_500),
        TariffDefinition(
            "DAC",
            "Doméstica de alto consumo",
            TariffAudience.HOME,
            seasonal = false,
            calculationSupport = TariffCalculationSupport.RECEIPT_CALIBRATION_REQUIRED,
        ),
        TariffDefinition(
            "PDBT",
            "Pequeña demanda en baja tensión",
            TariffAudience.BUSINESS,
            seasonal = false,
            calculationSupport = TariffCalculationSupport.RECEIPT_CALIBRATION_REQUIRED,
        ),
    )

    fun normalize(raw: String): String = raw
        .uppercase()
        .replace("TARIFA", "")
        .replace(" ", "")
        .let { if (it == "01") "1" else it }

    fun find(raw: String): TariffDefinition? {
        val code = normalize(raw)
        return supported.firstOrNull { it.code == code }
    }
}

data class TariffRateSnapshot(
    val tariffCode: String,
    val effectivePeriod: String,
    val blocks: List<TariffBlock>,
    val fixedChargeMxn: Double = 0.0,
    val taxRate: Double = 0.16,
    val sourceBillId: String? = null,
) {
    fun engine(): TariffEngine = TariffEngine(blocks, fixedChargeMxn, taxRate)
}
