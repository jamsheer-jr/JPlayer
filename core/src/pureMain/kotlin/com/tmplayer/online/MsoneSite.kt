package com.tmplayer.online

import com.tmplayer.data.MediaName
import java.net.URI
import java.util.Locale

/**
 * One release in MSone's public catalogue (malayalamsubtitles.org): a film, or one season of a
 * series, since the site files a series as one post per season. [pageUrl] is the release's own
 * page on the site, the address a subtitle is later fetched from.
 */
data class MsoneRelease(
    val title: String,
    val year: Int?,
    val series: Boolean,
    /** The season a series post holds, from its slug; a series' first post names none and is season 1. */
    val season: Int?,
    val pageUrl: String,
)

/**
 * MSone's public site, read as text: the way MyDramaList's pages are read ([MdlPage]), because
 * the site has no API the app may use (its REST route is firewalled) and the gateway key route
 * is closed to builds without one. Two reads: the search results page, a grid of release cards
 * whose classes and links carry the facts, and a release page, where the subtitle's download is
 * an anchor.
 *
 * Pure string work: no network, no Android types, nothing that can throw on a strange page.
 * Everything is read defensively: a card without a usable release link or title is skipped, and
 * a page that yields nothing yields an empty list or null.
 */
object MsoneSite {

    /** The site's own address; every link read here must stay on it. */
    const val BASE = "https://malayalamsubtitles.org"
    const val HOST = "malayalamsubtitles.org"

    /** More cards than this on one page is a page shape this reader does not trust. */
    private const val MAX_RELEASES = 60

    private val CONTAINER = Regex("""id\s*=\s*["']archive-container["']""", RegexOption.IGNORE_CASE)

