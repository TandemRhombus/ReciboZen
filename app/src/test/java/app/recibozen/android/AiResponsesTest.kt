package app.recibozen.android

import app.recibozen.android.core.ai.AiResponses
import app.recibozen.android.core.ai.ComfortImpact
import app.recibozen.android.core.ai.SavingsActionKind
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TODAY = 20_690L

class AiResponsesTest {

    private fun evidenceEntry(
        metric: String = "ANNUAL_KWH",
        value: Double = 420.0,
        methodology: String = "MODELED",
        low: Double? = 380.0,
        high: Double? = 470.0,
    ) = JSONObject()
        .put("metric", metric)
        .put("value", value)
        .put("methodology", methodology)
        .apply {
            low?.let { put("rangeMinValue", it) }
            high?.let { put("rangeMaxValue", it) }
        }

    private fun applianceBody(vararg entries: JSONObject) = JSONObject()
        .put("recognized", false)
        .put("displayName", "Refrigerador Mabe")
        .put("assumption", "Estimado por comparables.")
        .put("marketMexicoVerified", true)
        .put("evidence", JSONArray().apply { entries.forEach { put(it) } })

    @Test
    fun anAnswerBecomesEvidenceTheInventoryAlreadyKnowsHowToRank() {
        val parsed = AiResponses.parseApplianceEnergy(applianceBody(evidenceEntry()), TODAY)

        assertNotNull(parsed)
        val evidence = parsed!!.evidence.single()
        assertEquals(EvidenceMetric.ANNUAL_KWH, evidence.metric)
        assertEquals(420.0, evidence.value, 0.001)
        assertEquals(EvidenceMethodology.MODELED, evidence.methodology)
        assertEquals(TODAY, evidence.retrievedAtEpochDay)
        assertEquals(380.0, evidence.rangeMinValue!!, 0.001)
    }

    @Test
    fun theSourceIsStampedByUsAndNeverReadFromTheResponse() {
        // A server saying NOM_LABEL would have a guess outranking a reading the
        // household took with its own hands. The Worker already refuses to send
        // this; the client refuses to believe it either way.
        val lying = evidenceEntry().put("source", "NOM_LABEL").put("confidence", "HIGH")
        val parsed = AiResponses.parseApplianceEnergy(applianceBody(lying), TODAY)

        assertEquals(EvidenceSource.AI_SUGGESTION, parsed!!.evidence.single().source)
    }

    @Test
    fun aMethodologyOnlyAMeasurementCouldClaimIsRefused() {
        for (methodology in listOf("METERED", "STANDARD_TEST")) {
            val parsed = AiResponses.parseApplianceEnergy(
                applianceBody(evidenceEntry(methodology = methodology)),
                TODAY,
            )
            assertNull("$methodology must not survive", parsed)
        }
    }

    @Test
    fun unusableEntriesAreDroppedAndAWhollyUnusableAnswerIsNull() {
        val mixed = AiResponses.parseApplianceEnergy(
            applianceBody(
                evidenceEntry(metric = "KWH_PER_FORTNIGHT"),
                evidenceEntry(metric = "ACTIVE_WATTS", value = 150.0, low = 120.0, high = 180.0),
            ),
            TODAY,
        )
        assertEquals(1, mixed!!.evidence.size)
        assertEquals(EvidenceMetric.ACTIVE_WATTS, mixed.evidence.single().metric)

        assertNull(AiResponses.parseApplianceEnergy(applianceBody(evidenceEntry(value = -5.0)), TODAY))
        assertNull(AiResponses.parseApplianceEnergy(applianceBody(), TODAY))
        assertNull(AiResponses.parseApplianceEnergy(JSONObject().put("displayName", "X"), TODAY))
    }

    @Test
    fun anIncoherentRangeIsDiscardedAndTheValueSurvives() {
        val parsed = AiResponses.parseApplianceEnergy(
            applianceBody(evidenceEntry(low = 900.0, high = 1000.0)),
            TODAY,
        )
        val evidence = parsed!!.evidence.single()
        assertEquals(420.0, evidence.value, 0.001)
        assertNull("a range that excludes its own value is not a range", evidence.rangeMinValue)
    }

    @Test
    fun anAbsentMarketFlagStaysUnknownInsteadOfBecomingFalse() {
        val body = applianceBody(evidenceEntry())
        body.remove("marketMexicoVerified")
        assertNull(AiResponses.parseApplianceEnergy(body, TODAY)!!.marketMexicoVerified)

        val explicitlyFalse = applianceBody(evidenceEntry()).put("marketMexicoVerified", false)
        assertEquals(false, AiResponses.parseApplianceEnergy(explicitlyFalse, TODAY)!!.marketMexicoVerified)
    }

