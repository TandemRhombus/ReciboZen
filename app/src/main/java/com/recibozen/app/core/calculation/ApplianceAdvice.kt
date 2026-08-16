package com.recibozen.app.core.calculation

import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import java.util.Locale
import kotlin.math.roundToInt

enum class ApplianceAdvicePriority { HIGH, MEDIUM, LOW }

enum class ApplianceAdviceEvidence { MODEL, BRAND, DEVICE_DATA, CATEGORY }

data class ApplianceAdvice(
    val id: String,
    val title: String,
    val description: String,
    val priority: ApplianceAdvicePriority,
    val evidence: ApplianceAdviceEvidence,
    val estimatedSavingsKwh: Double? = null,
    val estimatedSavingsMxn: Int? = null,
    val sourceLabel: String? = null,
    val sourceUrl: String? = null,
)

data class InventoryApplianceAdvice(
    val applianceId: String,
    val applianceName: String,
    val advice: ApplianceAdvice,
)

/**
 * Produces contextual savings actions without pretending that a generic tip is
 * documentation for a specific model. Exact menu routes are emitted only for
 * devices covered by an official manufacturer guide.
 */
object ApplianceAdviceEngine {
    fun recommendations(
        appliance: Appliance,
        billingDays: Int = appliance.referencePeriodDays,
        effectiveRateMxnPerKwh: Double = appliance.effectiveRate(),
    ): List<ApplianceAdvice> {
        val safeDays = billingDays.coerceIn(1, 366)
        val result = buildList {
            modelOrBrandAdvice(appliance)?.let(::add)
            standbyAdvice(appliance, safeDays, effectiveRateMxnPerKwh)?.let(::add)
            add(categoryAdvice(appliance))
        }
        return result.distinctBy { it.id }.take(3)
    }

    fun rankInventory(
        appliances: List<Appliance>,
        billingDays: Int,
        limit: Int = 3,
    ): List<InventoryApplianceAdvice> = appliances
        .mapNotNull { appliance ->
            recommendations(appliance, billingDays).firstOrNull()?.let { advice ->
                InventoryApplianceAdvice(appliance.id, appliance.name, advice)
            }
        }
        .sortedWith(
            compareBy<InventoryApplianceAdvice> { it.advice.priority.ordinal }
                .thenByDescending { it.advice.estimatedSavingsKwh ?: 0.0 }
                .thenByDescending { advice -> appliances.firstOrNull { it.id == advice.applianceId }?.kwh ?: 0 },
        )
        .take(limit.coerceAtLeast(0))

    private fun modelOrBrandAdvice(appliance: Appliance): ApplianceAdvice? {
        val text = appliance.searchableText()
        val brand = appliance.brand.orEmpty().lowercase(Locale.ROOT)

        return when {
            text.contains("ps5") || text.contains("playstation 5") || text.contains("cfi-") -> ApplianceAdvice(
                id = "ps5_power_saver",
                title = "Activa el ahorro de energía de tu PS5",
                description = "Ve a Configuración → Sistema → Ahorro de energía. Activa Ahorro de energía para juegos y deja en reposo sólo las funciones que sí necesitas, como carga USB o conexión a Internet.",
                priority = ApplianceAdvicePriority.HIGH,
                evidence = ApplianceAdviceEvidence.MODEL,
                sourceLabel = "Guía oficial de PlayStation",
                sourceUrl = "https://www.playstation.com/es-mx/support/hardware/power-saving/",
            )

            text.contains("switch 2") -> ApplianceAdvice(
                id = "switch2_auto_sleep",
                title = "Usa Auto-Sleep y revisa la red durante reposo",
                description = "Activa Sleep/Auto-Sleep. Si no necesitas mantener la conexión LAN cableada mientras duerme, desactívala: Nintendo reporta cerca de 0.5 W en reposo normal y alrededor de 5 W con LAN activa.",
                priority = ApplianceAdvicePriority.HIGH,
                evidence = ApplianceAdviceEvidence.MODEL,
                sourceLabel = "Especificaciones oficiales de Nintendo Switch 2",
                sourceUrl = "https://www.nintendo.com/au/hardware/nintendo-switch-2/tech-specs/",
            )

            text.contains("xbox") -> ApplianceAdvice(
                id = "xbox_shutdown_energy_saving",
                title = "Cambia Xbox a apagado con ahorro de energía",
                description = "En Perfil y sistema → Configuración → General → Opciones de energía, elige Apagado (ahorro de energía) si no necesitas inicio remoto ni actualizaciones inmediatas.",
                priority = ApplianceAdvicePriority.HIGH,
                evidence = ApplianceAdviceEvidence.MODEL,
                sourceLabel = "Soporte oficial de Xbox",
                sourceUrl = "https://support.xbox.com/es-MX/help/hardware-network/settings-updates/system-updates-2023",
            )

            brand.contains("samsung") && appliance.isTelevision() -> ApplianceAdvice(
                id = "samsung_tv_energy_saving",
                title = "Configura Energía y ahorro en tu TV Samsung",
                description = "En televisores compatibles, busca Ajustes → Todas las configuraciones → General y Privacidad → Energía y Ahorro de Energía. La ruta puede variar por año y firmware; confirma que coincida con tu modelo.",
                priority = ApplianceAdvicePriority.MEDIUM,
                evidence = ApplianceAdviceEvidence.BRAND,
                sourceLabel = "Guía oficial de Samsung México",
                sourceUrl = "https://www.samsung.com/mx/support/tv-audio-video/how-to-use-the-energy-saving-mode-on-the-samsung-smart-tv/",
            )

            brand == "lg" && appliance.category == ApplianceCategory.CLIMATE -> ApplianceAdvice(
                id = "lg_ac_energy_saving",
                title = "Busca Energy Saving en el control LG",
                description = "Si tu modelo y control son compatibles, pulsa Establecer función / Set Function y selecciona el modo de ahorro. Algunos Dual Inverter también ofrecen Active Energy Control; no todos usan la misma ruta.",
                priority = ApplianceAdvicePriority.HIGH,
                evidence = ApplianceAdviceEvidence.BRAND,
                sourceLabel = "Soporte oficial de LG México",
                sourceUrl = "https://www.lg.com/mx/soporte/soporte-de-producto/solucion-de-problemas/help-library/cs-CT30015000-20153402519544/",
            )

            else -> null
        }
    }

