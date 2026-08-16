package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ApplianceUsageModel
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import kotlin.math.roundToInt

data class ApplianceCatalogItem(
    val id: String,
    val name: String,
    val category: ApplianceCategory,
    val typicalWatts: Int,
    val defaultHoursPerWeek: Int?,
    val typicalKwhPer30Days: Double? = null,
    val standbyWatts: Int = 0,
    val brand: String? = null,
    val model: String? = null,
    val annualKwh: Double? = null,
    /** Lowest certified value found for the same normalized brand/model. */
    val annualKwhMin: Double? = null,
    /** Highest certified value found for the same normalized brand/model. */
    val annualKwhMax: Double? = null,
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
    val dataSource: ApplianceDataSource? = null,
    val confidence: ConfidenceLevel? = null,
    /** null means the source did not make a market claim. */
    val marketMexicoVerified: Boolean? = null,
    /** ENERGY STAR sometimes certifies a wildcard model family instead of one exact SKU. */
    val isModelFamily: Boolean = false,
)

data class ApplianceUsageEstimate(
    val kwh: Int,
    val costMxn: Int,
    val calculation: String,
    val activeKwh: Double = kwh.toDouble(),
    val standbyKwh: Double = 0.0,
)

object ApplianceCalculator {
    fun fromPower(
        watts: Int,
        hoursPerDay: Double,
        daysPerWeek: Int,
        billingDays: Int,
        effectiveRateMxnPerKwh: Double,
        quantity: Int = 1,
        standbyWatts: Int = 0,
        includeStandby: Boolean = false,
    ): ApplianceUsageEstimate {
        val safeWatts = watts.coerceIn(1, 100_000)
        val safeHours = hoursPerDay.coerceIn(0.0, 24.0)
        val safeDays = daysPerWeek.coerceIn(0, 7)
        val safeBillingDays = billingDays.coerceIn(1, 366)
        val safeQuantity = quantity.coerceIn(1, 999)
        val unitKwh = safeWatts / 1_000.0 * safeHours * safeDays / 7.0 * safeBillingDays
        val unitStandbyKwh = if (includeStandby && standbyWatts > 0) {
            standbyWatts.coerceIn(1, 10_000) / 1_000.0 *
                (168.0 - safeHours * safeDays).coerceAtLeast(0.0) * safeBillingDays / 7.0
        } else 0.0
        val activeKwh = unitKwh * safeQuantity
        val standbyKwh = unitStandbyKwh * safeQuantity
        val kwhExact = activeKwh + standbyKwh
        val kwh = kwhExact.roundToInt().coerceAtLeast(0)
        return ApplianceUsageEstimate(
            kwh = kwh,
            costMxn = (kwhExact * effectiveRateMxnPerKwh.coerceAtLeast(0.0)).roundToInt(),
            calculation = buildString {
                append("$safeWatts W × ${format(safeHours)} h/día × $safeDays días/semana × $safeBillingDays días ÷ 7 ÷ 1,000")
                if (safeQuantity > 1) append(" × $safeQuantity unidades")
                if (unitStandbyKwh > 0) append(" + ${standbyWatts.coerceIn(1, 10_000)} W en espera")
            },
            activeKwh = activeKwh,
            standbyKwh = standbyKwh,
        )
    }

    fun fromCatalog(
        item: ApplianceCatalogItem,
        hoursPerWeek: Int? = item.defaultHoursPerWeek,
        billingDays: Int,
        effectiveRateMxnPerKwh: Double,
        quantity: Int = 1,
        periodKwhPerUnit: Double? = null,
        includeStandby: Boolean = false,
    ): ApplianceUsageEstimate {
        val safeBillingDays = billingDays.coerceAtLeast(1)
        val safeQuantity = quantity.coerceIn(1, 999)
        val unitActiveKwh = periodKwhPerUnit?.coerceAtLeast(0.0)
            ?: item.annualKwh?.let { it * safeBillingDays / 365.0 }
            ?: item.typicalKwhPer30Days?.let { it * safeBillingDays / 30.0 }
            ?: (item.typicalWatts / 1_000.0 * (hoursPerWeek ?: 0) * safeBillingDays / 7.0)
        val unitStandbyKwh = if (
            includeStandby && periodKwhPerUnit == null && item.annualKwh == null &&
                item.typicalKwhPer30Days == null && item.standbyWatts > 0
        ) {
            val inactiveHoursPerWeek = (168 - (hoursPerWeek ?: 0)).coerceAtLeast(0)
            item.standbyWatts / 1_000.0 * inactiveHoursPerWeek * safeBillingDays / 7.0
        } else 0.0
        val activeKwh = unitActiveKwh * safeQuantity
        val standbyKwh = unitStandbyKwh * safeQuantity
        val exactKwh = activeKwh + standbyKwh
        val kwh = exactKwh.roundToInt().coerceAtLeast(0)
        val calculation = buildString {
            when {
                periodKwhPerUnit != null -> append("${format(periodKwhPerUnit)} kWh por unidad en $billingDays días")
                item.annualKwh != null -> append("${format(item.annualKwh)} kWh/año × $billingDays ÷ 365")
                item.typicalKwhPer30Days != null -> append("${format(item.typicalKwhPer30Days)} kWh/30 días × $billingDays ÷ 30")
                else -> append("${item.typicalWatts} W × ${hoursPerWeek ?: 0} h/semana × $billingDays días ÷ 7 ÷ 1,000")
            }
            if (unitStandbyKwh > 0) append(" + ${item.standbyWatts} W en espera")
            if (safeQuantity > 1) append(" × $safeQuantity unidades")
        }
        return ApplianceUsageEstimate(
            kwh = kwh,
            costMxn = (exactKwh * effectiveRateMxnPerKwh).roundToInt(),
            calculation = calculation,
            activeKwh = activeKwh,
            standbyKwh = standbyKwh,
        )
    }

