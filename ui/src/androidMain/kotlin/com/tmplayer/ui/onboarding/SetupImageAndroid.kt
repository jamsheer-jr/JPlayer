package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tmplayer.data.DeviceForm
import com.tmplayer.ui.components.deviceForm
import com.tmplayer.ui.theme.LocalDarkTheme

/**
 * Where the Android tour pictures would come from. They are the app's own drawables, which this
 * library cannot name, so the app can hand over the lookup when it starts. No build hands one
 * over today, so the tour pages on Android draw as text lines with no picture (item 12 left that
 * mechanism as it was). The setup's pictures are not here at all any more: every setup step draws
 * its own (see ChoicePicture in SetupScreen.kt), and no setup screenshot ships in the app.
 */
object SetupImages {
    /** The drawable for a tour [page] on a television or a phone, light or dark; 0 when it has none. */
    @Volatile
    var tour: (page: TourPage, tv: Boolean, dark: Boolean) -> Int = { _, _, _ -> 0 }
}

/**
 * A tour page's picture: a cropped, marked shot from the television or the phone, light or dark to
 * match. Shown whole ([ContentScale.Fit]), since the badges drawn over it are placed by fractions
 * of the picture.
 */
@Composable
internal fun TourShot(page: TourPage, modifier: Modifier = Modifier) {
    val tv = deviceForm() == DeviceForm.Tv
    val id = SetupImages.tour(page, tv, LocalDarkTheme.current)
    if (id == 0) return
    Image(
        painter = painterResource(id),
        // The numbered lines beside it say what it shows.
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** The size of a tour page's picture, for laying out the badges over it; null when it has none. */
@Composable
internal fun tourShotSize(page: TourPage): androidx.compose.ui.geometry.Size? {
    val tv = deviceForm() == DeviceForm.Tv
    val id = SetupImages.tour(page, tv, LocalDarkTheme.current)
    if (id == 0) return null
    return painterResource(id).intrinsicSize
}
