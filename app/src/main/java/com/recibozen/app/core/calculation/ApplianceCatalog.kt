package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceDataSource
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
)

data class ApplianceUsageEstimate(
    val kwh: Int,
    val costMxn: Int,
    val calculation: String,
)

object ApplianceCalculator {
    fun fromPower(
        watts: Int,
        hoursPerDay: Double,
        daysPerWeek: Int,
        billingDays: Int,
        effectiveRateMxnPerKwh: Double,
    ): ApplianceUsageEstimate {
        val safeWatts = watts.coerceIn(1, 100_000)
        val safeHours = hoursPerDay.coerceIn(0.0, 24.0)
        val safeDays = daysPerWeek.coerceIn(0, 7)
        val safeBillingDays = billingDays.coerceIn(1, 366)
        val kwhExact = safeWatts / 1_000.0 * safeHours * safeDays / 7.0 * safeBillingDays
        val kwh = kwhExact.roundToInt().coerceAtLeast(0)
        return ApplianceUsageEstimate(
            kwh = kwh,
            costMxn = (kwhExact * effectiveRateMxnPerKwh.coerceAtLeast(0.0)).roundToInt(),
            calculation = "$safeWatts W × ${format(safeHours)} h/día × $safeDays días/semana × $safeBillingDays días ÷ 7 ÷ 1,000",
        )
    }

    fun fromCatalog(
        item: ApplianceCatalogItem,
        hoursPerWeek: Int? = item.defaultHoursPerWeek,
        billingDays: Int,
        effectiveRateMxnPerKwh: Double,
    ): ApplianceUsageEstimate {
        val exactKwh = item.typicalKwhPer30Days?.let { it * billingDays.coerceAtLeast(1) / 30.0 }
            ?: (item.typicalWatts / 1_000.0 * (hoursPerWeek ?: 0) * billingDays.coerceAtLeast(1) / 7.0)
        val kwh = exactKwh.roundToInt().coerceAtLeast(0)
        val calculation = if (item.typicalKwhPer30Days != null) {
            "${format(item.typicalKwhPer30Days)} kWh/30 días × $billingDays días"
        } else {
            "${item.typicalWatts} W × ${hoursPerWeek ?: 0} h/semana × $billingDays días ÷ 7 ÷ 1,000"
        }
        return ApplianceUsageEstimate(kwh, (exactKwh * effectiveRateMxnPerKwh).roundToInt(), calculation)
    }

