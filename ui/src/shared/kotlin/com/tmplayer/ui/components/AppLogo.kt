package com.tmplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.ui.theme.Tone

/**
 * The player mark as a vector, in its own colours: the same shapes as Android's
 * `res/drawable/ic_logo.xml` (the yellow tile, one big white play triangle on it with the
 * navy J inside it, and the mark's line under it at rest), for the places that have no
 * Android resources (the desktop's window icon and its sign in pane).
 * Drawn with `Image`, never `Icon`, which would tint it flat.
 */
object AppLogo {

    val Mark: ImageVector by lazy {
        ImageVector.Builder(
            name = "AppLogo",
            defaultWidth = 192.dp,
            defaultHeight = 192.dp,
            viewportWidth = 1024f,
            viewportHeight = 1024f,
        ).apply {
            layer(0xFFFFD400, TILE)
            play(PLAY)
            layer(0xFF050C1C, LETTER)
            bar(TRACK_LINE, 0xFF8F6B00)
            bar(PLAYED_LINE, 0xFF050C1C)
        }.build()
    }

    private fun ImageVector.Builder.layer(argb: Long, data: String) {
        addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color(argb)))
    }

    /** The one white play triangle: filled, with a round joined stroke of its own colour over the edge, so its corners read round. */
    private fun ImageVector.Builder.play(data: String) {
        addPath(
            PathParser().parsePathString(data).toNodes(),
            fill = SolidColor(Color(0xFFFFFFFF)),
            stroke = SolidColor(Color(0xFFFFFFFF)),
            strokeLineWidth = 40f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    }

    /** One part of the mark's line under the glyph: [data] stroked in its colour, nothing filled. */
    private fun ImageVector.Builder.bar(data: String, argb: Long) {
        addPath(
            PathParser().parsePathString(data).toNodes(),
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color(argb)),
            strokeLineWidth = 30f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }

    private const val TILE =
        "M960 1002.666667H64a42.666667 42.666667 0 0 1-42.666667-42.666667V64a42.666667 42.666667 0 0 1 " +
            "42.666667-42.666667h896a42.666667 42.666667 0 0 1 42.666667 42.666667v896a42.666667 " +
            "42.666667 0 0 1-42.666667 42.666667z"
    private const val PLAY = "M340 185 L750 432 L340 680 Z"

    /** The navy J inside the play triangle, a bold sans letterform drawn as a path. */
    private const val LETTER =
        "M479.0 575.0Q441.7 575.0 421.7 558.8Q401.6 542.6 395.0 506.5L444.9 499.1Q448.0 517.7 " +
            "456.4 526.7Q464.7 535.6 479.4 535.6Q494.4 535.6 502.1 525.6Q509.9 515.5 509.9 496.8V370.8H462.0V331.4H560.0V495.6Q560.0 " +
            "533.1 538.7 554.0Q517.4 575.0 479.0 575.0Z"

    /** The mark's line at rest: its amber track, then its played half in navy over it. */
    private const val TRACK_LINE = "M300 838 L724 838"
    private const val PLAYED_LINE = "M300 838 L512 838"
}

/**
 * A word set beside the logo where this build is a preview: "Beta" in the browser build, nothing
 * in the apps. Each place that draws the logo draws [BrandBadge] beside it.
 */
val LocalBrandBadge = staticCompositionLocalOf<String?> { null }

