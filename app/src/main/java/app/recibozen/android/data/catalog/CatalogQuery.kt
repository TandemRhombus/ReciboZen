package app.recibozen.android.data.catalog

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * Query understanding for the appliance catalog.
 *
 * The previous matcher required every typed token to appear as a substring of a
 * record, which failed on the way people actually write: "pantalla lg 55
 * pulgadas", "minisplit", "whirpool". Loosening it alone is worse, not better:
 * on the real 9,305-model dataset a permissive matcher answers "IEM
 * refrigerador" with 4,535 records, none of them IEM. A wrong kWh/año does not
 * stay in the search screen — it lands in the inventory, the balance, the
 * projection and the DAC risk.
 *
 * So the rule here is the opposite of "always return something": a query that
 * names a brand may only return that brand, and a query that names a product
 * kind the dataset does not carry returns nothing at all, with a reason.
 */
object CatalogText {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .trim()

    fun tokens(value: String): List<String> = normalize(value).split(' ').filter(String::isNotBlank)

    /** Bounded edit distance; returns [limit] + 1 as soon as no cell can beat it. */
    fun editDistanceAtMost(a: String, b: String, limit: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowBest = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
                rowBest = min(rowBest, current[j])
            }
            if (rowBest > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
}

/**
 * Product kinds as people write them in Mexico, mapped to the subcategory names
 * the dataset actually uses. Nobody types "aire_acondicionado_ventana_portatil".
 */
enum class ProductKind(val label: String, val subcategories: Set<String>) {
    REFRIGERATOR("refrigerador", setOf("refrigerador_residencial")),
    FREEZER("congelador", setOf("congelador_residencial")),
    AIR_CONDITIONER("aire acondicionado", setOf("aire_acondicionado_ventana_portatil")),
    TELEVISION("televisor", setOf("televisor")),
    COMPUTER("computadora", setOf("computadora")),
    WASHER("lavadora", setOf("lavadora_residencial")),
    DISHWASHER("lavavajillas", setOf("lavavajillas_residencial")),
    WATER_HEATER("calentador de agua", setOf("calentador_agua")),
    ;

