package app.recibozen.android

import app.recibozen.android.core.calculation.ApplianceCatalog
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.data.repository.SessionJsonCodec
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplianceInventoryTest {
    @Test
    fun oneRowCanRepresentTwentyEqualBulbs() {
        val item = ApplianceCatalog.find("foco_led_9")!!
        val appliance = ApplianceCatalog.toAppliance(
            item = item,
            instanceId = "twenty_bulbs",
            room = "Sala",
            hoursPerWeek = 28,
            billingDays = 30,
            effectiveRateMxnPerKwh = 2.5,
            quantity = 20,
            spaceId = "living_room",
        )

        assertEquals(20, appliance.quantity)
        assertEquals(22, appliance.kwh)
        assertTrue(appliance.calculationNote.orEmpty().contains("20 unidades"))
    }

    @Test
    fun fixedCycleKwhAndQuantityUpdateForecast() {
        val repository = DemoReciboZenRepository()
        repository.addAppliance(fixedAppliance())

        repository.updateAppliance(
            applianceId = "fridge",
            quantity = 2,
            spaceId = "kitchen",
            room = "Cocina",
            brand = "Marca",
            model = "Modelo 1",
            usageModel = ApplianceUsageModel.PERIOD_KWH,
            hoursPerWeek = null,
            unitKwh = 42.0,
            dataSource = ApplianceDataSource.USER_MEASURED,
        )

        val updated = repository.appliance("fridge")!!
        assertEquals(2, updated.quantity)
        assertEquals(84, updated.kwh)
        assertEquals(84, repository.energySummary().applianceProjectedKwh)
        assertEquals(ConfidenceLevel.HIGH, updated.confidence)
    }

    @Test
    fun variableLoadApplianceCanUseAUserAdjustedAveragePower() {
        val repository = DemoReciboZenRepository()
        val catalogItem = ApplianceCatalog.find("pc_gaming")!!
        repository.addAppliance(
            ApplianceCatalog.toAppliance(
                item = catalogItem,
                instanceId = "gaming_pc",
                room = "Oficina",
                billingDays = 30,
                hoursPerWeek = 21,
            ),
        )

        repository.updateAppliance(
            applianceId = "gaming_pc",
            quantity = 1,
            spaceId = "home",
            room = "Oficina",
            brand = null,
            model = "Equipo con carga variable",
            usageModel = ApplianceUsageModel.POWER_SCHEDULE,
            hoursPerWeek = 21,
            powerWatts = 800,
            unitKwh = null,
            dataSource = ApplianceDataSource.USER_ESTIMATE,
            standbyEnabled = true,
            standbyWatts = 12,
        )

        val updated = repository.appliance("gaming_pc")!!
        assertEquals(800, updated.powerWatts)
        assertEquals(80, updated.kwh)
        assertEquals(ConfidenceLevel.LOW, updated.confidence)
        assertTrue(updated.calculationNote.orEmpty().contains("800 W promedio"))
        assertTrue(updated.standbyEnabled)
        assertEquals(12, updated.standbyWatts)
    }

    @Test
    fun removingSpaceReassignsItsAppliancesWithoutDeletingThem() {
        val repository = DemoReciboZenRepository()
        val office = repository.addHouseSpace("Oficina")!!
        repository.addAppliance(fixedAppliance().copy(spaceId = office.id, room = office.name))

        repository.renameHouseSpace(office.id, "Estudio")
        assertEquals("Estudio", repository.appliance("fridge")!!.room)

        repository.removeHouseSpace(office.id)
        val reassigned = repository.appliance("fridge")!!
        assertEquals("home", reassigned.spaceId)
        assertEquals("Toda la casa", reassigned.room)
        assertNotNull(reassigned)
    }

    @Test
    fun catalogIncludesManufacturerModelsWithAuditableConsumption() {
        assertTrue(ApplianceCatalog.items.size >= 140)
        val documentedModels = ApplianceCatalog.items.filter {
            it.brand != null && it.model != null && it.sourceUrl?.startsWith("https://") == true
        }
        assertTrue(documentedModels.size >= 10)
        assertTrue(documentedModels.count { it.annualKwh != null } >= 6)
        assertTrue(ApplianceCatalog.items.any { it.name == "Xbox Series X" && it.sourceUrl?.contains("microsoft.com") == true })
        assertTrue(ApplianceCatalog.items.any { it.id == "pc_gaming_entusiasta" && it.typicalWatts >= 600 })
        val entertainmentBrands = ApplianceCatalog.items
            .filter { it.category == ApplianceCategory.ENTERTAINMENT }
            .mapNotNull { it.brand }
            .toSet()
        assertTrue(entertainmentBrands.containsAll(setOf("Microsoft", "Sony", "Nintendo", "Valve")))
        val xbox = ApplianceCatalog.find("model_xbox_series_x")!!
        val ps5 = ApplianceCatalog.find("model_sony_ps5_cfi2016")!!
        val switch2 = ApplianceCatalog.find("model_nintendo_switch2")!!
        assertEquals(150, xbox.typicalWatts)
        assertEquals(217, ps5.typicalWatts)
        assertEquals(19, switch2.typicalWatts)
        assertTrue(listOf(xbox, ps5, switch2).all { it.sourceLabel?.contains("promedio", ignoreCase = true) == true })
        assertEquals(170, ApplianceCatalog.find("model_xbox_360_xenon")?.typicalWatts)
        assertEquals(102, ApplianceCatalog.find("model_xbox_360_jasper")?.typicalWatts)
        assertTrue(
            ApplianceCatalog.find("pc_gaming_entusiasta")?.sourceLabel
                ?.contains("no es potencia máxima", ignoreCase = true) == true,
        )
    }

    @Test
    fun persistenceRoundTripKeepsSpacesQuantityAndProvenance() {
        val state = ReciboZenSessionState(
            houseSpaces = listOf(HouseSpace("home", "Toda la casa"), HouseSpace("office", "Oficina")),
            solarDemandGrowthPercent = 40,
            solarPlannedLoadsKwh = mapOf("electric_car" to 2_500, "extra_inverter_ac" to 2_000),
            customAppliances = listOf(
                fixedAppliance().copy(
                    quantity = 3,
                    spaceId = "office",
                    room = "Oficina",
                    brand = "LG",
                    model = "GB35BVB",
                    dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
                    referenceAnnualKwh = 329.0,
                    referenceAnnualKwhMin = 320.0,
                    referenceAnnualKwhMax = 341.0,
                    marketMexicoVerified = false,
                    isModelFamily = true,
                    sourceLabel = "Ficha oficial",
                    sourceUrl = "https://example.com/model",
                    standbyWatts = 4,
                    standbyEnabled = true,
                ),
            ),
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))
        val appliance = decoded.customAppliances.single()
        assertEquals(2, decoded.houseSpaces.size)
        assertEquals(3, appliance.quantity)
        assertEquals("office", appliance.spaceId)
        assertEquals("LG", appliance.brand)
        assertEquals(ApplianceDataSource.MANUFACTURER_LABEL, appliance.dataSource)
        assertEquals(329.0, appliance.referenceAnnualKwh!!, 0.001)
        assertEquals(320.0, appliance.referenceAnnualKwhMin!!, 0.001)
        assertEquals(341.0, appliance.referenceAnnualKwhMax!!, 0.001)
        assertEquals(false, appliance.marketMexicoVerified)
        assertTrue(appliance.isModelFamily)
        assertTrue(appliance.standbyEnabled)
        assertEquals(4, appliance.standbyWatts)
        assertEquals(40, decoded.solarDemandGrowthPercent)
        assertEquals(2_500, decoded.solarPlannedLoadsKwh["electric_car"])
        assertEquals(2_000, decoded.solarPlannedLoadsKwh["extra_inverter_ac"])
    }

    @Test
    fun catalogProvenanceSurvivesConversionToInventory() {
        val source = app.recibozen.android.core.calculation.ApplianceCatalogItem(
            id = "certified_model",
            name = "Refrigerador",
            category = ApplianceCategory.KITCHEN,
            typicalWatts = 0,
            defaultHoursPerWeek = null,
            brand = "Marca",
            model = "ABC*",
            annualKwh = 300.0,
            annualKwhMin = 290.0,
            annualKwhMax = 315.0,
            marketMexicoVerified = false,
            isModelFamily = true,
        )

        val appliance = ApplianceCatalog.toAppliance(source, "instance", "Cocina")

        assertEquals(290.0, appliance.referenceAnnualKwhMin!!, 0.001)
        assertEquals(315.0, appliance.referenceAnnualKwhMax!!, 0.001)
        assertEquals(false, appliance.marketMexicoVerified)
        assertTrue(appliance.isModelFamily)
    }

    @Test
    fun schemaTwoApplianceRoomsBecomeManageableSpaces() {
        val decoded = SessionJsonCodec.decode(
            """{
                "schemaVersion":2,
                "customAppliances":[{
                    "id":"legacy-pump",
                    "name":"Bomba",
                    "room":"Patio trasero",
                    "costMxn":10,
                    "kwh":5,
                    "hours":2,
                    "confidence":"MEDIUM",
                    "category":"WATER",
                    "dataSource":"CATALOG"
                }]
            }""".trimIndent(),
        )

        assertTrue(decoded.onboardingCompleted.not())
        assertTrue(decoded.houseSpaces.any { it.name == "Patio trasero" })
        assertEquals("Patio trasero", decoded.customAppliances.single().room)
    }

    private fun fixedAppliance() = Appliance(
        id = "fridge",
        name = "Refrigerador",
        room = "Cocina",
        costMxn = 80,
        kwh = 30,
        sharePercent = 0,
        hoursPerWeek = null,
        confidence = ConfidenceLevel.MEDIUM,
        category = ApplianceCategory.KITCHEN,
        dataSource = ApplianceDataSource.CATALOG,
        usageModel = ApplianceUsageModel.PERIOD_KWH,
        unitKwh = 30.0,
        referencePeriodDays = 30,
    )
}
