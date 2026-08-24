package app.recibozen.android.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzBarEntry
import app.recibozen.android.core.components.RzScrollableBarChart
import app.recibozen.android.core.components.RzBarSegment
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzStackedBarRow
import app.recibozen.android.core.components.RzSegmentedTabs
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.components.RzTabHeader
import app.recibozen.android.core.components.rzTimeGreeting
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.model.BillPeriod
import app.recibozen.android.core.model.HistoryReadingEntry
import app.recibozen.android.core.model.HistoryUnit
import app.recibozen.android.core.model.PastPeriodComparison
import app.recibozen.android.core.model.ReadingMethod
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.core.components.rzCoachAnchor
import app.recibozen.android.navigation.RzTourAnchors
import app.recibozen.android.navigation.Routes
import app.recibozen.android.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import kotlin.math.roundToInt

data class HistoryUiState(
    val greetingName: String = "",
    val householdName: String = "",
    val userInitial: String = "?",
    val currentPeriod: BillPeriod? = null,
    /** Days of the period in progress the readings already cover. */
    val currentPeriodObservedDays: Int = 0,
    /** Days the period is expected to last, 0 while it is not established. */
    val currentPeriodBillingDays: Int = 0,
    val pastBills: List<BillPeriod> = emptyList(),
    val historyUnit: HistoryUnit = HistoryUnit.MXN,
    val comparison: PastPeriodComparison? = null,
    val readingsCountLabel: String = "0 lecturas recientes",
    val readings: List<HistoryReadingEntry> = emptyList(),
)

class HistoryViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<HistoryUiState> = combine(
        repository.session,
        repository.importedBillHistory,
    ) { state, importedBills ->
            val history = repository.billHistory(state)
            val projected = history.lastOrNull { it.isProjected }
            val energy = repository.energySummary(state)
            HistoryUiState(
                greetingName = state.userName,
                householdName = state.householdName,
                userInitial = state.userName.firstOrNull()?.uppercase() ?: "?",
                currentPeriod = projected,
                currentPeriodObservedDays = energy.observedDays,
                currentPeriodBillingDays = energy.billingDays,
                pastBills = importedBills,
                historyUnit = state.historyUnit,
                readingsCountLabel = "${state.readings.size} lecturas recientes",
                readings = state.readings,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    fun setHistoryUnit(unit: HistoryUnit) = repository.setHistoryUnit(unit)
}

@Composable
fun HistoryRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { HistoryViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HistoryScreen(
        uiState = uiState,
        onProfileClick = { navController.navigate(Routes.Profile) },
        onHouseholdClick = { navController.navigate(Routes.Services) },
        onUnitChange = viewModel::setHistoryUnit,
        onRegisterReading = { navController.navigate(Routes.ReadingMethod) },
        onImportBill = { navController.navigate(Routes.BillImport) },
    )
}

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onProfileClick: () -> Unit,
    onHouseholdClick: () -> Unit,
    onUnitChange: (HistoryUnit) -> Unit,
    onRegisterReading: () -> Unit,
    onImportBill: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRegisterReading,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                icon = { Icon(RzIcons.Schedule, contentDescription = null) },
                text = { Text(stringResource(R.string.action_register_reading)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            RzTabHeader(
                greeting = rzTimeGreeting(uiState.greetingName),
                householdName = uiState.householdName.ifBlank { stringResource(R.string.home_household_unconfigured) },
                userInitial = uiState.userInitial,
                onHouseholdClick = onHouseholdClick,
                onProfileClick = onProfileClick,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = RzSpacing.screenHorizontal)
                    .padding(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
            ) {
                Text(
                    stringResource(R.string.history_headline),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                RzSecondaryButton(
                    text = stringResource(R.string.history_import_bill),
                    onClick = onImportBill,
                    modifier = Modifier.rzCoachAnchor(RzTourAnchors.HISTORY_IMPORT).fillMaxWidth(),
                )

                uiState.currentPeriod?.let { currentPeriod ->
                    CurrentPeriodProjectionCard(
                        currentPeriod = currentPeriod,
                        unit = uiState.historyUnit,
                        observedDays = uiState.currentPeriodObservedDays,
                        billingDays = uiState.currentPeriodBillingDays,
                    )
                }

                PreviousBillsCard(
                    pastBills = uiState.pastBills,
                    unit = uiState.historyUnit,
                    onUnitChange = onUnitChange,
                )

                uiState.comparison?.let { comparison -> PeriodComparisonCard(comparison = comparison) }

                Text(
                    stringResource(R.string.history_recent_readings_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    uiState.readingsCountLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.history_recent_readings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                RecentReadingsCard(readings = uiState.readings)

                Text(
                    stringResource(R.string.history_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

            }
        }
    }
}

/**
 * Design correction: the current period's projection is its own small card,
 * never folded into "Recibos anteriores" (a card whose title promises real
 * receipts only).
 */
@Composable
private fun CurrentPeriodProjectionCard(
    currentPeriod: BillPeriod,
    unit: HistoryUnit,
    observedDays: Int,
    billingDays: Int,
) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.history_current_period_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RzExtraShapes.pill,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    stringResource(R.string.history_current_period_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = RzSpacing.md, vertical = 3.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = RzSpacing.sm)) {
            Text(
                displayAmount(currentPeriod, unit),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "  " + currentPeriod.monthLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        PeriodProgressBar(
            observedDays = observedDays,
            billingDays = billingDays,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
        )
        if (billingDays > 0) {
            Text(
                stringResource(
                    R.string.history_current_period_progress,
                    observedDays.coerceIn(0, billingDays),
                    billingDays,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        Text(
            stringResource(R.string.history_current_period_caption_dynamic),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

/**
 * How much of the period the projection actually rests on.
 *
 * This used to be a full-width band of diagonal stripes that represented
 * nothing: identical on day 1 and on day 60, drawn from no data at all. It was
 * texture from the mockup sitting directly under a peso figure, which invites
 * reading it as a measurement of something.
 *
 * Now the solid part is the days the readings cover and the striped part is the
 * rest of the period, which is the projection. The stripes keep their original
 * meaning -- provisional -- and now mark exactly the portion that is.
 */
@Composable
private fun PeriodProgressBar(observedDays: Int, billingDays: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    // Without a period length nothing here is known, so the whole bar is
    // provisional rather than a made-up fraction of an unknown total.
    val elapsed = if (billingDays > 0) observedDays.coerceIn(0, billingDays).toFloat() / billingDays else 0f
    RzStackedBarRow(
        segments = listOf(
            RzBarSegment(weight = elapsed, color = color),
            RzBarSegment(weight = 1f - elapsed, color = color, striped = true),
        ),
        height = 10.dp,
        modifier = modifier,
    )
}

@Composable
private fun PreviousBillsCard(
    pastBills: List<BillPeriod>,
    unit: HistoryUnit,
    onUnitChange: (HistoryUnit) -> Unit,
) {
    var selectedIndex by remember(pastBills.size) { mutableIntStateOf((pastBills.lastIndex).coerceAtLeast(0)) }
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.history_previous_bills_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        RzSegmentedTabs(
            options = listOf(stringResource(R.string.history_unit_mxn), stringResource(R.string.history_unit_kwh)),
            selectedIndex = if (unit == HistoryUnit.MXN) 0 else 1,
            onSelectedChange = { index -> onUnitChange(if (index == 0) HistoryUnit.MXN else HistoryUnit.KWH) },
            modifier = Modifier.padding(top = RzSpacing.md),
        )

        if (pastBills.isEmpty()) {
            Text(
                stringResource(R.string.history_no_bills),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
        } else {
        val maxAmount = pastBills.maxOfOrNull { it.amountMxn } ?: 1
        val entries = pastBills.map { bill ->
            RzBarEntry(
                categoryLabel = bill.monthLabel,
                valueLabel = displayAmount(bill, unit),
                heightFraction = (if (unit == HistoryUnit.MXN) bill.amountMxn else bill.consumptionKwh).toFloat() /
                    (if (unit == HistoryUnit.MXN) maxAmount else pastBills.maxOfOrNull { it.consumptionKwh }?.coerceAtLeast(1) ?: 1).toFloat(),
                color = RzTheme.extendedColors.chartHistoryBar,
            )
        }
        RzScrollableBarChart(
            entries = entries,
            selectedIndex = selectedIndex.coerceIn(0, entries.lastIndex),
            onSelected = { selectedIndex = it },
            modifier = Modifier.padding(top = RzSpacing.lg),
        )

        pastBills.getOrNull(selectedIndex)?.let { selected ->
            Text(
                stringResource(
                    R.string.chart_selected_bill,
                    selected.monthLabel,
                    selected.consumptionKwh,
                    selected.amountMxn,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
        }
        Text(
            stringResource(R.string.chart_swipe_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(top = RzSpacing.sm),
        ) {
            LegendSwatch(color = RzTheme.extendedColors.chartHistoryBar)
            Text(
                stringResource(R.string.history_legend_real),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .size(10.dp)
            .background(color, RoundedCornerShape(2.dp)),
    ) {}
}

@Composable
private fun PeriodComparisonCard(comparison: PastPeriodComparison) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.history_period_comparison_title, comparison.periodLabel),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md), horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
            ComparisonStat(
                label = stringResource(R.string.history_estimate_label),
                value = stringResource(R.string.history_amount_mxn, formatThousands(comparison.previousEstimateMxn)),
                modifier = Modifier.weight(1f),
            )
            ComparisonStat(
                label = stringResource(R.string.history_actual_label),
                value = stringResource(R.string.history_amount_mxn, formatThousands(comparison.actualBillMxn)),
                modifier = Modifier.weight(1f),
            )
            ComparisonStat(
                label = stringResource(R.string.history_difference_label),
                value = stringResource(
                    R.string.history_amount_signed_mxn,
                    formatThousands(comparison.actualBillMxn - comparison.previousEstimateMxn),
                ),
                valueColor = RzTheme.extendedColors.warningText,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            comparison.note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun ComparisonStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

@Composable
private fun RecentReadingsCard(readings: List<HistoryReadingEntry>) {
    RzCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = 0.dp),
    ) {
        if (readings.isEmpty()) {
            Text(
                stringResource(R.string.history_no_readings),
                modifier = Modifier.padding(vertical = RzSpacing.lg),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        readings.forEachIndexed { index, entry ->
            ReadingRow(entry)
            if (index != readings.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ReadingRow(entry: HistoryReadingEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = RzSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
    ) {
        Icon(methodIcon(entry.method), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                stringResource(R.string.history_reading_value, formatThousands(entry.kwh)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.history_reading_meta, entry.dateLabel, methodLabel(entry.method), entry.note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun methodIcon(method: ReadingMethod): ImageVector = when (method) {
    ReadingMethod.DIAL -> RzIcons.Schedule
    ReadingMethod.MANUAL -> RzIcons.Keyboard
    ReadingMethod.PHOTO -> RzIcons.PhotoCamera
    ReadingMethod.PDF -> RzIcons.ReceiptLong
}

@Composable
private fun methodLabel(method: ReadingMethod): String = when (method) {
    ReadingMethod.DIAL -> stringResource(R.string.history_reading_method_dial)
    ReadingMethod.MANUAL -> stringResource(R.string.history_reading_method_manual)
    ReadingMethod.PHOTO -> stringResource(R.string.history_reading_method_photo)
    ReadingMethod.PDF -> stringResource(R.string.history_reading_method_pdf)
}

/**
 * [BillPeriod] only stores a pesos amount -- the design's kWh toggle has no
 * matching kWh fixture in [DemoReciboZenData]. For this handoff build we
 * derive an approximate kWh figure from the pesos amount purely for display
 * (not a real tariff calculation); flagged for the tariff-engine follow-up.
 */
private fun displayAmount(period: BillPeriod, unit: HistoryUnit): String {
    return when (unit) {
        HistoryUnit.MXN -> "$" + formatThousands(period.amountMxn)
        HistoryUnit.KWH -> formatThousands(period.consumptionKwh) + " kWh"
    }
}

private fun formatThousands(value: Int): String = String.format(Locale("es", "MX"), "%,d", value)

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    ReciboZenTheme {
        HistoryScreen(
            uiState = HistoryUiState(),
            onProfileClick = {},
            onHouseholdClick = {},
            onUnitChange = {},
            onRegisterReading = {},
            onImportBill = {},
        )
    }
}
