package app.recibozen.android.data.catalog

import android.content.Context
import app.recibozen.android.core.calculation.ApplianceCatalogItem
import app.recibozen.android.core.model.ApplianceCategory
import app.recibozen.android.core.model.ApplianceDataSource
import app.recibozen.android.core.model.ConfidenceLevel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EnergyStarCatalogMetadata(
    val datasetVersion: String,
    val sourceRows: Int,
    val searchableModels: Int,
    val excludedMisclassifiedIceMachineRows: Int,
    val marketMexicoVerifiedModels: Int,
)

data class EnergyStarCatalogRecord(
    val id: String,
    val sourceCategory: String,
    val subcategory: String,
    val brand: String,
    val model: String,
    val annualKwh: Double,
    val annualKwhMin: Double,
    val annualKwhMax: Double,
    val capacityValue: Double?,
    val capacityUnit: String?,
    val modelFamily: Boolean,
    val marketMexicoVerified: Boolean,
    val sourceDatasetId: String,
    val certifiedDate: String?,
) {
    val category: ApplianceCategory = sourceCategory.toApplianceCategory()
    internal val normalizedBrand = normalizeSearchText(brand)
    private val normalizedModel = normalizeSearchText(model)
    private val searchable = normalizeSearchText("$brand $model ${subcategory.replace('_', ' ')} ${displayName()}")

    fun matches(query: String, categoryFilter: ApplianceCategory?): Boolean {
        if (categoryFilter != null && category != categoryFilter) return false
        return normalizeSearchText(query).split(' ').filter(String::isNotBlank).all(searchable::contains)
    }

    /**
     * Returns null when the record is not an acceptable answer, and a score
     * (higher is better) when it is. The brand and product-kind checks are
     * gates, not scores: a query that names a brand must never be answered with
     * a different one.
     */
    internal fun scoreFor(
        parsed: ParsedCatalogQuery,
        categoryFilter: ApplianceCategory?,
        requireModelTokenMatch: Boolean,
    ): Int? {
        if (categoryFilter != null && category != categoryFilter) return null
        if (parsed.brand != null && normalizedBrand != normalizeSearchText(parsed.brand)) return null
        if (parsed.kinds.isNotEmpty() && parsed.kinds.none { subcategory in it.subcategories }) return null
        if (requireModelTokenMatch && parsed.modelTokens.none(normalizedModel::contains)) return null

        val anchored = parsed.brand != null || parsed.kinds.isNotEmpty()
        if (!anchored) {
            // Without a brand or a recognized product kind there is nothing to
            // anchor a loose match to, so the strict rule stays: every token has
            // to be in the record. This is what keeps "bomba de agua" from
            // returning every water heater in the dataset.
            return if (parsed.tokens.all(searchable::contains)) BASE_SCORE else null
        }

        var score = BASE_SCORE
        if (parsed.brand != null) score += BRAND_MATCH_SCORE
        if (parsed.kinds.isNotEmpty()) score += KIND_MATCH_SCORE
        parsed.modelTokens.forEach { token ->
            if (normalizedModel.contains(token)) score += MODEL_TOKEN_SCORE
        }
        parsed.otherTokens.forEach { token ->
            if (searchable.contains(token)) score += OTHER_TOKEN_SCORE
        }
        return score
    }

    fun rank(query: String): Int {
        val normalized = normalizeSearchText(query)
        val normalizedModel = normalizeSearchText(model)
        val normalizedBrand = normalizeSearchText(brand)
        val normalizedIdentity = "$normalizedBrand $normalizedModel"
        return when {
            normalizedIdentity == normalized -> 0
            normalizedModel == normalized -> 0
            normalizedIdentity.startsWith(normalized) -> 1
            normalizedModel.startsWith(normalized) -> 1
            normalizedBrand == normalized -> 2
            normalizedBrand.startsWith(normalized) -> 3
            searchable.startsWith(normalized) -> 4
            else -> 5
        }
    }

    fun toCatalogItem(): ApplianceCatalogItem {
        val rangeNote = if (annualKwhMax - annualKwhMin >= 0.1) {
            " · certificaciones encontradas: ${format(annualKwhMin)}–${format(annualKwhMax)} kWh/año"
        } else ""
        val marketNote = if (marketMexicoVerified) "mercado México verificado" else "venta en México no verificada"
        return ApplianceCatalogItem(
            id = "energy_star_$id",
            name = displayName(),
            category = category,
            typicalWatts = 0,
            defaultHoursPerWeek = null,
            brand = brand,
            model = model,
            annualKwh = annualKwh,
            annualKwhMin = annualKwhMin,
            annualKwhMax = annualKwhMax,
            sourceLabel = "Certificación ENERGY STAR · $marketNote$rangeNote",
            sourceUrl = "https://data.energystar.gov/d/$sourceDatasetId",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL,
            confidence = ConfidenceLevel.HIGH,
            marketMexicoVerified = marketMexicoVerified,
            isModelFamily = modelFamily,
        )
    }

    private fun displayName(): String {
        val base = when (subcategory) {
            "refrigerador_residencial" -> "Refrigerador"
            "congelador_residencial" -> "Congelador"
            "computadora" -> "Computadora"
            "lavavajillas_residencial" -> "Lavavajillas"
            "calentador_agua" -> "Calentador de agua"
            "aire_acondicionado_ventana_portatil" -> "Aire acondicionado de habitación"
            "lavadora_residencial" -> "Lavadora"
            "televisor" -> "Televisor"
            else -> subcategory.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        val capacity = capacityValue?.let { value ->
            val unit = when (capacityUnit) {
                "ft3" -> "pies³"
                "btu_h" -> "BTU/h"
                "gal" -> "gal"
                "in" -> "pulgadas"
                else -> capacityUnit.orEmpty()
            }
            " ${format(value)} $unit"
        }.orEmpty()
        return "$base$capacity"
    }

    internal companion object {
        const val BASE_SCORE = 1
        const val BRAND_MATCH_SCORE = 40
        const val KIND_MATCH_SCORE = 20
        const val MODEL_TOKEN_SCORE = 30
        const val OTHER_TOKEN_SCORE = 5
    }
}

/**
 * Why a search came back the way it did. The screen needs this because "no
 * tenemos esa marca todavía" and "no encontramos nada" ask the user to do
 * different things.
 */
sealed interface CatalogSearchOutcome {
    /** Fewer than two characters typed: nothing to answer yet. */
    data object NotEnoughInput : CatalogSearchOutcome

    data class Matches(
        val items: List<ApplianceCatalogItem>,
        /** Brand the query was locked to, if any. */
        val brand: String?,
    ) : CatalogSearchOutcome

    /**
     * The user named a brand and the catalog has no model of it. Returning
     * another brand's kWh/año here is how a wrong number gets into the
     * inventory, so we return none and say so.
     */
    data class BrandNotCovered(val brand: String) : CatalogSearchOutcome

    data class NoMatches(val query: String) : CatalogSearchOutcome
}

class EnergyStarCatalogIndex(private val records: List<EnergyStarCatalogRecord>) {
    val size: Int get() = records.size

    private val parser = CatalogQueryParser(records.map(EnergyStarCatalogRecord::brand))

    fun search(
        query: String,
        category: ApplianceCategory? = null,
        limit: Int = 80,
    ): List<ApplianceCatalogItem> = when (val outcome = searchOutcome(query, category, limit)) {
        is CatalogSearchOutcome.Matches -> outcome.items
        else -> emptyList()
    }

    fun searchOutcome(
        query: String,
        category: ApplianceCategory? = null,
        limit: Int = 80,
    ): CatalogSearchOutcome {
        if (query.trim().length < 2) return CatalogSearchOutcome.NotEnoughInput
        val parsed = parser.parse(query)
        if (parsed.isEmpty) return CatalogSearchOutcome.NotEnoughInput

        // When the user typed a model code and the catalog has it, answering with
        // the other 290 models of the same brand buries the one they asked for.
        // Only if no model code matches do we fall back to the whole brand.
        val items = collect(parsed, category, limit, requireModelTokenMatch = parsed.modelTokens.isNotEmpty())
            .ifEmpty { collect(parsed, category, limit, requireModelTokenMatch = false) }

        if (items.isNotEmpty()) return CatalogSearchOutcome.Matches(items, parsed.brand)
        // "No tenemos esa marca" has to mean it, so it is only said when the
        // brand is absent from the whole catalog — not when the category filter
        // or the product kind is what left the list empty.
        val brand = parsed.brand ?: return CatalogSearchOutcome.NoMatches(parsed.raw)
        val normalizedBrand = normalizeSearchText(brand)
        return if (records.none { it.normalizedBrand == normalizedBrand }) {
            CatalogSearchOutcome.BrandNotCovered(brand)
        } else {
            CatalogSearchOutcome.NoMatches(parsed.raw)
        }
    }

    private fun collect(
        parsed: ParsedCatalogQuery,
        category: ApplianceCategory?,
        limit: Int,
        requireModelTokenMatch: Boolean,
    ): List<ApplianceCatalogItem> = records.asSequence()
        .mapNotNull { record ->
            record.scoreFor(parsed, category, requireModelTokenMatch)?.let { record to it }
        }
        .sortedWith(
            compareByDescending<Pair<EnergyStarCatalogRecord, Int>> { it.second }
                .thenBy { it.first.rank(parsed.raw) }
                .thenBy { it.first.brand }
                .thenBy { it.first.model },
        )
        .take(limit.coerceIn(1, 200))
        .map { it.first.toCatalogItem() }
        .toList()

    fun popularBrands(
        category: ApplianceCategory? = null,
        limit: Int = 16,
    ): List<String> = records.asSequence()
        .filter { category == null || it.category == category }
        .filter { it.brand.isNotBlank() }
        .groupBy { normalizeSearchText(it.brand) }
        .values
        .map { sameBrand ->
            val displayName = sameBrand
                .groupingBy { it.brand.trim() }
                .eachCount()
                .maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
                .key
            displayName to sameBrand.size
        }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase(Locale.ROOT) })
        .take(limit.coerceIn(1, 50))
        .map(Pair<String, Int>::first)
}

