package app.recibozen.android.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand palette extracted from the ReciboZen V2 design file. Kept as raw
// constants (instead of only inline hex) so both the M3 ColorScheme and the
// extended semantic colors below can reference the same source values.
object RzPalette {
    val Violet = Color(0xFF565AE8)
    val VioletHover = Color(0xFF4A4EDB)
    val VioletContainerLight = Color(0xFFEFF0FE)
    val VioletContainerLightAlt = Color(0xFFF2F3FE)
    val VioletContainerBorderLight = Color(0xFFD8DAFA)
    val VioletContainerDark = Color(0xFF252A5A)
    val VioletOnContainerDark = Color(0xFFA7AAF4)

    val Lime = Color(0xFFD9EE63)
    val LimeHover = Color(0xFFCFE651)

    val SuccessLight = Color(0xFF168765)
    val SuccessContainerLight = Color(0xFFEAF6F1)
    val SuccessBorderLight = Color(0xFFC4E3D6)
    val SuccessDark = Color(0xFF5FCFA3)
    val SuccessContainerDark = Color(0xFF1B2E27)
    val SuccessBorderDark = Color(0xFF2A4A3D)

    val WarningLight = Color(0xFFE99A32)
    val WarningContainerLight = Color(0xFFFDF3E7)
    val WarningBorderLight = Color(0xFFF0D3AC)
    val WarningTextLight = Color(0xFFB06F13)
    val WarningDark = Color(0xFFE9B36A)
    val WarningContainerDark = Color(0xFF2E2517)
    val WarningBorderDark = Color(0xFF4A3A22)

    val SolarContainerLight = Color(0xFFF7FAE8)
    val SolarBorderLight = Color(0xFFE3EFAE)
    val SolarTextLight = Color(0xFF6E7D25)
    val SolarContainerDark = Color(0xFF222B14)
    val SolarBorderDark = Color(0xFF3A4A22)
    val SolarTextDark = Color(0xFFC7DE7A)

    val AlertLight = Color(0xFFD64D4D)
    val AlertContainerLight = Color(0xFFFBEAEA)
    val AlertDark = Color(0xFFE88585)
    val AlertContainerDark = Color(0xFF3A1E1E)

    val TextPrimaryLight = Color(0xFF14201D)
    val TextSecondaryLight = Color(0xFF60706A)
    val TextTertiaryLight = Color(0xFF89938F)
    val BorderLight = Color(0xFFDDE4DF)
    val DividerLight = Color(0xFFEFF1EE)
    val ScreenBackgroundLight = Color(0xFFF6F7F3)
    val SurfaceLight = Color(0xFFFFFFFF)
    val NeutralChipLight = Color(0xFFEFF1EE)

    val TextPrimaryDark = Color(0xFFF5F8F6)
    val TextSecondaryDark = Color(0xFFA9B7B1)
    val TextTertiaryDark = Color(0xFF7E8C85)
    val BorderDark = Color(0xFF2A3733)
    val ScreenBackgroundDark = Color(0xFF101815)
    val SurfaceDark = Color(0xFF18221F)
    val SurfaceVariantDark = Color(0xFF202C28)
    val NeutralChipDark = Color(0xFF223129)

    // Chart series colors (used by RzLineChart / RzBarChart / RzStackedBar)
    val ChartObserved = Violet
    val ChartGoalLine = SuccessLight
    val ChartHistoryBar = Color(0xFFC9CDF4)
    val ChartSolarProduction = Lime
}

val LightColorScheme = lightColorScheme(
    primary = RzPalette.Violet,
    onPrimary = Color.White,
    primaryContainer = RzPalette.VioletContainerLight,
    onPrimaryContainer = RzPalette.Violet,
    secondary = RzPalette.VioletHover,
    onSecondary = Color.White,
    secondaryContainer = RzPalette.VioletContainerLightAlt,
    onSecondaryContainer = RzPalette.Violet,
    tertiary = RzPalette.Lime,
    onTertiary = RzPalette.TextPrimaryLight,
    tertiaryContainer = RzPalette.Lime,
    onTertiaryContainer = RzPalette.TextPrimaryLight,
    error = RzPalette.AlertLight,
    onError = Color.White,
    errorContainer = RzPalette.AlertContainerLight,
    onErrorContainer = RzPalette.AlertLight,
    background = RzPalette.ScreenBackgroundLight,
    onBackground = RzPalette.TextPrimaryLight,
    surface = RzPalette.SurfaceLight,
    onSurface = RzPalette.TextPrimaryLight,
    surfaceVariant = RzPalette.NeutralChipLight,
    onSurfaceVariant = RzPalette.TextSecondaryLight,
    outline = RzPalette.TextTertiaryLight,
    outlineVariant = RzPalette.BorderLight,
)

