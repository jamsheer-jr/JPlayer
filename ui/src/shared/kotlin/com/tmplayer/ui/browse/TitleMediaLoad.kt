package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.SeriesFill
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.Td
import com.tmplayer.online.KnownMedia
import com.tmplayer.platform.LruMap
import kotlinx.coroutines.CancellationException

/**
 * Opening a title loads its whole set where it stands (owner, v1.0.14): the detail page's
 * copies and episode guide are drawn from [KnownMedia], the pool every listing feeds, so a
 * title opened from a Home row or a chat's first page used to show only the copies that page
 * happened to have listed, and an episode another chat held stood as missing until the viewer
 * had visited the Chats tab and read that chat. This asks for the rest at the open itself.
 *
 * Non-blocking by shape: the page draws with what is known, each chat's finds are noted into
 * the pool as they land, and the sections that read the pool (the copies list, the seasons)
 * watch its version and redraw. Once per title per run, like [WholeSeries]' kept set, so
 * going back and forth over a page does not search again behind it.
 */
@Composable
fun rememberTitleMediaLoad(item: MediaItem) {
    LaunchedEffect(item.id) {
        runCatching { TitleMediaLoad.load(item) }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
        }
    }
}

/** The searches behind [rememberTitleMediaLoad]. */
object TitleMediaLoad {
    private val loaded = LruMap<String, Unit>(KEPT)

    /**
     * Searches for [item]'s title across the chats the viewer browses and notes what is found
     * into [KnownMedia]. The set asked is the browsing gate itself
     * ([ChatGate.awaitBrowsing]: the chats the viewer chose, met with the kids lock), never an
     * account wide search and never the search setting: with the viewer's chosen chats the
     * only narrowing, a title's copies are asked for in exactly those chats even with global
     * search off. While the gate narrows nothing and that is known to be the viewer's own
     * answer, the chats the app has listed pages of stand for the visible ones; while the
     * gate is still unread, only the title's own chat is asked, and the load is not
     * remembered, so the next open asks again with the real scope (see [TitleScope]). The
     * title's own chat goes first either way, as [WholeSeries] reads.
     *
     * The words asked are the title as the file's own name reads it: a chat search matches the
     * posted names, not the viewer's corrections, which are for the eye only.
     */
    suspend fun load(
        item: MediaItem,
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors) -> MediaPage? = ::telegram,
    ) {
        if (loaded[item.id] != null) return
        val gate = ChatGate.awaitBrowsing()
        val gateKnown = ChatGate.awaitBrowsingKnown()
        if (gate != null || gateKnown) loaded[item.id] = Unit
        val query = SeriesShelf.episodeOf(item).title.trim()
        if (query.isBlank()) return
        val chats = TitleScope.chats(item.chatId, gate, gateKnown, KnownMedia.all().map { it.chatId }.toSet())
        for (chatId in chats) {
            val found = search(fetch, chatId, query)
            if (found.isNotEmpty()) KnownMedia.note(found)
        }
    }

    /**
     * One chat's pages for [query], up to [SeriesFill.MAX_PAGES]. Everything the search
     * returns is noted, with none of [WholeSeries]' size filter: the pool is a record of what
     * the app has listed, and the copies list and the episode guide do their own narrowing
     * (the same file, the same show) over it, so a small copy is not kept out of the versions
     * by a search default.
     */
    private suspend fun search(
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors) -> MediaPage?,
        chatId: Long,
        query: String,
    ): List<MediaItem> {
        val found = ArrayList<MediaItem>()
        var cursors = MediaCursors()
        for (page in 0 until SeriesFill.MAX_PAGES) {
            val read = fetch(chatId, query, cursors) ?: break
            found += read.items
            if (read.endReached || read.cursors.allDone) break
            cursors = read.cursors
        }
        return found
    }

    private suspend fun telegram(chatId: Long, query: String, cursors: MediaCursors): MediaPage? {
        val session = Td.awaitConnectedSessionOrNull() ?: return null
        return ChatRepository(session.client).mediaPage(chatId, cursors, query = query, pageSize = SeriesFill.PAGE)
    }

    /** Titles asked after in a run: a session's worth of pages opened. */
    private const val KEPT = 96
}

/**
 * Which chats a title's load may ask (owner, v1.0.15): the scope decision behind
 * [TitleMediaLoad] and [WholeSeries], kept pure so a test pins it.
 *
 * The browsing gate where it narrows. Where it narrows nothing and that is known to be
 * the viewer's own answer ([gateKnown]: they chose every chat, or the lock is off and
 * no choice narrows), the chats the app has listed stand for the visible ones, as
 * v1.0.14's item 39 settled. Where the gate is unread ([gateKnown] false), only the
 * title's own chat: an unread gate answers with the process default, which reads as
 * "no narrowing", and a load that trusted it asked every chat the pool knew, so a
 * viewer who had turned the global scope off still had titles loaded from chats they
 * never selected. The title's own chat always leads: it is the one surface the viewer
 * is actually looking at, whatever the scope says.
 */
internal object TitleScope {
    /** The chats to ask, the title's own first, the rest in id order. */
    fun chats(ownChatId: Long, gate: Set<Long>?, gateKnown: Boolean, listedChatIds: Set<Long>): List<Long> {
        val rest = when {
            gate != null -> gate
            gateKnown -> listedChatIds
            else -> emptySet()
        }
        return listOf(ownChatId) + (rest - ownChatId).sorted()
    }
}
