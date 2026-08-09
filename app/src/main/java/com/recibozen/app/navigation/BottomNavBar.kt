package com.recibozen.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.recibozen.app.R
import com.recibozen.app.core.designsystem.RzIcons

private data class BottomTab(val route: String, val labelRes: Int, val icon: ImageVector)

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
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
            )
        }
    }
}

/** True for the 4 bottom-nav roots, matching the mock's `TABS.includes(s)` split. */
fun isBottomTabRoute(route: String?): Boolean = route != null && Routes.bottomTabs.contains(route)
