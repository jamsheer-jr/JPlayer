package com.tmplayer.ui.browse

import com.tmplayer.ui.components.TopMessageHost
import com.tmplayer.ui.components.TouchPullRefresh
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesProgress
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * A show's tile in a chat's grid or a row on Home: the frame a video's tile has ([VideoTile]), with
 * the show's art and "2 seasons · 18 eps" under its name, as a 2:3 [poster] card or a 16:9 one.
 * [badge] goes in the art's top end corner.
 */
@Composable
internal fun SeriesCard(
    series: Series,
    progress: SeriesProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onFocused: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    /** A 2:3 poster card, in a grid of films and shows; a 16:9 card in a row of videos. */
    poster: Boolean = false,
    /** Episodes posted since the viewer last watched the show: a "New Episode" tag on the picture. */
    fresh: Boolean = false,
    /** A tag across the foot of the art (Home's Recently added row), or null for none. */
    bottomTag: String? = null,
    /** A long press or a held OK: the show's menu ([ShowMenu]), as a video's tile opens its panel (HX20). */
    onLongClick: (() -> Unit)? = null,
) {
    val summary = listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  ")
    VideoTile(
        title = series.title,
        onClick = onClick,
        onFocused = onFocused,
        onLongClick = onLongClick ?: LocalSeriesHold.current?.let { hold -> { hold(series) } },
        dense = dense,
        poster = poster,
        modifier = modifier,
        meta = { ink ->
            // A show already begun keeps its line in the accent; otherwise the tile's own colour.
            val accent = Tone.accent
            val begun = progress.watched > 0
            TileCaption(
                summary,
                if (poster) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                remember(begun, accent, ink) { if (begun) androidx.compose.ui.graphics.ColorProducer { accent } else ink },
            )
        },
    ) {
        // VideoTile already rounds the art's corners.
        SeriesArt(series, progress, Modifier.fillMaxSize(), compact = dense, badge = badge, poster = poster, fresh = fresh, bottomTag = bottomTag, clip = false)
    }
}

/**
 * A show card's menu on a phone and a television (HX20): Details, Watch later, Mark as watched and
 * Go to the chat, on the show made whole from its chat so "the whole show" means every episode.
 */
@Composable
internal fun SeriesMenuOpened(
    series: Series,
    watch: SeriesWatch,
    chatTitle: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onOpenChat: (() -> Unit)? = null,
) {
    val whole = com.tmplayer.ui.browse.rememberWholeSeries(series)
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = rememberShowActions(
        whole, watch,
        remember(context) { com.tmplayer.data.SettingsStore(context) },
        remember(context) { com.tmplayer.data.WatchedStore(context) },
        chatTitle = chatTitle,
        onMessage = { com.tmplayer.ui.components.showMessage(context, it) },
        onOpenChat = onOpenChat,
    )
    ShowMenu(whole, watch, actions, onDismiss = onDismiss, onOpen = onOpen)
}

/**
 * A show, opened: a page of its own over everything, on a phone and a television alike. Choosing
 * an episode plays it with the page left open under the player, so Back from the player lands on
 * the show again, on the episode that was pressed.
 */
@Composable
internal fun SeriesOpened(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)? = null,
    /** Download season and Download unwatched; see [queueSeason]. Null leaves them out. */
    onDownload: ((SeasonDownloadRequest) -> Unit)? = null,
    /** The chat the show is in, for the page's facts line. */
    chatTitle: String? = null,
    /** The show's chat, from a page opened outside it (Home, search, Watch later); null leaves Go to out. */
    onOpenChat: (() -> Unit)? = null,
) {
    // Every episode its chat holds, not only those the screen behind had read.
    val readWhole = com.tmplayer.ui.browse.rememberWholeSeries(series)
    // A pull on the page (owner, v1.0.20) reads the show again and stands the
    // fresher whole in the read one's place.
    var refreshed by remember(series.key) { androidx.compose.runtime.mutableStateOf<com.tmplayer.data.Series?>(null) }
    val whole = refreshed ?: readWhole
    var refreshing by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Which episodes are in the Downloads folder, for the season's download buttons: read again
    // whenever the index changes (a download finishing, or one deleted while the page is open),
    // each file asked of the disk off the drawing thread, as [queueSeason] does.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.tmplayer.data.SettingsStore(context) }
    // The hero's trailer autoplay follows its Playback switch, on until read.
    val heroTrailerAutoplay by remember(settings) { settings.heroTrailerAutoplay }
        .collectAsStateWithLifecycle(initialValue = true)
    val history by remember(settings) { settings.downloadHistory }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedIds by produceState(initialValue = emptySet<String>(), history) {
        value = withContext(Dispatchers.IO) {
            history.filter { com.tmplayer.data.LocalDownloads.presentVideo(it) != null }
                .mapTo(HashSet()) { "${it.chatId}:${it.messageId}" }
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The saved place goes first, so the player opening a moment later finds nothing to resume
    // from, as the video page's Start over does.
    val startOver: (MediaItem) -> Unit = { item ->
        scope.launch {
            runCatching { settings.clearResumePosition(item.chatId, item.messageId, item) }
            onPlay(item)
        }
    }
    // Watch later, Mark as watched and Go to the chat for the show as a whole (HX20).
    val showActions = rememberShowActions(
        whole, watch, settings, remember(context) { com.tmplayer.data.WatchedStore(context) },
        chatTitle = chatTitle.orEmpty(),
        onMessage = { com.tmplayer.ui.components.showMessage(context, it) },
        onOpenChat = onOpenChat,
    )
    // Opening the page clears the show's "New Episode" tag (owner, v1.0.13): the tag stands
    // while episodes arrived after the viewer last watched the show, and opening the show is
    // a watch act of the same kind as playing one, so it writes the same touch the watch path
    // writes: the latest watched copy's resume line and watched mark are stamped now, in the
    // stores that already hold them, and newEpisodes then finds nothing posted after the
    // stamp. Once per page opened, and only while the tag stands.
    val watchedStore = remember(context) { com.tmplayer.data.WatchedStore(context) }
    val touchedOnOpen = remember(series.key) { booleanArrayOf(false) }
    androidx.compose.runtime.LaunchedEffect(series.key, whole, watch) {
        if (touchedOnOpen[0]) return@LaunchedEffect
        touchedOnOpen[0] = true
        if (watch.newEpisodes(whole) <= 0) return@LaunchedEffect
        val latest = whole.episodes.flatMap { it.copies }
            .filter { watch.finished(it) || watch.point(it) != null }
            .maxByOrNull { watch.touchedAt(it) ?: Long.MIN_VALUE }
            ?: return@LaunchedEffect
        runCatching { settings.touchResume(latest.chatId, latest.messageId) }
        runCatching { watchedStore.touchWatched(latest.chatId, latest.messageId) }
    }
    SeriesPage(
        whole, watch, onPlay, startOver, onDismiss, onLongClick.takeIf { isTouch() }, onDownload, chatTitle, downloadedIds, showActions, heroTrailerAutoplay,
        refreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    runCatching { com.tmplayer.ui.browse.WholeSeries.read(whole) }.getOrNull()?.let { refreshed = it }
                    refreshing = false
                }
            }
        },
    )
}

