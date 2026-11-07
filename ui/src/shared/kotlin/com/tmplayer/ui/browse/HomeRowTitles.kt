package com.tmplayer.ui.browse

import com.tmplayer.data.HomeRow
import com.tmplayer.i18n.Messages

/**
 * A Home row's heading, the same on the phone, the television and the desktop: a starred chat's
 * row is headed by the chat's name.
 */
fun homeRowTitle(row: HomeRow, s: Messages): String = when (row) {
    is HomeRow.Continue -> s.homeRowContinue
    is HomeRow.WatchLater -> s.homeRowWatchLater
    is HomeRow.NewEpisodes -> s.homeRowNewEpisodes
    is HomeRow.Chat -> row.title
    is HomeRow.Recent -> if (row.starredOnly) s.homeRowRecent else s.homeRowRecentAll
    is HomeRow.Catalog -> when (row.kind) {
        "movies" -> s.homeRowMovies
        "series" -> s.homeRowShows
        "anime" -> s.homeRowAnime
        "uhd" -> s.homeRowUhd
        "series-lang-ko" -> s.homeRowKdrama
        "series-lang-zh" -> s.homeRowCdrama
        "top-rated" -> s.homeRowTopRated
        "trending" -> s.homeRowTrending
        "because" -> s.homeRowBecause
        "top10" -> s.homeRowTop10
        "weekly" -> s.homeRowWeekly
        "multi-lang" -> s.homeRowMultiLang
        "top-picks" -> s.homeRowTopPicks
        else -> if (row.kind.startsWith("dub-") && row.label.isNotEmpty()) s.homeRowDubbed(row.label) else row.label
    }
}

/** Whether [row] has its tiles yet; a row that has not is drawn as a placeholder strip of the same height. */
val HomeRow.isLoaded: Boolean
    get() = when (this) {
        is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.NewEpisodes -> true
        is HomeRow.Chat -> loaded
        is HomeRow.Recent -> loaded
        is HomeRow.Catalog -> true
    }
