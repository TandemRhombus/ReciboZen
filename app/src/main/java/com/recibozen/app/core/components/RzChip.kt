package com.recibozen.app.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzSpacing

/** Non-interactive status pill, e.g. "Confianza alta", "Alta oportunidad", "Uso esencial". */
@Composable
fun RzStatusChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .clip(RzExtraShapes.pill)
            .background(containerColor)
            .padding(horizontal = RzSpacing.md, vertical = RzSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.wrapContentHeight())
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = contentColor)
    }
}

/** Tappable filter/choice pill — Aparatos filters, meter-type picker in onboarding. */
@Composable
fun RzFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surface
    val content = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = RzSpacing.minTouchTarget)
            .clip(RzExtraShapes.pill)
            .background(container)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = RzSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Segmented control with a filled pill for the active option — Solar scenarios, Historial units. */
@Composable
fun RzSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RzExtraShapes.pill)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = RzSpacing.minTouchTarget)
                    .clip(RzExtraShapes.pill)
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .selectable(selected = selected, onClick = { onSelectedChange(index) }, role = Role.Tab),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