    private fun format(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
}

object ApplianceCatalog {
    val items = listOf(
        // Modelos con consumo anual declarado en páginas/manuales oficiales del fabricante.
        ApplianceCatalogItem(
            "model_lg_gb35bvb", "Refrigerador Bottom Freezer 12 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "LG", model = "GB35BVB", annualKwh = 329.0, sourceLabel = "Ficha oficial LG México",
            sourceUrl = "https://www.lg.com/mx/small-medium-business/refrigeradores/refrigeradores-bottom-freezer-congelador-abajo/gb35bvb/",
            marketMexicoVerified = true,
        ),
        ApplianceCatalogItem(
            "model_lg_gm39bt", "Refrigerador French Door 29 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "LG", model = "GM39BT", annualKwh = 534.0, sourceLabel = "Ficha oficial LG México",
            sourceUrl = "https://www.lg.com/mx/refrigeradores/refrigeradores-french-door/gm39bt/",
            marketMexicoVerified = true,
        ),
        ApplianceCatalogItem(
            "model_lg_gm65bpp", "Refrigerador French Door 25 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "LG", model = "GM65BPP", annualKwh = 535.0, sourceLabel = "Ficha oficial LG México",
            sourceUrl = "https://www.lg.com/mx/refrigeradores/refrigeradores-french-door/gm65bpp/",
            marketMexicoVerified = true,
        ),
        ApplianceCatalogItem(
            "model_lg_gm90fip", "Refrigerador Multi-Door 32 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "LG", model = "GM90FIP", annualKwh = 712.0, sourceLabel = "Ficha oficial LG México",
            sourceUrl = "https://www.lg.com/mx/refrigeradores/refrigeradores-french-door/gm90fip/",
            marketMexicoVerified = true,
        ),
        ApplianceCatalogItem(
            "model_samsung_rf22a4010s9", "Refrigerador French Door 22 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "Samsung", model = "RF22A4010S9/EM", annualKwh = 504.0, sourceLabel = "Ficha oficial Samsung México",
            sourceUrl = "https://www.samsung.com/mx/refrigerators/french-door/rf4500a-french-door-refrigerator-flat-door-design-silver-rf22a4010s9-em/",
            marketMexicoVerified = true,
        ),
        ApplianceCatalogItem(
            "model_mabe_rma0821xmxgc", "Refrigerador 8 pies³", ApplianceCategory.KITCHEN, 0, null,
            brand = "Mabe", model = "RMA0821XMXGC", annualKwh = 316.0, sourceLabel = "Manual oficial Mabe",
            sourceUrl = "https://servicio.mabeglobal.com/medias/RMA0821XMXGC-ManualUsuario-Refrigerador.pdf",
            marketMexicoVerified = true,
        ),

        // Potencias por modelo recuperadas del paquete y respaldadas por fichas oficiales.
        // Se calculan con las horas reales del usuario; no se presenta el perfil de uso del
        // fabricante como si fuera una medición de ese hogar.
        ApplianceCatalogItem(
            "model_sony_ps5_cfi2016", "PlayStation 5", ApplianceCategory.ENTERTAINMENT, 217, 14, standbyWatts = 4,
            brand = "Sony", model = "CFI-2016", sourceLabel = "Promedio oficial de juego activo (tres juegos)",
            sourceUrl = "https://www.playstation.com/en-dk/legal/ecodesign/",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_sony_ps5_cfi1216b", "PlayStation 5 Digital", ApplianceCategory.ENTERTAINMENT, 201, 14, standbyWatts = 4,
            brand = "Sony", model = "CFI-1216B", sourceLabel = "Promedio oficial de juego activo (tres juegos)",
            sourceUrl = "https://www.playstation.com/en-dk/legal/ecodesign/",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_sony_ps5_pro_cfi7021", "PlayStation 5 Pro", ApplianceCategory.ENTERTAINMENT, 216, 14, standbyWatts = 5,
            brand = "Sony", model = "CFI-7021", sourceLabel = "Promedio oficial de juego activo (tres juegos)",
            sourceUrl = "https://www.playstation.com/en-dk/legal/ecodesign/",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_nintendo_switch2", "Nintendo Switch 2", ApplianceCategory.ENTERTAINMENT, 19, 14,
            brand = "Nintendo", model = "Switch 2", sourceLabel = "Promedio oficial en juego y modo TV",
            sourceUrl = "https://www.nintendo.com/au/hardware/nintendo-switch-2/tech-specs/",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_xbox_series_x", "Xbox Series X", ApplianceCategory.ENTERTAINMENT, 150, 14, standbyWatts = 10,
            brand = "Microsoft", model = "Xbox Series X", sourceLabel = "Promedio oficial de juego activo en perfiles comunes",
            sourceUrl = "https://learn.microsoft.com/en-us/gaming/sustainability/lab-platform-baselines",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_xbox_series_s", "Xbox Series S", ApplianceCategory.ENTERTAINMENT, 71, 14, standbyWatts = 10,
            brand = "Microsoft", model = "Xbox Series S", sourceLabel = "Promedio oficial de juego activo en perfiles comunes",
            sourceUrl = "https://learn.microsoft.com/en-us/gaming/sustainability/lab-platform-baselines",
            dataSource = ApplianceDataSource.MANUFACTURER_LABEL, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_xbox_one_launch", "Xbox One", ApplianceCategory.ENTERTAINMENT, 112, 14, standbyWatts = 12,
            brand = "Microsoft", model = "Xbox One (2013)", sourceLabel = "Promedio activo de laboratorio NRDC/ENERGY STAR",
            sourceUrl = "https://www.tomsguide.com/us/xbox-one-ps4-power-consumption,news-18882.html",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),
        ApplianceCatalogItem(
            "model_xbox_360_jasper", "Xbox 360 Jasper", ApplianceCategory.ENTERTAINMENT, 102, 14, standbyWatts = 1,
            brand = "Microsoft", model = "Xbox 360 Jasper", sourceLabel = "Promedio activo medido con wattímetro",
            sourceUrl = "https://at-web1.www.anandtech.com/show/2682/5",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_xbox_360_xenon", "Xbox 360 original", ApplianceCategory.ENTERTAINMENT, 170, 14, standbyWatts = 1,
            brand = "Microsoft", model = "Xbox 360 Xenon", sourceLabel = "Promedio activo medido para la revisión original",
            sourceUrl = "https://at-web1.www.anandtech.com/show/2682/5",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_ps4_launch", "PlayStation 4", ApplianceCategory.ENTERTAINMENT, 140, 14, standbyWatts = 3,
            brand = "Sony", model = "CUH-1000/1100", sourceLabel = "Promedio de juego medido con monitor de energía",
            sourceUrl = "https://ecocostsavings.com/how-many-watts-does-a-ps4-use/",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),
        ApplianceCatalogItem(
            "model_ps4_slim", "PlayStation 4 Slim", ApplianceCategory.ENTERTAINMENT, 110, 14, standbyWatts = 2,
            brand = "Sony", model = "CUH-2000/2100/2200", sourceLabel = "Promedio de juego estimado y contrastado con Sony",
            sourceUrl = "https://www.playstation.com/en-us/ps4/tech-specs/",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),
        ApplianceCatalogItem(
            "model_ps4_pro", "PlayStation 4 Pro", ApplianceCategory.ENTERTAINMENT, 155, 14, standbyWatts = 4,
            brand = "Sony", model = "CUH-7000/7100/7200", sourceLabel = "Promedio de juego medido por Digital Foundry",
            sourceUrl = "https://nerdburglars.net/playstation-4-pro-power-consumption-measured/",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),
        ApplianceCatalogItem(
            "model_switch_hac001", "Nintendo Switch en dock", ApplianceCategory.ENTERTAINMENT, 11, 14,
            brand = "Nintendo", model = "HAC-001", sourceLabel = "Promedio en juego medido con wattímetro",
            sourceUrl = "https://www.neogaf.com/threads/anandtech-switch-power-consumption-analysis.1350361/",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.HIGH,
        ),
        ApplianceCatalogItem(
            "model_switch_oled", "Nintendo Switch OLED en dock", ApplianceCategory.ENTERTAINMENT, 14, 14,
            brand = "Nintendo", model = "HEG-001", sourceLabel = "Promedio estimado para juego en modo dock",
            sourceUrl = "https://powercostcalculator.com/articles/nintendo-switch-power-consumption",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),
        ApplianceCatalogItem(
            "model_steam_deck_oled", "Steam Deck OLED", ApplianceCategory.ENTERTAINMENT, 24, 14,
            brand = "Valve", model = "Steam Deck OLED", sourceLabel = "Promedio medido del sistema durante juego",
            sourceUrl = "https://www.notebookcheck.net",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.MEDIUM,
        ),

        // Catálogo genérico: potencia orientativa y perfiles editables basados en la base CFE entregada.
        ApplianceCatalogItem("refrigerador_10_14", "Refrigerador 10–14 pies³", ApplianceCategory.KITCHEN, 120, null, typicalKwhPer30Days = 30.0),
        ApplianceCatalogItem("refrigerador_15_22", "Refrigerador 15–22 pies³", ApplianceCategory.KITCHEN, 160, null, typicalKwhPer30Days = 40.0),
        ApplianceCatalogItem("congelador_horizontal", "Congelador horizontal", ApplianceCategory.KITCHEN, 140, null, typicalKwhPer30Days = 36.0),
        ApplianceCatalogItem("minisplit_inverter_1t", "Minisplit inverter 1 tonelada", ApplianceCategory.CLIMATE, 700, 35),
        ApplianceCatalogItem("minisplit_convencional_1t", "Minisplit convencional 1 tonelada", ApplianceCategory.CLIMATE, 1_200, 35),
        ApplianceCatalogItem("minisplit_inverter_15t", "Minisplit inverter 1.5 toneladas", ApplianceCategory.CLIMATE, 1_000, 35),
        ApplianceCatalogItem("aire_ventana_1t", "Aire de ventana 1 tonelada", ApplianceCategory.CLIMATE, 1_200, 35),
        ApplianceCatalogItem("aire_central_3t", "Aire acondicionado central 3 toneladas", ApplianceCategory.CLIMATE, 3_200, 35),
        ApplianceCatalogItem("ventilador_pedestal", "Ventilador de pedestal", ApplianceCategory.CLIMATE, 55, 42),
        ApplianceCatalogItem("ventilador_techo", "Ventilador de techo", ApplianceCategory.CLIMATE, 55, 42),
        ApplianceCatalogItem("calefactor_electrico", "Calefactor eléctrico", ApplianceCategory.CLIMATE, 1_500, 14),
        ApplianceCatalogItem("calefactor_ceramico", "Calefactor cerámico", ApplianceCategory.CLIMATE, 1_200, 14),
        ApplianceCatalogItem("purificador_aire", "Purificador de aire", ApplianceCategory.CLIMATE, 50, 84),
        ApplianceCatalogItem("humidificador", "Humidificador", ApplianceCategory.CLIMATE, 30, 42),
        ApplianceCatalogItem("deshumidificador", "Deshumidificador", ApplianceCategory.CLIMATE, 450, 42),
        ApplianceCatalogItem("enfriador_evaporativo", "Enfriador evaporativo", ApplianceCategory.CLIMATE, 300, 42),
        ApplianceCatalogItem("extractor_bano", "Extractor de baño", ApplianceCategory.CLIMATE, 30, 4),

        ApplianceCatalogItem("boiler_deposito", "Boiler eléctrico de depósito", ApplianceCategory.WATER, 3_000, 7),
        ApplianceCatalogItem("boiler_instantaneo", "Boiler eléctrico instantáneo", ApplianceCategory.WATER, 7_000, 3),
        ApplianceCatalogItem("bomba_agua_media_hp", "Bomba de agua 1/2 HP", ApplianceCategory.WATER, 450, 5),
        ApplianceCatalogItem("bomba_agua_1_hp", "Bomba de agua 1 HP", ApplianceCategory.WATER, 850, 5),
        ApplianceCatalogItem("bomba_piscina", "Bomba de piscina", ApplianceCategory.WATER, 1_000, 21),
        ApplianceCatalogItem("calentador_piscina", "Calentador eléctrico de piscina", ApplianceCategory.WATER, 9_000, 7),
        ApplianceCatalogItem("dispensador_agua", "Dispensador de agua frío/caliente", ApplianceCategory.WATER, 250, null, typicalKwhPer30Days = 25.0),
        ApplianceCatalogItem("acuario_bomba", "Bomba o filtro de acuario", ApplianceCategory.WATER, 12, null, typicalKwhPer30Days = 8.6),
        ApplianceCatalogItem("acuario_calentador", "Calentador de acuario", ApplianceCategory.WATER, 100, 42),

        ApplianceCatalogItem("lavadora_superior", "Lavadora de carga superior", ApplianceCategory.LAUNDRY, 450, 4),
        ApplianceCatalogItem("lavadora_frontal", "Lavadora frontal", ApplianceCategory.LAUNDRY, 400, 4),
        ApplianceCatalogItem("secadora_electrica", "Secadora eléctrica", ApplianceCategory.LAUNDRY, 3_500, 4),
        ApplianceCatalogItem("secadora_gas_motor", "Secadora de gas (motor eléctrico)", ApplianceCategory.LAUNDRY, 400, 4),
        ApplianceCatalogItem("plancha_ropa", "Plancha de ropa", ApplianceCategory.LAUNDRY, 1_300, 3),
        ApplianceCatalogItem("centro_planchado", "Centro de planchado", ApplianceCategory.LAUNDRY, 2_200, 3),
        ApplianceCatalogItem("maquina_coser", "Máquina de coser", ApplianceCategory.LAUNDRY, 90, 3),
        ApplianceCatalogItem("calentador_toallas", "Calentador de toallas", ApplianceCategory.LAUNDRY, 150, 7),

        ApplianceCatalogItem("lavavajillas", "Lavavajillas", ApplianceCategory.KITCHEN, 1_200, 5),
        ApplianceCatalogItem("horno_electrico", "Horno eléctrico", ApplianceCategory.KITCHEN, 2_500, 4),
        ApplianceCatalogItem("parrilla_induccion", "Parrilla de inducción (1 zona)", ApplianceCategory.KITCHEN, 1_800, 7),
        ApplianceCatalogItem("parrilla_resistencia", "Parrilla eléctrica de resistencia", ApplianceCategory.KITCHEN, 1_500, 7),
        ApplianceCatalogItem("microondas", "Horno de microondas", ApplianceCategory.KITCHEN, 1_200, 2, standbyWatts = 2),
        ApplianceCatalogItem("freidora_aire", "Freidora de aire", ApplianceCategory.KITCHEN, 1_500, 4),
        ApplianceCatalogItem("cafetera_goteo", "Cafetera de goteo", ApplianceCategory.KITCHEN, 900, 2),
        ApplianceCatalogItem("cafetera_espresso", "Cafetera espresso", ApplianceCategory.KITCHEN, 1_300, 2),
        ApplianceCatalogItem("olla_arrocera", "Olla arrocera", ApplianceCategory.KITCHEN, 700, 4),
        ApplianceCatalogItem("olla_lenta", "Olla de cocción lenta", ApplianceCategory.KITCHEN, 220, 7),
        ApplianceCatalogItem("olla_presion_electrica", "Olla de presión eléctrica", ApplianceCategory.KITCHEN, 1_000, 4),
        ApplianceCatalogItem("batidora_pedestal", "Batidora de pedestal", ApplianceCategory.KITCHEN, 350, 1),
        ApplianceCatalogItem("sandwichera", "Sandwichera", ApplianceCategory.KITCHEN, 900, 2),
        ApplianceCatalogItem("refrigerador_vinos", "Refrigerador para vinos", ApplianceCategory.KITCHEN, 90, null, typicalKwhPer30Days = 25.0),
        ApplianceCatalogItem("hervidor", "Hervidor eléctrico", ApplianceCategory.KITCHEN, 1_500, 1),
        ApplianceCatalogItem("tostador", "Tostador", ApplianceCategory.KITCHEN, 1_000, 1),
        ApplianceCatalogItem("licuadora", "Licuadora", ApplianceCategory.KITCHEN, 600, 1),
        ApplianceCatalogItem("procesador_alimentos", "Procesador de alimentos", ApplianceCategory.KITCHEN, 650, 1),
        ApplianceCatalogItem("extractor_cocina", "Extractor de cocina", ApplianceCategory.KITCHEN, 100, 7),

        ApplianceCatalogItem("tv_led_32", "Televisión LED 32 pulgadas", ApplianceCategory.ENTERTAINMENT, 45, 28, standbyWatts = 1),
        ApplianceCatalogItem("tv_led_43", "Televisión LED 43 pulgadas", ApplianceCategory.ENTERTAINMENT, 75, 28, standbyWatts = 1),
        ApplianceCatalogItem("television_led", "Televisión LED 55 pulgadas", ApplianceCategory.ENTERTAINMENT, 100, 28, standbyWatts = 1),
        ApplianceCatalogItem("tv_oled_55", "Televisión OLED 55 pulgadas", ApplianceCategory.ENTERTAINMENT, 120, 28, standbyWatts = 1),
        ApplianceCatalogItem("tv_75", "Televisión 75 pulgadas", ApplianceCategory.ENTERTAINMENT, 180, 28, standbyWatts = 1),
        ApplianceCatalogItem("barra_sonido", "Barra de sonido", ApplianceCategory.ENTERTAINMENT, 35, 14, standbyWatts = 1),
        ApplianceCatalogItem("equipo_sonido", "Equipo de sonido", ApplianceCategory.ENTERTAINMENT, 80, 14, standbyWatts = 2),
        ApplianceCatalogItem("decodificador", "Decodificador de TV", ApplianceCategory.ENTERTAINMENT, 15, 35, standbyWatts = 8),
        ApplianceCatalogItem(
            "consola_actual", "Consola de videojuegos actual", ApplianceCategory.ENTERTAINMENT, 150, 14,
            standbyWatts = 10, sourceLabel = "Promedio orientativo durante juego; no es la potencia máxima",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.LOW,
        ),
        ApplianceCatalogItem("consola_portatil", "Consola portátil cargando", ApplianceCategory.ENTERTAINMENT, 25, 7),
        ApplianceCatalogItem("smart_speaker", "Bocina inteligente", ApplianceCategory.ENTERTAINMENT, 4, null, typicalKwhPer30Days = 2.9),
        ApplianceCatalogItem("proyector", "Proyector", ApplianceCategory.ENTERTAINMENT, 300, 14, standbyWatts = 1),
        ApplianceCatalogItem("reproductor_bluray", "Reproductor Blu-ray", ApplianceCategory.ENTERTAINMENT, 20, 7, standbyWatts = 1),
        ApplianceCatalogItem("casco_vr_cargando", "Visor de realidad virtual cargando", ApplianceCategory.ENTERTAINMENT, 18, 7),

        ApplianceCatalogItem("router_wifi", "Router Wi‑Fi", ApplianceCategory.COMPUTING, 12, null, typicalKwhPer30Days = 8.6),
        ApplianceCatalogItem("ont_fibra", "ONT de fibra", ApplianceCategory.COMPUTING, 7, null, typicalKwhPer30Days = 5.0),
        ApplianceCatalogItem("pc_oficina", "PC de escritorio para oficina", ApplianceCategory.COMPUTING, 120, 35, standbyWatts = 3),
        ApplianceCatalogItem(
            "pc_gaming", "PC gaming de alta gama", ApplianceCategory.COMPUTING, 350, 21,
            standbyWatts = 4, sourceLabel = "Promedio orientativo del equipo completo durante juego; no es el pico de la GPU ni la fuente",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.LOW,
        ),
        ApplianceCatalogItem(
            "pc_gaming_entusiasta", "PC gaming entusiasta", ApplianceCategory.COMPUTING, 650, 21,
            standbyWatts = 5, sourceLabel = "Promedio orientativo del equipo completo bajo juego exigente; no es potencia máxima",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.LOW,
        ),
        ApplianceCatalogItem(
            "workstation_gpu", "Workstation para render o IA", ApplianceCategory.COMPUTING, 800, 35,
            standbyWatts = 6, sourceLabel = "Promedio orientativo mientras ejecuta carga sostenida; no es la suma de potencias máximas",
            dataSource = ApplianceDataSource.CATALOG, confidence = ConfidenceLevel.LOW,
        ),
        ApplianceCatalogItem("mini_pc", "Mini PC", ApplianceCategory.COMPUTING, 35, 35, standbyWatts = 2),
        ApplianceCatalogItem("servidor_torre", "Servidor torre 24/7", ApplianceCategory.COMPUTING, 250, null, typicalKwhPer30Days = 180.0),
        ApplianceCatalogItem("laptop_ultrabook", "Laptop ultrabook", ApplianceCategory.COMPUTING, 35, 35, standbyWatts = 1),
        ApplianceCatalogItem("laptop_gaming", "Laptop gaming", ApplianceCategory.COMPUTING, 120, 21, standbyWatts = 1),
        ApplianceCatalogItem("monitor_24", "Monitor 24 pulgadas", ApplianceCategory.COMPUTING, 25, 35, standbyWatts = 1),
        ApplianceCatalogItem("monitor_27", "Monitor 27 pulgadas 1440p", ApplianceCategory.COMPUTING, 35, 35, standbyWatts = 1),
        ApplianceCatalogItem("monitor_32", "Monitor 32 pulgadas 4K", ApplianceCategory.COMPUTING, 55, 35, standbyWatts = 1),
        ApplianceCatalogItem("impresora_inyeccion", "Impresora de inyección", ApplianceCategory.COMPUTING, 15, 2, standbyWatts = 1),
        ApplianceCatalogItem("impresora_laser", "Impresora láser", ApplianceCategory.COMPUTING, 350, 1, standbyWatts = 5),
        ApplianceCatalogItem("multifuncional_laser", "Multifuncional láser", ApplianceCategory.COMPUTING, 500, 2, standbyWatts = 8),
        ApplianceCatalogItem("nas_2_bahias", "Servidor NAS de 2 bahías", ApplianceCategory.COMPUTING, 25, null, typicalKwhPer30Days = 18.0),
        ApplianceCatalogItem("ups", "UPS / no-break", ApplianceCategory.COMPUTING, 20, null, typicalKwhPer30Days = 14.4),
        ApplianceCatalogItem("regulador", "Regulador de voltaje", ApplianceCategory.COMPUTING, 8, null, typicalKwhPer30Days = 5.8),
        ApplianceCatalogItem("switch_8", "Switch de red 8 puertos", ApplianceCategory.COMPUTING, 15, null, typicalKwhPer30Days = 10.8),
        ApplianceCatalogItem("switch_poe_24", "Switch PoE 24 puertos", ApplianceCategory.COMPUTING, 120, null, typicalKwhPer30Days = 86.4),
        ApplianceCatalogItem("nodo_mesh", "Nodo Wi‑Fi mesh", ApplianceCategory.COMPUTING, 10, null, typicalKwhPer30Days = 7.2),
        ApplianceCatalogItem("camara_seguridad", "Cámara de seguridad Wi‑Fi", ApplianceCategory.COMPUTING, 6, null, typicalKwhPer30Days = 4.3),
        ApplianceCatalogItem("nvr_seguridad", "Grabador NVR de seguridad", ApplianceCategory.COMPUTING, 35, null, typicalKwhPer30Days = 25.2),
        ApplianceCatalogItem("disco_externo", "Disco duro externo", ApplianceCategory.COMPUTING, 8, 35, standbyWatts = 1),

        ApplianceCatalogItem("foco_led_9", "Foco LED 9 W", ApplianceCategory.LIGHTING, 9, 28),
        ApplianceCatalogItem("foco_led_15", "Foco LED 15 W", ApplianceCategory.LIGHTING, 15, 28),
        ApplianceCatalogItem("foco_incandescente_60", "Foco incandescente 60 W", ApplianceCategory.LIGHTING, 60, 28),
        ApplianceCatalogItem("foco_halógeno_50", "Foco halógeno 50 W", ApplianceCategory.LIGHTING, 50, 28),
        ApplianceCatalogItem("tira_led_5m", "Tira LED de 5 m", ApplianceCategory.LIGHTING, 35, 28),
        ApplianceCatalogItem("foco_inteligente", "Foco LED inteligente", ApplianceCategory.LIGHTING, 10, 28, standbyWatts = 1),
        ApplianceCatalogItem("tubo_led", "Tubo LED", ApplianceCategory.LIGHTING, 18, 35),
        ApplianceCatalogItem("reflector_exterior", "Reflector LED exterior", ApplianceCategory.LIGHTING, 50, 14),
        ApplianceCatalogItem("luces_navidenas", "Serie de luces LED decorativas", ApplianceCategory.LIGHTING, 20, 42),

        ApplianceCatalogItem("cargador_telefono", "Cargador de teléfono", ApplianceCategory.PERSONAL_CARE, 12, 10),
        ApplianceCatalogItem("tablet_cargando", "Tablet cargando", ApplianceCategory.PERSONAL_CARE, 15, 10),
        ApplianceCatalogItem("aspiradora", "Aspiradora convencional", ApplianceCategory.PERSONAL_CARE, 1_200, 2),
        ApplianceCatalogItem("robot_aspirador", "Robot aspirador", ApplianceCategory.PERSONAL_CARE, 45, 7, standbyWatts = 3),
        ApplianceCatalogItem("secadora_cabello", "Secadora de cabello", ApplianceCategory.PERSONAL_CARE, 1_600, 1),
        ApplianceCatalogItem("plancha_cabello", "Rizadora o plancha de cabello", ApplianceCategory.PERSONAL_CARE, 60, 2),
        ApplianceCatalogItem("cobija_electrica", "Cobija eléctrica", ApplianceCategory.PERSONAL_CARE, 80, 21),
        ApplianceCatalogItem("despertador", "Despertador", ApplianceCategory.PERSONAL_CARE, 2, null, typicalKwhPer30Days = 1.4),
        ApplianceCatalogItem("cepillo_dental", "Cepillo dental eléctrico", ApplianceCategory.PERSONAL_CARE, 2, 1),
        ApplianceCatalogItem("rasuradora", "Rasuradora eléctrica", ApplianceCategory.PERSONAL_CARE, 10, 1),
        ApplianceCatalogItem("maquina_cpap", "Equipo CPAP", ApplianceCategory.PERSONAL_CARE, 50, 56),

        ApplianceCatalogItem("camara_wifi", "Cámara de seguridad Wi‑Fi", ApplianceCategory.OTHER, 5, null, typicalKwhPer30Days = 3.6),
        ApplianceCatalogItem("nvr_dvr", "NVR / DVR de videovigilancia", ApplianceCategory.OTHER, 30, null, typicalKwhPer30Days = 21.6),
        ApplianceCatalogItem("porton_electrico", "Portón eléctrico", ApplianceCategory.OTHER, 400, 1, standbyWatts = 4),
        ApplianceCatalogItem("refrigerador_comercial", "Refrigerador comercial", ApplianceCategory.OTHER, 500, null, typicalKwhPer30Days = 180.0),
        ApplianceCatalogItem("congelador_comercial", "Congelador comercial", ApplianceCategory.OTHER, 650, null, typicalKwhPer30Days = 234.0),
        ApplianceCatalogItem("vitrina_refrigerada", "Vitrina refrigerada", ApplianceCategory.OTHER, 750, null, typicalKwhPer30Days = 270.0),
        ApplianceCatalogItem("microondas_comercial", "Microondas comercial", ApplianceCategory.OTHER, 1_800, 7),
        ApplianceCatalogItem("maquina_hielo", "Máquina de hielo", ApplianceCategory.OTHER, 700, 21),
        ApplianceCatalogItem("compresor_aire", "Compresor de aire pequeño", ApplianceCategory.OTHER, 1_200, 7),
        ApplianceCatalogItem("maquina_expendedora", "Máquina expendedora", ApplianceCategory.OTHER, 600, null, typicalKwhPer30Days = 216.0),
        ApplianceCatalogItem("secador_manos", "Secador de manos", ApplianceCategory.OTHER, 1_200, 2),
        ApplianceCatalogItem("copiadora", "Copiadora de oficina", ApplianceCategory.OTHER, 500, 2, standbyWatts = 10),
        ApplianceCatalogItem("terminal_pos", "Terminal de punto de venta", ApplianceCategory.OTHER, 30, 84, standbyWatts = 2),
        ApplianceCatalogItem("servidor_rack", "Servidor rack pequeño", ApplianceCategory.OTHER, 350, null, typicalKwhPer30Days = 252.0),
    )

