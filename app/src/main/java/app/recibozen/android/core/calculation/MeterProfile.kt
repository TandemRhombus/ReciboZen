package app.recibozen.android.core.calculation

/**
 * What the app knows about the physical meter.
 *
 * This replaces three scattered `meterType.contains("4")` probes over a Spanish
 * interface label. That probe was wrong in two ways beyond its fragility: it
 * matched the character "4" anywhere in the string, and everything it did not
 * match was silently treated as five dials -- including "Digital, con números",
 * whose digit count nobody ever stated. Assuming five for a four-digit meter is
 * what produces phantom readings ten times too large.
 *
 * There are now two sources for the digit count and they are not equal:
 *
 *  - The **declared** count is testimony. The household looked at the register
 *    and counted. It is the only source that can answer for a digital meter,
 *    whose onboarding label ("Digital, con números") establishes nothing.
 *  - The **label** is an inference from a string the user picked off a list.
 *    "De manecillas, 4 carátulas" does establish four digits, but only because
 *    we wrote that string; it is a translation away from establishing nothing.
 *
 * Testimony wins. Where neither exists the answer is null, and null is a real
 * answer that must not be replaced by a default: [MeterDelta] refuses to do
 * rollover arithmetic without a capacity, and refusing is correct.
 */
/**
 * The two facts about a physical register that every consumption figure depends
 * on, carried together.
 *
 * They travel as a pair on purpose. The multiplier was omitted at every
 * reading-derived call site for as long as the code existed -- not because
 * anyone decided readings should not be multiplied, but because it was a
 * separate optional argument with a harmless-looking default of 1 and nothing
 * ever forced the question. Handing over a capacity without a multiplier is now
 * unrepresentable.
 */
data class MeterRegister(
    /** 10^digits, or null when the digit count was never established. */
    val capacityKwh: Int?,
    /** The CFE constant. 1 when the meter has none. */
    val multiplier: Int,
)

object MeterProfile {

    /** How many faces the dial component models before any meter-specific trim. */
    const val MODELLED_FACES = 5

    /**
     * Digit counts a CFE residential register can actually have. A declaration
     * outside this range is not stored and not used: it is a typo, and falling
     * back to whatever the label says beats doing arithmetic against a capacity
     * of 10 kWh. The UI constrains input to the same range, so a rejection here
     * means something upstream is wrong rather than that a user mistyped.
     */
    val DECLARABLE_DIGITS = 4..8

    private val DIAL_COUNT = Regex("""(\d+)\s*car[aá]tula""", RegexOption.IGNORE_CASE)

    /**
     * Number of reading digits this meter shows, or null when nothing
     * establishes it. [declaredDigitCount] is what the household stated and
     * takes precedence over anything read out of [meterTypeLabel].
     */
    fun digitCount(meterTypeLabel: String, declaredDigitCount: Int? = null): Int? =
        declaredDigitCount?.takeIf { it in DECLARABLE_DIGITS } ?: inferredDigitCount(meterTypeLabel)

    /** The digit count [meterTypeLabel] states on its own, ignoring any declaration. */
    fun inferredDigitCount(meterTypeLabel: String): Int? =
        DIAL_COUNT.find(meterTypeLabel)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..9 }

    /**
     * Register capacity, i.e. the value at which this meter wraps back to zero.
     * Null whenever the digit count is unknown, so [MeterDelta] refuses to do
     * rollover arithmetic rather than inventing a capacity.
     */
    fun capacity(meterTypeLabel: String, declaredDigitCount: Int? = null): Int? =
        MeterDelta.capacityFor(digitCount(meterTypeLabel, declaredDigitCount))

    /**
     * Everything a consumption calculation needs to know about this register.
     * Prefer this over calling [capacity] and reading the multiplier separately:
     * a call site that takes a [MeterRegister] cannot forget half of it.
     */
    fun register(meterTypeLabel: String, declaredDigitCount: Int?, multiplier: Int): MeterRegister =
        MeterRegister(
            capacityKwh = capacity(meterTypeLabel, declaredDigitCount),
            multiplier = multiplier.coerceAtLeast(1),
        )

    /**
     * Which of the [MODELLED_FACES] dial faces this meter actually has, most
     * significant first. A four-dial meter drops the leading face; anything
     * nothing describes keeps every face, which is what the component did
     * before and what a five-dial meter needs.
     *
     * The test dial some meters carry -- marked "Prueba" and usually offset below
     * the row -- is not one of these. It never contributes a digit.
     */
    fun faceIndices(meterTypeLabel: String, declaredDigitCount: Int? = null): List<Int> {
        val digits = digitCount(meterTypeLabel, declaredDigitCount) ?: return (0 until MODELLED_FACES).toList()
        if (digits >= MODELLED_FACES) return (0 until MODELLED_FACES).toList()
        return (MODELLED_FACES - digits until MODELLED_FACES).toList()
    }

    /** The face the dial screen should start on for this meter. */
    fun firstFaceIndex(meterTypeLabel: String, declaredDigitCount: Int? = null): Int =
        faceIndices(meterTypeLabel, declaredDigitCount).first()

    /** Keeps only the digits this meter actually shows, from a full modelled row. */
    fun trimDigits(meterTypeLabel: String, allDigits: List<Int>, declaredDigitCount: Int? = null): List<Int> {
        val digits = digitCount(meterTypeLabel, declaredDigitCount) ?: return allDigits
        return if (digits >= allDigits.size) allDigits else allDigits.takeLast(digits)
    }
}
