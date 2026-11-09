package com.tmplayer.player

import androidx.media3.common.Player
import com.tmplayer.online.SubtitleTarget
import java.io.File

/**
 * What the track picker and the online subtitle list need from the screen playing the video:
 * the player, the timing and the look, and the online subtitle hand-off. The player activity is
 * one; the promo fixture is the other, so both lists can be drawn without a Telegram account.
 */
interface TrackPickerHost {
    val player: Player?

    fun syncDelaysNow(): SyncDelays

    fun stepDelay(trackType: Int, direction: Int)

    fun subtitleStyleNow(): SubtitleStyle

    fun changeSubtitleStyle(style: SubtitleStyle)

    /** The video as an online search wants it, its hash included when both ends are on the disk. */
    suspend fun onlineTarget(): SubtitleTarget?

    /** Loads a downloaded online subtitle over the video, as a picked file would be. */
    fun attachOnlineSubtitle(file: File, label: String)

    /**
     * The viewer turned subtitles off on this video, by the Off row or by
     * tapping the standing track again. The default host notes nothing.
     */
    fun subtitleTurnedOff() {}

    /**
     * The viewer deliberately chose a different subtitle on this video: an
     * embedded track from the list, or an online subtitle that is not the
     * series' remembered kind. The default host notes nothing.
     */
    fun subtitleSwitched() {}

    /**
     * An MSone release the viewer just picked for this video (owner, v1.0.23):
     * where the video is an episode, the pick is remembered for its series, so
     * the next episode asks the same release for its own file on its own. The
     * default host remembers nothing.
     */
    fun noteMsonePick(hit: com.tmplayer.online.SubtitleHit) {}

    /**
     * The file a loaded subtitle track stands for, named by its format id; null when
     * the id names none of the files loaded onto this video (an embedded track).
     */
    fun loadedSubtitleFile(formatId: String?): File? = null

    /**
     * The way on when the online search found nothing: pick a subtitle file from the device.
     * Null where there is no picker (a television, the fixture).
     */
    val pickSubtitleFile: (() -> Unit)? get() = null

    /**
     * Beside the file picker: ask for a link to a subtitle file and load it from there.
     * Null where it is not offered (the fixture).
     */
    val loadSubtitleUrl: (() -> Unit)? get() = null
}
