package com.tmplayer.player

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import com.tmplayer.data.guardedLifecycleScope as lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.tmplayer.i18n.L
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleText
import com.tmplayer.online.SubtitleTranslator
import com.tmplayer.online.UrlConnectionTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Subtitle files loaded from the phone over the video playing now: the phone's version of the
 * desktop's drop a subtitle on the picture.
 *
 * The file comes through the system document picker (`ACTION_OPEN_DOCUMENT`), so no storage
 * permission is asked for, and is copied into the app's cache before Media3 sees it: a content
 * URI's grant ends with this activity, and a copy is also the moment to turn a MicroDVD `.sub`
 * into SubRip ([ExternalSubtitle]). Media3 takes it as a [Media3Item.SubtitleConfiguration] on the
 * video's media item, so loading one rebuilds the item at the current position, and the new track
 * is selected as soon as the player reports it.
 *
 * Built as a field of the activity, because the document picker's result has to be registered
 * before the activity starts.
 */
@OptIn(UnstableApi::class)
class SubtitleFiles(
    private val activity: FragmentActivity,
    private val player: () -> ExoPlayer?,
    private val say: (String) -> Unit,
) {
    private val loaded = mutableListOf<Media3Item.SubtitleConfiguration>()

    /** The ids of the entries [attachFile] brought in: files downloaded for one video. */
    private val downloadedIds = mutableSetOf<String>()

    /** Entry ids, counted up and never reused, so forgetting an entry cannot clash. */
    private var nextId = 0

    /** Every file loaded so far: what a cast sends to the TV as side tracks (CP73). */
    val files: List<Media3Item.SubtitleConfiguration> get() = loaded.toList()

    /**
     * Forgets the files downloaded for the video being left (owner, v1.0.25): a
     * subtitle fetched for one episode is that episode's, and must not stand in
     * the next episode's list. Files the viewer loaded by hand this session stay,
     * as they always have.
     */
    fun forgetDownloaded() {
        if (downloadedIds.isEmpty()) return
        loaded.removeAll { cfg -> cfg.id?.let { it in downloadedIds } == true }
        if (pending?.let { it.first in downloadedIds } == true) pending = null
        downloadedIds.clear()
    }

    /**
     * Set while the video is on a TV: a file loaded then goes to the cast (true when it took it)
     * instead of into the stopped local player, which must not start playing under the cast.
     */
    var onCastFile: ((file: File, mimeType: String, label: String) -> Boolean)? = null

    /**
     * Set by the activity: the entries of a subtitle pack the viewer loaded by
     * hand, shown for a pick (owner, v1.0.25). Answers the picked entry's name,
     * or null when the viewer picks none.
     */
    var entryChooser: (suspend (List<String>) -> String?)? = null

    /** The id of the file just loaded, until its track shows up and is selected. */
    private var pending: Pair<String, String>? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    /** Opens the document picker. Every file is offered; the extension is checked on the way back. */
    fun pick() {
        runCatching { picker.launch(arrayOf("*/*")) }
            .onFailure { say(L.tracksNoFilePicker) }
    }

    /**
     * What the media session shows of the video (the notification, the lock screen), carried into
     * every rebuild of the item so loading a subtitle file does not blank it.
     */
    var metadata: MediaMetadata = MediaMetadata.EMPTY

    /** The video's media item with every subtitle file loaded so far, for building or rebuilding. */
    fun item(video: Uri): Media3Item = Media3Item.Builder()
        .setUri(video)
        .setSubtitleConfigurations(loaded.toList())
        .setMediaMetadata(metadata)
        .build()

    /** Selects a just loaded file's track once the player lists it. Call from onTracksChanged. */
    fun onTracksChanged(tracks: Tracks) {
        val (id, label) = pending ?: return
        val exo = player() ?: return
        // By the id first: a label can be the same as an embedded track's name ("English.srt"
        // over a track called that), and the file loaded tenth is "external-10", which also
        // contains "external-1". The label only where Media3 kept no id.
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).map { group to it } }
        val found = text.firstOrNull { (group, index) -> ExternalSubtitle.isTrackId(group.getTrackFormat(index).id, id) }
            ?: text.firstOrNull { (group, index) -> group.getTrackFormat(index).let { it.id == null && it.label == label } }
            ?: return
        pending = null
        val (group, index) = found
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    /** A subtitle already on the disk as UTF-8, such as one downloaded from online, loaded as is. */
    fun attachFile(file: File, label: String) {
        val mime = ExternalSubtitle.mimeTypeOf(ExternalSubtitle.extensionOf(file.name))
        if (mime == null) {
            say(L.onlineNoticeUnreadable)
            return
        }
        attach(Copy.Done(file, mime), label, fromDownload = true)
    }

    private fun load(uri: Uri) {
        activity.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: L.tracksFileFallbackName
            val extension = ExternalSubtitle.extensionOf(name)
            if (!ExternalSubtitle.accepts(name)) {
                say(L.tracksChooseFile)
                return@launch
            }
            // Read on the main thread before the copy: the player belongs to it.
            val fps = player()?.videoFormat?.frameRate?.let { FrameRates.plausible(it).toDouble() }?.takeIf { it > 0 } ?: ExternalSubtitle.DEFAULT_FPS
            val copied = withContext(Dispatchers.IO) { runCatching { copy(uri, name, extension, fps) }.getOrNull() }
            when (copied) {
                null -> say(L.tracksFileUnreadable(name))
                is Copy.Refused -> copied.reason?.let(::say)
                is Copy.Done -> {
                    val (ready, label) = maybeTranslate(copied, copied.entryName ?: name)
                    attach(ready, label)
                }
            }
        }
    }

    /**
     * A subtitle from a link the viewer pastes: the same road as a picked file from the download
     * on. The link must be a full http or https address; what comes back is capped at the same
     * size as a picked file, must name a subtitle (in the link itself or in the name the server
     * gives the download), and is decoded, converted and translated by the very code a picked
     * file goes through ([store], [maybeTranslate]), so a link and a file behave alike.
     */
    fun loadFromUrl(raw: String) {
        val link = raw.trim()
        val uri = runCatching { URI(link) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            say(L.tracksUrlInvalid)
            return
        }
        activity.lifecycleScope.launch {
            say(L.tracksUrlDownloading)
            // Read on the main thread before the fetch: the player belongs to it.
            val fps = player()?.videoFormat?.frameRate?.let { FrameRates.plausible(it).toDouble() }?.takeIf { it > 0 } ?: ExternalSubtitle.DEFAULT_FPS
            val fetched = withContext(Dispatchers.IO) { runCatching { fetch(link) }.getOrNull() }
            when (fetched) {
                null -> say(L.tracksUrlFailed)
                is Fetch.TooBig -> say(L.tracksFileTooBig)
                is Fetch.Done -> {
                    val name = fetched.name ?: L.tracksFileFallbackName
                    if (!ExternalSubtitle.accepts(name) && !SubtitleText.isZip(fetched.bytes)) {
                        say(L.tracksUrlNotSubtitle)
                        return@launch
                    }
                    val stored = withContext(Dispatchers.IO) {
                        runCatching { store(fetched.bytes, name, ExternalSubtitle.extensionOf(name), fps) }.getOrNull()
                    }
                    when (stored) {
                        null -> say(L.tracksUrlFailed)
                        is Copy.Refused -> stored.reason?.let(::say)
                        is Copy.Done -> {
                            val (ready, label) = maybeTranslate(stored, stored.entryName ?: name)
                            attach(ready, label)
                        }
                    }
                }
            }
        }
    }

    /**
     * The stored file carried into the viewer's chosen language (the choice made in
     * the player for this video, or the one kept in Settings), or itself when translation
     * is off, the file is already in that language, or
     * its format is not one the translator takes. The translation is kept under a name made
     * from the file's own hash and the language, so loading the same file again finds it
     * instead of translating it again; the original file is never touched, and a failed
     * translation loads the original with a sentence saying so.
     */
    private suspend fun maybeTranslate(done: Copy.Done, name: String): Pair<Copy.Done, String> {
        val target = OnlineSubtitles.current?.translateToNow()?.takeIf { it.isNotBlank() }
            ?: return done to name
        if (SubtitleTranslator.sameLanguage(ExternalSubtitle.languageOf(name), target)) return done to name
        val extension = ExternalSubtitle.extensionOf(done.file.name)
        val mime = ExternalSubtitle.mimeTypeOf(extension) ?: return done to name
        val label = translatedName(name, target, extension)
        val kept = withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(activity.cacheDir, "subtitles").apply { mkdirs() }
                translatedFile(folder, done.file, target, extension).takeIf { it.isFile }
            }.getOrNull()
        }
        if (kept != null) return Copy.Done(kept, mime) to label
        say(L.tracksTranslating)
        val made = withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(activity.cacheDir, "subtitles").apply { mkdirs() }
                val text = SubtitleText.Text(extension, done.file.readText())
                val translated = SubtitleTranslator(UrlConnectionTransport).translate(text, target)
                    ?: return@runCatching null
                translatedFile(folder, done.file, target, extension).apply { writeText(translated.text) }
            }.getOrNull()
        }
        if (made != null) return Copy.Done(made, mime) to label
        say(L.tracksTranslateFailed)
        return done to name
    }

    /** The cache file a translation of [source] into [target] is kept in, made from its hash. */
    private fun translatedFile(folder: File, source: File, target: String, extension: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }.take(16)
        return File(folder, "tr-$hash-$target.$extension")
    }

    /** A loaded file's name with the translation's language in it, the way side files are named. */
    private fun translatedName(name: String, target: String, extension: String): String =
        name.substringBeforeLast('.', name) + ".$target.$extension"

    private sealed interface Fetch {
        data class Done(val bytes: ByteArray, val name: String?) : Fetch
        data object TooBig : Fetch
    }

    /**
     * The bytes at [link], following redirects the way a browser does (a subtitle host's http
     * address commonly answers with its https one, which HttpURLConnection will not follow on
     * its own). Null when the address cannot be read or does not answer with a file. The name
     * is the download's own when the server states one, else the link's last part.
     */
    private fun fetch(link: String): Fetch? {
        var address = link
        repeat(MAX_REDIRECTS + 1) {
            val connection = (URI(address).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "TMPlayer")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() } ?: return null
                    address = runCatching { URI(address).resolve(next).toString() }.getOrNull() ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        out.write(chunk, 0, read)
                        if (out.size() > ExternalSubtitle.MAX_BYTES) return Fetch.TooBig
                    }
                    out.toByteArray()
                }
                val named = connection.getHeaderField("Content-Disposition")
                    ?.let { dispositionName(it) }
                val fromLink = address.substringAfterLast('/').substringBefore('?').substringBefore('#')
                    .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    .takeIf { it.isNotBlank() }
                return Fetch.Done(bytes, named ?: fromLink)
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** The file name a `Content-Disposition` header states, quoted or plain; null when it states none. */
    private fun dispositionName(header: String): String? {
        val at = header.indexOf("filename=")
        if (at < 0) return null
        val rest = header.substring(at + "filename=".length).trim()
        val name = if (rest.startsWith("\"")) rest.substring(1).substringBefore('"') else rest.substringBefore(';').trim()
        return name.substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun attach(copy: Copy.Done, name: String, fromDownload: Boolean = false) {
        val exo = player() ?: return
        val video = exo.currentMediaItem?.localConfiguration?.uri ?: return
        val id = "external-${++nextId}"
        if (fromDownload) downloadedIds += id
        loaded += Media3Item.SubtitleConfiguration.Builder(Uri.fromFile(copy.file))
            .setMimeType(copy.mimeType)
            .setLabel(name)
            .setId(id)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        if (onCastFile?.invoke(copy.file, copy.mimeType, name) == true) {
            // Kept in [loaded] for when the video comes back here; the cast shows it now.
            say(L.tracksFileLoaded(name))
            return
        }
        pending = id to name
        exo.setMediaItem(item(video), exo.currentPosition)
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        say(L.tracksFileLoaded(name))
    }

    private sealed interface Copy {
        /** [entryName]: the file inside an archive this came out of, where it did. */
        data class Done(val file: File, val mimeType: String, val entryName: String? = null) : Copy

        /** A null [reason] says nothing: the viewer stopped the load themselves. */
        data class Refused(val reason: String?) : Copy
    }

    private suspend fun copy(uri: Uri, name: String, extension: String, fps: Double): Copy {
        val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                if (out.size() > ExternalSubtitle.MAX_BYTES) {
                    return Copy.Refused(L.tracksFileTooBig)
                }
            }
            out.toByteArray()
        } ?: return Copy.Refused(L.tracksFileUnopenable)
        return store(bytes, name, extension, fps)
    }

    /**
     * Bytes from the picker or from a link, kept as a subtitle file in the cache: the one road
     * both take, so both get the same decoding, the same MicroDVD conversion and the same
     * refusals. A zip is opened for the subtitle inside it ([SubtitleText.fromArchive]).
     */
    private suspend fun store(bytes: ByteArray, name: String, extension: String, fps: Double): Copy {
        val folder = File(activity.cacheDir, "subtitles").apply { mkdirs() }
        if (SubtitleText.isZip(bytes)) {
            val language = ExternalSubtitle.languageOf(name) ?: Locale.getDefault().language
            // A pack holds a subtitle per episode: loaded by hand, the viewer
            // picks the one they want from the pack's own names (owner, v1.0.25),
            // instead of the app taking the first entry and the rest standing
            // nowhere. One entry, or no chooser wired, reads as it always has.
            val entries = SubtitleText.archiveEntries(bytes)
            val chooser = entryChooser
            val inside = when {
                entries.size > 1 && chooser != null -> {
                    val picked = chooser(entries) ?: return Copy.Refused(null)
                    runCatching { SubtitleText.fromArchiveEntry(bytes, picked, language) }.getOrNull()
                }
                else -> runCatching { SubtitleText.from(bytes, name, language = language) }.getOrNull()
            } ?: return Copy.Refused(L.onlineNoticeUnreadable)
            val mime = ExternalSubtitle.mimeTypeOf(inside.extension)
                ?: return Copy.Refused(L.onlineNoticeUnreadable)
            val file = File(folder, "${++nextId}.${inside.extension}")
            file.writeText(inside.text)
            return Copy.Done(file, mime, inside.entryName)
        }
        // Written back as UTF-8 whatever it arrived in: Media3 reads these formats as UTF-8, and
        // an older .srt saved in a Windows code page would otherwise lose every accented letter.
        // A file that is not Unicode is read in the code page of the language its name gives
        // ("film.ar.srt"), else the viewer's own: see SubtitleText.decode. A name part that is
        // not a language ("film.mkv.srt") reads as the Western page and gives way to the viewer's.
        val western = SubtitleText.codePageFor(null)
        val named = ExternalSubtitle.languageOf(name)?.takeIf { SubtitleText.codePageFor(it) != western }
        val text = SubtitleText.decode(bytes, named ?: Locale.getDefault().language)
        val mime = ExternalSubtitle.mimeTypeOf(extension)
        if (mime != null) {
            val file = File(folder, "${++nextId}.$extension")
            file.writeText(text)
            return Copy.Done(file, mime)
        }
        if (!ExternalSubtitle.isMicroDvd(text)) {
            return Copy.Refused(L.tracksFileVobsub)
        }
        val file = File(folder, "${++nextId}.srt")
        file.writeText(ExternalSubtitle.microDvdToSrt(text, fps))
        return Copy.Done(file, ExternalSubtitle.MIME_SUBRIP)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        /** Redirects followed for a pasted link before the address is given up as a loop. */
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
