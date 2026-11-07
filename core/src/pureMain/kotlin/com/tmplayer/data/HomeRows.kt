package com.tmplayer.data

/**
 * One row on Home: what the viewer was part way through, the newest videos in a starred chat, or
 * the newest videos across the starred chats together.
 *
 * A chat row and the Recent row can be on screen before their videos are: [loaded] false is a row
 * still asking Telegram, which draws as a placeholder strip so the rows under it do not jump when
 * it fills.
 */
sealed interface HomeRow {
    /** Stable while the row loads and refills, so a lazy list can key on it. */
    val key: String

    /** What a row's "See all" has to say: how many videos there are and whether that is all of them. */
    val total: Int get() = 0

    /** True when the count is only what has been fetched so far, so "12+" rather than "12". */
    val totalAtLeast: Boolean get() = false

    /** Whether the row leaves anything out, which is the only time it offers "See all". */
    val hasMore: Boolean get() = false

    /**
     * @param more the records past the row's [HomeRows.LIMIT], newest first, for the See all
     *   tile's picture and count.
     */
    data class Continue(
        val records: List<ResumeRecord>,
        val more: List<ResumeRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "continue"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * @param more the chat's fetched videos that the row does not show, newest first.
     * @param complete false when Telegram has more of this chat than was fetched for the row.
     * @param fetched how many of the chat's videos were fetched, within the size limits.
     */
    data class Chat(
        val chatId: Long,
        val title: String,
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
        val more: List<MediaItem> = emptyList(),
        val complete: Boolean = true,
        val fetched: Int = 0,
    ) : HomeRow {
        override val key: String get() = "chat-$chatId"
        override val total: Int get() = fetched
        override val totalAtLeast: Boolean get() = !complete
        override val hasMore: Boolean get() = loaded && (more.isNotEmpty() || !complete)
    }

    /**
     * The videos the viewer put aside for later, newest first.
     *
     * @param more the records past the row's [HomeRows.LIMIT], for the See all count.
     */
    data class WatchLater(
        val records: List<WatchLaterRecord>,
        val more: List<WatchLaterRecord> = emptyList(),
    ) : HomeRow {
        override val key: String get() = "watch-later"
        override val total: Int get() = records.size + more.size
        override val hasMore: Boolean get() = more.isNotEmpty()
    }

    /**
     * Episodes of the shows the viewer is watching that arrived since they last watched them (see
     * [com.tmplayer.data.NewEpisodes]). Only ever built with something in it, so it has no loading
     * state: a row that may well stay empty should not hold a placeholder strip open on Home.
     */
    data class NewEpisodes(val episodes: List<NewEpisode>) : HomeRow {
        override val key: String get() = "new-episodes"
    }

    /**
     * Home's chats' newest videos in one line, newest first, whichever chat they came from.
     *
     * @param starredOnly true when every chat it draws from is starred, which is what its heading
     *   says; false once Home fills itself from the chats with videos ([HomeRows.homeChats]).
     */
    data class Recent(
        val entries: List<ShelfEntry>,
        val loaded: Boolean,
        val starredOnly: Boolean = true,
        /** The chats it draws from, which its See all page reads on through ([HomeRows.seeAll]). */
        val chatIds: List<Long> = emptyList(),
        /**
         * False when Settings turned the row off but kept the carousel, which is drawn from it:
         * the row is there for the carousel's pages and is not drawn as a row ([HomeLayout]).
         */
        val asRow: Boolean = true,
        /**
         * Recently added reads the added chats ([AddedChats]) and none is added, or none of them is
         * left in the chat list: a line in the row's place says so and leads to Add chats.
         */
        val noneChosen: Boolean = false,
    ) : HomeRow {
        override val key: String get() = "recent"

        /** Its See all page reads on through [chatIds], past the row's [HomeRows.LIMIT]. */
        override val hasMore: Boolean get() = loaded && chatIds.isNotEmpty() && entries.isNotEmpty()
    }

    /**
     * One of the shelves under the chat rows (J Player, 2026-11-06): the films together, the
     * series together, or one language's titles together, grouped from the same videos the
     * chat rows draw. [kind] is "movies", "series" or "lang-" and the language code; [label]
     * names a language shelf (a language's own name for itself in English, a proper noun the
     * data carries, the way a chat's name is data), and is empty for Movies and Series, whose
     * headings the screens take from the catalog. Since v1.0.7 a shelf can also be one genre
     * ("genre-" and a slug of the genre's name, the name itself in [label], data the online
     * lookups carry) or the Top Rated shelf ("top-rated", its heading from the catalog),
     * grouped from the [TitleMeta] the caller hands in: neither a genre nor a rating is part
     * of a [MediaItem], so a title with no meta stands in neither kind of shelf. Since v1.0.10
     * a shelf can also be "trending" (the titles TMDB says are trending this week, of the
     * ones in the viewer's chats, in TMDB's own order) or "because" (unwatched titles sharing
     * a genre with what the viewer has watched), both from inputs the caller hands in the
     * same way; their headings come from the catalog too. Since v1.0.13 a shelf can also be
     * "anime" (the titles the online match calls Animation in Japanese, or whose name
     * carries the word on its own where nobody matched them), which stands apart from
     * Movies and Series.
     */
    data class Catalog(
        val kind: String,
        val label: String,
        val entries: List<ShelfEntry>,
        /** Every tile the shelf holds, past the row's cut: its See all page lists these. */
        val all: List<ShelfEntry> = entries,
    ) : HomeRow {
        override val key: String get() = "catalog-$kind"
        override val total: Int get() = all.size
        override val hasMore: Boolean get() = all.size > entries.size
    }
}

/**
 * What the online lookups already know of one title, for the genre and Top Rated shelves:
 * its genres (the provider's own names for them) and its rating out of ten, where the
 * provider gave one that stands (enough votes behind it). Keyed in [HomeRows.build]'s
 * input by the [MediaItem.id] of the tile's lead item: a file's own id, a show's cover's.
 * A title nobody has matched, or whose facts were never fetched, has no entry here and
 * stands in no genre or Top Rated shelf; it keeps its place in the chat rows.
 */
data class TitleMeta(
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    /** The title's original language (ISO 639-1) from the online match, where it gave one. */
    val originalLanguage: String? = null,
    /** The year the title came out, from the online match, where it gave one. */
    val year: Int? = null,
    /** Where the title streams, by the app's platform names, from the online match. */
    val providers: List<String> = emptyList(),
    /**
     * The match itself is an anime catalogue record (an AniList match, v1.0.13): the title is
     * anime by the fact of the match, whatever its genres and language say, since AniList
     * catalogues animation and its records carry neither an Animation genre nor a language
     * the genre test can read. The caller sets this from the match's provider.
     */
    val anime: Boolean = false,
)

/**
 * Builds Home's rows from what is on this device and what Telegram has answered so far.
 *
 * Pure, so the rules live in one place for the phone, the television and the desktop:
 * - Continue watching first, then "Recently added in starred chats", so what just arrived in a
 *   favourite is near the top, then Watch later, then New episodes when there are any, then one
 *   row per starred chat in the chat list's own order.
 * - Recently added draws from Home's chats: the starred ones, and where Home fills itself
 *   ([homeChats], the app's default since 2026-10-21) the chats the search across chats found the
 *   most videos in, which leaves out family groups and news that only ever post a clip. Starred
 *   chats alone need two at least: with one, it would repeat that chat's own row.
 * - Filling itself, Home is the carousel, Continue watching, Recently added and a row for each
 *   starred chat, and nothing else (user, 2026-10-23, after the Movies and Shows rows of
 *   2026-10-21 sorted videos wrongly): no Watch later or New episodes, which stay in their own
 *   pages, and no row for a chat Home picked for itself, which only feeds Recently added.
 * - Every row holds at most [LIMIT] tiles. A show folded into one tile counts once.
 * - Continue watching holds one record per film or episode: copies of it (a 1080p and a 720p)
 *   fold into the one watched last ([foldCopies]).
 * - A video appears once among Continue, Watch later and the chat rows: something part way
 *   through is in Continue and nowhere else, something saved for later is in Watch later. Recently added is the starred chats' videos again, merged into one
 *   timeline, so it leaves out only what is in Continue.
 * - A row that has loaded and found nothing is left out rather than drawn as an empty strip.
 * - New episodes are not taken out of the chat rows: a starred chat's show tile stands for the
 *   whole show, and losing its newest episodes there would make it look further behind.
 */
object HomeRows {