    private val ARTICLE = Regex(
        """<article\b([^>]*)>(.*?)</article\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val CLASS_ATTR = Regex("""class\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /** The card's title link: the heading's anchor, whose text names the release. */
    private val TITLE_LINK = Regex(
        """<h[23]\b[^>]*>\s*<a\b[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Any anchor, for a card whose heading does not parse: its poster links the same page. */
    private val ANY_LINK = Regex(
        """<a\b[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>(.*?)</a\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val HREF = Regex("""<a\b[^>]*?href\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    private val ANCHOR_TAG = Regex("""<a\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val ONCLICK = Regex("""onclick\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

    /** The site's download button, by the id its pages give it. */
    private val REVIEW_BUTTON = Regex("""id\s*=\s*["']review-button["']""", RegexOption.IGNORE_CASE)

    /** A download manager package number inside a click handler's address. */
    private val WPDM_ID = Regex("""wpdmdl=(\d{1,10})\b""")

    private val RELEASE_PATH = Regex("""/languages/[^/]+/[^/]+/?$""")

    private val SLUG_YEAR = Regex("""-(19\d\d|20\d\d)$""")

    private val SLUG_SEASON = Regex("""season-0*(\d{1,3})\b""")

    private val TITLE_YEAR = Regex("""\((\d{4})\)\s*$""")

    private val ANY_TAG = Regex("""<[^>]+>""")

    private val NUMERIC_ENTITY = Regex("""&#(\d{1,7});""")

    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /**
     * The releases on a search results page, in the site's own order. Cards begin at the
     * results grid (`archive-container`) where the page has one, so links in the page's
     * furniture are never read as results; without a grid the whole page is read.
     */
    fun parseSearch(html: String): List<MsoneRelease> {
        if (html.isBlank()) return emptyList()
        val from = CONTAINER.find(html)?.range?.first ?: 0
        val page = if (from > 0) html.substring(from) else html
        val found = LinkedHashMap<String, MsoneRelease>()
        for (card in ARTICLE.findAll(page)) {
            if (found.size >= MAX_RELEASES) break
            val release = card(card.groupValues.getOrNull(1).orEmpty(), card.groupValues.getOrNull(2).orEmpty()) ?: continue
            if (release.pageUrl !in found) found[release.pageUrl] = release
        }
        return found.values.toList()
    }

    /** One card, or null when it is not a release or names nothing usable. */
    private fun card(attrs: String, body: String): MsoneRelease? {
        val classes = CLASS_ATTR.find(attrs)?.let { it.groupValues.getOrNull(1) ?: it.groupValues.getOrNull(2) }.orEmpty()
        val series = when {
            "release-type-series" in classes -> true
            "release-type-movie" in classes -> false
            else -> return null
        }
        val titled = TITLE_LINK.find(body)
        var url = titled?.let { it.groupValues.getOrNull(1) ?: it.groupValues.getOrNull(2) }
        var text = titled?.groupValues?.getOrNull(3)?.let { cleanText(it) }.orEmpty()
        if (releaseUrl(url) == null || text.isBlank()) {
            // The poster anchor links the same page; its text is the picture's alt words at best,
            // so the slug names the release instead (below).
            val any = ANY_LINK.findAll(body).firstOrNull { link ->
                releaseUrl(link.groupValues.getOrNull(1) ?: link.groupValues.getOrNull(2)) != null
            } ?: return null
            url = any.groupValues.getOrNull(1) ?: any.groupValues.getOrNull(2)
            if (text.isBlank()) text = cleanText(any.groupValues.getOrNull(3).orEmpty())
        }
        val pageUrl = releaseUrl(url) ?: return null
        val slug = pageUrl.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        val slugYear = SLUG_YEAR.find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val season = SLUG_SEASON.find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..999 }
            ?: if (series) 1 else null
        // The card's words: "Resident Evil / (its Malayalam name) (2026)". The English title is
        // what a search matches, and the year rides at the end; the slug says both as well.
        val cardYear = TITLE_YEAR.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val named = text.replace(TITLE_YEAR, "").substringBefore(" / ").trim()
        val title = named.ifBlank { slugTitle(slug) }
        if (title.isBlank()) return null
        return MsoneRelease(
            title = title,
            year = cardYear ?: slugYear,
            series = series,
            season = season,
            pageUrl = pageUrl,
        )
    }

    /** The title a slug names: its year and season parts off, hyphens as spaces. */
    private fun slugTitle(slug: String): String {
        var words = slug.replace(SLUG_YEAR, "")
        SLUG_SEASON.find(words)?.let { words = words.substring(0, it.range.first).trimEnd('-') }
        return words.replace('-', ' ').trim()
    }

    /** [href] as a release page address on the site itself, or null when it is anything else. */
    private fun releaseUrl(href: String?): String? {
        val raw = href?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.scheme?.equals("https", ignoreCase = true) != true) return null
        if (host != HOST && !host.endsWith(".$HOST")) return null
        if (!RELEASE_PATH.containsMatchIn(uri.path.orEmpty())) return null
        return raw
    }

    /**
     * The release a video opens with, by the owner's simple rule (2026-11-08): name and year are
     * enough, and for a series the episode's season as well. No release group chasing: where
     * several releases of one title stand, the site's own order decides, and a viewer who needs
     * another picks it in the panel. Null when no release is plainly this title.
     *
     * A year is only held against a release that names one: a season post past the first often
     * names none, and its season is the fact that counts there. For a series the season counts
     * ahead of the year: a season post is filed under the show's first year, while an episode's
     * own name may carry its season's year, so a post of the right name and season stands even
     * when the years differ. A film never matches a series post, nor an episode a film.
     */
    fun match(releases: List<MsoneRelease>, title: String, year: Int?, season: Int?, episode: Int?): MsoneRelease? {
        val wanted = normalise(title)
        if (wanted.isEmpty()) return null
        val episodic = season != null || episode != null
        val candidates = releases.filter { release ->
            release.series == episodic && normalise(release.title) == wanted &&
                (year == null || release.year == null || release.year == year)
        }
        if (!episodic) return candidates.firstOrNull()
        val wantedSeason = season ?: 1
        return candidates.firstOrNull { it.season == wantedSeason }
            ?: releases.firstOrNull { it.series && normalise(it.title) == wanted && it.season == wantedSeason }
    }

    /** Whether [release] is plainly the title named: the same words, in the matcher's reading. */
    fun sameTitle(release: MsoneRelease, title: String): Boolean {
        val wanted = normalise(title)
        return wanted.isNotEmpty() && normalise(release.title) == wanted
    }

    /** A title's words, lowered and spaced, the one reading names are compared in. */
    private fun normalise(title: String): String =
        title.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim().replace(Regex("""\s+"""), " ")

    /**
     * The subtitle's download address on a release page, or null when the page names none this
     * reader trusts. The shapes read for, stated plainly: the page's own download button, an
     * anchor whose click code carries a download manager package (`?wpdmdl=` followed by its
     * number, the address the button itself sends a browser to, answered with the file); an
     * anchor whose address ends in `.srt` (the file itself); or one ending in `.zip` (an archive
     * the app opens for the subtitle inside, episode wise where the file names say). The button
     * beats a stray file link, a direct file beats a looser button and an archive; where several
     * of one kind stand and the episode is known, the one whose file name numbers that episode
     * wins. Only https addresses on the site itself are taken.
     */
    fun downloadHref(pageHtml: String, pageUrl: String, season: Int?, episode: Int?): String? {
        if (pageHtml.isBlank()) return null
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        var button: String? = null
        var looseButton: String? = null
        for (tag in ANCHOR_TAG.findAll(pageHtml)) {
            val text = tag.value
            val click = ONCLICK.find(text)?.let { m ->
                listOf(m.groupValues.getOrNull(1), m.groupValues.getOrNull(2)).firstOrNull { !it.isNullOrBlank() }
            } ?: continue
            val id = WPDM_ID.find(click)?.groupValues?.getOrNull(1) ?: continue
            if (REVIEW_BUTTON.containsMatchIn(text)) {
                if (button == null) button = "$BASE/?wpdmdl=$id"
            } else if (looseButton == null) {
                looseButton = "$BASE/?wpdmdl=$id"
            }
        }
        val files = ArrayList<String>()
        val archives = ArrayList<String>()
        for (anchor in HREF.findAll(pageHtml)) {
            val href = (anchor.groupValues.getOrNull(1) ?: anchor.groupValues.getOrNull(2))
                ?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_000 } ?: continue
            val uri = runCatching { base.resolve(href) }.getOrNull() ?: continue
            val host = uri.host?.lowercase(Locale.ROOT) ?: continue
            if (!uri.scheme.equals("https", ignoreCase = true)) continue
            if (host != HOST && !host.endsWith(".$HOST")) continue
            val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
            when {
                path.endsWith(".srt") -> files += uri.toString()
                path.endsWith(".zip") -> archives += uri.toString()
            }
        }
        return button ?: pick(files, season, episode) ?: looseButton ?: pick(archives, season, episode)
    }

    /** The candidate naming [episode] where one does, else the first; null for an empty list. */
    private fun pick(candidates: List<String>, season: Int?, episode: Int?): String? {
        if (candidates.isEmpty()) return null
        if (episode == null) return candidates.firstOrNull()
        return candidates.firstOrNull { url ->
            val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
                .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val parsed = MediaName.parse(name)
            parsed.episode == episode && (season == null || parsed.season == null || parsed.season == season)
        } ?: candidates.firstOrNull()
    }

    /** A card's words with its tags off and its entities read, the way [MdlPage] reads a page. */
    private fun cleanText(raw: String): String = runCatching {
        val numeric = NUMERIC_ENTITY.replace(ANY_TAG.replace(raw, " ")) { m ->
            m.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 32..0x10FFFF && it !in 0xD800..0xDFFF }
                ?.let { cp ->
                    if (cp < 0x10000) {
                        cp.toChar().toString()
                    } else {
                        val rest = cp - 0x10000
                        String(charArrayOf((0xD800 + (rest shr 10)).toChar(), (0xDC00 + (rest and 0x3FF)).toChar()))
                    }
                } ?: m.value
        }
        numeric
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }.getOrDefault("")
}
