package com.tmplayer.data

/**
 * The viewer's watching, added up: how long, how much finished, what is still on the go, and
 * the genres and chats that fill it. Worked out from the Watched list and Continue watching,
 * folded the way Home folds them ([HomeRows.foldWatched], [HomeRows.foldCopies]), so a film
 * kept at two sizes counts once at its own length, and a part watched title adds the place
 * reached, not its whole length. Genres come from the caller, which reads the metadata the
 * app already holds: a title with no match simply has none.
 */
data class WatchStats(
    /** Time actually spent: finished titles at their length, part watched at the place reached. */
    val watchedMs: Long,
    val finishedCount: Int,
    val goingCount: Int,
    /** Genre name to how many finished titles carry it, fullest first. */
    val topGenres: List<Pair<String, Int>>,
    /** Chat name to how many titles came from it, fullest first. */
    val topChats: List<Pair<String, Int>>,
) {
    val isEmpty: Boolean get() = finishedCount == 0 && goingCount == 0

    companion object {

        const val TOP = 3

        fun of(
            watched: List<WatchedRecord>,
            continuing: List<ResumeRecord>,
            genresOf: (MediaItem) -> List<String>,
        ): WatchStats {
            val finished = HomeRows.foldWatched(watched)
            val going = HomeRows.foldCopies(continuing)
            val spent = finished.sumOf { it.durationSec.toLong().coerceAtLeast(0) * 1_000L } +
                going.sumOf { it.positionMs.coerceAtLeast(0) }
            val genres = HashMap<String, Int>()
            finished.forEach { record ->
                genresOf(record.toMediaItem()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    .forEach { genre -> genres[genre] = (genres[genre] ?: 0) + 1 }
            }
            val chats = HashMap<String, Int>()
            finished.forEach { record -> record.chatTitle.trim().takeIf { it.isNotEmpty() }?.let { chats[it] = (chats[it] ?: 0) + 1 } }
            going.forEach { record -> record.chatTitle.trim().takeIf { it.isNotEmpty() }?.let { chats[it] = (chats[it] ?: 0) + 1 } }
            return WatchStats(
                watchedMs = spent,
                finishedCount = finished.size,
                goingCount = going.size,
                topGenres = genres.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(TOP).map { it.key to it.value },
                topChats = chats.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(TOP).map { it.key to it.value },
            )
        }
    }
}