/** [LocalBrandBadge] as a small outlined label in the accent, or nothing when the build has none. */
@Composable
fun BrandBadge(modifier: Modifier = Modifier) {
    val text = LocalBrandBadge.current ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Tone.accent,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, Tone.accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The app's name beside its mark. A brand, never translated. */
const val BRAND_NAME = "J Player"

/**
 * How large a [BrandLockup] is drawn: the mark's side, the name's type to go with it, and the
 * room between them. [Small] for bars and corners, [Medium] for sign in, the lock and setup,
 * [Large] for About and the start-up loader.
 */
class BrandSize(val mark: Dp, val name: TextUnit, val gap: Dp) {
    companion object {
        val Small = BrandSize(26.dp, 18.sp, 10.dp)
        val Medium = BrandSize(44.dp, 26.sp, 14.dp)
        val Large = BrandSize(64.dp, 36.sp, 18.dp)
    }
}

/** Whether a [BrandLockup] sets the name beside the mark, under it, or whichever fits. */
enum class BrandLayout { Auto, Row, Stacked }

/**
 * The mark and the name "J Player" together, so whoever is looking knows which app this is
 * (user, 2026-10-22): side by side when the row fits in the width given, otherwise the name
 * centred under the mark. [BrandBadge] ("Beta" in the browser) follows the name, under it when
 * even that line has no room. The name is never cut short.
 *
 * Read out once, as the name: the mark is the name's picture. [mark] replaces the drawn mark
 * (the start-up loader's [LogoMark]); it gets the side to draw at. [color] is the name's, for a
 * lockup over a picture ([com.tmplayer.ui.theme.Tone.onArt]).
 */
@Composable
fun BrandLockup(
    modifier: Modifier = Modifier,
    size: BrandSize = BrandSize.Small,
    layout: BrandLayout = BrandLayout.Auto,
    color: Color = com.tmplayer.ui.theme.Tone.text,
    badge: Boolean = true,
    mark: (@Composable (Dp) -> Unit)? = null,
) {
    val badgeText = if (badge) LocalBrandBadge.current else null
    Layout(
        contents = listOf(
            {
                if (mark != null) {
                    mark(size.mark)
                } else {
                    Image(
                        AppLogo.Mark,
                        contentDescription = null,
                        modifier = Modifier.size(size.mark).clip(RoundedCornerShape(size.mark * MARK_CORNER)),
                    )
                }
            },
            {
                Text(
                    BRAND_NAME,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = size.name,
                        lineHeight = size.name * 1.2f,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            },
            { if (badge) BrandBadge() },
        ),
        modifier = modifier.clearAndSetSemantics {
            contentDescription = listOfNotNull(BRAND_NAME, badgeText).joinToString(" ")
        },
    ) { (markM, nameM, badgeM), constraints ->
        // Measured at their own widths: the name never wraps or shortens.
        val loose = Constraints()
        val markP = markM.single().measure(loose)
        val nameP = nameM.single().measure(loose)
        val badgeP = badgeM.firstOrNull()?.measure(loose)
        val gap = size.gap.roundToPx()
        val badgeGap = (size.gap * 0.7f).roundToPx()
        val badgeW = badgeP?.let { it.width + badgeGap } ?: 0
        val rowW = markP.width + gap + nameP.width + badgeW
        val asRow = when (layout) {
            BrandLayout.Row -> true
            BrandLayout.Stacked -> false
            BrandLayout.Auto -> !constraints.hasBoundedWidth || rowW <= constraints.maxWidth
        }
        if (asRow) {
            val h = maxOf(markP.height, nameP.height, badgeP?.height ?: 0)
            layout(rowW.coerceAtLeast(constraints.minWidth), h.coerceAtLeast(constraints.minHeight)) {
                markP.placeRelative(0, (h - markP.height) / 2)
                val nameX = markP.width + gap
                nameP.placeRelative(nameX, (h - nameP.height) / 2)
                badgeP?.placeRelative(nameX + nameP.width + badgeGap, (h - badgeP.height) / 2)
            }
        } else {
            val under = (size.gap * 0.6f).roundToPx()
            val nameLine = nameP.width + badgeW
            // The badge stays on the name's line while that fits, else it takes a line of its own.
            val badgeBeside = badgeP != null && (!constraints.hasBoundedWidth || nameLine <= constraints.maxWidth)
            val badgeBelow = badgeP != null && !badgeBeside
            val lineW = if (badgeBeside) nameLine else nameP.width
            val lineH = maxOf(nameP.height, if (badgeBeside) badgeP!!.height else 0)
            val w = maxOf(markP.width, lineW, if (badgeBelow) badgeP!!.width else 0)
                .coerceAtLeast(constraints.minWidth)
            val h = markP.height + under + lineH + (if (badgeBelow) badgeGap + badgeP!!.height else 0)
            layout(w, h.coerceAtLeast(constraints.minHeight)) {
                markP.placeRelative((w - markP.width) / 2, 0)
                val lineY = markP.height + under
                val lineX = (w - lineW) / 2
                nameP.placeRelative(lineX, lineY + (lineH - nameP.height) / 2)
                if (badgeP != null) {
                    if (badgeBeside) {
                        badgeP.placeRelative(lineX + nameP.width + badgeGap, lineY + (lineH - badgeP.height) / 2)
                    } else {
                        badgeP.placeRelative((w - badgeP.width) / 2, lineY + lineH + badgeGap)
                    }
                }
            }
        }
    }
}

/** The mark's corner as a share of its side, as the desktop has always clipped it. */
private const val MARK_CORNER = TILE_CORNER