    @Test
    fun theMarketFlagInsideEvidenceAlsoStaysUnknownWhenAbsent() {
        // Same field name at two levels, and only one of them was covered:
        // DeviceEvidence.confidenceAsOf() treats a null market flag differently
        // from an explicit false, so collapsing them changes how the app ranks
        // the answer.
        val parsed = AiResponses.parseApplianceEnergy(applianceBody(evidenceEntry()), TODAY)
        assertNull(parsed!!.evidence.single().marketMexicoVerified)

        val stated = evidenceEntry().put("marketMexicoVerified", false)
        val withFlag = AiResponses.parseApplianceEnergy(applianceBody(stated), TODAY)
        assertEquals(false, withFlag!!.evidence.single().marketMexicoVerified)
    }

    @Test
    fun theQuotaIsReadSoTheAppCanShowTheMeter() {
        val body = applianceBody(evidenceEntry()).put(
            "quota",
            JSONObject().put("operation", "identify-appliance").put("used", 3).put("limit", 150)
                .put("resetsAtEpochSeconds", 1_788_220_800L),
        )
        val quota = AiResponses.parseApplianceEnergy(body, TODAY)!!.quota
        assertEquals(3, quota!!.used)
        assertEquals(150, quota.limit)
    }

    // --- Savings ---

    private fun action(
        index: Int = 0,
        kind: String = "SETTING",
        percent: Double = 20.0,
        target: JSONObject? = null,
    ) = JSONObject()
        .put("applianceIndex", index)
        .put("kind", kind)
        .put("title", "Activa el modo ECO")
        .put("howTo", "En el control remoto, boton ECO.")
        .put("estimatedReductionPercent", percent)
        .put("effort", "ONE_TIME")
        .put("comfortImpact", "MINOR")
        .apply { target?.let { put("target", it) } }

    private fun planBody(vararg actions: JSONObject) = JSONObject()
        .put("summary", "Tu aire se lleva casi la mitad.")
        .put("recommendations", JSONArray().apply { actions.forEach { put(it) } })

    @Test
    fun aSavingsPlanKeepsItsLinkToTheAppliance() {
        val plan = AiResponses.parseSavingsPlan(planBody(action()), applianceCount = 3)
        assertNotNull(plan)
        val first = plan!!.actions.single()
        assertEquals(0, first.applianceIndex)
        assertEquals(SavingsActionKind.SETTING, first.kind)
        assertEquals(ComfortImpact.MINOR, first.comfortImpact)
        assertEquals(20.0, first.estimatedReductionPercent, 0.001)
    }

    @Test
    fun anActionPointingAtAnApplianceWeNeverSentIsDropped() {
        val plan = AiResponses.parseSavingsPlan(planBody(action(index = 7), action(index = 1)), applianceCount = 3)
        assertEquals(1, plan!!.actions.size)
        assertEquals(1, plan.actions.single().applianceIndex)

        assertNull(AiResponses.parseSavingsPlan(planBody(action(index = 7)), applianceCount = 3))
        assertNull(AiResponses.parseSavingsPlan(planBody(action(index = -1)), applianceCount = 3))
    }

    @Test
    fun anImpossibleSavingIsDropped() {
        for (percent in listOf(0.0, 0.5, 61.0, 200.0, -10.0)) {
            assertNull("$percent must not survive", AiResponses.parseSavingsPlan(planBody(action(percent = percent)), 3))
        }
    }

    @Test
    fun theTargetFigureIsCarriedThroughWithItsSafetyFlag() {
        val plan = AiResponses.parseSavingsPlan(
            planBody(action(target = JSONObject().put("text", "60 °C o más").put("safetyLimit", true))),
            applianceCount = 1,
        )
        val target = plan!!.actions.single().targetSetting
        assertEquals("60 °C o más", target!!.text)
        assertTrue("a health limit must arrive marked as one", target.isSafetyLimit)
    }

    @Test
    fun anActionWithoutATargetSimplyHasNone() {
        val plan = AiResponses.parseSavingsPlan(planBody(action()), applianceCount = 1)
        assertNull(plan!!.actions.single().targetSetting)
    }

    @Test
    fun unknownEnumsAndEmptyPlansAreRefused() {
        assertNull(AiResponses.parseSavingsPlan(planBody(action(kind = "MAGIC")), 3))
        assertNull(AiResponses.parseSavingsPlan(planBody(), 3))
        assertNull(AiResponses.parseSavingsPlan(JSONObject().put("summary", "x"), 3))
        assertNull(AiResponses.parseSavingsPlan(JSONObject().put("recommendations", JSONArray()), 3))
    }
}
