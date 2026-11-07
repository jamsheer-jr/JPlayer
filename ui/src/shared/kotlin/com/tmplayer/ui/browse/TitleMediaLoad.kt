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
     * search off. While the gate narrows nothing, the chats the app has listed pages of stand
     * for the visible ones, the title's own chat first either way, as [WholeSeries] reads.
     *
     * The words asked are the title as the file's own name reads it: a chat search matches the
     * posted names, not the viewer's corrections, which are for the eye only.
     */
    suspend fun load(
        item: MediaItem,
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors) -> MediaPage? = ::telegram,
    ) {
        if (loaded[item.id] != null) return
        loaded[item.id] = Unit
        val query = SeriesShelf.episodeOf(item).title.trim()
        if (query.isBlank()) return
        val gate = ChatGate.awaitBrowsing()
        val chats = gate ?: (KnownMedia.all().map { it.chatId }.toSet() + item.chatId)
        for (chatId in listOf(item.chatId) + (chats - item.chatId).sorted()) {
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
