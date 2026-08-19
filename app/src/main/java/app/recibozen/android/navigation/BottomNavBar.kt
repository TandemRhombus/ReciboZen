package app.recibozen.android.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.recibozen.android.R
import app.recibozen.android.core.components.rzCoachAnchor
import app.recibozen.android.core.designsystem.RzIcons

private data class BottomTab(val route: String, val labelRes: Int, val icon: ImageVector)

/** Anchor ids the guided tour can highlight. */
object RzTourAnchors {
    const val TAB_HOME = "tab_home"
    const val TAB_APPLIANCES = "tab_appliances"
    const val TAB_PLAN = "tab_plan"
    const val TAB_HISTORY = "tab_history"
    const val HOME_HEADLINE = "home_headline"
    const val HOME_DAC = "home_dac"
    const val HOME_REGISTER_READING = "home_register_reading"
    const val APPLIANCES_ADD = "appliances_add"
    const val HISTORY_IMPORT = "history_import"
    const val PLAN_ACTIONS = "plan_actions"
}

private val tabAnchors = mapOf(
    Routes.Home to RzTourAnchors.TAB_HOME,
    Routes.Appliances to RzTourAnchors.TAB_APPLIANCES,
    Routes.Plan to RzTourAnchors.TAB_PLAN,
    Routes.History to RzTourAnchors.TAB_HISTORY,
)

private val bottomTabsSpec = listOf(
    BottomTab(Routes.Home, R.string.nav_home, RzIcons.Home),
    BottomTab(Routes.Appliances, R.string.nav_appliances, RzIcons.Appliances),
    BottomTab(Routes.Plan, R.string.nav_plan, RzIcons.Plan),
    BottomTab(Routes.History, R.string.nav_history, RzIcons.History),
)

@Composable
fun RzBottomNavBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    NavigationBar {
        bottomTabsSpec.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
            // Called unconditionally: a composable behind a null-check would
            // break Compose's positional memoization if the map ever changed.
            val anchor = Modifier.rzCoachAnchor(tabAnchors[tab.route] ?: tab.route)
            NavigationBarItem(
                modifier = anchor,
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigateToBottomTab(tab.route)
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

/**
 * All links that land on a bottom tab use the same operation. Popping to Home
 * avoids leaving a second tab root on top of the first one (the cause of the
 * Home button becoming inert after "Ver todos").
 */
fun NavHostController.navigateToBottomTab(route: String) {
    // Inicio is the anchor every other tab pops back to, so it is always on the
    // stack and never needs a fresh navigation. Going to it with the operation
    // below made the controller save the stack it had just popped under Inicio's
    // own key and then restore it in the same call, which put the user back on
    // the tab they were leaving — tapping Inicio right after the tour ended on
    // Historial landed on Historial.
    if (route == Routes.Home && popBackStack(Routes.Home, inclusive = false)) return
    navigate(route) {
        popUpTo(Routes.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Tab switch for the guided tour, without state saving or restoring.
 *
 * [navigateToBottomTab] saves the stack it pops and restores it later. That is
 * right for a person tapping tabs, and wrong for the tour: when the tour was
 * replayed from Perfil, that screen got saved under Home and came back on the
 * first tab change, so around step four or five the tour appeared to cancel
 * itself and dumped the user in Configuración. The tour always walks to a clean
 * tab root instead.
 */
fun NavHostController.navigateToTourTab(route: String) {
    navigate(route) {
        popUpTo(Routes.Home) { saveState = false }
        launchSingleTop = true
        restoreState = false
    }
}

/** True for the 4 bottom-nav roots, matching the mock's `TABS.includes(s)` split. */
fun isBottomTabRoute(route: String?): Boolean = route != null && Routes.bottomTabs.contains(route)
