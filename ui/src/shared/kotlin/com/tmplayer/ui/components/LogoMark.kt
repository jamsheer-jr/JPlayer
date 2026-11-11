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
 * the yellow tile, and on it one big white play triangle, filled, its corners rounded
 * (app/src/main/res/drawable/ic_logo.xml, from art/player.svg; owner, v1.0.35: the yellow mark
 * he supplied stands everywhere, drawn as original vectors in that style).
 *
 * [progress] runs a slim line under the glyph, 0 at its start and 1 at its end; read in the
 * draw pass only, so an animation of it redraws the mark and never recomposes it. The line is
 * the loader's progress treatment for this mark: the old design's playhead knob belonged to a
 * progress bar along the tile's foot, which the glyph leaves no room for, so the played part
 * of the line itself, in the glyph's white, is what moves (J Player, 2026-11-06).
 */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, progress: () -> Float = { RESTING }) {
    Canvas(modifier.size(size).semantics { contentDescription = BRAND_NAME }) {
        val u = this.size.width / VIEWPORT
        fun at(x: Float, y: Float) = Offset(x * u, y * u)

        // Rounded as [BrandLockup] clips the drawn mark, so the tile's corners do not stand
        // square inside a round clip.
        drawRoundRect(TILE, Offset.Zero, this.size, CornerRadius(this.size.width * TILE_CORNER))

        val play = Path().apply {
            moveTo(380f * u, 300f * u)
            lineTo(730f * u, 512f * u)
            lineTo(380f * u, 724f * u)
            close()
        }
        // Filled, with a round joined stroke of its own colour over the edge, so the
        // corners read round rather than cut, the one shape on every surface.
        drawPath(play, PLAY)
        drawPath(play, PLAY, style = Stroke(width = 40f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val bar = 30f * u
        val start = at(BAR_START, BAR_Y)
        val end = at(BAR_END, BAR_Y)
        drawLine(TRACK, start, end, strokeWidth = bar, cap = StrokeCap.Round)
        val headX = BAR_START + (BAR_END - BAR_START) * progress().coerceIn(0f, 1f)
        if (headX > BAR_START) drawLine(PLAY, start, at(headX, BAR_Y), strokeWidth = bar, cap = StrokeCap.Round)
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

private val TILE = Color(0xFFFFD400)

/** The progress line's unplayed part: a deep amber that reads on the yellow tile. */
private val TRACK = Color(0xFF8F6B00)

private val PLAY = Color(0xFFFFFFFF)
