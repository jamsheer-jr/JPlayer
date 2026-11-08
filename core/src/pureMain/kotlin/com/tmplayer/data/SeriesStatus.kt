package com.tmplayer.data

/**
 * Where a series stands with the viewer (owner, v1.0.15, item 9d), the way the TV Time app
 * tracks shows: Watching, Completed or Dropped. The viewer sets a series' status from its
 * page or its card menu; the three lists live on the Profile page.
 *
 * One record per series, stored per viewer profile (the store key is the profile's, as the
 * collections' is). A record names its series by the chat it was found in and the key the
 * series view groups it under ([Series.key]), and carries the title and one episode's post
 * so the Profile lists can draw the series without loading its chat.
 */
enum class SeriesStatus {
    Watching,
    Completed,
    Dropped,
}

/** One series' standing, as stored. */
data class SeriesStatusRecord(
    /** [SeriesStatuses.keyOf]: the chat and the series' group key. */
    val key: String,
    val title: String,
    val chatId: Long,
    /** One episode's post, for the list's picture and its way into the title's page. */
    val messageId: Long,
    val fileId: Int,
    val status: SeriesStatus,
    /** When the status was set, in milliseconds. */
    val at: Long,
) {
    /** The episode the record stands for, for a card or a detail page. */
    fun toMediaItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = title,
        sizeBytes = 0,
        durationSec = 0,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = title,
        caption = "",
    )
}

object SeriesStatuses {

    /** How many series one profile's lists hold at most; the oldest set goes first. */
    const val MAX_RECORDS = 300

    private const val SEP = '\u001F'

    /** A series' record key: its chat and its group key, which no other chat's series shares. */
    fun keyOf(chatId: Long, seriesKey: String): String = "$chatId|$seriesKey"

    /** The record for [series] in [chatId], standing on its first episode's post. */
    fun recordOf(chatId: Long, series: Series, status: SeriesStatus, at: Long): SeriesStatusRecord? {
        val episode = series.episodes.firstOrNull() ?: return null
        val item = episode.item
        return SeriesStatusRecord(
            key = keyOf(chatId, series.key),
            title = series.title,
            chatId = chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            status = status,
            at = at,
        )
    }

    /** [records] with [record] set: an existing record for its series is replaced. */
    fun with(records: List<SeriesStatusRecord>, record: SeriesStatusRecord): List<SeriesStatusRecord> =
        (records.filterNot { it.key == record.key } + record)
            .sortedByDescending { it.at }
            .take(MAX_RECORDS)

    /** [records] with the series [key] cleared of any status. */
    fun without(records: List<SeriesStatusRecord>, key: String): List<SeriesStatusRecord> =
        records.filterNot { it.key == key }

    /** The records standing at [status], newest set first. */
    fun byStatus(records: List<SeriesStatusRecord>, status: SeriesStatus): List<SeriesStatusRecord> =
        records.filter { it.status == status }

    /** The status of the series [key], or null when the viewer never set one. */
    fun statusOf(records: List<SeriesStatusRecord>, key: String): SeriesStatus? =
        records.firstOrNull { it.key == key }?.status

    fun encode(records: List<SeriesStatusRecord>): String = records.joinToString("\n") { record ->
        listOf(
            record.key,
            record.title.replace(SEP, ' '),
            record.chatId.toString(),
            record.messageId.toString(),
            record.fileId.toString(),
            when (record.status) {
                SeriesStatus.Watching -> "w"
                SeriesStatus.Completed -> "c"
                SeriesStatus.Dropped -> "d"
            },
            record.at.toString(),
        ).joinToString(SEP.toString())
    }

    /**
     * The records in [raw], the stored text. A line of the wrong shape, a status letter
     * nothing knows, or ids that do not read lose only their own line; a repeated key keeps
     * its first line, as [encode] writes newest first.
     */
    fun decode(raw: String?): List<SeriesStatusRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<SeriesStatusRecord>()
        for (line in raw.split('\n')) {
            if (out.size >= MAX_RECORDS) break
            val parts = line.split(SEP)
            if (parts.size != 7) continue
            val key = parts[0]
            if (key.isBlank() || !seen.add(key)) continue
            val chatId = parts[2].toLongOrNull() ?: continue
            val messageId = parts[3].toLongOrNull() ?: continue
            val fileId = parts[4].toIntOrNull() ?: continue
            val status = when (parts[5]) {
                "w" -> SeriesStatus.Watching
                "c" -> SeriesStatus.Completed
                "d" -> SeriesStatus.Dropped
                else -> continue
            }
            out += SeriesStatusRecord(
                key = key,
                title = parts[1],
                chatId = chatId,
                messageId = messageId,
                fileId = fileId,
                status = status,
                at = parts[6].toLongOrNull() ?: 0L,
            )
        }
        return out
    }
}
