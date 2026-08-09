package com.recibozen.app.core.ocr

import kotlin.math.abs

/** Extracts plausible 4–7 digit meter readings from noisy OCR text. */
object MeterReadingParser {
    fun parse(text: String, previousReadingKwh: Int): Int? {
        val candidates = text.lineSequence()
            .flatMap { line -> line.split(Regex("\\s+")).asSequence() }
            .map(::normalizeToken)
            .map { it.filter(Char::isDigit) }
            .filter { it.length in 4..7 }
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return null
        val plausible = candidates.filter { it in (previousReadingKwh - 1_000)..(previousReadingKwh + 10_000) }
        return (plausible.ifEmpty { candidates }).minByOrNull { candidate ->
            abs(candidate - previousReadingKwh) + abs(candidate.toString().length - previousReadingKwh.toString().length) * 10_000
        }
    }

    private fun normalizeToken(token: String): String {
        if (token.count(Char::isDigit) < 2) return token
        return token.uppercase()
            .replace('O', '0')
            .replace('Q', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')
    }
}
