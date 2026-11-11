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
 * the yellow tile, and on it one big white play triangle, filled, its corners rounded, with the
 * navy J inside it (app/src/main/res/drawable/ic_logo.xml, from art/player.svg; owner, v1.0.36:
 * his refined mark, the yellow tile and white triangle of v1.0.35 with the J in the triangle and
 * the line under the glyph, drawn as original vectors in that design).
 *
 * [progress] runs a slim line under the glyph, 0 at its start and 1 at its end; read in the
 * draw pass only, so an animation of it redraws the mark and never recomposes it. The line is
 * the loader's progress treatment for this mark: the old design's playhead knob belonged to a
 * progress bar along the tile's foot, which the glyph leaves no room for, so the played part
 * of the line itself is what moves (J Player, 2026-11-06).
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
            moveTo(340f * u, 185f * u)
            lineTo(750f * u, 432f * u)
            lineTo(340f * u, 680f * u)
            close()
        }
        // Filled, with a round joined stroke of its own colour over the edge, so the
        // corners read round rather than cut, the one shape on every surface.
        drawPath(play, PLAY)
        drawPath(play, PLAY, style = Stroke(width = 40f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // The J inside the triangle, in the brand navy: a bold sans letterform, its
        // quadratic outline restated as cubics so this canvas needs no curve conversion.
        val letter = Path().apply {
            moveTo(479.0f * u, 575.0f * u)
            cubicTo(454.1f * u, 575.0f * u, 435.0f * u, 569.6f * u, 421.7f * u, 558.8f * u)
            cubicTo(408.3f * u, 548.0f * u, 399.4f * u, 530.6f * u, 395.0f * u, 506.5f * u)
            lineTo(444.9f * u, 499.1f * u)
            cubicTo(447.0f * u, 511.5f * u, 450.8f * u, 520.7f * u, 456.4f * u, 526.7f * u)
            cubicTo(461.9f * u, 532.6f * u, 469.6f * u, 535.6f * u, 479.4f * u, 535.6f * u)
            cubicTo(489.4f * u, 535.6f * u, 497.0f * u, 532.3f * u, 502.1f * u, 525.6f * u)
            cubicTo(507.3f * u, 518.9f * u, 509.9f * u, 509.3f * u, 509.9f * u, 496.8f * u)
            lineTo(509.9f * u, 370.8f * u)
            lineTo(462.0f * u, 370.8f * u)
            lineTo(462.0f * u, 331.4f * u)
            lineTo(560.0f * u, 331.4f * u)
            lineTo(560.0f * u, 495.6f * u)
            cubicTo(560.0f * u, 520.6f * u, 552.9f * u, 540.1f * u, 538.7f * u, 554.0f * u)
            cubicTo(524.5f * u, 568.0f * u, 504.6f * u, 575.0f * u, 479.0f * u, 575.0f * u)
            close()
        }
        drawPath(letter, LINE)

        val bar = 30f * u
        val start = at(BAR_START, BAR_Y)
        val end = at(BAR_END, BAR_Y)
        drawLine(TRACK, start, end, strokeWidth = bar, cap = StrokeCap.Round)
        val headX = BAR_START + (BAR_END - BAR_START) * progress().coerceIn(0f, 1f)
        if (headX > BAR_START) drawLine(LINE, start, at(headX, BAR_Y), strokeWidth = bar, cap = StrokeCap.Round)
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

/**
 * The brand navy: the J inside the glyph, and the progress line's played part.
 * The played part was the glyph's white when the mark went yellow (v1.0.35), and
 * white on the yellow tile washed out to nothing at the line's slim width, so the
 * loader's line read as missing (owner, v1.0.36). Navy is the one brand colour
 * that stands on the tile and against the amber track alike.
 */
private val LINE = Color(0xFF050C1C)
