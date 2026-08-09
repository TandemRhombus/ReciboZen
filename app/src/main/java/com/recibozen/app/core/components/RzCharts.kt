package com.recibozen.app.core.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ChartLineStyle { SOLID, DASHED, DOTTED }

/** A single line/series inside [RzLineChart]. Values are normalized 0f (bottom) .. 1f (top). */
data class RzChartSeries(
    val values: List<Float>,
    val color: Color,
    val style: ChartLineStyle = ChartLineStyle.SOLID,
    val strokeWidth: Dp = 3.dp,
    val markEnd: Boolean = false,
)

/**
 * Minimal line chart for "Cómo va el periodo" / "Consumo acumulado del periodo":
 * one or more series sharing an x-axis, drawn against a baseline.
 */
@Composable
fun RzLineChart(
    series: List<RzChartSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    baselineColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        drawLine(
            color = baselineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val stepX = size.width / (s.values.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            s.values.forEachIndexed { index, value ->
                val x = stepX * index
                val y = size.height * (1f - value.coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val effect = when (s.style) {
                ChartLineStyle.SOLID -> null
                ChartLineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                ChartLineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1.5f, 6f))
            }
            drawPath(
                path = path,
                color = s.color,
                style = Stroke(
                    width = s.strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = effect,
                ),
            )
            if (s.markEnd) {
                val lastIndex = s.values.size - 1
                val x = stepX * lastIndex
                val y = size.height * (1f - s.values.last().coerceIn(0f, 1f))
                drawCircle(color = Color.White, radius = 5.5.dp.toPx(), center = Offset(x, y))
                drawCircle(
                    color = s.color,
                    radius = 5.5.dp.toPx(),
                    center = Offset(x, y),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

/** One column in [RzBarChart]: a value label above a bar and a category label below it. */
data class RzBarEntry(
    val categoryLabel: String,
    val valueLabel: String,
    val heightFraction: Float,
    val color: Color,
    val emphasized: Boolean = false,
)

@Composable
fun RzBarChart(
    entries: List<RzBarEntry>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        entries.forEach { entry ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    entry.valueLabel,
                    style = if (entry.emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
                    color = if (entry.emphasized) entry.color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(entry.heightFraction.coerceIn(0.02f, 1f))
                            .background(entry.color, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                    )
                }
                Text(
                    entry.categoryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Two-series grouped bars per category — Solar "mes a mes: consumo y producción". */
data class RzDualBarEntry(val categoryLabel: String, val a: Float, val b: Float)

@Composable
fun RzDualBarChart(
    entries: List<RzDualBarEntry>,
    colorA: Color,
    colorB: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(entry.a.coerceIn(0.02f, 1f))
                            .background(colorA, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(entry.b.coerceIn(0.02f, 1f))
                            .background(colorB, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
            }
        }
    }
}
