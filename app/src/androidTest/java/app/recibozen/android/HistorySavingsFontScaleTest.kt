package app.recibozen.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.recibozen.android.core.calculation.PeriodSaving
import app.recibozen.android.core.calculation.RealizedSavings
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.feature.history.HistoryScreen
import app.recibozen.android.feature.history.HistoryUiState
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The savings card against a large system font size.
 *
 * Each row is a label ("Jun–Ago 26 contra Jun–Jul 25") next to an amount, the
 * exact shape [ProfileRowFontScaleTest] caught collapsing into a column of
 * single letters: a Row measures its unweighted children first, so a label
 * without weight gets whatever is left.
 */
@RunWith(AndroidJUnit4::class)
class HistorySavingsFontScaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rowLabel = "Jun–Ago 26 contra Jun–Jul 25"

    @Test
    fun aSavingsRowLabelIsNotStarvedByItsAmountAtALargeFontSize() {
        val savings = RealizedSavings(
            trackingStartEpochDay = LocalDate.of(2026, 5, 20).toEpochDay(),
            periods = listOf(
                PeriodSaving(
                    label = "Jun–Ago 26",
                    baselineLabel = "Jun–Jul 25",
                    periodEndEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
                    kwh = 1_350,
                    baselineKwh = 1_720,
                    savedKwh = 370,
                    // A wide amount, so the row is as hostile as it gets.
                    savedMxn = 12_345,
                ),
            ),
            periodsWithoutBaseline = 0,
        )
        composeRule.setContent {
            AtFontScale(2f) {
                HistoryScreen(
                    uiState = HistoryUiState(savings = savings),
                    onProfileClick = {},
                    onHouseholdClick = {},
                    onUnitChange = {},
                    onRegisterReading = {},
                    onImportBill = {},
                )
            }
        }

        val label = composeRule
            .onNodeWithText(rowLabel, useUnmergedTree = true)
            .fetchSemanticsNode()
        val screenWidth = composeRule.onRoot().fetchSemanticsNode().size.width

        assertTrue(
            "la etiqueta quedó estrangulada por el monto: ${label.size.width}px de $screenWidth px",
            label.size.width > screenWidth / 2,
        )

        // The other half, and the one the label assertion cannot see. Take the
        // weight off the label column and the label keeps its width -- it is the
        // amount that gets measured last and left with nothing. A row whose label
        // survives and whose number vanished is not a working savings row.
        val amount = composeRule
            .onNodeWithText("−$12345", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            "el monto quedó estrangulado por la etiqueta: ${amount.size.width}px de $screenWidth px",
            amount.size.width > screenWidth / 8,
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
