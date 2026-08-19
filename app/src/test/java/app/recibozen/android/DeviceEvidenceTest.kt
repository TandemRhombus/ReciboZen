package app.recibozen.android

import app.recibozen.android.core.calculation.EvidenceResolution
import app.recibozen.android.core.calculation.EvidenceResolver
import app.recibozen.android.core.calculation.declaredEvidence
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.DeviceEvidence
import app.recibozen.android.core.model.EvidenceMethodology
import app.recibozen.android.core.model.EvidenceMetric
import app.recibozen.android.core.model.EvidenceSource
import app.recibozen.android.core.model.EvidenceUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evidence schema exists so that, when a language model starts writing
 * appliance data, it arrives at a house with rules already in place. These tests
 * are those rules.
 */
class DeviceEvidenceTest {

    // --- The unit can never contradict what is being measured ---

    @Test
    fun theUnitComesFromTheMetricSoTheTwoCannotDisagree() {
        assertEquals(EvidenceUnit.KWH_PER_YEAR, evidence(metric = EvidenceMetric.ANNUAL_KWH).unit)
        assertEquals(EvidenceUnit.WATTS, evidence(metric = EvidenceMetric.ACTIVE_WATTS).unit)
        assertEquals(EvidenceUnit.WATTS, evidence(metric = EvidenceMetric.STANDBY_WATTS).unit)
        assertEquals(EvidenceUnit.KWH_PER_CYCLE, evidence(metric = EvidenceMetric.KWH_PER_CYCLE).unit)
    }

    // --- Confidence is derived, never declared ---

    @Test
    fun aMeteredReadingAndAReadLabelAreTheOnlyThingsThatStartHigh() {
        assertEquals(
            ConfidenceLevel.HIGH,
            evidence(source = EvidenceSource.USER_MEASURED, methodology = EvidenceMethodology.METERED)
                .confidenceAsOf(TODAY),
        )
        assertEquals(
            ConfidenceLevel.HIGH,
            evidence(source = EvidenceSource.NOM_LABEL, methodology = EvidenceMethodology.STANDARD_TEST)
                .confidenceAsOf(TODAY),
        )
        assertEquals(
            ConfidenceLevel.MEDIUM,
            evidence(source = EvidenceSource.MANUFACTURER_SPEC, methodology = EvidenceMethodology.STANDARD_TEST)
                .confidenceAsOf(TODAY),
        )
    }

    @Test
    fun nothingAModelSuggestedCanEverReadAsConfident() {
        val best = evidence(
            source = EvidenceSource.AI_SUGGESTION,
            methodology = EvidenceMethodology.STANDARD_TEST,
            retrievedAtEpochDay = TODAY,
        )

        assertEquals(ConfidenceLevel.LOW, best.confidenceAsOf(TODAY))
    }

    @Test
    fun aNameplateRatingIsDemotedBecauseItStatesWhatTheDeviceMayDrawNotWhatItDoes() {
        assertEquals(
            ConfidenceLevel.MEDIUM,
            evidence(source = EvidenceSource.USER_MEASURED, methodology = EvidenceMethodology.NAMEPLATE)
                .confidenceAsOf(TODAY),
        )
    }

    @Test
    fun aFigureOlderThanFiveYearsStopsDescribingWhatIsOnSale() {
        val stale = evidence(
            source = EvidenceSource.NOM_LABEL,
            methodology = EvidenceMethodology.STANDARD_TEST,
            retrievedAtEpochDay = TODAY - DeviceEvidence.STALE_AFTER_DAYS - 1,
        )

        assertEquals(ConfidenceLevel.MEDIUM, stale.confidenceAsOf(TODAY))
    }

    @Test
    fun aMissingDateCostsNothingBecauseAbsenceIsNotEvidenceOfStaleness() {
        val undated = evidence(
            source = EvidenceSource.NOM_LABEL,
            methodology = EvidenceMethodology.STANDARD_TEST,
            retrievedAtEpochDay = null,
        )

        assertEquals(ConfidenceLevel.HIGH, undated.confidenceAsOf(TODAY))
    }

    @Test
    fun aRangeWhoseTopDoublesItsFloorIsTooWideToCallPrecise() {
        val vague = evidence(
            source = EvidenceSource.NOM_LABEL,
            methodology = EvidenceMethodology.STANDARD_TEST,
            value = 400.0,
            rangeMinValue = 200.0,
            rangeMaxValue = 900.0,
        )

        assertTrue(vague.hasRange)
        assertEquals(ConfidenceLevel.MEDIUM, vague.confidenceAsOf(TODAY))
    }

    // --- Agreement ---

    @Test
    fun valuesWithinTheToleranceAreTheSameFinding() {
        assertTrue(evidence(value = 340.0).agreesWith(evidence(value = 360.0)))
        assertFalse(evidence(value = 340.0).agreesWith(evidence(value = 520.0)))
    }

