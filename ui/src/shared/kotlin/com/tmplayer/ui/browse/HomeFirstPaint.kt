package com.tmplayer.ui.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether Home has drawn its first whole screen since the app started: its rows, and the
 * carousel's first picture when it has a carousel. Until then the brand loader stays over the
 * window (MainActivity on Android, Shell on the desktop), so a cold start goes from the loader straight to a finished Home
 * rather than a skeleton filling in piece by piece and the rows jumping down when the carousel
 * lands (user, 2026-10-19). [HOLD_MAX_MS] bounds the wait, so a slow chat never keeps the loader
 * up; whatever is still on its way then arrives under its own placeholders.
 *
 * Set once per process: coming back to Home later never brings the loader back.
 */
object HomeFirstPaint {
    var ready by mutableStateOf(false)
        private set

    fun markReady() {
        if (!ready) ready = true
    }

    /**
     * The longest the loader waits for Home after Telegram is reached. Six seconds
     * was too short on a large account (owner, v1.0.32): the hold gave way while
     * the first build was still running, and the light skeleton page that stood
     * under it read as a blank white loading page on every reopen. The branded
     * loader now covers the build for as long as a build can honestly take; the
     * bound still stands, so a Home that never settles gives way all the same.
     */
    const val HOLD_MAX_MS = 20_000L
}
