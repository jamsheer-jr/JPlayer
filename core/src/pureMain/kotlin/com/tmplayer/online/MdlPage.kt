package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/**
 * The facts read off one MyDramaList title page. [path] is the page's own address path
 * ("/32986-alchemy-of-souls") and doubles as the title's id for this provider.
 */
data class MdlFacts(
    val path: String,
    val title: String,
    val posterUrl: String?,
    val year: Int?,
    val genres: List<String> = emptyList(),
    /** What the page itself calls the title, where it says; null leaves the kind to the query. */
    val kind: MetaKind? = null,
    /** The country the page's details list names ("South Korea"), where it names one. */
    val country: String? = null,
)

/**
 * MyDramaList's pages, read as text. The site has no API, so a title the other providers do
 * not know is found by reading its search page and then the top result's page. Best effort by
 * nature: the site can change its markup, and everything here is read defensively, so a page
 * that yields no title or no poster yields nothing at all rather than half a fact.
 *
 * Pure string work: no network, no Android types, nothing that can throw on a strange page.
 */
object MdlPage {

    /** A title page's path: a number, a hyphen and the slug. People and news live elsewhere. */
    private val TITLE_PATH = Regex("""/\d+-[a-z0-9][a-z0-9-]*""")

    private val TITLE_HREF = Regex("""href\s*=\s*["'](/\d+-[a-z0-9][a-z0-9-]*)/?["']""", RegexOption.IGNORE_CASE)

