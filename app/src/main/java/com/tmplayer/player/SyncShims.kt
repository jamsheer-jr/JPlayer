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
) : TextOutput {

    /** Words already carried over, by the line as it was sent: session lived, a film at most. */
    private val cache = ConcurrentHashMap<String, String>()

    /** Lines a request is out for, so a line on screen is not asked for twice. */
    private val asked = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

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

    /** [cues] with every line the cache already holds carried into [language]. */
    private fun translated(cues: List<Cue>, language: String): List<Cue> = cues.map { cue ->
        val words = wordsOf(cue) ?: return@map cue
        cache[key(language, words)]?.let { cue.buildUpon().setText(it).build() } ?: cue
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
        val missing = cues.mapNotNull(::wordsOf).distinct()
            .filter { cache[key(language, it)] == null && asked.add(key(language, it)) }
        if (missing.isEmpty()) return
        scope.launch {
            val answers = runCatching { SubtitleTranslator().translateLines(missing, language) }.getOrNull()
            var landed = false
            missing.forEachIndexed { index, words ->
                asked.remove(key(language, words))
                val answer = answers?.getOrNull(index)
                if (answer != null) {
                    cache[key(language, words)] = answer
                    landed = true
                }
            }
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
