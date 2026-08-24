package app.recibozen.android.core.ai

import app.recibozen.android.core.calculation.DacRiskLevel
import app.recibozen.android.core.model.Appliance

/**
 * Turns the household's own inventory into the request the savings intent takes.
 *
 * Everything numeric here is already computed by the app: kWh per appliance,
 * kWh projected for the period, the DAC risk level. The server sees no pesos and
 * no tariff — it answers in percentages of one appliance's consumption and
 * TariffEngine turns those back into money. A model doing arithmetic on CFE tiers
 * gets it wrong and sounds convincing.
 */
private const val MAX_APPLIANCES_SENT = 12
private const val MIN_KWH_WORTH_ADVICE = 1.0

data class AiSavingsRequest(
    val appliances: List<AiApplianceInput>,
    val periodDays: Int,
    val kwhProjected: Double,
    val dacRisk: AiDacRisk,
    /** The appliances actually sent, in the order the server will index them. */
    val sent: List<Appliance>,
) {
    val isUsable: Boolean get() = appliances.isNotEmpty() && periodDays > 0 && kwhProjected > 0.0
}

/**
 * Picks what to ask about.
 *
 * The heaviest twelve, because the intent is capped there and because advice on
 * the thirteenth appliance is advice nobody acts on. Anything under a kWh for the
 * whole period is dropped: a phone charger cannot be optimised into mattering,
 * and spending a slot on it costs a real recommendation.
 */
fun aiSavingsRequest(
    appliances: List<Appliance>,
    periodDays: Int,
    kwhProjected: Int,
    dacRisk: DacRiskLevel,
): AiSavingsRequest {
    val worthwhile = appliances
        .filter { it.kwh >= MIN_KWH_WORTH_ADVICE }
        .sortedByDescending { it.kwh }
        .take(MAX_APPLIANCES_SENT)

    return AiSavingsRequest(
        appliances = worthwhile.map { appliance ->
            AiApplianceInput(
                // The quantity travels in the name because the kWh figure covers
                // the whole row: twenty bulbs are one entry, and advice about
                // "the bulb" would understate what changing them is worth.
                name = if (appliance.quantity > 1) {
                    "${appliance.name} (x${appliance.quantity})"
                } else {
                    appliance.name
                },
                category = appliance.category,
                kwhPeriod = appliance.kwh.toDouble(),
                brand = appliance.brand?.takeIf { it.isNotBlank() },
                model = appliance.model?.takeIf { it.isNotBlank() },
            )
        },
        periodDays = periodDays,
        kwhProjected = kwhProjected.toDouble(),
        dacRisk = dacRisk.toAiDacRisk(),
        sent = worthwhile,
    )
}

/**
 * Maps the app's seven-level risk onto the four the server accepts.
 *
 * UNKNOWN and UNSUPPORTED both become NONE rather than something alarming: the
 * app reports UNKNOWN until roughly a year of bills is imported, and telling the
 * model a household is at risk when nobody knows would produce urgent advice on
 * no evidence.
 */
private fun DacRiskLevel.toAiDacRisk(): AiDacRisk = when (this) {
    DacRiskLevel.WATCH -> AiDacRisk.APPROACHING
    DacRiskLevel.RISK, DacRiskLevel.ABOVE_LIMIT -> AiDacRisk.AT_RISK
    DacRiskLevel.ALREADY_DAC -> AiDacRisk.IN_DAC
    DacRiskLevel.NORMAL, DacRiskLevel.UNKNOWN, DacRiskLevel.UNSUPPORTED -> AiDacRisk.NONE
}
