package com.recibozen.app

import com.recibozen.app.core.importer.BillParseResult
import com.recibozen.app.core.importer.CfeBillTextParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CfeBillTextParserTest {
    private val parser = CfeBillTextParser()

    @Test fun parsesLowDomesticReceiptAndItsHistory() = assertReceipt(
        domesticLow, "1", 124, 174.0, 1, LocalDate.of(2026, 4, 7), LocalDate.of(2026, 6, 8),
    )
    @Test fun parsesTieredDomesticReceiptAndItsHistory() = assertReceipt(
        domesticHigh, "1", 646, 2_274.0, 3, LocalDate.of(2026, 5, 13), LocalDate.of(2026, 7, 14),
    )
    @Test fun parsesSeasonalReceiptAndItsHistory() = assertReceipt(
        seasonal, "1B", 936, 2_948.0, 6, LocalDate.of(2026, 4, 7), LocalDate.of(2026, 6, 8),
    )
    @Test fun parsesPdbtReceiptAndItsHistory() = assertReceipt(
        pdbt, "PDBT", 858, 4_396.0, 2, LocalDate.of(2026, 4, 7), LocalDate.of(2026, 6, 8),
    )
    @Test fun parsesDacEnergyRateWithoutConfusingTheMeterReadingRow() = assertReceipt(
        dac, "DAC", 500, 3_619.0, 2, LocalDate.of(2026, 4, 7), LocalDate.of(2026, 6, 8),
    )

    @Test fun ignoresLongIdentifiersAndDateLikeTriplesBetweenReadingColumns() {
        val reorderedText = domesticLow.replace(
            "Energía (kWh) 2,317 2,193 124",
            "Energía (kWh)\n08 07 1\n096240255658\n2,317 2,193 124",
        )

        assertReceipt(
            reorderedText, "1", 124, 174.0, 1, LocalDate.of(2026, 4, 7), LocalDate.of(2026, 6, 8),
        )
    }

    private fun assertReceipt(
        text: String,
        tariff: String,
        kwh: Int,
        total: Double,
        minimumCharges: Int,
        expectedStart: LocalDate,
        expectedEnd: LocalDate,
    ) {
        val result = parser.parse(text)
        assertTrue(result is BillParseResult.Success)
        val bill = (result as BillParseResult.Success).bill
        assertEquals(tariff, bill.tariffCode)
        assertEquals(kwh, bill.consumptionKwh)
        assertEquals(total, bill.totalToPayMxn, 0.001)
        assertEquals(expectedStart, bill.periodStart)
        assertEquals(expectedEnd, bill.periodEnd)
        assertEquals(11, bill.history.size)
        assertTrue(bill.charges.size >= minimumCharges)
        assertTrue(bill.warnings.isEmpty())
    }

    private val history = """
        CONSUMO HISTÓRICO
        del 05 FEB 26 al 07 ABR 26 164 ${'$'}234.00 ${'$'}234.00
        del 05 DIC 25 al 05 FEB 26 342 ${'$'}736.00 ${'$'}736.00
        del 08 OCT 25 al 05 DIC 25 86 ${'$'}119.00 ${'$'}119.00
        del 07 AGO 25 al 08 OCT 25 93 ${'$'}128.00 ${'$'}128.00
        del 06 JUN 25 al 07 AGO 25 96 ${'$'}131.00 ${'$'}131.00
        del 04 ABR 25 al 06 JUN 25 100 ${'$'}135.00 ${'$'}135.00
        del 05 FEB 25 al 04 ABR 25 114 ${'$'}153.00 ${'$'}153.00
        del 05 DIC 24 al 05 FEB 25 129 ${'$'}173.00 ${'$'}173.00
        del 09 OCT 24 al 05 DIC 24 113 ${'$'}149.00 ${'$'}149.00
        del 08 AGO 24 al 09 OCT 24 284 ${'$'}423.00 ${'$'}423.00
        del 06 JUN 24 al 08 AGO 24 253 ${'$'}359.00 ${'$'}359.00
    """.trimIndent()

    private val domesticLow = base("000000000001", "01", "MTR001", "2,317", "2,193", "124", "174") + "\nBasico 124 1.122 139.12\n" + history
    private val domesticHigh = base(
        "000000000002", "01", "MTR002", "9,803", "9,157", "646", "2,274",
        period = "13 MAY 26-14 JUL 26",
    ) +
        "\nBasico 150 1.125 168.75\nIntermedio 130 1.369 177.97\nExcedente 366 4.004 1,465.46\n" + history
    private val seasonal = base("000000000003", "1B", "MTR003", "4,332", "3,396", "936", "2,948", wires = 2) +
        "\nBasico 57 1.119 64.34 157 1.004 157.89\nIntermedio 76 1.361 104.34 125 1.163 146.31\nExcedente 212 3.980 847.05 305 3.992 1,221.26\n" + history
    private val pdbt = base("000000000004", "PDBT", "MTR004", "1,632", "774", "858", "4,396", wires = 3) +
        "\nCargo Fijo((3)) 66.14\nEnergia 3,723.13\n" + history
    private val dac = base("000000000005", "DAC", "MTR005", "5,500", "5,000", "500", "3,619") +
        "\nCargo Fijo 120.00\nEnergia 500 6.000 3,000.00\n" + history

    private fun base(
        service: String,
        tariff: String,
        meter: String,
        current: String,
        previous: String,
        kwh: String,
        total: String,
        wires: Int = 1,
        period: String = "07 ABR 26-08 JUN 26",
    ) = """
        TOTAL A PAGAR:
        ${'$'}$total
        NO. DE SERVICIO:$service
        RMU:00000 00-00-00 XAXX-010101 001 CFE
        CUENTA:DEMO
        TARIFA:${tariff}NO. MEDIDOR:$meter
        MULTIPLICADOR:1
        NO HILOS:$wires
        PERIODO FACTURADO:$period
        Energía (kWh) $current $previous $kwh
    """.trimIndent()
}