    private fun standbyAdvice(
        appliance: Appliance,
        billingDays: Int,
        effectiveRateMxnPerKwh: Double,
    ): ApplianceAdvice? {
        if (!appliance.standbyEnabled) return null
        val standbyWatts = appliance.standbyWatts?.takeIf { it >= 2 } ?: return null
        val activeHours = appliance.hoursPerWeek ?: return null
        if (appliance.isContinuousOrEssential()) return null

        val inactiveHoursPerWeek = (168 - activeHours).coerceAtLeast(0)
        if (inactiveHoursPerWeek < 18) return null
        val avoidableKwh = standbyWatts / 1_000.0 * inactiveHoursPerWeek * billingDays / 7.0 *
            appliance.quantity.coerceIn(1, 999) * 0.8
        if (avoidableKwh < 0.25) return null
        val roundedKwh = oneDecimal(avoidableKwh)
        val cost = effectiveRateMxnPerKwh
            .takeIf { it > 0.0 }
            ?.let { (avoidableKwh * it).roundToInt().coerceAtLeast(1) }
        val highStandby = standbyWatts >= 5
        val impact = buildString {
            append("Con los datos capturados, podrías evitar cerca de $roundedKwh kWh en $billingDays días")
            if (cost != null) append(" (aprox. $$cost)")
            append(".")
        }
        return ApplianceAdvice(
            id = "standby_${if (highStandby) "high" else "reduce"}",
            title = if (highStandby) "Su consumo en reposo merece atención" else "Reduce su consumo fantasma",
            description = "Registraste $standbyWatts W en reposo por unidad. Usa apagado completo, temporizador o una regleta cuando no necesites encendido remoto ni actualizaciones. $impact",
            priority = if (highStandby || avoidableKwh >= 5.0) ApplianceAdvicePriority.HIGH else ApplianceAdvicePriority.MEDIUM,
            evidence = ApplianceAdviceEvidence.DEVICE_DATA,
            estimatedSavingsKwh = avoidableKwh,
            estimatedSavingsMxn = cost,
        )
    }

