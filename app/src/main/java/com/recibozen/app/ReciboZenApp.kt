package com.recibozen.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.ReciboZenNavHost
import com.recibozen.app.navigation.RzBottomNavBar
import com.recibozen.app.navigation.isBottomTabRoute

@Composable
fun ReciboZenApp(repository: ReciboZenRepository = remember { DemoReciboZenRepository() }) {
    val session by repository.session.collectAsStateWithLifecycle()

    ReciboZenTheme(themeMode = session.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (isBottomTabRoute(currentRoute)) {
                    RzBottomNavBar(navController)
                }
            },
        ) { innerPadding ->
            ReciboZenNavHost(navController = navController, repository = repository, innerPadding = innerPadding)
        }
    }
}