interface EnergyStarCatalogSource {
    suspend fun metadata(): EnergyStarCatalogMetadata
    suspend fun searchOutcome(
        query: String,
        category: ApplianceCategory? = null,
        limit: Int = 80,
    ): CatalogSearchOutcome
    suspend fun popularBrands(category: ApplianceCategory? = null, limit: Int = 16): List<String>
}

class AssetEnergyStarCatalogSource(context: Context) : EnergyStarCatalogSource {
    private val appContext = context.applicationContext
    private val loadMutex = Mutex()
    @Volatile private var loadedIndex: EnergyStarCatalogIndex? = null
    @Volatile private var loadedMetadata: EnergyStarCatalogMetadata? = null

    override suspend fun metadata(): EnergyStarCatalogMetadata = withContext(Dispatchers.IO) {
        loadedMetadata ?: loadMutex.withLock {
            loadedMetadata ?: readMetadata().also { loadedMetadata = it }
        }
    }

    override suspend fun searchOutcome(
        query: String,
        category: ApplianceCategory?,
        limit: Int,
    ): CatalogSearchOutcome = withContext(Dispatchers.IO) {
        index().searchOutcome(query, category, limit)
    }

    override suspend fun popularBrands(category: ApplianceCategory?, limit: Int): List<String> =
        withContext(Dispatchers.IO) { index().popularBrands(category, limit) }

