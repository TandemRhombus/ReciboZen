package app.recibozen.android

import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.data.catalog.CatalogSearchOutcome
import app.recibozen.android.data.catalog.EnergyStarCatalogIndex
import app.recibozen.android.data.catalog.EnergyStarCatalogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measured on the shipped 9,305-model dataset, a permissive matcher answered
 * "IEM refrigerador" with 4,535 records and not one of them was an IEM. The
 * point of these tests is that the search would rather return nothing than
 * return another brand's kWh/año, because that number does not stay in the
 * search screen: it goes to the inventory, the projection and the DAC risk.
 */
class CatalogSearchOutcomeTest {
    private val index = EnergyStarCatalogIndex(
        listOf(
            record("wp-1", "refrigeracion", "refrigerador_residencial", "Whirlpool", "WRT519SZDM", 480.0),
            record("wp-2", "refrigeracion", "refrigerador_residencial", "Whirlpool", "WRS325SDHZ", 610.0),
            record("sam-tv", "tv_entretenimiento", "televisor", "Samsung", "UN55TU8000", 95.0),
            record("sam-ref", "refrigeracion", "refrigerador_residencial", "Samsung", "RF28R7351SG", 630.0),
            record("lg-ac", "climatizacion", "aire_acondicionado_ventana_portatil", "LG", "LW1216ER", 900.0),
            record("hp-pc", "computo", "computadora", "HP", "ProDesk 400 G7", 120.0),
            record("mabe-ref", "refrigeracion", "refrigerador_residencial", "Mabe", "MEB1436", 520.0),
            // Real ENERGY STAR brand, one substitution away from the Spanish
            // word "horno". It is here to keep it from being one.
            record("forno", "cocina", "lavavajillas_residencial", "Forno", "FDW24", 260.0),
        ),
    )

    @Test
    fun aMisspelledBrandStillFindsItsOwnModels() {
        val outcome = index.searchOutcome("whirpool")

        assertTrue(outcome is CatalogSearchOutcome.Matches)
        val matches = outcome as CatalogSearchOutcome.Matches
        assertEquals(2, matches.items.size)
        assertTrue(matches.items.all { it.brand == "Whirlpool" })
    }

    @Test
    fun aMisspelledBrandWithAModelCodeKeepsTheModelFirst() {
        val outcome = index.searchOutcome("samsug rf28") as CatalogSearchOutcome.Matches

        assertEquals("energy_star_sam-ref", outcome.items.first().id)
        assertTrue(outcome.items.all { it.brand == "Samsung" })
    }

    @Test
    fun spanishProductWordsReachTheEnglishSubcategories() {
        assertEquals(
            listOf("energy_star_sam-tv"),
            (index.searchOutcome("televisión samsung") as CatalogSearchOutcome.Matches).items.map { it.id },
        )
        assertEquals(
            listOf("energy_star_sam-tv"),
            (index.searchOutcome("pantalla samsung 55 pulgadas") as CatalogSearchOutcome.Matches).items.map { it.id },
        )
        assertEquals(
            listOf("energy_star_lg-ac"),
            (index.searchOutcome("minisplit lg inverter") as CatalogSearchOutcome.Matches).items.map { it.id },
        )
        assertEquals(
            listOf("energy_star_hp-pc"),
            (index.searchOutcome("laptop hp") as CatalogSearchOutcome.Matches).items.map { it.id },
        )
    }

    @Test
    fun aBrandWeDoNotCarryReturnsNothingAndSaysWhich() {
        listOf(
            "Acros AT1330G" to "Acros",
            "IEM refrigerador" to "IEM",
            "Koblenz ventilador" to "Koblenz",
            "aire acondicionado mirage" to "Mirage",
            "Winia DFR-25210" to "Winia",
        ).forEach { (query, expectedBrand) ->
            val outcome = index.searchOutcome(query)
            assertTrue("«$query» debía responder que no tenemos la marca", outcome is CatalogSearchOutcome.BrandNotCovered)
            assertEquals(expectedBrand, (outcome as CatalogSearchOutcome.BrandNotCovered).brand)
        }
    }

    @Test
    fun aKnownBrandWithAnUnknownModelOffersOnlyThatBrand() {
        // The dataset has exactly one Mabe. Showing it beats showing nothing,
        // and showing a Frigidaire instead would be a wrong number.
        val outcome = index.searchOutcome("Mabe RMA1025YMXB") as CatalogSearchOutcome.Matches

        assertEquals(listOf("energy_star_mabe-ref"), outcome.items.map { it.id })
        assertEquals("Mabe", outcome.brand)
    }

    @Test
    fun withoutABrandOrAProductKindTheStrictRuleStays() {
        // "agua" appears in the water-heater subcategory text, so a loose match
        // would answer a water-pump query with water heaters.
        assertTrue(index.searchOutcome("bomba de agua") is CatalogSearchOutcome.NoMatches)
        assertTrue(index.searchOutcome("horno de microondas") is CatalogSearchOutcome.NoMatches)
    }

    @Test
    fun everydayWordsAreNotCorrectedIntoBrandsThatHappenToLookLikeThem() {
        // "horno" is one edit from the real brand "Forno". On the shipped
        // catalog that turned "horno de microondas" into 60 dishwashers.
        assertTrue(index.searchOutcome("horno de microondas") is CatalogSearchOutcome.NoMatches)
        assertTrue(index.searchOutcome("ventilador de techo") is CatalogSearchOutcome.NoMatches)
        assertTrue(index.searchOutcome("licuadora") is CatalogSearchOutcome.NoMatches)
    }

    @Test
    fun anExactModelCodeIsNotBuriedUnderTheRestOfTheBrand() {
        // Samsung has two models here. Asking for one of them by code must not
        // answer with both; on the real catalog it answered with 291.
        val outcome = index.searchOutcome("Samsung RF28R7351SG") as CatalogSearchOutcome.Matches

        assertEquals(listOf("energy_star_sam-ref"), outcome.items.map { it.id })
    }

    @Test
    fun modelCodesAreNeverCorrectedIntoBrands() {
        // "lw1216er" is 8 characters away from nothing in particular; if typo
        // correction touched model codes it would invent a brand nobody typed.
        val outcome = index.searchOutcome("LW1216ER") as CatalogSearchOutcome.Matches

        assertEquals(listOf("energy_star_lg-ac"), outcome.items.map { it.id })
        assertEquals(null, outcome.brand)
    }

    @Test
    fun aBrandWeDoCarryIsNeverReportedAsMissing() {
        // Samsung is in the catalog; the laundry filter is what emptied the list.
        // Saying "todavía no tenemos Samsung" would be a lie the user can check.
        assertTrue(
            index.searchOutcome("samsung", ApplianceCategory.LAUNDRY) is CatalogSearchOutcome.NoMatches,
        )
    }

    @Test
    fun theExplicitCategoryFilterStillWins() {
        assertEquals(
            listOf("energy_star_sam-tv"),
            (
                index.searchOutcome("samsung", ApplianceCategory.ENTERTAINMENT)
                    as CatalogSearchOutcome.Matches
                ).items.map { it.id },
        )
    }

    @Test
    fun oneCharacterIsNotAQuestionYet() {
        assertTrue(index.searchOutcome("s") is CatalogSearchOutcome.NotEnoughInput)
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
        annualKwhMin = annualKwh,
        annualKwhMax = annualKwh,
        capacityValue = null,
        capacityUnit = null,
        modelFamily = false,
        marketMexicoVerified = true,
        sourceDatasetId = "abcd-1234",
        certifiedDate = "2026-01-01",
    )
}