    companion object {
        private val BY_WORD: Map<String, ProductKind> = buildMap {
            fun put(kind: ProductKind, vararg words: String) = words.forEach { put(CatalogText.normalize(it), kind) }
            put(REFRIGERATOR, "refrigerador", "refri", "refrigerator", "nevera", "heladera", "frigorifico", "frigobar")
            put(FREEZER, "congelador", "freezer", "conservador")
            put(
                AIR_CONDITIONER,
                "aire", "acondicionado", "clima", "minisplit", "split", "inverter", "climatizacion",
            )
            put(TELEVISION, "televisor", "television", "tele", "tv", "pantalla")
            put(COMPUTER, "computadora", "compu", "computer", "pc", "laptop", "portatil", "notebook", "desktop")
            put(WASHER, "lavadora", "washer", "washing")
            put(DISHWASHER, "lavavajillas", "lavaplatos", "lavatrastes", "dishwasher")
            put(WATER_HEATER, "calentador", "boiler", "boyler", "calenton", "calentadores")
        }

        fun of(token: String): ProductKind? = BY_WORD[token]
    }
}

/**
 * Brands sold in Mexico that the ENERGY STAR dataset does not carry. Counted on
 * the shipped catalog: Mabe has 2 models, and Acros, IEM, Koblenz, Winia,
 * Mirage, Atvio and Easy have none. Knowing the name is a brand — even one we
 * cannot answer for — is what lets the app say "no tenemos esa marca" instead
 * of handing back somebody else's refrigerator.
 */
private val BRANDS_SOLD_IN_MEXICO_WITHOUT_DATA = mapOf(
    "mabe" to "Mabe",
    "acros" to "Acros",
    "iem" to "IEM",
    "koblenz" to "Koblenz",
    "winia" to "Winia",
    "daewoo" to "Daewoo",
    "mirage" to "Mirage",
    "atvio" to "Atvio",
    "easy" to "Easy",
    "hisense" to "Hisense",
    "tcl" to "TCL",
)

/**
 * Words that are never a brand. Without this list "horno" gets corrected to the
 * real ENERGY STAR brand "Forno" — one substitution away — and "horno de
 * microondas" comes back as 60 ovens of a brand nobody typed.
 */
private val NEVER_A_BRAND = setOf(
    // Appliances the dataset has no subcategory for. Naming them here is what
    // makes the app answer "no encontramos eso" instead of guessing.
    "horno", "hornito", "microondas", "licuadora", "batidora", "plancha", "ventilador",
    "estufa", "parrilla", "secadora", "bomba", "tostador", "cafetera", "aspiradora",
    "extractor", "campana", "freidora", "olla", "sarten", "purificador", "deshumidificador",
    "humidificador", "calefactor", "bomba", "motobomba", "hidroneumatico", "alberca",
    // Places, sizes and units.
    "casa", "cocina", "sala", "cuarto", "recamara", "bano", "patio", "garage", "techo",
    "grande", "chico", "pequeno", "mediano", "nuevo", "viejo", "gamer", "gaming",
    "puertas", "puerta", "pulgadas", "pulgada", "litros", "pies", "btu", "watts", "kwh",
    "electrico", "electrica", "gas", "solar",
    // Function words.
    "de", "la", "el", "un", "una", "con", "sin", "para", "mi", "que", "por", "del", "y", "o",
)

data class ParsedCatalogQuery(
    val raw: String,
    val tokens: List<String>,
    /** Canonical brand the user meant, resolved through the catalog vocabulary. */
    val brand: String?,
    /** Brand as the user typed it, for messages. */
    val brandAsTyped: String?,
    val brandIsKnownButUncovered: Boolean,
    val kinds: Set<ProductKind>,
    /** Tokens that look like a model code: they carry digits or are long alphanumerics. */
    val modelTokens: List<String>,
    val otherTokens: List<String>,
) {
    val isEmpty: Boolean get() = tokens.isEmpty()
}

class CatalogQueryParser(brandVocabulary: Collection<String>) {
    private val canonicalByNormalized: Map<String, String> = brandVocabulary.asSequence()
        .map { CatalogText.normalize(it) to it.trim() }
        .filter { it.first.isNotBlank() }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, spellings) -> spellings.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key }

    private val normalizedBrands: List<String> = canonicalByNormalized.keys.toList()

    fun parse(raw: String): ParsedCatalogQuery {
        val tokens = CatalogText.tokens(raw)
        val kinds = tokens.mapNotNull(ProductKind::of).toSet()
        val kindWords = tokens.filter { ProductKind.of(it) != null }.toSet()

        // Typo correction is only safe when the query is shaped like someone
        // naming a product: one word on its own, or a word next to a model code
        // or a product kind. In free prose ("horno de microondas") every noun is
        // one or two edits from some brand in a 500-brand vocabulary.
        val allowFuzzy = tokens.size == 1 ||
            kinds.isNotEmpty() ||
            tokens.any(::looksLikeModelCode)

        var brand: String? = null
        var brandAsTyped: String? = null
        var uncovered = false
        for (token in tokens) {
            if (token in kindWords || token in NEVER_A_BRAND) continue
            val resolved = resolveBrand(token, allowFuzzy) ?: continue
            brand = resolved.canonical
            brandAsTyped = token
            uncovered = resolved.uncovered
            break
        }

        val consumed = buildSet {
            addAll(kindWords)
            brandAsTyped?.let(::add)
        }
        val rest = tokens.filterNot { it in consumed }
        return ParsedCatalogQuery(
            raw = raw.trim(),
            tokens = tokens,
            brand = brand,
            brandAsTyped = brandAsTyped?.replaceFirstChar { it.titlecase(Locale.ROOT) },
            brandIsKnownButUncovered = uncovered,
            kinds = kinds,
            modelTokens = rest.filter(::looksLikeModelCode),
            otherTokens = rest.filterNot(::looksLikeModelCode),
        )
    }

    private data class ResolvedBrand(val canonical: String, val uncovered: Boolean)

    private fun resolveBrand(token: String, allowFuzzy: Boolean): ResolvedBrand? {
        canonicalByNormalized[token]?.let { return ResolvedBrand(it, uncovered = false) }
        BRANDS_SOLD_IN_MEXICO_WITHOUT_DATA[token]?.let { return ResolvedBrand(it, uncovered = true) }
        if (!allowFuzzy) return null

        // Typo correction only against brands the catalog really has, and only
        // for long alphabetic tokens. "rf28" is a model code, and "agua" is four
        // letters away from half the brand list — correcting either one invents
        // a brand nobody typed. The curated Mexican list gets no fuzzy matching
        // at all: being wrong there costs a false "no la tenemos".
        if (token.any(Char::isDigit) || token.length < MIN_FUZZY_LENGTH) return null
        val budget = if (token.length >= 8) 2 else 1
        var best: String? = null
        var bestDistance = budget + 1
        for (candidate in normalizedBrands) {
            val distance = CatalogText.editDistanceAtMost(token, candidate, budget)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best?.takeIf { bestDistance <= budget }
            ?.let { ResolvedBrand(canonicalByNormalized.getValue(it), uncovered = false) }
    }

    /** "rf28", "dfr25210" are model codes; "55" in "55 pulgadas" is not. */
    private fun looksLikeModelCode(token: String): Boolean =
        token.any(Char::isDigit) && (token.any { !it.isDigit() } || token.length >= 3)

    private companion object {
        const val MIN_FUZZY_LENGTH = 5
    }
}
