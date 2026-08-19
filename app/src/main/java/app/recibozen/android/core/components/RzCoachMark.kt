package app.recibozen.android.core.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.recibozen.android.R
import app.recibozen.android.core.designsystem.RzExtraShapes
import app.recibozen.android.core.designsystem.RzSpacing
import kotlinx.coroutines.delay

/**
 * One step of the guided tour.
 *
 * [anchorId] must match the id passed to [Modifier.rzCoachAnchor]. [route] is the
 * destination where that element lives: the tour navigates there before
 * highlighting it, so a step can point at something on another screen.
 */
data class RzCoachStep(
    val anchorId: String,
    val titleRes: Int,
    val bodyRes: Int,
    val route: String? = null,
)

/** Screen positions of the elements the tour can highlight, plus how to reveal them. */
class RzCoachMarkState internal constructor() {
    internal val anchors = mutableStateMapOf<String, Rect>()
    private val requesters = mutableMapOf<String, BringIntoViewRequester>()
    private val seekers = mutableMapOf<String, suspend () -> Unit>()

    internal fun put(id: String, bounds: Rect) {
        anchors[id] = bounds
    }

    internal fun register(id: String, requester: BringIntoViewRequester) {
        requesters[id] = requester
    }

    internal fun unregister(id: String) {
        requesters.remove(id)
        anchors.remove(id)
    }

    internal fun registerSeeker(id: String, seeker: suspend () -> Unit) {
        seekers[id] = seeker
    }

    internal fun unregisterSeeker(id: String) {
        seekers.remove(id)
    }

    /**
     * Brings an anchor that is not composed yet into composition.
     *
     * A `LazyColumn` does not compose items outside the viewport, so an element
     * far down the list has no modifier, no requester and no bounds — it does
     * not exist as far as the tour is concerned. This is why the DAC step
     * silently skipped itself: it lives near the bottom of Inicio.
     * [BringIntoViewRequester] cannot fix that, because there is nothing to
     * bring into view yet. The host screen registers how to scroll its list
     * instead.
     */
    internal suspend fun seekAnchor(id: String) {
        seekers[id]?.invoke()
    }

    /** Fine-tunes the position once the element is composed. */
    internal suspend fun revealAnchor(id: String) {
        requesters[id]?.bringIntoView()
    }
}

@Composable
fun rememberRzCoachMarkState(): RzCoachMarkState = remember { RzCoachMarkState() }

/**
 * Lets any screen register a tour target without threading the state through
 * every navigation route and composable signature. Screens that are never part
 * of a tour simply write into the default instance and nothing happens.
 */
val LocalRzCoachMarkState = staticCompositionLocalOf { RzCoachMarkState() }

/**
 * Modifier that registers this element as a tour target and lets the tour
 * scroll it into view. Purely observational: it does not change layout, so
 * adding it cannot move anything on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.rzCoachAnchor(id: String): Modifier {
    val state = LocalRzCoachMarkState.current
    val requester = remember(id) { BringIntoViewRequester() }
    DisposableEffect(state, id) {
        state.register(id, requester)
        // Leaving the screen must clear the anchor, otherwise the tour would
        // keep pointing at coordinates that no longer exist.
        onDispose { state.unregister(id) }
    }
    return this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { coordinates -> state.put(id, coordinates.boundsInRoot()) }
}

/**
 * Declares that [anchorIds] live inside [listState]'s lazy list, so the tour can
 * scroll down until they compose.
 *
 * Without this, any target below the first screenful is invisible to the tour:
 * the anchor modifier is never composed, so the step times out and is skipped.
 */
@Composable
fun RzCoachLazyAnchors(listState: LazyListState, vararg anchorIds: String) {
    val state = LocalRzCoachMarkState.current
    val ids = remember(anchorIds) { anchorIds.toList() }
    DisposableEffect(state, listState, ids) {
        ids.forEach { id ->
            state.registerSeeker(id) {
                var item = 0
                // Walk the list one item at a time and stop the moment the
                // anchor registers itself. Stepping beats jumping to a fixed
                // index: the item order on Inicio changes with the data mode.
                while (state.anchors[id] == null && item < listState.layoutInfo.totalItemsCount) {
                    listState.scrollToItem(item)
                    // Layout — and therefore onGloballyPositioned — happens on
                    // the next frames, not when scrollToItem returns.
                    withFrameNanos { }
                    withFrameNanos { }
                    item++
                }
            }
        }
        onDispose { ids.forEach(state::unregisterSeeker) }
    }
}

