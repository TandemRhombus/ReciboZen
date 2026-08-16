package com.recibozen.app

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.HouseSpace
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

        val result = filterAppliances(appliances, filterIndex = 5, spaces = listOf(patio, office))

        assertEquals(listOf("pc"), result.map { it.id })
    }
}
