package app.recibozen.android.core.ocr

/**
 * Extracts a meter reading from noisy OCR text.
 *
 * The previous version took any 4-to-7 digit run and picked whichever was
 * closest to the last reading. Run against a real CFE faceplate that returns a
 * homologation code as the reading: `SIC DGE 4348` and `X 7690 W` are both
 * four-digit runs, and the closeness heuristic makes whichever sits nearer the
 * previous value look the most convincing.
 *
 * Two changes follow from that. Lines that describe the meter rather than its
 * count are dropped before anything is considered, and a run only qualifies if
 * it has exactly as many digits as the meter is known to show. What survives is
 * still checked for ambiguity: two credible candidates means the photo did not
 * establish a reading, and saying so is the only honest answer.
 *
 * A dial meter has no digits to read at all -- the value lives in needle angles.
 * For those, this returns null by construction, which is correct: the reading
 * has to come from the dial screen.
 */
object MeterReadingParser {

    /**
     * Words that only ever appear on the faceplate: standards bodies, catalogue
     * and lot codes, meter type and the disk constants. A line carrying one of
     * these is describing the meter, never counting kWh.
     */
    private val NAMEPLATE_MARKERS = listOf(
        "SIC", "DGE", "DGN", "NOM", "COD", "LOTE", "SERIE", "TIPO", "MOD", "CAT",
        "CLASE", "CL-", "KH", "RR", "PRUEBA", "WATTHORIMETRO", "COMISION", "FEDERAL",
    )

    /** Standalone unit tokens that mark a rating line, not a reading. */
    private val RATING_UNITS = setOf("W", "V", "A", "HZ", "KW", "VA", "KVA", "PH", "FASES")

    fun parse(text: String, previousReadingKwh: Int, meterDigitCount: Int? = null): Int? {
        val candidates = text.lineSequence()
            .filterNot(::describesTheMeter)
            .flatMap { line -> line.split(Regex("\\s+")).asSequence() }
            .map(::normalizeToken)
            .map { it.filter(Char::isDigit) }
            .filter { it.isNotEmpty() && it.length == (meterDigitCount ?: it.length) }
            .filter { it.length in MIN_DIGITS..MAX_DIGITS }
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .toList()

        if (candidates.isEmpty()) return null

        val credible = candidates.filter { it in plausibleRange(previousReadingKwh) }
        // With nothing inside the plausible window, the photo did not produce a
        // reading. Falling back to "the closest of whatever we found" is exactly
        // how a catalogue code became a meter reading.
        if (credible.isEmpty()) return null
        // Two credible values and no way to choose. Refusing sends the user back
        // to a photo they can retake; guessing writes a wrong number into the
        // history and every projection that follows from it.
        if (credible.size > 1) return null
        return credible.single()
    }

    private fun describesTheMeter(line: String): Boolean {
        val upper = line.uppercase()
        if (NAMEPLATE_MARKERS.any { upper.contains(it) }) return true
        return line.split(Regex("[\\s\\-]+")).any { it.uppercase() in RATING_UNITS }
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

    /**
     * A register only ever moves forward, so anything below the previous reading
     * is either a wrap or a misread, and neither is this function's call to make.
     * The upper bound keeps a stray long number from passing as a reading.
     */
    private fun plausibleRange(previousReadingKwh: Int): IntRange =
        previousReadingKwh..(previousReadingKwh + MAX_FORWARD_KWH)

    private const val MIN_DIGITS = 4
    private const val MAX_DIGITS = 7
    private const val MAX_FORWARD_KWH = 10_000
}
