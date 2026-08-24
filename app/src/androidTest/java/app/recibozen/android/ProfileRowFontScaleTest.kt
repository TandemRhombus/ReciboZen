package app.recibozen.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.feature.settings.ProfileScreen
import app.recibozen.android.feature.settings.ProfileUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Perfil rows against a large system font size.
 *
 * This guards the worst thing the emulator turned up, and the one no amount of
 * reading the code would have shown: at a 2x font scale the row
 * "Cómo se lee tu medidor" rendered as a **vertical column of single letters**.
 *
 * The cause is a plain Compose layout rule rather than anything specific to this
 * screen. A Row gives its unweighted children their full intrinsic width before
 * the weighted ones get anything, and the trailing value carried no weight. Once
 * the value was long enough -- which a large font size does on its own -- the
 * label was left a couple of pixels wide and broke mid-word, then mid-character.
 *
 * The assertion is on the label's measured width, because that is the quantity
 * that collapsed. It is expressed as a fraction of the screen so it does not
 * become a screenshot test tied to one device.
 */
@RunWith(AndroidJUnit4::class)
class ProfileRowFontScaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** A row carrying both a long label and a long value: the pathological pair. */
    private val longRowLabel = "Cómo se lee tu medidor"

    @Test
    fun aRowLabelIsNotStarvedByItsValueAtALargeFontSize() {
        composeRule.setContent {
            AtFontScale(2f) {
                ProfileScreen(
                    uiState = ProfileUiState(userName = "Sergio", userInitial = "S"),
                    onBack = {},
                    onNavigateAppearance = {},
                    onNavigateSupport = {},
                    onNavigateNotifications = {},
                    onNavigatePremium = {},
                    onNavigateDonate = {},
                    onNavigateServices = {},
                    onUpdateProfile = { _, _, _, _ -> },
                    onUpdateService = { _, _, _, _, _ -> },
                )
            }
        }

        // useUnmergedTree, or this measures the wrong thing entirely. The row is
        // clickable, so it merges its descendants' semantics and a merged lookup
        // returns the row -- full width, always passing, whatever the label did.
        // The first version of this test did exactly that and could not fail.
        val label = composeRule
            .onNodeWithText(longRowLabel, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().size.width

        // Starved, this measured a couple of characters. Half the screen is well
        // clear of that and well under what a healthy row occupies, so it catches
        // the collapse without pinning the design.
        assertTrue(
            "la etiqueta quedó estrangulada por el valor: ${label.size.width}px de $screenWidth px",
            label.size.width > screenWidth / 2,
        )
    }

    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density = density, fontScale = scale)) {
            ReciboZenTheme { content() }
        }
    }
}