/**
 * Dimming overlay with a cut-out around the current step's target.
 *
 * Navigates to the step's screen, scrolls the element into view and only then
 * draws the hole. Consumes every touch so the highlighted control cannot be
 * pressed while it is being explained, and the card is placed on the side with
 * more free space so it never covers the target.
 */
@Composable
fun RzCoachMarkOverlay(
    state: RzCoachMarkState,
    steps: List<RzCoachStep>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the scrim is drawn right now. The tour stays composed even when
     * it is not: unmounting it used to throw away which step the user was on,
     * so any moment spent outside a tab root restarted the tour from zero.
     */
    visible: Boolean = true,
) {
    if (steps.isEmpty()) return
    var index by remember(steps) { mutableIntStateOf(0) }
    val step = steps.getOrNull(index) ?: return
    val navigate by rememberUpdatedState(onNavigate)
    val finish by rememberUpdatedState(onFinish)

    // Take the step to its screen, wait for the element, and if it is below the
    // fold scroll the list until it composes. Only give up after both.
    LaunchedEffect(index, step.anchorId) {
        step.route?.let { route -> if (route != currentRoute) navigate(route) }
        if (!state.awaitAnchor(step.anchorId, ARRIVE_TIMEOUT_MS)) {
            state.seekAnchor(step.anchorId)
            if (!state.awaitAnchor(step.anchorId, SEEK_TIMEOUT_MS)) {
                if (index == steps.lastIndex) finish() else index++
                return@LaunchedEffect
            }
        }
        state.revealAnchor(step.anchorId)
        // Let the scroll settle before measuring where the hole goes.
        delay(SETTLE_MS)
    }

    if (!visible) return

    val target = state.anchors[step.anchorId]
    val density = LocalDensity.current
    val padding = with(density) { 8.dp.toPx() }
    val corner = with(density) { 16.dp.toPx() }
    val highlight = target?.let {
        Rect(it.left - padding, it.top - padding, it.right + padding, it.bottom + padding)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(index) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        val scrim = Color.Black.copy(alpha = 0.72f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = 0.99f)
                .clearAndSetSemantics { }
                .drawWithCutout(scrim, highlight, corner),
        )

        // Place the card away from the target: below it when the target sits in
        // the upper half of the screen, above it otherwise. Measured against the
        // real height instead of a fixed 420 dp, which put the card on top of
        // the target on short screens.
        val halfHeight = with(density) { maxHeight.toPx() } / 2f
        val cardAtBottom = highlight == null || highlight.center.y < halfHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(if (cardAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                // The scrim covers the whole screen on purpose, but the card must
                // clear the status bar and the system navigation bar: with
                // 3-button navigation the buttons sat on top of "Siguiente".
                .safeDrawingPadding()
                .padding(RzSpacing.screenHorizontal),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RzExtraShapes.cardSmall)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(RzSpacing.lg),
            ) {
                Text(
                    stringResource(R.string.coach_progress, index + 1, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(step.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                Text(
                    stringResource(step.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.xs),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RzTextButton(
                        text = stringResource(R.string.coach_skip),
                        onClick = finish,
                        modifier = Modifier.weight(1f),
                    )
                    RzPrimaryButton(
                        text = stringResource(
                            if (index == steps.lastIndex) R.string.coach_done else R.string.coach_next,
                        ),
                        onClick = { if (index == steps.lastIndex) finish() else index++ },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Polls until [id] reports bounds. Returns false if it never does. */
private suspend fun RzCoachMarkState.awaitAnchor(id: String, timeoutMs: Long): Boolean {
    var waited = 0L
    while (anchors[id] == null && waited < timeoutMs) {
        delay(POLL_MS)
        waited += POLL_MS
    }
    return anchors[id] != null
}

private const val POLL_MS = 60L

/** Enough for the destination screen to appear and lay out its first screenful. */
private const val ARRIVE_TIMEOUT_MS = 900L

/** Extra margin once the list is being scrolled looking for the element. */
private const val SEEK_TIMEOUT_MS = 1_500L
private const val SETTLE_MS = 220L

/**
 * Fills the screen with [scrim] and punches a rounded hole over [hole].
 * Needs an offscreen layer (`graphicsLayer(alpha = 0.99f)` on the caller) for
 * [BlendMode.Clear] to cut instead of painting black. A null [hole] simply
 * dims everything, which is what happens while a step is still travelling to
 * its screen.
 */
private fun Modifier.drawWithCutout(scrim: Color, hole: Rect?, corner: Float): Modifier =
    drawBehind {
        drawRect(scrim)
        if (hole == null) return@drawBehind
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(hole.left, hole.top),
            size = Size(hole.width, hole.height),
            cornerRadius = CornerRadius(corner, corner),
            blendMode = BlendMode.Clear,
        )
    }
