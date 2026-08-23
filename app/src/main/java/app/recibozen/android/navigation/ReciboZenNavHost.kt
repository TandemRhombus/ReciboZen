package app.recibozen.android.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.PaddingValues
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.feature.appliances.ApplianceDetailRoute
import app.recibozen.android.feature.appliances.ApplianceSimulatorRoute
import app.recibozen.android.feature.appliances.ApplianceUsageRoute
import app.recibozen.android.feature.appliances.AppliancesEmptyRoute
import app.recibozen.android.feature.appliances.AppliancesRoute
import app.recibozen.android.feature.appliances.ApplianceSpacesRoute
import app.recibozen.android.feature.home.HomeRoute
import app.recibozen.android.feature.onboarding.OnboardingRoute
import app.recibozen.android.feature.period.PeriodAnalysisRoute
import app.recibozen.android.feature.plan.BalanceRoute
import app.recibozen.android.feature.plan.DacRoute
import app.recibozen.android.feature.plan.PlanRoute
import app.recibozen.android.feature.readings.HowWeCalculateRoute
import app.recibozen.android.feature.readings.ReadingConfirmRoute
import app.recibozen.android.feature.readings.ReadingDialRoute
import app.recibozen.android.feature.readings.ReadingManualRoute
import app.recibozen.android.feature.readings.ReadingMethodRoute
import app.recibozen.android.feature.readings.ReadingPhotoRoute
import app.recibozen.android.feature.readings.ReadingPhotoUnclearRoute
import app.recibozen.android.feature.history.HistoryRoute
import app.recibozen.android.feature.history.BillImportRoute
import app.recibozen.android.feature.settings.AppearanceRoute
import app.recibozen.android.feature.settings.ProfileRoute
import app.recibozen.android.feature.settings.StatesCatalogRoute
import app.recibozen.android.feature.settings.ServicesRoute
import app.recibozen.android.feature.solar.SolarRoute
import app.recibozen.android.feature.support.DonationRoute
import app.recibozen.android.feature.support.LegalRoute
import app.recibozen.android.feature.support.NotificationSettingsRoute
import app.recibozen.android.feature.support.ReportIssueRoute
import app.recibozen.android.feature.support.SupportCenterRoute
import app.recibozen.android.feature.premium.PremiumRoute

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
        composable(Routes.Legal) { LegalRoute(navController) }
        composable(Routes.ReportIssue) { ReportIssueRoute(navController) }
        composable(Routes.NotificationSettings) { NotificationSettingsRoute(navController, repository) }
        composable(Routes.Premium) { PremiumRoute(navController, repository) }
    }
}
