package com.tmplayer.data

/**
 * A chat's holdings by quality (owner, v1.0.15, item 34): how many 1080p, how many 720p,
 * and the total size, for the chat page's header line, so the best chat for a title is
 * visible at a glance.
 *
 * Computed from the videos the page has already loaded, and only those: the summary never
 * asks Telegram for more, so a chat of twenty thousand videos summarises the pages the
 * viewer has actually read, and the header says so ("in the videos loaded so far") until
 * the listing is complete. The quality reading is the app's own ([MediaFacts]: the stated
 * picture size first, then the quality in the file's name); a video neither can read counts
 * towards the size and the video count but stands in no quality's line.
 */
object ChatQualitySummary {

    /** One quality's count, in the order the header shows them: sharpest first. */
    data class Line(val label: String, val count: Int)

    data class Summary(
        val lines: List<Line>,
        /** Every listed video's size added up, the ones with no quality reading included. */
        val totalBytes: Long,
        /** How many videos were summarised: the loaded ones the caller handed over. */
        val videos: Int,
    ) {
        val isEmpty: Boolean get() = videos == 0
    }

    /** The order qualities stand in, sharpest first; a label outside it sorts after them. */
    private val ORDER = listOf("4K", "2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "SD")

    fun of(items: List<MediaItem>): Summary {
        if (items.isEmpty()) return Summary(emptyList(), 0L, 0)
        val counts = LinkedHashMap<String, Int>()
        var total = 0L
        for (item in items) {
            total += item.sizeBytes.coerceAtLeast(0L)
            val label = MediaFacts.of(item).resolution ?: continue
            counts[label] = (counts[label] ?: 0) + 1
        }
        val lines = counts.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { entry ->
                ORDER.indexOf(entry.key).let { if (it < 0) ORDER.size else it }
            }.thenBy { it.key })
            .map { Line(it.key, it.value) }
        return Summary(lines, total, items.size)
    }

    /** "1080p × 12, 720p × 30": the counts alone, for the header line to finish as it likes. */
    fun countsText(summary: Summary): String =
        summary.lines.joinToString(", ") { "${it.label} × ${it.count}" }
}
