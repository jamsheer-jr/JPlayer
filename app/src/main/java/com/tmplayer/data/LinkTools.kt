package com.tmplayer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reading a file host's page for the file address inside it (owner, 2026-11-06, best
 * effort): GDFlix and HubCloud put a page between the viewer and the file, and the address
 * is sometimes in the page as served. Those pages are built by script and change often, and
 * some refuse automated readers outright, so a page that yields nothing is reported as a
 * plain failure by the caller, never retried in a loop.
 */
object PageLinkResolver {

    private const val TIMEOUT_MS = 9_000
    private const val MAX_BODY = 512 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** The playable link inside [pageUrl]'s page, or null where the page gives none up. */
    suspend fun resolvePage(pageUrl: String): StreamLink? = withContext(Dispatchers.IO) {
        val body = fetch(pageUrl) ?: return@withContext null
        StreamLinks.pasted(body)
    }

    /**
     * A Drive direct address made playable, as a [DriveOutcome]: a small public file
     * answers with the file itself, a large one with a scan confirmation page whose form
     * is followed (with the cookies Drive set on the way) to the address that serves the
     * file. Drive's own refusal shapes are told apart by [DrivePage]: a quota page is
     * [DriveOutcome.Quota], a sign in page or anything else unreadable is
     * [DriveOutcome.Refused]. A fetch that fails outright leaves the address as it was
     * (Playable with the original link), as this resolver has always done: the player
     * then gives the stream's own answer.
     */
    suspend fun resolveDriveOutcome(direct: StreamLink): DriveOutcome = withContext(Dispatchers.IO) {
        val jar = CookieJar()
        val first = fetchRaw(direct.url, jar, alwaysReadBody = false)
            ?: return@withContext DriveOutcome.Playable(direct)
        // No page came back: the answer is the file itself, playable as it stands.
        val page = first.body ?: return@withContext DriveOutcome.Playable(direct)
        when (val reading = DrivePage.read(page)) {
            is DrivePageReading.Confirm -> {
                val second = fetchRaw(reading.url, jar, alwaysReadBody = false)
                    ?: return@withContext DriveOutcome.Refused
                val secondPage = second.body
                    ?: return@withContext DriveOutcome.Playable(direct.copy(url = second.finalUrl))
                // The follow up answered with another page: read it once more for a
                // direct address inside it, and give up if there is none.
                val deeper = DrivePage.directAddress(secondPage)
                if (deeper != null && deeper != reading.url && deeper != second.finalUrl) {
                    DriveOutcome.Playable(direct.copy(url = deeper))
                } else {
                    DriveOutcome.Refused
                }
            }
            DrivePageReading.Quota -> DriveOutcome.Quota
            DrivePageReading.Refused -> DriveOutcome.Refused
        }
    }

    /** A fetched manifest's answer, for the player's honesty check ([StreamManifest]). */
    class ManifestAnswer(val contentType: String, val body: String)

    /**
     * One manifest address fetched once, small and capped, its content type and text
     * kept together for [StreamManifest.classify]. Null on any failure: the caller then
     * opens the stream unchecked, exactly as it did before the check existed.
     */
    suspend fun fetchManifest(url: String): ManifestAnswer? = withContext(Dispatchers.IO) {
        val answer = fetchRaw(url, CookieJar(), alwaysReadBody = true) ?: return@withContext null
        val body = answer.body ?: return@withContext null
        ManifestAnswer(answer.contentType, body)
    }

