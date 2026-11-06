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
     * A Drive direct address made playable: a small public file answers with the file
     * itself, a large one with a scan confirmation page whose own download address is the
     * playable one. Null where Drive refuses either way (a private file, a quota page).
     */
    suspend fun resolveDrive(direct: StreamLink): StreamLink? = withContext(Dispatchers.IO) {
        val body = fetch(direct.url) ?: return@withContext direct
        val confirm = Regex("""https://drive\.google\.com/uc\?export=download[^"'\s<]*""")
            .find(body)?.value?.replace("&amp;", "&") ?: return@withContext direct
        if (confirm == direct.url) direct else direct.copy(url = confirm)
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
}

/**
 * A link's file saved to the phone's own Downloads folder (owner, 2026-11-06): handed to
 * the system's download manager, which saves into the public folder, shows its own
 * progress in the shade, and needs no permission from the app. Telegram downloads are a
 * different road and stay in the app's folder, as they always have.
 */
object LinkDownloader {

    /** Starts [link]'s download; false where the system refused it. */
    fun download(context: Context, link: StreamLink): Boolean = runCatching {
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

    /** The saved file's name: the link's own name where it carries an ending, else the address's last part. */
    fun fileNameOf(link: StreamLink): String {
        val fromUrl = link.url.substringBefore('?').substringAfterLast('/').ifBlank { "video" }
        val named = link.name.takeIf { '.' in it.substringAfterLast('/') } ?: fromUrl
        val cleaned = named.map { ch -> if (ch in "\\/:*?\"<>|") '_' else ch }.joinToString("").trim()
        return cleaned.ifBlank { "video" }
    }
}
