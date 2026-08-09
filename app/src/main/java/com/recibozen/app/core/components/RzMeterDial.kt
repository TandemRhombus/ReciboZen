package com.recibozen.app.core.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recibozen.app.R
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure math mirroring the original prototype's dial logic (angle -> digit
 * with carry from neighboring faces) so the interpreted reading matches
 * exactly what a user copying their meter's hands would expect.
 */
object MeterDialMath {
    fun rawValue(angleDegrees: Float, clockwise: Boolean): Float {
        val r = angleDegrees / 36f
        return if (clockwise) r else (10f - r) % 10f
    }

    fun digits(angles: List<Float>, directions: List<Boolean>): List<Int> {
        val raw = angles.indices.map { i -> rawValue(angles[i], directions[i]) }
        val d = raw.map { floor(it).toInt() % 10 }.toMutableList()
        for (i in raw.size - 2 downTo 0) {
            val frac = raw[i] - floor(raw[i])
            if (frac >= 0.88f && d[i + 1] <= 1) d[i] = (d[i] + 1) % 10
            else if (frac <= 0.12f && d[i + 1] >= 8) d[i] = (d[i] + 9) % 10
        }
        return d
    }

    fun angleFromDrag(center: Offset, position: Offset): Float {
        val dx = (position.x - center.x).toDouble()
        val dy = (position.y - center.y).toDouble()
        var deg = Math.toDegrees(atan2(dx, -dy))
        if (deg < 0) deg += 360.0
        return deg.toFloat()
    }

    fun nudge(angle: Float, deltaDegrees: Float, clockwise: Boolean): Float {
        val signed = deltaDegrees * (if (clockwise) 1f else -1f)
        return (angle + signed + 360f) % 360f
    }

    fun angleForDigit(digit: Int, clockwise: Boolean): Float {
        val target = if (clockwise) digit else (10 - digit) % 10
        return ((target + 0.4f) * 36f) % 360f
    }
}

private val DialDirections = listOf(true, false, true, false, true)

/**
 * The full "ajustar manecillas" cluster: title + sentido chip, one large
 * draggable face for [selectedIndex], nudge buttons, a strip of 5 tappable
 * mini faces, and the interpreted reading. Angle/selection state is owned by
 * the caller (readings feature ViewModel) so it can gate "Guardar lectura"
 * on all 5 faces having been visited.
 */
