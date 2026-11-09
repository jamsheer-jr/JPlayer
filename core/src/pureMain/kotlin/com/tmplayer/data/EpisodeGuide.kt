package com.tmplayer.data

import com.tmplayer.platform.sortedDistinct
import com.tmplayer.online.MetaAiring
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaSeason

/**
 * A show's seasons and episodes for the detail page: what the chats hold, laid over what the
 * provider lists, so the page can play the first, grey out the episodes no chat holds, and mark
 * those still to air as upcoming rather than missing.
 *
 * Dates are the providers' own `yyyy-MM-dd`, compared as text against [today] in the same form.
 */
object EpisodeGuide {

    enum class State {
        /** In the chat: plays. */
        InChat,

        /** Aired, but nobody posted it here. */
        Missing,

        /** Not aired yet. */
        Upcoming,
    }

    data class Season(
        val number: Int,
        /** Episodes of it the chat holds. */
        val inChat: Int,
        /** Episodes the provider lists, when it lists them. */
        val total: Int?,
        val state: State,
        val airDate: String? = null,
    )

    data class Episode(
        val season: Int,
        val number: Int,
        val inChat: SeriesEpisode?,
        val meta: MetaEpisode?,
        val state: State,
    ) {
        val code: String get() = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
    }

    /**
     * Every season either side knows, in order. A season the chat has any of is [State.InChat];
     * one it has none of is [State.Upcoming] when it has not begun (or the next episode opens it),
     * else [State.Missing].
     */
    fun seasons(series: Series?, listed: List<MetaSeason>, next: MetaAiring?, today: String): List<Season> {
        val held = series?.seasons.orEmpty().associate { it.number to it.coveredCount }
        val provider = listed.associateBy { it.number }
        val numbers = (held.keys + provider.keys + listOfNotNull(next?.season)).filter { it > 0 }.sortedDistinct()
        // A season held as one combined file counts as held whole: every episode
        // the provider lists for it is in the chat, in the pack (owner, v1.0.21).
        val packSeasons = series?.packs.orEmpty().mapTo(HashSet()) { it.season }
        return numbers.map { n ->
            val listedSeason = provider[n]
            val have = if (n in packSeasons) {
                listedSeason?.episodeCount?.takeIf { it > 0 } ?: (held[n] ?: 0).coerceAtLeast(1)
            } else {
                held[n] ?: 0
            }
            val airDate = listedSeason?.airDate ?: next?.takeIf { it.season == n && it.episode == 1 }?.airDate
            val state = when {
                have > 0 -> State.InChat
                airDate == null || airDate > today -> State.Upcoming
                else -> State.Missing
            }
            Season(n, have, listedSeason?.episodeCount?.takeIf { it > 0 }, state, airDate)
        }
    }

    /**
     * [season]'s episodes: the provider's list with the chat's episodes put in their places, and
     * any the chat holds that the provider does not list (a special numbered as an episode) kept.
     * An episode with no air date that is not in the chat counts as upcoming: providers list
     * announced episodes before they are dated.
     */
    fun episodes(season: Int, series: Series?, listed: List<MetaEpisode>?, today: String): List<Episode> {
        val rows = series?.seasons?.firstOrNull { it.number == season }?.episodes.orEmpty()
        val held = rows.associateBy { it.episode }
        // A combined pack holds every episode of its span, not only the one it stands under:
        // an episode inside a pack's range is in the chat, and the pack is what plays it.
        val holders = rows.filter { (it.lastEpisode ?: 0) > it.episode }
        val provider = listed.orEmpty().filter { it.season == season }.associateBy { it.number }
        val numbers = (held.keys + provider.keys).sortedDistinct()
        // A season pack holds the whole season: an episode no file names on its own
        // is still in the chat, and the pack is what plays it (owner, v1.0.21).
        val pack = series?.packs?.firstOrNull { it.season == season }
        return numbers.map { n ->
            val mine = held[n]
                ?: holders.firstOrNull { holder -> n in (holder.episode + 1)..(holder.lastEpisode ?: holder.episode) }
                ?: pack?.let { SeriesEpisode(it.item, season, n, it.copies) }
            val meta = provider[n]
            val state = when {
                mine != null -> State.InChat
                meta?.airDate == null || meta.airDate > today -> State.Upcoming
                else -> State.Missing
            }
            Episode(season, n, mine, meta, state)
        }
    }

    /**
     * The show [item] belongs to among [known] (every video the app has loaded), or null when
     * it stands alone. The held set is every chat's, not only the chat [item] came from
     * (owner, v1.0.14): an episode another chat holds is held, so the guide over a title
     * agrees with the series a search gathers across the chats, and no episode any loaded
     * chat holds is ever marked missing. The item's own chat is arranged whole, as before;
     * from the other chats only files of the same show (the same title key) are taken, so
     * one title's page never pays to arrange everything the app has ever loaded.
     */
    fun seriesOf(item: MediaItem, known: Collection<MediaItem>): Series? {
        val titleKey = SeriesShelf.episodeOf(item).titleKey
        val items = (known.filter { it.chatId == item.chatId || (titleKey.isNotEmpty() && SeriesShelf.episodeOf(it).titleKey == titleKey) } + item)
            .distinctBy { it.id }
        return SeriesShelf.arrange(items).asSequence()
            .mapNotNull { (it as? ShelfEntry.Show)?.series }
            .firstOrNull { show ->
                show.episodes.any { ep -> ep.copies.any { it.id == item.id } } ||
                    show.packs.any { pack -> pack.copies.any { it.id == item.id } }
            }
    }
}
