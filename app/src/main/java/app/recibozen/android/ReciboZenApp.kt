package app.recibozen.android

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.CompositionLocalProvider
import app.recibozen.android.core.components.LocalRzCoachMarkState
import app.recibozen.android.core.components.RzCoachMarkOverlay
import app.recibozen.android.core.components.RzCoachStep
import app.recibozen.android.core.components.rememberRzCoachMarkState
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.data.repository.PersistentReciboZenRepository
import app.recibozen.android.feature.premium.PremiumLeaseRenewer
import kotlinx.coroutines.launch
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.ReciboZenNavHost
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.RzBottomNavBar
import app.recibozen.android.navigation.navigateToTourTab
import app.recibozen.android.navigation.RzTourAnchors
import app.recibozen.android.navigation.isBottomTabRoute

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
        // Only once hydrated: hydration writes the tier it read at startup, and a
        // renewal landing before it would be overwritten by that older value.
        (effectiveRepository as? PersistentReciboZenRepository)?.let { PremiumLeaseRenewalEffect(it) }
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val coachState = rememberRzCoachMarkState()
        // The tour runs once after onboarding, can be replayed from Perfil, and
        // skipping counts as finishing so it never nags.
        //
        // Being active and being drawn are two different things. The overlay
        // holds which step the user is on, so it must stay composed even while
        // the app is momentarily on a non-tab route — unmounting it there is
        // what used to restart the tour from step one.
        val tourActive = session.onboardingCompleted && !session.tutorialCompleted
        val tourVisible = isBottomTabRoute(currentRoute)

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
        if (tourActive) {
            RzCoachMarkOverlay(
                state = coachState,
                steps = tourSteps,
                currentRoute = currentRoute,
                onNavigate = navController::navigateToTourTab,
                onFinish = { effectiveRepository.setTutorialCompleted(true) },
                visible = tourVisible,
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
internal val tourSteps = listOf(
    RzCoachStep(RzTourAnchors.HOME_HEADLINE, R.string.coach_home_title, R.string.coach_home_body, Routes.Home),
    RzCoachStep(RzTourAnchors.HOME_REGISTER_READING, R.string.coach_reading_title, R.string.coach_reading_body, Routes.Home),
    RzCoachStep(RzTourAnchors.HOME_DAC, R.string.coach_dac_title, R.string.coach_dac_body, Routes.Home),
    RzCoachStep(RzTourAnchors.TAB_APPLIANCES, R.string.coach_tabs_title, R.string.coach_tabs_body, Routes.Home),
    RzCoachStep(RzTourAnchors.APPLIANCES_ADD, R.string.coach_appliances_title, R.string.coach_appliances_body, Routes.Appliances),
    RzCoachStep(RzTourAnchors.PLAN_ACTIONS, R.string.coach_plan_title, R.string.coach_plan_body, Routes.Plan),
    RzCoachStep(RzTourAnchors.HISTORY_IMPORT, R.string.coach_history_title, R.string.coach_history_body, Routes.History),
)

/**
 * Renews the premium lease every time the app comes to the foreground, if it is
 * missing or close to expiring. See [PremiumLeaseRenewer] for why this cannot be
 * left to the Premium screen.
 */
@Composable
private fun PremiumLeaseRenewalEffect(repository: PersistentReciboZenRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renewer = remember(repository) {
        PremiumLeaseRenewer(
            context = context.applicationContext,
            subject = repository::premiumSubject,
            leaseExpiresAtEpochSeconds = repository::premiumLeaseExpiresAtEpochSeconds,
            onSignedLease = repository::acceptPremiumLease,
        )
    }
    LifecycleStartEffect(renewer) {
        // Main, like the Premium screen: Billing calls back there, and the exchange
        // moves itself to IO for the network.
        val job = scope.launch { renewer.renewIfNeeded() }
        onStopOrDispose { job.cancel() }
    }
}
