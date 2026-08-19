package app.recibozen.android

import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.data.repository.SessionJsonCodec
import app.recibozen.android.demo.repository.DemoReciboZenRepository
import app.recibozen.android.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HonestDataDefaultsTest {
    @Test
    fun newInstallationStartsWithoutInventedCustomerOrConsumptionData() {
        val repository = DemoReciboZenRepository()
        val state = repository.session.value

        assertEquals("", state.userName)
        assertEquals("", state.householdName)
        assertEquals("", state.serviceNumber)
        assertTrue(state.readings.isEmpty())
        assertTrue(repository.electricServices.value.isEmpty())
        assertTrue(repository.appliances(state).isEmpty())
        assertTrue(repository.billHistory(state).isEmpty())
        assertFalse(repository.billProjection(state).hasUsageEvidence)
        assertEquals(0, repository.billProjection(state).projectedMxn)
    }

    @Test
    fun versionOneDesignFixturesAreRemovedAndOnboardingRunsAgain() {
        val decoded = SessionJsonCodec.decode(
            """{
                "onboardingCompleted": true,
                "onboardingStep": 3,
                "userName": "Emi",
                "householdName": "Casa",
                "serviceAlias": "Casa",
                "location": "Mérida",
                "tariff": "Tarifa 1C",
                "billingCycle": "Bimestral",
                "goalMxn": 780,
                "meterType": "De manecillas, 5 carátulas",
                "readings": [
                    {"kwh":18426,"dateLabel":"demo","method":"MANUAL","note":"demo"}
                ]
            }""".trimIndent(),
        )

        assertFalse(decoded.onboardingCompleted)
        assertEquals(0, decoded.onboardingStep)
        assertEquals("", decoded.userName)
        assertEquals("", decoded.householdName)
        assertEquals("", decoded.serviceAlias)
        assertEquals("", decoded.location)
        assertEquals("", decoded.tariff)
        assertEquals("", decoded.billingCycle)
        assertEquals(0, decoded.goalMxn)
        assertEquals("", decoded.meterType)
        assertTrue(decoded.readings.isEmpty())
        assertEquals(0, decoded.lastConfirmedReadingKwh)
    }

    @Test
    fun currentSchemaPreservesRealProfileAndPdfReading() {
        val state = ReciboZenSessionState(
            onboardingCompleted = true,
            userName = "Ana",
            householdName = "Taller",
            serviceAlias = "Medidor principal",
            serviceNumber = "510221105026",
            tariff = "1F",
            readings = listOf(
                HistoryReadingEntry(
                    kwh = 12_345,
                    dateLabel = "Recibo · 2026-07-10",
                    method = ReadingMethod.PDF,
                    note = "Importado",
                    timestampEpochMillis = 1_783_641_600_000,
                ),
            ),
            lastConfirmedReadingKwh = 12_345,
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))

        assertTrue(decoded.onboardingCompleted)
        assertEquals("Ana", decoded.userName)
        assertEquals("Taller", decoded.householdName)
        assertEquals("510221105026", decoded.serviceNumber)
        assertEquals("1F", decoded.tariff)
        assertEquals(ReadingMethod.PDF, decoded.readings.single().method)
        assertEquals(12_345, decoded.lastConfirmedReadingKwh)
    }

    @Test
    fun theGuidedTourIsPendingOnAFreshInstallAndSurvivesARestart() {
        val repository = DemoReciboZenRepository()
        assertFalse(repository.session.value.tutorialCompleted)

        repository.setTutorialCompleted(true)
        assertTrue(repository.session.value.tutorialCompleted)

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(repository.session.value))
        assertTrue("el tutorial no debe repetirse tras reiniciar", decoded.tutorialCompleted)

        // Replayed from Perfil.
        repository.setTutorialCompleted(false)
        assertFalse(repository.session.value.tutorialCompleted)
    }
}