    private suspend fun index(): EnergyStarCatalogIndex = loadedIndex ?: loadMutex.withLock {
        loadedIndex ?: readIndex().also { loadedIndex = it }
    }

    private fun readMetadata(): EnergyStarCatalogMetadata {
        val json = appContext.assets.open(METADATA_ASSET).bufferedReader().use { it.readText() }.let(::JSONObject)
        return EnergyStarCatalogMetadata(
            datasetVersion = json.getString("datasetVersion"),
            sourceRows = json.getInt("sourceRows"),
            searchableModels = json.getInt("searchableModels"),
            excludedMisclassifiedIceMachineRows = json.getInt("excludedMisclassifiedIceMachineRows"),
            marketMexicoVerifiedModels = json.getInt("marketMexicoVerifiedModels"),
        )
    }

    private fun readIndex(): EnergyStarCatalogIndex {
        val records = buildList {
            appContext.assets.open(MODELS_ASSET).use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    BufferedReader(InputStreamReader(gzip, Charsets.UTF_8)).useLines { lines ->
                        lines.filter(String::isNotBlank).forEach { line -> add(parseRecord(JSONObject(line))) }
                    }
                }
            }
        }
        return EnergyStarCatalogIndex(records)
    }

    private fun parseRecord(json: JSONObject) = EnergyStarCatalogRecord(
        id = json.getString("id"),
        sourceCategory = json.getString("category"),
        subcategory = json.getString("subcategory"),
        brand = json.getString("brand"),
        model = json.getString("model"),
        annualKwh = json.getDouble("annualKwh"),
        annualKwhMin = json.getDouble("annualKwhMin"),
        annualKwhMax = json.getDouble("annualKwhMax"),
        capacityValue = json.optNullableDouble("capacityValue"),
        capacityUnit = json.optNullableString("capacityUnit"),
        modelFamily = json.optBoolean("modelFamily"),
        marketMexicoVerified = json.optBoolean("marketMexicoVerified"),
        sourceDatasetId = json.getString("sourceDatasetId"),
        certifiedDate = json.optNullableString("certifiedDate"),
    )

    private fun JSONObject.optNullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
    private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    companion object {
        // Do not use a .gz suffix: AAPT transparently expands and renames .gz assets.
        private const val MODELS_ASSET = "catalog/energy_star_models_v1.jsonl.gzip"
        private const val METADATA_ASSET = "catalog/energy_star_models_v1.metadata.json"
    }
}

private fun String.toApplianceCategory(): ApplianceCategory = when (this) {
    "climatizacion" -> ApplianceCategory.CLIMATE
    "refrigeracion", "cocina" -> ApplianceCategory.KITCHEN
    "lavanderia" -> ApplianceCategory.LAUNDRY
    "tv_entretenimiento" -> ApplianceCategory.ENTERTAINMENT
    "computo" -> ApplianceCategory.COMPUTING
    "iluminacion" -> ApplianceCategory.LIGHTING
    "agua", "agua_calefaccion" -> ApplianceCategory.WATER
    else -> ApplianceCategory.OTHER
}

private fun normalizeSearchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()

private fun format(value: Double): String = if (value % 1.0 == 0.0) {
    value.toInt().toString()
} else {
    "%.1f".format(Locale.US, value)
}
