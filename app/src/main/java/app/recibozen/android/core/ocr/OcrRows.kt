package app.recibozen.android.core.ocr

/** One recognized word and where it sits in the photo, in image pixels. */
data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Rebuilds the visual rows of a photo from word positions.
 *
 * The recognizer groups text into blocks by its own idea of layout, and a drum
 * register defeats it: each digit sits behind a separate window with a frame in
 * between, so the five digits of one reading can come back as five blocks, one
 * per line of the recognizer's plain text. No amount of parsing that text puts
 * them back together. Their positions still do -- they share a row.
 *
 * Two words share a row when each one's vertical center falls inside the other's
 * height band, measured against the first word of the row so a long, slightly
 * tilted line cannot drift into the next one. Rows come out top to bottom and
 * words left to right.
 */
object OcrRows {

    fun reassemble(words: List<OcrWord>): String {
        val rows = mutableListOf<MutableList<OcrWord>>()
        for (word in words.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()?.takeIf { sharesRow(it.first(), word) }
            if (row != null) row += word else rows += mutableListOf(word)
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    private fun sharesRow(anchor: OcrWord, word: OcrWord): Boolean {
        val distance = kotlin.math.abs(anchor.centerY - word.centerY)
        return distance * 2 <= anchor.height && distance * 2 <= word.height
    }
}
