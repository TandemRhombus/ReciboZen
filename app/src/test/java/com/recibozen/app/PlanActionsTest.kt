package com.recibozen.app

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceUsageModel
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.data.repository.SessionJsonCodec
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan used to render `state.planActions`, a list nothing ever populated, so
 * its suggestions section was permanently empty for a real household. Actions
 * are now derived from the appliance inventory through the advice engine.
 */
class PlanActionsTest {
    private class Repository : DemoReciboZenRepository() {
        fun setBills(bills: List<BillPeriod>) {
            mutableImportedBillHistory.value = bills
        }
    }

    private fun appliance(
        id: String,
        name: String,
        category: ApplianceCategory,
        watts: Int,
        hours: Int,
        standbyWatts: Int? = null,
    ) = Appliance(
        id = id,
        name = name,
        room = "Sala",
        costMxn = 0,
        kwh = 0,
        sharePercent = 0,
        hoursPerWeek = hours,
        confidence = ConfidenceLevel.MEDIUM,
        category = category,
        powerWatts = watts,
        standbyWatts = standbyWatts,
        standbyEnabled = standbyWatts != null,
        usageModel = ApplianceUsageModel.POWER_SCHEDULE,
        referencePeriodDays = 30,
    )

    private fun repositoryWithInventory(): Repository {
        val repository = Repository()
        repository.setBills(
            listOf(
                BillPeriod(
                    monthLabel = "May–Jul 26",
                    amountMxn = 2_274,
                    consumptionKwh = 646,
                    periodStartEpochDay = 20_584,
                    periodEndEpochDay = 20_646,
                ),
            ),
        )
        return repository
    }

    private val inventory = listOf(
        appliance("tv1", "Televisión sala", ApplianceCategory.ENTERTAINMENT, watts = 120, hours = 28, standbyWatts = 12),
        appliance("ac1", "Minisplit recámara", ApplianceCategory.CLIMATE, watts = 1_200, hours = 30),
        appliance("pc1", "Computadora", ApplianceCategory.COMPUTING, watts = 200, hours = 40),
    )

    @Test
    fun anEmptyInventoryProducesNoPlanInsteadOfPreloadedSuggestions() {
        val repository = repositoryWithInventory()

        assertTrue(repository.planActions(ReciboZenSessionState()).isEmpty())
    }

    @Test
    fun theInventoryProducesRealActionsRankedByTheAdviceEngine() {
        val repository = repositoryWithInventory()
        val state = ReciboZenSessionState(customAppliances = inventory)

        val actions = repository.planActions(state)

        assertTrue("debe haber al menos una acción", actions.isNotEmpty())
        assertTrue("cada acción pertenece a un aparato real", actions.all { action ->
            inventory.any { it.id == action.applianceId }
        })
        assertTrue("cada acción dice qué hacer", actions.all { it.description.isNotBlank() })
        assertEquals("ninguna arranca en seguimiento", 0, actions.count { it.status == PlanActionStatus.TRACKING })
    }

    @Test
    fun followingAnActionMarksItAndSurvivesARestart() {
        val repository = repositoryWithInventory()
        val state = ReciboZenSessionState(customAppliances = inventory)
        val target = repository.planActions(state).first()

        repository.togglePlanAction(target.id)
        val tracked = repository.session.value.trackedActionIds
        assertTrue(target.id in tracked)

        val decoded = SessionJsonCodec.decode(
            SessionJsonCodec.encode(repository.session.value.copy(customAppliances = inventory)),
        )
        assertTrue("la decisión debe persistir", target.id in decoded.trackedActionIds)
        assertEquals(
            PlanActionStatus.TRACKING,
            repository.planActions(decoded).first { it.id == target.id }.status,
        )
    }

    @Test
    fun followingAnActionAgainStopsFollowingIt() {
        val repository = repositoryWithInventory()
        val state = ReciboZenSessionState(customAppliances = inventory)
        val target = repository.planActions(state).first()

        repository.togglePlanAction(target.id)
        repository.togglePlanAction(target.id)

        assertFalse(target.id in repository.session.value.trackedActionIds)
    }

    @Test
    fun removingAnApplianceAlsoDropsItsTrackedActions() {
        val repository = repositoryWithInventory()
        repository.addAppliance(inventory.first())
        val action = repository.planActions().firstOrNull { it.applianceId == inventory.first().id }
        assertNotNull("el aparato agregado debe generar una acción", action)
        repository.togglePlanAction(action!!.id)
        assertTrue(action.id in repository.session.value.trackedActionIds)

        repository.removeAppliance(inventory.first().id)

        assertFalse(
            "un ahorro no puede seguir contando para un aparato que ya no existe",
            action.id in repository.session.value.trackedActionIds,
        )
    }

    @Test
    fun anActionIdCarriesTheApplianceSoTwoEqualDevicesDoNotShareIt() {
        val repository = repositoryWithInventory()
        val twoTelevisions = listOf(
            appliance("tv1", "Tele sala", ApplianceCategory.ENTERTAINMENT, watts = 120, hours = 28, standbyWatts = 12),
            appliance("tv2", "Tele recámara", ApplianceCategory.ENTERTAINMENT, watts = 120, hours = 28, standbyWatts = 12),
        )
        val state = ReciboZenSessionState(customAppliances = twoTelevisions)

        val actions = repository.planActions(state)

        assertEquals(actions.size, actions.map { it.id }.distinct().size)
    }
}
