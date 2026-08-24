package app.recibozen.android.core.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzSpacing

/** Filled CTA — "Agregar a mi plan", "Confirmar lectura", the app's main action style. */
@Composable
fun RzPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // A floor, not a fixed height. This was `height(52.dp)`, which is the
        // one button of the three that did not grow: RzSecondaryButton and
        // RzTextButton both already used defaultMinSize. A household running a
        // large system font size got every primary action in the app -- confirm
        // a reading, save, continue -- with its label cut off, and the longer
        // Spanish labels wrap to two lines well before that.
        //
        // At the default font size nothing moves: the label plus padding comes
        // to well under 52dp, so the floor still decides the height.
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        shape = RzExtraShapes.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        // Vertical padding so a label that does grow is not flush against the
        // border. There was none, which is invisible while the height is fixed
        // and ugly the moment it is not.
        contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.sm),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Outlined secondary action — "Editar uso", "Corregir", "Anterior". */
@Composable
fun RzSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        shape = RzExtraShapes.control,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = RzSpacing.xl, vertical = RzSpacing.sm),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Text-only action in the accent color — "Ver por qué", "Ver todos". */
@Composable
fun RzTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = RzSpacing.minTouchTarget),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