    private fun categoryAdvice(appliance: Appliance): ApplianceAdvice {
        val text = appliance.searchableText()
        val (id, title, description) = when {
            appliance.isRefrigeration() -> Triple(
                "refrigeration_maintenance",
                "Ahorra sin desconectar la refrigeración",
                "No la desconectes para ahorrar. Revisa empaques, ventilación y limpieza del condensador; usa Eco o Vacaciones sólo si el manual de tu modelo lo permite y no hay alimentos que requieran frío.",
            )
            appliance.category == ApplianceCategory.CLIMATE -> Triple(
                "climate_setpoint",
                "Usa Eco/Sleep y evita cambios bruscos",
                "Si el equipo lo permite, usa Eco o Sleep, mantén puertas y ventanas cerradas y limpia filtros. En equipos inverter evita encendidos y apagados cortos repetidos; un ajuste estable suele ser más eficiente.",
            )
            appliance.category == ApplianceCategory.LAUNDRY -> Triple(
                "laundry_full_load",
                "Consolida cargas y usa el ciclo Eco",
                "Lava cargas completas sin sobrellenar, usa agua fría cuando la prenda lo permita y activa Eco. En secadora, limpia el filtro y evita prolongar el ciclo después de que la ropa esté seca.",
            )
            appliance.category == ApplianceCategory.ENTERTAINMENT -> Triple(
                "entertainment_auto_off",
                "Activa apagado automático y modo ahorro",
                "Reduce brillo cuando sea cómodo, configura apagado por inactividad y desactiva inicio rápido o red en reposo si no utilizas esas funciones. Confirma la ruta en el manual de tu modelo.",
            )
            appliance.category == ApplianceCategory.COMPUTING && appliance.isContinuousOrEssential() -> Triple(
                "computing_continuous_safe",
                "Optimiza la carga continua sin perder servicio",
                "No la desconectes si sostiene Internet, almacenamiento o respaldo. Programa ahorro en periodos sin actividad, desactiva puertos o radios que no uses y revisa si realmente necesita funcionar las 24 horas.",
            )
            appliance.category == ApplianceCategory.COMPUTING -> Triple(
                "computing_sleep",
                "Configura suspensión automática",
                "Activa suspensión y apagado de pantalla después de pocos minutos sin uso. En equipos de escritorio, evita dejar aplicaciones pesadas o periféricos activos cuando no los necesitas.",
            )
            appliance.category == ApplianceCategory.LIGHTING -> Triple(
                "lighting_controls",
                "Apaga por zonas y automatiza horarios",
                "Usa sólo las luminarias necesarias, agrupa focos por espacio y considera temporizadores o sensores. Si aún son incandescentes o halógenos, sustituirlos por LED suele ser la mayor oportunidad.",
            )
            appliance.category == ApplianceCategory.WATER -> Triple(
                "water_schedule",
                "Evita calentar o bombear de más",
                "Corrige fugas, programa bombas y ajusta el termostato del calentador a una temperatura segura. No cortes bombas de acuario ni equipos críticos sin evaluar primero la necesidad del sistema.",
            )
            appliance.isContinuousOrEssential() -> Triple(
                "continuous_safe",
                "Optimiza sin interrumpir una función esencial",
                "No lo desconectes a ciegas. Revisa horarios, mantenimiento y funciones que puedan apagarse de forma segura sin afectar seguridad, conservación de alimentos, red o salud.",
            )
            appliance.category == ApplianceCategory.PERSONAL_CARE -> Triple(
                "personal_unplug",
                "Desconecta cargadores y resistencias al terminar",
                "Apaga y desconecta el equipo cuando termines, sobre todo si genera calor. Usa temporizador en cobijas o calentadores y evita mantener baterías cargando más tiempo del necesario.",
            )
            appliance.category == ApplianceCategory.KITCHEN && text.contains("lavavajillas") -> Triple(
                "dishwasher_eco",
                "Usa carga completa y ciclo Eco",
                "Evita ciclos con poca carga, usa Eco cuando el tiempo lo permita y omite el secado caliente si tu vajilla puede secarse al aire.",
            )
            appliance.category == ApplianceCategory.KITCHEN -> Triple(
                "kitchen_right_size",
                "Usa el equipo adecuado y evita esperas encendidas",
                "Elige el aparato del tamaño justo para la tarea, aprovecha el calor residual y apágalo al terminar. Activa Eco o apagado de display sólo cuando el manual de tu modelo lo confirme.",
            )
            else -> Triple(
                "general_schedule",
                "Programa su uso y elimina tiempos ociosos",
                "Concentra el uso en menos sesiones, activa temporizador o apagado automático y compara los watts de la etiqueta con una medición real antes de reemplazar el equipo.",
            )
        }
        return ApplianceAdvice(
            id = id,
            title = title,
            description = description,
            priority = if (appliance.kwh >= 100) ApplianceAdvicePriority.MEDIUM else ApplianceAdvicePriority.LOW,
            evidence = ApplianceAdviceEvidence.CATEGORY,
        )
    }

    private fun Appliance.searchableText(): String = listOf(id, name, brand, model)
        .joinToString(" ")
        .lowercase(Locale.ROOT)

    private fun Appliance.isTelevision(): Boolean {
        val text = searchableText()
        return text.contains("televisi") || Regex("(^|\\s)tv($|\\s)").containsMatchIn(text)
    }

    private fun Appliance.isRefrigeration(): Boolean {
        val text = searchableText()
        return text.contains("refriger") || text.contains("congelador") || text.contains("vitrina refrigerada")
    }

    private fun Appliance.isContinuousOrEssential(): Boolean {
        if (isEssential || isRefrigeration()) return true
        val text = searchableText()
        return listOf(
            "router", "wi-fi", "wifi", "ont", "nvr", "dvr", "cámara de seguridad", "camara de seguridad",
            "servidor", "nas", "ups", "no-break", "acuario", "alarma", "equipo médico", "equipo medico", "cpap",
        ).any(text::contains)
    }

    private fun Appliance.effectiveRate(): Double = if (kwh > 0 && costMxn > 0) costMxn.toDouble() / kwh else 0.0

    private fun oneDecimal(value: Double): String = "%.1f".format(Locale.US, value)
}
