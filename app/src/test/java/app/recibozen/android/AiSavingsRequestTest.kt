package app.recibozen.android

import app.recibozen.android.core.ai.AiDacRisk
import app.recibozen.android.core.ai.aiSavingsRequest
import app.recibozen.android.core.calculation.DacRiskLevel
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun appliance(
    name: String,
    kwh: Int,
    category: ApplianceCategory = ApplianceCategory.OTHER,
    quantity: Int = 1,
    brand: String? = null,
    model: String? = null,
) = Appliance(
    id = name,
    name = name,
    room = "Toda la casa",
    costMxn = 0,
    kwh = kwh,
    sharePercent = 0,
    hoursPerWeek = null,
    confidence = ConfidenceLevel.LOW,
    category = category,
    quantity = quantity,
    brand = brand,
    model = model,
)

class AiSavingsRequestTest {

    @Test
    fun theHeaviestAppliancesGoFirstBecauseThatIsWhereTheBillIs() {
        val request = aiSavingsRequest(
            appliances = listOf(
                appliance("Television", 40),
                appliance("Minisplit", 340),
                appliance("Refrigerador", 120),
            ),
            periodDays = 60,
            kwhProjected = 800,
            dacRisk = DacRiskLevel.NORMAL,
        )

        assertEquals(listOf("Minisplit", "Refrigerador", "Television"), request.appliances.map { it.name })
        assertEquals(340.0, request.appliances.first().kwhPeriod, 0.001)
        assertTrue(request.isUsable)
    }

    @Test
    fun onlyTwelveAreSentBecauseTheIntentAcceptsTwelve() {
        val many = (1..30).map { appliance("Aparato $it", kwh = it * 10) }
        val request = aiSavingsRequest(many, periodDays = 60, kwhProjected = 5000, dacRisk = DacRiskLevel.NORMAL)

        assertEquals(12, request.appliances.size)
        // The heaviest twelve, not the first twelve.
        assertEquals("Aparato 30", request.appliances.first().name)
        assertEquals("Aparato 19", request.appliances.last().name)
        assertEquals(12, request.sent.size)
    }

    @Test
    fun anApplianceTooSmallToMatterDoesNotSpendASlot() {
        val request = aiSavingsRequest(
            appliances = listOf(appliance("Cargador", 0), appliance("Minisplit", 340)),
            periodDays = 60,
            kwhProjected = 800,
            dacRisk = DacRiskLevel.NORMAL,
        )
        assertEquals(listOf("Minisplit"), request.appliances.map { it.name })
    }

    @Test
    fun theQuantityTravelsInTheNameBecauseTheKwhCoversTheWholeRow() {
        val request = aiSavingsRequest(
            appliances = listOf(appliance("Foco LED", 30, quantity = 20)),
            periodDays = 60,
            kwhProjected = 800,
            dacRisk = DacRiskLevel.NORMAL,
        )
        assertEquals("Foco LED (x20)", request.appliances.single().name)
        assertEquals(30.0, request.appliances.single().kwhPeriod, 0.001)
    }

    @Test
    fun brandAndModelTravelWhenKnownSoTheAdviceCanNameTheRealSetting() {
        val request = aiSavingsRequest(
            appliances = listOf(
                appliance("Minisplit", 340, brand = "Mirage", model = "Magnum 19"),
                appliance("Refrigerador", 120, brand = "  "),
            ),
            periodDays = 60,
            kwhProjected = 800,
            dacRisk = DacRiskLevel.NORMAL,
        )
        assertEquals("Mirage", request.appliances[0].brand)
        assertEquals("Magnum 19", request.appliances[0].model)
        assertNull("a blank brand is not a brand", request.appliances[1].brand)
    }

    @Test
    fun everyDacLevelMapsToSomethingTheServerAccepts() {
        val expected = mapOf(
            DacRiskLevel.WATCH to AiDacRisk.APPROACHING,
            DacRiskLevel.RISK to AiDacRisk.AT_RISK,
            DacRiskLevel.ABOVE_LIMIT to AiDacRisk.AT_RISK,
            DacRiskLevel.ALREADY_DAC to AiDacRisk.IN_DAC,
            DacRiskLevel.NORMAL to AiDacRisk.NONE,
        )
        for ((level, risk) in expected) {
            val request = aiSavingsRequest(listOf(appliance("X", 10)), 60, 800, level)
            assertEquals("$level", risk, request.dacRisk)
        }
    }

    @Test
    fun anUnknownRiskIsNotReportedAsAnAlarmingOne() {
        // The app reports UNKNOWN until about a year of bills is imported.
        // Telling the model a household is at risk on no evidence would produce
        // urgent advice nobody can justify.
        for (level in listOf(DacRiskLevel.UNKNOWN, DacRiskLevel.UNSUPPORTED)) {
            val request = aiSavingsRequest(listOf(appliance("X", 10)), 60, 800, level)
            assertEquals("$level", AiDacRisk.NONE, request.dacRisk)
        }
    }

    @Test
    fun anEmptyOrUnmeasuredHouseholdIsNotWorthAsking() {
        assertFalse(aiSavingsRequest(emptyList(), 60, 800, DacRiskLevel.NORMAL).isUsable)
        assertFalse(aiSavingsRequest(listOf(appliance("X", 10)), 0, 800, DacRiskLevel.NORMAL).isUsable)
        assertFalse(aiSavingsRequest(listOf(appliance("X", 10)), 60, 0, DacRiskLevel.NORMAL).isUsable)
    }

    @Test
    fun theSentListLinesUpWithTheIndexesTheServerWillAnswerWith() {
        val request = aiSavingsRequest(
            appliances = listOf(appliance("Television", 40), appliance("Minisplit", 340)),
            periodDays = 60,
            kwhProjected = 800,
            dacRisk = DacRiskLevel.NORMAL,
        )
        // Index 0 in the answer must resolve to the same appliance we sent as 0,
        // or advice lands on the wrong device.
        assertEquals(request.appliances[0].name, request.sent[0].name)
        assertEquals("Minisplit", request.sent[0].name)
        assertEquals(request.appliances.size, request.sent.size)
    }
}
