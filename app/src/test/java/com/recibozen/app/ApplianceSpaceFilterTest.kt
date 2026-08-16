package com.recibozen.app

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.HouseSpace
import com.recibozen.app.feature.appliances.ApplianceSort
import com.recibozen.app.feature.appliances.filterAppliances
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplianceSpaceFilterTest {
    @Test
    fun configuredSpacesBecomeRealInventoryFilters() {
        val patio = HouseSpace("patio", "Patio")
        val office = HouseSpace("office", "Oficina")
        val appliances = listOf(
            Appliance(id = "pump", name = "Bomba", room = "Patio", costMxn = 100, kwh = 30, sharePercent = 10, hoursPerWeek = null, confidence = ConfidenceLevel.MEDIUM, spaceId = patio.id),
            Appliance(id = "pc", name = "PC", room = "Oficina", costMxn = 300, kwh = 80, sharePercent = 20, hoursPerWeek = null, confidence = ConfidenceLevel.MEDIUM, spaceId = office.id),
        )

        val result = filterAppliances(appliances, office.id, ApplianceSort.COST, listOf(patio, office))

        assertEquals(listOf("pc"), result.map { it.id })
    }

    @Test
    fun spaceAndSortAreIndependentSoARoomCanStillBeOrderedByCost() {
        val kitchen = HouseSpace("kitchen", "Cocina")
        val appliances = listOf(
            appliance("micro", "Microondas", kitchen.id, cost = 40),
            appliance("fridge", "Refrigerador", kitchen.id, cost = 220),
            appliance("tv", "Televisión", "living", cost = 500),
        )

        val result = filterAppliances(appliances, kitchen.id, ApplianceSort.COST, listOf(kitchen))

        assertEquals(listOf("fridge", "micro"), result.map { it.id })
    }

    @Test
    fun noSpaceSelectedKeepsEveryAppliance() {
        val kitchen = HouseSpace("kitchen", "Cocina")
        val appliances = listOf(
            appliance("micro", "Microondas", kitchen.id, cost = 40),
            appliance("tv", "Televisión", "living", cost = 500),
        )

        val result = filterAppliances(appliances, null, ApplianceSort.COST, listOf(kitchen))

        assertEquals(listOf("tv", "micro"), result.map { it.id })
    }

    private fun appliance(id: String, name: String, spaceId: String, cost: Int) = Appliance(
        id = id,
        name = name,
        room = "",
        costMxn = cost,
        kwh = cost,
        sharePercent = 0,
        hoursPerWeek = null,
        confidence = ConfidenceLevel.MEDIUM,
        spaceId = spaceId,
    )
}
