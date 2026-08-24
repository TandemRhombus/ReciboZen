package app.recibozen.android.feature.appliances

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.calculation.ApplianceCalculator
import app.recibozen.android.core.calculation.ApplianceCatalog
import app.recibozen.android.core.calculation.ApplianceCatalogItem
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzDashedCard
import app.recibozen.android.core.components.RzFilterChip
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzDropdownField
import app.recibozen.android.core.components.RzDropdownOption
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzSpacing
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.model.Appliance
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ConfidenceLevel
import app.recibozen.android.core.model.OpportunityLevel
import app.recibozen.android.core.model.ApplianceUsageModel
import app.recibozen.android.core.model.HouseSpace
import app.recibozen.android.core.model.HouseSpaceDefaults
import app.recibozen.android.data.catalog.AssetEnergyStarCatalogSource
import app.recibozen.android.data.catalog.CatalogSearchOutcome
import app.recibozen.android.data.catalog.EnergyStarCatalogSource
import app.recibozen.android.demo.repository.ReciboZenRepository
import app.recibozen.android.navigation.rzViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AppliancesEmptyUiState(
    val catalog: List<ApplianceCatalogItem> = ApplianceCatalog.items,
    val billingDays: Int = 61,
    val effectiveRateMxnPerKwh: Double = 0.0,
    val hasCostReference: Boolean = false,
    val spaces: List<HouseSpace> = HouseSpaceDefaults.items,
    /**
     * Space of the appliance added most recently. Someone registering the whole
     * kitchen should not have to pick "Cocina" again on every single item.
     */
    val defaultSpaceId: String? = null,
    val certifiedModelResults: List<ApplianceCatalogItem> = emptyList(),
    val certifiedModelCount: Int = 0,
    val isSearchingCertifiedModels: Boolean = false,
    val certifiedCatalogError: Boolean = false,
    /** Brand the search locked onto, so the local catalog obeys the same rule. */
    val searchBrand: String? = null,
    /** Set when the user named a brand the catalog does not carry. */
    val brandNotCovered: String? = null,
    val quickBrands: List<String> = emptyList(),
    val isLoadingBrands: Boolean = false,
)

data class CatalogApplianceDraft(
    val space: HouseSpace,
    val quantity: Int,
    val hoursPerWeek: Int?,
    val periodKwhPerUnit: Double?,
    val brand: String?,
    val model: String?,
    val usageModel: ApplianceUsageModel,
    val activeWatts: Int?,
    val standbyEnabled: Boolean,
    val standbyWatts: Int?,
)

data class CustomApplianceDraft(
    val name: String,
    val space: HouseSpace,
    val quantity: Int,
    val brand: String?,
    val model: String?,
    val usageModel: ApplianceUsageModel,
    val watts: Int?,
    val hoursPerDay: Double?,
    val daysPerWeek: Int?,
    val periodKwhPerUnit: Double?,
    val dataSource: ApplianceDataSource,
)

private data class CertifiedCatalogSearchState(
    val results: List<ApplianceCatalogItem> = emptyList(),
    val totalModels: Int = 0,
    val loading: Boolean = false,
    val error: Boolean = false,
    val quickBrands: List<String> = emptyList(),
    val loadingBrands: Boolean = false,
    val brand: String? = null,
    val brandNotCovered: String? = null,
)

