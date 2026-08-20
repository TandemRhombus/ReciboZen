package app.recibozen.android.core.calculation

/**
 * What the app knows about the physical meter, derived from the type the user
 * picked during onboarding.
 *
 * This replaces three scattered `meterType.contains("4")` probes over a Spanish
 * interface label. That probe was wrong in two ways beyond its fragility: it
 * matched the character "4" anywhere in the string, and everything it did not
 * match was silently treated as five dials -- including "Digital, con números",
 * whose digit count nobody ever stated. Assuming five for a four-digit meter is
 * what produces phantom readings ten times too large.
 *
 * Reading a label is not a good long-term home for this. It is, however, the
 * datum that exists today, and reading it in one tested place is strictly better
 * than reading it in three untested ones. When the meter's digit count becomes a
 * field the user states directly, only this object changes.
 */
object MeterProfile {

    /** How many faces the dial component models before any meter-specific trim. */
    const val MODELLED_FACES = 5

    private val DIAL_COUNT = Regex("""(\d+)\s*car[aá]tula""", RegexOption.IGNORE_CASE)

    /**
     * Number of reading digits this meter shows, or null when the label does not
     * establish it. Null is a real answer and must not be replaced by a default:
     * a digital meter may show 5, 6 or 7 digits and the app has not asked.
     */
    fun digitCount(meterTypeLabel: String): Int? =
        DIAL_COUNT.find(meterTypeLabel)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..9 }

    /**
     * Register capacity, i.e. the value at which this meter wraps back to zero.
     * Null whenever the digit count is unknown, so [MeterDelta] refuses to do
     * rollover arithmetic rather than inventing a capacity.
     */
    fun capacity(meterTypeLabel: String): Int? = MeterDelta.capacityFor(digitCount(meterTypeLabel))

    /**
     * Which of the [MODELLED_FACES] dial faces this meter actually has, most
     * significant first. A four-dial meter drops the leading face; anything the
     * label does not describe keeps every face, which is what the component did
     * before and what a five-dial meter needs.
     *
     * The test dial some meters carry -- marked "Prueba" and usually offset below
     * the row -- is not one of these. It never contributes a digit.
     */
    fun faceIndices(meterTypeLabel: String): List<Int> {
        val digits = digitCount(meterTypeLabel) ?: return (0 until MODELLED_FACES).toList()
        if (digits >= MODELLED_FACES) return (0 until MODELLED_FACES).toList()
        return (MODELLED_FACES - digits until MODELLED_FACES).toList()
    }

    /** The face the dial screen should start on for this meter. */
    fun firstFaceIndex(meterTypeLabel: String): Int = faceIndices(meterTypeLabel).first()

    /** Keeps only the digits this meter actually shows, from a full modelled row. */
    fun trimDigits(meterTypeLabel: String, allDigits: List<Int>): List<Int> {
        val digits = digitCount(meterTypeLabel) ?: return allDigits
        return if (digits >= allDigits.size) allDigits else allDigits.takeLast(digits)
    }
}
