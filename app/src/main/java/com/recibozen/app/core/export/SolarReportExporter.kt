package com.recibozen.app.core.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.recibozen.app.core.model.SolarScenario
import java.io.File

object SolarReportExporter {
    fun createAndShare(
        context: Context,
        householdName: String,
        location: String,
        annualConsumptionKwh: Int,
        scenario: SolarScenario,
    ) {
        val reportDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val report = File(reportDir, "preestudio_solar_recibozen.pdf")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 34, 40) }
            var y = 64f

            fun line(text: String, size: Float = 14f, bold: Boolean = false, gap: Float = 28f) {
                paint.textSize = size
                paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText(text, 52f, y, paint)
                y += gap
            }

            line("ReciboZen", 25f, bold = true, gap = 38f)
            line("Preestudio solar · $householdName", 20f, bold = true, gap = 34f)
            line("Ubicación: $location")
            line("Consumo anual estimado: $annualConsumptionKwh kWh", gap = 38f)
            line(scenario.name, 18f, bold = true, gap = 34f)
            line("Cobertura aproximada: ${scenario.coverageLabel}")
            line("Paneles: ${scenario.panels}")
            line("Potencia del sistema: ${scenario.systemPower}")
            line("Área necesaria: ${scenario.area}")
            line("Producción estimada: ${scenario.production}")
            line("Energía restante de la red: ${scenario.gridRemaining}", gap = 42f)

            paint.color = Color.rgb(75, 75, 82)
            val wrapped = wrap(scenario.note, 72)
            wrapped.forEach { line(it, size = 12f, gap = 20f) }
            y += 18f
            line("Estimación informativa; no es una cotización ni garantía de producción.", size = 11f, bold = true, gap = 20f)
            line("Un instalador debe validar orientación, sombras, estructura y condiciones de CFE.", size = 11f, gap = 20f)

            document.finishPage(page)
            report.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", report)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Compartir preestudio solar"))
    }

    private fun wrap(text: String, maxCharacters: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        text.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (candidate.length > maxCharacters && current.isNotEmpty()) {
                lines += current
                current = word
            } else current = candidate
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
