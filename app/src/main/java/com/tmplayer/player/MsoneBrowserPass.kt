package com.tmplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The browser pass for an MSone release page (item 5, v1.0.16), the platform half of
 * [com.tmplayer.online.MsoneSiteApi]'s second way in. A real browser look (2026-11-08)
 * showed the site's release pages carry no plain download link at all: the only control
 * is a script driven anchor labelled with the Malayalam word for Subtitle, href "#", and
 * the download address stands in its click code as a download manager package the plain
 * reader now reads for itself first. So the page is loaded here in a WebView that never
 * goes on screen, the control is clicked by script, and whatever the page then asks for
 * (a request or a download whose address ends in .srt or .zip, or the package address
 * itself) is captured and fetched with the WebView session's cookies through a plain
 * connection. Bounded end to end; anything that does not arrive in time, or a device
 * with no WebView at all, answers null and the caller keeps its honest failure.
 */
object MsoneBrowserPass {

    /** The whole pass gets this long, clicks and fetch included. */
    private const val TOTAL_MS = 20_000L

    /** A second click when the first built the download area without firing it. */
    private const val RECLICK_MS = 6_000L

    /** One byte past the subtitle ceiling is already not a subtitle. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * The capture fetch's own time budget, inside [TOTAL_MS] (owner, v1.0.19): its read
     * timeout alone restarts with every byte, so a trickling answer held the fetch, and
     * its detached thread, past the whole pass. The loop checks a deadline itself.
     */
    private const val FETCH_MS = 12_000L

    /**
     * The release page's subtitle control, found by its own marks, never by a loose word: the
     * Malayalam word for Subtitle also stands in the page's menu links, and an earlier script
     * that clicked the first element containing it clicked a menu link and wandered the site
     * while the download never started (owner's phone, 2026-11-08). The button carries the id
     * `review-button` and a click handler naming its download package; failing those, only an
     * element whose whole label is the word counts.
     */
    private const val CLICK_SCRIPT = """
        (function() {
          var btn = document.querySelector('#review-button');
          if (btn) { btn.click(); return 'clicked'; }
          var links = document.querySelectorAll('a, button');
          var i;
          for (i = 0; i < links.length; i++) {
            var oc = links[i].getAttribute('onclick') || '';
            if (oc.indexOf('wpdmdl') >= 0) { links[i].click(); return 'clicked'; }
          }
          for (i = 0; i < links.length; i++) {
            var text = (links[i].textContent || '').trim();
            if (text === 'പരിഭാഷ') { links[i].click(); return 'clicked'; }
          }
          return 'none';
        })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(context: Context, pageUrl: String): ByteArray? {
        val appContext = context.applicationContext
        return withContext(Dispatchers.Main) {
            val done = CompletableDeferred<ByteArray?>()
            val webView = runCatching {
                WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
            }.getOrNull() ?: return@withContext null
            val userAgent = webView.settings.userAgentString.orEmpty()
            val ioScope = CoroutineScope(Dispatchers.IO)
            // The capture in flight, if any: the pass's end tears its connection down, so a
            // timed out pass leaves no blocked read running behind it (owner, v1.0.19).
            var captureJob: Job? = null
            var liveConnection: HttpURLConnection? = null

            fun capture(url: String) {
                if (done.isCompleted) return
                captureJob = ioScope.launch {
                    val bytes = runCatching { fetchBytes(url, userAgent) { liveConnection = it } }.getOrNull()
                    done.complete(bytes)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url?.toString().orEmpty()
                    if (!isSubtitleFile(url)) return null
                    capture(url)
                    // The file is fetched by the capture; the view itself needs nothing.
                    return WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url?.toString().orEmpty()
                    // The control's script may send the whole view at the file: a
                    // navigation, not a request the interceptor above ever sees. Take
                    // the address for the capture and keep the view where it is.
                    if (request.isForMainFrame && (isSubtitleFile(url) || url.contains("download", ignoreCase = true) || url.contains("wpdmdl="))) {
                        capture(url)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(CLICK_SCRIPT, null)
                }
            }
            webView.setDownloadListener { url, _, _, _, _ -> if (url != null) capture(url) }

            val clicker = CoroutineScope(Dispatchers.Main).launch {
                delay(RECLICK_MS)
                if (!done.isCompleted) webView.evaluateJavascript(CLICK_SCRIPT, null)
            }
            try {
                webView.loadUrl(pageUrl)
                withTimeoutOrNull(TOTAL_MS) { done.await() } ?: null
            } finally {
                clicker.cancel()
                captureJob?.cancel()
                runCatching { liveConnection?.disconnect() }
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
            }
        }
    }

    /** Whether [url]'s path names a subtitle file, a .srt or the .zip releases come in. */
    private fun isSubtitleFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return path.endsWith(".srt") || path.endsWith(".zip")
    }

    /**
     * [url]'s bytes, with the WebView session's cookies; null past the ceiling, past
     * [FETCH_MS] in all, or on any failure. Read in chunks with the size and the deadline
     * checked as it grows: a whole readBytes() under a per read timeout could be held
     * forever by a host that trickles (owner, v1.0.19). [register] hears the connection
     * as it opens, so the pass's end can tear a stuck read down.
     */
    private fun fetchBytes(url: String, userAgent: String, register: (HttpURLConnection) -> Unit = {}): ByteArray? {
        val connection = URL(url).openConnection() as? HttpURLConnection ?: return null
        register(connection)
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (userAgent.isNotBlank()) connection.setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            if (connection.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + FETCH_MS
            connection.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                    if (out.size() > MAX_BYTES) return null
                    if (System.currentTimeMillis() > deadline) return null
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            connection.disconnect()
        }
    }
}
