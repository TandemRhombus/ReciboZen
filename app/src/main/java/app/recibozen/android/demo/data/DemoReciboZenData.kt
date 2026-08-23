package app.recibozen.android.demo.data

import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.EffortLevel
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.MonthlyEnergyPoint
import app.recibozen.android.core.model.OpportunityLevel
import app.recibozen.android.core.model.PastPeriodComparison
import app.recibozen.android.core.model.PlanAction
import app.recibozen.android.core.model.PlanActionStatus
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.core.model.SolarScenario

/**
 * All the fixed demo numbers/labels for the app, in one place, matching the
 * ReciboZen V2 design file 1:1. Nothing here is a real tariff calculation —
 * it is fixture data a future engine can replace wholesale.
 */
object DemoReciboZenData {
    const val HOUSEHOLD_NAME = "Casa"
    const val USER_NAME = "Emi"
    const val USER_INITIAL = "E"
    const val LOCATION = "Mérida"
    const val TARIFF = "Tarifa 1C"
    const val BILLING_CYCLE = "Bimestral"
    const val PERIOD_LABEL = "18 jun a 18 ago"
    const val GOAL_MXN = 780
    const val BASE_PROJECTED_MXN = 846
    const val DAYS_REMAINING = 18
    const val OBSERVED_KWH_TODAY = 327
    const val OBSERVED_DAYS = 43
    const val DAILY_RATE_NOW = 7.2
    const val DAILY_RATE_RECOMMENDED = 6.4
    const val LAST_READING_KWH = 18397
    const val LAST_READING_DATE_LABEL = "5 ago, 3:10 p. m."

    const val MINISPLIT_ACTION_ID = "minisplit_35min"
    const val PS5_ACTION_ID = "ps5_standby"

    val appliances = listOf(
        Appliance(
            id = "minisplit",
            name = "Minisplit inverter",
            room = "Recámara principal",
            costMxn = 322,
            kwh = 128,
            sharePercent = 38,
            hoursPerWeek = 25,
            confidence = ConfidenceLevel.MEDIUM,
            opportunity = OpportunityLevel.HIGH,
            usageNote = "uso estacional",
            previousPeriodDeltaPercent = null,
        ),
        Appliance(
            id = "refrigerador",
            name = "Refrigerador",
            room = "Cocina",
            costMxn = 141,
            kwh = 56,
            sharePercent = 17,
            hoursPerWeek = null,
            confidence = ConfidenceLevel.HIGH,
            isEssential = true,
        ),
        Appliance(
            id = "ps5",
            name = "PlayStation 5",
            room = "Sala",
            costMxn = 93, // recomputed reactively from hoursPerWeek by the repository
            kwh = 37,
            sharePercent = 11,
            hoursPerWeek = 17,
            confidence = ConfidenceLevel.HIGH,
            activeCostMxn = 75,
            standbyCostMxn = 18,
            standbySharePercent = 19,
            previousPeriodDeltaPercent = 12,
            calculationNote = "200 W × %d h/semana × 8.7 semanas ÷ 1,000",
        ),
    )

    const val UNIDENTIFIED_COST_MXN = 74
    const val UNIDENTIFIED_SHARE_PERCENT = 9
    const val EXPLAINED_SHARE_PERCENT = 84
    const val EXPLAINED_KWH = 263
    const val UNEXPLAINED_KWH = 64
    val unidentifiedSuspects = listOf("Bomba de agua", "Boiler eléctrico", "Segundo refrigerador", "Horas subestimadas")
    const val OVERNIGHT_TEST_OBSERVED_KWH = 2.7
    const val OVERNIGHT_TEST_EXPECTED_KWH = 1.8
    const val OVERNIGHT_TEST_UNEXPLAINED_KWH = 0.9

    val planActions = listOf(
        PlanAction(
            id = MINISPLIT_ACTION_ID,
            title = "Minisplit · reducir 35 minutos diarios",
            savingsRangeLabel = "\$64–\$82",
            effort = EffortLevel.LOW,
            confidence = ConfidenceLevel.MEDIUM,
            status = PlanActionStatus.SUGGESTED,
            estimatedCutMxn = 73,
            trackingSinceLabel = "6 de agosto",
            trackingNote = "2 lecturas registradas desde entonces. Tu consumo bajó cerca de 8%, pero todavía necesitamos más lecturas para atribuir el cambio por completo: la temperatura exterior también fue menor.",
        ),
        PlanAction(
            id = PS5_ACTION_ID,
            title = "PlayStation 5 · configurar ahorro en reposo",
            savingsRangeLabel = "\$14–\$20",
            effort = EffortLevel.LOW,
            confidence = ConfidenceLevel.HIGH,
            status = PlanActionStatus.SUGGESTED,
            estimatedCutMxn = 17,
            trackingSinceLabel = "hoy",
            trackingNote = "Registra una lectura ahora para tener un punto de partida y poder comparar.",
        ),
        PlanAction(
            id = "computadora_suspender",
            title = "Computadora · suspender tras 10 minutos",
            savingsRangeLabel = "\$19",
            effort = EffortLevel.LOW,
            confidence = ConfidenceLevel.HIGH,
            status = PlanActionStatus.VERIFIED,
            estimatedCutMxn = 19,
            verifiedSinceLabel = "12 de junio, con 14 lecturas posteriores",
            verifiedNote = "Tu consumo nocturno bajó 0.4 kWh diarios de forma sostenida, equivalente a unos \$19 por periodo. Confianza alta: el cambio se mantuvo en semanas con clima parecido.",
        ),
    )

