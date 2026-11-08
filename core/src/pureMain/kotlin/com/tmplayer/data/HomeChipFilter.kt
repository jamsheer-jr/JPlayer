package com.tmplayer.data

/**
 * The phone Home's chips (owner, v1.0.15, items 16 and 29): All, Shows, Movies, and a
 * New & Hot chip of new arrivals. A chip does not open another page: it turns Home
 * itself into that shape, banner and rows alike, and tapping the same chip again (the
 * All state) brings the full Home back.
 */
enum class HomeChip {
    All,
    Shows,
    Movies,
    NewHot,
}

/**
 * [HomeRows.build]'s rows cut down to one [HomeChip]'s shape, purely: every row is
 * either kept whole (All), filtered tile by tile to the chip's kind, or left out.
 * Nothing is invented here: a filtered row stands only where the full Home's row
 * already held a tile of that kind, and a row the chats cannot fill in that shape
 * is simply absent, the way an empty shelf is absent from Home itself.
 *
 * The banner follows on its own: Home's featured pages are picked from the Recent
 * row, and the Recent row handed to it here is already the filtered one, so a
 * Movies home's banner is movies and a Shows home's banner is shows.
 *
 * A film is a file no episode reading claims ([SeriesShelf.episodeOf]); a show is a
 * folded show tile or an episode file standing on its own. Continue watching and
 * Watch later are cut by the same reading, the first by each record's own file,
 * the second by the record's own show flag.
 */
object HomeChipFilter {

    /**
     * [rows] as [chip] shows them. [HomeChip.All] is the rows themselves.
     *
     * [movieChats] and [showChats] are the channels the viewer assigned to the kind
     * in Settings (item 3, v1.0.16): while a chip's assignment is non-empty, the
     * chip's pool is first cut to those chats' entries, and the kind reading then
     * classifies inside the pool as it always has. An empty assignment is today's
     * behaviour exactly: the kind alone, read from the names and matches.
     */
    fun apply(
        rows: List<HomeRow>,
        chip: HomeChip,
        movieChats: Set<Long> = emptySet(),
        showChats: Set<Long> = emptySet(),
    ): List<HomeRow> {
        if (chip == HomeChip.All) return rows
        val pool = when (chip) {
            HomeChip.Movies -> movieChats
            HomeChip.Shows -> showChats
            else -> emptySet()
        }
        val pooled = if (pool.isEmpty()) rows else rows.mapNotNull { row -> inPool(row, pool) }
        return pooled.mapNotNull { row -> cut(row, chip) }
    }

    /** [row] with only [pool]'s chats standing in it; null where none of them do. */
    private fun inPool(row: HomeRow, pool: Set<Long>): HomeRow? = when (row) {
        is HomeRow.Continue -> {
            val kept = (row.records + row.more).filter { it.chatId in pool }
            if (kept.isEmpty()) null else HomeRow.Continue(kept.take(HomeRows.LIMIT), kept.drop(HomeRows.LIMIT))
        }
        is HomeRow.WatchLater -> {
            val kept = (row.records + row.more).filter { it.chatId in pool }
            if (kept.isEmpty()) null else HomeRow.WatchLater(kept.take(HomeRows.LIMIT), kept.drop(HomeRows.LIMIT))
        }
        is HomeRow.NewEpisodes -> {
            val kept = row.episodes.filter { it.item.chatId in pool }
            if (kept.isEmpty()) null else HomeRow.NewEpisodes(kept)
        }
        is HomeRow.Chat -> row.takeIf { it.chatId in pool }
        is HomeRow.Recent -> {
            val entries = row.entries.filter { entry -> inPool(entry, pool) }
            if (entries.isEmpty() && row.loaded) null
            else row.copy(entries = entries, chatIds = row.chatIds.filter { it in pool })
        }
        is HomeRow.Catalog -> {
            val all = row.all.filter { entry -> inPool(entry, pool) }
            if (all.isEmpty()) null else row.copy(entries = all.take(HomeRows.LIMIT), all = all)
        }
    }

    /** Whether [entry] stands on a video from one of [pool]'s chats: a show tile where any of its episodes does. */
    private fun inPool(entry: ShelfEntry, pool: Set<Long>): Boolean = when (entry) {
        is ShelfEntry.File -> entry.item.chatId in pool
        is ShelfEntry.Show -> entry.series.episodes.any { it.item.chatId in pool }
    }

    private fun cut(row: HomeRow, chip: HomeChip): HomeRow? = when (row) {
        is HomeRow.Continue -> when (chip) {
            HomeChip.NewHot -> null
            else -> {
                val kept = (row.records + row.more).filter { record -> isKind(record.toMediaItem(), chip) }
                if (kept.isEmpty()) null
                else HomeRow.Continue(kept.take(HomeRows.LIMIT), kept.drop(HomeRows.LIMIT))
            }
        }
        is HomeRow.WatchLater -> when (chip) {
            HomeChip.NewHot -> null
            else -> {
                val kept = (row.records + row.more).filter { record -> record.isShow == (chip == HomeChip.Shows) }
                if (kept.isEmpty()) null
                else HomeRow.WatchLater(kept.take(HomeRows.LIMIT), kept.drop(HomeRows.LIMIT))
            }
        }
        is HomeRow.NewEpisodes -> row.takeIf { chip == HomeChip.Shows || chip == HomeChip.NewHot }
        is HomeRow.Chat -> when (chip) {
            HomeChip.NewHot -> null
            else -> {
                val entries = row.entries.filter { entry -> isKind(entry, chip) }
                val more = row.more.filter { item -> isKind(item, chip) }
                if (entries.isEmpty()) null
                else row.copy(entries = entries, more = more, fetched = entries.size + more.size)
            }
        }
        is HomeRow.Recent -> when (chip) {
            HomeChip.NewHot -> row.takeUnless { it.noneChosen }
            else -> {
                if (row.noneChosen) null
                else {
                    val entries = row.entries.filter { entry -> isKind(entry, chip) }
                    if (entries.isEmpty() && row.loaded) null else row.copy(entries = entries)
                }
            }
        }
        is HomeRow.Catalog -> when (chip) {
            HomeChip.NewHot -> row.takeIf { it.kind in NEW_HOT_KINDS }
            else -> {
                val all = row.all.filter { entry -> isKind(entry, chip) }
                if (all.isEmpty()) null
                else row.copy(entries = all.take(HomeRows.LIMIT), all = all)
            }
        }
    }

    /** Whether [item] is the chip's kind: an episode file is a show, any other file a film. */
    private fun isKind(item: MediaItem, chip: HomeChip): Boolean {
        val episode = SeriesShelf.episodeOf(item).episode != null
        return when (chip) {
            HomeChip.Shows -> episode
            HomeChip.Movies -> !episode
            else -> true
        }
    }

    /** Whether [entry] is the chip's kind: a show tile is a show, a file follows its own reading. */
    private fun isKind(entry: ShelfEntry, chip: HomeChip): Boolean = when (entry) {
        is ShelfEntry.Show -> chip == HomeChip.Shows
        is ShelfEntry.File -> isKind(entry.item, chip)
    }

    /**
     * The catalog kinds that are new arrivals by their own building: the titles TMDB
     * says are trending, and what arrived in the chats in the last seven days. The
     * Recent row and the New episodes row, kept whole beside them, are the rest of
     * the New & Hot home: nothing older is dressed up as new.
     */
    private val NEW_HOT_KINDS = setOf("trending", "weekly")
}
