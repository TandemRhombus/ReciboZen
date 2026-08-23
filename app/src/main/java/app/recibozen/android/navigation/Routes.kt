package app.recibozen.android.navigation

/** Every destination in the app's single [androidx.navigation.NavHost]. Plain string routes — no extra
 * type-safe-nav dependency needed at this scale. */
object Routes {
    const val Onboarding = "onboarding"

    // Bottom nav roots
    const val Home = "home"
    const val Appliances = "appliances"
    const val Plan = "plan"
    const val History = "history"
    const val BillImport = "bill_import"
    val bottomTabs = listOf(Home, Appliances, Plan, History)

    // Flow routes (pushed with a back-button top bar)
    const val PeriodAnalysis = "period_analysis"
    const val ApplianceEmpty = "appliance_empty"
    const val ApplianceSpaces = "appliance_spaces"

    const val ApplianceIdArg = "applianceId"
    const val ApplianceDetailPattern = "appliance_detail/{$ApplianceIdArg}"
    fun applianceDetail(id: String) = "appliance_detail/$id"
    const val ApplianceUsagePattern = "appliance_usage/{$ApplianceIdArg}"
    fun applianceUsage(id: String) = "appliance_usage/$id"

    const val Simulator = "appliance_simulator"
    const val Balance = "balance"
    const val Dac = "dac"
    const val Solar = "solar"

    const val ReadingMethod = "reading_method"
    const val ReadingPhoto = "reading_photo"
    const val ReadingPhotoUnclear = "reading_photo_unclear"
    const val ReadingManual = "reading_manual"
    const val ReadingDial = "reading_dial"
    const val ReadingConfirm = "reading_confirm"
    const val HowWeCalculate = "how_we_calculate"

    const val Profile = "profile"
    const val Services = "services"
    const val Appearance = "appearance"
    const val StatesCatalog = "states_catalog"
    const val Support = "support"
    const val Donate = "donate"
    const val ReportIssue = "report_issue"
    const val NotificationSettings = "notification_settings"
    const val Legal = "legal"
    const val Premium = "premium"
}
