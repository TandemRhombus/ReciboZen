package com.recibozen.app.data.catalog

import android.content.Context
import com.recibozen.app.core.calculation.ApplianceCatalogItem
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ConfidenceLevel
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
    private val searchable = normalizeSearchText("$brand $model ${subcategory.replace('_', ' ')} ${displayName()}")

    fun matches(query: String, categoryFilter: ApplianceCategory?): Boolean {
        if (categoryFilter != null && category != categoryFilter) return false
        return normalizeSearchText(query).split(' ').filter(String::isNotBlank).all(searchable::contains)
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
}

class EnergyStarCatalogIndex(private val records: List<EnergyStarCatalogRecord>) {
    val size: Int get() = records.size

    fun search(
        query: String,
        category: ApplianceCategory? = null,
        limit: Int = 80,
    ): List<ApplianceCatalogItem> {
        if (query.trim().length < 2) return emptyList()
        return records.asSequence()
            .filter { it.matches(query, category) }
            .sortedWith(compareBy<EnergyStarCatalogRecord> { it.rank(query) }.thenBy { it.brand }.thenBy { it.model })
            .take(limit.coerceIn(1, 200))
            .map(EnergyStarCatalogRecord::toCatalogItem)
            .toList()
    }

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
    suspend fun search(query: String, category: ApplianceCategory? = null, limit: Int = 80): List<ApplianceCatalogItem>
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

    override suspend fun search(
        query: String,
        category: ApplianceCategory?,
        limit: Int,
    ): List<ApplianceCatalogItem> = withContext(Dispatchers.IO) {
        index().search(query, category, limit)
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
