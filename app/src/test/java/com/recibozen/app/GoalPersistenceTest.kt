package com.recibozen.app

import com.recibozen.app.data.repository.SessionJsonCodec
import com.recibozen.app.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-service goal map reused a helper written for weekly hours, which
 * clamped every value to 0..168. A goal of $800 came back as $168 on the next
 * launch, and the active service then copied that number over the good one.
 */
class GoalPersistenceTest {
    @Test
    fun aGoalAboveTheWeeklyHoursCeilingSurvivesARestart() {
        val state = ReciboZenSessionState(
            activeServiceId = "svc-1",
            goalMxn = 1_450,
            goalMxnByService = mapOf("svc-1" to 1_450, "svc-2" to 980),
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))

        assertEquals(1_450, decoded.goalMxn)
        assertEquals(1_450, decoded.goalMxnByService["svc-1"])
        assertEquals(980, decoded.goalMxnByService["svc-2"])
    }

    @Test
    fun weeklyHoursAreStillCappedAtOneWeek() {
        val state = ReciboZenSessionState(
            applianceHoursPerWeek = mapOf("tv" to 40, "imposible" to 500),
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))

        assertEquals(40, decoded.applianceHoursPerWeek["tv"])
        assertEquals("una semana no tiene más de 168 horas", 168, decoded.applianceHoursPerWeek["imposible"])
    }

    @Test
    fun goalsSavedByTheBrokenBuildAreDiscardedInsteadOfKeepingTheCappedValue() {
        // What the previous build left on disk: a real goal in the scalar and a
        // capped copy in the map. Trusting the map would show $168 forever.
        val corrupted = """
            {
              "schemaVersion": 4,
              "activeServiceId": "svc-1",
              "goalMxn": 1450,
              "goalMxnByService": {"svc-1": 168}
            }
        """.trimIndent()

        val decoded = SessionJsonCodec.decode(corrupted)

        assertEquals("la meta real nunca se recortó", 1_450, decoded.goalMxn)
        assertTrue("el mapa dañado debe descartarse", decoded.goalMxnByService.isEmpty())
    }

    @Test
    fun aGoalOfExactlyOneHundredSixtyEightIsKeptWhenItWasSavedByTheFixedBuild() {
        val state = ReciboZenSessionState(
            activeServiceId = "svc-1",
            goalMxn = 168,
            goalMxnByService = mapOf("svc-1" to 168),
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))

        assertEquals(168, decoded.goalMxnByService["svc-1"])
    }

    @Test
    fun theMeterTypeAndCityOfEachSupplySurviveTooo() {
        val state = ReciboZenSessionState(
            activeServiceId = "svc-1",
            meterType = "De manecillas, 5 carátulas",
            location = "Mérida",
            meterTypeByService = mapOf("svc-1" to "De manecillas, 5 carátulas", "svc-2" to "Digital"),
            locationByService = mapOf("svc-1" to "Mérida", "svc-2" to "Monterrey"),
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(state))

        assertEquals("Digital", decoded.meterTypeByService["svc-2"])
        assertEquals("Monterrey", decoded.locationByService["svc-2"])
    }
}