    private fun format(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
}

object ApplianceCatalog {
    val items = listOf(
        ApplianceCatalogItem("minisplit_inverter", "Minisplit inverter", ApplianceCategory.CLIMATE, 1_150, 25),
        ApplianceCatalogItem("minisplit_conventional", "Minisplit convencional", ApplianceCategory.CLIMATE, 1_500, 25),
        ApplianceCatalogItem("ventilador_techo", "Ventilador de techo", ApplianceCategory.CLIMATE, 65, 42),
        ApplianceCatalogItem("ventilador_pedestal", "Ventilador de pedestal", ApplianceCategory.CLIMATE, 55, 35),
        ApplianceCatalogItem("calefactor", "Calefactor eléctrico", ApplianceCategory.CLIMATE, 1_500, 14),
        ApplianceCatalogItem("refrigerador", "Refrigerador", ApplianceCategory.KITCHEN, 180, null, typicalKwhPer30Days = 35.0),
        ApplianceCatalogItem("segundo_refrigerador", "Segundo refrigerador", ApplianceCategory.KITCHEN, 220, null, typicalKwhPer30Days = 45.0),
        ApplianceCatalogItem("congelador", "Congelador", ApplianceCategory.KITCHEN, 200, null, typicalKwhPer30Days = 42.0),
        ApplianceCatalogItem("microondas", "Horno de microondas", ApplianceCategory.KITCHEN, 1_200, 2),
        ApplianceCatalogItem("horno_electrico", "Horno eléctrico", ApplianceCategory.KITCHEN, 1_500, 4),
        ApplianceCatalogItem("parrilla_electrica", "Parrilla eléctrica", ApplianceCategory.KITCHEN, 1_800, 7),
        ApplianceCatalogItem("cafetera", "Cafetera", ApplianceCategory.KITCHEN, 900, 2),
        ApplianceCatalogItem("licuadora", "Licuadora", ApplianceCategory.KITCHEN, 500, 1),
        ApplianceCatalogItem("freidora_aire", "Freidora de aire", ApplianceCategory.KITCHEN, 1_500, 4),
        ApplianceCatalogItem("lavadora", "Lavadora", ApplianceCategory.LAUNDRY, 500, 4),
        ApplianceCatalogItem("secadora_electrica", "Secadora eléctrica", ApplianceCategory.LAUNDRY, 4_500, 4),
        ApplianceCatalogItem("plancha", "Plancha", ApplianceCategory.LAUNDRY, 1_200, 3),
        ApplianceCatalogItem("television_led", "Televisión LED", ApplianceCategory.ENTERTAINMENT, 100, 28, standbyWatts = 1),
        ApplianceCatalogItem("consola_videojuegos", "Consola de videojuegos", ApplianceCategory.ENTERTAINMENT, 200, 14, standbyWatts = 2),
        ApplianceCatalogItem("barra_sonido", "Barra de sonido", ApplianceCategory.ENTERTAINMENT, 40, 14, standbyWatts = 1),
        ApplianceCatalogItem("decodificador", "Decodificador de TV", ApplianceCategory.ENTERTAINMENT, 18, 35, standbyWatts = 12),
        ApplianceCatalogItem("computadora_escritorio", "Computadora de escritorio", ApplianceCategory.COMPUTING, 250, 28, standbyWatts = 3),
        ApplianceCatalogItem("laptop", "Laptop", ApplianceCategory.COMPUTING, 65, 35, standbyWatts = 1),
        ApplianceCatalogItem("monitor", "Monitor", ApplianceCategory.COMPUTING, 30, 28, standbyWatts = 1),
        ApplianceCatalogItem("modem_router", "Módem / router", ApplianceCategory.COMPUTING, 12, null, typicalKwhPer30Days = 8.6),
        ApplianceCatalogItem("foco_led", "Foco LED", ApplianceCategory.LIGHTING, 10, 28),
        ApplianceCatalogItem("bomba_agua", "Bomba de agua", ApplianceCategory.WATER, 750, 5),
        ApplianceCatalogItem("boiler_electrico", "Boiler eléctrico", ApplianceCategory.WATER, 4_000, 7),
        ApplianceCatalogItem("dispensador_agua", "Dispensador de agua", ApplianceCategory.WATER, 500, null, typicalKwhPer30Days = 25.0),
        ApplianceCatalogItem("aspiradora", "Aspiradora", ApplianceCategory.PERSONAL_CARE, 1_200, 2),
        ApplianceCatalogItem("secadora_cabello", "Secadora de cabello", ApplianceCategory.PERSONAL_CARE, 1_500, 1),
        ApplianceCatalogItem("bomba_piscina", "Bomba de piscina", ApplianceCategory.WATER, 1_100, 21),
    )

    fun find(id: String): ApplianceCatalogItem? = items.firstOrNull { it.id == id }

    fun toAppliance(
        item: ApplianceCatalogItem,
        instanceId: String,
        room: String,
        hoursPerWeek: Int? = item.defaultHoursPerWeek,
        billingDays: Int = 61,
        effectiveRateMxnPerKwh: Double = 2.3,
    ): Appliance {
        val estimate = ApplianceCalculator.fromCatalog(item, hoursPerWeek, billingDays, effectiveRateMxnPerKwh)
        return Appliance(
            id = instanceId,
            name = item.name,
            room = room.trim().ifEmpty { "Hogar" },
            costMxn = estimate.costMxn,
            kwh = estimate.kwh,
            sharePercent = 0,
            hoursPerWeek = hoursPerWeek,
            confidence = ConfidenceLevel.MEDIUM,
            opportunity = OpportunityLevel.MEDIUM,
            calculationNote = estimate.calculation,
            category = item.category,
            powerWatts = item.typicalWatts,
            standbyWatts = item.standbyWatts.takeIf { it > 0 },
            dataSource = ApplianceDataSource.CATALOG,
        )
    }
}
