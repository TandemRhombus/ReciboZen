package app.recibozen.android.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.recibozen.android.R
import app.recibozen.android.core.designsystem.RzSpacing

/**
 * The greeting bar shared by all 4 bottom-nav roots ("Buenas tardes, Emi ·
 * Casa" + avatar -> Perfil). Renders above each tab's own content, matching
 * the design's shared `tabScreen` header block.
 */
@Composable
fun RzTabHeader(
    greeting: String,
    householdName: String,
    userInitial: String,
    onHouseholdClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profileDescription = stringResource(R.string.content_desc_profile)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                Text(greeting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.clickable(onClickLabel = "Cambiar de hogar", onClick = onHouseholdClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(householdName, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    androidx.compose.material3.Icon(
                        app.recibozen.android.core.designsystem.RzIcons.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        RzAvatarCircle(
            initial = userInitial,
            baseDiameter = 48.dp,
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable(onClickLabel = profileDescription, onClick = onProfileClick)
                .semantics { contentDescription = profileDescription },
        )
    }
}
