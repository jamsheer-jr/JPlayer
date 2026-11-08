package com.tmplayer.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text as M3Text
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.R
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.online.DownloadResult
import com.tmplayer.online.OnlineSubtitles
import com.tmplayer.online.OnlineWords
import com.tmplayer.online.SearchResult
import com.tmplayer.online.SubtitleHit
import com.tmplayer.online.SubtitleNotice
import com.tmplayer.online.SubtitleTarget
import com.tmplayer.online.SubtitleTranslator
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The audio and subtitle chooser, and the subtitles' "Search online" results, drawn as the
 * language picker's modal ([ChoiceSheet]) over the dimmed video on a phone and a television alike.
 *
 * Choosing a track acts on the one tap or press and closes. With the tracks sit the timing lines
 * (earlier, later, reset) and, for subtitles, their look. Those act in place and leave the picker
 * open where it was scrolled to, so a line can be nudged a tenth at a time while it plays behind
 * the dimmed list, and the look changes on the subtitles as they are chosen. On a touch screen
 * the panel opens on the track list itself, the video's own embedded tracks first among them,
 * with the jobs (open a file or a link, search online, translate), the synchronization and the
 * customization as compact rows and controls under it; on a television the one column keeps
 * its order, tracks first.
 *
 * For subtitles, "Search online" sits under the tracks and turns the panel into what OpenSubtitles
 * (and SubDL, with the viewer's key) has for the video. Downloads work without signing in; what
 * stands in the way (the day's downloads spent, with or without an account, the service
 * unavailable) is said above the results, and the results it blocks are greyed out. Back returns
 * from the results to the tracks; Close at the bottom closes the picker from either page.
 *
 * A fragment on the activity's back stack, so the player knows the picker is up ([isOpen]) and
 * holds its controls while it is. The panel itself is a window of its own (a dialog), which takes
 * the remote's keys away from the player while it is up: nothing behind it seeks or pauses.
 */
@UnstableApi
class TrackPickerFragment : Fragment() {

    private val trackType: Int get() = requireArguments().getInt(ARG_TRACK_TYPE)
    private val host: TrackPickerHost? get() = activity as? TrackPickerHost

    /** A track to choose, its name and, under it, what tells it apart from a namesake. */
    private class Option(val group: Tracks.Group?, val trackIndex: Int, val label: String, val detail: String? = null)

    private enum class Page { Tracks, Online, Msone, Translate }

    /** The groups a page's lines fall into, in order, with the heading each one carries. */
    private enum class Group(val heading: () -> String?) {
        Tracks({ null }),
        Online({ null }),
        Timing({ L.tracksSectionTiming }),
        Look({ L.tracksSectionLook }),
    }

    /** One line of either page, drawn as the phone's or the television's row. */
    private class Line(
        val key: Any,
        val title: String,
        val detail: String? = null,
        val selected: Boolean? = null,
        val icon: ImageVector? = null,
        val enabled: Boolean = true,
        val group: Group = Group.Tracks,
        val onClick: () -> Unit,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Not named Content: inside apply that resolves to ComposeView.Content, which draws
            // this same lambda again, and the player died of a stack overflow on every TV.
            setContent { TMPlayerTheme { PickerPanel() } }
        }

    /** Closes the picker from any page. */
    private fun close() {
        val fm = parentFragmentManager
        if (isAdded && !fm.isStateSaved) fm.popBackStack(BACK_STACK, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    @Composable
    private fun PickerPanel() {
        val startOnline = requireArguments().getBoolean(ARG_ONLINE)
        var page by remember { mutableStateOf(if (startOnline) Page.Online else Page.Tracks) }
        val touch = isTouch()
        val closeFocus = remember { FocusRequester() }
        val firstFocus = remember { FocusRequester() }

        // From the results, Back is a step back to the tracks; from the tracks it closes. The
        // panel is a dialog window of its own, so Back reaches it as the dialog's dismissal
        // (onDismiss below), never this fragment; a BackHandler here was never asked.
        val back: () -> Unit = { if (startOnline) close() else page = Page.Tracks }

        when (page) {
            Page.Tracks -> {
                // Bumped by every in-place line, so the timing and look figures under them redraw.
                var tick by remember { mutableIntStateOf(0) }
                val title = if (trackType == C.TRACK_TYPE_AUDIO) L.tracksAudioLanguage else L.tracksSubtitles
                if (touch) {
                    // The phone's panel is the MX shaped side panel (item 7, v1.0.16): see
                    // [MxTrackPanel]. The television keeps the centred sheet and its list.
                    MxTrackPanel(
                        title = title,
                        version = tick,
                        onInPlace = { tick++ },
                        onOnline = { page = Page.Online },
                        onMsone = { page = Page.Msone },
                        onTranslate = { page = Page.Translate },
                    )
                } else {
                    ChoiceSheet(title = title, onDismiss = ::close, onClose = ::close, closeFocus = closeFocus, ignoreRelease = true, fullScreen = false) {
                        @Suppress("UNUSED_EXPRESSION") tick
                        val lines = trackLines(
                            onOnline = { page = Page.Online },
                            onMsone = { page = Page.Msone },
                            onTranslate = { page = Page.Translate },
                            onInPlace = { tick++ },
                            lookIcon = ImageVector.vectorResource(R.drawable.ic_subtitles),
                        )
                        Lines(
                            lines,
                            firstFocus,
                            focusKey = lines.firstOrNull { it.selected == true }?.key ?: lines.firstOrNull()?.key,
                            onInPlace = { tick++ },
                            version = tick,
                        )
                        // Inside the dialog's own composition: asked from outside, the request came
                        // before the lines existed, and the window's own focus fell on the first line
                        // (Off) instead of the track playing.
                        FocusOnOpen(firstFocus)
                    }
                }
            }
            Page.Online -> OnlinePage(closeFocus, firstFocus, back, msone = false)
            Page.Msone -> OnlinePage(closeFocus, firstFocus, { page = Page.Tracks }, msone = true)
            Page.Translate -> TranslatePage(closeFocus, firstFocus, back)
        }
    }

    // ---- the tracks ---------------------------------------------------------------------------

    /** The choices for this panel's track type, Off first for subtitles, the file's own tracks before loaded ones. */
    private fun optionsOf(player: Player): List<Option> = buildList {
            // Subtitles need an explicit "off"; audio always has at least one track playing.
            if (trackType == C.TRACK_TYPE_TEXT) add(Option(null, -1, L.commonOff))
            var number = 0
            for (group in player.currentTracks.groups) {
                if (group.type != trackType) continue
                for (index in 0 until group.length) {
                    if (!group.isTrackSupported(index)) continue
                    number++
                    val format = group.getTrackFormat(index)
                    add(Option(group, index, describe(group, index, number - 1), tags(format).ifEmpty { null }?.joinToString("  ·  ")))
                }
            }
        }.let { built ->
            // The video's own tracks first (owner, 2026-11-06): a subtitle the file itself
            // carries stands above the ones loaded onto it from a file, a link or a search,
            // which keep the player's order among themselves. Off stays at the head, where
            // it has always been. Sound has no loaded tracks, so its order is untouched.
            if (trackType != C.TRACK_TYPE_TEXT) return@let built
            val (off, tracks) = built.partition { it.group == null }
            off + tracks.sortedBy { option -> if (isLoadedTrack(option)) 1 else 0 }
        }.let(::numberNamesakes)

    private fun trackLines(onOnline: () -> Unit, onMsone: () -> Unit, onTranslate: () -> Unit, onInPlace: () -> Unit, lookIcon: ImageVector): List<Line> {
        val host = host ?: return emptyList()
        val player = host.player ?: return emptyList()
        val options = optionsOf(player)
        return buildList {
            val none = options.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && options.size == 1)
            if (none) {
                add(Line("none", if (trackType == C.TRACK_TYPE_AUDIO) L.tracksNoOtherAudio else L.tracksNoSubtitles) { close() })
                // Sound can still be out of step with only the one track to choose from.
                if (trackType == C.TRACK_TYPE_AUDIO && options.isNotEmpty()) addAll(timingLines(host, onInPlace))
                // No subtitles in the file is exactly when looking online is worth it.
                if (trackType == C.TRACK_TYPE_TEXT) {
                    addAll(openLines())
                    onlineLine(onOnline)?.let(::add)
                    msoneLine(onMsone)?.let(::add)
                    translateLine(onTranslate)?.let(::add)
                }
                return@buildList
            }
            // The pair playing together, where there is one (see [pickTrack]): the main
            // track is the first of the pair in this list's order, the companion the
            // second, and the companion's row says so.
            val chosenPair = if (trackType == C.TRACK_TYPE_TEXT) {
                options.filter { it.group != null && isSelected(player.currentTracks, it) }
            } else {
                emptyList()
            }
            val companion = chosenPair.getOrNull(1)
            options.forEachIndexed { index, option ->
                val selected = isSelected(player.currentTracks, option)
                val isCompanion = companion != null && option.group != null &&
                    option.group === companion.group && option.trackIndex == companion.trackIndex
                add(
                    Line(
                        key = "track$index",
                        title = option.label,
                        detail = listOfNotNull(
                            if (isCompanion) L.tracksDual else null,
                            if (selected && !isCompanion) L.tracksPlayingNow else null,
                            option.detail,
                        ).ifEmpty { null }?.joinToString("  ·  "),
                        selected = selected,
                    ) { pickTrack(option) },
                )
            }
            if (trackType == C.TRACK_TYPE_TEXT) {
                addAll(openLines())
                onlineLine(onOnline)?.let(::add)
                msoneLine(onMsone)?.let(::add)
                translateLine(onTranslate)?.let(::add)
            }
            addAll(timingLines(host, onInPlace))
            if (trackType == C.TRACK_TYPE_TEXT) addAll(styleLines(host, onInPlace, lookIcon))
        }
    }

    /** "Search online", in a build that carries the OpenSubtitles key. */
    private fun onlineLine(onOnline: () -> Unit): Line? {
        if (!OnlineSubtitles.available) return null
        return Line("online", L.onlineSearchOnline, L.onlineSearchOnlineDetail, icon = TmIcons.Search, group = Group.Online, onClick = onOnline)
    }

    /**
     * MSone's search: through the gateway in a build with its key, or read from MSone's public
     * site in a build without one (item 10, v1.0.15), and unless hidden on this device (`msone`
     * as the SubDL key). It needs no OpenSubtitles key.
     */
    private fun msoneLine(onMsone: () -> Unit): Line? {
        val online = OnlineSubtitles.current ?: return null
        if (!online.msoneShown && !online.msoneSiteShown) return null
        return Line("msone", L.msoneSearch, L.msoneSearchDetail, icon = TmIcons.Search, group = Group.Online, onClick = onMsone)
    }

    /**
     * "Translate subtitles": the language subtitles are carried into as they are downloaded or
     * loaded, Off when none is chosen. Needs no provider key: the setting lives on the device,
     * and the translation itself is keyless.
     */
    private fun translateLine(onTranslate: () -> Unit): Line? {
        val online = OnlineSubtitles.current ?: return null
        val target = online.translateToNow()
        return Line(
            "translate",
            L.onlineTranslateTitle,
            if (target.isBlank()) L.commonOff else OnlineSubtitles.languageName(target),
            icon = TmIcons.Language,
            group = Group.Online,
            onClick = onTranslate,
        )
    }

    /** The offset now in force, under each timing line, so a press shows where it got to. */
    private fun timingLines(host: TrackPickerHost, onInPlace: () -> Unit): List<Line> {
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        val delays = host.syncDelaysNow()
        val ms = if (subtitles) delays.subtitleMs else delays.audioMs
        val step = L.tracksDelayStep(SyncDelays.label(ms))
        fun nudge(direction: Int) {
            host.stepDelay(trackType, direction)
            onInPlace()
        }
        return listOf(
            Line("earlier", if (subtitles) L.tracksSubtitlesEarlier else L.tracksSoundEarlier, step, icon = TmIcons.ChevronLeft, group = Group.Timing) { nudge(-1) },
            Line("later", if (subtitles) L.tracksSubtitlesLater else L.tracksSoundLater, step, icon = TmIcons.ChevronRight, group = Group.Timing) { nudge(1) },
            // Greys itself out at zero, but keeps the remote: the row stays focusable.
            Line(
                key = "reset",
                title = L.tracksResetTiming,
                detail = if (ms == 0L) L.tracksDelayNone else L.tracksDelayNow(SyncDelays.label(ms)),
                icon = TmIcons.Refresh,
                enabled = ms != 0L,
                group = Group.Timing,
            ) { nudge(0) },
        )
    }

    private fun styleLines(host: TrackPickerHost, onInPlace: () -> Unit, icon: ImageVector): List<Line> {
        val style = host.subtitleStyleNow()
        fun change(next: SubtitleStyle) {
            host.changeSubtitleStyle(next)
            onInPlace()
        }
        // Each line steps through its choices with one press, the way a remote drives them; the
        // subtitles behind the dimmed list are the preview.
        return listOf(
            Line("size", L.tracksSubtitleSize, style.size.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextSize()) },
            Line("colour", L.subtitlesColour, style.colour.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextColour()) },
            Line("edge", L.subtitlesEdge, style.edge.label, icon = icon, enabled = !style.box, group = Group.Look) { change(host.subtitleStyleNow().nextEdge()) },
            Line("box", L.tracksBackgroundBox, style.boxLevel.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextBox()) },
            Line("face", L.subtitlesFace, style.face.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextFace()) },
            Line("weight", L.subtitlesWeight, style.weightLabel, icon = icon, group = Group.Look) {
                host.subtitleStyleNow().let { change(it.copy(bold = !it.bold)) }
            },
            Line("position", L.tracksSubtitlePosition, style.position.label, icon = icon, group = Group.Look) { change(host.subtitleStyleNow().nextPosition()) },
            // Greys itself out on the default look, but keeps the remote: the row stays focusable.
            Line(
                key = "look_reset",
                title = L.subtitlesDefaultLook,
                detail = L.subtitlesDefaultLookDetail,
                icon = TmIcons.Refresh,
                enabled = !style.isDefault,
                group = Group.Look,
            ) { change(SubtitleStyle()) },
        )
    }

    private fun choose(option: Option) {
        val player = host?.player
        if (player != null) {
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(trackType)
            if (option.group == null) {
                builder.setTrackTypeDisabled(trackType, true)
            } else {
                builder
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
            }
            player.trackSelectionParameters = builder.build()
        }
        close()
    }

    /**
     * The one tap rule for the subtitle list (owner, v1.0.16): there is no second list
     * and no dual toggle, the list itself is the whole control. A tap picks the track
     * as the subtitle, as [choose] always did; a tap on another track while one is
     * playing makes that track the companion shown together with the first (see
     * [chooseSecond]); a tap on the companion takes the companion off again, leaving
     * the main track playing. Sound, and the Off row, keep [choose] as they are.
     */
    private fun pickTrack(option: Option) {
        val player = host?.player
        if (trackType != C.TRACK_TYPE_TEXT || option.group == null || player == null) {
            choose(option)
            return
        }
        val chosen = optionsOf(player).filter { it.group != null && isSelected(player.currentTracks, it) }
        val main = chosen.firstOrNull()
        val companion = chosen.getOrNull(1)
        val isCompanion = companion != null && option.group === companion.group && option.trackIndex == companion.trackIndex
        val isMain = main != null && option.group === main.group && option.trackIndex == main.trackIndex
        when {
            isCompanion -> chooseSecond(option)
            isMain || main == null -> choose(option)
            else -> chooseSecond(option)
        }
    }

    /**
     * Picks (or unpicks) the companion subtitle shown together with the main one: the
     * pair is rebuilt as two overrides of the text type, the main track first, so both
     * render together in the one subtitle view, the companion's lines above the main
     * one's as the view stacks them, with the panel's styling and lift applying to the
     * pair as one. Picking the track that is already the companion takes it off again;
     * picking with nothing playing makes the pick the only track. Which of the pair is
     * called the main one is the first in the panel's order, so after a pair is set the
     * two may trade names here while the pair on screen is unchanged. Reached only
     * through [pickTrack]: the one list carries the whole dual choice (owner, v1.0.16).
     */
    private fun chooseSecond(option: Option) {
        val player = host?.player
        val optionGroup = option.group
        if (player != null && optionGroup != null) {
            val chosen = optionsOf(player).filter { it.group != null && isSelected(player.currentTracks, it) }
            val main = chosen.firstOrNull()
            val isSecond = chosen.getOrNull(1)
                ?.let { it.group === optionGroup && it.trackIndex == option.trackIndex } == true
            // The pair that stands after this tap: the tapped track taken on as the
            // companion (an older companion gives its place up), the companion taken
            // off again, or the pair untouched when the main track itself was tapped.
            val pair: List<Option> = when {
                main == null -> listOf(option)
                isSecond -> listOf(main)
                main.group === optionGroup && main.trackIndex == option.trackIndex -> chosen.take(2)
                else -> listOf(main, option)
            }
            // One override per track group, holding every picked track of that group.
            // The player's parameters keep a single override for a group, so a second
            // override naming the same group quietly replaced the first, and two
            // subtitles out of one file (the common case: one embedded set) could
            // never stand together (owner's phone, v1.0.17).
            val builder = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, false)
            pair.mapNotNull { pick -> pick.group?.let { it.mediaTrackGroup to pick.trackIndex } }
                .groupBy({ it.first }, { it.second })
                .forEach { (group, indices) ->
                    builder.addOverride(TrackSelectionOverride(group, indices))
                }
            player.trackSelectionParameters = builder.build()
        }
        close()
    }

    // ---- online -------------------------------------------------------------------------------

    /**
     * The results of "Search online", or with [msone] of MSone's search. MSone's state at the
     * gateway (not set up, paused or stopped at its request) shows where the results would be,
     * with the gateway's own words under it, Check again, and a subtitle file of the viewer's own.
     * In a build without the gateway key the same page lists what MSone's public site holds for
     * this video (item 10, v1.0.15), with the same file and link rows as its fallback.
     */
    @Composable
    private fun OnlinePage(closeFocus: FocusRequester, firstFocus: FocusRequester, onBack: () -> Unit, msone: Boolean) {
        val online = OnlineSubtitles.current ?: return close()
        val scope = rememberCoroutineScope()
        var attempt by remember { mutableIntStateOf(0) }
        // Check again asks the gateway even inside the six hours between checks.
        var force by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<SearchResult?>(null) }
        var override by remember { mutableStateOf<SubtitleNotice?>(null) }
        var target by remember { mutableStateOf<SubtitleTarget?>(null) }
        var busy by remember { mutableStateOf<SubtitleHit?>(null) }
        // The MSone site hit whose download failed (item 5, v1.0.16): its release page can
        // be opened in the browser, where the download and the Open row remain the way on.
        var failedHit by remember { mutableStateOf<SubtitleHit?>(null) }
        // A typed ask of the viewer's own (item 15, v1.0.17): the MSone page's manual
        // search, for a video whose own name reads wrong.
        var manualQuery by remember { mutableStateOf<String?>(null) }
        var editingQuery by remember { mutableStateOf(false) }
        var queryText by remember { mutableStateOf("") }
        val pageContext = LocalContext.current

        LaunchedEffect(attempt) {
            result = null
            override = null
            failedHit = null
            val wanted = host?.onlineTarget()
            target = wanted
            if (msone && force && online.msoneInBuild) online.msoneStatus(force = true)
            force = false
            result = when {
                wanted == null -> SearchResult(emptyList())
                // The gateway where the build carries its key; MSone's public site where it does not.
                msone -> if (online.msoneInBuild) online.searchMsone(wanted) else online.searchMsoneSite(wanted, manualQuery)
                else -> online.search(wanted)
            }
        }

        ChoiceSheet(
            title = if (msone) L.msoneTitle else L.onlineResultsTitle,
            note = if (msone) L.msoneCredit else L.onlineSearchOnlineDetail,
            onDismiss = onBack,
            onClose = ::close,
            closeFocus = closeFocus,
            fullScreen = false,
        ) {
            val shown = result
            if (shown == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Tone.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(if (msone) L.msoneSearching else L.onlineSearching, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                }
                // A search that runs long says so, with Retry, rather than spinning in silence.
                com.tmplayer.ui.components.SlowAnswerNotice(key = attempt, onRetry = { attempt++ }, modifier = Modifier.padding(top = 12.dp))
                // Nothing to choose yet, so the remote waits on Close.
                FocusOnOpen(closeFocus)
                return@ChoiceSheet
            }
            if (msone && !online.msoneInBuild && editingQuery) {
                // The manual search's editor (item 15, v1.0.17): type the title (and
                // year) the site should be asked for, and the list under it is the
                // typed ask's, ranked by this video as before.
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .heightIn(min = 52.dp)
                            .background(Tone.surfaceHigh, RoundedCornerShape(Corner.Medium))
                            .border(1.dp, Tone.outline, RoundedCornerShape(Corner.Medium))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (queryText.isEmpty()) {
                            Text(L.msoneSearchHint, style = MaterialTheme.typography.bodyLarge, color = Tone.muted)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = queryText,
                            onValueChange = { queryText = it.take(80) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Tone.accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                                manualQuery = queryText
                                editingQuery = false
                                attempt++
                            }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Lines(
                        listOf(
                            Line("msone-manual-go", L.commonSearch, icon = TmIcons.Search, group = Group.Online) {
                                manualQuery = queryText
                                editingQuery = false
                                attempt++
                            },
                            Line("msone-manual-cancel", L.commonCancel, group = Group.Online) { editingQuery = false },
                        ),
                        firstFocus,
                        "msone-manual-go",
                    )
                }
                return@ChoiceSheet
            }
            val notice = override ?: shown.notice
            if (notice != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Medium))
                        .background(Tone.surfaceHigh)
                        .padding(12.dp),
                ) {
                    Text(OnlineWords.notice(notice), style = MaterialTheme.typography.bodyMedium, color = Tone.text)
                    // The gateway's own words, as it gave them (MSone's, quoted by the operator).
                    OnlineWords.message(notice)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else if (shown.hits.isEmpty()) {
                Text(if (msone) L.msoneNoResults else L.onlineNoResults, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            }
            val lines = buildList {
                // The manual search stands at the head of the MSone list (item 15,
                // v1.0.17): the options are all here to pick from, and a wrong name
                // is the viewer's to correct.
                if (msone && !online.msoneInBuild) {
                    add(Line("msone-manual", L.msoneSearchManual, detail = manualQuery, icon = TmIcons.Search, group = Group.Online) {
                        queryText = manualQuery ?: ""
                        editingQuery = true
                    })
                }
                shown.hits.forEach { hit ->
                    add(
                        Line(
                            key = "hit:${hit.provider}:${hit.id}",
                            title = hit.release.ifBlank { hit.fileName },
                            detail = if (busy == hit) L.onlineDownloading else OnlineWords.hitDetail(hit),
                            enabled = online.blockedFor(hit) == null,
                        ) {
                            if (busy != null) return@Line
                            busy = hit
                            scope.launch {
                                try {
                                    when (val done = online.download(hit, target)) {
                                        is DownloadResult.Done -> {
                                            host?.attachOnlineSubtitle(done.file, done.label)
                                            close()
                                        }
                                        is DownloadResult.Failed -> {
                                            override = done.notice
                                            if (msone && !online.msoneInBuild) failedHit = hit
                                        }
                                    }
                                } finally {
                                    busy = null
                                }
                            }
                        },
                    )
                }
                if (notice is SubtitleNotice.MsoneOff) {
                    add(Line("retry", L.msoneCheckAgain, icon = TmIcons.Refresh, group = Group.Online) { force = true; attempt++ })
                } else if (notice == SubtitleNotice.Offline || notice == SubtitleNotice.Busy || notice == SubtitleNotice.MsoneBusy) {
                    add(Line("retry", L.commonTryAgain, icon = TmIcons.Refresh, group = Group.Online) { attempt++ })
                }
                // A release the app could not fetch (item 5, v1.0.16): its page in the
                // browser is never a dead end, the file it downloads loads through Open.
                val failed = failedHit
                if (msone && !online.msoneInBuild && failed != null) {
                    add(Line("open-browser", L.onlineOpenInBrowser, icon = TmIcons.OpenExternal, group = Group.Online) {
                        runCatching {
                            pageContext.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(failed.pageUrl.ifBlank { failed.id }),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    })
                }
                // Nothing found online: a file of the viewer's own is the way on, where there is a
                // picker. For MSone also whenever it cannot search, paused or stopped included.
                val pick = host?.pickSubtitleFile
                if (shown.hits.isEmpty() && (notice == null || msone) && pick != null) {
                    add(Line("file", if (msone) L.msonePickFile else L.playerLoadSubtitleFile, icon = TmIcons.Folder, group = Group.Online) {
                        close()
                        pick()
                    })
                }
                // Beside the file: the same subtitle by link, for a viewer who has one.
                val byLink = host?.loadSubtitleUrl
                if (shown.hits.isEmpty() && (notice == null || msone) && byLink != null) {
                    add(Line("url", L.playerLoadSubtitleUrl, icon = TmIcons.Download, group = Group.Online) {
                        close()
                        byLink()
                    })
                }
            }
            val focusKey = lines.firstOrNull { it.enabled }?.key
            Lines(lines, firstFocus, focusKey)
            // The remote goes to the first result that can be fetched, or to Close when there is
            // none, so it never sits on nothing.
            FocusOnOpen(if (focusKey != null) firstFocus else closeFocus, key = shown to focusKey)
        }
    }

    /**
     * The translation's language: Off, or one of [SubtitleTranslator.LANGUAGES], Malayalam
     * first. Choosing one keeps it for this video only and steps back to the tracks; the
     * choice kept in Settings is not touched. Subtitles already playing are not reloaded,
     * the choice counts from the next one downloaded or loaded.
     */
    @Composable
    private fun TranslatePage(closeFocus: FocusRequester, firstFocus: FocusRequester, onBack: () -> Unit) {
        val online = OnlineSubtitles.current ?: return close()
        val current = online.translateToNow()
        ChoiceSheet(
            title = L.onlineTranslateTitle,
            note = L.onlineTranslateVideoDetail,
            onDismiss = onBack,
            onClose = ::close,
            closeFocus = closeFocus,
            fullScreen = false,
        ) {
            val lines = buildList {
                add(Line("off", L.commonOff, selected = current.isBlank()) {
                    online.setPlayerTranslateTo("")
                    onBack()
                })
                SubtitleTranslator.LANGUAGES.forEach { code ->
                    add(Line("lang:$code", OnlineSubtitles.languageName(code), selected = current == code) {
                        online.setPlayerTranslateTo(code)
                        onBack()
                    })
                }
            }
            Lines(
                lines,
                firstFocus,
                focusKey = lines.firstOrNull { it.selected == true }?.key ?: lines.firstOrNull()?.key,
            )
            FocusOnOpen(firstFocus)
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    /**
     * [lines] in a column that scrolls inside the panel ([ChoiceList]); the one keyed [focusKey]
     * carries [firstFocus]. A Column rather than a lazy list, so a refresh after an in-place line
     * keeps the scroll where the viewer left it and the focused line is always composed.
     *
     * Each group after the first opens with its heading (Timing, Look), or, for the one line
     * that needs no heading (Search online, Try again), with the same room and no words.
     *
     * On a touch screen the tracks page ([onInPlace] given, which only it passes) is one
     * scrolling column with the track list itself first (owner, 2026-11-06), the video's own
     * embedded tracks at its head, then the opening rows (a file or a link of the viewer's
     * own, Search online, the other search, Translate) as plain rows under it, then
     * synchronization as one stepper, then customization (the size and position as segments,
     * the lift as a slider, the colours as swatches). On a television nothing here changes:
     * the one column keeps its order, the tracks first and the timing and look as lines
     * under them.
     */
    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.Lines(
        lines: List<Line>,
        firstFocus: FocusRequester,
        focusKey: Any?,
        onInPlace: (() -> Unit)? = null,
        /** Bumped by every in-place change, so the touch controls redraw what they read from the host. */
        version: Int = 0,
    ) {
        val (choose, adjust) = lines.partition { it.group == Group.Tracks || it.group == Group.Online }
        // On a touch screen the timing and the look are controls rather than lines: one stepper
        // for Earlier and Later, the size and position as segments, the box as a switch. Six
        // look-alike lines that each nudged or cycled something read as a puzzle under a finger.
        if (isTouch() && onInPlace != null) {
            val (functions, tracks) = choose.partition { it.group == Group.Online }
            ChoiceList {
                // The tracks themselves first (owner, 2026-11-06): choosing one is the job
                // the panel is opened for, and it was the last thing in it. The ways to add
                // a track and the timing and look controls stand under the list, compact
                // as they were, and nothing they offered is gone. The Open rows lead the
                // functions because [trackLines] builds them in first (item 5, v1.0.15).
                val opening = functions
                LineRows(tracks, firstFocus, focusKey)
                // A divider under the track list (owner, 2026-11-06): the tracks are one
                // section, everything the panel adds under them is another.
                if (tracks.isNotEmpty() && (opening.isNotEmpty() || adjust.isNotEmpty())) PanelDivider()
                if (opening.isNotEmpty()) LineRows(opening, firstFocus, focusKey)
                if (adjust.isNotEmpty()) {
                    TouchAdjust(
                        timing = adjust.any { it.group == Group.Timing },
                        look = adjust.any { it.group == Group.Look },
                        version,
                        onInPlace,
                    )
                }
            }
        } else {
            ChoiceList { LineRows(lines, firstFocus, focusKey) }
        }
    }

    /** Sound off altogether: the audio panel's Disable radio (item 7, v1.0.16). */
    private fun disableAudio() {
        val player = host?.player
        if (player != null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, true)
                .build()
        }
        close()
    }

    /**
     * The tracks page on a phone, in the shape of the owner's reference panels (item 7,
     * v1.0.16): a near black panel docked to the right of the picture, the title at the
     * top, then the tracks, a divider, the opening and searching rows, the
     * synchronization (and for subtitles the speed) as steppers, a round button either
     * side of a value pill, and the panel and customization entries opening their
     * controls in place under them. Subtitle tracks carry square checkboxes, the
     * companion of a dual pair among them (item 6); sound carries radios, the playing
     * one filled, with Disable under them and the downmix to stereo as its checkbox
     * row. Every row carries a function the panel already had: the tracks and their
     * one tap rule, Open by file or by link, Online subtitles and MSone, Translate,
     * the sync step, the look under Panel and Customization. The rows of the reference
     * panels that name things this player has no control for (a software audio
     * decoder choice, a stereo mode picker, an AV sync switch, opening an audio file)
     * are not drawn dead: the batch log names them. A television never draws this:
     * its page is the centred sheet and its list, unchanged.
     */
    @Composable
    private fun MxTrackPanel(
        title: String,
        version: Int,
        onInPlace: () -> Unit,
        onOnline: () -> Unit,
        onMsone: () -> Unit,
        onTranslate: () -> Unit,
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        val host = host ?: return
        val player = host.player ?: return
        val lines = trackLines(
            onOnline = onOnline,
            onMsone = onMsone,
            onTranslate = onTranslate,
            onInPlace = onInPlace,
            lookIcon = ImageVector.vectorResource(R.drawable.ic_subtitles),
        )
        val tracks = lines.filter { it.group == Group.Tracks }
        val functions = lines.filter { it.group == Group.Online }
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        var openShown by remember { mutableStateOf(false) }
        var panelShown by remember { mutableStateOf(false) }
        var lookShown by remember { mutableStateOf(false) }
        FloatingWindow(onDismiss = ::close, ignoreRelease = false) {
            val panelWidth = if (maxWidth > maxHeight) (maxWidth * 0.42f).coerceAtMost(480.dp) else (maxWidth * 0.88f).coerceAtMost(480.dp)
            // A touch anywhere outside the panel closes it (owner, 2026-11-08): the window
            // fills the screen, so the dialog's own outside tap never fires; this catcher is
            // the outside. The panel column carries its own empty clickable below, so its
            // dead space (padding, spacers) does not fall through to the catcher.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = ::close,
                    ),
            )
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .background(Color(0xFF0A0A0C))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                M3Text(title, style = M3Theme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(10.dp))
                tracks.forEach { line -> MxTrackRow(line, radio = !subtitles) }
                if (!subtitles) {
                    MxPlainRow(
                        title = L.tracksDisable,
                        radio = true,
                        radioSelected = tracks.none { it.selected == true },
                        onClick = ::disableAudio,
                    )
                    MxDownmixRow(onInPlace)
                }
                if (tracks.isNotEmpty() || functions.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Tone.outline)
                }
                if (subtitles) {
                    val opens = functions.filter { it.key == "open_file" || it.key == "open_url" }
                    if (opens.isNotEmpty()) {
                        MxPlainRow(title = L.commonOpen, onClick = { openShown = !openShown })
                        if (openShown) opens.forEach { line -> MxPlainRow(line.title, detail = line.detail, onClick = line.onClick) }
                    }
                    functions.filter { it.key != "open_file" && it.key != "open_url" }.forEach { line ->
                        MxPlainRow(line.title, detail = line.detail, enabled = line.enabled, onClick = line.onClick)
                    }
                }
                MxSyncStepper(host, onInPlace)
                if (subtitles) {
                    MxSpeedStepper(player, onInPlace)
                    MxPlainRow(title = L.tracksPanel, onClick = { panelShown = !panelShown })
                    if (panelShown) MxPanelControls(host, onInPlace)
                    MxPlainRow(title = L.tracksSectionCustomization, onClick = { lookShown = !lookShown })
                    if (lookShown) MxLookControls(host, onInPlace)
                }
            }
        }
    }

    /**
     * One track row of the side panel: a square checkbox for subtitles, a radio for
     * sound, the playing sound's radio filled. A line with no choice of its own (the
     * "no subtitles" line) is drawn plain, without a mark.
     */
    @Composable
    private fun MxTrackRow(line: Line, radio: Boolean) {
        val ink = if (line.enabled) Color.White else Color.White.copy(alpha = 0.45f)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = line.enabled, role = if (radio) Role.RadioButton else Role.Checkbox) { line.onClick() }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                line.selected == null -> Spacer(Modifier.width(20.dp))
                radio -> MxRadio(line.selected == true)
                else -> MxCheckbox(line.selected == true)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                M3Text(line.title, style = M3Theme.typography.bodyLarge, color = ink, maxLines = 2)
                if (line.detail != null) {
                    M3Text(line.detail, style = M3Theme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                }
            }
        }
    }

    /** A plain row of the side panel, with a radio at its head where [radio] asks for one (Disable). */
    @Composable
    private fun MxPlainRow(
        title: String,
        detail: String? = null,
        enabled: Boolean = true,
        radio: Boolean = false,
        radioSelected: Boolean = false,
        onClick: () -> Unit,
    ) {
        val ink = if (enabled) Color.White else Color.White.copy(alpha = 0.45f)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = if (radio) Role.RadioButton else Role.Button) { onClick() }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (radio) {
                MxRadio(radioSelected)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                M3Text(title, style = M3Theme.typography.bodyLarge, color = ink, maxLines = 2)
                if (detail != null) {
                    M3Text(detail, style = M3Theme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                }
            }
        }
    }

    /** The radio mark: an outline ring, filled with the accent at its centre when chosen. */
    @Composable
    private fun MxRadio(selected: Boolean) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Tone.accent else Color.White.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Tone.accent))
        }
    }

    /** The square checkbox mark: an outline square, filled with the accent and ticked when chosen. */
    @Composable
    private fun MxCheckbox(selected: Boolean) {
        val shape = RoundedCornerShape(3.dp)
        Box(
            Modifier
                .size(20.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(Tone.accent) else Modifier)
                .border(2.dp, if (selected) Tone.accent else Color.White.copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) M3Icon(TmIcons.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
        }
    }

    /**
     * The downmix to stereo as the audio panel's checkbox row: the same stored choice
     * Settings carries (see [com.tmplayer.player.AudioDownmix.wanted]), drawn filled
     * while the fold is what this device would do. It is read back on the next video,
     * as the Settings switch's own answer is.
     */
    @Composable
    private fun MxDownmixRow(onInPlace: () -> Unit) {
        val context = LocalContext.current
        val store = remember { SettingsStore(context.applicationContext) }
        val scope = rememberCoroutineScope()
        val choice by store.downmixChoice.collectAsState(initial = null)
        val wanted = AudioDownmix.wanted(choice, television = false)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Checkbox) {
                    scope.launch { store.setDownmix(!wanted) }
                    onInPlace()
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MxCheckbox(wanted)
            Spacer(Modifier.width(14.dp))
            M3Text(L.playerDownmix, style = M3Theme.typography.bodyLarge, color = Color.White, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }

    /**
     * Synchronization as the reference panels draw it: the name at the left, then a
     * round button, the figure on a dark pill, and a round button. A tap on the pill
     * itself resets the timing, the reset the list rows carried before.
     */
    @Composable
    private fun MxSyncStepper(host: TrackPickerHost, onInPlace: () -> Unit) {
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        val delays = host.syncDelaysNow()
        val ms = if (subtitles) delays.subtitleMs else delays.audioMs
        MxStepper(
            label = if (subtitles) L.playerSubtitleSync else L.tracksSectionTiming,
            value = SyncDelays.label(ms),
            onStep = { direction ->
                host.stepDelay(trackType, direction)
                onInPlace()
            },
            onReset = if (ms != 0L) {
                {
                    host.stepDelay(trackType, 0)
                    onInPlace()
                }
            } else {
                null
            },
        )
    }

    /** The playback speed as a stepper of fine steps (see [PlaybackSpeed.fine]), its pill reading as a percentage. */
    @Composable
    private fun MxSpeedStepper(player: Player, onInPlace: () -> Unit) {
        val speed = PlaybackSpeed.sanitise(player.playbackParameters.speed)
        val tenths = (speed * 1000).roundToInt()
        MxStepper(
            label = L.playerSpeed,
            value = "${tenths / 10}.${tenths % 10}%",
            onStep = { direction ->
                player.setPlaybackSpeed(PlaybackSpeed.fine(speed, up = direction > 0))
                onInPlace()
            },
        )
    }

    /** A stepper row: the label at the left, a round step button, the value on its pill, a round step button. */
    @Composable
    private fun MxStepper(label: String, value: String, onStep: (Int) -> Unit, onReset: (() -> Unit)? = null) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            M3Text(label, style = M3Theme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f), maxLines = 2)
            MxStepButton(TmIcons.Remove) { onStep(-1) }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .width(104.dp)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .then(if (onReset != null) Modifier.clickable(role = Role.Button) { onReset() } else Modifier)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                M3Text(value, style = M3Theme.typography.bodyLarge, color = Color.White, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            MxStepButton(TmIcons.Add) { onStep(1) }
        }
    }

    /** One round step button of a stepper: a dark disc with the step's sign on it. */
    @Composable
    private fun MxStepButton(icon: ImageVector, onClick: () -> Unit) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(role = Role.Button) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            M3Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }

    /**
     * The Panel entry's controls, opened in place under it: where the subtitle lines
     * stand (their position steps and the drag between them), the placement half of
     * the look the panel's list carried below the tracks.
     */
    @Composable
    private fun MxPanelControls(host: TrackPickerHost, onInPlace: () -> Unit) {
        val style = host.subtitleStyleNow()
        fun change(next: SubtitleStyle) {
            host.changeSubtitleStyle(next)
            onInPlace()
        }
        SegmentsLabel(L.tracksSubtitlePosition)
        Segments(SubtitlePosition.entries.map { it.label }, SubtitlePosition.entries.indexOf(style.position)) {
            change(host.subtitleStyleNow().copy(position = SubtitlePosition.entries[it]))
        }
        SegmentsLabel(L.tracksSubtitleDrag)
        Slider(
            value = style.lift,
            onValueChange = { change(host.subtitleStyleNow().copy(lift = it)) },
            valueRange = 0f..SubtitleStyle.MAX_LIFT,
            colors = SliderDefaults.colors(thumbColor = Tone.accent, activeTrackColor = Tone.accent),
            modifier = Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp),
        )
    }

    /**
     * The Customization entry's controls, opened in place under it: the size, the
     * colour, the background box, the edge, the face and the weight, and the way back
     * to the plain look: the rest of what the panel's list carried below the tracks.
     */
    @Composable
    private fun MxLookControls(host: TrackPickerHost, onInPlace: () -> Unit) {
        val style = host.subtitleStyleNow()
        fun change(next: SubtitleStyle) {
            host.changeSubtitleStyle(next)
            onInPlace()
        }
        val boldTick = com.tmplayer.ui.components.rememberToggleTick()
        SegmentsLabel(L.tracksSubtitleSize)
        Segments(SubtitleSize.entries.map { it.label }, SubtitleSize.entries.indexOf(style.size)) {
            change(host.subtitleStyleNow().copy(size = SubtitleSize.entries[it]))
        }
        SegmentsLabel(L.subtitlesColour)
        ColourSwatches(style.colour) { change(host.subtitleStyleNow().copy(colour = it)) }
        SegmentsLabel(L.tracksBackgroundBox)
        Segments(SubtitleBox.entries.map { it.label }, SubtitleBox.entries.indexOf(style.boxLevel)) {
            change(host.subtitleStyleNow().copy(boxLevel = SubtitleBox.entries[it]))
        }
        if (!style.box) {
            SegmentsLabel(L.subtitlesEdge)
            Segments(SubtitleEdge.entries.map { it.label }, SubtitleEdge.entries.indexOf(style.edge)) {
                change(host.subtitleStyleNow().copy(edge = SubtitleEdge.entries[it]))
            }
        }
        SegmentsLabel(L.subtitlesFace)
        Segments(SubtitleFace.entries.map { it.label }, SubtitleFace.entries.indexOf(style.face)) {
            change(host.subtitleStyleNow().copy(face = SubtitleFace.entries[it]))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = style.bold, role = Role.Switch) { on -> boldTick(on); change(host.subtitleStyleNow().copy(bold = on)) }
                .padding(horizontal = ADJUST_PAD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            M3Text(L.subtitlesWeightBold, style = M3Theme.typography.bodyLarge, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = style.bold, onCheckedChange = null)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { change(SubtitleStyle()) }, enabled = !style.isDefault) {
                M3Icon(TmIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                M3Text(L.subtitlesDefaultLook, maxLines = 1)
            }
        }
    }

    /**
     * The file and link rows of the subtitle panel: a subtitle file, or the same by link, the
     * host's own ways on (null where a build or a screen offers neither, as on a television,
     * whose host offers neither). Choosing one closes the picker first, as the online page's
     * own file and link lines do. Subtitles only: the audio panel has nothing to open.
     *
     * [trackLines] builds these in ahead of the online lines, so the one list every surface
     * draws follows the MX shape (item 5, v1.0.15): the tracks first, then Open, then Online
     * subtitles, then the synchronization steppers, then the panel's own entries (Translate
     * rides with Online, and the look lines close the list). Playback speed is not a track
     * panel entry at all: it stays in the player's More panel, where it already lives.
     */
    private fun openLines(): List<Line> {
        if (trackType != C.TRACK_TYPE_TEXT) return emptyList()
        val host = host ?: return emptyList()
        return buildList {
            host.pickSubtitleFile?.let { pick ->
                add(Line("open_file", L.playerLoadSubtitleFile, icon = TmIcons.Folder, group = Group.Online) { close(); pick() })
            }
            host.loadSubtitleUrl?.let { byLink ->
                add(Line("open_url", L.playerLoadSubtitleUrl, icon = TmIcons.Download, group = Group.Online) { close(); byLink() })
            }
        }
    }

    /**
     * The timing and, for subtitles, the look, as a touch screen draws them. Each change acts at
     * once on the video behind and redraws the figures here through [onInPlace].
     *
     * Drawn compact (owner, v1.0.13, "simple and small"): a 40 dp stepper with a smaller figure,
     * tighter gaps between the segment rows, 36 dp swatches and a 48 dp weight row, so the panel
     * that floats now (see [PickerPanel]) holds the tracks and these controls together.
     */
    @Composable
    private fun TouchAdjust(timing: Boolean, look: Boolean, version: Int, onInPlace: () -> Unit) {
        val host = host ?: return
        // What the host holds is not Compose state: reading [version] is what redraws the figure
        // and the ticked segment after a press, rather than only when the picker opens again.
        @Suppress("UNUSED_EXPRESSION") version
        val subtitles = trackType == C.TRACK_TYPE_TEXT
        if (timing) {
            // The touch panel names this job as the More panel does for subtitles; the sound's
            // own panel keeps the plain heading, since its sync is of the sound, not the lines.
            ChoiceSection(if (subtitles) L.playerSubtitleSync else L.tracksSectionTiming)
            val delays = host.syncDelaysNow()
            val ms = if (subtitles) delays.subtitleMs else delays.audioMs
            fun nudge(direction: Int) {
                host.stepDelay(trackType, direction)
                onInPlace()
            }
            // Earlier and Later either side of where the timing stands now, so a press shows its
            // answer between the two buttons; Reset sits under the figure once there is one.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { nudge(-1) }, modifier = Modifier.weight(1f).height(40.dp)) {
                    M3Icon(TmIcons.ChevronLeft, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    M3Text(L.tracksEarlier, maxLines = 1)
                }
                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    M3Text(SyncDelays.label(ms), style = M3Theme.typography.titleMedium, color = Tone.text, maxLines = 1)
                    if (ms != 0L) {
                        TextButton(onClick = { nudge(0) }, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                            M3Text(L.tracksResetTiming, maxLines = 1)
                        }
                    } else {
                        M3Text(L.tracksDelayNone, style = M3Theme.typography.bodySmall, color = Tone.muted, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
                OutlinedButton(onClick = { nudge(1) }, modifier = Modifier.weight(1f).height(40.dp)) {
                    M3Text(L.tracksLater, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    M3Icon(TmIcons.ChevronRight, contentDescription = null)
                }
            }
        }
        if (look) {
            val style = host.subtitleStyleNow()
            fun change(next: SubtitleStyle) {
                host.changeSubtitleStyle(next)
                onInPlace()
            }
            ChoiceSection(L.tracksSectionCustomization)
            SegmentsLabel(L.tracksSubtitleSize)
            Segments(SubtitleSize.entries.map { it.label }, SubtitleSize.entries.indexOf(style.size)) {
                change(host.subtitleStyleNow().copy(size = SubtitleSize.entries[it]))
            }
            SegmentsLabel(L.tracksSubtitlePosition)
            Segments(SubtitlePosition.entries.map { it.label }, SubtitlePosition.entries.indexOf(style.position)) {
                change(host.subtitleStyleNow().copy(position = SubtitlePosition.entries[it]))
            }
            // The drag (J Player): a slider over the lift the position stands on, so the lines
            // can be pulled to any height between the steps, and it stays where it is left.
            SegmentsLabel(L.tracksSubtitleDrag)
            Slider(
                value = style.lift,
                onValueChange = { change(host.subtitleStyleNow().copy(lift = it)) },
                valueRange = 0f..SubtitleStyle.MAX_LIFT,
                colors = SliderDefaults.colors(thumbColor = Tone.accent, activeTrackColor = Tone.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp),
            )
            val boldTick = com.tmplayer.ui.components.rememberToggleTick()
            SegmentsLabel(L.subtitlesColour)
            ColourSwatches(style.colour) { change(host.subtitleStyleNow().copy(colour = it)) }
            SegmentsLabel(L.tracksBackgroundBox)
            Segments(SubtitleBox.entries.map { it.label }, SubtitleBox.entries.indexOf(style.boxLevel)) {
                change(host.subtitleStyleNow().copy(boxLevel = SubtitleBox.entries[it]))
            }
            // The edge is for text without a box; with one it has nothing to do, and says so.
            if (!style.box) {
                SegmentsLabel(L.subtitlesEdge)
                Segments(SubtitleEdge.entries.map { it.label }, SubtitleEdge.entries.indexOf(style.edge)) {
                    change(host.subtitleStyleNow().copy(edge = SubtitleEdge.entries[it]))
                }
            }
            SegmentsLabel(L.subtitlesFace)
            Segments(SubtitleFace.entries.map { it.label }, SubtitleFace.entries.indexOf(style.face)) {
                change(host.subtitleStyleNow().copy(face = SubtitleFace.entries[it]))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = style.bold, role = Role.Switch) { on -> boldTick(on); change(host.subtitleStyleNow().copy(bold = on)) }
                    .padding(horizontal = ADJUST_PAD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                M3Text(L.subtitlesWeightBold, style = M3Theme.typography.bodyLarge, color = Tone.text, modifier = Modifier.weight(1f))
                Switch(checked = style.bold, onCheckedChange = null)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { change(SubtitleStyle()) }, enabled = !style.isDefault) {
                    M3Icon(TmIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    M3Text(L.subtitlesDefaultLook, maxLines = 1)
                }
            }
        }
    }

    /**
     * The five colours as round swatches, each named for a screen reader and ringed when chosen:
     * a colour is quicker to pick by sight than by its name.
     */
    @Composable
    private fun ColourSwatches(selected: SubtitleColour, onPick: (SubtitleColour) -> Unit) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SubtitleColour.entries.forEach { colour ->
                val chosen = colour == selected
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(
                            if (chosen) {
                                Modifier.border(3.dp, Tone.accent, CircleShape)
                            } else {
                                Modifier.border(1.dp, Tone.muted.copy(alpha = 0.5f), CircleShape)
                            },
                        )
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(colour.argb))
                        .selectable(selected = chosen, role = Role.RadioButton) { onPick(colour) }
                        .semantics { contentDescription = colour.label },
                    contentAlignment = Alignment.Center,
                ) {
                    if (chosen) M3Icon(TmIcons.Check, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    /** The name over a row of [Segments]. */
    @Composable
    private fun SegmentsLabel(title: String) {
        M3Text(
            title,
            style = M3Theme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.padding(horizontal = ADJUST_PAD).padding(top = 6.dp, bottom = 4.dp),
        )
    }

    /** One of a few, as Material's segmented buttons: every choice in sight, one tap each. */
    @Composable
    private fun Segments(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val segmentTick = com.tmplayer.ui.components.rememberStepTick()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD).padding(bottom = 8.dp)) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == selected,
                    onClick = { if (index != selected) segmentTick(); onPick(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = { M3Text(label, maxLines = 1) },
                )
            }
        }
    }

    /** The hairline under a panel's track section, in the sheets' own divider colour. */
    @Composable
    private fun PanelDivider() {
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ADJUST_PAD, vertical = 6.dp),
            color = Tone.outline,
        )
    }

    /** [lines] one under another, each group after the first opened by its heading or a gap. */
    @Composable
    private fun LineRows(lines: List<Line>, firstFocus: FocusRequester, focusKey: Any?) {
        lines.forEachIndexed { index, line ->
            val heading = line.group.heading()
            // A column that starts on Timing (the right half of a landscape phone) still names it.
            if (index == 0 && heading != null) {
                ChoiceSection(heading)
            } else if (index > 0 && lines[index - 1].group != line.group) {
                // The track section ends with a divider line (owner, 2026-11-06), the sections
                // after it with their headings or the plain gap they have always had.
                if (lines[index - 1].group == Group.Tracks) PanelDivider()
                if (heading != null) ChoiceSection(heading)
                else if (lines[index - 1].group != Group.Tracks) Spacer(Modifier.height(GROUP_GAP))
            }
            ChoiceLine(
                title = line.title,
                detail = line.detail,
                selected = line.selected,
                icon = line.icon,
                enabled = line.enabled,
                modifier = if (line.key == focusKey) Modifier.focusRequester(firstFocus) else Modifier,
                onClick = line.onClick,
            )
        }
    }

    // ---- naming the tracks --------------------------------------------------------------------

    /**
     * Whether [option] is a track loaded onto the video rather than one the file carries:
     * a loaded subtitle file's format id carries its "external-N" name (see
     * [ExternalSubtitle] and the player's own check), an embedded track's never does.
     */
    private fun isLoadedTrack(option: Option): Boolean {
        val group = option.group ?: return false
        return EXTERNAL_ID.containsMatchIn(group.getTrackFormat(option.trackIndex).id.orEmpty())
    }

    private fun isSelected(tracks: Tracks, option: Option): Boolean {
        if (option.group == null) {
            return tracks.groups.none { it.type == trackType && it.isSelected }
        }
        return option.group.isTrackSelected(option.trackIndex)
    }

    /** Language first, then sound format and channel layout: what tells dual audio apart. */
    private fun describe(group: Tracks.Group, index: Int, position: Int): String {
        val format = group.getTrackFormat(index)
        val language = format.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Translator.messages.formatter.trackLanguage(it) ?: it }
        val label = format.label?.takeIf { it.isNotBlank() }

        val name = when {
            label != null && language != null && !label.contains(language, ignoreCase = true) ->
                "$language ($label)"
            label != null -> label
            language != null -> language
            else -> L.tracksTrackNumber((position + 1).toString())
        }

        val details = buildList {
            soundFormat(format)?.let { add(it) }
            if (format.channelCount > 0) add(channels(format.channelCount))
        }

        return if (details.isEmpty()) name else "$name  ·  ${details.joinToString(" ")}"
    }

    /**
     * What tells a subtitle track apart from another in the same language: the script or region
     * its tag names (Simplified, Traditional, Brazil), Forced, SDH or CC, and the kind of file
     * (PGS pictures, SRT, ASS). A file with three Chinese tracks by one encoder otherwise listed
     * three identical rows. Empty for sound, whose name already carries its format and channels.
     */
    private fun tags(format: Format): List<String> {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return listOfNotNull(Translator.messages.formatter.trackLanguageVariant(format.language))
        }
        return buildList {
            Translator.messages.formatter.trackLanguageVariant(format.language)?.let(::add)
            if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add(L.tracksForced)
            when {
                format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 -> add("SDH")
                format.roleFlags and C.ROLE_FLAG_CAPTION != 0 -> add("CC")
            }
            subtitleFormat(format)?.let(::add)
        }
    }

    /** The subtitle file's kind, from its own MIME type or, once Media3 has parsed it, its codecs. */
    private fun subtitleFormat(format: Format): String? {
        val id = "${format.sampleMimeType.orEmpty()} ${format.codecs.orEmpty()}".lowercase(Locale.ROOT)
        return SUBTITLE_FORMATS.firstOrNull { (mime, _) -> id.contains(mime) }?.second
    }

    /**
     * Tracks that still read the same, name and tags alike, are told apart by their number
     * among the tracks of their kind, as a last resort.
     */
    private fun numberNamesakes(options: List<Option>): List<Option> {
        val seen = options.groupingBy { it.label to it.detail }.eachCount()
        var number = 0
        return options.map { option ->
            if (option.group == null) return@map option
            number++
            if ((seen[option.label to option.detail] ?: 0) < 2) return@map option
            val tag = L.tracksTrackNumber(number.toString())
            Option(option.group, option.trackIndex, option.label, listOfNotNull(option.detail, tag).joinToString("  ·  "))
        }
    }

    /**
     * The two sound-format names a viewer might recognise, and nothing else. Anything outside the
     * list is left out: the channel layout beside it already identifies the track.
     */
    private fun soundFormat(format: Format): String? {
        val id = "${format.codecs.orEmpty()} ${format.sampleMimeType.orEmpty()}".lowercase(Locale.ROOT)
        return when {
            DOLBY.any { id.contains(it) } -> "Dolby"
            DTS.any { id.contains(it) } -> "DTS"
            else -> null
        }
    }

    private fun channels(count: Int) = when (count) {
        1 -> L.tracksMono
        2 -> L.tracksStereo
        6 -> "5.1"
        8 -> "7.1"
        else -> L.tracksChannels(count.toString())
    }

    companion object {
        private const val ARG_TRACK_TYPE = "track_type"

        /** The room that opens a group without a heading: about what a heading's top takes. */
        private val GROUP_GAP = 6.dp

        /** A loaded subtitle file's mark in its format id: "external-1", "external-2", and so on. */
        private val EXTERNAL_ID = Regex("""external-\d+""")

        /** The touch controls' inset, the same 24 dp the lines carry, so they line up with the radios. */
        private val ADJUST_PAD = 24.dp
        private const val ARG_ONLINE = "online"
        private const val TAG = "track_picker"
        private const val BACK_STACK = "track_picker"

        // Spelled several ways depending on whether the id came from the MP4 sample entry or
        // from the MIME type, so both are matched.
        private val DOLBY = listOf("ac-3", "ac3", "ec-3", "eac3")
        private val DTS = listOf("dts", "dca")

        /** Subtitle MIME types (or codecs) and the name a viewer knows each by, most specific first. */
        private val SUBTITLE_FORMATS = listOf(
            "application/pgs" to "PGS",
            "application/vobsub" to "VobSub",
            "application/dvbsubs" to "DVB",
            "application/x-subrip" to "SRT",
            "text/x-ssa" to "ASS",
            "text/vtt" to "WebVTT",
            "application/ttml+xml" to "TTML",
            "application/cea-608" to "CEA-608",
            "application/cea-708" to "CEA-708",
            "application/x-quicktime-tx3g" to "TX3G",
        )

        /** Whether the picker is up, on either page. */
        fun isOpen(fm: FragmentManager): Boolean = fm.findFragmentByTag(TAG) != null

        /**
         * Opens the picker for [trackType] over [container]; with [online] straight on the
         * subtitles' "Search online" results, which Back then closes.
         */
        fun show(fm: FragmentManager, container: Int, trackType: Int, online: Boolean = false) {
            if (isOpen(fm) || fm.isStateSaved) return
            val picker = TrackPickerFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_TRACK_TYPE, trackType)
                    putBoolean(ARG_ONLINE, online)
                }
            }
            fm.beginTransaction().add(container, picker, TAG).addToBackStack(BACK_STACK).commit()
        }
    }
}
