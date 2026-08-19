package app.recibozen.android.core.importer

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedInputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class ExtractedPhotoDocument(val text: String, val sha256: String)

/**
 * Reads a photographed paper bill into plain text so it can go through the very
 * same [CfeBillTextParser] and review screen as an imported PDF — only the
 * input adapter differs, never the parsing or the confirmation rules.
 *
 * Accuracy is materially lower than a native PDF (glare, creases, angle), which
 * is exactly why the review step is editable: whatever this misreads, the user
 * fixes before anything is persisted.
 */
class CfePhotoDocumentExtractor(private val context: Context) {

    suspend fun extract(uri: Uri): Result<ExtractedPhotoDocument> = runCatching {
        val sha256 = sha256(uri)
        // fromFilePath applies the EXIF rotation for us, so a photo taken in
        // portrait is not fed to the recognizer sideways.
        val image = withContext(Dispatchers.IO) { InputImage.fromFilePath(context, uri) }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resume("")
                    }
            }
        } finally {
            recognizer.close()
        }
        require(text.length >= MIN_TEXT_LENGTH) {
            "No se distinguió suficiente texto en la foto. Acércate, evita reflejos y vuelve a intentar."
        }
        ExtractedPhotoDocument(text, sha256)
    }

    private suspend fun sha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        } ?: error("No se pudo leer la fotografía.")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // Below this a "successful" recognition is really a blurry frame; the
        // parser would fail confusingly instead of saying "retake the photo".
        const val MIN_TEXT_LENGTH = 120
    }
}