@Composable
fun RzMeterDialCluster(
    angles: List<Float>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    onAngleChange: (index: Int, angleDegrees: Float) -> Unit,
    modifier: Modifier = Modifier,
    directions: List<Boolean> = DialDirections,
) {
    val digits = MeterDialMath.digits(angles, directions)
    val clockwise = directions[selectedIndex]

    RzCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.dial_face_label, selectedIndex + 1),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(modifier = Modifier.weight(1f))
            RzStatusChip(text = stringResource(if (clockwise) R.string.dial_clockwise else R.string.dial_counterclockwise))
        }

        Box(modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg), contentAlignment = Alignment.Center) {
            DialFace(
                angleDegrees = angles[selectedIndex],
                clockwise = clockwise,
                digit = digits[selectedIndex],
                faceIndex = selectedIndex,
                selected = true,
                faceSize = 210.dp,
                draggable = true,
                onDrag = { angle -> onAngleChange(selectedIndex, angle) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val nudgeBackDescription = stringResource(R.string.dial_nudge_back)
            IconButton(
                onClick = { onAngleChange(selectedIndex, MeterDialMath.nudge(angles[selectedIndex], -6f, clockwise)) },
                modifier = Modifier
                    .size(60.dp, 52.dp)
                    .background(MaterialTheme.colorScheme.surface, RzExtraShapes.control)
                    .semantics { contentDescription = nudgeBackDescription },
            ) { Text("−", style = MaterialTheme.typography.headlineMedium) }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(digits[selectedIndex].toString(), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.dial_reads_as), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            val nudgeForwardDescription = stringResource(R.string.dial_nudge_forward)
            IconButton(
                onClick = { onAngleChange(selectedIndex, MeterDialMath.nudge(angles[selectedIndex], 6f, clockwise)) },
                modifier = Modifier
                    .size(60.dp, 52.dp)
                    .background(MaterialTheme.colorScheme.surface, RzExtraShapes.control)
                    .semantics { contentDescription = nudgeForwardDescription },
            ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
        }

        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = RzSpacing.lg))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            angles.indices.forEach { index ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DialFace(
                        angleDegrees = angles[index],
                        clockwise = directions[index],
                        digit = digits[index],
                        faceIndex = index,
                        selected = index == selectedIndex,
                        faceSize = 100.dp,
                        draggable = false,
                        onTap = { onSelectedIndexChange(index) },
                    )
                    Text(
                        digits[index].toString(),
                        style = if (index == selectedIndex) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (index == selectedIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            stringResource(R.string.dial_tap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
        )
    }
}

@Composable
private fun DialFace(
    angleDegrees: Float,
    clockwise: Boolean,
    digit: Int,
    faceIndex: Int,
    selected: Boolean,
    faceSize: Dp,
    draggable: Boolean,
    modifier: Modifier = Modifier,
    onDrag: ((Float) -> Unit)? = null,
    onTap: (() -> Unit)? = null,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val needleColor = MaterialTheme.colorScheme.onSurface
    val dotColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    val directionLabel = stringResource(if (clockwise) R.string.dial_clockwise else R.string.dial_counterclockwise)
    val description = stringResource(R.string.dial_face_content_description, faceIndex + 1, directionLabel, digit)
    val selectLabel = stringResource(R.string.dial_select_face, faceIndex + 1)

    Canvas(
        modifier
            .size(faceSize)
            .clip(CircleShape)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .then(
                if (draggable && onDrag != null) {
                    Modifier.pointerInput(faceIndex) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            onDrag(MeterDialMath.angleFromDrag(center, change.position))
                        }
                    }
                } else if (!draggable && onTap != null) {
                    Modifier.clickable(onClickLabel = selectLabel) { onTap() }
                } else Modifier
            ),
    ) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        drawCircle(color = surfaceColor, radius = radius * 0.98f, center = center)
        drawCircle(
            color = borderColor,
            radius = radius * 0.96f,
            center = center,
            style = Stroke(width = (if (selected) 3.5f else 1.6f).dp.toPx()),
        )

        for (slot in 0 until 10) {
            val n = if (clockwise) slot else (10 - slot) % 10
            val angRad = Math.toRadians((slot * 36).toDouble())
            val sinA = sin(angRad).toFloat()
            val cosA = cos(angRad).toFloat()

            val tickStart = Offset(center.x + sinA * radius * 0.86f, center.y - cosA * radius * 0.86f)
            val tickEnd = Offset(center.x + sinA * radius * 0.95f, center.y - cosA * radius * 0.95f)
            drawLine(tickColor, tickStart, tickEnd, strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)

            val labelPos = Offset(center.x + sinA * radius * 0.68f, center.y - cosA * radius * 0.68f)
            val layout = textMeasurer.measure(
                text = n.toString(),
                style = TextStyle(fontSize = (faceSize.value * 0.11f).sp, color = labelColor, fontWeight = FontWeight.SemiBold),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(labelPos.x - layout.size.width / 2f, labelPos.y - layout.size.height / 2f),
            )
        }

        val needleRad = Math.toRadians(angleDegrees.toDouble())
        val needleEnd = Offset(
            center.x + sin(needleRad).toFloat() * radius * 0.66f,
            center.y - cos(needleRad).toFloat() * radius * 0.66f,
        )
        drawLine(
            color = needleColor,
            start = center,
            end = needleEnd,
            strokeWidth = (if (draggable) 4f else 6f).dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color = dotColor, radius = (if (draggable) 4f else 6f).dp.toPx(), center = center)
    }
}
