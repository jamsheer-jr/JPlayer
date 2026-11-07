package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.animation.togetherWith
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.icons.TmIcons
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.Bookmark
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.copyQuality
import com.tmplayer.ui.browse.copyFit
import com.tmplayer.ui.browse.copyHeading
import com.tmplayer.ui.browse.copySound
import com.tmplayer.ui.browse.isCamCopy
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.SubtitleTranslator
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.LocalChoiceFullScreen
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone

/**
 * The player's speed, picture shape, sleep timer and playback details, drawn as the language picker's modal
 * ([ChoiceSheet]) over the dimmed video, as the subtitle and audio pickers are.
 *
 * More options shows the same pickers as pages of its own panel ([PlayerMorePanel]), so Back
 * steps back to its list; this host opens them on their own, from the row's buttons (speed) and
 * the player's questions. It is a [ComposeView] laid over the player that
 * draws nothing in the player's window: each sheet is a dialog window of its own, which takes the
 * remote's keys and the touches away from the player while it is up.
 */
class PlayerSheets(
    activity: FragmentActivity,
    root: ViewGroup,
    private val onClosed: () -> Unit,
) {
    /** Whether the device is driven by touch, read once: the bookmark list's Add asks for a note only there. */
    private val isTouchHost = !com.tmplayer.data.FormFactor.isTv(activity)

    private sealed interface Sheet {
        class Speed(
            val current: Float,
            val onPick: (Float) -> Unit,
            val skipSilence: Boolean,
            val onSkipSilence: (Boolean) -> Unit,
            val onFine: (Float) -> Unit,
        ) : Sheet
        class Shape(val current: VideoScale, val onPick: (VideoScale) -> Unit) : Sheet

        /** The quality chooser: the badge tiers this video's copies have, each with its best copy. */
        class Quality(
            val choices: List<com.tmplayer.data.VideoCopies.TierChoice>,
            val detailOf: (com.tmplayer.data.VideoCopies.TierChoice) -> String,
            val onPick: (MediaItem) -> Unit,
        ) : Sheet

        /** A stream's own picture variants, Auto first, from a tap on the badge while a stream plays. */
        class StreamQuality(val options: List<StreamQualityOption>, val onPick: (Int) -> Unit) : Sheet

        /** 3D video; [onChange] answers with what took effect, which a refusal leaves as it was. */
        class Stereo(val current: Stereo3d, val tagged: Stereo3d.Layout?, val onChange: (Stereo3d) -> Pair<String?, Stereo3d>) : Sheet
        class Sleep(val running: String?, val chosen: Int?, val onPick: (Int?) -> Unit) : Sheet
        class Details(val title: String, val lines: List<String>) : Sheet
        class Bookmarks(val actions: BookmarkSheetActions) : Sheet
        class BookmarkActions(val mark: Bookmark, val actions: BookmarkSheetActions) : Sheet

        /** A note for a new bookmark at [positionMs] ([editing] null), or for [editing]. */
        class Note(val positionMs: Long, val editing: Bookmark?, val actions: BookmarkSheetActions, val fromList: Boolean) : Sheet

        /** The video does not fit, but would in Low storage mode: switch to it, or not. */
        class LowStorageOffer(val onAnswer: (Boolean) -> Unit) : Sheet

        /**
         * The only copy here is too heavy for this device (HX2): play Telegram's lighter copy
         * ([lighter], its quality, when there is one) or play anyway, with "Don't ask again".
         */
        class HeavyAsk(
            val codec: String,
            val device: String,
            val lighter: String?,
            val onAnswer: (useLighter: Boolean?, dontAsk: Boolean) -> Unit,
        ) : Sheet

        /** A link to a subtitle file, pasted by the viewer, for the video playing. */
        class SubtitleUrl(val onSubmit: (String) -> Unit) : Sheet

        /** The copies of the video playing, [playing] ticked. */
        class Copies(
            val copies: List<MediaItem>,
            val playing: String,
            val chatOf: (MediaItem) -> String,
            val onPick: (MediaItem) -> Unit,
            val qualities: List<com.tmplayer.data.TelegramQuality> = emptyList(),
            val playingQuality: Int = 0,
            val onQuality: (Int) -> Unit = {},
        ) : Sheet
    }

    private val sheet = mutableStateOf<Sheet?>(null)

    val isOpen: Boolean get() = sheet.value != null

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content (see PlayerMorePanel).
            setContent { TMPlayerTheme { SheetContent() } }
        }
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    /**
     * The speeds, the fine control and the skip silence switch under them. A preset closes the
     * sheet; a fine step ([onFine]) and the switch act at once and leave it open.
     */
    fun showSpeed(
        current: Float,
        onPick: (Float) -> Unit,
        skipSilence: Boolean = false,
        onSkipSilence: (Boolean) -> Unit = {},
        onFine: (Float) -> Unit = onPick,
    ) {
        sheet.value = Sheet.Speed(current, onPick, skipSilence, onSkipSilence, onFine)
    }

    fun showShape(current: VideoScale, onPick: (VideoScale) -> Unit) {
        sheet.value = Sheet.Shape(current, onPick)
    }

    /** The quality tiers of the copies playing, from a tap on the top bar's badge. */
    fun showQuality(
        choices: List<com.tmplayer.data.VideoCopies.TierChoice>,
        detailOf: (com.tmplayer.data.VideoCopies.TierChoice) -> String,
        onPick: (MediaItem) -> Unit,
    ) {
        sheet.value = Sheet.Quality(choices, detailOf, onPick)
    }

    /** The stream variant list: [options] in order, index 0 is Auto. */
    fun showStreamQuality(options: List<StreamQualityOption>, onPick: (Int) -> Unit) {
        sheet.value = Sheet.StreamQuality(options, onPick)
    }

    /**
     * 3D video's page: every choice takes effect at once and the page stays up, so the viewer
     * can try the glasses colours against the picture. [tagged] is what the file's name says.
     * [onChange] answers with why 3D could not come on (or null) and the state in force.
     */
    fun showStereo(current: Stereo3d, tagged: Stereo3d.Layout?, onChange: (Stereo3d) -> Pair<String?, Stereo3d>) {
        sheet.value = Sheet.Stereo(current, tagged, onChange)
    }

    /** [running] is the running timer's detail, [chosen] the length it was started with. */
    fun showSleep(running: String?, chosen: Int?, onPick: (Int?) -> Unit) {
        sheet.value = Sheet.Sleep(running, chosen, onPick)
    }

    fun showDetails(title: String, lines: List<String>) {
        sheet.value = Sheet.Details(title, lines)
    }

    /** The video's bookmarks; the list follows [BookmarkSheetActions.bookmarks] while it is up. */
    fun showBookmarks(actions: BookmarkSheetActions) {
        sheet.value = Sheet.Bookmarks(actions)
    }

    /** The note for a new bookmark at [positionMs], straight from the overflow. */
    fun showNewBookmark(positionMs: Long, actions: BookmarkSheetActions) {
        sheet.value = Sheet.Note(positionMs, editing = null, actions = actions, fromList = false)
    }

    /**
     * Asks whether to switch to Low storage mode for a video that only fits that way. [onAnswer]
     * gets true for "Switch and play" and false for "Not now", Back included.
     */
    fun showLowStorageOffer(onAnswer: (Boolean) -> Unit) {
        sheet.value = Sheet.LowStorageOffer(onAnswer)
    }

    /**
     * "This video may stutter on this TV": [onAnswer] gets true for Telegram's lighter copy, false
     * for Play anyway, null for Back (leave the video), and whether "Don't ask again" was ticked.
     */
    fun showHeavyAsk(codec: String, device: String, lighter: String?, onAnswer: (Boolean?, Boolean) -> Unit) {
        sheet.value = Sheet.HeavyAsk(codec, device, lighter, onAnswer)
    }

    /** Asks for a link to a subtitle file; [onSubmit] gets the pasted address, trimmed. */
    fun showSubtitleUrl(onSubmit: (String) -> Unit) {
        sheet.value = Sheet.SubtitleUrl(onSubmit)
    }

    /** Every copy of the video playing; choosing another plays it from the same moment. */
    fun showCopies(
        copies: List<MediaItem>,
        playing: String,
        chatOf: (MediaItem) -> String,
        onPick: (MediaItem) -> Unit,
        qualities: List<com.tmplayer.data.TelegramQuality> = emptyList(),
        playingQuality: Int = 0,
        onQuality: (Int) -> Unit = {},
    ) {
        sheet.value = Sheet.Copies(copies, playing, chatOf, onPick, qualities, playingQuality, onQuality)
    }

    private fun close() {
        if (sheet.value == null) return
        sheet.value = null
        onClosed()
    }

    @Composable
    private fun SheetContent() {
        when (val shown = sheet.value) {
            null -> Unit
            is Sheet.Speed -> {
                var skipping by remember(shown) { mutableStateOf(shown.skipSilence) }
                var speed by remember(shown) { mutableStateOf(shown.current) }
                SpeedSheet(
                    current = speed,
                    onFine = { next ->
                        speed = next
                        shown.onFine(next)
                    },
                    onPick = { close(); shown.onPick(it) },
                    onDismiss = ::close,
                    onClose = ::close,
                    skipSilence = skipping,
                    onSkipSilence = { on ->
                        skipping = on
                        shown.onSkipSilence(on)
                    },
                )
            }
            is Sheet.Stereo -> {
                var stereo by remember(shown) { mutableStateOf(shown.current) }
                var refused by remember(shown) { mutableStateOf<String?>(null) }
                StereoSheet(
                    current = stereo,
                    tagged = shown.tagged,
                    refused = refused,
                    onChange = {
                        val (why, now) = shown.onChange(it)
                        refused = why
                        stereo = now
                    },
                    onDismiss = ::close,
                    onClose = ::close,
                )
            }
            is Sheet.Shape -> ShapeSheet(
                current = shown.current,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Quality -> QualitySheet(
                choices = shown.choices,
                detailOf = shown.detailOf,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.StreamQuality -> StreamQualitySheet(
                options = shown.options,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Sleep -> SleepSheet(
                running = shown.running,
                chosen = shown.chosen,
                onPick = { close(); shown.onPick(it) },
                onDismiss = ::close,
                onClose = ::close,
            )
            is Sheet.Details -> PlaybackDetailsSheet(shown.title, shown.lines, onClose = ::close)
            is Sheet.Copies -> CopiesSheet(
                copies = shown.copies,
                playing = shown.playing,
                chatOf = shown.chatOf,
                onPick = { close(); shown.onPick(it) },
                onClose = ::close,
                qualities = shown.qualities,
                playingQuality = shown.playingQuality,
                onQuality = { close(); shown.onQuality(it) },
            )
            is Sheet.SubtitleUrl -> {
                val s = LocalStrings.current
                var link by remember(shown) { mutableStateOf("") }
                TvConfirm(
                    title = s.playerSubtitleUrlTitle,
                    message = s.playerSubtitleUrlMessage,
                    confirmLabel = s.playerLoadSubtitleUrl,
                    destructive = false,
                    icon = TmIcons.Download,
                    focusConfirm = true,
                    onConfirm = {
                        val pasted = link.trim()
                        close()
                        if (pasted.isNotEmpty()) shown.onSubmit(pasted)
                    },
                    onDismiss = ::close,
                    extra = {
                        com.tmplayer.ui.auth.PaneField(
                            value = link,
                            onValueChange = { link = it },
                            placeholder = s.playerSubtitleUrlHint,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                autoCorrectEnabled = false,
                            ),
                        )
                    },
                )
            }
            is Sheet.LowStorageOffer -> {
                val s = LocalStrings.current
                // Focus on "Switch and play": it is the answer that does what the viewer pressed.
                TvConfirm(
                    title = s.settingsLowStorage,
                    message = s.mainLowStorageAskPlay(mode = s.settingsLowStorage),
                    detail = s.settingsLowStorageDetail,
                    confirmLabel = s.mainLowStorageSwitchPlay,
                    cancelLabel = s.mainLowStorageNotNow,
                    destructive = false,
                    focusConfirm = true,
                    onConfirm = { close(); shown.onAnswer(true) },
                    onDismiss = { close(); shown.onAnswer(false) },
                )
            }
            is Sheet.HeavyAsk -> {
                val s = LocalStrings.current
                var dontAsk by remember(shown) { mutableStateOf(false) }
                val lighter = shown.lighter
                // With a lighter copy the question is which to play, the lighter first; without
                // one it is whether to play at all, Play anyway first (Back leaves the video).
                TvConfirm(
                    title = s.playerHeavyAskTitle,
                    message = s.playerHeavyAsk(device = shown.device, codec = shown.codec),
                    confirmLabel = if (lighter != null) s.playerHeavyAskLighter(lighter) else s.playerHeavyAskPlay,
                    cancelLabel = if (lighter != null) s.playerHeavyAskPlay else s.commonCancel,
                    destructive = false,
                    focusConfirm = true,
                    extra = { com.tmplayer.ui.settings.TickRow(s.playerHeavyAskSkip, dontAsk) { dontAsk = !dontAsk } },
                    onConfirm = { close(); shown.onAnswer(lighter != null, dontAsk) },
                    onDismiss = { close(); shown.onAnswer(if (lighter != null) false else null, dontAsk) },
                )
            }
            is Sheet.Bookmarks -> {
                val actions = shown.actions
                val list by actions.bookmarks.collectAsState()
                BookmarksSheet(
                    bookmarks = list,
                    onAdd = {
                        // The television saves at once, without the keyboard; the phone asks for a note.
                        val at = actions.position()
                        if (isTouchHost) {
                            sheet.value = Sheet.Note(at, editing = null, actions = actions, fromList = true)
                        } else {
                            actions.add(at, "")
                        }
                    },
                    onPick = { sheet.value = Sheet.BookmarkActions(it, actions) },
                    onDismiss = ::close,
                    onClose = ::close,
                )
            }
            is Sheet.BookmarkActions -> {
                val actions = shown.actions
                val back = { sheet.value = Sheet.Bookmarks(actions) }
                BookmarkActionsSheet(
                    mark = shown.mark,
                    onPlay = { close(); actions.jump(shown.mark) },
                    onNote = { sheet.value = Sheet.Note(shown.mark.positionMs, shown.mark, actions, fromList = true) },
                    onDelete = { actions.delete(shown.mark); back() },
                    onDismiss = back,
                    onClose = ::close,
                )
            }
            is Sheet.Note -> {
                val actions = shown.actions
                val back = { if (shown.fromList) sheet.value = Sheet.Bookmarks(actions) else close() }
                val s = LocalStrings.current
                BookmarkNoteSheet(
                    title = s.playerBookmarkNoteTitle(Translator.messages.formatter.clock(shown.positionMs)),
                    initial = shown.editing?.note.orEmpty(),
                    saveLabel = if (shown.editing != null) s.playerBookmarkSaveNote else s.playerBookmarkSave,
                    onSave = { note ->
                        val editing = shown.editing
                        if (editing != null) actions.rename(editing, note) else actions.add(shown.positionMs, note)
                        back()
                    },
                    onDismiss = back,
                    onClose = ::close,
                )
            }
        }
    }
}

/**
 * The playback speeds, one ticked: the one playing. Back is [onDismiss] (a page of the More menu
 * steps back with it), Close is [onClose]. The remote starts on the speed in force, or on Faster
 * when the speed in force is a fine one.
 *
 * Full screen on a phone they are a grid of tiles instead of a list, all seven in one row on a
 * landscape phone and four to a row upright, so every speed is one tap with nothing to scroll.
 *
 * Under the presets, the fine control: [PlaybackSpeed.FINE_STEP] at a time from
 * [PlaybackSpeed.MIN] to [PlaybackSpeed.MAX], as the desktop's ] and [ keys step. A phone has
 * minus and plus either side of the figure; a television has Slower and Faster lines under it,
 * which OK presses as often as it likes. Each step plays at once through [onFine] and leaves the
 * sheet open; the figure rolls up or down with the step, and only fades with less motion asked for.
 *
 * Skip silence sits under the speeds, a switch rather than a speed: [skipSilence] is whether it
 * is on, and choosing the line flips it through [onSkipSilence] with the sheet left open.
 */
@Composable
internal fun SpeedSheet(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    skipSilence: Boolean = false,
    onSkipSilence: (Boolean) -> Unit = {},
    onFine: (Float) -> Unit = onPick,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val preset = PlaybackSpeed.CHOICES.firstOrNull { kotlin.math.abs(it - current) < 0.001f }
    ChoiceSheet(title = s.playerPlaybackSpeed, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        if (LocalChoiceFullScreen.current) {
            SpeedTiles(current, onPick)
            FineSpeedStepper(current, onFine)
            SkipSilenceLine(skipSilence, onSkipSilence)
            return@ChoiceSheet
        }
        ChoiceList {
            PlaybackSpeed.CHOICES.forEach { choice ->
                val selected = choice == preset
                ChoiceLine(
                    title = PlaybackSpeed.label(choice),
                    detail = if (selected) s.playerSpeedCurrent else null,
                    selected = selected,
                    modifier = if (choice == preset) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(choice) }
            }
            if (touch) {
                FineSpeedStepper(current, onFine)
            } else {
                FineSpeedLines(current, onFine, if (preset == null) focus else null)
            }
            SkipSilenceLine(skipSilence, onSkipSilence)
        }
        FocusOnOpen(focus)
    }
}

/**
 * "Subtitle sync": the picker's Timing lines (earlier, later, reset) on their own page in the
 * More panel, so the timing can be fixed without opening the whole subtitle picker. Each press
 * steps the delay and the figures are read again, the way the picker's lines redraw.
 */
@Composable
internal fun SubtitleSyncSheet(
    delayMs: () -> Long,
    onStep: (Int) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    var tick by remember { mutableStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val seen = tick
    val ms = delayMs()
    fun step(direction: Int) {
        onStep(direction)
        tick++
    }
    ChoiceSheet(title = s.playerSubtitleSync, onDismiss = onDismiss, onClose = onClose) {
        ChoiceList {
            ChoiceLine(
                title = s.tracksSubtitlesEarlier,
                detail = s.tracksDelayStep(SyncDelays.label(ms)),
                icon = TmIcons.ChevronLeft,
            ) { step(-1) }
            ChoiceLine(
                title = s.tracksSubtitlesLater,
                detail = s.tracksDelayStep(SyncDelays.label(ms)),
                icon = TmIcons.ChevronRight,
            ) { step(1) }
            ChoiceLine(
                title = s.tracksResetTiming,
                detail = if (ms == 0L) s.tracksDelayNone else s.tracksDelayNow(SyncDelays.label(ms)),
                icon = TmIcons.Refresh,
                enabled = ms != 0L,
            ) { step(0) }
        }
    }
}

/**
 * "Translate subtitles": Off, or one of [SubtitleTranslator.LANGUAGES], Malayalam first. The
 * same page the subtitle picker's Online group opens; the More menu carries it too, because
 * that is where a viewer who just loaded a subtitle looks for it. Choosing writes the setting
 * and ends the errand; subtitles already playing are not reloaded, the choice counts from the
 * next one downloaded or loaded.
 */
@Composable
internal fun TranslateSheet(
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val online = OnlineSubtitles.current
    val current = online?.store?.now?.translateTo.orEmpty()
    val focus = remember { FocusRequester() }
    ChoiceSheet(title = s.onlineTranslateTitle, note = s.onlineTranslateDetail, onDismiss = onDismiss, onClose = onClose, fullScreen = true) {
        ChoiceList {
            ChoiceLine(
                title = s.commonOff,
                selected = current.isBlank(),
                modifier = if (current.isBlank()) Modifier.focusRequester(focus) else Modifier,
            ) {
                online?.setTranslateTo("")
                onDone()
            }
            SubtitleTranslator.LANGUAGES.forEach { code ->
                val selected = current == code
                ChoiceLine(
                    title = OnlineSubtitles.languageName(code),
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                ) {
                    online?.setTranslateTo(code)
                    onDone()
                }
            }
        }
        FocusOnOpen(focus)
    }
}

/** "0.05x a press, from 0.25x to 4x": what the fine control does, under its heading. */
@Composable
private fun fineDetail(): String {
    val s = LocalStrings.current
    return s.playerSpeedFineDetail(
        step = PlaybackSpeed.label(PlaybackSpeed.FINE_STEP),
        min = PlaybackSpeed.label(PlaybackSpeed.MIN),
        max = PlaybackSpeed.label(PlaybackSpeed.MAX),
    )
}

/**
 * The speed in force, large, rolling up when it rose and down when it fell. Only a fade with
 * less motion asked for.
 */
@Composable
private fun SpeedFigure(speed: Float, modifier: Modifier = Modifier) {
    val still = com.tmplayer.ui.components.rememberLessMotion()
    androidx.compose.animation.AnimatedContent(
        targetState = speed,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            val fadeIn = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.tmplayer.ui.theme.Motion.Fast))
            val fadeOut = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.tmplayer.ui.theme.Motion.Fast))
            if (still) {
                fadeIn togetherWith fadeOut
            } else {
                val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(com.tmplayer.ui.theme.Motion.Fast)
                (androidx.compose.animation.slideInVertically(spec) { h -> if (up) h / 2 else -h / 2 } + fadeIn) togetherWith
                    (androidx.compose.animation.slideOutVertically(spec) { h -> if (up) -h / 2 else h / 2 } + fadeOut)
            }
        },
        contentAlignment = Alignment.Center,
        label = "fineSpeed",
    ) { shown ->
        Text(PlaybackSpeed.label(shown), style = MaterialTheme.typography.headlineSmall, color = Tone.text, maxLines = 1)
    }
}

