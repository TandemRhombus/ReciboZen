package app.recibozen.android.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity

/**
 * Whether the reader's font size is large enough that side-by-side layouts stop
 * working and have to stack.
 *
 * `Type.kt` honours the system font size deliberately, which means a row like
 * "label .......... value >" eventually cannot hold both. What happens then is
 * not a graceful squeeze: a Row gives its unweighted children their full
 * intrinsic width first, so a long value starves a weighted label down to a
 * couple of pixels and the label wraps **one character per line**. Seen on
 * Perfil at a 2x font scale, where "Cómo se lee tu medidor" rendered as a
 * vertical column of single letters.
 *
 * The threshold is a judgement, not a measurement: 1.3 is where Android's own
 * "Grande" font setting lands, and by then the pairs in this app already need
 * the full width each. Below it nothing changes, so no layout moves for a reader
 * on the default size.
 */
@Composable
@ReadOnlyComposable
fun rzPrefersStackedLayout(): Boolean = LocalDensity.current.fontScale >= STACK_THRESHOLD

private const val STACK_THRESHOLD = 1.3f
