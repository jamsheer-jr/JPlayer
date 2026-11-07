package com.tmplayer.data

/**
 * The Series row's deeper read (owner, v1.0.15): why a show's shelf undercounted, and
 * the bounded shape that fixes it.
 *
 * Home's per chat read is a window, not the chat: its read (HomeViewModel's
 * HomeReads) stops once it holds a row's worth of videos (its page is 20, and it
 * stops at 20), and the search across
 * chats behind Recently added is one global answer of a few dozen. The Series row is
 * built from those two pools alone, so a show whose episodes are spread through a
 * busy channel stands on the shelf with only the episodes inside the newest window:
 * the owner's The Walking Dead read "2 seasons, 3 eps" because three of its episodes
 * sat in the window and the rest were never read at all. The grouping itself
 * ([SeriesShelf]) was not the fault: handed the episodes, it counts them.
 *
 * The fix deepens only where a show already shows: a chat whose window holds at
 * least [SeriesShelf.MIN_EPISODES] distinct episodes of one title is searched for
 * that title through its whole history (Telegram's own in chat search, which is
 * not windowed), and what belongs to the show joins the Series row's pool. The
 * bounds, so a low end television is never held paging: at most [MAX_SHOWS_PER_CHAT]
 * shows per chat, at most [SEARCH_PAGES] pages of [SEARCH_PAGE] per show, at most
 * [MAX_ITEMS_PER_CHAT] videos kept per chat, and the chats themselves are capped
 * by HomeViewModel's series chat limit. A chat with no show in its window is never
 * searched, and a chat row keeps showing its window exactly as before: the deeper
 * pool feeds the Series row alone ([HomeRows.build]'s seriesPool).
 */
object SeriesDeepening {

    /** The most shows one chat is searched for, the fullest in its window first. */
    const val MAX_SHOWS_PER_CHAT = 4

    /** Messages asked for per search page of one show. */
    const val SEARCH_PAGE = 100

    /** The most search pages read for one show, so one show costs a bounded number of searches. */
    const val SEARCH_PAGES = 2

    /** The most videos kept for one chat's series pool. */
    const val MAX_ITEMS_PER_CHAT = 300

    /** One show a chat's window establishes: its grouping key and the title to search by. */
    data class Show(val key: String, val title: String)

    /**
     * The shows [items] (one chat's window) establishes: a title with at least
     * [SeriesShelf.MIN_EPISODES] distinct episodes among them, fullest first, then
     * newest first, capped at [MAX_SHOWS_PER_CHAT]. A single episode establishes
     * nothing: one stray episode in a film channel is not worth a search.
     */
    fun showsIn(items: List<MediaItem>): List<Show> {
        class Found(val title: String) {
            val episodes = HashSet<Pair<Int, Int>>()
            var newest = 0
        }
        val byKey = LinkedHashMap<String, Found>()
        for (item in items) {
            val name = SeriesShelf.episodeOf(item)
            val episode = name.episode ?: continue
            if (name.extra != null) continue
            val key = name.titleKey
            if (key.isEmpty()) continue
            val found = byKey.getOrPut(key) { Found(name.title) }
            found.episodes += (name.season ?: 1) to episode
            if (item.date > found.newest) found.newest = item.date
        }
        return byKey.entries
            .filter { it.value.episodes.size >= SeriesShelf.MIN_EPISODES }
            .sortedWith(
                compareByDescending<Map.Entry<String, Found>> { it.value.episodes.size }
                    .thenByDescending { it.value.newest },
            )
            .take(MAX_SHOWS_PER_CHAT)
            .map { Show(it.key, it.value.title) }
    }

    /**
     * The word of [show]'s title to search its chat by: Telegram matches a search
     * against a file name as a literal run of characters, so the whole title finds
     * nothing in a name written with dots or underscores between its words, while
     * its longest word stands in every spelling. What the search brings back is
     * screened by [isEpisodeOf], so a word shared with another title costs nothing
     * but the search itself.
     */
    fun queryOf(show: Show): String =
        show.title.split(' ').filter { it.isNotBlank() }.maxByOrNull { it.length } ?: show.title

    /** Whether [item] is an episode of the show keyed [key], by the same parse the shelf groups on. */
    fun isEpisodeOf(item: MediaItem, key: String): Boolean {
        val name = SeriesShelf.episodeOf(item)
        return name.episode != null && name.extra == null && name.titleKey == key
    }

    /**
     * A chat's series pool: its window ([first]) and what the searches found, each
     * video once, newest first, capped at [MAX_ITEMS_PER_CHAT].
     */
    fun merge(first: List<MediaItem>, found: List<MediaItem>): List<MediaItem> =
        (first + found).distinctBy { it.id }
            .sortedWith(FeedMerge.newestFirst)
            .take(MAX_ITEMS_PER_CHAT)
}
