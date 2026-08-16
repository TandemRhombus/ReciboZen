package com.recibozen.app

import com.recibozen.app.core.calculation.TariffBlock
import com.recibozen.app.core.calculation.TariffCalibration
import com.recibozen.app.core.calculation.TariffRateSnapshot
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.RemoteTariffReference
import com.recibozen.app.demo.repository.TariffCalibrationState
import com.recibozen.app.demo.repository.TariffRateOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Precedence for the rates behind every estimate: the household's own bill,
 * then CFE's synced catalog, then the built-in generic blocks. The synced
 * catalog was being written to the database and never read, which left
 * households without a bill priced with generic numbers.
 */
class TariffRateOriginTest {
    private class Repository : DemoReciboZenRepository() {
        fun useRemote(reference: RemoteTariffReference?) {
            mutableRemoteTariffReference.value = reference
        }

        fun useCalibration(state: TariffCalibrationState?) {
            mutableTariffCalibration.value = state
        }
    }

    private val syncedTariffOne = RemoteTariffReference(
        tariffCode = "1",
        effectiveYearMonth = "2026-08",
        season = "NONE",
        blocks = listOf(
            TariffBlock(limitKwh = 75, rateMxnPerKwh = 1.0),
            TariffBlock(limitKwh = 140, rateMxnPerKwh = 2.0),
            TariffBlock(limitKwh = null, rateMxnPerKwh = 6.0),
        ),
        fixedChargeMxn = 0.0,
        sourceUrl = "https://app.cfe.mx/",
    )

    @Test
    fun withoutAnyReceiptTheSyncedCatalogPricesTheEstimate() {
        val repository = Repository()
        repository.setTariff("1")
        repository.useRemote(syncedTariffOne)

        assertEquals(TariffRateOrigin.REMOTE_CATALOG, repository.tariffRateOrigin())
        assertEquals(syncedTariffOne.blocks, repository.activeTariffBlocks())
        // 100 kWh = 75 × 1.0 + 25 × 2.0 = 125 MXN, plus 16% tax.
        assertEquals(145, repository.estimateCostMxn(100))
    }

    @Test
    fun theHouseholdsOwnReceiptOutranksTheSyncedCatalog() {
        val repository = Repository()
        repository.setTariff("1")
        repository.useRemote(syncedTariffOne)
        repository.useCalibration(
            TariffCalibrationState(
                snapshot = TariffRateSnapshot(
                    tariffCode = "1",
                    effectivePeriod = "Jul 26",
                    blocks = listOf(TariffBlock(limitKwh = null, rateMxnPerKwh = 3.0)),
                    fixedChargeMxn = 0.0,
                    taxRate = 0.0,
                ),
                coverage = TariffCalibration.Coverage.FULL,
                periodLabel = "Jul 26",
            ),
        )

        assertEquals(TariffRateOrigin.BILL, repository.tariffRateOrigin())
        assertEquals(300, repository.estimateCostMxn(100))
    }

    @Test
    fun aCatalogForAnotherTariffIsNeverUsed() {
        val repository = Repository()
        repository.setTariff("1C")
        repository.useRemote(syncedTariffOne)

        assertEquals(TariffRateOrigin.GENERIC, repository.tariffRateOrigin())
        assertNotEquals(syncedTariffOne.blocks, repository.activeTariffBlocks())
    }

    @Test
    fun aSeasonalCatalogWithoutAKnownSeasonFallsBackInsteadOfGuessing() {
        val repository = Repository()
        repository.setTariff("1C")
        repository.useRemote(
            syncedTariffOne.copy(tariffCode = "1C", season = "SUMMER"),
        )

        // Picking a season without the locality's summer start would bill the
        // wrong rate silently, so the estimate stays on the generic blocks.
        assertEquals(TariffRateOrigin.GENERIC, repository.tariffRateOrigin())
    }

    @Test
    fun anEmptyCatalogNeverReplacesTheFallback() {
        val repository = Repository()
        repository.setTariff("1")
        repository.useRemote(syncedTariffOne.copy(blocks = emptyList()))

        assertEquals(TariffRateOrigin.GENERIC, repository.tariffRateOrigin())
    }
}
