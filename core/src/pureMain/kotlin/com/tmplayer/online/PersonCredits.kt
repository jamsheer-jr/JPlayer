package com.tmplayer.online

import com.tmplayer.data.MediaItem

/**
 * One title in a person's TMDB credits: the title as a [MetaRef] (so it matches the titles a
 * viewer has by [MetaRef.matchKey], the way "More like this" matches), its year where TMDB
 * states one, the part played or the job done, and its poster.
 */
data class PersonCredit(
    val ref: MetaRef,
    val year: Int? = null,
    val role: String = "",
    val posterUrl: String? = null,
)

/** One row of a person's page: a credit, and the copy of it in the viewer's chats, if any. */
data class FilmographyEntry(
    val credit: PersonCredit,
    /** The copy to open, where one of the viewer's chats holds this title; null stands as not here. */
    val item: MediaItem? = null,
) {
    val playable: Boolean get() = item != null
}

/**
 * The rules behind a person's full filmography (owner, v1.0.15, item 17): TMDB's combined
 * credits for the person, acting and crew together, tidied into one list, then marked against
 * the titles the viewer has. Wikipedia was the owner's other thought for the data; TMDB is
 * what the app already holds a key for and already trusts for the cast itself, so it is the
 * source, and a person TMDB does not know simply keeps the older in-chats list.
 */
object PersonFilmography {

    /**
     * The most credits one page lists. A long career runs to a few hundred titles; past this
     * the oldest are left off rather than the page growing without bound on a small television.
     */
    const val LIMIT = 300

    /**
     * Cast and crew as one list: one entry per title (a person credited twice on one title,
     * as an actor and a producer say, stands once, with the acting part named where there is
     * one), newest first, a title with no year last, then by name. Blank titles and titles
     * with no usable id never reach the list: the caller has already skipped those.
     */
    fun tidy(cast: List<PersonCredit>, crew: List<PersonCredit>): List<PersonCredit> {
        val byKey = LinkedHashMap<String, PersonCredit>()
        for (credit in cast + crew) {
            val held = byKey[credit.ref.matchKey]
            if (held == null) {
                byKey[credit.ref.matchKey] = credit
            } else if (held.role.isBlank() && credit.role.isNotBlank()) {
                byKey[credit.ref.matchKey] = held.copy(role = credit.role)
            }
        }
        return byKey.values
            .sortedWith(
                compareByDescending<PersonCredit> { it.year ?: Int.MIN_VALUE }
                    .thenBy { it.ref.title.lowercase() },
            )
            .take(LIMIT)
    }

    /**
     * [credits] with the ones the viewer has marked: a credit whose [MetaRef.matchKey] one of
     * [known] was matched to takes that title's copy, so the row opens it; the rest keep a
     * null item and stand as filmography entries only, the same honesty as a season list's
     * missing episodes. Order is the credits' own; marking never drops or moves a row.
     */
    fun mark(credits: List<PersonCredit>, known: List<KnownTitle>): List<FilmographyEntry> {
        if (credits.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, MediaItem>()
        for (title in known) byKey.putIfAbsent(title.info.let { MetaRef.matchKey(it.provider, it.kind, it.id) }, title.item)
        return credits.map { credit -> FilmographyEntry(credit, byKey[credit.ref.matchKey]) }
    }
}