    @Test
    fun overlappingPublishedRangesAgreeEvenWhenTheMidpointsDoNot() {
        val a = evidence(value = 320.0, rangeMinValue = 300.0, rangeMaxValue = 380.0)
        val b = evidence(value = 400.0, rangeMinValue = 370.0, rangeMaxValue = 430.0)

        // 320 vs 400 is a 20% gap, past the tolerance, but both sources admit 370-380.
        assertTrue(a.agreesWith(b))
    }

    @Test
    fun differentMetricsAreNeverComparedToEachOther() {
        val annual = evidence(metric = EvidenceMetric.ANNUAL_KWH, value = 350.0)
        val watts = evidence(metric = EvidenceMetric.ACTIVE_WATTS, value = 350.0)

        assertFalse(annual.agreesWith(watts))
    }

    // --- Resolution ---

    @Test
    fun nothingSaidAboutAMetricResolvesToNothing() {
        assertEquals(
            EvidenceResolution.None,
            EvidenceResolver.resolve(emptyList(), EvidenceMetric.ANNUAL_KWH, TODAY),
        )
        assertEquals(
            EvidenceResolution.None,
            EvidenceResolver.resolve(
                listOf(evidence(metric = EvidenceMetric.ACTIVE_WATTS)),
                EvidenceMetric.ANNUAL_KWH,
                TODAY,
            ),
        )
    }

    @Test
    fun aSuggestionFromAModelNeverOverridesALabelSomebodyRead() {
        val label = evidence(
            source = EvidenceSource.NOM_LABEL,
            methodology = EvidenceMethodology.STANDARD_TEST,
            value = 340.0,
        )
        val guess = evidence(
            source = EvidenceSource.AI_SUGGESTION,
            methodology = EvidenceMethodology.MODELED,
            value = 520.0,
        )

        val resolution = EvidenceResolver.resolve(listOf(guess, label), EvidenceMetric.ANNUAL_KWH, TODAY)

        assertTrue(resolution is EvidenceResolution.Conflicted)
        assertEquals(340.0, (resolution as EvidenceResolution.Conflicted).winner.value, 0.001)
        assertEquals(EvidenceSource.NOM_LABEL, resolution.winner.source)
    }

    @Test
    fun contradictingSourcesAreNeverAveraged() {
        val label = evidence(source = EvidenceSource.NOM_LABEL, value = 340.0)
        val guess = evidence(source = EvidenceSource.AI_SUGGESTION, value = 520.0)

        val resolution = EvidenceResolver.resolve(listOf(label, guess), EvidenceMetric.ANNUAL_KWH, TODAY)
            as EvidenceResolution.Conflicted

        // 430.0 would be the average. It is a number nobody asserted.
        assertEquals(340.0, resolution.winner.value, 0.001)
        assertEquals(1, resolution.dissenting.size)
        assertEquals(520.0, resolution.dissenting.first().value, 0.001)
    }