    /** Tiles per row. Ten fill a television's width twice over and keep each row cheap to compose. */
    const val LIMIT = 10

    /**
     * Chosen chats Recently added waits on before it says it found nothing: the ones Home reads
     * at once (HomeViewModel's EAGER_CHATS). The rest count through the search across chats.
     */
    const val CHOSEN_WAIT = 8

    /** Starred chats it takes for Recently added: one alone already has a row of its own. */
    const val RECENT_MIN_STARRED = 2

    /** The starred chats that get a row, in the chat list's order, which is Telegram's recency. */
    fun favouriteChats(chats: List<ChatSummary>, favourites: Set<Long>): List<ChatSummary> =
        if (favourites.isEmpty()) emptyList() else chats.filter { it.id in favourites }

    /** Chats Home picks for itself, besides the starred ones: a screen's worth of rows. */
    const val AUTO_CHATS = 6

    /** Pinned chats Home always reads, in the chat list's order: the first few, as a list shows them. */
    const val PINNED_CHATS = 5

    /**
     * The chats Home draws from: the starred ones first, in the chat list's order, each with a
     * row of its own, then up to [limit] more that the search across every chat ([recent]) found
     * videos in, the most videos first, then the newest. So Home is full on the first open with
     * nothing starred (user, 2026-10-21); starring a chat only puts it first. Saved Messages,
     * where most people keep their videos, always follows the starred ones, whether or not it had
     * an upload among the newest across the account (user, 2026-10-22: a show kept there was
     * missing from Home). The chats pinned in the list come next, the first [PINNED_CHATS] of
     * them: a pinned channel is one somebody keeps their shows in, however long ago it last
     * posted (user, 2026-10-22: Loki and other shows in a pinned channel were missing). An
     * archived chat is never picked, nor one missing from [chats] (the kids lock's, or one this
     * account left). Before [recent] arrives only the starred, Saved Messages and pinned chats
     * are known.
     */
    fun homeChats(
        chats: List<ChatSummary>,
        favourites: Set<Long>,
        recent: List<MediaItem>?,
        limit: Int = AUTO_CHATS,
    ): List<ChatSummary> {
        val starred = favouriteChats(chats, favourites)
        val saved = chats.firstOrNull { it.kind == ChatKind.Saved && !it.isArchived && it.id !in favourites }
        val pinned = chats.filter { it.isPinned && !it.isArchived && it.id !in favourites && it.id != saved?.id }.take(PINNED_CHATS)
        val fixed = starred + listOfNotNull(saved) + pinned
        if (recent.isNullOrEmpty() || limit <= 0) return fixed
        val taken = fixed.mapTo(HashSet()) { it.id }
        val listed = chats.filterNot { it.isArchived || it.id in taken }.associateBy { it.id }
        val picked = recent
            .filter { it.chatId in listed }
            .groupBy { it.chatId }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, List<MediaItem>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.maxOf { it.date } }
                    .thenBy { it.key },
            )
            .take(limit)
            .mapNotNull { listed[it.key] }
        return fixed + picked
    }

    /**
     * @param favourites the starred chats, already in display order (see [favouriteChats]).
     * @param loaded each starred chat's newest videos, by chat id; a chat missing here is still loading.
     * @param recent the newest videos across every chat, or null while that search is running. Only
     *   the starred chats' are kept, together with what [loaded] already has of them.
     * @param partial the starred chats whose fetch stopped short of their oldest video.
     * @param watchLater the Watch later list, newest first.
     * @param fresh the new episodes of the shows in progress, as [NewEpisodes.collect] lists them.
     * @param recentMinStarred the starred chats it takes for Recently added: [RECENT_MIN_STARRED],
     *   or one on a phone, where the row is the featured carousel ([HomeFeatured]) rather than a
     *   repeat of the one chat's own row.
     * @param starred which of [favourites] the viewer starred, when [favourites] is [homeChats]'
     *   answer; null when they all are. Only says what Recently added's heading may claim.
     * @param autoFill true where Home fills itself ([homeChats]): with nothing starred and the
     *   search across chats still running, Recently added holds its place as loading rather than
     *   Home opening on the first-run card, and only the [starred] chats get a row of their own.
     * @param chatsPending the chat list has not come in yet while [recent] (kept from the last
     *   run) already has videos: Home's chats cannot be picked, so Recently added holds its place
     *   as loading. Without it a cold start drew Continue watching alone and the carousel landed
     *   over it seconds later, pushing it down (Mi TV, caches cleared, 2026-10-23).
     */
    fun build(
        continueWatching: List<ResumeRecord>,
        favourites: List<ChatSummary>,
        loaded: Map<Long, List<MediaItem>>,
        recent: List<MediaItem>?,
        partial: Set<Long> = emptySet(),
        watchLater: List<WatchLaterRecord> = emptyList(),
        fresh: List<NewEpisode> = emptyList(),
        recentMinStarred: Int = RECENT_MIN_STARRED,
        starred: Set<Long>? = null,
        autoFill: Boolean = false,
        chatsPending: Boolean = false,
        layout: HomeLayout = HomeLayout(),
        recentFrom: List<Long>? = null,
        noneChosen: Boolean = false,
        titleMeta: Map<String, TitleMeta> = emptyMap(),
        collections: List<VideoCollection> = emptyList(),
        trending: Map<String, Int> = emptyMap(),
        watchedGenres: Set<String> = emptySet(),
        watchedKeys: Set<String> = emptySet(),
        /** A phone's order (owner, 2026-11-06): Watch later and the starred chats' rows directly under Continue watching. A television keeps the order it has. */
        phoneOrder: Boolean = false,
        /** Now, for the Weekly shelf (new arrivals this week); zero turns that shelf off. */
        nowMs: Long = 0L,
        /**
         * Videos under 5 minutes left out of the rows and shelves (the Playback switch,
         * see [ShortVideos]): the pools the rows are built from are screened before
         * anything is arranged, so a short video is no tile, no episode of a show tile
         * and no copy counted in a shelf. Continue watching and Watch later are the
         * viewer's own lists and are never screened. Off by default here, as the
         * screens pass the switch in.
         */
        hideShortVideos: Boolean = false,
        /**
         * Extra videos for the Series row alone, past what [loaded] and [recent] hold:
         * the bounded deeper read of the chats that feed the series shelves (see
         * [SeriesDeepening]). They feed only the Series row and its language rows,
         * never a chat row or another shelf, so a chat read deeper for its shows does
         * not change what its own row shows, and a show tile can carry the seasons
         * and episodes the channels really hold rather than the window's share.
         */
        seriesPool: List<MediaItem> = emptyList(),
    ): List<HomeRow> {
        // A phone's catalog shelves, held back until Recently added stands (owner, v1.0.13):
        // the series shelves and the foot shelves are gathered here and laid down together,
        // in the phone's headline order, once every other row is in place.
        val phoneCatalog = ArrayList<HomeRow.Catalog>()
        val rows = buildList<HomeRow> {
        val seen = HashSet<String>()
        // Continue watching off: nothing is in progress as far as Home goes, so a film part way
        // through still shows in its chat's row rather than vanishing from Home altogether.
        val continueWatching = if (layout.continueWatching) continueWatching else emptyList()
        // The under 5 minute screening (see the parameter): the chats' videos and the
        // search across chats, screened once here so every row and shelf below agrees.
        val loaded = if (hideShortVideos) loaded.mapValues { (_, items) -> ShortVideos.kept(items) } else loaded
        val recent = if (hideShortVideos) recent?.let(ShortVideos::kept) else recent
        val seriesPool = if (hideShortVideos) ShortVideos.kept(seriesPool) else seriesPool

        val distinct = continueWatching.distinctBy { keyOf(it.chatId, it.messageId) }
        val everyResume = foldCopies(distinct)
        val resume = everyResume.take(LIMIT)
        resume.forEach { seen += keyOf(it.chatId, it.messageId) }
        // A copy folded under the one watched last is in Continue too, through that one.
        val shownCopies = resume.mapNotNullTo(HashSet()) { record -> copyKeyOf(record) }
        // And by the film's name and year, for a copy whose file name does not tie it to them.
        resume.mapNotNullTo(shownCopies) { record -> if (record.fileName != null) SameTitle.filmKey(record.toMediaItem()) else null }
        distinct.forEach { record ->
            val copy = copyKeyOf(record)
            if ((copy != null && copy in shownCopies) || foldKeyOf(record)?.let { it in shownCopies } == true) seen += keyOf(record.chatId, record.messageId)
        }
        if (resume.isNotEmpty()) add(HomeRow.Continue(resume, everyResume.drop(LIMIT)))
        val inContinue = HashSet(seen)

        val homeChats = favourites.distinctBy { it.id }

        // The starred chats' rows, as a step of their own: on a phone they stand directly
        // under Continue watching with Watch later (owner, 2026-11-06), on a television
        // where they have always stood, under Recently added.
        val emitChatRows: MutableList<HomeRow>.() -> Unit = {
            // A row for each starred chat, in the chat list's order (user, 2026-10-23:
            // carousel, Continue watching, Recently added, then the favourites). The chats
            // Home picked for itself feed Recently added and the carousel only.
            // Off in Settings: no starred chat has a row, and none is read for one.
            if (layout.starredRows) homeChats.filter { starred == null || it.id in starred }.forEach { chat ->
                val items = loaded[chat.id]
                if (items == null) {
                    add(HomeRow.Chat(chat.id, chat.title, emptyList(), loaded = false))
                    return@forEach
                }
                val entries = row(items, seen, shownCopies, inContinue)
                if (entries.isNotEmpty()) {
                    val all = items.distinctBy { it.id }
                    add(
                        HomeRow.Chat(
                            chat.id,
                            chat.title,
                            entries,
                            loaded = true,
                            more = beyond(all, entries),
                            complete = chat.id !in partial,
                            fetched = all.size,
                        ),
                    )
                }
            }
        }

        // Watch later and New episodes are worked out here, while only Continue's keys are
        // in [seen]: a show's tile in a chat row marks every episode seen, which would
        // empty New episodes wherever the same show stands in a starred chat's row. Where
        // the two rows stand is the shape's choice below; Watch later stands on a phone
        // even where Home fills itself, which is the order the owner asked for there.
        val everyLater = watchLater.distinctBy { it.id }.filterNot { !it.isShow && it.key in inContinue }
        val later = everyLater.take(LIMIT)
        later.forEach { if (!it.isShow) seen += it.key }
        val watchLaterRow =
            if (later.isNotEmpty() && (!autoFill || phoneOrder)) HomeRow.WatchLater(later, everyLater.drop(LIMIT)) else null
        val newEpisodesRow = fresh.filterNot { keyOf(it.item.chatId, it.item.messageId) in seen }
            .take(LIMIT)
            .takeIf { it.isNotEmpty() && !autoFill }
            ?.let { HomeRow.NewEpisodes(it) }

        // A phone's order (owner, 2026-11-06): Continue watching, Watch later, Favourites.
        if (phoneOrder) {
            watchLaterRow?.let { add(it) }
            emitChatRows()
        }

        // The Series row (owner, 2026-11-06): fed by every chat whose videos Home has read
        // (the whole [loaded] map, not only Home's picked chats) together with the search
        // across chats, so a series in any visible chat stands here. On a television it
        // stands directly under Continue watching; on a phone it is held back with the
        // other shelves until Recently added stands (owner, v1.0.13). The shelves at the
        // foot keep their narrower pool, their gates and their place.
        if (layout.catalogRows) {
            // The deeper series pool ([seriesPool] the parameter, screened above) joins
            // the window pools here and nowhere else: a show stands with every episode
            // its chats hold, while its chat's own row keeps its window.
            val seriesItems = (loaded.values.flatten() + recent.orEmpty() + seriesPool).distinctBy { it.id }
            // Anime stands in its own shelf at the foot, not among the shows (owner, v1.0.13).
            val shows = seriesShows(seriesItems, shownCopies, inContinue).filterNot { isAnime(it, titleMeta) }
            if (shows.isNotEmpty()) {
                val seriesRows = listOf(HomeRow.Catalog("series", "", shows.take(LIMIT), all = shows)) +
                    seriesLanguageRows(shows, titleMeta)
                if (phoneOrder) phoneCatalog += seriesRows else addAll(seriesRows)
            }
        }

        // The chats Recently added draws from: Home's chats, or the added ones (in Home's order).
        val homeOrder = if (recentFrom == null) homeChats.map { it.id } else recentFrom.distinct()
        val homeIds = homeOrder.toHashSet()
        // Where Home fills itself, its chats are not all known until the chat list and the search
        // across chats are in: before that only the starred ones are picked, and "in starred
        // chats" would read true for a few seconds and then flip to the plain heading once the
        // busiest chats join (TV, 2026-10-27). The plain heading is held until the chats are known.
        val homeKnown = !chatsPending && !(autoFill && recent == null)
        val starredOnly = homeKnown && (starred == null || homeIds.all { it in starred })
        val asRow = layout.recentlyAdded
        val wanted = layout.needsRecent
        if (wanted && noneChosen) {
            add(HomeRow.Recent(emptyList(), loaded = true, starredOnly = false, asRow = asRow, noneChosen = true))
        } else if (wanted && recentFrom != null && homeIds.isEmpty()) {
            // Chosen chats, none of them in the chat list yet: it is still on its way.
            add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = false, asRow = asRow))
        } else if (wanted && (homeIds.size >= recentMinStarred.coerceAtLeast(1) || (recentFrom == null && autoFill && (recent == null || chatsPending)))) {
            val starredIds = homeIds
            if (recent == null) {
                add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
            } else {
                // The search across chats reaches a starred chat further back than its own row's
                // fetch, and a starred chat's fetch fills in whatever the search did not reach.
                val pool = recent.filter { it.chatId in starredIds } + starredIds.flatMap { loaded[it].orEmpty() }
                // A copy, so the rows below still show these videos in their own chats.
                val entries = row(pool, HashSet(inContinue), shownCopies, inContinue)
                if (entries.isNotEmpty()) {
                    add(HomeRow.Recent(entries, loaded = true, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
                } else if (chatsPending || (if (recentFrom == null) starredIds.any { it !in loaded } else homeOrder.take(CHOSEN_WAIT).any { it !in loaded })) {
                    // Nothing yet, but a starred chat's own videos are still on their way: the row
                    // stays as loading, so the carousel holds its place rather than vanishing and
                    // coming back once they land (seen on a cold start on the TV stick).
                    add(HomeRow.Recent(emptyList(), loaded = false, starredOnly = starredOnly, chatIds = homeOrder, asRow = asRow))
                }
            }
        }

        // Watch later and New episodes stand here on a television; on a phone Watch later
        // already stood under Continue watching (see above), and New episodes follows
        // Recently added either way.
        if (!phoneOrder) watchLaterRow?.let { add(it) }
        newEpisodesRow?.let { add(it) }

        // The starred chats' rows on a television; a phone put them under Continue watching.
        if (!phoneOrder) emitChatRows()
        // The shelves at the foot (J Player, 2026-11-06), from every video Home's chats hold,
        // the recent search's included: a second way through them, under the chat rows.
        if (layout.catalogRows) {
            val pool = (homeChats.flatMap { loaded[it.id].orEmpty() } + recent.orEmpty())
                .distinctBy { it.id }
            val foot = ArrayList<HomeRow.Catalog>()
            foot += catalogShelves(pool, shownCopies, inContinue, titleMeta, trending, watchedGenres, watchedKeys, nowMs)
            // The viewer's own collections under the shelves (J Player, 2026-11-06): a title is
            // in a collection by its progress key, and any copy of it stands for it, the way a
            // folded tile stands for its copies everywhere else on Home. One tile is enough:
            // the row is the viewer's own, not a shelf that has to earn its place.
            if (collections.isNotEmpty()) {
                val byKey = HashMap<String, ShelfEntry>()
                arranged(pool, emptySet()).forEach { entry ->
                    itemsOf(entry).forEach { item -> byKey.putIfAbsent(item.progressKey, entry) }
                }
                collections.forEachIndexed { index, collection ->
                    val all = collection.keys.mapNotNull { byKey[it] }.distinct()
                    if (all.isNotEmpty()) foot += HomeRow.Catalog("collection-$index", collection.name, all.take(LIMIT), all = all)
                }
            }
            if (phoneOrder) phoneCatalog += foot else foot.forEach { add(it) }
        }
        // A phone's shelves (owner, v1.0.13): Recently added and New episodes already stand
        // above, and the shelves follow in the headline order, Top 10 first, then Movies,
        // the shows with their language rows, then Anime, the rest keeping the relative
        // order they were built in. The sort is stable, so the rest keep that order among
        // themselves. A television never reaches this: its shelves were added as they came.
        if (phoneOrder && phoneCatalog.isNotEmpty()) {
            addAll(phoneCatalog.sortedBy { phoneShelfRank(it.kind) })
        }
        }
        return arrangeCatalog(rows, layout)
    }

    /**
     * The catalog group a shelf kind belongs to, for the Categories picker (owner,
     * 2026-11-06): the picker's groups are coarser than kinds (every language shelf is
     * "lang", every OTT row "ott"), so hiding and ordering speak in groups.
     */
    fun catalogGroupOf(kind: String): String = when {
        kind == "series" || kind.startsWith("series-lang-") -> "series"
        kind == "anime" -> "anime"
        kind.startsWith("lang-") -> "lang"
        kind.startsWith("genre-") -> "genre"
        kind.startsWith("ott-") -> "ott"
        kind.startsWith("collection-") -> "collections"
        kind == "multi-lang" -> "multi"
        else -> kind
    }

    /**
     * Where a shelf kind stands among a phone's headline shelves (owner, v1.0.13): Top 10
     * first, then Movies, the shows (the Series row and its language rows together), then
     * Anime. Every other kind shares the last place, so a stable sort leaves the rest in
     * the order they were built in.
     */
    private fun phoneShelfRank(kind: String): Int = when {
        kind == "top10" -> 0
        kind == "movies" -> 1
        kind == "series" || kind.startsWith("series-lang-") -> 2
        kind == "anime" -> 3
        else -> 4
    }

    /**
     * [build]'s rows with the viewer's category choices laid over them: a hidden group's
     * catalog rows are left out, and within each run of catalog rows the listed groups
     * stand in the viewer's order, the rest keeping their built order after them. Only
     * catalog rows move or go; every other row keeps its exact place.
     */
    private fun arrangeCatalog(rows: List<HomeRow>, layout: HomeLayout): List<HomeRow> {
        if (layout.hiddenCatalog.isEmpty() && layout.catalogOrder.isEmpty()) return rows
        val out = ArrayList<HomeRow>(rows.size)
        var at = 0
        while (at < rows.size) {
            if (rows[at] !is HomeRow.Catalog) {
                out += rows[at]
                at++
                continue
            }
            var end = at
            val run = ArrayList<HomeRow.Catalog>()
            while (end < rows.size && rows[end] is HomeRow.Catalog) {
                run += rows[end] as HomeRow.Catalog
                end++
            }
            val kept = run.filter { catalogGroupOf(it.kind) !in layout.hiddenCatalog }
            val ordered = if (layout.catalogOrder.isEmpty()) {
                kept
            } else {
                kept.sortedBy { row ->
                    layout.catalogOrder.indexOf(catalogGroupOf(row.kind)).let { if (it < 0) Int.MAX_VALUE else it }
                }
            }
            out += ordered
            at = end
        }
        return out
    }

    /** Tiles a shelf needs before it is a shelf at all: fewer reads as a gap in the chat rows. */
    const val CATALOG_MIN = 3

    /** Language shelves at most, the fullest first: Home stays a screen, not a catalogue. */
    const val CATALOG_LANGUAGES = 4

    /** Genre shelves at most, the fullest first, capped the way the language shelves are. */
    const val CATALOG_GENRES = 4

    /** The lowest rating, out of ten, that earns a tile a place on the Top Rated shelf. */
    const val TOP_RATED_MIN = 7.5

    /** A language code's name, for its shelf's heading. */
    fun languageName(code: String): String? = LANGUAGE_NAMES[code]

    private val LANGUAGE_NAMES = mapOf(
        "ml" to "Malayalam", "ta" to "Tamil", "te" to "Telugu", "hi" to "Hindi",
        "en" to "English", "kn" to "Kannada", "mr" to "Marathi", "bn" to "Bengali",
        "pa" to "Punjabi", "ko" to "Korean", "ja" to "Japanese", "zh" to "Chinese",
        "es" to "Spanish", "fr" to "French", "ru" to "Russian", "de" to "German",
        "it" to "Italian", "tr" to "Turkish", "ar" to "Arabic",
    )

    /** The language an entry's name announces, from its file (a show: its cover, else an episode). */
    private fun languageOf(entry: ShelfEntry): String? = when (entry) {
        is ShelfEntry.File -> VideoCopies.languagesOf(entry.item).firstOrNull()
        is ShelfEntry.Show -> VideoCopies.languagesOf(entry.series.cover).firstOrNull()
            ?: entry.series.episodes.firstOrNull()?.copies?.firstOrNull()?.let { VideoCopies.languagesOf(it).firstOrNull() }
    }

    /**
     * An entry's language, the online match's original language first (owner, 2026-11-06):
     * TMDB knows a show's language whatever its files are named, and the name's markers
     * are the fallback for a title nobody matched.
     */
    private fun languageOf(entry: ShelfEntry, titleMeta: Map<String, TitleMeta>): String? =
        metaOf(entry, titleMeta)?.originalLanguage?.takeIf { it in LANGUAGE_NAMES } ?: languageOf(entry)

    /** The platform an entry belongs to: the match's providers first, else its name's own tag ([MediaFacts]). */
    private fun platformOf(entry: ShelfEntry, titleMeta: Map<String, TitleMeta>): String? =
        metaOf(entry, titleMeta)?.providers?.firstOrNull()
            ?: MediaFacts.read(leadNameOf(entry)).platform

    /** A platform's slug for its OTT row kind: lowered, runs of other characters as one dash. */
    private fun ottKind(platform: String): String =
        "ott-" + platform.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    /** The name an entry is filed under: a file's own name, a show's cover's. */
    private fun leadNameOf(entry: ShelfEntry): String = when (entry) {
        is ShelfEntry.File -> entry.item.fileName.ifBlank { entry.item.title }
        is ShelfEntry.Show -> entry.series.cover.fileName.ifBlank { entry.series.cover.title }
    }

    /** Whether an entry carries more than one soundtrack language, by its names or its copies. */
    private fun isMultiLanguage(entry: ShelfEntry): Boolean {
        if (Regex("""(?i)(?<![A-Za-z])multi(?![A-Za-z])""").containsMatchIn(leadNameOf(entry))) return true
        return itemsOf(entry).any { VideoCopies.languagesOf(it).size >= 2 }
    }

    /** The meta of an entry's tile: a file's own, a show's cover's (its newest episode). */
    private fun metaOf(entry: ShelfEntry, titleMeta: Map<String, TitleMeta>): TitleMeta? = when (entry) {
        is ShelfEntry.File -> titleMeta[entry.item.id]
        is ShelfEntry.Show -> titleMeta[entry.series.cover.id]
    }

    /** The word "anime" standing on its own in a name, the shape [isMultiLanguage] reads. */
    private val ANIME_WORD = Regex("""(?i)(?<![A-Za-z])anime(?![A-Za-z])""")

    /**
     * Whether an entry is anime (owner, v1.0.13): the online match calls it Animation
     * (the provider's own name, read without case) and its original language is Japanese.
     * Where nobody matched the title, so there are no genres and no original language to
     * read, the name it is filed under is the only word on it: a standalone "anime" there
     * counts, and nothing else in the name does. Neither [MediaFacts] nor [MediaName]
     * carries a reusable anime parse of its own: the fansub shapes the online lookup
     * reads only ever name an episode, so a film would slip past them, and they are not
     * part of the name facts this builder reads.
     */
    private fun isAnime(entry: ShelfEntry, titleMeta: Map<String, TitleMeta>): Boolean {
        val meta = metaOf(entry, titleMeta)
        // An anime catalogue's own match settles it before any genre reading (v1.0.13).
        if (meta?.anime == true) return true
        if (meta != null && (meta.genres.isNotEmpty() || meta.originalLanguage != null)) {
            return meta.originalLanguage?.equals("ja", ignoreCase = true) == true &&
                meta.genres.any { it.trim().equals("Animation", ignoreCase = true) }
        }
        return ANIME_WORD.containsMatchIn(leadNameOf(entry))
    }

    /** A genre's name made safe for a shelf's kind: letters and numbers, the rest as dashes. */
    private fun genreKind(genre: String): String =
        "genre-" + genre.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')

    /** The id a shelf's inputs are keyed by: a file's own, a show's cover's. */
    private fun leadIdOf(entry: ShelfEntry): String = when (entry) {
        is ShelfEntry.File -> entry.item.id
        is ShelfEntry.Show -> entry.series.cover.id
    }

    /**
     * The shelves for [build]'s foot: Trending now where TMDB's week has titles in the chats,
     * then Movies, a shelf per language, a shelf per genre, Top Rated, and Because you
     * watched, from [pool] arranged the way a row arranges it. (The Series row is not one of
     * them any more: it is fed wider and stands under Continue watching instead, see
     * [seriesShows].) A title may stand in a shelf
     * and in its chat's row: the shelves are a second way through the same videos, the way a
     * streaming home groups them, and they leave out only what Continue watching holds. A
     * shelf with fewer than [CATALOG_MIN] tiles is left out. The genre and Top Rated shelves
     * group by [titleMeta] alone: a title with no meta, or with genres the lookups never
     * named, stands in neither, and a title with several genres stands in each of its genres'
     * shelves. Top Rated holds the titles rated at or above [TOP_RATED_MIN], best first, ties
     * in the row's own newest first order. Trending holds the pool's titles [trending] ranks
     * (a tile's lead id to its place in TMDB's order), in that order. Because you watched
     * holds titles sharing a genre in [watchedGenres] with what was watched, most shared
     * genres first, then best rated, and leaves out anything already watched ([watchedKeys]
     * holds the watched videos' progress keys: any copy watched keeps the tile out). The
     * Anime shelf holds every anime title in the pool, films and shows together (see
     * [isAnime]), and anime titles stand in neither Movies nor the language shelves; the
     * discovery shelves (Trending, Top 10, the genre shelves, Top Rated, Because, the OTT
     * rows, Multi Language and Weekly) keep them as they always have.
     */
    private fun catalogShelves(
        pool: List<MediaItem>,
        continuing: Set<String>,
        inProgress: Set<String>,
        titleMeta: Map<String, TitleMeta> = emptyMap(),
        trending: Map<String, Int> = emptyMap(),
        watchedGenres: Set<String> = emptySet(),
        watchedKeys: Set<String> = emptySet(),
        nowMs: Long = 0L,
    ): List<HomeRow.Catalog> {
        if (pool.isEmpty()) return emptyList()
        val all = arranged(pool, HashSet(inProgress), continuing, inProgress)
        if (all.isEmpty()) return emptyList()
        return buildList {
            if (trending.isNotEmpty()) {
                val ranked = all.mapNotNull { entry -> trending[leadIdOf(entry)]?.let { entry to it } }
                    .sortedBy { it.second }
                if (ranked.size >= CATALOG_MIN) add(HomeRow.Catalog("trending", "", ranked.take(LIMIT).map { it.first }, all = ranked.map { it.first }))
            }
            // Top 10 (owner, 2026-11-06): the titles with the most presence in the chats,
            // a title counted once per copy of it anywhere in the pool, most first. Only a
            // title present more than once is ranked: where nothing repeats, every title
            // stands at one and a top ten would be an arbitrary first ten.
            run {
                val presence = HashMap<String, Int>()
                pool.forEach { item ->
                    val key = SameTitle.filmKey(item) ?: item.id
                    presence[key] = (presence[key] ?: 0) + 1
                }
                val scored = all.map { entry ->
                    entry to itemsOf(entry).map { SameTitle.filmKey(it) ?: it.id }.distinct().sumOf { presence[it] ?: 0 }
                }.filter { it.second >= 2 }.sortedByDescending { it.second }
                if (scored.size >= CATALOG_MIN) add(HomeRow.Catalog("top10", "", scored.take(10).map { it.first }, all = scored.map { it.first }))
            }
            val movies = all.filter { entry -> entry is ShelfEntry.File && SeriesShelf.episodeOf(entry.item).episode == null && !isAnime(entry, titleMeta) }
            if (movies.size >= CATALOG_MIN) add(HomeRow.Catalog("movies", "", movies.take(LIMIT), all = movies))
            // Anime (owner, v1.0.13): every anime title in the pool, films and shows
            // together, in its own shelf; its heading comes from the catalog, so the
            // label is empty, as Movies and Series leave theirs.
            val anime = all.filter { entry -> isAnime(entry, titleMeta) }
            if (anime.size >= CATALOG_MIN) add(HomeRow.Catalog("anime", "", anime.take(LIMIT), all = anime))
            // UHD (owner, v1.0.14): the titles with a 4K copy, films and shows together
            // as Anime gathers them, by the quality facts the copies already carry; its
            // heading comes from the catalog too, so the label is empty.
            val uhd = all.filter { entry -> isUhd(entry) }
            if (uhd.size >= CATALOG_MIN) add(HomeRow.Catalog("uhd", "", uhd.take(LIMIT), all = uhd))
            val byLanguage = all.filterNot { entry -> isAnime(entry, titleMeta) }.groupBy { languageOf(it, titleMeta) }
            byLanguage.entries
                .filter { (code, entries) -> code != null && LANGUAGE_NAMES.containsKey(code) && entries.size >= CATALOG_MIN }
                .sortedWith(compareByDescending<Map.Entry<String?, List<ShelfEntry>>> { it.value.size }.thenBy { LANGUAGE_NAMES[it.key] })
                .take(CATALOG_LANGUAGES)
                .forEach { (code, entries) ->
                    add(HomeRow.Catalog("lang-$code", LANGUAGE_NAMES.getValue(code!!), entries.take(LIMIT), all = entries))
                }
            if (titleMeta.isNotEmpty()) {
                val byGenre = LinkedHashMap<String, MutableList<ShelfEntry>>()
                all.forEach { entry ->
                    metaOf(entry, titleMeta)?.genres.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        .forEach { genre -> byGenre.getOrPut(genre) { ArrayList() } += entry }
                }
                byGenre.entries
                    .filter { it.value.size >= CATALOG_MIN }
                    .sortedWith(compareByDescending<Map.Entry<String, List<ShelfEntry>>> { it.value.size }.thenBy { it.key })
                    .take(CATALOG_GENRES)
                    .forEach { (genre, entries) ->
                        add(HomeRow.Catalog(genreKind(genre), genre, entries.take(LIMIT), all = entries))
                    }
                val rated = all.mapNotNull { entry ->
                    metaOf(entry, titleMeta)?.rating?.takeIf { it >= TOP_RATED_MIN }?.let { entry to it }
                }.sortedByDescending { it.second }
                if (rated.size >= CATALOG_MIN) add(HomeRow.Catalog("top-rated", "", rated.take(LIMIT).map { it.first }, all = rated.map { it.first }))
                if (watchedGenres.isNotEmpty()) {
                    val wanted = watchedGenres.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
                    val scored = all.mapNotNull { entry ->
                        val meta = metaOf(entry, titleMeta) ?: return@mapNotNull null
                        if (itemsOf(entry).any { it.progressKey in watchedKeys }) return@mapNotNull null
                        val shared = meta.genres.count { it.trim().lowercase() in wanted }
                        if (shared == 0) null else Triple(entry, shared, meta.rating ?: 0.0)
                    }.sortedWith(
                        compareByDescending<Triple<ShelfEntry, Int, Double>> { it.second }.thenByDescending { it.third },
                    )
                    if (scored.size >= CATALOG_MIN) add(HomeRow.Catalog("because", "", scored.take(LIMIT).map { it.first }, all = scored.map { it.first }))
                }
            }
            // OTT rows (owner, 2026-11-06): one per platform, from the match's providers
            // or the name's own tag, the fullest first, capped like the language shelves.
            val byPlatform = all.groupBy { platformOf(it, titleMeta) }
            byPlatform.entries
                .filter { (platform, entries) -> platform != null && entries.size >= CATALOG_MIN }
                .sortedWith(compareByDescending<Map.Entry<String?, List<ShelfEntry>>> { it.value.size }.thenBy { it.key })
                .take(CATALOG_LANGUAGES)
                .forEach { (platform, entries) ->
                    add(HomeRow.Catalog(ottKind(platform!!), platform, entries.take(LIMIT), all = entries))
                }
            // Multi Language (owner, 2026-11-06): the titles carrying more than one
            // soundtrack language, by name or by their copies' tracks.
            val multi = all.filter { isMultiLanguage(it) }
            if (multi.size >= CATALOG_MIN) add(HomeRow.Catalog("multi-lang", "", multi.take(LIMIT), all = multi))
            // Weekly (owner, 2026-11-06): what arrived in the chats in the last seven
            // days, by the posts' own dates. No clock handed in, no row.
            if (nowMs > 0) {
                val cutoff = nowMs / 1000 - 7 * 24 * 60 * 60
                val weekly = all.filter { entry -> itemsOf(entry).any { it.date.toLong() >= cutoff } }
                if (weekly.size >= CATALOG_MIN) add(HomeRow.Catalog("weekly", "", weekly.take(LIMIT), all = weekly))
            }
        }
    }

    /**
     * Every episode-detected show in [pool], arranged (owner, 2026-11-06). Split from
     * [catalogShelves] because [build] feeds it wider than the shelves (every chat whose
     * videos Home has read, plus the search across chats: a series in a chat Home did not
     * pick was invisible when the pool was Home's chats alone) and stands it directly under
     * Continue watching rather than at the foot.
     */
    private fun seriesShows(
        pool: List<MediaItem>,
        continuing: Set<String>,
        inProgress: Set<String>,
    ): List<ShelfEntry.Show> {
        if (pool.isEmpty()) return emptyList()
        return arranged(pool, HashSet(inProgress), continuing, inProgress)
            .filterIsInstance<ShelfEntry.Show>()
    }

    /**
     * The series language rows under the Series row (owner, 2026-11-06): the same shows
     * grouped by the language their name announces ([languageOf], the source the language
     * shelves themselves read), the fullest row first, and one show enough for a row to
     * stand, as the Series row allows. Only shows stand here, never a film that shares the
     * language. A show whose language no name announces stands in the Series row alone
     * rather than nowhere. Anime shows never reach here: [build] leaves them out of the
     * shows it hands in, so no series language row holds one (owner, v1.0.13).
     */
    private fun seriesLanguageRows(shows: List<ShelfEntry.Show>, titleMeta: Map<String, TitleMeta> = emptyMap()): List<HomeRow.Catalog> =
        shows.groupBy { languageOf(it, titleMeta) }
            .entries
            .filter { (code, _) -> code != null && LANGUAGE_NAMES.containsKey(code) }
            .sortedWith(
                compareByDescending<Map.Entry<String?, List<ShelfEntry.Show>>> { it.value.size }
                    .thenBy { seriesLanguageTitle(it.key!!) },
            )
            .map { (code, entries) ->
                HomeRow.Catalog("series-lang-$code", seriesLanguageTitle(code!!), entries.take(LIMIT), all = entries)
            }

    /**
     * A series language row's heading: the owner's own names for the Korean and Chinese
     * rows, and the language's name with Series after it for the rest, so the row is not
     * mistaken for the mixed language shelf of the same name at the foot.
     */
    private fun seriesLanguageTitle(code: String): String = when (code) {
        "ko" -> "K-Drama"
        "zh" -> "C-Drama"
        else -> LANGUAGE_NAMES[code]?.let { "$it Series" } ?: code
    }

    /**
     * What a row's "See all" page lists once [items] have been read of its chats (Home's own and
     * the pages read on past them, see [FeedMerge]): the row's own tiles first, as Home drew
     * them, each show with every episode read since, then everything else, newest first. A page that grows only at its foot, so nothing on screen moves when a page
     * lands. Null for a row that reads no chats (Continue watching, Watch later, New episodes).
     */
    fun seeAll(row: HomeRow, items: List<MediaItem>, hideShortVideos: Boolean = false): List<ShelfEntry>? {
        val entries = when (row) {
            is HomeRow.Chat -> row.entries
            is HomeRow.Recent -> row.entries
            else -> return null
        }
        // The pages read on are screened as the row was ([build]'s screening): the
        // row's own tiles already were, so only what is read past them needs it.
        val read = if (hideShortVideos) ShortVideos.kept(items) else items
        val own = entries.flatMap { entry ->
            when (entry) {
                is ShelfEntry.File -> listOf(entry.item)
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }
            }
        }
        val pool = (own + read).distinctBy { it.id }.sortedWith(FeedMerge.newestFirst)
        val arranged = SeriesShelf.arrange(pool)
        val byKey = arranged.associateBy { it.key }
        val first = entries.map { byKey[it.key] ?: it }
        val firstKeys = first.mapTo(HashSet()) { it.key }
        // Copies of one film across chats, or at another size, folded as the row folds them
        // ([arranged]): without this the pages read on past the row listed every copy as a tile
        // of its own. A copy folds into the tile already standing, so nothing on screen moves.
        return SameTitle.dedupe(first + arranged.filterNot { it.key in firstKeys })
    }

    /**
     * Newest first, without anything an earlier row already showed, folded into shows, cut to
     * [LIMIT]. Whatever ends up in the row is added to [seen], every episode of a folded show
     * included (every copy of each), so a later row cannot show one of them again on its own.
     * Nor can another copy of a film or episode in Continue ([continuing], as [copyKeyOf] keys
     * them): the 1080p of a film started in 720p is the same film, part way through.
     *
     * An episode in Continue ([inProgress]) still counts towards its show, though: left out, the
     * show's tile said "11 episodes" for a show of 12. It is only never a tile of its own.
     */
    /** Every video an entry stands for: a film's copies and parts, a show's episode copies. */
    private fun itemsOf(entry: ShelfEntry): List<MediaItem> = when (entry) {
        is ShelfEntry.File -> listOf(entry.item) + entry.item.versions + entry.item.parts
        is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies + it.alsoIn }
    }

    /**
     * A title with a 4K copy (owner, v1.0.14): any of its items whose quality reads 4K by
     * the facts the copies list itself shows ([VideoCopies.qualityLabel], the height from
     * the name's marker or the size Telegram states). One 4K copy among 1080p ones is
     * enough: the shelf promises a 4K copy exists, not that every copy is one.
     */
    private fun isUhd(entry: ShelfEntry): Boolean =
        itemsOf(entry).any { VideoCopies.qualityLabel(it) == "4K" }

    private fun row(
        items: List<MediaItem>,
        seen: MutableSet<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        val entries = arranged(items, seen, continuing, inProgress).take(LIMIT)
        markSeen(entries, seen)
        return entries
    }

    private fun markSeen(entries: List<ShelfEntry>, seen: MutableSet<String>) {
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> {
                    seen += entry.item.progressKey
                    entry.item.versions.forEach { seen += it.progressKey }
                }
                is ShelfEntry.Show -> entry.series.episodes.forEach { episode -> episode.copies.forEach { seen += it.progressKey } }
            }
        }
    }

    /** [row]'s tiles before the cut to [LIMIT], with nothing added to [seen]. */
    private fun arranged(
        items: List<MediaItem>,
        seen: Set<String>,
        continuing: Set<String> = emptySet(),
        inProgress: Set<String> = emptySet(),
    ): List<ShelfEntry> {
        fun copyOfContinuing(item: MediaItem): Boolean =
            continuing.isNotEmpty() && (
                VideoCopies.keyOf(item)?.let { it in continuing } == true ||
                    SameTitle.filmKey(item)?.let { it in continuing } == true
                )
        fun episode(item: MediaItem): Boolean = SeriesShelf.episodeOf(item).episode != null
        // One pass, each item's keys read once: Home runs this for every row on every build.
        val fresh = ArrayList<MediaItem>(items.size)
        val ids = HashSet<String>()
        for (item in items) {
            val key = item.progressKey
            val copy = copyOfContinuing(item)
            val partOfAShow = episode(item) && (key in inProgress || copy)
            if (!partOfAShow && (key in seen || copy)) continue
            if (ids.add(item.id)) fresh += item
        }
        fresh.sortWith(NEWEST_FIRST)
        // One tile per title in a row: copies the shelf did not fold, across chats or by their
        // online match, are folded here, the best copy leading ([SameTitle]).
        return SameTitle.dedupe(SeriesShelf.arrange(fresh))
            .filterNot { entry ->
                entry is ShelfEntry.File && entry.item.let { it.progressKey in inProgress || copyOfContinuing(it) }
            }
    }

    /** Newest first, the later message first on the same second; compared without boxing either. */
    private val NEWEST_FIRST = Comparator<MediaItem> { a, b ->
        when {
            a.date != b.date -> b.date.compareTo(a.date)
            else -> b.messageId.compareTo(a.messageId)
        }
    }

    /** A chat's videos that none of the row's tiles stands for, newest first: what See all adds. */
    private fun beyond(items: List<MediaItem>, entries: List<ShelfEntry>): List<MediaItem> {
        val shown = HashSet<String>()
        entries.forEach { entry ->
            when (entry) {
                is ShelfEntry.File -> shown += entry.item.id
                is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }.forEach { shown += it.id }
            }
        }
        return items
            .filterNot { it.id in shown }
            .sortedWith(NEWEST_FIRST)
    }

    /**
     * [records] with the copies of one film or one episode in a chat (a 1080p and a 720p of it,
     * see [SeriesShelf.sameVideoKey]) folded into one: the copy watched last, with its own place
     * and quality, where the newest of them stood. Everything else is left as it is, in its order.
     * What Continue watching lists, on Home and in History alike.
     */
    fun foldCopies(records: List<ResumeRecord>): List<ResumeRecord> {
        val latest = HashMap<String, ResumeRecord>()
        records.forEach { record ->
            val key = foldKeyOf(record) ?: return@forEach
            val held = latest[key]
            if (held == null || record.updatedAt > held.updatedAt) latest[key] = record
        }
        val placed = HashSet<String>()
        return records.mapNotNull { record ->
            val key = foldKeyOf(record) ?: return@mapNotNull record
            if (placed.add(key)) latest.getValue(key) else null
        }
    }

    /**
     * The Watched list with the copies of one film or episode as one card, as [foldCopies] folds
     * Continue watching: the copy finished last, where it stood. [records] come newest first.
     */
    fun foldWatched(records: List<WatchedRecord>): List<WatchedRecord> {
        val seen = HashSet<String>()
        return records.filter { record -> watchedKeyOf(record)?.let(seen::add) ?: true }
    }

    /**
     * [record] as the video its folded card stands for: every copy on the Watched list as its
     * [MediaItem.versions], so taking the mark off takes it off all of them.
     */
    fun watchedItem(record: WatchedRecord, records: List<WatchedRecord>): MediaItem {
        val item = record.toMediaItem()
        val key = watchedKeyOf(record) ?: return item
        val copies = records.filter { it.key == record.key || watchedKeyOf(it) == key }.map { it.toMediaItem() }
        return if (copies.size > 1) item.copy(versions = copies) else item
    }

    private fun watchedKeyOf(record: WatchedRecord): String? =
        if (record.fileName != null) VideoCopies.keyOf(record.toMediaItem()) else VideoCopies.keyOfTitle(record.title)

    /**
     * [record] and every other copy of the same film or episode in [records]: what removing a
     * card [foldCopies] made must remove, or the copy behind it takes its place at once.
     */
    fun copiesOf(record: ResumeRecord, records: List<ResumeRecord>): List<ResumeRecord> {
        val key = foldKeyOf(record) ?: return listOf(record)
        return (listOf(record) + records.filter { foldKeyOf(it) == key }).distinctBy { it.chatId to it.messageId }
    }

    /**
     * Which film or episode [record] is a copy of, in any chat ([VideoCopies.keyOf]), or null for a
     * video that names neither. An old record is read cautiously: its title alone has to name a
     * year or an episode, so a caption's bare "S02E04" never folds a whole show into one card.
     */
    private fun copyKeyOf(record: ResumeRecord): String? = VideoCopies.keyOf(record)

    /**
     * What Continue watching folds by: a film's name and year read with its caption
     * ([SameTitle.filmKey]), so a bare "Khalifa.mkv" captioned "Khalifa (2026)" and a "Khalifa
     * 2026 1080p" posted elsewhere are one card; anything else by [copyKeyOf].
     */
    private fun foldKeyOf(record: ResumeRecord): String? =
        (if (record.fileName != null) SameTitle.filmKey(record.toMediaItem()) else null) ?: copyKeyOf(record)

    /** The same key [SettingsStore.progressKey] writes, so a resume record and a video match. */
    private fun keyOf(chatId: Long, messageId: Long) = ProgressKeys.of(chatId, messageId)

    /**
     * [row] with only the tiles that have a picture of their own ([HomeFeatured.hasArt]), as the
     * desktop's Home draws it: never a tile of nothing but a generated cover. A record of Continue
     * watching or Watch later keeps no picture, so it shows once its video has been asked for and
     * is in [art] (keyed as [SettingsStore.progressKey]) with one. Null for a loaded row left with
     * nothing to show; a row still loading is returned as it is.
     */
    fun withArt(row: HomeRow, art: Map<String, MediaItem>): HomeRow? {
        fun pictured(chatId: Long, messageId: Long) = art[keyOf(chatId, messageId)]?.let(HomeFeatured::hasArt) == true
        val kept = when (row) {
            is HomeRow.Continue -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.WatchLater -> row.copy(records = row.records.filter { pictured(it.chatId, it.messageId) })
            is HomeRow.NewEpisodes -> row.copy(episodes = row.episodes.filter { HomeFeatured.hasArt(it.item) })
            is HomeRow.Chat -> if (!row.loaded) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
            is HomeRow.Catalog -> row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
            // The line saying no chosen chat is there stands as it is.
            is HomeRow.Recent -> if (!row.loaded || row.noneChosen) return row else row.copy(entries = row.entries.filter(HomeFeatured::hasArt))
        }
        val empty = when (kept) {
            is HomeRow.Continue -> kept.records.isEmpty()
            is HomeRow.WatchLater -> kept.records.isEmpty()
            is HomeRow.NewEpisodes -> kept.episodes.isEmpty()
            is HomeRow.Chat -> kept.entries.isEmpty()
            is HomeRow.Recent -> kept.entries.isEmpty()
            is HomeRow.Catalog -> kept.entries.isEmpty()
        }
        return if (empty) null else kept
    }
}
