package com.tmplayer.ui.browse

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Asks for the Live row's posts to be read again (owner, v1.0.15).
 *
 * The row read the channel's posts once, when it was first composed, and nothing ever
 * asked again: a stream the owner posted in the channel, or one he deleted there, waited
 * until the row happened to be built anew (the app restarted, or the row scrolled far
 * enough away to leave the list and come back) before it showed. The chat list's own
 * arrival refresh never reached it either: it reloads the chats, not the row's posts,
 * and it is gated at two minutes besides (BrowseViewModel.refreshIfStale).
 *
 * The asks are the moments a change can have happened: arriving at the Chats page, the
 * app coming back to the front (the owner posts in Telegram, then switches back), and
 * the viewer's own refreshes: a pull, the bar's Refresh, the Home button pressed again.
 * An ask says whether a hand made it: a forced ask (the viewer's own refresh) is always
 * honoured; an arrival ask is spaced out and settled by the row itself (see
 * [LiveStreamsRow]), so arriving often never becomes a hammering. Either way an ask is
 * the one read the row has always made: the newest posts of the one built-in channel.
 */
internal object LiveRefresh {
    private val _asked = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    /** True for an ask a hand made (a pull, a Refresh, the Home button again). */
    val asked: SharedFlow<Boolean> = _asked.asSharedFlow()

    fun ask(forced: Boolean = false) {
        _asked.tryEmit(forced)
    }
}
