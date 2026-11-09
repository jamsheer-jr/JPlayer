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
     * An MSone release the viewer just picked for this video (owner, v1.0.23):
     * where the video is an episode, the pick is remembered for its series, so
     * the next episode asks the same release for its own file on its own. The
     * default host remembers nothing.
     */
    fun noteMsonePick(hit: com.tmplayer.online.SubtitleHit) {}

    /**
     * The track id of the subtitle standing as the companion over the playing track
     * (dual subtitles), or null while none does. The companion is drawn by the app, so
     * the player never selects it; the picker reads the choice back from here.
     */
    fun companionTrackId(): String? = null

    /**
     * Takes the loaded subtitle [file] on as the companion, remembered under the track
     * id it was picked by; a null [file] takes the companion off again.
     */
    fun takeCompanion(trackId: String?, file: File?) {}

    /**
     * Takes the video's own track named [trackId] on as the companion, read out of
     * the video's own source by a second, silent player (dual subtitles); a null
     * [trackId] takes the embedded companion off again. False where this video
     * cannot give one (a split set joined from pieces), and the picker keeps
     * its older way for the pair.
     */
    fun takeEmbeddedCompanion(trackId: String?): Boolean = false

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