val DarkColorScheme = darkColorScheme(
    primary = RzPalette.VioletOnContainerDark,
    onPrimary = Color(0xFF1B1E4A),
    primaryContainer = RzPalette.VioletContainerDark,
    onPrimaryContainer = RzPalette.VioletOnContainerDark,
    secondary = RzPalette.VioletOnContainerDark,
    onSecondary = Color(0xFF1B1E4A),
    secondaryContainer = RzPalette.VioletContainerDark,
    onSecondaryContainer = RzPalette.VioletOnContainerDark,
    tertiary = RzPalette.Lime,
    onTertiary = RzPalette.TextPrimaryLight,
    tertiaryContainer = RzPalette.Lime,
    onTertiaryContainer = RzPalette.TextPrimaryLight,
    error = RzPalette.AlertDark,
    onError = Color(0xFF3A1E1E),
    errorContainer = RzPalette.AlertContainerDark,
    onErrorContainer = RzPalette.AlertDark,
    background = RzPalette.ScreenBackgroundDark,
    onBackground = RzPalette.TextPrimaryDark,
    surface = RzPalette.SurfaceDark,
    onSurface = RzPalette.TextPrimaryDark,
    surfaceVariant = RzPalette.SurfaceVariantDark,
    onSurfaceVariant = RzPalette.TextSecondaryDark,
    outline = RzPalette.TextTertiaryDark,
    outlineVariant = RzPalette.BorderDark,
)

/**
 * Semantic colors M3's default ColorScheme has no slot for (success, warning,
 * solar, chart series). Mirrors the light/dark intent from the design file.
 */
data class RzExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val successBorder: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningBorder: Color,
    val warningText: Color,
    val solarContainer: Color,
    val solarBorder: Color,
    val solarText: Color,
    val chartObserved: Color,
    val chartGoalLine: Color,
    val chartHistoryBar: Color,
    val chartSolarProduction: Color,
)

val LightExtendedColors = RzExtendedColors(
    success = RzPalette.SuccessLight,
    onSuccess = Color.White,
    successContainer = RzPalette.SuccessContainerLight,
    onSuccessContainer = RzPalette.TextPrimaryLight,
    successBorder = RzPalette.SuccessBorderLight,
    warning = RzPalette.WarningLight,
    onWarning = RzPalette.TextPrimaryLight,
    warningContainer = RzPalette.WarningContainerLight,
    onWarningContainer = RzPalette.TextPrimaryLight,
    warningBorder = RzPalette.WarningBorderLight,
    warningText = RzPalette.WarningTextLight,
    solarContainer = RzPalette.SolarContainerLight,
    solarBorder = RzPalette.SolarBorderLight,
    solarText = RzPalette.SolarTextLight,
    chartObserved = RzPalette.ChartObserved,
    chartGoalLine = RzPalette.ChartGoalLine,
    chartHistoryBar = RzPalette.ChartHistoryBar,
    chartSolarProduction = RzPalette.ChartSolarProduction,
)

val DarkExtendedColors = RzExtendedColors(
    success = RzPalette.SuccessDark,
    onSuccess = Color(0xFF0B1F17),
    successContainer = RzPalette.SuccessContainerDark,
    onSuccessContainer = RzPalette.SuccessDark,
    successBorder = RzPalette.SuccessBorderDark,
    warning = RzPalette.WarningDark,
    onWarning = Color(0xFF2E2517),
    warningContainer = RzPalette.WarningContainerDark,
    onWarningContainer = RzPalette.WarningDark,
    warningBorder = RzPalette.WarningBorderDark,
    warningText = RzPalette.WarningDark,
    solarContainer = RzPalette.SolarContainerDark,
    solarBorder = RzPalette.SolarBorderDark,
    solarText = RzPalette.SolarTextDark,
    chartObserved = RzPalette.VioletOnContainerDark,
    chartGoalLine = RzPalette.SuccessDark,
    chartHistoryBar = RzPalette.VioletContainerDark,
    chartSolarProduction = RzPalette.Lime,
)

val LocalRzExtendedColors = staticCompositionLocalOf { LightExtendedColors }