    /** One page's text, capped; null on any refusal or failure. */
    private fun fetch(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Range", "bytes=0-${MAX_BODY - 1}")
        try {
            val type = connection.contentType.orEmpty()
            if (type.isNotBlank() && !type.contains("text") && !type.contains("html")) return null
            connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (out.length < MAX_BODY) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    out.append(buffer, 0, minOf(read, MAX_BODY - out.length))
                }
                out.toString()
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** One raw answer: where the redirects ended, what it calls itself, and its text where it was read. */
    private class RawAnswer(val finalUrl: String, val contentType: String, val body: String?)

    /**
     * One address fetched with [jar]'s cookies sent and its own cookies captured,
     * redirects followed by hand (at most [MAX_REDIRECTS]) so the jar sees every hop:
     * Drive sets its cookies on the redirecting answers, which an automatic follow
     * would never show this code. The body is read (capped, as [fetch] reads) where
     * [alwaysReadBody] asks for it, or where the answer calls itself text or a page;
     * a file's answer is not read at all, its content type alone says what it is.
     * Null on any failure.
     */
    private fun fetchRaw(startUrl: String, jar: CookieJar, alwaysReadBody: Boolean): RawAnswer? = runCatching {
        var current = startUrl
        var answer: RawAnswer? = null
        var hops = 0
        while (answer == null) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Range", "bytes=0-${MAX_BODY - 1}")
            jar.header()?.let { connection.setRequestProperty("Cookie", it) }
            try {
                jar.capture(connection.headerFields)
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                if (location != null && code in REDIRECT_CODES && hops < MAX_REDIRECTS) {
                    current = URL(URL(current), location).toString()
                    hops++
                } else {
                    val type = connection.contentType.orEmpty()
                    val readIt = alwaysReadBody || type.isBlank() ||
                        type.contains("text") || type.contains("html")
                    answer = if (!readIt) {
                        RawAnswer(current, type, null)
                    } else {
                        val stream = (if (code >= 400) connection.errorStream else connection.inputStream)
                        if (stream == null) {
                            RawAnswer(current, type, null)
                        } else {
                            val body = stream.bufferedReader().use { reader ->
                                val out = StringBuilder()
                                val buffer = CharArray(8192)
                                while (out.length < MAX_BODY) {
                                    val read = reader.read(buffer)
                                    if (read < 0) break
                                    out.append(buffer, 0, minOf(read, MAX_BODY - out.length))
                                }
                                out.toString()
                            }
                            RawAnswer(current, type, body)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        answer
    }.getOrNull()

    private const val MAX_REDIRECTS = 6
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}

/**
 * The cookies of one resolver call (owner, 2026-11-07): captured from each answer's
 * Set-Cookie headers and sent on the call's follow up requests. Held in memory for the
 * one call only: nothing is persisted, and nothing is shared between calls.
 */
private class CookieJar {

    private val cookies = LinkedHashMap<String, String>()

    /** Reads every Set-Cookie header out of one answer's [headers] into the jar. */
    fun capture(headers: Map<String?, List<String>>) {
        for ((name, values) in headers) {
            if (name == null || !name.equals("Set-Cookie", ignoreCase = true)) continue
            for (value in values) {
                val pair = value.substringBefore(';').trim()
                val split = pair.indexOf('=')
                if (split <= 0) continue
                val key = pair.substring(0, split).trim()
                val held = pair.substring(split + 1).trim()
                if (key.isNotEmpty()) cookies[key] = held
            }
        }
    }

    /** The Cookie header for the next request, or null while the jar is empty. */
    fun header(): String? =
        if (cookies.isEmpty()) null
        else cookies.entries.joinToString("; ") { (key, held) -> "$key=$held" }
}

/** What a Drive address came to, in [PageLinkResolver.resolveDriveOutcome]. */
sealed class DriveOutcome {

    /** The file can be played: [link] carries the address that serves it. */
    data class Playable(val link: StreamLink) : DriveOutcome()

    /** Drive's quota page: too many people are using the file right now. */
    object Quota : DriveOutcome()

    /** Drive refused: a sign in page, a private file, or a page nothing could be read from. */
    object Refused : DriveOutcome()
}

/** What one Drive page says, read by [DrivePage]. */
sealed class DrivePageReading {

    /** A scan confirmation form: [url] is the follow up address built from it. */
    data class Confirm(val url: String) : DrivePageReading()

    /** Drive's quota page. */
    object Quota : DrivePageReading()

    /** A sign in page, or anything else no follow up can be built from. */
    object Refused : DrivePageReading()
}

/**
 * Reading a Drive page for what it offers (owner, 2026-11-07). Pure string work: the
 * page's text in, a [DrivePageReading] out, no network and nothing that can throw, so
 * the shapes Drive answers with can be tested off the wire. Drive's pages change, so
 * every reading is conservative: the confirm form is only believed where its action is
 * Drive's own download host carrying its hidden fields, and the quota page only where
 * its distinctive wordings appear.
 */
object DrivePage {

    /** How much of a page is ever scanned. */
    private const val MAX_PAGE = 512 * 1024

    /** Drive's quota wordings, lowercased: phrases no ordinary page carries. */
    private val QUOTA_PHRASES = listOf(
        "too many users have viewed or downloaded this file",
        "can't view or download this file at this time",
        "cannot view or download this file at this time",
        "download quota for this file",
    )

    /** What [page] offers: a confirmation to follow, a quota refusal, or a plain refusal. */
    fun read(page: String): DrivePageReading {
        val text = page.take(MAX_PAGE)
        confirmUrl(text)?.let { return DrivePageReading.Confirm(it) }
        val flat = text.replace('’', '\'').lowercase()
        if (QUOTA_PHRASES.any { it in flat }) return DrivePageReading.Quota
        return DrivePageReading.Refused
    }

    /**
     * A direct download address named inside [page] (Drive's usercontent host, or the
     * older uc shape), with its entities unescaped; null where the page names none.
     */
    fun directAddress(page: String): String? {
        val text = page.take(MAX_PAGE)
        for (prefix in listOf("https://drive.usercontent.google.com/download", "https://drive.google.com/uc?export=download")) {
            var at = text.indexOf(prefix)
            while (at >= 0) {
                var end = at
                while (end < text.length && text[end] !in "\"'<> \t\r\n") end++
                val found = text.substring(at, end).replace("&amp;", "&")
                if (found.length > prefix.length) return found
                at = text.indexOf(prefix, at + 1)
            }
        }
        return null
    }

    /**
     * The follow up address a confirmation form in [page] builds: the form whose action
     * is Drive's usercontent download address, plus its hidden fields (id, confirm,
     * export and any others) in the page's own order. Null where no such form is found,
     * or it carries no hidden field to follow with.
     */
    private fun confirmUrl(page: String): String? {
        val lower = page.lowercase()
        var from = 0
        while (true) {
            val formAt = lower.indexOf("<form", from)
            if (formAt < 0) return null
            val tagEnd = page.indexOf('>', formAt)
            if (tagEnd < 0) return null
            val action = attributes(page.substring(formAt, tagEnd + 1))["action"].orEmpty()
            if ("drive.usercontent.google.com" in action) {
                val close = lower.indexOf("</form>", tagEnd).let { if (it < 0) page.length else it }
                val fields = hiddenFields(page, lower, tagEnd + 1, close)
                if (fields.isEmpty()) return null
                val separator = if ('?' in action) "&" else "?"
                return action + separator + fields.joinToString("&") { (name, value) ->
                    "${encodeField(name)}=${encodeField(value)}"
                }
            }
            from = tagEnd + 1
        }
    }

    /** The hidden inputs between [start] and [end] of [page], as name and value pairs in page order. */
    private fun hiddenFields(page: String, lower: String, start: Int, end: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var at = start
        while (true) {
            val inputAt = lower.indexOf("<input", at)
            if (inputAt < 0 || inputAt >= end) return out
            val tagEnd = page.indexOf('>', inputAt)
            if (tagEnd < 0 || tagEnd > end) return out
            val attrs = attributes(page.substring(inputAt, tagEnd + 1))
            val type = attrs["type"]?.lowercase()
            val name = attrs["name"]
            val value = attrs["value"]
            if ((type == null || type == "hidden") && !name.isNullOrEmpty() && value != null) {
                out += name to value
            }
            at = tagEnd + 1
        }
    }

    /**
     * A tag's attributes by lowercase name, read by hand: double quoted, single quoted
     * and bare values are all taken, and the common entities in a value are unescaped.
     */
    private fun attributes(tag: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < tag.length && !tag[i].isWhitespace() && tag[i] != '>' && tag[i] != '/') i++
        while (i < tag.length) {
            while (i < tag.length && (tag[i].isWhitespace() || tag[i] == '/')) i++
            if (i >= tag.length || tag[i] == '>') break
            var nameEnd = i
            while (nameEnd < tag.length && tag[nameEnd] != '=' && !tag[nameEnd].isWhitespace() && tag[nameEnd] != '>' && tag[nameEnd] != '/') nameEnd++
            val name = tag.substring(i, nameEnd).lowercase()
            i = nameEnd
            while (i < tag.length && tag[i].isWhitespace()) i++
            var value = ""
            if (i < tag.length && tag[i] == '=') {
                i++
                while (i < tag.length && tag[i].isWhitespace()) i++
                if (i < tag.length && (tag[i] == '"' || tag[i] == '\'')) {
                    val quote = tag[i]
                    i++
                    val valueStart = i
                    while (i < tag.length && tag[i] != quote) i++
                    value = tag.substring(valueStart, i)
                    if (i < tag.length) i++
                } else {
                    val valueStart = i
                    while (i < tag.length && !tag[i].isWhitespace() && tag[i] != '>') i++
                    value = tag.substring(valueStart, i)
                }
            }
            if (name.isNotEmpty()) out[name] = unescape(value)
        }
        return out
    }

    /** The entities a Drive page writes into its addresses and field values, undone. */
    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    /** One form field's name or value, percent encoded for the address it joins. */
    private fun encodeField(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = (byte + 256) % 256
            val ch = b.toChar()
            val plain = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            if (plain) out.append(ch) else out.append('%').append(b.toString(16).uppercase().padStart(2, '0'))
        }
        return out.toString()
    }
}

/**
 * A link's file saved to the phone's own Downloads folder (owner, 2026-11-06): handed to
 * the system's download manager, which saves into the public folder, shows its own
 * progress in the shade, and needs no permission from the app. Telegram downloads are a
 * different road and stay in the app's folder, as they always have.
 *
 * Background and resume are the system's own here, and they are narrower than the
 * Telegram road's. The system manager keeps fetching with this app closed, paused by a
 * lost connection and carried on when it returns, which this app neither drives nor
 * reports: its progress and its failures live in the system's own Downloads listing, not
 * on this app's Downloads screen, and no size is known for a link, so no room check can
 * be made before it starts. A system download that has failed for good, a full disk
 * included, cannot be resumed through the manager's API: pressing Download on the link
 * again starts a new download from the beginning. That restart is this road's only
 * retry, and it is a restart, not a resume.
 */
object LinkDownloader {

    /**
     * Starts [link]'s download; false where the system refused it.
     *
     * A refusal is said out loud here, because both callers ignore the result: a press
     * that silently did nothing reads exactly like a download that started.
     */
    fun download(context: Context, link: StreamLink): Boolean {
        val started = runCatching {
            val manager = context.getSystemService(DownloadManager::class.java) ?: return false
            val request = DownloadManager.Request(Uri.parse(link.url))
                .setTitle(fileNameOf(link))
                .setDescription(link.source)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileNameOf(link))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            manager.enqueue(request)
            true
        }.getOrDefault(false)
        if (!started) {
            com.tmplayer.ui.components.showMessage(context, com.tmplayer.i18n.L.downloadsLinkDownloadRefused)
        }
        return started
    }

    /** The saved file's name: the link's own name where it carries an ending, else the address's last part. */
    fun fileNameOf(link: StreamLink): String {
        val fromUrl = link.url.substringBefore('?').substringAfterLast('/').ifBlank { "video" }
        val named = link.name.takeIf { '.' in it.substringAfterLast('/') } ?: fromUrl
        val cleaned = named.map { ch -> if (ch in "\\/:*?\"<>|") '_' else ch }.joinToString("").trim()
        return cleaned.ifBlank { "video" }
    }
}
