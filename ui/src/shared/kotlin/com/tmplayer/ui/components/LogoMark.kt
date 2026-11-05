package com.tmplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * The app's mark, drawn rather than loaded, so every platform has the same one and it can move:
 * the dark navy tile, and on it the neon cyan play glyph, an outer play triangle in outline
 * with a smaller play inside it, as in the launcher icon
 * (app/src/main/res/drawable/ic_logo.xml, from art/player.svg). A wide, faint stroke under the
 * outer triangle is its glow.
 *
 * [progress] runs a slim line under the glyph, 0 at its start and 1 at its end; read in the
 * draw pass only, so an animation of it redraws the mark and never recomposes it. The line is
 * the loader's progress treatment for this mark: the old design's playhead knob belonged to a
 * progress bar along the tile's foot, which the glyph leaves no room for, so the played part
 * of the line itself, in the glyph's cyan, is what moves (J Player, 2026-11-06).
 */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, progress: () -> Float = { RESTING }) {
    Canvas(modifier.size(size).semantics { contentDescription = BRAND_NAME }) {
        val u = this.size.width / VIEWPORT
        fun at(x: Float, y: Float) = Offset(x * u, y * u)

        // Rounded as [BrandLockup] clips the drawn mark, so the tile's corners do not stand
        // square inside a round clip.
        drawRoundRect(TILE, Offset.Zero, this.size, CornerRadius(this.size.width * TILE_CORNER))

        val outer = Path().apply {
            moveTo(350f * u, 270f * u)
            lineTo(740f * u, 492f * u)
            lineTo(350f * u, 714f * u)
            close()
        }
        // The glow first: the same triangle, wider and faint, so the crisp stroke sits in it.
        drawPath(outer, NEON.copy(alpha = 0.28f), style = Stroke(width = 110f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(outer, NEON, style = Stroke(width = 52f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val inner = Path().apply {
            moveTo(462f * u, 378f * u)
            lineTo(648f * u, 492f * u)
            lineTo(462f * u, 606f * u)
            close()
        }
        drawPath(inner, NEON, style = Stroke(width = 34f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val bar = 30f * u
        val start = at(BAR_START, BAR_Y)
        val end = at(BAR_END, BAR_Y)
        drawLine(TRACK, start, end, strokeWidth = bar, cap = StrokeCap.Round)
        val headX = BAR_START + (BAR_END - BAR_START) * progress().coerceIn(0f, 1f)
        if (headX > BAR_START) drawLine(NEON, start, at(headX, BAR_Y), strokeWidth = bar, cap = StrokeCap.Round)
    }
}

/** Where the progress line rests when nothing moves it. */
const val RESTING = 0.5f

private const val VIEWPORT = 1024f

/** The tile's corner as a share of its side: the lockup's (`MARK_CORNER`) and [AppMark]'s. */
const val TILE_CORNER = 0.22f

private const val BAR_START = 300f
private const val BAR_END = 724f
private const val BAR_Y = 838f

private val TILE = Color(0xFF050C1C)
private val TRACK = Color(0xFF1D3448)
private val NEON = Color(0xFF22E6FF)
