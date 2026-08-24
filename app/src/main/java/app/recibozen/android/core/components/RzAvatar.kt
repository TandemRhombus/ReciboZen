package app.recibozen.android.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The circle carrying the household's initial, in the tab header and in Perfil.
 *
 * Both sites had it hand-rolled with a fixed `size(52.dp)` / `size(48.dp)` and a
 * Text inside. The letter is styled in sp, so it grows with the system font size
 * while the circle did not, and past roughly a 1.4x font scale the initial was
 * clipped by its own background.
 *
 * The circle cannot simply take a minimum size the way a button does: growing on
 * one axis only would turn it into an ellipse under `CircleShape`. So the
 * diameter is derived from the same font scale the letter uses, which keeps it
 * round, and then capped.
 *
 * The cap is a deliberate trade-off and worth stating: at a 2x font scale an
 * uncapped avatar would be 104dp and would push the header of every tab down by
 * more than an entire row, for one decorative letter whose meaning is already
 * carried by the content description. Growing to [MAX_GROWTH] of the base keeps
 * the letter legible without the chrome eating the screen.
 */
@Composable
fun RzAvatarCircle(
    initial: String,
    baseDiameter: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(scaledDiameter(baseDiameter))
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = textStyle, color = contentColor)
    }
}

/** How far past its base diameter an avatar may grow with the font size. */
private const val MAX_GROWTH = 1.6f

/**
 * [baseDiameter] scaled by the system font size, capped at [MAX_GROWTH].
 *
 * Expressed by round-tripping the diameter through sp: `Dp.toSp()` divides by
 * the font scale and `TextUnit.toDp()` multiplies by it, so converting a dp
 * value to sp at scale 1 and back at the current scale yields the diameter the
 * letter actually needs.
 */
@Composable
private fun scaledDiameter(baseDiameter: Dp): Dp = with(LocalDensity.current) {
    val asSp: TextUnit = baseDiameter.value.sp
    val scaled = asSp.toDp()
    val ceiling = baseDiameter * MAX_GROWTH
    if (scaled > ceiling) ceiling else if (scaled < baseDiameter) baseDiameter else scaled
}

private val Float.sp: TextUnit get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
