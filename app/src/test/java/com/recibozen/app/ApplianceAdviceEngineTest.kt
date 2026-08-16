package com.recibozen.app

import com.recibozen.app.core.calculation.ApplianceAdviceEngine
import com.recibozen.app.core.calculation.ApplianceAdviceEvidence
import com.recibozen.app.core.calculation.ApplianceAdvicePriority
import com.recibozen.app.core.calculation.ApplianceCatalog
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplianceAdviceEngineTest {
    @Test
    fun everyCatalogDeviceGetsAtLeastOneSavingsAction() {
        ApplianceCatalog.items.forEach { item ->
            val appliance = ApplianceCatalog.toAppliance(
                item = item,
                instanceId = item.id,
                room = "Hogar",
                billingDays = 60,
            )

            assertTrue("Missing advice for ${item.id}", ApplianceAdviceEngine.recommendations(appliance).isNotEmpty())
        }
    }

    @Test
    fun refrigeratorNeverSuggestsBlindDisconnection() {
        val refrigerator = appliance(
            name = "Refrigerador",
            category = ApplianceCategory.KITCHEN,
            standbyWatts = 12,
            hoursPerWeek = 10,
            isEssential = true,
        )

        val advice = ApplianceAdviceEngine.recommendations(refrigerator)

        assertTrue(advice.any { it.title.contains("sin desconectar", ignoreCase = true) })
        assertTrue(advice.none { it.description.contains("regleta", ignoreCase = true) })
    }

    @Test
    fun highStandbyUsesCapturedWattsAndQuantifiesAvoidableEnergy() {
        val decoder = appliance(
            name = "Decodificador de TV",
            category = ApplianceCategory.ENTERTAINMENT,
            standbyWatts = 8,
            hoursPerWeek = 35,
            costMxn = 80,
            kwh = 20,
        )

        val advice = ApplianceAdviceEngine.recommendations(decoder, billingDays = 60)
            .first { it.evidence == ApplianceAdviceEvidence.DEVICE_DATA }

        assertEquals(ApplianceAdvicePriority.HIGH, advice.priority)
        assertTrue(advice.description.contains("8 W"))
        assertTrue((advice.estimatedSavingsKwh ?: 0.0) > 7.0)
        assertNotNull(advice.estimatedSavingsMxn)
    }

    @Test
    fun exactPs5ModelGetsOfficialMenuRouteAndSource() {
        val ps5Item = ApplianceCatalog.find("model_sony_ps5_cfi2016")!!
        val ps5 = ApplianceCatalog.toAppliance(ps5Item, "my-ps5", "Sala", billingDays = 60)

        val advice = ApplianceAdviceEngine.recommendations(ps5).first()

        assertEquals(ApplianceAdviceEvidence.MODEL, advice.evidence)
        assertTrue(advice.description.contains("Configuración → Sistema → Ahorro de energía"))
        assertTrue(advice.sourceUrl.orEmpty().startsWith("https://www.playstation.com/"))
    }

    @Test
    fun samsungTvRouteIsNotAppliedToAnUnbrandedTv() {
        val samsung = appliance(
            name = "Televisión LED",
            category = ApplianceCategory.ENTERTAINMENT,
            brand = "Samsung",
        )
        val generic = samsung.copy(id = "generic", brand = null)

        assertTrue(ApplianceAdviceEngine.recommendations(samsung).any { it.id == "samsung_tv_energy_saving" })
        assertTrue(ApplianceAdviceEngine.recommendations(generic).none { it.id == "samsung_tv_energy_saving" })
    }

    @Test
    fun inventoryRankingPrioritizesDataBackedStandbyOpportunity() {
        val decoder = appliance(
            id = "decoder",
            name = "Decodificador",
            category = ApplianceCategory.ENTERTAINMENT,
            standbyWatts = 10,
            hoursPerWeek = 10,
        )
        val lamp = appliance(
            id = "lamp",
            name = "Foco LED",
            category = ApplianceCategory.LIGHTING,
            standbyWatts = null,
            hoursPerWeek = 28,
        )

        val ranked = ApplianceAdviceEngine.rankInventory(listOf(lamp, decoder), billingDays = 60)

        assertEquals("decoder", ranked.first().applianceId)
    }

    private fun appliance(
        id: String = "device",
        name: String,
        category: ApplianceCategory,
        brand: String? = null,
        standbyWatts: Int? = null,
        hoursPerWeek: Int? = 20,
        costMxn: Int = 100,
        kwh: Int = 40,
        isEssential: Boolean = false,
    ) = Appliance(
        id = id,
        name = name,
        room = "Hogar",
        costMxn = costMxn,
        kwh = kwh,
        sharePercent = 10,
        hoursPerWeek = hoursPerWeek,
        confidence = ConfidenceLevel.MEDIUM,
        category = category,
        brand = brand,
        standbyWatts = standbyWatts,
        isEssential = isEssential,
        referencePeriodDays = 60,
    )
}
