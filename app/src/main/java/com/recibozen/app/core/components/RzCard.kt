package com.recibozen.app.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing

/**
 * The generic rounded/bordered surface used for almost every card in the
 * design ("background:#fff;border:1px solid #DDE4DF;border-radius:22px").
 */
@Composable
fun RzCard(
    modifier: Modifier = Modifier,
    shape: Shape = RzExtraShapes.cardLarge,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = MaterialTheme.colorScheme.outlineVariant,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(RzSpacing.xl),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor, shape)
            .then(
                if (borderColor != null) Modifier.border(borderWidth, borderColor, shape) else Modifier
            )
            .padding(contentPadding),
        content = { content() },
    )
}

/** Dashed-border card used for hint/placeholder boxes (empty state suggestions, calc footnotes). */
@Composable
fun RzDashedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RzExtraShapes.cardSmall,
    cornerRadius: Dp = 18.dp,
    containerColor: Color = MaterialTheme.colorScheme.background,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    contentPadding: PaddingValues = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.lg),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor, shape)
            .dashedBorder(color = borderColor, cornerRadius = cornerRadius),
        content = {
            Column(modifier = Modifier.padding(contentPadding)) { content() }
        },
    )
}

fun Modifier.dashedBorder(
    color: Color,
    strokeWidth: Dp = 1.dp,
    dashLength: Dp = 5.dp,
    gapLength: Dp = 4.dp,
    cornerRadius: Dp = 18.dp,
): Modifier = drawBehind {
    val strokePx = strokeWidth.toPx()
    drawRoundRect(
        color = color,
        size = Size(size.width - strokePx, size.height - strokePx),
        topLeft = Offset(strokePx / 2, strokePx / 2),
        cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), gapLength.toPx())),
        ),
    )
}

/** Shorthand for [RoundedCornerShape] with an equal corner on all sides, used by a few one-off surfaces. */
fun rz(radius: Dp): Shape = RoundedCornerShape(radius)
