package com.recibozen.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.PaddingValues
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.feature.appliances.ApplianceDetailRoute
import com.recibozen.app.feature.appliances.ApplianceSimulatorRoute
import com.recibozen.app.feature.appliances.ApplianceUsageRoute
import com.recibozen.app.feature.appliances.AppliancesEmptyRoute
import com.recibozen.app.feature.appliances.AppliancesRoute
import com.recibozen.app.feature.appliances.ApplianceSpacesRoute
import com.recibozen.app.feature.home.HomeRoute
import com.recibozen.app.feature.onboarding.OnboardingRoute
import com.recibozen.app.feature.period.PeriodAnalysisRoute
import com.recibozen.app.feature.plan.BalanceRoute
import com.recibozen.app.feature.plan.DacRoute
import com.recibozen.app.feature.plan.PlanRoute
import com.recibozen.app.feature.readings.HowWeCalculateRoute
import com.recibozen.app.feature.readings.ReadingConfirmRoute
import com.recibozen.app.feature.readings.ReadingDialRoute
import com.recibozen.app.feature.readings.ReadingManualRoute
import com.recibozen.app.feature.readings.ReadingMethodRoute
import com.recibozen.app.feature.readings.ReadingPhotoRoute
import com.recibozen.app.feature.readings.ReadingPhotoUnclearRoute
import com.recibozen.app.feature.history.HistoryRoute
import com.recibozen.app.feature.history.BillImportRoute
import com.recibozen.app.feature.settings.AppearanceRoute
import com.recibozen.app.feature.settings.ProfileRoute
import com.recibozen.app.feature.settings.ServicesRoute
import com.recibozen.app.feature.settings.StatesCatalogRoute
import com.recibozen.app.feature.solar.SolarRoute
import com.recibozen.app.feature.support.DonationRoute
import com.recibozen.app.feature.support.NotificationSettingsRoute
import com.recibozen.app.feature.support.ReportIssueRoute
import com.recibozen.app.feature.support.SupportCenterRoute
import com.recibozen.app.feature.premium.PremiumRoute

@Composable
fun ReciboZenNavHost(
    navController: NavHostController,
    repository: ReciboZenRepository,
    innerPadding: PaddingValues,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding),
    ) {
        composable(Routes.Onboarding) { OnboardingRoute(navController, repository) }

        composable(Routes.Home) { HomeRoute(navController, repository) }
        composable(Routes.Appliances) { AppliancesRoute(navController, repository) }
        composable(Routes.Plan) { PlanRoute(navController, repository) }
        composable(Routes.History) { HistoryRoute(navController, repository) }
        composable(Routes.BillImport) { BillImportRoute(navController, repository) }

        composable(Routes.PeriodAnalysis) { PeriodAnalysisRoute(navController, repository) }
        composable(Routes.ApplianceEmpty) { AppliancesEmptyRoute(navController, repository) }
        composable(Routes.ApplianceSpaces) { ApplianceSpacesRoute(navController, repository) }
        composable(Routes.ApplianceDetailPattern) { backStackEntry ->
            val id = backStackEntry.arguments?.getString(Routes.ApplianceIdArg).orEmpty()
            ApplianceDetailRoute(navController, repository, applianceId = id)
        }
        composable(Routes.ApplianceUsagePattern) { backStackEntry ->
            val id = backStackEntry.arguments?.getString(Routes.ApplianceIdArg).orEmpty()
            ApplianceUsageRoute(navController, repository, applianceId = id)
        }
        composable(Routes.Simulator) { ApplianceSimulatorRoute(navController, repository) }

        composable(Routes.Balance) { BalanceRoute(navController, repository) }
        composable(Routes.Dac) { DacRoute(navController, repository) }
        composable(Routes.Solar) { SolarRoute(navController, repository) }

        composable(Routes.ReadingMethod) { ReadingMethodRoute(navController, repository) }
        composable(Routes.ReadingPhoto) { ReadingPhotoRoute(navController, repository) }
        composable(Routes.ReadingPhotoUnclear) { ReadingPhotoUnclearRoute(navController, repository) }
        composable(Routes.ReadingManual) { ReadingManualRoute(navController, repository) }
        composable(Routes.ReadingDial) { ReadingDialRoute(navController, repository) }
        composable(Routes.ReadingConfirm) { ReadingConfirmRoute(navController, repository) }
        composable(Routes.HowWeCalculate) { HowWeCalculateRoute(navController, repository) }

        composable(Routes.Profile) { ProfileRoute(navController, repository) }
        composable(Routes.Services) { ServicesRoute(navController, repository) }
        composable(Routes.Appearance) { AppearanceRoute(navController, repository) }
        composable(Routes.StatesCatalog) { StatesCatalogRoute(navController, repository) }
        composable(Routes.Support) { SupportCenterRoute(navController) }
        composable(Routes.Donate) { DonationRoute(navController) }
        composable(Routes.ReportIssue) { ReportIssueRoute(navController) }
        composable(Routes.NotificationSettings) { NotificationSettingsRoute(navController, repository) }
        composable(Routes.Premium) { PremiumRoute(navController, repository) }
    }
}