    fun find(id: String): ApplianceCatalogItem? = items.firstOrNull { it.id == id }

    fun toAppliance(
        item: ApplianceCatalogItem,
        instanceId: String,
        room: String,
        hoursPerWeek: Int? = item.defaultHoursPerWeek,
        billingDays: Int = 61,
        effectiveRateMxnPerKwh: Double = 2.3,
        quantity: Int = 1,
        spaceId: String? = null,
        brand: String? = item.brand,
        model: String? = item.model,
        periodKwhPerUnit: Double? = null,
        standbyEnabled: Boolean = false,
    ): Appliance {
        val safeQuantity = quantity.coerceIn(1, 999)
        val estimate = ApplianceCalculator.fromCatalog(
            item = item,
            hoursPerWeek = hoursPerWeek,
            billingDays = billingDays,
            effectiveRateMxnPerKwh = effectiveRateMxnPerKwh,
            quantity = safeQuantity,
            periodKwhPerUnit = periodKwhPerUnit,
            includeStandby = standbyEnabled,
        )
        val usesPeriodKwh = periodKwhPerUnit != null || item.annualKwh != null || item.typicalKwhPer30Days != null
        val exactUnitKwh = (estimate.activeKwh + estimate.standbyKwh) / safeQuantity
        return Appliance(
            id = instanceId,
            name = item.name,
            room = room.trim().ifEmpty { "Hogar" },
            costMxn = estimate.costMxn,
            kwh = estimate.kwh,
            sharePercent = 0,
            hoursPerWeek = if (usesPeriodKwh) null else hoursPerWeek,
            confidence = item.confidence
                ?: if (item.annualKwh != null) ConfidenceLevel.HIGH else ConfidenceLevel.MEDIUM,
            opportunity = OpportunityLevel.MEDIUM,
            activeCostMxn = if (estimate.standbyKwh > 0) {
                (estimate.activeKwh * effectiveRateMxnPerKwh).roundToInt()
            } else null,
            standbyCostMxn = if (estimate.standbyKwh > 0) {
                (estimate.standbyKwh * effectiveRateMxnPerKwh).roundToInt()
            } else null,
            standbySharePercent = if (estimate.kwh > 0 && estimate.standbyKwh > 0) {
                (estimate.standbyKwh * 100 / estimate.kwh).roundToInt().coerceIn(0, 100)
            } else null,
            calculationNote = estimate.calculation,
            category = item.category,
            powerWatts = item.typicalWatts,
            standbyWatts = item.standbyWatts.takeIf { it > 0 },
            standbyEnabled = standbyEnabled && item.standbyWatts > 0,
            dataSource = item.dataSource
                ?: if (item.annualKwh != null) ApplianceDataSource.MANUFACTURER_LABEL else ApplianceDataSource.CATALOG,
            quantity = safeQuantity,
            spaceId = spaceId,
            brand = brand?.trim()?.takeIf { it.isNotEmpty() },
            model = model?.trim()?.takeIf { it.isNotEmpty() },
            usageModel = if (usesPeriodKwh) ApplianceUsageModel.PERIOD_KWH else ApplianceUsageModel.POWER_SCHEDULE,
            unitKwh = exactUnitKwh,
            referencePeriodDays = billingDays.coerceAtLeast(1),
            referenceAnnualKwh = item.annualKwh,
            referenceAnnualKwhMin = item.annualKwhMin ?: item.annualKwh,
            referenceAnnualKwhMax = item.annualKwhMax ?: item.annualKwh,
            marketMexicoVerified = item.marketMexicoVerified,
            isModelFamily = item.isModelFamily,
            sourceLabel = item.sourceLabel,
            sourceUrl = item.sourceUrl,
        )
    }
}
