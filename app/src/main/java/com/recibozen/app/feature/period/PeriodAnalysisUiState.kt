package com.recibozen.app.feature.period

import com.recibozen.app.demo.data.DemoReciboZenData

/**
 * Everything the "Análisis del periodo" flow screen needs. The kWh
 * milestones (meta/proyección/excedente) and the cost-to-date figure mirror
 * the design mock 1:1 but aren't tracked in [DemoReciboZenData] (only the
 * MXN goal lives there), so they're scoped locally to this feature.
 */
data class PeriodAnalysisUiState(
    val lastReadingKwh: Int = 18426,
    val lastReadingDateLabel: String = "12 de agosto",
    val observedKwh: Int = DemoReciboZenData.OBSERVED_KWH_TODAY,
    val observedDays: Int = DemoReciboZenData.OBSERVED_DAYS,
    val costToDateMxn: Int = 487,
    val goalKwh: Int = 350,
    val projectedKwh: Int = 382,
    val surplusStartKwh: Int = 410,
)
