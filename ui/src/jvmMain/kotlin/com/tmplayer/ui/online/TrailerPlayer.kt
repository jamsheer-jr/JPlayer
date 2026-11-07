package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer

/** The desktop has no embedded player: a trailer opens in the browser, which plays it well. */
@Composable
internal fun rememberInAppTrailers(): Boolean = false

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun TrailerPlayer(
    trailer: MetaTrailer,
    onClose: () -> Unit,
    onOpenElsewhere: () -> Unit,
    fallback: @Composable () -> Unit,
) = Unit

/** The desktop reads as unmetered, as its connectivity does everywhere. */
@Composable
internal fun rememberMeteredConnection(): Boolean = false

/** No embedded player on the desktop, so no hero preview: [com.tmplayer.online.HeroPreview] already says no. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun HeroTrailerPlayer(
    trailer: MetaTrailer,
    muted: Boolean,
    onFailed: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) = Unit
