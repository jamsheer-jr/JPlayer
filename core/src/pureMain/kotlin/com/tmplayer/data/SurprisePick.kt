package com.tmplayer.data

import kotlin.random.Random

/**
 * What "Surprise me" on Home can offer: a film nobody has started, or the next episode of a
 * show that has not been started either, drawn from the entries Home already has (the starred
 * chats' rows, folded by [SeriesShelf]). Anything part watched is left out on purpose: it
 * already has a home in Continue watching, and a surprise that lands mid film is no surprise.
 * Extras (a trailer, a teaser, a sample) never stand for their film here.
 */
object SurprisePick {

    /** Every video a press could land on, in the entries' own order, each title once. */
    fun candidates(
        entries: List<ShelfEntry>,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
    ): List<MediaItem> {
        val seen = HashSet<String>()
        val out = ArrayList<MediaItem>()
        fun offer(item: MediaItem) {
            if (seen.add(SettingsStore.progressKey(item.chatId, item.messageId))) out += item
        }
        for (entry in entries) {
            when (entry) {
                is ShelfEntry.File -> {
                    val item = entry.item
                    if (item.extra != null) continue
                    val copies = listOf(item) + item.versions
                    if (copies.any(finished) || copies.any { position(it) > 0f }) continue
                    offer(item)
                }
                is ShelfEntry.Show -> {
                    val progress = SeriesShelf.progress(entry.series, finished, position)
                    if (progress.finished) continue
                    val next = progress.next ?: continue
                    // The next episode already started belongs to Continue watching, not here.
                    if (next.copies.any { !finished(it) && position(it) > 0f }) continue
                    offer(next.item)
                }
            }
        }
        return out
    }

    /** One of the [candidates], or null when there is nothing unwatched to offer. */
    fun pick(
        entries: List<ShelfEntry>,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
        random: Random = Random,
    ): MediaItem? {
        val all = candidates(entries, finished, position)
        return if (all.isEmpty()) null else all[random.nextInt(all.size)]
    }
}
