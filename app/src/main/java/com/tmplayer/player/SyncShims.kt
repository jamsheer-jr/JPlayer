package com.tmplayer.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import com.tmplayer.online.SubtitleTranslator
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A text renderer that runs [delayUs] behind the picture, which is how the subtitle delay works.
 *
 * Media3 has no subtitle offset of its own, and the cues come out of the renderer already timed,
 * so the only place to move them is the clock the renderer is handed. Telling it the video is at
 * `position - delay` shows each line [delayUs] later; a negative delay asks it for lines ahead of
 * the picture, which it can give because a text track is read well ahead of playback.
 *
 * [delayUs] is read on the playback thread each frame and written from the main thread, hence
 * the lambda over a volatile field in the activity.
 */
@UnstableApi
class DelayedTextRenderer(
    renderer: Renderer,
    private val delayUs: () -> Long,
) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render((positionUs - delayUs()).coerceAtLeast(0L), elapsedRealtimeUs)
    }
}

/**
 * A text output that carries the words of a subtitle track built into the file into another
 * language as they play, which is how the translate setting reaches embedded subtitles: one
 * loaded as a file was translated before it was attached, but a track inside the video never
 * exists as a file anywhere, so its words can only be caught on their way to the screen.
 *
 * A line is shown as it came the moment it is due (its timing is the file's and is never
 * touched), its words are sent for translation, and when the answer lands the line still on
 * screen is put up again translated, through [poster], the output's own thread. The answer is
 * kept, so a line met again, on a seek back or a second watching, is translated from its first
 * moment. A picture cue (a DVD or PGS image) has no words and passes through untouched, and a
 * translated line is plain: the styling the file dressed the original in does not survive
 * flattening it for the service, which is the same bargain the file translation makes.
 *
 * [target] is read on the playback thread each time lines arrive, and answers come back on
 * [scope]; a line whose translation failed is simply asked for again next time it appears.
 */
