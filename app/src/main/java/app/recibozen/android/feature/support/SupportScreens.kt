package app.recibozen.android.feature.support

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.core.net.toUri
import app.recibozen.android.BuildConfig
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.layout.FlowRow
import app.recibozen.android.R
import app.recibozen.android.core.components.RzBannerTone
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzInlineBanner
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.model.EntitlementTier
import app.recibozen.android.core.calculation.NightUnplugAdvice
import app.recibozen.android.core.calculation.NightUnplugAdvisor
import app.recibozen.android.core.notifications.NightUnplugScheduler
import app.recibozen.android.core.notifications.NightUnplugSettings
import app.recibozen.android.core.notifications.NightUnplugStore
import app.recibozen.android.core.notifications.PremiumFeaturePolicy
import app.recibozen.android.core.notifications.ReadingReminderScheduler
import app.recibozen.android.core.notifications.ReadingReminderSettings
import app.recibozen.android.core.notifications.ReadingReminderStore
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.Routes

/** Where issue reports land. PayPal donations use its own link, see [PAYPAL_ME]. */
private const val SUPPORT_EMAIL = "emilianohdzvi@gmail.com"
/** Direct donation link: opens the PayPal app or the web checkout already addressed. */
private const val PAYPAL_ME = "https://paypal.me/KALIJOKER"
private const val PAYPAL_ME_HANDLE = "paypal.me/KALIJOKER"

@Composable
fun SupportCenterRoute(navController: NavHostController) {
    SupportCenterScreen(
        onBack = navController::popBackStack,
        onReportIssue = { navController.navigate(Routes.ReportIssue) },
        onNotifications = { navController.navigate(Routes.NotificationSettings) },
        onDonate = { navController.navigate(Routes.Donate) },
        onStatesGuide = { navController.navigate(Routes.StatesCatalog) },
    )
}

@Composable
fun SupportCenterScreen(
    onBack: () -> Unit,
    onReportIssue: () -> Unit,
    onNotifications: () -> Unit,
    onDonate: () -> Unit,
    onStatesGuide: () -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.support_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.support_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RzCard(modifier = Modifier.fillMaxWidth()) {
                SupportRow(RzIcons.BugReport, stringResource(R.string.support_report), stringResource(R.string.support_report_hint), onReportIssue)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupportRow(RzIcons.Notifications, stringResource(R.string.support_notifications), stringResource(R.string.support_notifications_hint), onNotifications)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SupportRow(RzIcons.Help, stringResource(R.string.support_states), stringResource(R.string.support_states_hint), onStatesGuide)
            }
            RzCard(modifier = Modifier.fillMaxWidth()) {
                SupportRow(RzIcons.Favorite, stringResource(R.string.support_donate), stringResource(R.string.support_donate_hint), onDonate)
            }
            Text(
                stringResource(R.string.support_diagnostics, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SupportRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = RzSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun DonationRoute(navController: NavHostController) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val uriHandler = LocalUriHandler.current
    var emailCopied by remember { mutableStateOf(false) }
    DonationScreen(
        onBack = navController::popBackStack,
        emailCopied = emailCopied,
        onCopyEmail = {
            clipboard.setPrimaryClip(ClipData.newPlainText("PayPal", PAYPAL_ME))
            emailCopied = true
        },
        onOpenPayPal = { uriHandler.openUri(PAYPAL_ME) },
    )
}

@Composable
fun DonationScreen(onBack: () -> Unit, emailCopied: Boolean, onCopyEmail: () -> Unit, onOpenPayPal: () -> Unit) {
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.donate_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.lg),
        ) {
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Icon(RzIcons.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.donate_student_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = RzSpacing.md))
                Text(stringResource(R.string.donate_student_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = RzSpacing.sm))
            }
            RzInlineBanner(
                title = stringResource(R.string.donate_voluntary_title),
                text = stringResource(R.string.donate_voluntary_body),
                tone = RzBannerTone.INFO,
                icon = RzIcons.Info,
            )
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.donate_paypal_label), style = MaterialTheme.typography.labelLarge)
                Text(PAYPAL_ME_HANDLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = RzSpacing.xs))
                RzPrimaryButton(
                    text = stringResource(R.string.donate_open_paypal),
                    onClick = onOpenPayPal,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
                RzSecondaryButton(
                    text = stringResource(if (emailCopied) R.string.donate_email_copied else R.string.donate_copy_email),
                    onClick = onCopyEmail,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                )
                Text(stringResource(R.string.donate_paypal_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = RzSpacing.sm))
            }
        }
    }
}

