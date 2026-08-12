package com.recibozen.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
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
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.data.repository.PersistentReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.ReciboZenNavHost
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.RzBottomNavBar
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

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
    }
}
