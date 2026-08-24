package app.recibozen.android.core.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.recibozen.android.core.designsystem.RzIcons
import app.recibozen.android.core.designsystem.RzTheme
import app.recibozen.android.core.model.ConfidenceLevel

/** Single rounded progress track — consumption bars, appliance share bars, plan progress. */
@Composable
fun RzLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else Modifier
            ),
    ) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        if (clamped > 0f) {
            drawRoundRect(
                color = progressColor,
                size = size.copy(width = size.width * clamped),
                cornerRadius = radius,
            )
        }
    }
}

/** Progress track with a fixed marker line — DAC "límite de referencia", Análisis meta. */
@Composable
fun RzMarkedProgress(
    progress: Float,
    markerAt: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    markerColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        drawRoundRect(
            color = progressColor,
            size = size.copy(width = size.width * progress.coerceIn(0f, 1f)),
            cornerRadius = radius,
        )
        val markerX = size.width * markerAt.coerceIn(0f, 1f)
        drawRect(
            color = markerColor,
            topLeft = androidx.compose.ui.geometry.Offset(markerX - 1.5.dp.toPx(), 0f),
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
        )
    }
}

/** Three-segment confidence indicator with icon + label, matching "Confianza media" everywhere. */
@Composable
fun RzConfidenceIndicator(
    level: ConfidenceLevel,
    modifier: Modifier = Modifier,
    label: String,
) {
    val filled = when (level) {
        ConfidenceLevel.LOW -> 1
        ConfidenceLevel.MEDIUM -> 2
        ConfidenceLevel.HIGH -> 3
    }
    val color = when (level) {
        ConfidenceLevel.LOW, ConfidenceLevel.MEDIUM -> RzTheme.extendedColors.warning
        ConfidenceLevel.HIGH -> RzTheme.extendedColors.success
    }
    val icon = when (level) {
        ConfidenceLevel.HIGH -> RzIcons.CheckCircle
        ConfidenceLevel.MEDIUM -> RzIcons.GppMaybe
        ConfidenceLevel.LOW -> RzIcons.Help
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { index ->
                Canvas(modifier = Modifier.weight(1f).height(6.dp)) {
                    drawRoundRect(
                        color = if (index < filled) color else trackColorFallback(),
                        cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.trackColorFallback(): Color =
    Color(0xFFEAEDE8)

/** Horizontal stacked bar — "activo/reposo", Balance "explicado/sin identificar", DAC tariff tiers. */
data class RzBarSegment(val weight: Float, val color: Color, val striped: Boolean = false)

/**
 * One rounded bar divided into proportional segments.
 *
 * Three things this gets right that the previous version did not, all of them
 * visible on the DAC tier bar and the Balance split:
 *
 *  - **A segment worth nothing is not drawn.** Weights used to be coerced up to
 *    0.001, so a tariff tier the household never reaches -- the usual case, since
 *    every block of the tariff was mapped whether or not it was used -- rendered
 *    as a one-pixel sliver that still claimed a 4dp gap. Two unreached tiers ate
 *    the right end of the bar with nothing in them.
 *  - **The segments form one bar, not a row of floating rectangles.** Gaps
 *    between square-cornered rects read as separate objects; a single pill with
 *    colour changes inside reads as a proportion, which is what it is.
 *  - **The striped fill is legible.** It was drawn at 35% alpha over the card,
 *    which on a light surface is nearly the surface itself.
 *
 * With nothing to show it still draws an empty track, so a card does not change
 * height the moment data arrives.
 */
@Composable
fun RzStackedBarRow(
    segments: List<RzBarSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val visible = segments.filter { it.weight > 0f }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50)),
    ) {
        if (visible.isEmpty()) {
            Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) { drawRect(color = trackColor) }
            return@Row
        }
        visible.forEach { segment ->
            Canvas(modifier = Modifier.weight(segment.weight).fillMaxHeight()) {
                if (segment.striped) {
                    // A solid tint the stripes sit on, not a wash. The stripes
                    // say "provisional"; the tint is what makes the segment
                    // readable as a segment at all.
                    drawRect(color = segment.color.copy(alpha = 0.22f))
                    val step = 7.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(
                            color = segment.color,
                            start = androidx.compose.ui.geometry.Offset(x, size.height),
                            end = androidx.compose.ui.geometry.Offset(x + size.height, 0f),
                            strokeWidth = 2.5.dp.toPx(),
                        )
                        x += step
                    }
                } else {
                    drawRect(color = segment.color)
                }
            }
        }
    }
}
