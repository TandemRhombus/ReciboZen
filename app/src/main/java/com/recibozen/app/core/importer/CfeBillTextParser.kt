package com.recibozen.app.core.importer

import com.recibozen.app.core.calculation.TariffCatalog
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale
import com.recibozen.app.core.model.EntitlementTier

data class ParsedBillCharge(
    val type: String,
    val season: String,
    val quantityKwh: Double?,
    val unitRateMxn: Double?,
    val amountMxn: Double?,
    val documentLabel: String,
)

data class ParsedBillHistoryRow(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val consumptionKwh: Int,
    val amountMxn: Double,
    val paidMxn: Double? = null,
)

data class ParsedCfeBill(
    val serviceNumber: String,
    val rmu: String?,
    val meterNumber: String,
    val multiplier: Int,
    val wireCount: Int?,
    val tariffCode: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val currentReadingKwh: Int,
    val previousReadingKwh: Int,
    val consumptionKwh: Int,
    val totalToPayMxn: Double,
    val charges: List<ParsedBillCharge>,
    val history: List<ParsedBillHistoryRow>,
    val confidence: Double,
    val warnings: List<String>,
)

sealed interface BillParseResult {
    data class Success(val bill: ParsedCfeBill) : BillParseResult
    data class Failure(val reasons: List<String>) : BillParseResult
}

data class BillImportResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val serviceId: String? = null,
    val requiredTier: EntitlementTier? = null,
    val message: String? = null,
)

/** Parser for CFE text layers and OCR output. It never extracts customer name or address. */
class CfeBillTextParser {
    fun parse(rawText: String): BillParseResult {
        val text = fold(rawText)
        val reasons = mutableListOf<String>()
        val service = capture(text, "NO\\.?\\s*DE\\s*SERVICIO\\s*:?\\s*(\\d{12})")
            ?: missing(reasons, "Número de servicio")
        val tariff = capture(text, "TARIFA\\s*:?\\s*([A-Z0-9]+)\\s*NO\\.?\\s*MEDIDOR")
            ?.let(TariffCatalog::normalize)
            ?: missing(reasons, "Tarifa")
        val meter = capture(text, "NO\\.?\\s*MEDIDOR\\s*:?\\s*([A-Z0-9-]+)")
            ?: missing(reasons, "Número de medidor")
        val multiplier = capture(text, "MULTIPLICADOR\\s*:?\\s*(\\d+)")?.toIntOrNull()
            ?: missing(reasons, "Multiplicador")
        val wireCount = capture(text, "NO\\s*HILOS\\s*:?\\s*(\\d+)")?.toIntOrNull()
        val periodMatch = Regex("PERIODO\\s*FACTURADO\\s*:?\\s*(\\d{2}\\s+[A-Z]{3}\\s+\\d{2})\\s*-\\s*(\\d{2}\\s+[A-Z]{3}\\s+\\d{2})")
            .find(text)
        val periodStart = periodMatch?.groupValues?.get(1)?.let(::parseDate)
            ?: missing(reasons, "Inicio del periodo")
        val periodEnd = periodMatch?.groupValues?.get(2)?.let(::parseDate)
            ?: missing(reasons, "Fin del periodo")
        val energy = findReadingTriple(text, multiplier ?: 1)
        val current = energy?.first
            ?: missing(reasons, "Lectura actual")
        val previous = energy?.second
            ?: missing(reasons, "Lectura anterior")
        val consumption = energy?.third
            ?: missing(reasons, "Consumo del periodo")
        val total = capture(text, "TOTAL\\s+A\\s+PAGAR\\s*:?\\s*\\$\\s*([0-9,]+(?:\\.\\d{1,2})?)")
            ?.toMoney()
            ?: missing(reasons, "Total a pagar")

        if (reasons.isNotEmpty()) return BillParseResult.Failure(reasons)

        service as String
        tariff as String
        meter as String
        multiplier as Int
        periodStart as LocalDate
        periodEnd as LocalDate
        current as Int
        previous as Int
        consumption as Int
        total as Double

        val warnings = mutableListOf<String>()
        if ((current - previous) * multiplier != consumption) {
            warnings += "Las lecturas y el multiplicador no reproducen el consumo del periodo."
        }
        if (TariffCatalog.find(tariff) == null) warnings += "La tarifa $tariff todavía no está soportada."
        val history = parseHistory(text)
        if (history.isEmpty()) warnings += "No se encontró historial anterior."
        else if (history.size < 11) warnings += "Solo se recuperaron ${history.size} de 11 periodos históricos esperados."
        val charges = parseCharges(text, tariff)
        if (charges.isEmpty()) warnings += "No se pudo reconstruir el desglose tarifario."

        return BillParseResult.Success(
            ParsedCfeBill(
                serviceNumber = service,
                rmu = capture(text, "RMU\\s*:?\\s*([^\\r\\n]+?)\\s+CUENTA\\s*:")?.replace(Regex("[^A-Z0-9]"), ""),
                meterNumber = meter,
                multiplier = multiplier,
                wireCount = wireCount,
                tariffCode = tariff,
                periodStart = periodStart,
                periodEnd = periodEnd,
                currentReadingKwh = current,
                previousReadingKwh = previous,
                consumptionKwh = consumption,
                totalToPayMxn = total,
                charges = charges,
                history = history,
                confidence = (1.0 - warnings.size * 0.1).coerceIn(0.4, 1.0),
                warnings = warnings,
            ),
        )
    }

