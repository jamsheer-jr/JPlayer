package com.tmplayer.data

/**
 * The last Home's inputs, kept on disk so a cold start paints real rows at once
 * (owner, v1.0.20/21): the rows themselves are rebuilt by the one build from these
 * seeded lists, exactly as from live answers, and live answers replace the seeds as
 * they land. What is kept is the shape [com.tmplayer.data.ResumeRecord] proved: a
 * video's chat, post, file and name facts, enough to stand a tile and to open the
 * player. Posters resolve from the thumbnail file id the same way live tiles ask.
 *
 * The lists are bounded (a snapshot is a head start, not an archive): the newest
 * sixty, forty per chat for the first twelve chats Home read, and two hundred and
 * forty deepened catalog videos in all. A line that does not parse is skipped, a
 * snapshot of the wrong version is no snapshot, and nothing here is trusted past
 * the build's own gates: a chat locked or removed since is filtered by the same
 * inputs path live answers take.
 */
object HomeSnapshot {

    const val RECENT_MAX = 60
    const val LOADED_CHATS_MAX = 12
    const val LOADED_PER_CHAT_MAX = 40
    const val CATALOG_MAX = 240

    private const val SEP = '\u001f'
    private const val VERSION = "v1"
    private const val FIELDS = 13

    /** What a decode gives back: the three seeded lists, loaded and catalog by chat. */
    data class Saved(
        val recent: List<MediaItem>,
        val loaded: Map<Long, List<MediaItem>>,
        val catalog: Map<Long, List<MediaItem>>,
    )

    fun encode(
        recent: List<MediaItem>,
        loaded: Map<Long, List<MediaItem>>,
        catalog: Map<Long, List<MediaItem>>,
    ): String {
        val out = StringBuilder(VERSION)
        for (item in recent.take(RECENT_MAX)) out.append('\n').append(line("R", item))
        var chats = 0
        for ((_, items) in loaded) {
            if (chats >= LOADED_CHATS_MAX) break
            chats++
            for (item in items.take(LOADED_PER_CHAT_MAX)) out.append('\n').append(line("L", item))
        }
        var left = CATALOG_MAX
        for ((_, items) in catalog) {
            if (left <= 0) break
            for (item in items.take(left)) {
                out.append('\n').append(line("C", item))
                left--
            }
        }
        return out.toString()
    }

    fun decode(raw: String?): Saved? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.split('\n')
        if (lines.firstOrNull()?.trim() != VERSION) return null
        val recent = ArrayList<MediaItem>()
        val loaded = LinkedHashMap<Long, MutableList<MediaItem>>()
        val catalog = LinkedHashMap<Long, MutableList<MediaItem>>()
        for (text in lines.drop(1)) {
            val item = item(text) ?: continue
            when (text.firstOrNull()) {
                'R' -> if (recent.size < RECENT_MAX) recent += item
                'L' -> {
                    val list = loaded.getOrPut(item.chatId) { ArrayList() }
                    if (list.size < LOADED_PER_CHAT_MAX && loaded.size <= LOADED_CHATS_MAX) list += item
                }
                'C' -> {
                    val list = catalog.getOrPut(item.chatId) { ArrayList() }
                    if (catalog.values.sumOf { it.size } < CATALOG_MAX) list += item
                }
            }
        }
        if (recent.isEmpty() && loaded.isEmpty() && catalog.isEmpty()) return null
        return Saved(recent, loaded, catalog)
    }

    private fun line(kind: String, item: MediaItem): String = listOf(
        kind,
        item.chatId.toString(),
        item.messageId.toString(),
        item.fileId.toString(),
        item.thumbnailFileId.toString(),
        item.title,
        item.sizeBytes.toString(),
        item.durationSec.toString(),
        item.date.toString(),
        item.width.toString(),
        item.height.toString(),
        item.fileName,
        item.caption,
    ).joinToString(SEP.toString())

    private fun item(text: String): MediaItem? {
        val fields = text.split(SEP)
        if (fields.size != FIELDS) return null
        val chatId = fields[1].toLongOrNull() ?: return null
        val messageId = fields[2].toLongOrNull() ?: return null
        val fileId = fields[3].toIntOrNull() ?: return null
        val thumbnailFileId = fields[4].toIntOrNull() ?: return null
        val sizeBytes = fields[6].toLongOrNull() ?: return null
        val durationSec = fields[7].toIntOrNull() ?: return null
        val date = fields[8].toIntOrNull() ?: return null
        val width = fields[9].toIntOrNull() ?: return null
        val height = fields[10].toIntOrNull() ?: return null
        return MediaItem(
            chatId = chatId,
            messageId = messageId,
            fileId = fileId,
            title = fields[5],
            sizeBytes = sizeBytes,
            durationSec = durationSec,
            mimeType = "",
            thumbnailFileId = thumbnailFileId,
            miniThumbnail = null,
            date = date,
            fileName = fields[11],
            caption = fields[12],
            width = width,
            height = height,
        )
    }
}