    private val META_TAG = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val ATTRIBUTE = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private val LD_SCRIPT = Regex(
        """<script\b[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val TITLE_TAG = Regex("""<title[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val TRAILING_YEAR = Regex("""\((\d{4})\)\s*$""")

    private val YEAR_IN_TEXT = Regex("""\b(19\d\d|20\d\d)\b""")

    private val NUMERIC_ENTITY = Regex("""&#(\d{1,7});""")

    private val ANCHOR_TEXT = Regex("""<a\b[^>]*>([^<]*)</a>""", RegexOption.IGNORE_CASE)

    private val ANY_TAG = Regex("""<[^>]+>""")

    private val SHOW_LABEL = Regex(""">\s*(Drama|TV Show|Special):\s*<""", RegexOption.IGNORE_CASE)

    private val FILM_LABEL = Regex(""">\s*Movie:\s*<""", RegexOption.IGNORE_CASE)

    /** More genres than this is a tag list, not a facts line. */
    private const val GENRE_LIMIT = 12

    /**
     * The top title result's page path on a search page, or null when it lists none. The
     * results begin at the page's heading; links in the furniture before it (a trending
     * title in the masthead, say) are not results. A path is a number and a slug at the
     * site's root, which is how title pages are addressed; people live under "/people/".
     */
    fun topResultPath(searchHtml: String): String? {
        if (searchHtml.isBlank()) return null
        val from = searchHtml.indexOf("<h1").let { if (it >= 0) it else 0 }
        return TITLE_HREF.find(searchHtml, from)?.groupValues?.getOrNull(1)
            ?: TITLE_HREF.find(searchHtml)?.groupValues?.getOrNull(1)
    }

    /**
     * The facts on the title page at [path], or null when it has no usable title or no poster.
     * Title and poster come from the OpenGraph tags first; the year and the genres from the
     * page's JSON-LD where it carries any, else from its details list.
     */
    fun facts(pageHtml: String, path: String): MdlFacts? {
        if (pageHtml.isBlank() || !TITLE_PATH.matches(path)) return null
        val ld = jsonLd(pageHtml)
        val titled = titledYear(meta(pageHtml, "og:title"))
            ?: titledYear(ld?.optString("name"))
            ?: titledYear(titleTag(pageHtml))
        val title = titled?.first?.takeIf { it.isNotBlank() } ?: return null
        val poster = meta(pageHtml, "og:image")?.takeIf { it.startsWith("https://") }
            ?: meta(pageHtml, "twitter:image")?.takeIf { it.startsWith("https://") }
            ?: return null
        val year = yearOf(ld?.optString("datePublished"))
            ?: yearOf(ld?.optString("startDate"))
            ?: titled?.second
            ?: detailsYear(pageHtml)
        val genres = ldGenres(ld).ifEmpty { detailsGenres(pageHtml) }
        val kind = ldKind(ld) ?: detailsKind(pageHtml)
        return MdlFacts(path = path, title = title, posterUrl = poster, year = year, genres = genres, kind = kind, country = detailsCountry(pageHtml))
    }

    /** The original language a title from [country] is shelved by: MDL's own country names. */
    fun languageOf(country: String?): String? = when (country?.trim()?.lowercase()) {
        "south korea" -> "ko"
        "china", "taiwan", "hong kong" -> "zh"
        "japan" -> "ja"
        "thailand" -> "th"
        "philippines" -> "tl"
        "vietnam" -> "vi"
        else -> null
    }

    // ---- the OpenGraph tags -------------------------------------------------------------------

    /** The `content` of the first meta tag naming [property] (as `property` or as `name`), decoded. */
    private fun meta(html: String, property: String): String? {
        for (tag in META_TAG.findAll(html)) {
            val attrs = HashMap<String, String>()
            for (attr in ATTRIBUTE.findAll(tag.value)) {
                val name = attr.groupValues.getOrNull(1)?.lowercase() ?: continue
                val value = attr.groupValues.getOrNull(2) ?: attr.groupValues.getOrNull(3) ?: continue
                if (name !in attrs) attrs[name] = value
            }
            if (attrs["property"] == property || attrs["name"] == property) {
                return attrs["content"]?.let { decode(it).trim() }?.ifBlank { null } ?: continue
            }
        }
        return null
    }

    private fun titleTag(html: String): String? =
        TITLE_TAG.find(html)?.groupValues?.getOrNull(1)?.let { decode(it).trim() }?.ifBlank { null }

    /**
     * A page title and the year it carries: "Hidden Moon (2023)", with a trailing site name
     * (" - MyDramaList") dropped. Null when there is no title at all.
     */
    private fun titledYear(raw: String?): Pair<String, Int?>? {
        var text = decode(raw.orEmpty()).trim()
        if (text.isBlank()) return null
        for (suffix in listOf(" - MyDramaList", " | MyDramaList")) {
            if (text.endsWith(suffix)) text = text.dropLast(suffix.length).trim()
        }
        val match = TRAILING_YEAR.find(text)
        val year = match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val title = if (match != null && year != null) text.substring(0, match.range.first).trim() else text
        return title to year
    }

    /** The common HTML entities in a page's words, numeric ones included; the rest is left as written. */
    private fun decode(text: String): String = runCatching {
        val numeric = NUMERIC_ENTITY.replace(text) { m ->
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
    }.getOrDefault(text)

    // ---- the JSON-LD block ----------------------------------------------------------------------

    /**
     * The page's title-like JSON-LD object (the first typed one of its `@graph`): the first
     * block carrying a name or genres, since a page may lead with another block (breadcrumbs)
     * that says nothing about the title. The first block of any kind, when none is title-like.
     */
    private fun jsonLd(html: String): JSONObject? {
        fun parse(text: String): JSONObject? {
            runCatching { JSONObject(text) }.getOrNull()?.let { return unwrap(it) }
            runCatching { JSONArray(text) }.getOrNull()?.let { list ->
                return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull()?.let { unwrap(it) }
            }
            return null
        }
        var first: JSONObject? = null
        for (block in LD_SCRIPT.findAll(html)) {
            val text = block.groupValues.getOrNull(1)?.trim().orEmpty()
            if (text.isBlank()) continue
            val candidate = parse(text) ?: continue
            if (first == null) first = candidate
            if (candidate.has("name") || candidate.has("genre")) return candidate
        }
        return first
    }

    private fun unwrap(json: JSONObject): JSONObject {
        val graph = json.optJSONArray("@graph") ?: return json
        return (0 until graph.length()).mapNotNull { graph.optJSONObject(it) }.firstOrNull { it.has("@type") } ?: json
    }

    private fun yearOf(date: String?): Int? =
        date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }

    private fun ldGenres(ld: JSONObject?): List<String> {
        ld ?: return emptyList()
        val list = ld.optJSONArray("genre")
        val names = if (list != null) {
            (0 until list.length()).map { list.optString(it) }
        } else {
            ld.optString("genre").split(',')
        }
        return names.map { decode(it).trim() }.filter { it.isNotBlank() }.distinct().take(GENRE_LIMIT)
    }

    private fun ldKind(ld: JSONObject?): MetaKind? {
        ld ?: return null
        val types = ArrayList<String>()
        ld.optString("@type").takeIf { it.isNotBlank() }?.let { types += it }
        ld.optJSONArray("@type")?.let { list -> (0 until list.length()).mapTo(types) { list.optString(it) } }
        return when {
            types.any { "series" in it.lowercase() || it.equals("TVShow", ignoreCase = true) } -> MetaKind.Show
            types.any { it.equals("Movie", ignoreCase = true) } -> MetaKind.Film
            else -> null
        }
    }

    // ---- the details list -----------------------------------------------------------------------

    /** The first year in the details list's aired or release line ("Aired: Jun 18, 2022 - ..."). */
    private fun detailsYear(html: String): Int? {
        for (label in listOf("Release Date:", "Aired:")) {
            val at = html.indexOf(label)
            if (at < 0) continue
            val segment = html.substring(at, minOf(html.length, at + 200))
            YEAR_IN_TEXT.find(segment)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1900..2100 }?.let { return it }
        }
        return null
    }

    /**
     * The genres in the details list: the links of its Genres row, or the row's own words
     * split on commas where it has no links. The row ends at the next row's label.
     */
    private fun detailsGenres(html: String): List<String> {
        val label = "Genres:"
        val at = html.indexOf(label)
        if (at < 0) return emptyList()
        var segment = html.substring(at, minOf(html.length, at + 1500))
        val next = segment.indexOf("<b>", label.length).let { if (it > 0) it else segment.length }
        segment = segment.substring(0, next)
        val linked = ANCHOR_TEXT.findAll(segment)
            .map { decode(it.groupValues.getOrNull(1).orEmpty()).trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (linked.isNotEmpty()) return linked.distinct().take(GENRE_LIMIT)
        val plain = decode(ANY_TAG.replace(segment.substring(label.length), " "))
        return plain.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().take(GENRE_LIMIT)
    }

    /**
     * The country in the details list's Country row, read the way [detailsGenres] reads its
     * row: the words after the label, up to the next row's label, tags stripped. The first
     * named country where a page names more than one. Null where the page names none.
     */
    private fun detailsCountry(html: String): String? {
        val label = "Country:"
        val at = html.indexOf(label)
        if (at < 0) return null
        var segment = html.substring(at, minOf(html.length, at + 300))
        val next = segment.indexOf("<b>", label.length).let { if (it > 0) it else segment.length }
        segment = segment.substring(0, next)
        return decode(ANY_TAG.replace(segment.substring(label.length), " "))
            .split(',').firstOrNull()?.trim()?.ifBlank { null }
    }

    /** The details list opens with what the title is: "Drama:", "TV Show:", "Special:" or "Movie:". */
    private fun detailsKind(html: String): MetaKind? = when {
        SHOW_LABEL.containsMatchIn(html) -> MetaKind.Show
        FILM_LABEL.containsMatchIn(html) -> MetaKind.Film
        else -> null
    }
}