/**
 * The show's page: full screen, edge to edge, its backdrop under the status bar, in a window of
 * its own like [com.tmplayer.ui.components.TvMenu], so Back (the key, the gesture or the round
 * button at the top start) closes it and the grid behind keeps the focus it had. On a television
 * it keeps inside the overscan, with the play button focused first.
 */
@Composable
private fun SeriesPage(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onStartOver: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)?,
    onDownload: ((SeasonDownloadRequest) -> Unit)?,
    chatTitle: String?,
    downloadedIds: Set<String>,
    showActions: ShowActions,
    heroTrailerAutoplay: Boolean,
    /** A pull down on a phone asks the chats for the show again (owner, v1.0.20). */
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val touch = isTouch()
    // On a phone the page grows out of the show's tile, and folds back towards it.
    val motion = com.tmplayer.ui.components.rememberPageMotion(onDismiss)
    Dialog(
        onDismissRequest = motion.close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (ScreenshotBarsOverride.hidden) HideDialogBars()
        motion.Frame {
        Box(Modifier.fillMaxSize().background(Tone.background)) {
            val panel: @Composable () -> Unit = {
                SeriesPanel(
                    series = series,
                    watch = watch,
                    // The page stays open under the player, so Back from an episode lands on the
                    // show again, with its progress and Continue line brought up to date.
                    onPlay = onPlay,
                    onStartOver = onStartOver,
                    onLongClick = onLongClick,
                    onDownload = onDownload,
                    downloadedIds = downloadedIds,
                    chatTitle = chatTitle,
                    showActions = showActions,
                    // A hold on a season tab marks the whole season watched in one tap (item 36,
                    // v1.0.15): the page menu's own season choice, taken without the menu. The
                    // bulk path is the watched store's, under the acting profile (the store is
                    // the profile's), and Watch later is crossed off only by a whole-show mark,
                    // the menu's rule, never by a season's.
                    onMarkSeason = showActions.onMark?.let { mark ->
                        { held ->
                            val choice = com.tmplayer.data.ShowMarks.choices(series, held, watch::finishedEpisode)
                                .firstOrNull { it.watched && it.scope == com.tmplayer.data.ShowMarks.Scope.Season }
                            if (choice != null) mark(choice)
                        }
                    },
                    heroTrailerAutoplay = heroTrailerAutoplay,
                    contentPadding = if (touch) {
                        PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    } else {
                        PaddingValues(start = Tv.SafeH, end = Tv.SafeH, bottom = Tv.SafeV)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                    // A remote has Back on it; a finger needs something to press.
                    onBack = motion.close.takeIf { touch },
                    barInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                )
            }
            // A phone page can be pulled down to ask again (owner, v1.0.20); a
            // television has no pull, and its page re-reads behind itself as always.
            if (touch && onRefresh != null) {
                TouchPullRefresh(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { panel() }
            } else {
                panel()
            }
            // Over the page, which is a window of its own above the app's.
            TopMessageHost()
        }
        }
    }
}

/**
 * Set by the promo build's screenshot fixture when it hides the system bars (`--ez nobars true`):
 * the show's page is a window of its own, so it has to hide them again for itself. Never set in
 * the app.
 */
object ScreenshotBarsOverride {
    @Volatile
    var hidden: Boolean = false
}

@Composable
private fun HideDialogBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }
}

/**
 * A show card's hold (HX20) where the card's own caller hands it none: Home's rows, whose tiles sit
 * several functions down. The browse screen provides it and draws the show's menu.
 */
internal val LocalSeriesHold = androidx.compose.runtime.staticCompositionLocalOf<((Series) -> Unit)?> { null }
