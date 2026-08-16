package com.recibozen.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.CompositionLocalProvider
import com.recibozen.app.core.components.LocalRzCoachMarkState
import com.recibozen.app.core.components.RzCoachMarkOverlay
import com.recibozen.app.core.components.RzCoachStep
import com.recibozen.app.core.components.rememberRzCoachMarkState
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.data.repository.PersistentReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.ReciboZenNavHost
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.RzBottomNavBar
import com.recibozen.app.navigation.navigateToBottomTab
import com.recibozen.app.navigation.RzTourAnchors
import com.recibozen.app.navigation.isBottomTabRoute

@Composable
fun ReciboZenApp(repository: ReciboZenRepository? = null) {
    val context = LocalContext.current
    val effectiveRepository = repository ?: remember(context) {
        PersistentReciboZenRepository(context.applicationContext)
    }
    val session by effectiveRepository.session.collectAsStateWithLifecycle()

    ReciboZenTheme(themeMode = session.themeMode) {
        if (!session.isHydrated) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@ReciboZenTheme
        }
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val coachState = rememberRzCoachMarkState()
        // The tour runs once, on Inicio, after onboarding. It can be replayed
        // from Perfil, and skipping counts as finishing so it never nags.
        // The tour spans the four tab roots, so it stays visible while it
        // navigates between them, and hides inside any pushed flow.
        val showTour = session.onboardingCompleted &&
            !session.tutorialCompleted &&
            isBottomTabRoute(currentRoute)

        CompositionLocalProvider(LocalRzCoachMarkState provides coachState) {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // Tab roots receive the full bottom-bar height. Every other route
            // still receives the system navigation inset (3-button or gesture).
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            bottomBar = {
                if (isBottomTabRoute(currentRoute)) {
                    RzBottomNavBar(navController)
                }
            },
        ) { innerPadding ->
            ReciboZenNavHost(
                navController = navController,
                repository = effectiveRepository,
                innerPadding = innerPadding,
                startDestination = if (session.onboardingCompleted) Routes.Home else Routes.Onboarding,
            )
        }
        if (showTour) {
            RzCoachMarkOverlay(
                state = coachState,
                steps = tourSteps,
                currentRoute = currentRoute,
                onNavigate = navController::navigateToBottomTab,
                onFinish = { effectiveRepository.setTutorialCompleted(true) },
            )
        }
        }
        }
    }
}

/**
 * The tour walks the four tabs in the order a new household needs them:
 * understand the estimate, feed it, and then act on it.
 */
private val tourSteps = listOf(
    RzCoachStep(RzTourAnchors.HOME_HEADLINE, R.string.coach_home_title, R.string.coach_home_body, Routes.Home),
    RzCoachStep(RzTourAnchors.HOME_REGISTER_READING, R.string.coach_reading_title, R.string.coach_reading_body, Routes.Home),
    RzCoachStep(RzTourAnchors.HOME_DAC, R.string.coach_dac_title, R.string.coach_dac_body, Routes.Home),
    RzCoachStep(RzTourAnchors.TAB_APPLIANCES, R.string.coach_tabs_title, R.string.coach_tabs_body, Routes.Home),
    RzCoachStep(RzTourAnchors.APPLIANCES_ADD, R.string.coach_appliances_title, R.string.coach_appliances_body, Routes.Appliances),
    RzCoachStep(RzTourAnchors.PLAN_ACTIONS, R.string.coach_plan_title, R.string.coach_plan_body, Routes.Plan),
    RzCoachStep(RzTourAnchors.HISTORY_IMPORT, R.string.coach_history_title, R.string.coach_history_body, Routes.History),
)
