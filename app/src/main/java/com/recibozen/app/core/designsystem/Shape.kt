package com.recibozen.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val RzShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/** Radii the M3 [Shapes] 5-slot scale doesn't cover: pill controls and the dashed/outlined cards. */
object RzExtraShapes {
    val pill = RoundedCornerShape(percent = 50)
    val cardLarge = RoundedCornerShape(22.dp)
    val cardMedium = RoundedCornerShape(20.dp)
    val cardSmall = RoundedCornerShape(18.dp)
    val control = RoundedCornerShape(14.dp)
}
