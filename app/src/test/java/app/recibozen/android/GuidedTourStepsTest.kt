package app.recibozen.android

import app.recibozen.android.navigation.RzTourAnchors
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.isBottomTabRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour is only drawn on the four tab roots. A step pointing anywhere else
 * would leave the user staring at a dimmed screen with no card and no way out,
 * which is the failure mode that is hardest to notice in review and most
 * obvious to whoever installs the app.
 */
class GuidedTourStepsTest {
    @Test
    fun everyStepLivesOnAScreenWhereTheTourIsDrawn() {
        tourSteps.forEach { step ->
            assertTrue(
                "el paso ${step.anchorId} apunta a «${step.route}», que no es una pestaña",
                isBottomTabRoute(step.route),
            )
        }
    }

    @Test
    fun everyStepPointsAtAnAnchorTheAppDeclares() {
        val declared = setOf(
            RzTourAnchors.TAB_HOME,
            RzTourAnchors.TAB_APPLIANCES,
            RzTourAnchors.TAB_PLAN,
            RzTourAnchors.TAB_HISTORY,
            RzTourAnchors.HOME_HEADLINE,
            RzTourAnchors.HOME_DAC,
            RzTourAnchors.HOME_REGISTER_READING,
            RzTourAnchors.APPLIANCES_ADD,
            RzTourAnchors.HISTORY_IMPORT,
            RzTourAnchors.PLAN_ACTIONS,
        )

        tourSteps.forEach { step ->
            assertTrue("ancla desconocida: ${step.anchorId}", step.anchorId in declared)
        }
    }

    @Test
    fun noStepIsRepeatedAndTheTourIsNotEmpty() {
        assertTrue(tourSteps.isNotEmpty())
        assertEquals(tourSteps.size, tourSteps.map { it.anchorId }.distinct().size)
    }

    @Test
    fun profileIsNotATabRouteSoTheTourNeverDrawsOverIt() {
        // Replaying the tour from Perfil used to leave that screen saved under
        // Inicio; it came back on the first tab change and cancelled the tour.
        assertFalse(isBottomTabRoute(Routes.Profile))
        assertFalse(isBottomTabRoute(null))
        assertTrue(isBottomTabRoute(Routes.Home))
    }
}