    @Test
    fun theLoserIsKeptSoTheScreenCanSayTwoSourcesDisagree() {
        val resolution = EvidenceResolver.resolve(
            listOf(
                evidence(source = EvidenceSource.MANUFACTURER_SPEC, value = 300.0),
                evidence(source = EvidenceSource.CATEGORY_DEFAULT, value = 600.0),
            ),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Conflicted

        assertEquals(0.5, resolution.disagreementFraction, 0.001)
    }

    @Test
    fun aDisagreementCostsConfidenceEvenWhenTheWinnerIsStrong() {
        val alone = EvidenceResolver.resolve(
            listOf(evidence(source = EvidenceSource.NOM_LABEL, value = 340.0)),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Settled
        val contested = EvidenceResolver.resolve(
            listOf(
                evidence(source = EvidenceSource.NOM_LABEL, value = 340.0),
                evidence(source = EvidenceSource.AI_SUGGESTION, value = 900.0),
            ),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Conflicted

        assertEquals(ConfidenceLevel.HIGH, alone.confidence)
        assertEquals(ConfidenceLevel.MEDIUM, contested.confidence)
    }

    @Test
    fun anIndependentSourceSayingTheSameThingRaisesConfidence() {
        val corroborated = EvidenceResolver.resolve(
            listOf(
                evidence(source = EvidenceSource.MANUFACTURER_SPEC, value = 340.0),
                evidence(source = EvidenceSource.CERTIFICATION_CATALOG, value = 350.0),
            ),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Settled

        assertEquals(EvidenceSource.MANUFACTURER_SPEC, corroborated.winner.source)
        assertEquals(1, corroborated.corroborating.size)
        assertEquals(ConfidenceLevel.HIGH, corroborated.confidence)
    }

    @Test
    fun theSameSourceRepeatingItselfIsNotCorroboration() {
        val echoed = EvidenceResolver.resolve(
            listOf(
                evidence(source = EvidenceSource.MANUFACTURER_SPEC, value = 340.0),
                evidence(source = EvidenceSource.MANUFACTURER_SPEC, value = 345.0),
            ),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Settled

        assertEquals(ConfidenceLevel.MEDIUM, echoed.confidence)
    }

    @Test
    fun betweenEqualSourcesTheMoreRecentOneWins() {
        val old = evidence(source = EvidenceSource.NOM_LABEL, value = 300.0, retrievedAtEpochDay = TODAY - 400)
        val fresh = evidence(source = EvidenceSource.NOM_LABEL, value = 500.0, retrievedAtEpochDay = TODAY - 10)

        val resolution = EvidenceResolver.resolve(listOf(old, fresh), EvidenceMetric.ANNUAL_KWH, TODAY)
            as EvidenceResolution.Conflicted

        assertEquals(500.0, resolution.winner.value, 0.001)
    }

    @Test
    fun anUndatedAssertionNeverDisplacesADatedOneOfTheSameRank() {
        val dated = evidence(source = EvidenceSource.NOM_LABEL, value = 300.0, retrievedAtEpochDay = TODAY - 400)
        val undated = evidence(source = EvidenceSource.NOM_LABEL, value = 500.0, retrievedAtEpochDay = null)

        val resolution = EvidenceResolver.resolve(listOf(undated, dated), EvidenceMetric.ANNUAL_KWH, TODAY)
            as EvidenceResolution.Conflicted

        assertEquals(300.0, resolution.winner.value, 0.001)
    }

    @Test
    fun aFullTieResolvesTheSameWayEveryTime() {
        val a = evidence(source = EvidenceSource.CATEGORY_DEFAULT, value = 700.0, retrievedAtEpochDay = null)
        val b = evidence(source = EvidenceSource.CATEGORY_DEFAULT, value = 200.0, retrievedAtEpochDay = null)

        val forwards = EvidenceResolver.resolve(listOf(a, b), EvidenceMetric.ANNUAL_KWH, TODAY)
        val backwards = EvidenceResolver.resolve(listOf(b, a), EvidenceMetric.ANNUAL_KWH, TODAY)

        assertEquals(
            (forwards as EvidenceResolution.Conflicted).winner.value,
            (backwards as EvidenceResolution.Conflicted).winner.value,
            0.001,
        )
        assertEquals(200.0, forwards.winner.value, 0.001)
    }

    // --- The bridge from what the app already stores ---

    @Test
    fun anApplianceProjectsEverythingItAlreadyKnowsOntoTheSchema() {
        val items = appliance(
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
            referenceAnnualKwh = 340.0,
            powerWatts = 120,
            standbyWatts = 2,
        ).declaredEvidence()

        assertEquals(3, items.size)
        assertEquals(
            setOf(EvidenceMetric.ANNUAL_KWH, EvidenceMetric.ACTIVE_WATTS, EvidenceMetric.STANDBY_WATTS),
            items.map { it.metric }.toSet(),
        )
        assertTrue(items.all { it.source == EvidenceSource.MANUFACTURER_SPEC })
        assertTrue(items.all { it.methodology == EvidenceMethodology.STANDARD_TEST })
    }

    @Test
    fun anApplianceTheUserMeteredIsRecordedAsMetered() {
        val items = appliance(
            dataSource = ApplianceDataSource.USER_MEASURED,
            referenceAnnualKwh = 410.0,
        ).declaredEvidence()

        assertEquals(EvidenceSource.USER_MEASURED, items.single().source)
        assertEquals(EvidenceMethodology.METERED, items.single().methodology)
        assertEquals(ConfidenceLevel.HIGH, items.single().confidenceAsOf(TODAY))
    }

    @Test
    fun anApplianceTheUserGuessedIsRecordedAsDeclaredAndReadsAsLowConfidence() {
        val items = appliance(
            dataSource = ApplianceDataSource.USER_ESTIMATE,
            powerWatts = 90,
        ).declaredEvidence()

        assertEquals(EvidenceMethodology.DECLARED, items.single().methodology)
        assertEquals(ConfidenceLevel.LOW, items.single().confidenceAsOf(TODAY))
    }

    // --- The user's own configuration, checked against the model's figure ---

    @Test
    fun theScheduleTheUserConfiguredBecomesItsOwnAnnualAssertion() {
        val items = appliance(
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
            referenceAnnualKwh = 340.0,
            unitKwh = 30.0,
            referencePeriodDays = 30,
        ).declaredEvidence()

        val configured = items.single { it.source == EvidenceSource.USER_CONFIGURED }
        // 30 kWh over 30 days, held all year: 365.2425 kWh.
        assertEquals(365.2, configured.value, 0.1)
        assertEquals(EvidenceMetric.ANNUAL_KWH, configured.metric)
        assertEquals(EvidenceMethodology.MODELED, configured.methodology)
    }

    @Test
    fun aScheduleFarFromTheModelFigureSurfacesAsADisagreement() {
        val items = appliance(
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
            referenceAnnualKwh = 340.0,
            unitKwh = 50.0,
            referencePeriodDays = 30,
        ).declaredEvidence()

        val resolution = EvidenceResolver.resolve(items, EvidenceMetric.ANNUAL_KWH, TODAY)
            as EvidenceResolution.Conflicted

        // The published figure is what the card keeps showing; the configuration
        // is what gets questioned, never the other way round.
        assertEquals(EvidenceSource.MANUFACTURER_SPEC, resolution.winner.source)
        assertEquals(340.0, resolution.winner.value, 0.001)
        assertEquals(EvidenceSource.USER_CONFIGURED, resolution.dissenting.single().source)
        assertEquals(608.7, resolution.dissenting.single().value, 0.1)
    }

    @Test
    fun aScheduleThatMatchesTheModelFigureRaisesNoAlarm() {
        val items = appliance(
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
            referenceAnnualKwh = 350.0,
            unitKwh = 30.0,
            referencePeriodDays = 30,
        ).declaredEvidence()

        val resolution = EvidenceResolver.resolve(items, EvidenceMetric.ANNUAL_KWH, TODAY)

        assertTrue(resolution is EvidenceResolution.Settled)
    }

    @Test
    fun aTypedScheduleNeverOverridesALabelOrAMeasurement() {
        val configured = evidence(
            source = EvidenceSource.USER_CONFIGURED,
            methodology = EvidenceMethodology.MODELED,
            value = 900.0,
        )
        val measured = evidence(
            source = EvidenceSource.USER_MEASURED,
            methodology = EvidenceMethodology.METERED,
            value = 300.0,
        )

        val resolution = EvidenceResolver.resolve(
            listOf(configured, measured),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Conflicted

        assertEquals(300.0, resolution.winner.value, 0.001)
    }

    @Test
    fun aScheduleStillOutranksSomethingAModelInvented() {
        val guess = evidence(source = EvidenceSource.AI_SUGGESTION, value = 900.0)
        val configured = evidence(source = EvidenceSource.USER_CONFIGURED, value = 300.0)

        val resolution = EvidenceResolver.resolve(
            listOf(guess, configured),
            EvidenceMetric.ANNUAL_KWH,
            TODAY,
        ) as EvidenceResolution.Conflicted

        assertEquals(EvidenceSource.USER_CONFIGURED, resolution.winner.source)
    }

    @Test
    fun anApplianceWithNoReferenceDataAssertsNothingRatherThanAssertingZero() {
        assertTrue(appliance().declaredEvidence().isEmpty())
        assertTrue(appliance(powerWatts = 0, standbyWatts = 0).declaredEvidence().isEmpty())
    }

    private fun evidence(
        metric: EvidenceMetric = EvidenceMetric.ANNUAL_KWH,
        value: Double = 350.0,
        source: EvidenceSource = EvidenceSource.CERTIFICATION_CATALOG,
        methodology: EvidenceMethodology = EvidenceMethodology.STANDARD_TEST,
        retrievedAtEpochDay: Long? = TODAY - 30,
        rangeMinValue: Double? = null,
        rangeMaxValue: Double? = null,
    ) = DeviceEvidence(
        metric = metric,
        value = value,
        source = source,
        methodology = methodology,
        retrievedAtEpochDay = retrievedAtEpochDay,
        rangeMinValue = rangeMinValue,
        rangeMaxValue = rangeMaxValue,
    )

    private fun appliance(
        dataSource: ApplianceDataSource = ApplianceDataSource.CATALOG,
        referenceAnnualKwh: Double? = null,
        powerWatts: Int? = null,
        standbyWatts: Int? = null,
        unitKwh: Double? = null,
        referencePeriodDays: Int = 30,
    ) = Appliance(
        id = "test",
        name = "Refrigerador",
        room = "Cocina",
        costMxn = 0,
        kwh = 0,
        sharePercent = 0,
        hoursPerWeek = null,
        confidence = ConfidenceLevel.MEDIUM,
        dataSource = dataSource,
        referenceAnnualKwh = referenceAnnualKwh,
        powerWatts = powerWatts,
        standbyWatts = standbyWatts,
        unitKwh = unitKwh,
        referencePeriodDays = referencePeriodDays,
    )

    private companion object {
        /** 1 Aug 2026, as an epoch day. Fixed so staleness is testable. */
        const val TODAY = 20_666L
    }
}