/**
 * The phone's fine control: minus, the figure, plus, under a heading that says the step. Each
 * button sinks under the finger ([pressScale]) and ticks the hand, and greys out at its end of
 * the range.
 */
@Composable
private fun FineSpeedStepper(current: Float, onFine: (Float) -> Unit) {
    val s = LocalStrings.current
    val tick = com.tmplayer.ui.components.rememberStepTick()
    Column(Modifier.fillMaxWidth()) {
        com.tmplayer.ui.components.ChoiceSection(s.playerSpeedFine)
        Text(
            fineDetail(),
            style = MaterialTheme.typography.bodySmall,
            color = Tone.muted,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            FineButton(TmIcons.Remove, s.playerSpeedSlower, enabled = PlaybackSpeed.canStep(current, up = false)) {
                tick()
                onFine(PlaybackSpeed.fine(current, up = false))
            }
            androidx.compose.foundation.layout.Box(Modifier.width(128.dp), contentAlignment = Alignment.Center) {
                SpeedFigure(current)
            }
            FineButton(TmIcons.Add, s.playerSpeedFaster, enabled = PlaybackSpeed.canStep(current, up = true)) {
                tick()
                onFine(PlaybackSpeed.fine(current, up = true))
            }
        }
    }
}

@Composable
private fun FineButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    com.tmplayer.ui.components.IconTip(label) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactions,
            modifier = Modifier.size(56.dp).pressScale(interactions),
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = label)
        }
    }
}