class AppliancesEmptyViewModel(
    private val repository: ReciboZenRepository,
    private val certifiedCatalog: EnergyStarCatalogSource,
) : ViewModel() {
    private val certifiedSearch = MutableStateFlow(CertifiedCatalogSearchState())
    private var searchJob: Job? = null
    private var brandsJob: Job? = null
    private var brandsCategory: ApplianceCategory? = null
    private var brandsLoaded = false

    init {
        viewModelScope.launch {
            runCatching { certifiedCatalog.metadata() }
                .onSuccess { metadata ->
                    certifiedSearch.value = certifiedSearch.value.copy(totalModels = metadata.searchableModels)
                }
                .onFailure { certifiedSearch.value = certifiedSearch.value.copy(error = true) }
        }
        refreshQuickBrands(null)
    }

    val uiState: StateFlow<AppliancesEmptyUiState> = combine(
        repository.session,
        repository.importedBillHistory,
        certifiedSearch,
    ) { state, bills, certified ->
            val latestBill = bills.lastOrNull()
            AppliancesEmptyUiState(
                // Same period length the projection uses, instead of a second
                // rule that could disagree with the rest of the app.
                billingDays = repository.energySummary(state).billingDays.takeIf { it > 0 } ?: 30,
                effectiveRateMxnPerKwh = latestBill?.takeIf { it.consumptionKwh > 0 }
                    ?.let { it.amountMxn.toDouble() / it.consumptionKwh } ?: 0.0,
                hasCostReference = latestBill?.consumptionKwh?.let { it > 0 } == true,
                spaces = state.houseSpaces,
                defaultSpaceId = state.customAppliances.lastOrNull()?.spaceId,
                certifiedModelResults = certified.results,
                certifiedModelCount = certified.totalModels,
                isSearchingCertifiedModels = certified.loading,
                certifiedCatalogError = certified.error,
                searchBrand = certified.brand,
                brandNotCovered = certified.brandNotCovered,
                quickBrands = certified.quickBrands,
                isLoadingBrands = certified.loadingBrands,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppliancesEmptyUiState())

    fun searchCertifiedModels(query: String, category: ApplianceCategory?) {
        refreshQuickBrands(category)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            certifiedSearch.value = certifiedSearch.value.copy(
                results = emptyList(),
                loading = false,
                error = false,
                brand = null,
                brandNotCovered = null,
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(180)
            certifiedSearch.value = certifiedSearch.value.copy(loading = true, error = false)
            try {
                val outcome = certifiedCatalog.searchOutcome(query.trim(), category)
                certifiedSearch.value = certifiedSearch.value.copy(
                    results = (outcome as? CatalogSearchOutcome.Matches)?.items.orEmpty(),
                    brand = (outcome as? CatalogSearchOutcome.Matches)?.brand,
                    brandNotCovered = (outcome as? CatalogSearchOutcome.BrandNotCovered)?.brand,
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                certifiedSearch.value = certifiedSearch.value.copy(
                    results = emptyList(),
                    brand = null,
                    brandNotCovered = null,
                    loading = false,
                    error = true,
                )
            }
        }
    }

    private fun refreshQuickBrands(category: ApplianceCategory?) {
        if (brandsLoaded && brandsCategory == category) return
        brandsCategory = category
        brandsJob?.cancel()
        brandsJob = viewModelScope.launch {
            certifiedSearch.value = certifiedSearch.value.copy(loadingBrands = true)
            val localBrands = ApplianceCatalog.items.asSequence()
                .filter { category == null || it.category == category }
                .mapNotNull(ApplianceCatalogItem::brand)
                .filter(String::isNotBlank)
                .toList()
            val certifiedBrands = try {
                certifiedCatalog.popularBrands(category, limit = 18)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val combined = (localBrands + certifiedBrands)
                .distinctBy { it.trim().lowercase(java.util.Locale.ROOT) }
                .take(18)
            brandsLoaded = true
            certifiedSearch.value = certifiedSearch.value.copy(
                quickBrands = combined,
                loadingBrands = false,
            )
        }
    }

    fun addCatalogItem(item: ApplianceCatalogItem, draft: CatalogApplianceDraft) {
        val defaultPeriodKwh = item.annualKwh?.let { it * uiState.value.billingDays / 365.0 }
            ?: item.typicalKwhPer30Days?.let { it * uiState.value.billingDays / 30.0 }
        val consumptionWasCustomized = when (draft.usageModel) {
            ApplianceUsageModel.POWER_SCHEDULE ->
                (draft.activeWatts != null && draft.activeWatts != item.typicalWatts) ||
                    (draft.standbyWatts != null && draft.standbyWatts != item.standbyWatts)
            ApplianceUsageModel.PERIOD_KWH -> defaultPeriodKwh == null ||
                kotlin.math.abs((draft.periodKwhPerUnit ?: 0.0) - defaultPeriodKwh) >= 0.05
        }
        val configuredItem = item.copy(
            typicalWatts = draft.activeWatts ?: item.typicalWatts,
            annualKwh = item.annualKwh.takeIf { draft.usageModel == ApplianceUsageModel.PERIOD_KWH },
            typicalKwhPer30Days = item.typicalKwhPer30Days.takeIf {
                draft.usageModel == ApplianceUsageModel.PERIOD_KWH
            },
            standbyWatts = draft.standbyWatts ?: item.standbyWatts,
            dataSource = if (consumptionWasCustomized) ApplianceDataSource.USER_ESTIMATE else item.dataSource,
            confidence = if (consumptionWasCustomized) ConfidenceLevel.LOW else item.confidence,
            sourceLabel = if (consumptionWasCustomized) {
                item.sourceLabel?.let { "Ajuste del usuario · referencia original: $it" } ?: "Ajuste del usuario"
            } else item.sourceLabel,
        )
        repository.addAppliance(
            ApplianceCatalog.toAppliance(
                item = configuredItem,
                instanceId = "catalog_${item.id}_${System.currentTimeMillis()}",
                room = draft.space.name,
                hoursPerWeek = draft.hoursPerWeek.takeIf { draft.usageModel == ApplianceUsageModel.POWER_SCHEDULE },
                billingDays = uiState.value.billingDays,
                effectiveRateMxnPerKwh = uiState.value.effectiveRateMxnPerKwh,
                quantity = draft.quantity,
                spaceId = draft.space.id,
                brand = draft.brand,
                model = draft.model,
            periodKwhPerUnit = draft.periodKwhPerUnit.takeIf { draft.usageModel == ApplianceUsageModel.PERIOD_KWH },
            standbyEnabled = draft.standbyEnabled,
            ),
        )
    }

    fun addCustom(draft: CustomApplianceDraft) {
        val estimate = if (draft.usageModel == ApplianceUsageModel.POWER_SCHEDULE) {
            ApplianceCalculator.fromPower(
                watts = draft.watts ?: return,
                hoursPerDay = draft.hoursPerDay ?: return,
                daysPerWeek = draft.daysPerWeek ?: return,
                billingDays = uiState.value.billingDays,
                effectiveRateMxnPerKwh = uiState.value.effectiveRateMxnPerKwh,
                quantity = draft.quantity,
            )
        } else {
            val unitKwh = draft.periodKwhPerUnit ?: return
            val exact = unitKwh * draft.quantity
            app.recibozen.android.core.calculation.ApplianceUsageEstimate(
                kwh = exact.roundToInt(),
                costMxn = (exact * uiState.value.effectiveRateMxnPerKwh).roundToInt(),
                calculation = "$unitKwh kWh por unidad × ${draft.quantity} unidades",
                activeKwh = exact,
            )
        }
        repository.addAppliance(
            Appliance(
                id = "custom_${System.currentTimeMillis()}",
                name = draft.name.trim(),
                room = draft.space.name,
                costMxn = estimate.costMxn,
                kwh = estimate.kwh,
                sharePercent = 0,
                hoursPerWeek = if (draft.usageModel == ApplianceUsageModel.POWER_SCHEDULE) {
                    ((draft.hoursPerDay ?: 0.0) * (draft.daysPerWeek ?: 0)).roundToInt()
                } else null,
                confidence = when (draft.dataSource) {
                    ApplianceDataSource.USER_MEASURED,
                    ApplianceDataSource.MANUFACTURER_LABEL -> ConfidenceLevel.HIGH
                    ApplianceDataSource.CATALOG,
                    ApplianceDataSource.RECEIPT_INFERENCE -> ConfidenceLevel.MEDIUM
                    ApplianceDataSource.USER_ESTIMATE,
                    ApplianceDataSource.AI_SUGGESTION -> ConfidenceLevel.LOW
                },
                opportunity = OpportunityLevel.MEDIUM,
                calculationNote = estimate.calculation,
                category = ApplianceCategory.OTHER,
                powerWatts = draft.watts,
                dataSource = if (draft.usageModel == ApplianceUsageModel.POWER_SCHEDULE) {
                    ApplianceDataSource.USER_ESTIMATE
                } else draft.dataSource,
                quantity = draft.quantity,
                spaceId = draft.space.id,
                brand = draft.brand?.trim()?.takeIf { it.isNotEmpty() },
                model = draft.model?.trim()?.takeIf { it.isNotEmpty() },
                usageModel = draft.usageModel,
                unitKwh = (estimate.activeKwh + estimate.standbyKwh) / draft.quantity.coerceAtLeast(1),
                referencePeriodDays = uiState.value.billingDays,
                sourceLabel = when {
                    draft.usageModel == ApplianceUsageModel.POWER_SCHEDULE -> "Estimación ajustada por el usuario"
                    draft.dataSource == ApplianceDataSource.USER_MEASURED -> "Medición capturada por el usuario"
                    draft.dataSource == ApplianceDataSource.MANUFACTURER_LABEL -> "Dato capturado de etiqueta o fabricante"
                    else -> "Estimación ajustada por el usuario"
                },
            ),
        )
    }
}

@Composable
fun AppliancesEmptyRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val context = LocalContext.current.applicationContext
    val certifiedCatalog = remember(context) { AssetEnergyStarCatalogSource(context) }
    val viewModel = rzViewModel { AppliancesEmptyViewModel(repository, certifiedCatalog) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AppliancesEmptyScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onAddCatalogItem = { item, draft ->
            viewModel.addCatalogItem(item, draft)
            navController.popBackStack()
        },
        onAddCustom = { draft ->
            viewModel.addCustom(draft)
            navController.popBackStack()
        },
        onSearchCertifiedModels = viewModel::searchCertifiedModels,
    )
}

@Composable
fun AppliancesEmptyScreen(
    uiState: AppliancesEmptyUiState,
    onBack: () -> Unit,
    onAddCatalogItem: (ApplianceCatalogItem, CatalogApplianceDraft) -> Unit,
    onAddCustom: (CustomApplianceDraft) -> Unit,
    onSearchCertifiedModels: (String, ApplianceCategory?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ApplianceCategory?>(null) }
    var selectedBrand by remember { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<ApplianceCatalogItem?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    val effectiveSearch = listOfNotNull(selectedBrand, query.trim().takeIf { it.isNotEmpty() }).joinToString(" ")
    LaunchedEffect(effectiveSearch, selectedCategory) {
        onSearchCertifiedModels(effectiveSearch, selectedCategory)
    }
    // The local catalog obeys the same rule as the certified one: if the query
    // named a brand, nothing from another brand may come back. Its items are
    // mostly generic ("Refrigerador", no brand), and offering those as the
    // answer to "Acros AT1330G" would attribute somebody else's kWh/año.
    val commonMatches = if (uiState.brandNotCovered != null) {
        emptyList()
    } else {
        uiState.catalog.filter { item ->
            (selectedCategory == null || item.category == selectedCategory) &&
                (selectedBrand == null || item.brand.equals(selectedBrand, ignoreCase = true)) &&
                (uiState.searchBrand == null || item.brand.equals(uiState.searchBrand, ignoreCase = true)) &&
                (
                    query.isBlank() ||
                        listOfNotNull(item.name, item.brand, item.model)
                            .any { it.contains(query, ignoreCase = true) }
                    )
        }
    }
    val certifiedMatches = uiState.certifiedModelResults.distinctBy { it.id }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_catalog_top_bar_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = RzSpacing.screenHorizontal),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.xs)) {
                    Text(
                        stringResource(R.string.appliances_catalog_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(
                            R.string.appliances_catalog_description,
                            uiState.catalog.size,
                            uiState.certifiedModelCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.appliances_catalog_period_context, uiState.billingDays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.appliances_add_step_category),
                    style = MaterialTheme.typography.titleMedium,
                )
                RzDropdownField(
                    label = stringResource(R.string.appliances_catalog_category),
                    selectedLabel = selectedCategory?.let { categoryLabel(it) }
                        ?: stringResource(R.string.appliances_catalog_all),
                    options = buildList {
                        add(
                            RzDropdownOption<ApplianceCategory?>(
                                null,
                                stringResource(R.string.appliances_catalog_all),
                            ),
                        )
                        ApplianceCategory.entries.forEach { category ->
                            add(RzDropdownOption(category, categoryLabel(category)))
                        }
                    },
                    onSelected = {
                        selectedCategory = it
                        selectedBrand = null
                        query = ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.xs),
                )
            }
            item {
                Text(
                    stringResource(R.string.appliances_add_step_brand),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.appliances_add_step_brand_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = RzSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.xs),
                ) {
                    RzFilterChip(
                        text = stringResource(R.string.appliances_add_any_brand),
                        selected = selectedBrand == null,
                        onClick = { selectedBrand = null },
                    )
                    uiState.quickBrands.forEach { brand ->
                        RzFilterChip(
                            text = brand,
                            selected = selectedBrand.equals(brand, ignoreCase = true),
                            onClick = { selectedBrand = brand },
                        )
                    }
                }
                if (uiState.isLoadingBrands) {
                    Text(
                        stringResource(R.string.appliances_add_loading_brands),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = RzSpacing.xs),
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.appliances_add_step_product),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(50) },
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.xs),
                    label = { Text(stringResource(R.string.appliances_catalog_search)) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (effectiveSearch.trim().length < 2) stringResource(R.string.appliances_catalog_model_search_hint)
                            else stringResource(R.string.appliances_catalog_international_notice),
                        )
                    },
                )
                RzSecondaryButton(
                    text = stringResource(R.string.appliances_custom_quick_add),
                    onClick = { showCustom = true },
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.sm),
                )
            }
            if (uiState.isSearchingCertifiedModels) {
                item { Text(stringResource(R.string.appliances_catalog_loading), style = MaterialTheme.typography.bodySmall) }
            }
            if (uiState.certifiedCatalogError) {
                item {
                    Text(
                        stringResource(R.string.appliances_catalog_load_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (commonMatches.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.appliances_add_common_results, commonMatches.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(commonMatches, key = { "common_${it.id}" }) { item ->
                    CatalogResultCard(item = item, onClick = { selectedItem = item })
                }
            }
            if (certifiedMatches.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.appliances_add_certified_results, certifiedMatches.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(certifiedMatches, key = { "certified_${it.id}" }) { item ->
                    CatalogResultCard(item = item, onClick = { selectedItem = item })
                }
            }
            if (commonMatches.isEmpty() && certifiedMatches.isEmpty() && !uiState.isSearchingCertifiedModels) {
                item {
                    RzDashedCard(modifier = Modifier.fillMaxWidth().clickable { showCustom = true }) {
                        val brandNotCovered = uiState.brandNotCovered
                        if (brandNotCovered != null) {
                            Text(
                                stringResource(R.string.appliances_catalog_brand_missing, brandNotCovered),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.appliances_catalog_brand_missing_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = RzSpacing.xs),
                            )
                        } else {
                            Text(stringResource(R.string.appliances_catalog_no_results))
                        }
                        Text(
                            stringResource(R.string.appliances_custom_quick_add),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = RzSpacing.sm),
                        )
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        CatalogItemDialog(
            item = item,
            billingDays = uiState.billingDays,
            effectiveRateMxnPerKwh = uiState.effectiveRateMxnPerKwh,
            hasCostReference = uiState.hasCostReference,
            spaces = uiState.spaces,
            defaultSpaceId = uiState.defaultSpaceId,
            onDismiss = { selectedItem = null },
            onConfirm = { draft -> onAddCatalogItem(item, draft) },
        )
    }
    if (showCustom) {
        CustomApplianceDialog(
            billingDays = uiState.billingDays,
            effectiveRateMxnPerKwh = uiState.effectiveRateMxnPerKwh,
            hasCostReference = uiState.hasCostReference,
            spaces = uiState.spaces,
            defaultSpaceId = uiState.defaultSpaceId,
            onDismiss = { showCustom = false },
            onConfirm = onAddCustom,
        )
    }
}

@Composable
private fun CatalogResultCard(item: ApplianceCatalogItem, onClick: () -> Unit) {
    RzCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(horizontalArrangement = Arrangement.spacedBy(RzSpacing.md)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                if (item.brand != null || item.model != null) {
                    Text(
                        listOfNotNull(item.brand, item.model).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    catalogUsageLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.marketMexicoVerified == false) {
                    Text(
                        stringResource(R.string.appliances_catalog_market_unverified),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.isModelFamily) {
                    Text(
                        stringResource(R.string.appliances_catalog_model_family),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.appliances_add_configure),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
            }
            Text(
                item.annualKwh?.let { stringResource(R.string.appliances_catalog_annual_short, it) }
                    ?: item.typicalKwhPer30Days?.let {
                        stringResource(R.string.appliances_catalog_monthly_short, it)
                    }
                    ?: stringResource(R.string.appliances_catalog_watts_short, item.typicalWatts),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun CatalogItemDialog(
    item: ApplianceCatalogItem,
    billingDays: Int,
    effectiveRateMxnPerKwh: Double,
    hasCostReference: Boolean,
    spaces: List<HouseSpace>,
    defaultSpaceId: String?,
    onDismiss: () -> Unit,
    onConfirm: (CatalogApplianceDraft) -> Unit,
) {
    val safeSpaces = spaces.ifEmpty { HouseSpaceDefaults.items }
    var selectedSpace by remember(safeSpaces, defaultSpaceId) {
        mutableStateOf(safeSpaces.firstOrNull { it.id == defaultSpaceId } ?: safeSpaces.first())
    }
    var quantity by remember { mutableStateOf("1") }
    var brand by remember(item.id) { mutableStateOf(item.brand.orEmpty()) }
    var model by remember(item.id) { mutableStateOf(item.model.orEmpty()) }
    var hours by remember(item.id) { mutableStateOf(item.defaultHoursPerWeek?.toString().orEmpty()) }
    var watts by remember(item.id) { mutableStateOf(item.typicalWatts.takeIf { it > 0 }?.toString().orEmpty()) }
    var standbyEnabled by remember(item.id) { mutableStateOf(false) }
    var standbyWatts by remember(item.id) { mutableStateOf(item.standbyWatts.takeIf { it > 0 }?.toString().orEmpty()) }
    val defaultPeriodKwh = item.annualKwh?.let { it * billingDays / 365.0 }
        ?: item.typicalKwhPer30Days?.let { it * billingDays / 30.0 }
    val schedulePeriodKwh = item.defaultHoursPerWeek?.let { defaultHours ->
        ApplianceCalculator.fromCatalog(item, defaultHours, billingDays, 0.0).let { it.activeKwh + it.standbyKwh }
    }
    var periodKwh by remember(item.id, billingDays) {
        mutableStateOf((defaultPeriodKwh ?: schedulePeriodKwh)?.let { "%.1f".format(java.util.Locale.US, it) }.orEmpty())
    }
    val canUseSchedule = item.typicalWatts > 0
    var usageModel by remember(item.id) {
        mutableStateOf(if (defaultPeriodKwh != null || !canUseSchedule) ApplianceUsageModel.PERIOD_KWH else ApplianceUsageModel.POWER_SCHEDULE)
    }
    val parsedQuantity = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val parsedHours = hours.toIntOrNull()?.takeIf { it in 0..168 }
    val parsedWatts = watts.toIntOrNull()?.takeIf { it in 1..100_000 }
    val parsedStandbyWatts = standbyWatts.toIntOrNull()?.takeIf { it in 1..10_000 }
    val parsedPeriodKwh = periodKwh.toDoubleOrNull()?.takeIf { it in 0.0..100_000.0 }
    val isPeriodBased = usageModel == ApplianceUsageModel.PERIOD_KWH
    val valid = parsedQuantity != null && if (isPeriodBased) {
        parsedPeriodKwh != null
    } else {
        parsedHours != null && parsedWatts != null && (!standbyEnabled || parsedStandbyWatts != null)
    }
    val calculationItem = item.copy(
        typicalWatts = parsedWatts ?: item.typicalWatts,
        annualKwh = item.annualKwh.takeIf { isPeriodBased },
        typicalKwhPer30Days = item.typicalKwhPer30Days.takeIf { isPeriodBased },
        standbyWatts = parsedStandbyWatts ?: item.standbyWatts,
    )
    val estimate = ApplianceCalculator.fromCatalog(
        item = calculationItem,
        hoursPerWeek = parsedHours,
        billingDays = billingDays,
        effectiveRateMxnPerKwh = effectiveRateMxnPerKwh,
        quantity = parsedQuantity ?: 1,
        periodKwhPerUnit = parsedPeriodKwh.takeIf { isPeriodBased },
        includeStandby = standbyEnabled && !isPeriodBased,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.appliances_add_step_configure), style = MaterialTheme.typography.labelLarge)
                Text(item.name)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                // The number is the reason the user is here: it stays at the top
                // and updates as the fields change, instead of hiding under them.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RzExtraShapes.cardSmall)
                        .padding(horizontal = RzSpacing.md, vertical = RzSpacing.sm),
                ) {
                    Text(
                        if (hasCostReference) stringResource(R.string.appliances_catalog_estimate, billingDays, estimate.kwh, estimate.costMxn)
                        else stringResource(R.string.appliances_catalog_estimate_without_cost, billingDays, estimate.kwh),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        estimate.calculation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RzDropdownField(
                    label = stringResource(R.string.appliances_space_select),
                    selectedLabel = selectedSpace.name,
                    options = safeSpaces.map { RzDropdownOption(it, it.name) },
                    onSelected = { selectedSpace = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    quantity,
                    { quantity = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.appliances_quantity)) },
                    supportingText = { Text(stringResource(R.string.appliances_quantity_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    brand,
                    { brand = it.take(40) },
                    label = { Text(stringResource(R.string.appliances_brand)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    model,
                    { model = it.take(60) },
                    label = { Text(stringResource(R.string.appliances_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RzDropdownField(
                    label = stringResource(R.string.appliances_consumption_method),
                    selectedLabel = stringResource(
                        if (isPeriodBased) R.string.appliances_consumption_by_kwh else R.string.appliances_consumption_by_average_power,
                    ),
                    options = buildList {
                        if (canUseSchedule) {
                            add(
                                RzDropdownOption(
                                    ApplianceUsageModel.POWER_SCHEDULE,
                                    stringResource(R.string.appliances_consumption_by_average_power),
                                ),
                            )
                        }
                        add(
                            RzDropdownOption(
                                ApplianceUsageModel.PERIOD_KWH,
                                stringResource(R.string.appliances_consumption_by_kwh),
                            ),
                        )
                    },
                    onSelected = { usageModel = it },
                    supportingText = stringResource(R.string.appliances_consumption_variable_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!isPeriodBased) {
                    OutlinedTextField(
                        watts,
                        { watts = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.appliances_average_active_watts)) },
                        supportingText = { Text(stringResource(R.string.appliances_average_active_watts_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        hours,
                        { hours = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.appliances_catalog_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (item.standbyWatts > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.appliances_standby_question), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.appliances_standby_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = standbyEnabled, onCheckedChange = { standbyEnabled = it })
                        }
                        if (standbyEnabled) {
                            OutlinedTextField(
                                standbyWatts,
                                { standbyWatts = it.filter(Char::isDigit).take(5) },
                                label = { Text(stringResource(R.string.appliances_standby_watts)) },
                                supportingText = { Text(stringResource(R.string.appliances_standby_watts_hint)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.appliances_catalog_cycle_based_editable), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        periodKwh,
                        { periodKwh = it.filter { char -> char.isDigit() || char == '.' }.take(8) },
                        label = { Text(stringResource(R.string.appliances_period_kwh_per_unit, billingDays)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item.sourceLabel?.let { source ->
                    Text(
                        stringResource(R.string.appliances_catalog_source, source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (item.marketMexicoVerified == false) {
                    Text(
                        stringResource(R.string.appliances_catalog_market_unverified_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.isModelFamily) {
                    Text(
                        stringResource(R.string.appliances_catalog_model_family_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.annualKwhMin != null && item.annualKwhMax != null && item.annualKwhMax - item.annualKwhMin >= 0.1) {
                    Text(
                        stringResource(R.string.appliances_catalog_annual_range, item.annualKwhMin, item.annualKwhMax),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        CatalogApplianceDraft(
                            space = selectedSpace,
                            quantity = parsedQuantity ?: 1,
                            hoursPerWeek = if (isPeriodBased) null else parsedHours,
                            periodKwhPerUnit = parsedPeriodKwh.takeIf { isPeriodBased },
                            brand = brand,
                            model = model,
                            usageModel = usageModel,
                            activeWatts = parsedWatts.takeIf { !isPeriodBased },
                            standbyEnabled = standbyEnabled && !isPeriodBased,
                            standbyWatts = parsedStandbyWatts.takeIf { standbyEnabled && !isPeriodBased },
                        ),
                    )
                },
            ) { Text(stringResource(R.string.appliances_catalog_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CustomApplianceDialog(
    billingDays: Int,
    effectiveRateMxnPerKwh: Double,
    hasCostReference: Boolean,
    spaces: List<HouseSpace>,
    defaultSpaceId: String?,
    onDismiss: () -> Unit,
    onConfirm: (CustomApplianceDraft) -> Unit,
) {
    val safeSpaces = spaces.ifEmpty { HouseSpaceDefaults.items }
    var name by remember { mutableStateOf("") }
    var selectedSpace by remember(safeSpaces, defaultSpaceId) {
        mutableStateOf(safeSpaces.firstOrNull { it.id == defaultSpaceId } ?: safeSpaces.first())
    }
    var quantity by remember { mutableStateOf("1") }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var usePeriodKwh by remember { mutableStateOf(false) }
    var dataSource by remember { mutableStateOf(ApplianceDataSource.USER_ESTIMATE) }
    var watts by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("7") }
    var periodKwh by remember { mutableStateOf("") }
    val quantityValue = quantity.toIntOrNull()?.takeIf { it in 1..999 }
    val wattsValue = watts.toIntOrNull()
    val hoursValue = hours.toDoubleOrNull()
    val daysValue = days.toIntOrNull()
    val periodKwhValue = periodKwh.toDoubleOrNull()?.takeIf { it in 0.0..100_000.0 }
    val scheduleValid = wattsValue != null && wattsValue in 1..100_000 &&
        hoursValue != null && hoursValue in 0.0..24.0 && daysValue != null && daysValue in 1..7
    val valid = name.isNotBlank() && quantityValue != null && if (usePeriodKwh) {
        periodKwhValue != null
    } else scheduleValid
    val estimate = when {
        !valid -> null
        usePeriodKwh -> {
            val exact = periodKwhValue!! * quantityValue!!
            app.recibozen.android.core.calculation.ApplianceUsageEstimate(
                kwh = exact.roundToInt(),
                costMxn = (exact * effectiveRateMxnPerKwh).roundToInt(),
                calculation = "$periodKwhValue kWh por unidad × $quantityValue unidades",
                activeKwh = exact,
            )
        }
        else -> ApplianceCalculator.fromPower(
            wattsValue!!,
            hoursValue!!,
            daysValue!!,
            billingDays,
            effectiveRateMxnPerKwh,
            quantityValue!!,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appliances_custom_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RzSpacing.sm),
            ) {
                estimate?.let {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RzExtraShapes.cardSmall)
                            .padding(horizontal = RzSpacing.md, vertical = RzSpacing.sm),
                    ) {
                        Text(
                            if (hasCostReference) stringResource(R.string.appliances_catalog_estimate, billingDays, it.kwh, it.costMxn)
                            else stringResource(R.string.appliances_catalog_estimate_without_cost, billingDays, it.kwh),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            it.calculation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(stringResource(R.string.appliances_custom_dialog_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    name,
                    { name = it.take(50) },
                    label = { Text(stringResource(R.string.appliances_custom_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RzDropdownField(
                    label = stringResource(R.string.appliances_space_select),
                    selectedLabel = selectedSpace.name,
                    options = safeSpaces.map { RzDropdownOption(it, it.name) },
                    onSelected = { selectedSpace = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    quantity,
                    { quantity = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.appliances_quantity)) },
                    supportingText = { Text(stringResource(R.string.appliances_quantity_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    brand,
                    { brand = it.take(40) },
                    label = { Text(stringResource(R.string.appliances_brand)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    model,
                    { model = it.take(60) },
                    label = { Text(stringResource(R.string.appliances_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RzDropdownField(
                    label = stringResource(R.string.appliances_consumption_method),
                    selectedLabel = stringResource(
                        if (usePeriodKwh) R.string.appliances_consumption_by_kwh else R.string.appliances_consumption_by_average_power,
                    ),
                    options = listOf(
                        RzDropdownOption(false, stringResource(R.string.appliances_consumption_by_average_power)),
                        RzDropdownOption(true, stringResource(R.string.appliances_consumption_by_kwh)),
                    ),
                    onSelected = { usePeriodKwh = it },
                    supportingText = stringResource(R.string.appliances_consumption_variable_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (usePeriodKwh) {
                    OutlinedTextField(
                        periodKwh,
                        { periodKwh = it.filter { char -> char.isDigit() || char == '.' }.take(8) },
                        label = { Text(stringResource(R.string.appliances_period_kwh_per_unit, billingDays)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val sourceOptions = listOf(
                        RzDropdownOption(
                            ApplianceDataSource.USER_MEASURED,
                            stringResource(R.string.appliances_source_measured),
                        ),
                        RzDropdownOption(
                            ApplianceDataSource.MANUFACTURER_LABEL,
                            stringResource(R.string.appliances_source_label),
                        ),
                        RzDropdownOption(
                            ApplianceDataSource.USER_ESTIMATE,
                            stringResource(R.string.appliances_source_estimated),
                        ),
                    )
                    RzDropdownField(
                        label = stringResource(R.string.appliances_usage_source_title),
                        selectedLabel = when (dataSource) {
                            ApplianceDataSource.USER_MEASURED -> stringResource(R.string.appliances_source_measured)
                            ApplianceDataSource.MANUFACTURER_LABEL -> stringResource(R.string.appliances_source_label)
                            else -> stringResource(R.string.appliances_source_estimated)
                        },
                        options = sourceOptions,
                        onSelected = { dataSource = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        watts,
                        { watts = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.appliances_average_active_watts)) },
                        supportingText = { Text(stringResource(R.string.appliances_average_active_watts_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        hours,
                        { hours = it.filter { char -> char.isDigit() || char == '.' }.take(4) },
                        label = { Text(stringResource(R.string.appliances_custom_hours_day)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        days,
                        { days = it.filter(Char::isDigit).take(1) },
                        label = { Text(stringResource(R.string.appliances_custom_days_week)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        CustomApplianceDraft(
                            name = name,
                            space = selectedSpace,
                            quantity = quantityValue ?: 1,
                            brand = brand,
                            model = model,
                            usageModel = if (usePeriodKwh) ApplianceUsageModel.PERIOD_KWH else ApplianceUsageModel.POWER_SCHEDULE,
                            watts = wattsValue,
                            hoursPerDay = hoursValue,
                            daysPerWeek = daysValue,
                            periodKwhPerUnit = periodKwhValue,
                            dataSource = if (usePeriodKwh) dataSource else ApplianceDataSource.USER_ESTIMATE,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.appliances_catalog_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun categoryLabel(category: ApplianceCategory): String = when (category) {
    ApplianceCategory.CLIMATE -> stringResource(R.string.appliances_category_climate)
    ApplianceCategory.KITCHEN -> stringResource(R.string.appliances_category_kitchen)
    ApplianceCategory.LAUNDRY -> stringResource(R.string.appliances_category_laundry)
    ApplianceCategory.ENTERTAINMENT -> stringResource(R.string.appliances_category_entertainment)
    ApplianceCategory.COMPUTING -> stringResource(R.string.appliances_category_computing)
    ApplianceCategory.LIGHTING -> stringResource(R.string.appliances_category_lighting)
    ApplianceCategory.WATER -> stringResource(R.string.appliances_category_water)
    ApplianceCategory.PERSONAL_CARE -> stringResource(R.string.appliances_category_personal_care)
    ApplianceCategory.OTHER -> stringResource(R.string.appliances_category_other)
}

@Composable
private fun catalogUsageLabel(item: ApplianceCatalogItem): String = when {
    item.annualKwh != null -> stringResource(R.string.appliances_catalog_annual_reference, item.annualKwh)
    item.typicalKwhPer30Days != null -> stringResource(R.string.appliances_catalog_monthly_reference, item.typicalKwhPer30Days)
    else -> stringResource(R.string.appliances_catalog_weekly_reference, item.defaultHoursPerWeek ?: 0)
}

@Preview(showBackground = true)
@Composable
private fun AppliancesEmptyScreenPreview() {
    ReciboZenTheme {
        AppliancesEmptyScreen(
            uiState = AppliancesEmptyUiState(),
            onBack = {},
            onAddCatalogItem = { _, _ -> },
            onAddCustom = {},
            onSearchCertifiedModels = { _, _ -> },
        )
    }
}
