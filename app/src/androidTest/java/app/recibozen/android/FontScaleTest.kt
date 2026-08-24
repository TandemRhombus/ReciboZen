package app.recibozen.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.recibozen.android.core.components.RzPrimaryButton
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layouts against a large system font size.
 *
 * `Type.kt` uses `FontFamily.Default` on purpose so the app honours the font
 * size the household picked on their phone, and its own comment says the
 * consequence needs testing. It had never been tested, and [RzPrimaryButton]
 * did not survive it: it was pinned to `height(52.dp)` while
 * [RzSecondaryButton] and RzTextButton both used a minimum, so every primary
 * action in the app clipped a label that grew.
 *
 * Instrumented, because measuring a real layout needs a real composition:
 *
 * ```
 * ./gradlew connectedDebugAndroidTest
 * ```
 *
 * **What is and is not the property under test.** A first version of this file
 * asserted that a button gets taller at a large font scale. That is false and
 * the tests correctly failed: `labelLarge` has a 16sp line height, so even at
 * twice the font size a one-line label still fits inside the 48-52dp floor and
 * the button rightly does not move. Growing is not the requirement -- *not
 * clipping* is. The two are only the same once the text actually outgrows the
 * floor, which is what the wrapping tests below arrange, and which is exactly
 * where a fixed height fails.
 */
@RunWith(AndroidJUnit4::class)
class FontScaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Well past the largest size Android's own accessibility settings offer. */
    private val hugeFontScale = 2f

    private val shortLabel = "Guardar"

    /** Real labels from the meter-swap dialog. These are the ones that wrap. */
    private val longPrimaryLabel = "Guardar el cambio de medidor"
    private val longSecondaryLabel = "Todavía no tengo esos datos"

    /** Narrow enough that a long label cannot stay on one line as the text grows. */
    private val narrow = Modifier.width(200.dp)

    // --- The fix must be invisible at ordinary font sizes ---

    @Test
    fun aShortLabelLeavesThePrimaryButtonExactlyWhereItWas() {
        // The floor already fits a one-line label at twice the font size, so the
        // button holds its height. This is the half of the change that must not
        // be visible: swapping a fixed height for a minimum moves nothing until
        // something actually needs the room.
        val (atDefault, atHuge) = measureAtBothScales { RzPrimaryButton(shortLabel, {}) }

        assertEquals("el botón no debe moverse con una etiqueta que ya cabía", atDefault, atHuge)
    }

    @Test
    fun aShortLabelLeavesTheSecondaryButtonExactlyWhereItWas() {
        val (atDefault, atHuge) = measureAtBothScales { RzSecondaryButton(shortLabel, {}) }

        assertEquals(atDefault, atHuge)
    }

    // --- And it must give way once the label genuinely outgrows the floor ---

    @Test
    fun aPrimaryLabelThatOutgrowsTheFloorIsGivenTheRoom() {
        // This is the test that fails against `height(52.dp)`: the label needs
        // more than one line at the large size and the button has to supply it
        // instead of cutting the second line off.
        val (atDefault, atHuge) = measureAtBothScales {
            RzPrimaryButton(longPrimaryLabel, {}, modifier = narrow)
        }

        assertTrue(
            "la etiqueta larga necesita más alto y el botón debe dárselo " +
                "(normal=${atDefault}px, grande=${atHuge}px)",
            atHuge > atDefault,
        )
    }

    @Test
    fun aSecondaryLabelThatOutgrowsTheFloorIsGivenTheRoom() {
        val (atDefault, atHuge) = measureAtBothScales {
            RzSecondaryButton(longSecondaryLabel, {}, modifier = narrow)
        }

        assertTrue("normal=${atDefault}px, grande=${atHuge}px", atHuge > atDefault)
    }

    @Test
    fun theOnboardingFooterGrowsOnBothOfItsButtons() {
        // The footer used to impose its own `height(52.dp)` on top of the
        // buttons', which would put the clipping back even after the component
        // was fixed. Both controls are checked because only one of them was
        // wrong in the component and both were wrong at this call site.
        var scale by mutableFloatStateOf(1f)
        composeRule.setContent {
            AtFontScale(scale) {
                Column(modifier = narrow.fillMaxWidth()) {
                    RzSecondaryButton(longSecondaryLabel, {}, modifier = Modifier.fillMaxWidth())
                    RzPrimaryButton(longPrimaryLabel, {}, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        val primaryAtDefault = composeRule.button(longPrimaryLabel).height()
        val secondaryAtDefault = composeRule.button(longSecondaryLabel).height()
        scale = hugeFontScale
        composeRule.waitForIdle()

        assertTrue("primario", composeRule.button(longPrimaryLabel).height() > primaryAtDefault)
        assertTrue("secundario", composeRule.button(longSecondaryLabel).height() > secondaryAtDefault)
    }

    /** Renders [content] at the default font size and again at [hugeFontScale]. */
    private fun measureAtBothScales(content: @Composable () -> Unit): Pair<Int, Int> {
        var scale by mutableFloatStateOf(1f)
        composeRule.setContent { AtFontScale(scale) { content() } }

        val atDefault = composeRule.onlyButton().height()
        scale = hugeFontScale
        composeRule.waitForIdle()
        return atDefault to composeRule.onlyButton().height()
    }

    /**
     * Overrides only the font scale, keeping the device's real pixel density.
     * Scaling both would grow the dp floor along with the text and hide the very
     * mismatch these tests exist to catch.
     */
    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current.density
        val scaled = remember(density, scale) { Density(density = density, fontScale = scale) }
        CompositionLocalProvider(LocalDensity provides scaled) {
            ReciboZenTheme { content() }
        }
    }

    private fun ComposeContentTestRule.onlyButton() = onNode(hasClickAction())

    private fun ComposeContentTestRule.button(label: String) = onNode(hasClickAction() and hasText(label))

    private fun SemanticsNodeInteraction.height(): Int = fetchSemanticsNode().size.height
}