/**
 * The television's fine control: the figure under the heading, then Slower and Faster as lines
 * the remote stands on and presses as often as it likes. [focus], when given, is hung on Faster:
 * the speed in force is a fine one, so no preset line is there to start on.
 */
@Composable
private fun ColumnScope.FineSpeedLines(current: Float, onFine: (Float) -> Unit, focus: FocusRequester?) {
    val s = LocalStrings.current
    com.tmplayer.ui.components.ChoiceSection(s.playerSpeedFine)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(fineDetail(), style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f))
        SpeedFigure(current)
    }
    val now = s.playerSpeedFineNow(PlaybackSpeed.label(current))
    ChoiceLine(
        title = s.playerSpeedSlower,
        detail = now,
        icon = TmIcons.Remove,
        enabled = PlaybackSpeed.canStep(current, up = false),
    ) { onFine(PlaybackSpeed.fine(current, up = false)) }
    ChoiceLine(
        title = s.playerSpeedFaster,
        detail = now,
        icon = TmIcons.Add,
        enabled = PlaybackSpeed.canStep(current, up = true),
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
    ) { onFine(PlaybackSpeed.fine(current, up = true)) }
}

/**
 * The skip silence switch, ticked while it is on, in the tick's column so it lines up with the
 * speeds above it.
 */