@UnstableApi
class TranslatingTextOutput(
    private val delegate: TextOutput,
    private val poster: Handler,
    private val scope: CoroutineScope,
    private val target: () -> String?,
    cacheFile: java.io.File? = null,
) : TextOutput {

    init {
        // The cache outlives this output: it is shared by every player built afterwards and,
        // with a file, by the next launch too. See [LiveTranslationCache].
        if (cacheFile != null) LiveTranslationCache.install(cacheFile, scope)
    }

    /** Lines queued for a request or out in one, so a line on screen is not asked for twice. */
    private val asked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Lines waiting for the next request, in the order they reached the screen. */
    private val queueLock = Any()
    private val queue = ArrayDeque<Triple<String, String, String>>()

    /** How many drains are running; guarded by [queueLock]. */
    private var drains = 0

    /** The lines on screen now, so an answer that lands can be put up in place of the original. */
    @Volatile
    private var showing: List<Cue> = emptyList()

    /** The group the lines on screen arrived in, when they arrived as one; its timing is kept. */
    @Volatile
    private var showingGroup: CueGroup? = null

    override fun onCues(cues: List<Cue>) {
        showing = cues
        showingGroup = null
        val language = target()
        if (language == null) {
            delegate.onCues(cues)
            return
        }
        delegate.onCues(translated(cues, language))
        askFor(cues, language)
    }

    override fun onCues(group: CueGroup) {
        showing = group.cues
        showingGroup = group
        val language = target()
        if (language == null) {
            delegate.onCues(group)
            return
        }
        delegate.onCues(CueGroup(translated(group.cues, language), group.presentationTimeUs))
        askFor(group.cues, language)
    }

    /**
     * [cues] with every line the cache already holds carried into [language]. A line
     * whose translation has not landed stands blank, never in its own language
     * (owner, v1.0.35: "og language kanikkaruth"): while a translation stands, the
     * original line is not shown at all, and a line the service never answers stays
     * blank for that showing rather than falling back to it. The viewer who wants
     * the original words turns translation off and has the track as it was posted.
     */
    private fun translated(cues: List<Cue>, language: String): List<Cue> = cues.map { cue ->
        val words = wordsOf(cue) ?: return@map cue
        LiveTranslationCache.get(key(language, words))?.let { cue.buildUpon().setText(it).build() }
            ?: cue.buildUpon().setText("").build()
    }

    /**
     * One cue's words as they would be sent: flattened to one line, as the file translation
     * flattens them. Null for a picture cue and for a line with no words in it.
     */
    private fun wordsOf(cue: Cue): String? {
        val words = cue.text?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        return words.takeIf { it.isNotEmpty() }
    }

    private fun askFor(cues: List<Cue>, language: String) {
        // Media3 hands a text output only the lines current at each moment: there is no seam
        // here that names a line before its time, so lines cannot be fetched ahead of their
        // display from this place. What can be shaped is the asking itself (owner, v1.0.32):
        // one call per group, each started cold at the line's first moment, made the free
        // service's busy moments land on whole lines (a refusal was final for that showing,
        // so the line stood untranslated for good), and kept a call in flight
        // per line of dialogue. The lines now queue and the drains send them in batches:
        // the first batch goes at once, the lines that arrive while it is out ride the next
        // one together, and a batch the service answers with nothing is asked once more
        // after a short pause before its lines are given back. Two drains run side by side
        // (v1.0.36): one call's wait no longer holds every later line hostage, which is what
        // let whole runs of dialogue go by untranslated on a slow answer. The persistent
        // cache is the answer to the second showing: a line met again, on a seek back or a
        // later watching, is translated from its first moment and never asked for twice.
        val missing = cues.mapNotNull(::wordsOf).distinct()
            .filter { LiveTranslationCache.get(key(language, it)) == null && asked.add(key(language, it)) }
        if (missing.isEmpty()) return
        val start = synchronized(queueLock) {
            missing.forEach { words -> queue.addLast(Triple(language, words, key(language, words))) }
            var toStart = 0
            while (drains + toStart < MAX_DRAINS && queue.isNotEmpty()) toStart++
            drains += toStart
            toStart
        }
        repeat(start) { scope.launch { drain() } }
    }

    /** Sends the queued lines batch by batch until none are left, then stands down. */
    private suspend fun drain() {
        while (true) {
            val batch = synchronized(queueLock) {
                if (queue.isEmpty()) {
                    drains--
                    return
                }
                val language = queue.first().first
                val taken = mutableListOf<Triple<String, String, String>>()
                val rest = ArrayDeque<Triple<String, String, String>>()
                for (entry in queue) {
                    if (entry.first == language && taken.size < BATCH_MAX) taken += entry else rest += entry
                }
                queue.clear()
                queue.addAll(rest)
                taken
            }
            val words = batch.map { it.second }
            var answers = runCatching { SubtitleTranslator().translateLines(words, batch.first().first) }.getOrNull()
            if (answers == null || answers.all { it == null }) {
                // The service busy or refusing is a moment, not a verdict: one more
                // ask after a breath, then the lines go back to being askable.
                kotlinx.coroutines.delay(RETRY_WAIT_MS)
                answers = runCatching { SubtitleTranslator().translateLines(words, batch.first().first) }.getOrNull()
            }
            var landed = false
            batch.forEachIndexed { index, entry ->
                asked.remove(entry.third)
                val answer = answers?.getOrNull(index)
                if (answer != null) {
                    LiveTranslationCache.put(entry.third, answer)
                    landed = true
                }
            }
            val language = batch.first().first
            if (landed && target() == language) {
                poster.post {
                    val group = showingGroup
                    if (group != null) {
                        delegate.onCues(CueGroup(translated(showing, language), group.presentationTimeUs))
                    } else {
                        delegate.onCues(translated(showing, language))
                    }
                }
            }
        }
    }

    private fun key(language: String, words: String) = "$language\u0001$words"

    private companion object {
        /** Lines per translation call from the queue: a run of dialogue, not one line at a time. */
        const val BATCH_MAX = 24

        /** Drains sending batches side by side (v1.0.36): see [askFor]. */
        const val MAX_DRAINS = 2

        /** The pause before a batch the service answered with nothing is asked once more. */
        const val RETRY_WAIT_MS = 1_500L
    }
}

