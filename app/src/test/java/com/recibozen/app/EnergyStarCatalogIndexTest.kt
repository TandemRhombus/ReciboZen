package com.recibozen.app

import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.data.catalog.EnergyStarCatalogIndex
import com.recibozen.app.data.catalog.EnergyStarCatalogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyStarCatalogIndexTest {
    private val records = listOf(
        record(
            id = "exact",
            category = "refrigeracion",
            subcategory = "refrigerador_residencial",
            brand = "Márca",
            model = "MX-100",
            annualKwh = 310.0,
        ),
        record(
            id = "prefix",
            category = "refrigeracion",
            subcategory = "refrigerador_residencial",
            brand = "Marca",
            model = "MX-100 PLUS",
            annualKwh = 330.0,
        ),
        record(
            id = "computer",
            category = "computo",
            subcategory = "computadora",
            brand = "Marca",
            model = "MX-100 PC",
            annualKwh = 90.0,
        ),
        record(
            id = "other-computer",
            category = "computo",
            subcategory = "computadora",
            brand = "Otra",
            model = "PC-200",
            annualKwh = 120.0,
        ),
    )
    private val index = EnergyStarCatalogIndex(records)

    @Test
    fun searchIgnoresAccentsAndRanksExactModelFirst() {
        val results = index.search("marca mx 100")

        assertEquals(3, results.size)
        assertEquals("energy_star_exact", results.first().id)
    }

    @Test
    fun categoryFilterAndShortQueryAreApplied() {
        assertTrue(index.search("m").isEmpty())

        val results = index.search("mx 100", ApplianceCategory.COMPUTING)

        assertEquals(listOf("energy_star_computer"), results.map { it.id })
    }

    @Test
    fun convertedResultKeepsSourceRangeAndMarketWarning() {
        val item = index.search("MX-100").first()

        assertEquals(310.0, item.annualKwh!!, 0.001)
        assertEquals(305.0, item.annualKwhMin!!, 0.001)
        assertEquals(320.0, item.annualKwhMax!!, 0.001)
        assertEquals(false, item.marketMexicoVerified)
        assertFalse(item.sourceUrl.isNullOrBlank())
    }

    @Test
    fun popularBrandsAreDerivedFromRecordsAndRespectCategory() {
        assertEquals("Marca", index.popularBrands().first())
        assertEquals(setOf("Marca", "Otra"), index.popularBrands(ApplianceCategory.COMPUTING).toSet())
        assertEquals(listOf("Marca"), index.popularBrands(ApplianceCategory.KITCHEN))
    }

    private fun record(
        id: String,
        category: String,
        subcategory: String,
        brand: String,
        model: String,
        annualKwh: Double,
    ) = EnergyStarCatalogRecord(
        id = id,
        sourceCategory = category,
        subcategory = subcategory,
        brand = brand,
        model = model,
        annualKwh = annualKwh,
        annualKwhMin = annualKwh - 5,
        annualKwhMax = annualKwh + 10,
        capacityValue = null,
        capacityUnit = null,
        modelFamily = false,
        marketMexicoVerified = false,
        sourceDatasetId = "abcd-1234",
        certifiedDate = "2026-01-01",
    )
}
