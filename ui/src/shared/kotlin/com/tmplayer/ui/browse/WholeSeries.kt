package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesFill
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.Td
import com.tmplayer.platform.LruMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A show's page with every episode the chats hold, not only those the screen it was opened from
 * had read ([SeriesFill]): [series] on the first frame, then the show made whole as the chats
 * are searched for it, a page at a time up to [SeriesFill.MAX_PAGES]. Kept per show for the
 * run, so opening it again draws it whole at once and asks again only behind it.
 */
@Composable
fun rememberWholeSeries(series: Series): Series {
    val whole by produceState(WholeSeries.peek(series) ?: series, series) {
        val read = runCatching {
            WholeSeries.read(series, onProgress = { grown -> if (grown != value) value = grown })
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            null
        }
        if (read != null && read != value) value = read
    }
    return whole
}

/** The searches behind [rememberWholeSeries], and what they found, by show. */
object WholeSeries {
    private val kept = LruMap<String, Series>(KEPT)

    /** [series] as made whole before, when that still holds every episode it has now. */
    fun peek(series: Series): Series? =
        kept[keyOf(series)]?.takeIf { it.episodeCount >= series.episodeCount && it.key == series.key }

    /**
     * Searches the chats for [series] ([fetch], Telegram by default), within the default size
     * limits so a short sample is not taken for an episode, and folds in what belongs to it.
     * Every chat in the scope is asked the same question together, the show's own among
     * them (owner, v1.0.14, asked together since v1.0.32): a search gathers a show across
     * them, and the page is made whole from that same held set, so a show split across chats
     * opens with the same episodes from a category as from a search. While the browsing gate
     * narrows nothing and that is known to be the viewer's own answer, the chats the app has
     * loaded pages of stand for the visible ones; while the gate is unread, the show's own
     * chat alone is asked (see [TitleScope]). A chat that holds none of the show costs one
     * empty page and is asked no further.
     *
     * The other chats are asked together, not one after another, and the show grows on the
     * page as each chat answers ([onProgress] with the show as it stands), the same shape
     * [TitleMediaLoad] fills a title's copies in (owner, v1.0.24: a series he was already
     * watching showed only its own chat's episodes for as long as a line of chats was read
     * one by one, and leaving early lost everything the line had found). Each chat's finds
     * are noted into the pool as they land, so episodes opened from the page carry every
     * chat's copies, and one chat's search is bounded the title load's own way: a chat
     * Telegram never answers gives up its slot. A read a slow chat cut short is not kept,
     * so a later open asks the missing chats again.
     */
    suspend fun read(
        series: Series,
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors, topicId: Int) -> MediaPage? = ::telegram,
        onProgress: (Series) -> Unit = {},
    ): Series {
        val query = SeriesFill.queryOf(series) ?: return series
        val chatId = series.cover.chatId
        var whole = peek(series) ?: series
        // The gate awaited, but bounded, as the title load bounds it: at a cold open
        // the stored choice may not have been read yet, and an unanswered gate reads
        // as unknown, asking the show's own chat alone, unremembered. The read is
        // the shared one now ([BrowsingGate], owner, v1.0.32).
        // The chats asked are the title load's own decision ([TitleScope], v1.0.15).
        val (gate, gateKnown) = BrowsingGate.read()
        val scope = TitleScope.chats(chatId, gate, gateKnown, com.tmplayer.online.KnownMedia.all().map { it.chatId }.toSet())
        // Every chat in the scope is asked together, the show's own among them
        // (owner, v1.0.32): the own chat's full search used to finish before any
        // other chat was asked at all, so a show whose own chat answered slowly
        // (or held its episodes deep in its pages) kept every other chat's
        // episodes waiting behind it, and the seasons stood thin for as long.
        // The merges stay one at a time under the lock, each landing shown as it
        // comes, and the own chat's search is bounded like the rest: a chat
        // Telegram never answers gives up its slot instead of the whole read.
        // Each ask answers whether it ran out of time, so the kept mark below
        // rests on the asks' own returns, not a flag written across threads.
        val lock = Mutex()
        val incomplete = coroutineScope {
            val slots = Semaphore(CHATS_AT_ONCE)
            scope.map { other ->
                async {
                    slots.withPermit {
                        // The show's topic is its own chat's; another chat is asked whole.
                        val found = withTimeoutOrNull(CHAT_SEARCH_MS) { search(fetch, other, query, topicId = if (other == chatId) series.cover.topicId else 0) }
                        if (found == null) {
                            true
                        } else {
                            if (found.isNotEmpty()) {
                                com.tmplayer.online.KnownMedia.note(found)
                                val merged = lock.withLock {
                                    val next = SeriesFill.merge(whole, found)
                                    whole = next
                                    next
                                }
                                onProgress(merged)
                            }
                            false
                        }
                    }
                }
            }.awaitAll().any { it }
        }
        if ((gate != null || gateKnown) && !incomplete) kept[keyOf(series)] = whole
        return whole
    }

    /** One chat's pages for [query], up to [SeriesFill.MAX_PAGES], as [read] has always read them. */
    private suspend fun search(
        fetch: suspend (chatId: Long, query: String, cursors: MediaCursors, topicId: Int) -> MediaPage?,
        chatId: Long,
        query: String,
        topicId: Int,
    ): List<MediaItem> {
        val found = ArrayList<MediaItem>()
        var cursors = MediaCursors()
        for (page in 0 until SeriesFill.MAX_PAGES) {
            val read = fetch(chatId, query, cursors, topicId) ?: break
            found += read.items.filter { SizeFilter.matches(it.sizeBytes, SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX) }
            if (read.endReached || read.cursors.allDone) break
            cursors = read.cursors
        }
        return found
    }

    private suspend fun telegram(chatId: Long, query: String, cursors: MediaCursors, topicId: Int): MediaPage? {
        val session = Td.awaitConnectedSessionOrNull() ?: return null
        return ChatRepository(session.client).mediaPage(chatId, cursors, query = query, pageSize = SeriesFill.PAGE, topicId = topicId)
    }

    private fun keyOf(series: Series) = "${series.cover.chatId}|${series.key}"

    /** How long one chat's search may run before that chat is left out of this read. */
    private const val CHAT_SEARCH_MS = 15_000L

    /** Chats asked at once for one show: a handful in flight, never the whole line at once. */
    private const val CHATS_AT_ONCE = 6

    /** Shows kept made whole: a run's worth of pages opened. */
    private const val KEPT = 48
}