/**
 * The live translations, kept past the player that asked for them (J Player, 2026-11-06).
 *
 * In memory the map is shared by every [TranslatingTextOutput] the app builds, so leaving a
 * video and coming back to it in the same session asks for nothing twice. With a file (the
 * app's cache folder, installed by the player) each answer is also appended there, one line
 * per entry, and read back on the next launch: a rewatch days later is translated from its
 * first moment too. The key is the language and the line as sent, the same key the output
 * has always used, so the same words in another film still hit: subtitle lines repeat far
 * more than films do. Every file step is guarded: a cache that cannot be read or written
 * costs the translations, never the playback. The file is capped: past [MAX_ENTRIES] entries
 * nothing more is appended (the memory keeps the newest answers), so the cache folder stays
 * small however much is watched.
 */
object LiveTranslationCache {

    private val memory = ConcurrentHashMap<String, String>()

    @Volatile
    private var file: java.io.File? = null

    @Volatile
    private var loaded = false

    /** Points the cache at [file] and reads what a previous launch left there, off the caller's thread. */
    fun install(file: java.io.File, scope: CoroutineScope) {
        if (this.file == file && loaded) return
        this.file = file
        scope.launch {
            runCatching {
                if (file.isFile) {
                    file.forEachLine { line ->
                        val parts = line.split('\t')
                        if (parts.size == 3) memory[unescape(parts[0])] = unescape(parts[2])
                        if (memory.size >= MAX_ENTRIES) return@forEachLine
                    }
                }
            }
            loaded = true
        }
    }

    fun get(key: String): String? = memory[key]

    fun put(key: String, answer: String) {
        memory[key] = answer
        val target = file ?: return
        if (memory.size > MAX_ENTRIES) return
        runCatching {
            target.appendText(escape(key) + "\t-\t" + escape(answer) + "\n")
        }
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private const val MAX_ENTRIES = 6000
}

/**
 * Moves the sound against the picture, which is how the audio delay works.
 *
 * The player's clock is the audio itself: the position is however much sound has been played. So
 * slipping silence into the stream holds the sound back by that long while the picture carries on
 * (a positive delay), and dropping samples brings it forward (a negative one). The offset is
 * applied from scratch after every flush, which is what a seek is to an audio processor, so it
 * survives seeking without being counted twice.
 *
 * Silence is fed out a slice at a time rather than as one buffer: a ten second gap at 7.1 float is
 * twenty megabytes, which a 1 GB stick should never be asked for in one go.
 *
 * A Dolby or DTS track sent to the receiver untouched (passthrough) skips every audio processor,
 * so the delay does nothing there; decoded audio, which is most of it, is covered.
 */
@UnstableApi
class AudioOffsetProcessor : BaseAudioProcessor() {

    /** The offset wanted, in microseconds, positive for later. Set from any thread. */
    @Volatile
    var offsetUs: Long = 0L

    /** How many frames of offset have been applied since the last flush, positive for silence. */
    private var appliedFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Silence has to be zero bytes for the trick to be silent, which rules out 8 bit PCM. That
        // track is left alone rather than refused: refusing would fail the whole audio sink.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_8BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frameBytes = format.bytesPerFrame
        val wanted = offsetUs * format.sampleRate / 1_000_000L
        val owed = wanted - appliedFrames

        if (owed > 0) {
            // Silence first, a slice per call; the input waits, since what is left of it is
            // handed back on the next call.
            val frames = minOf(owed, format.sampleRate.toLong() / SILENCE_SLICES_PER_SECOND)
            val bytes = (frames * frameBytes).toInt()
            // Counted rather than filled to the limit: a reused buffer comes back cleared to its
            // whole capacity, which can be more than this slice.
            val silence = replaceOutputBuffer(bytes)
            repeat(bytes) { silence.put(0) }
            silence.flip()
            appliedFrames += frames
            return
        }

        if (owed < 0) {
            val dropFrames = minOf(-owed, (inputBuffer.remaining() / frameBytes).toLong())
            inputBuffer.position(inputBuffer.position() + (dropFrames * frameBytes).toInt())
            appliedFrames -= dropFrames
        }

        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        appliedFrames = 0L
    }

    override fun onReset() {
        appliedFrames = 0L
    }

    private companion object {
        /** A tenth of a second of silence per call at most. */
        const val SILENCE_SLICES_PER_SECOND = 10
    }
}
