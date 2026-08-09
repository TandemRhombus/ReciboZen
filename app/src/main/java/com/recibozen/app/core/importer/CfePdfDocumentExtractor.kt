package com.recibozen.app.core.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedInputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class ExtractedPdfDocument(
    val text: String,
    val sha256: String,
    val pageCount: Int,
    val usedOcr: Boolean,
)

class CfePdfDocumentExtractor(private val context: Context) {
    suspend fun extract(uri: Uri, forceOcr: Boolean = false): Result<ExtractedPdfDocument> = runCatching {
        validateHeaderAndSize(uri)
        val sha256 = sha256(uri)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("No se pudo abrir el PDF.")
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount in 1..MAX_PAGES) { "El PDF debe tener entre 1 y $MAX_PAGES páginas." }
                var usedOcr = false
                val pages = buildList {
                    repeat(renderer.pageCount) { index ->
                        renderer.openPage(index).use { page ->
                            val nativeText = if (!forceOcr && Build.VERSION.SDK_INT >= 35) {
                                page.textContents.joinToString("\n") { it.text }
                            } else ""
                            if (!forceOcr && nativeText.length >= MIN_NATIVE_TEXT_LENGTH) add(nativeText)
                            else {
                                usedOcr = true
                                add(recognize(page))
                            }
                        }
                    }
                }
                ExtractedPdfDocument(pages.joinToString("\n\n"), sha256, renderer.pageCount, usedOcr)
            }
        }
    }

    private suspend fun recognize(page: PdfRenderer.Page): String {
        val scale = (TARGET_WIDTH.toFloat() / page.width.coerceAtLeast(1)).coerceIn(1f, 3f)
        val width = (page.width * scale).toInt().coerceAtMost(TARGET_WIDTH)
        val height = (page.height * scale).toInt().coerceAtMost(TARGET_HEIGHT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume("") }
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private suspend fun validateHeaderAndSize(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val length = descriptor.length
            require(length < 0 || length <= MAX_BYTES) { "El PDF supera el límite de 15 MB." }
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(5)
            val count = stream.read(header)
            require(count == 5 && header.decodeToString() == "%PDF-") { "El archivo no tiene una firma PDF válida." }
        } ?: error("No se pudo leer el PDF.")
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
        } ?: error("No se pudo calcular la huella del PDF.")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_PAGES = 8
        const val MAX_BYTES = 15L * 1024L * 1024L
        const val MIN_NATIVE_TEXT_LENGTH = 300
        const val TARGET_WIDTH = 1800
        const val TARGET_HEIGHT = 2800
    }
}
