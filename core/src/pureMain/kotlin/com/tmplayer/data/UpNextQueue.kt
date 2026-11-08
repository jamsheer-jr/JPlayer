package com.tmplayer.data

/**
 * The Up Next queue (owner, v1.0.15, item 33): videos the viewer lined up from any card,
 * played in order after whatever is playing, across chats. "Play next" puts a video at the
 * head; "Add to queue" puts it at the end. The queue is the device's, not a profile's: it
 * is one list for whoever is holding the phone, and it lives in the running app (see the
 * app module's holder), so it survives screens and players within the run and is gone
 * after a restart, which the item accepts ("session-persistent at least within the run").
 *
 * The list is capped: a queue is a plan for the evening, not a second library, and the cap
 * keeps a slip of the finger from lining up a whole chat.
 */
object UpNextQueue {

    /** How many videos the queue holds at most; adding past it drops the add, not the old. */
    const val MAX_ITEMS = 50

    /** [queue] with [item] next: at the head, and not twice (it moves up instead). */
    fun playNext(queue: List<MediaItem>, item: MediaItem): List<MediaItem> =
        listOf(item) + queue.filterNot { it.id == item.id }

    /** [queue] with [item] added at the end; already there, it keeps its place. */
    fun add(queue: List<MediaItem>, item: MediaItem): List<MediaItem> =
        if (queue.any { it.id == item.id } || queue.size >= MAX_ITEMS) queue else queue + item

    /** The head of [queue] and what is left of it: what plays next, and the queue after. */
    fun takeNext(queue: List<MediaItem>): Pair<MediaItem?, List<MediaItem>> =
        if (queue.isEmpty()) null to emptyList() else queue.first() to queue.drop(1)

    /** [queue] without the video [id]. */
    fun remove(queue: List<MediaItem>, id: String): List<MediaItem> =
        queue.filterNot { it.id == id }

    /** [queue] with the video at [from] moved to [to]; out-of-range moves change nothing. */
    fun move(queue: List<MediaItem>, from: Int, to: Int): List<MediaItem> {
        if (from !in queue.indices || to !in queue.indices || from == to) return queue
        val out = queue.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        return out
    }
}
