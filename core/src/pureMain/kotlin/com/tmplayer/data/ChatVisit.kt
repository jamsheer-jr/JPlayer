package com.tmplayer.data

/**
 * New videos since the last visit (owner, v1.0.15, item 11e).
 *
 * Telegram's own unread count on a chat counts every message, pictures, text and all, and
 * the chat rows already show it. This item asks for new VIDEOS, so the count is worked out
 * from videos themselves: each chat remembers the newest video post seen when it was last
 * visited, and a chat's new count is how many of its videos, among the ones the app already
 * holds for it (its Home row, or a listing already loaded), are newer than that mark.
 *
 * That is the honest shape of it: counting new videos in every chat of a large list would
 * mean a history read per chat, which the scale rules forbid, so chats the app holds no
 * videos for show no video count, only Telegram's unread badge as before. Visiting a chat
 * moves its mark to the newest video the visit loaded, which clears its count.
 */
object ChatVisit {

    /** How many chats' marks are kept; a chat not visited in that long falls off the end. */
    const val MAX_CHATS = 500

    private const val SEP = '\u001F'

    /** New videos among [videos] for the chat whose mark is [mark]: posts newer than it. */
    fun newCount(videos: List<MediaItem>, mark: Long): Int {
        if (mark <= 0L) return 0
        val seen = HashSet<Long>()
        var count = 0
        for (video in videos) {
            if (video.messageId > mark && seen.add(video.messageId)) count++
        }
        return count
    }

    /** [marks] with [chatId]'s mark moved to [newestVideoMessageId], never backwards. */
    fun visited(marks: Map<Long, Long>, chatId: Long, newestVideoMessageId: Long): Map<Long, Long> {
        if (newestVideoMessageId <= 0L) return marks
        val current = marks[chatId] ?: 0L
        if (newestVideoMessageId <= current) return marks
        val out = LinkedHashMap<Long, Long>(marks)
        out.remove(chatId)
        out[chatId] = newestVideoMessageId
        while (out.size > MAX_CHATS) out.remove(out.keys.first())
        return out
    }

    /** The newest video post among [videos], or 0 when there are none. */
    fun newestOf(videos: List<MediaItem>): Long = videos.maxOfOrNull { it.messageId } ?: 0L

    fun encode(marks: Map<Long, Long>): String =
        marks.entries.joinToString("\n") { (chatId, mark) -> "$chatId$SEP$mark" }

    /** The marks in [raw]; a line that does not read loses only itself. */
    fun decode(raw: String?): Map<Long, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Long, Long>()
        for (line in raw.split('\n')) {
            val parts = line.split(SEP)
            if (parts.size != 2) continue
            val chatId = parts[0].toLongOrNull() ?: continue
            val mark = parts[1].toLongOrNull() ?: continue
            if (mark <= 0L) continue
            out[chatId] = mark
            if (out.size >= MAX_CHATS) break
        }
        return out
    }
}
