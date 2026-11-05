package com.tmplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.tan

/**
 * The wait while J Player connects, the same on every platform and kept quiet (user, 2026-10-22:
 * "a splash screen if needed and nothing else, or just a simple rotating loader"): the mark's
 * navy ground, which the system's splash hands over to with no flash, the brand lockup arriving
 * on it once (see [OpeningLockup]), then after [SPINNER_MS] one small white spinner under the
 * lockup, and nothing else. [label] is what a screen reader says for it. A warm start is over
 * before anything shows.
 *
 * [sweepAt] is kept for tests that draw a frame at once: any value shows the spinner without
 * waiting. [below] is room under the spinner for the help once the wait has gone on too long; it
 * starts under the spinner and never moves it.
 */
@Composable
fun BrandLoader(
    label: String,
    modifier: Modifier = Modifier,
    sweepAt: Float? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val form = deviceForm()
    val size = LoaderSizes.of(form)

    val immediate = sweepAt != null
    var spinner by remember { mutableStateOf(immediate) }
    if (!immediate) {
        LaunchedEffect(Unit) {
            delay(SPINNER_MS)
            spinner = true
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(loaderGround())
            .padding(horizontal = size.side),
        contentAlignment = Alignment.Center,
    ) {
        // The opening lockup (J Player, 2026-11-06): the neon glyph and the name, centred,
        // arriving once as the app opens, over the spinner's place. The spinner keeps its own
        // box under it, reserved from the first frame, so nothing moves when it shows.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OpeningLockup()
            androidx.compose.foundation.layout.Spacer(Modifier.size(size.gap))
            Box(Modifier.size(size.spinner), contentAlignment = Alignment.Center) {
                if (spinner) {
                    val spinnerModifier = Modifier.size(size.spinner).semantics { contentDescription = label }
                    val ink = Color.White.copy(alpha = SPINNER_ALPHA)
                    if (sweepAt != null) {
                        // A test's still frame: an endless spin never lets a test's clock settle.
                        androidx.compose.material3.CircularProgressIndicator(
                            progress = { sweepAt.coerceIn(0f, 1f) },
                            color = ink,
                            trackColor = Color.Transparent,
                            strokeWidth = size.stroke,
                            modifier = spinnerModifier,
                        )
                    } else {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = ink,
                            trackColor = Color.Transparent,
                            strokeWidth = size.stroke,
                            modifier = spinnerModifier,
                        )
                    }
                }
            }
            below()
        }
    }
}

/**
 * The brand lockup as the app opens: the glyph and the name fade and settle in over about a
 * second, once, while the real loading goes on underneath (nothing here delays anything: the
 * loader leaves when the loading does, animation or not). With less motion asked for, the
 * lockup is simply there, still. The name is white on the loader's navy ground, as in the
 * lockup artwork; the glyph is [LogoMark], whose own glow is part of the drawing.
 */
@Composable
private fun OpeningLockup() {
    val reduced = rememberLessMotion()
    val entry = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) entry.animateTo(1f, tween(OPEN_MS, easing = Motion.Standard))
    }
    BrandLockup(
        size = BrandSize.Large,
        layout = BrandLayout.Row,
        color = Color.White,
        modifier = Modifier.graphicsLayer {
            alpha = entry.value
            val scale = 0.94f + 0.06f * entry.value
            scaleX = scale
            scaleY = scale
        },
        mark = { side -> LogoMark(side) },
    )
}

/**
 * [BrandLoader] over the window while [label] is not null, fading out over whatever is under it
 * once it is: the sign in pane or the home screen is already drawn underneath, so the loader
 * thinning to nothing over it is the cross-fade, and the one leaving never jumps. Put it last in a
 * `Box` that fills the window, or give it a `zIndex`, so it is drawn above its siblings.
 */
@Composable
fun BrandLoaderHandOff(
    label: String?,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val showing = label != null
    // The last label shown, so the fading loader keeps its words rather than going blank. A plain
    // holder: it is only read in the composition that wrote it.
    val held = remember { arrayOf(label) }
    if (label != null) held[0] = label
    val alpha = remember { Animatable(if (showing) 1f else 0f) }
    LaunchedEffect(showing) {
        if (showing) alpha.snapTo(1f) else alpha.animateTo(0f, tween(EXIT_MS, easing = Motion.Standard))
    }
    val shown = held[0] ?: return
    if (!showing && alpha.value == 0f) return
    BrandLoader(
        label = shown,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        below = below,
    )
}

/**
 * The loader's ground: the mark's own dark navy, in every theme (J Player, 2026-11-06). The
 * system's splash paints the same navy (the v31 splash theme), so splash and loader read as
 * one sequence: navy, then the neon glyph arriving on navy, then the app. The lockup and the
 * spinner are drawn for this ground (white name, white spinner).
 */
@Composable
private fun loaderGround(): Color = Color(0xFF050C1C)

/** The loader's figures for each kind of device: the spinner a little larger on a television, seen from across the room. */
private class LoaderSizes(
    val spinner: androidx.compose.ui.unit.Dp,
    val stroke: androidx.compose.ui.unit.Dp,
    val gap: androidx.compose.ui.unit.Dp,
    val side: androidx.compose.ui.unit.Dp,
) {
    companion object {
        private val Phone = LoaderSizes(24.dp, 2.dp, 24.dp, 16.dp)
        private val Tv = LoaderSizes(32.dp, 2.5.dp, 30.dp, 72.dp)
        private val Desktop = LoaderSizes(28.dp, 2.dp, 30.dp, 48.dp)

        fun of(form: DeviceForm): LoaderSizes = when (form) {
            DeviceForm.Phone -> Phone
            DeviceForm.Tv -> Tv
            DeviceForm.Desktop -> Desktop
        }
    }
}

private const val EXIT_MS = 420

/** How long the opening lockup takes to arrive: about a second, and never a wait added to loading. */
private const val OPEN_MS = 900

/** White, but quiet: the spinner is the only thing on the screen and should not shout. */
private const val SPINNER_ALPHA = 0.6f

/** How long the ground stays bare: a warm start is over before the spinner would show. */
private const val SPINNER_MS = 500L