    private fun parseCharges(text: String, tariff: String): List<ParsedBillCharge> {
        val charges = mutableListOf<ParsedBillCharge>()
        Regex("(?m)^(BASICO|INTERMEDIO|EXCEDENTE)\\s+([^\\r\\n]+)").findAll(text).forEach { match ->
            val label = match.groupValues[1]
            val numbers = Regex("[0-9,]+(?:\\.\\d+)?").findAll(match.groupValues[2]).map { it.value }.toList()
            numbers.chunked(3).filter { it.size == 3 }.forEachIndexed { index, values ->
                charges += ParsedBillCharge(
                    type = label,
                    season = if (numbers.size > 3) if (index == 0) "OUT_OF_SUMMER" else "SUMMER" else "NONE",
                    quantityKwh = values[0].toMoney(),
                    unitRateMxn = values[1].toMoney(),
                    amountMxn = values[2].toMoney(),
                    documentLabel = label,
                )
            }
        }
        if (tariff == "PDBT" || tariff == "DAC") {
            Regex("(?m)^CARGO\\s+FIJO[^0-9]*([0-9,]+(?:\\.\\d+)?)").find(text)?.let { match ->
                charges += ParsedBillCharge("FIXED", "NONE", null, null, match.groupValues[1].toMoney(), "Cargo fijo")
            }
            Regex("(?m)^ENERGIA\\s+([^\\r\\n]+)").findAll(text).forEach { match ->
                val values = Regex("[0-9,]+(?:\\.\\d+)?")
                    .findAll(match.groupValues[1])
                    .map { it.value.toMoney() }
                    .toList()
                when {
                    values.size >= 3 -> charges += ParsedBillCharge(
                        "ENERGY", "NONE", values[0], values[1], values[2], "Energía",
                    )
                    tariff == "PDBT" && values.size == 1 -> charges += ParsedBillCharge(
                        "ENERGY", "NONE", null, null, values[0], "Energía",
                    )
                }
            }
        }
        return charges
    }

    private fun parseHistory(text: String): List<ParsedBillHistoryRow> {
        val pattern = Regex(
            "DEL\\s+(\\d{2}\\s+[A-Z]{3}\\s+\\d{2})\\s+AL\\s+(\\d{2}\\s+[A-Z]{3}\\s+\\d{2})\\s+([0-9,]+)\\s+\\$([0-9,]+(?:\\.\\d{1,2})?)(?:\\s+\\$([0-9,]+(?:\\.\\d{1,2})?))?",
        )
        return pattern.findAll(text).mapNotNull { match ->
            val start = parseDate(match.groupValues[1]) ?: return@mapNotNull null
            val end = parseDate(match.groupValues[2]) ?: return@mapNotNull null
            ParsedBillHistoryRow(
                periodStart = start,
                periodEnd = end,
                consumptionKwh = match.groupValues[3].toWholeNumber(),
                amountMxn = match.groupValues[4].toMoney(),
                paidMxn = match.groupValues[5].takeIf(String::isNotBlank)?.toMoney(),
            )
        }.toList()
    }

    private fun findReadingTriple(text: String, multiplier: Int): Triple<Int, Int, Int>? {
        val anchor = Regex("ENERGIA\\s*\\(\\s*KWH\\s*\\)").find(text) ?: return null
        val window = text.substring(anchor.range.last + 1, minOf(text.length, anchor.range.last + 1 + 1_200))
        val tokens = Regex("[0-9][0-9.,]*").findAll(window).take(40).mapNotNull { match ->
            val value = match.value.toWholeNumberOrNull() ?: return@mapNotNull null
            // Service/RMU identifiers can be 12 digits and sometimes appear between
            // columns in the text layer. They are identifiers, never meter readings.
            val reading = value.takeIf { it <= MAX_REASONABLE_READING }?.toInt()
                ?: return@mapNotNull null
            reading to match.range.first
        }.toList()
        data class Candidate(val triple: Triple<Int, Int, Int>, val score: Int)
        var best: Candidate? = null
        for (i in 0 until tokens.size - 2) {
            for (j in i + 1 until tokens.size - 1) {
                for (k in j + 1 until tokens.size) {
                    val current = tokens[i].first
                    val previous = tokens[j].first
                    val consumption = tokens[k].first
                    if (current < previous || (current - previous) * multiplier != consumption) continue
                    val implausibleReadingPenalty = if (current >= 100 && previous >= 100) 0 else 1_000_000
                    val score = implausibleReadingPenalty + (k - i) * 10_000 +
                        (tokens[k].second - tokens[i].second)
                    if (best == null || score < best.score) best = Candidate(Triple(current, previous, consumption), score)
                }
            }
        }
        return best?.triple
    }

    private fun parseDate(value: String): LocalDate? {
        val parts = value.trim().split(Regex("\\s+"))
        if (parts.size != 3) return null
        val month = MONTHS[parts[1]] ?: return null
        return runCatching { LocalDate.of(2000 + parts[2].toInt(), month, parts[0].toInt()) }.getOrNull()
    }

    private fun capture(text: String, pattern: String): String? = Regex(pattern).find(text)?.groupValues?.get(1)?.trim()
    private fun String.toWholeNumber(): Int = requireNotNull(toWholeNumberOrNull()?.takeIf { it <= Int.MAX_VALUE }) {
        "Número entero fuera de rango"
    }.toInt()
    private fun String.toWholeNumberOrNull(): Long? = replace(",", "").replace(".", "").toLongOrNull()
    private fun String.toMoney(): Double = replace(",", "").toDouble()
    private fun <T> missing(reasons: MutableList<String>, label: String): T? { reasons += "Falta $label"; return null }

    private fun fold(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .uppercase(Locale.ROOT)

    private companion object {
        const val MAX_REASONABLE_READING = 100_000_000L
        val MONTHS = mapOf(
            "ENE" to 1, "FEB" to 2, "MAR" to 3, "ABR" to 4, "MAY" to 5, "JUN" to 6,
            "JUL" to 7, "AGO" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DIC" to 12,
        )
    }
}