    val billHistory = listOf(
        BillPeriod("Feb", 612, consumptionKwh = 294),
        BillPeriod("Abr", 704, consumptionKwh = 336),
        BillPeriod("Jun", 798, consumptionKwh = 371),
        BillPeriod("Ago", BASE_PROJECTED_MXN, isProjected = true, consumptionKwh = 382),
    )

    val pastPeriodComparison = PastPeriodComparison(
        periodLabel = "18 abr – 18 jun",
        previousEstimateMxn = 772,
        actualBillMxn = 798,
        note = "Nuestra estimación quedó aproximadamente 3% por debajo del resultado real. Con ese dato ajustamos el modelo de tu minisplit.",
    )

    val recentReadings = listOf(
        HistoryReadingEntry(18426, "Hoy, 7:42 p. m.", ReadingMethod.DIAL, "+29 kWh desde la anterior"),
        HistoryReadingEntry(18397, "5 ago, 3:10 p. m.", ReadingMethod.MANUAL, "lectura editada"),
        HistoryReadingEntry(18368, "1 ago, 8:02 p. m.", ReadingMethod.PHOTO, "4 días sin lectura"),
    )
    const val RECENT_READINGS_COUNT_LABEL = "5 lecturas recientes"

    // DAC / average consumption
    const val ROLLING_AVERAGE_KWH = 872
    const val REFERENCE_LIMIT_KWH = 850
    const val KWH_OVER_LIMIT = 22
    const val KWH_TO_REDUCE = 28
    const val DAC_PERIODS_CONSIDERED = "9 de 12 meses"
    const val DAC_TARIFF_REVIEW_DATE = "12 jul 2026"

    data class TariffTier(val label: String, val fraction: Float, val amountLabel: String)
    val tariffTiers = listOf(
        TariffTier("Básico · 150 kWh", 0.23f, "\$147"),
        TariffTier("Intermedio · 177 kWh", 0.42f, "\$385"),
        TariffTier("Excedente · desde 410 kWh", 0.35f, "—"),
    )
    const val TARIFF_TODAY_MARKER_FRACTION = 0.62f

    // Solar
    const val ANNUAL_CONSUMPTION_KWH = 4820
    val solarScenarios = listOf(
        SolarScenario(
            name = "Escenario económico",
            tabLabel = "Económico",
            coverageLabel = "50%",
            panels = "4",
            systemPower = "1.9–2.2 kWp",
            area = "9–11 m²",
            production = "2,300–2,600 kWh/año",
            gridRemaining = "2,220–2,520 kWh/año",
            note = "Cubre la parte más constante de tu consumo con la menor inversión. Seguirías comprando bastante energía de la red.",
        ),
        SolarScenario(
            name = "Escenario recomendado",
            tabLabel = "Recomendado",
            coverageLabel = "80%",
            panels = "6–7",
            systemPower = "3.0–3.6 kWp",
            area = "14–18 m²",
            production = "3,650–4,150 kWh/año",
            gridRemaining = "670–1,170 kWh/año",
            note = "Equilibrio entre inversión y cobertura. Aunque la generación compense gran parte del consumo, pueden permanecer cargos aplicables.",
        ),
        SolarScenario(
            name = "Escenario de mayor cobertura",
            tabLabel = "Mayor cobertura", // adjustment #5: was "Máxima"
            coverageLabel = "95%",
            panels = "8",
            systemPower = "4.0–4.4 kWp",
            area = "19–23 m²",
            production = "4,350–4,800 kWh/año",
            gridRemaining = "0–470 kWh/año",
            note = "Se acerca al mínimo posible, pero aumenta el riesgo de sobredimensionar si tu consumo baja. No implica un recibo de cero pesos.",
        ),
    )
    const val DEFAULT_SOLAR_SCENARIO_INDEX = 1

    val monthlyEnergy = listOf(
        MonthlyEnergyPoint("Mar", 0.52f, 0.62f),
        MonthlyEnergyPoint("Abr", 0.58f, 0.70f),
        MonthlyEnergyPoint("May", 0.70f, 0.86f),
        MonthlyEnergyPoint("Jun", 0.92f, 0.88f),
        MonthlyEnergyPoint("Jul", 1.00f, 0.80f),
        MonthlyEnergyPoint("Ago", 0.64f, 0.66f),
    )
    const val SOLAR_CURRENT_KWP = "4.2 kWp"
    const val SOLAR_CURRENT_PANELS = "8 paneles"
    const val SOLAR_WITH_PLAN_KWP = "3.6 kWp"
    const val SOLAR_WITH_PLAN_PANELS = "7 paneles"

    // Onboarding household summary
    const val METER_TYPE_SELECTED = "De manecillas, 5 carátulas"

    // Confirm reading validation thresholds (mirrors the original prototype's simple sanity checks)
    const val ABNORMAL_RATE_THRESHOLD_KWH_PER_DAY = 20
    const val TYPICAL_DAILY_AVERAGE = 8.3
}
