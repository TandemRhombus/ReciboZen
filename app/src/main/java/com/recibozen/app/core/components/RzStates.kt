package com.recibozen.app.core.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.RzTheme

/** Full-page empty state — "Aún no has agregado aparatos", "Aún no tienes acciones en tu plan". */
@Composable
fun RzEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconContainerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    RzCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.xxl),
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RzExtraShapes.cardMedium)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(30.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = RzSpacing.lg),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = RzSpacing.sm),
            )
            if (primaryActionLabel != null && onPrimaryAction != null) {
                RzPrimaryButton(
                    text = primaryActionLabel,
                    onClick = onPrimaryAction,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
                )
            }
        }
    }
}

enum class RzBannerTone { INFO, SUCCESS, WARNING, NEUTRAL }

/** Icon + text callout — tips, confirmations, alerts. Never the only signal (icon+color+text). */
@Composable
fun RzInlineBanner(
    text: String,
    tone: RzBannerTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
) {
    val ext = RzTheme.extendedColors
    val (container, content) = when (tone) {
        RzBannerTone.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSurface
        RzBannerTone.SUCCESS -> ext.successContainer to ext.onSuccessContainer
        RzBannerTone.WARNING -> ext.warningContainer to ext.onWarningContainer
        RzBannerTone.NEUTRAL -> MaterialTheme.colorScheme.background to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint = when (tone) {
        RzBannerTone.INFO -> MaterialTheme.colorScheme.primary
        RzBannerTone.SUCCESS -> ext.success
        RzBannerTone.WARNING -> ext.warning
        RzBannerTone.NEUTRAL -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RzExtraShapes.cardSmall)
            .background(container)
            .padding(horizontal = RzSpacing.lg, vertical = RzSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(top = 1.dp))
        }
        androidx.compose.foundation.layout.Column {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

/** Pulsing placeholder block for the "Calculando" loading state. */
@Composable
fun RzSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(alpha),
    )
}

@Composable
fun RzSkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    RzSkeletonBlock(modifier = modifier.height(height), shape = RoundedCornerShape(percent = 50))
}