@Composable
fun ReportIssueRoute(navController: NavHostController) {
    val context = LocalContext.current
    var sendFailed by remember { mutableStateOf(false) }
    ReportIssueScreen(
        onBack = navController::popBackStack,
        sendFailed = sendFailed,
        onSend = { title, details ->
            val body = buildString {
                appendLine(details.trim())
                appendLine()
                appendLine("--- Diagnóstico técnico ---")
                appendLine("ReciboZen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android API ${Build.VERSION.SDK_INT}")
                append("No se adjuntaron recibos, lecturas ni datos del suministro.")
            }
            val uri = "mailto:$SUPPORT_EMAIL?subject=${Uri.encode("ReciboZen: ${title.trim()}")}&body=${Uri.encode(body)}".toUri()
            try {
                context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
                sendFailed = false
            } catch (_: ActivityNotFoundException) {
                sendFailed = true
            }
        },
    )
}

@Composable
fun ReportIssueScreen(onBack: () -> Unit, sendFailed: Boolean, onSend: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.report_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(stringResource(R.string.report_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sendFailed) {
                RzInlineBanner(stringResource(R.string.report_no_email_app), RzBannerTone.WARNING, icon = RzIcons.ErrorInfo)
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(100) },
                label = { Text(stringResource(R.string.report_subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = details,
                onValueChange = { details = it.take(3000) },
                label = { Text(stringResource(R.string.report_details)) },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            RzInlineBanner(stringResource(R.string.report_privacy), RzBannerTone.NEUTRAL, icon = RzIcons.Lock)
            RzPrimaryButton(
                text = stringResource(R.string.report_send),
                onClick = { onSend(title, details) },
                enabled = title.isNotBlank() && details.length >= 10,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun NotificationSettingsRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val context = LocalContext.current
    val session by repository.session.collectAsStateWithLifecycle()
    val store = remember(context) { ReadingReminderStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    val nightStore = remember(context) { NightUnplugStore(context) }
    var nightSettings by remember { mutableStateOf(nightStore.load()) }
    val tier = session.entitlementTier
    // What the reminder would actually name tonight, from the household's own
    // inventory. Showing it here is the difference between a switch someone
    // trusts and one they turn on, get a vague nudge from, and turn off.
    val nightAdvice = remember(session.customAppliances, session.applianceHoursPerWeek) {
        NightUnplugAdvisor.advise(session.customAppliances, session.applianceHoursPerWeek)
    }
    // Which switch asked for the permission, so the grant turns on that one.
    var pendingPermissionFor by remember { mutableStateOf<PendingReminder?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingPermissionFor
        pendingPermissionFor = null
        if (!granted) return@rememberLauncherForActivityResult
        when (requested) {
            PendingReminder.READING -> {
                settings = settings.copy(enabled = true)
                store.save(settings)
                ReadingReminderScheduler.apply(context, settings, tier)
            }
            PendingReminder.NIGHT -> {
                nightSettings = nightSettings.copy(enabled = true)
                nightStore.save(nightSettings)
                NightUnplugScheduler.apply(context, nightSettings, tier)
            }
            null -> Unit
        }
    }


    NotificationSettingsScreen(
        tier = tier,
        settings = settings,
        onBack = navController::popBackStack,
        onViewPremium = { navController.navigate(Routes.Premium) },
        onEnabledChange = enabledChange@ { enabled ->
            if (!PremiumFeaturePolicy.notificationsAllowed(tier)) return@enabledChange
            if (enabled && needsNotificationPermission(context)) {
                pendingPermissionFor = PendingReminder.READING
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                settings = settings.copy(enabled = enabled)
                store.save(settings)
                ReadingReminderScheduler.apply(context, settings, tier)
            }
        },
        onIntervalChange = { days ->
            settings = settings.copy(intervalDays = days)
            store.save(settings)
            ReadingReminderScheduler.apply(context, settings, tier)
        },
        nightSettings = nightSettings,
        nightAdvice = nightAdvice,
        onNightEnabledChange = nightChange@ { enabled ->
            if (!PremiumFeaturePolicy.notificationsAllowed(tier)) return@nightChange
            if (enabled && needsNotificationPermission(context)) {
                pendingPermissionFor = PendingReminder.NIGHT
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                nightSettings = nightSettings.copy(enabled = enabled)
                nightStore.save(nightSettings)
                NightUnplugScheduler.apply(context, nightSettings, tier)
            }
        },
        onNightHourChange = { hour ->
            nightSettings = nightSettings.copy(hour = hour)
            nightStore.save(nightSettings)
            NightUnplugScheduler.apply(context, nightSettings, tier)
        },
    )
}

/** Which switch is waiting on the POST_NOTIFICATIONS grant. */
private enum class PendingReminder { READING, NIGHT }

/**
 * Whether turning a reminder on has to ask for the runtime notification
 * permission first.
 *
 * The annotation is what lets the caller reference POST_NOTIFICATIONS without a
 * version check of its own: returning true does imply API 33 or newer, even
 * though the reverse is not true. Inlining the check at each call site instead
 * would repeat it, and hiding it behind an unannotated helper is what made lint
 * flag both launch sites.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

@Composable
fun NotificationSettingsScreen(
    tier: EntitlementTier,
    settings: ReadingReminderSettings,
    onBack: () -> Unit,
    onViewPremium: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
    nightSettings: NightUnplugSettings = NightUnplugSettings(),
    /** What the night reminder would name right now, or null with nothing to name. */
    nightAdvice: NightUnplugAdvice? = null,
    onNightEnabledChange: (Boolean) -> Unit = {},
    onNightHourChange: (Int) -> Unit = {},
) {
    val allowed = PremiumFeaturePolicy.notificationsAllowed(tier)
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.notifications_title), onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            if (!allowed) {
                RzInlineBanner(
                    title = stringResource(R.string.notifications_premium_title),
                    text = stringResource(R.string.notifications_premium_body),
                    tone = RzBannerTone.INFO,
                    icon = RzIcons.Lock,
                )
                RzPrimaryButton(stringResource(R.string.notifications_view_premium), onViewPremium, Modifier.fillMaxWidth())
            }
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.notifications_reading_setting), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.notifications_reading_setting_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = settings.enabled && allowed, onCheckedChange = onEnabledChange, enabled = allowed)
                }
                Text(stringResource(R.string.notifications_frequency), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = RzSpacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm), modifier = Modifier.padding(top = RzSpacing.sm)) {
                    ReadingReminderSettings.SUPPORTED_INTERVALS.sorted().forEach { days ->
                        RzFilterChip(
                            text = stringResource(
                                if (days == 3) R.string.notifications_every_three_days
                                else R.string.notifications_every_seven_days,
                            ),
                            selected = settings.intervalDays == days,
                            onClick = { onIntervalChange(days) },
                            enabled = allowed,
                        )
                    }
                }
            }
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.notifications_night_setting), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.notifications_night_setting_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = nightSettings.enabled && allowed,
                        onCheckedChange = onNightEnabledChange,
                        enabled = allowed,
                    )
                }
                Text(
                    stringResource(R.string.notifications_night_hour),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = RzSpacing.lg),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                    modifier = Modifier.padding(top = RzSpacing.sm),
                ) {
                    NightUnplugSettings.SUPPORTED_HOURS.forEach { hour ->
                        RzFilterChip(
                            text = stringResource(R.string.notifications_night_hour_option, hour),
                            selected = nightSettings.hour == hour,
                            onClick = { onNightHourChange(hour) },
                            enabled = allowed,
                        )
                    }
                }
                // The preview is the honest part: it shows exactly what the
                // reminder can say tonight, so nobody switches this on expecting
                // names the inventory cannot supply.
                if (nightAdvice == null) {
                    Text(
                        stringResource(R.string.notifications_night_no_inventory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                } else {
                    // map before joinToString: map is inline, so stringResource
                    // stays in composable scope. joinToString's transform is not,
                    // and reaching for LocalContext.getString instead skips the
                    // recomposition a configuration change needs.
                    val named = nightAdvice.items.take(3).map { item ->
                        stringResource(R.string.notifications_night_item, item.name, item.standbyWatts)
                    }
                    Text(
                        stringResource(R.string.notifications_night_preview, named.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = RzSpacing.md),
                    )
                }
                Text(
                    stringResource(R.string.notifications_night_inventory_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }
            Text(stringResource(R.string.notifications_delivery_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SupportPreview() {
    ReciboZenTheme { SupportCenterScreen({}, {}, {}, {}, {}) }
}
