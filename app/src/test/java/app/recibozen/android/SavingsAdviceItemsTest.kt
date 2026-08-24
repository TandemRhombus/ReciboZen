package app.recibozen.android

import app.recibozen.android.core.ai.ComfortImpact
import app.recibozen.android.core.ai.SavingsAction
import app.recibozen.android.core.ai.SavingsActionKind
import app.recibozen.android.core.ai.SavingsEffort
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.feature.plan.savingsAdviceItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun appliance(name: String, kwh: Int) = Appliance(
    id = name,
    name = name,
    room = "Toda la casa",
    costMxn = 0,
    kwh = kwh,
    sharePercent = 0,
    hoursPerWeek = null,
    confidence = ConfidenceLevel.LOW,
    category = ApplianceCategory.OTHER,
)

private fun action(index: Int, percent: Double) = SavingsAction(
    applianceIndex = index,
    kind = SavingsActionKind.SETTING,
    title = "Activa el modo ECO",
    howTo = "En el control remoto.",
    estimatedReductionPercent = percent,
    effort = SavingsEffort.ONE_TIME,
    comfortImpact = ComfortImpact.MINOR,
    targetSetting = null,
)

class SavingsAdviceItemsTest {

    private val sent = listOf(appliance("Minisplit", 340), appliance("Refrigerador", 120))

    @Test
    fun theSavingIsComputedFromOurOwnKwhNotReceivedFromTheServer() {
        val items = savingsAdviceItems(listOf(action(0, 20.0)), sent)

        assertEquals(1, items.size)
        assertEquals("Minisplit", items.single().applianceName)
        // 20% of the 340 kWh the app measured, not a figure a model chose.
        assertEquals(68.0, items.single().savedKwh, 0.001)
        assertEquals(340, items.single().applianceKwh)
    }

    @Test
    fun eachActionResolvesAgainstTheApplianceAtItsOwnIndex() {
        val items = savingsAdviceItems(listOf(action(1, 10.0), action(0, 50.0)), sent)

        assertEquals(listOf("Refrigerador", "Minisplit"), items.map { it.applianceName })
        assertEquals(12.0, items[0].savedKwh, 0.001)
        assertEquals(170.0, items[1].savedKwh, 0.001)
    }

    @Test
    fun anActionPointingNowhereIsDroppedRatherThanGuessedAt() {
        // Attaching advice to the wrong appliance is worse than one tip fewer.
        val items = savingsAdviceItems(listOf(action(0, 20.0), action(7, 30.0), action(-1, 15.0)), sent)

        assertEquals(1, items.size)
        assertEquals("Minisplit", items.single().applianceName)
    }

    @Test
    fun anEmptyInventoryProducesNoItemsInsteadOfCrashing() {
        assertTrue(savingsAdviceItems(listOf(action(0, 20.0)), emptyList()).isEmpty())
        assertTrue(savingsAdviceItems(emptyList(), sent).isEmpty())
    }
}