@Composable
private fun SkipSilenceLine(on: Boolean, onToggle: (Boolean) -> Unit) {
    val s = LocalStrings.current
    ChoiceLine(
        title = s.playerSkipSilence,
        detail = s.playerSkipSilenceDetail,
        selected = on,
    ) { onToggle(!on) }
}

/**
 * The speeds as tiles in the middle of the room the sheet has, the one playing filled with the
 * accent, the rest a step above the panel.
 */
@Composable
private fun ColumnScope.SpeedTiles(current: Float, onPick: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        val count = PlaybackSpeed.CHOICES.size
        val perRow = if (maxWidth >= (TILE_MIN + TILE_GAP) * count) count else 4
        val tile = (maxWidth - TILE_GAP * (perRow - 1)) / perRow
        Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            PlaybackSpeed.CHOICES.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    row.forEach { choice ->
                        val selected = kotlin.math.abs(choice - current) < 0.001f
                        val shape = RoundedCornerShape(Corner.Large)
                        Column(
                            Modifier
                                .width(tile)
                                .height(TILE_HEIGHT)
                                .clip(shape)
                                .background(if (selected) Tone.accent else FloatingTone.control)
                                .selectable(selected = selected, role = Role.RadioButton) { onPick(choice) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                PlaybackSpeed.label(choice),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (selected) Tone.onAccent else Tone.text,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TILE_HEIGHT = 112.dp
private val TILE_MIN = 88.dp
private val TILE_GAP = 12.dp

/**
 * The picture shapes, one ticked: fit, crop and stretch, as the pickers' panel draws a choice of
 * one of a few. The pinch on a phone stays as the shortcut; this is the route that can be found.
 */
/**
 * The quality chooser (J Player, 2026-11-06): the tiers the badge names, SD, HD and UHD, each
 * with the copy choosing it would play (its height and size), the tier playing ticked. Picking
 * the tier playing closes without a switch.
 */
@Composable
internal fun QualitySheet(
    choices: List<com.tmplayer.data.VideoCopies.TierChoice>,
    detailOf: (com.tmplayer.data.VideoCopies.TierChoice) -> String,
    onPick: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val current = choices.firstOrNull { it.current }
    ChoiceSheet(title = s.seriesQuality, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            choices.forEach { choice ->
                ChoiceLine(
                    title = choice.tier,
                    detail = detailOf(choice),
                    selected = choice.current,
                    modifier = if (choice.current) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(choice.copy) }
            }
        }
        if (current != null) FocusOnOpen(focus)
    }
}

/** One row of the stream quality list: what it is called, its size and rate, and whether it is on. */
data class StreamQualityOption(val label: String, val detail: String?, val selected: Boolean)

/**
 * A stream's own quality list (J Player, 2026-11-06): Auto, which lets the player pick the
 * variant the connection can carry, then each variant the stream itself offers, tallest first.
 */
@Composable
internal fun StreamQualitySheet(
    options: List<StreamQualityOption>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val selected = options.indexOfFirst { it.selected }
    ChoiceSheet(title = s.seriesQuality, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            options.forEachIndexed { index, option ->
                ChoiceLine(
                    title = option.label,
                    detail = option.detail,
                    selected = option.selected,
                    modifier = if (option.selected) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(index) }
            }
        }
        if (selected >= 0) FocusOnOpen(focus)
    }
}

@Composable
internal fun ShapeSheet(current: VideoScale, onPick: (VideoScale) -> Unit, onDismiss: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    ChoiceSheet(title = s.playerPictureShape, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            VideoScale.entries.forEach { choice ->
                ChoiceLine(
                    title = choice.label,
                    selected = choice == current,
                    modifier = if (choice == current) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(choice) }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * 3D video's page: how the file keeps its two pictures (Off first), the glasses, and the eye swap.
 * Each line acts at once and the page stays up. With nothing chosen yet, the layout the file's
 * name declares ([tagged]) is the one focus lands on and is marked as found. A glasses colour
 * picked while 3D is off is kept for when a layout is chosen. [refused] is why the last choice
 * could not come on (an HDR video, the app's own decoder), said where the note was.
 */
@Composable
internal fun StereoSheet(
    current: Stereo3d,
    tagged: Stereo3d.Layout?,
    refused: String?,
    onChange: (Stereo3d) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val focusOn = if (current.on) current.layout else tagged ?: Stereo3d.Layout.Off
    ChoiceSheet(title = s.playerStereo, note = refused ?: s.playerStereoNote, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            com.tmplayer.ui.components.ChoiceSection(s.playerStereoLayout)
            Stereo3d.Layout.entries.forEach { layout ->
                val found = layout == tagged && !current.on
                ChoiceLine(
                    title = stereoLayoutLabel(layout),
                    detail = when (layout) {
                        Stereo3d.Layout.Off -> s.playerStereoOffDetail
                        Stereo3d.Layout.SideBySide -> s.playerStereoSideBySideDetail
                        Stereo3d.Layout.TopBottom -> s.playerStereoTopBottomDetail
                        Stereo3d.Layout.RowInterleaved -> s.playerStereoRowsDetail
                        Stereo3d.Layout.ColumnInterleaved -> s.playerStereoColumnsDetail
                    }.let { if (found) s.commonWithDetail(it, s.playerStereoFoundShort) else it },
                    selected = layout == current.layout,
                    modifier = if (layout == focusOn) Modifier.focusRequester(focus) else Modifier,
                ) { onChange(current.copy(layout = layout)) }
            }
            com.tmplayer.ui.components.ChoiceSection(s.playerStereoGlasses)
            Stereo3d.View.entries.forEach { view ->
                ChoiceLine(
                    title = stereoViewLabel(view),
                    detail = when (view) {
                        Stereo3d.View.OneEye -> s.playerStereoOneEyeDetail
                        Stereo3d.View.Vr -> s.playerStereoVrDetail
                        else -> null
                    },
                    selected = view == current.view,
                ) { onChange(current.copy(view = view)) }
            }
            // Under the glasses: a file kept right eye first looks inside out through them.
            ChoiceLine(
                title = s.playerStereoSwap,
                detail = s.playerStereoSwapDetail,
                selected = current.swapEyes,
                enabled = current.on,
            ) { onChange(current.copy(swapEyes = !current.swapEyes)) }
        }
        FocusOnOpen(focus)
    }
}

@Composable
private fun stereoLayoutLabel(layout: Stereo3d.Layout): String {
    val s = LocalStrings.current
    return when (layout) {
        Stereo3d.Layout.Off -> s.commonOff
        Stereo3d.Layout.SideBySide -> s.playerStereoSideBySide
        Stereo3d.Layout.TopBottom -> s.playerStereoTopBottom
        Stereo3d.Layout.RowInterleaved -> s.playerStereoRows
        Stereo3d.Layout.ColumnInterleaved -> s.playerStereoColumns
    }
}

@Composable
private fun stereoViewLabel(view: Stereo3d.View): String {
    val s = LocalStrings.current
    return when (view) {
        Stereo3d.View.RedCyan -> s.playerStereoRedCyan
        Stereo3d.View.GreenMagenta -> s.playerStereoGreenMagenta
        Stereo3d.View.YellowBlue -> s.playerStereoYellowBlue
        Stereo3d.View.OneEye -> s.playerStereoOneEye
        Stereo3d.View.Vr -> s.playerStereoVr
    }
}

/**
 * The sleep timer's lengths, with Off first: Off is ticked while no timer runs, the length a
 * running timer was started with otherwise, and the time it has left is the note under the title.
 * [onPick] gets minutes, [SleepTimer.END_OF_VIDEO], or null for off.
 */
@Composable
internal fun SleepSheet(
    running: String?,
    chosen: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val ticked = if (running == null) null else chosen
    val focusOn: Int? = ticked?.takeIf { it in SleepTimer.CHOICES }
    ChoiceSheet(title = s.playerSleepTimer, note = running, onDismiss = onDismiss, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            ChoiceLine(
                title = s.commonOff,
                detail = if (running != null) s.playerKeepPlaying else null,
                selected = running == null,
                modifier = if (focusOn == null) Modifier.focusRequester(focus) else Modifier,
            ) { if (running == null) onClose() else onPick(null) }
            SleepTimer.CHOICES.forEach { minutes ->
                ChoiceLine(
                    title = SleepTimer.label(minutes),
                    selected = running != null && minutes == ticked,
                    modifier = if (minutes == focusOn) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(minutes) }
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * The copies of the video playing, grouped under their quality as the detail page's Versions
 * groups them (owner, v1.0.14): a heading for each quality there is (1080p, 720p, and last
 * "Other copies" for the copies stating no quality, the ones sent as files whose names carry
 * no marker; the Versions chooser keeps its own "Quality not stated" heading for them,
 * owner, v1.0.15), and under each heading its copies in the Versions rows' shape: the size first,
 * then what the copy is (its source, codec and the sound its name states: its languages and
 * audio), its length, date and the chat it came from, and the file's own name. The one
 * playing is ticked and focused. Choosing another plays it from the same moment.
 *
 * While the Playback switch "Hide cam and PreDVD copies" is on, cam family copies are left
 * out and counted on a line at the foot, as the other lists count theirs. The copy playing
 * is never left out: the sheet always shows what is playing, ticked as it is today, under
 * its own quality's heading even when no other copy sits there.
 */
@Composable
internal fun CopiesSheet(
    copies: List<MediaItem>,
    playing: String,
    chatOf: (MediaItem) -> String,
    onPick: (MediaItem) -> Unit,
    onClose: () -> Unit,
    /** Telegram's lighter copies of the upload playing (CP80), listed under it. */
    qualities: List<com.tmplayer.data.TelegramQuality> = emptyList(),
    playingQuality: Int = 0,
    onQuality: (Int) -> Unit = {},
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context) }
    val hideCam by settings.hideCamCopies.collectAsState(initial = true)
    // The copy playing always stays, even when it is itself a cam copy.
    val visible = remember(copies, hideCam, playing) {
        if (hideCam) copies.filter { copy -> copy.id == playing || !isCamCopy(copy) } else copies
    }
    val camHidden = copies.size - visible.size
    // Sorted and grouped the one way copies are always offered ([VideoCopies.tiersOf]), the
    // grouping the detail page's Versions shares, so the two lists never disagree: best
    // picture first, the biggest of it next, the copies stating no quality last. The copy
    // playing stays listed wherever it lands (VideoCopies.oneOfEachFile keeps it in the list
    // upstream), so its heading stands even when it is the only copy under it.
    val tiers = remember(visible) { VideoCopies.tiersOf(visible) { it } }
    val datesDiffer = remember(visible) { visible.map { it.date / 86_400 }.distinct().size > 1 }
    ChoiceSheet(title = s.playerCopies, note = s.playerCopiesNote, onDismiss = onClose, onClose = onClose, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            tiers.forEach { (height, group) ->
                // A stated quality heads its group, as on the Versions page; the copies
                // stating none are headed "Other copies" here (the Versions page keeps
                // its own "Quality not stated" heading for them): no quality is read into
                // a copy from its size.
                com.tmplayer.ui.components.ChoiceSection(height?.let { group.first().copyQuality() } ?: s.playerCopiesOther)
                group.forEach { copy ->
                    val here = copy.id == playing
                    // The upload is ticked only while no Telegram copy stands in for it.
                    val ticked = here && playingQuality == 0
                    val facts = MediaFacts.of(copy)
                    // The size leads the row, as a Versions row leads with it; the quality
                    // is the group's heading, said once.
                    val size = copy.sizeBytes.takeIf { it > 0 }?.let { com.tmplayer.data.SplitPieces.sizeFact(copy, s.formatter.size(it)) } ?: copyHeading(copy)
                    ChoiceLine(
                        title = if (here && qualities.isNotEmpty()) "$size  ·  ${s.detailUploadQuality}" else size,
                        // Under the size, what the copy is (its source, codec and the sound
                        // its name states: its languages and format), then its length, date
                        // and the chat it came from, then the file's own name, each on its
                        // own line under the first.
                        detail = listOfNotNull(
                            listOfNotNull(facts.source, facts.videoCodec, facts.dynamicRange, copySound(copy)).joinToString("  ·  ").ifEmpty { null },
                            listOfNotNull(
                                copyFit(copy, s, visible),
                                copy.durationSec.takeIf { it > 0 }?.let { s.formatter.duration(it.toLong()) },
                                copy.date.takeIf { datesDiffer && it > 0 }?.let { s.detailCopyPosted(s.formatter.date(it * 1000L)) },
                                chatOf(copy).takeIf { it.isNotBlank() }?.let { s.detailCopyFrom(it) },
                                when (copy.locality) {
                                    MediaItem.Locality.Downloaded -> s.gridBadgeDownloaded
                                    MediaItem.Locality.Cached -> s.detailCopyOnDevice
                                    MediaItem.Locality.Remote -> null
                                },
                                s.detailCopyNoSaving.takeIf { !copy.canBeSaved },
                            ).joinToString("  ·  ").ifEmpty { null },
                            copy.fileName.ifBlank { null },
                        ).joinToString("\n").ifEmpty { null },
                        selected = ticked,
                        modifier = if (ticked) Modifier.focusRequester(focus) else Modifier,
                    ) {
                        when {
                            ticked -> onClose()
                            here -> onQuality(0)
                            else -> onPick(copy)
                        }
                    }
                    if (here) {
                        qualities.forEach { q ->
                            val on = q.height == playingQuality
                            ChoiceLine(
                                title = s.detailTelegramQuality(com.tmplayer.data.TelegramQualities.label(q.height)),
                                detail = listOfNotNull(
                                    s.detailTelegramQualityDetail,
                                    q.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
                                ).joinToString("  ·  "),
                                selected = on,
                                modifier = if (on) Modifier.focusRequester(focus) else Modifier,
                            ) { if (on) onClose() else onQuality(q.height) }
                        }
                    }
                }
            }
            if (camHidden > 0) {
                Text(
                    s.playerCamHidden(camHidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        FocusOnOpen(focus)
    }
}

/**
 * What is actually playing, a line a figure, in the pickers' panel. Nothing in it is chosen, so
 * the remote starts on Close, and Back or Close shuts it.
 */
@Composable
internal fun PlaybackDetailsSheet(title: String, lines: List<String>, onClose: () -> Unit) {
    val touch = isTouch()
    val closeFocus = remember { FocusRequester() }
    ChoiceSheet(title = title, onDismiss = onClose, onClose = onClose, closeFocus = closeFocus, ignoreRelease = !touch, fullScreen = true) {
        ChoiceList {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyLarge, color = Tone.text)
                }
            }
        }
        FocusOnOpen(closeFocus)
    }
}
