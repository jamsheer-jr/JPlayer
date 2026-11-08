package com.tmplayer.ui.browse

import com.tmplayer.ui.components.EdgeStep
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.keepTopInSight
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.scrollBy
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.App
import com.tmplayer.data.BuiltInChannels
import com.tmplayer.data.HiddenStreams
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.StreamLink
import com.tmplayer.data.StreamLinks
import com.tmplayer.data.Td
import com.tmplayer.data.TelegramLinkActions
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.holdable
import com.tmplayer.ui.components.TmButton
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.components.WithinMargins
import com.tmplayer.ui.components.edgeFade
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalOnlineArt
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.drawBehind

/**
 * Home: a row of what the viewer was part way through, a row of what they saved for later, the new episodes of the shows they
 * are watching, a row per starred chat, and the newest videos across the starred chats (the rows
 * are built by [com.tmplayer.data.HomeRows]).
 *
 * Continue watching is wide cards with the name written on the art and the time left in a chip.
 * A starred chat's row and Recently added keep the chat's own order in one strip of 2:3 posters.
 *
 * One vertical lazy list of horizontal lazy rows, so only the rows on screen exist and only the
 * tiles on screen in each of those. Every row and tile is keyed, and every tile is a fixed width,
 * which is what lets a 1 GB television scroll this without measuring anything twice.
 *
 * On a television the D-pad moves along a row and between rows, each row remembering the tile it
 * was left on. Home opens on the first tile. A row that leaves videos out offers "See all" on its
 * heading.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomePane(
    rows: List<HomeRow>,
    watch: SeriesWatch,
    /** Continue watching videos as Telegram describes them, for their pictures. */
    art: Map<String, MediaItem>,
    chatTitle: (Long) -> String,
    start: Dp,
    end: Dp,
    bottom: Dp,
    onRowShown: (HomeRow) -> Unit,
    onArtWanted: (ResumeRecord) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onPlay: (MediaItem, String) -> Unit,
    /** A long press, a held OK or the info key on a video's tile: its detail panel. */
    onHoldMedia: (MediaItem, String) -> Unit,
    onSeeContinue: () -> Unit,
    onOpenChat: (Long) -> Unit,
    /**
     * True when no chat is starred, so Home has no chat rows to show and says how to get them.
     * Not needed when [rows] is empty: the empty state already says the same.
     */
    noFavourites: Boolean = false,
    /** False when Settings turned the carousel off: Home opens on its rows, Recently added among them. */
    carouselOn: Boolean = true,
    /** Every row of Home off in Settings ([com.tmplayer.data.HomeLayout.allOff]): a card says so, with the way back. */
    allOff: Boolean = false,
    /** Settings, on its Home page: from the all-off card and from Recently added's "no chosen chats" line. */
    onOpenHomeSettings: () -> Unit = {},
    /** False while the rows have not been built from an answer yet ([HomeViewModel.rowsSettled]). */
    rowsReady: Boolean = true,
    /** What the first-run state says and where its button goes: Saved Messages, or a folder. */
    /** Opens the first-run state's target. */
    onOpenSection: (BrowseSection) -> Unit = {},
    /** A Watch later tile: its detail or the player, as a Continue watching tile does. */
    onOpenWatchLater: (WatchLaterRecord) -> Unit = {},
    onLaterArtWanted: (WatchLaterRecord) -> Unit = {},
    onSeeWatchLater: () -> Unit = {},
    /** Download season and Download unwatched on a show's page; null leaves them out. */
    onDownloadSeason: ((SeasonDownloadRequest) -> Unit)? = null,
    /**
     * Starts a video at once, whatever "Open a video's page first" says: a show's page plays from
     * its Play pill and its episodes. [onPlay] is a tile's, which may open the page first, and a
     * page opened from the show's own Play sat behind the show and sent the viewer round again.
     */
    onPlayNow: (MediaItem, String) -> Unit = onPlay,
    /** A video's page, whatever "Open a video's page first" says: the carousel's Details on a television. */
    onDetails: (MediaItem, String) -> Unit = onHoldMedia,
    /** A live or file stream from the built-in channel's posts (v1.0.10): plays in the player. */
    onPlayStream: (StreamLink) -> Unit = {},
    /** Two live streams side by side (item 11c, v1.0.15); null leaves the offer out. */
    onPlayStreamPair: ((StreamLink, StreamLink) -> Unit)? = null,
    /** Home pressed again while on Home: Home reads itself fresh (owner, v1.0.15). */
    onRefreshHome: () -> Unit = {},
    /**
     * The newer version the update feed reports, or null for none: Home's own update
     * card stands for it (item 13) and the header line carries its notice (item 15).
     */
    updateVersion: String? = null,
    /** The newer version's What's New lines from the feed, for Home's update card. */
    updatePoints: List<String> = emptyList(),
    /** Opens the update popup: the card's and the header notice's Update action. */
    onUpdate: () -> Unit = {},
    /** The Downloads page, from the header line's action (item 15). */
    onOpenDownloads: () -> Unit = {},
    /**
     * The television's page heading. With the carousel up it stands on the carousel's picture
     * (true: the words in the colours for art) and scrolls away with it; otherwise it sits over
     * the rows. Null on a phone, whose heading is the app bar.
     */
    tvHeader: (@Composable (onArt: Boolean) -> Unit)? = null,
    /** Asked before Home takes the remote for its first row: not while the TV top bar holds it. */
    mayTakeFocus: () -> Boolean = { true },
    /** On the TV carousel's Play, for Down from the top bar. */
    tvPlayFocus: FocusRequester? = null,
    /**
     * Recently added reads on through Home's chats as it is scrolled to its end
     * ([com.tmplayer.ui.browse.HomeViewModel.seeAll]); null keeps each row to what Home holds.
     */
    feedOf: (HomeRow) -> com.tmplayer.ui.browse.RowFeed? = { null },
) {
    val s = LocalStrings.current
    val touch = isTouch()
    // On a phone the page runs up under the status bar (see LocalUnderStatusBar).
    val under = if (touch) LocalUnderStatusBar.current else 0.dp
    if (rows.isEmpty() && allOff) {
        LaunchedEffect(Unit) { HomeFirstPaint.markReady() }
        val focus = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().padding(top = under)) {
            tvHeader?.invoke(false)
            if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (touch) 16.dp else 72.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                com.tmplayer.ui.browse.HomeOffCard(onSettings = onOpenHomeSettings, focus = focus)
            }
        }
        // The remote lands on the one thing there is to press, unless the top bar holds it.
        if (!touch) LaunchedEffect(Unit) { if (mayTakeFocus()) runCatching { focus.requestFocus() } }
        return
    }
    if (rows.isEmpty()) {
        LaunchedEffect(Unit) { HomeFirstPaint.markReady() }
        // Rows still being built: the page's heading over a blank page, not the first run's card.
        // A list of the chats' videos that never comes back gives way to the card after a while,
        // so a first run without a connection still says what to do.
        var waitedOut by remember { mutableStateOf(false) }
        if (!rowsReady) LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(ROWS_WAIT_MS)
            waitedOut = true
        }
        if (!rowsReady && !waitedOut) {
            Column(Modifier.fillMaxSize().padding(top = under)) {
                tvHeader?.invoke(false)
                if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            }
            return
        }
        // Nothing played and nothing starred: a first run, so say how to get a first video in.
        Column(Modifier.fillMaxSize().padding(top = under)) {
            tvHeader?.invoke(false)
            if (touch) PhoneHomeBrand(Modifier.padding(start = start, top = BRAND_TOP), onArt = false)
            Box(Modifier.fillMaxSize()) { FirstVideoEmpty(onOpenSection) }
        }
        return
    }
    // The phone Home's chip (owner, v1.0.15, items 16 and 29): All, or the one shape
    // Home itself takes until the same chip is tapped again. Kept by name, so a turn
    // of the phone or a rebuild keeps the shape the viewer picked.
    var homeChipName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(com.tmplayer.data.HomeChip.All.name) }
    val homeChip = com.tmplayer.data.HomeChip.entries.firstOrNull { it.name == homeChipName } ?: com.tmplayer.data.HomeChip.All
    // The rows in the chip's shape: the banner, the rows and the surprise pick all
    // read these, so a Movies home is movies from its banner to its last row. The
    // full rows stay for what a See all page and a show's page read.
    val chipRows = remember(rows, homeChip) { com.tmplayer.data.HomeChipFilter.apply(rows, homeChip) }
    // Recently added is the featured carousel at the top as well as a row, on a phone and a
    // television alike: its frame is held from the first frame whenever the row is, so nothing
    // below it moves when it fills, and given up (the rows moving up) once its fill finds no
    // complete page, a moment after the row is in at most ([HomeFeatured.FILL_WAIT_MS]).
    val recent = chipRows.firstNotNullOfOrNull { it as? HomeRow.Recent }
    // The banner's way (owner, 2026-11-06): auto is the newest titles as always; pinned
    // leads with the viewer's own titles, found across Home's rows by their copies keys,
    // and the newest titles fill the rest; off hides the banner. The chats it draws from
    // are the same chats Home reads.
    val bannerContext = LocalContext.current
    val bannerSettings = remember(bannerContext) { SettingsStore(bannerContext) }
    val bannerMode by remember(bannerSettings) { bannerSettings.homeBannerMode }.collectAsState(initial = "auto")
    // The trailer autoplay switch, for the TV banner's preview (item 11b, v1.0.15).
    val trailerSwitch by remember(bannerSettings) { bannerSettings.heroTrailerAutoplay }.collectAsState(initial = true)
    val bannerPins by remember(bannerSettings) { bannerSettings.homeBannerPins }.collectAsState(initial = emptyList())
    // The catalog's hidden groups and their order, for the Categories picker on Home
    // itself (owner, v1.0.14, item 23): the same store Settings writes, read through the
    // one SettingsStore this page already keeps, so a change in either place is the
    // change in both.
    val homeLayout by remember(bannerSettings) { bannerSettings.homeLayout }.collectAsState(initial = com.tmplayer.data.HomeLayout())
    // Home's own update card (owner, v1.0.15, item 13): stands while the feed's newer
    // version stands, until the viewer updates or puts it away for the day (its
    // memory is [com.tmplayer.data.HomeUpdateNotice], kept in the same store).
    val updateDismissed by remember(bannerSettings) { bannerSettings.homeUpdateCardDismissed }.collectAsState(initial = "")
    val updateCardNow = remember(updateVersion) { System.currentTimeMillis() }
    val showUpdateCard = updateVersion != null &&
        com.tmplayer.data.HomeUpdateNotice.visible(updateDismissed, updateVersion, updateCardNow)
    val dismissUpdateCard: () -> Unit = {
        val version = updateVersion
        if (version != null) {
            App.backgroundScope.launch {
                runCatching {
                    bannerSettings.setHomeUpdateCardDismissed(
                        com.tmplayer.data.HomeUpdateNotice.stamp(version, System.currentTimeMillis()),
                    )
                }
            }
        }
    }
    val bannerRecent = if (bannerMode == "off") null else recent
    // The viewer's pinned titles, found across Home's rows by their copies keys: in pinned
    // mode they lead the banner, and the newest titles fill its remaining places toward the
    // full set in the mix (owner, v1.0.14). No pins found, and the banner is the auto one.
    val bannerPinEntries = if (bannerMode == "pinned" && bannerPins.isNotEmpty()) {
        val pins = bannerPins.toSet()
        chipRows.flatMap { row ->
            when (row) {
                is HomeRow.Catalog -> row.all
                is HomeRow.Chat -> row.entries
                is HomeRow.Recent -> row.entries
                else -> emptyList()
            }
        }.filter { entry ->
            val lead = when (entry) {
                is ShelfEntry.File -> entry.item
                is ShelfEntry.Show -> entry.series.cover
            }
            com.tmplayer.data.VideoCopies.keyOf(lead) in pins
        }
    } else {
        emptyList()
    }
    // Off in Settings, nothing is picked or turned over for it either.
    val featured = rememberFeatured(
        bannerRecent.takeIf { carouselOn },
        pinEntries = bannerPinEntries,
        pinKeys = if (bannerMode == "pinned") bannerPins.toSet() else emptySet(),
    )
    val carousel = carouselOn && recent != null && featured?.isEmpty() != true
    // With no carousel the rows are the whole first screen; with one, its first picture tells
    // HomeFirstPaint (through the carousel), so the loader lifts over a finished Home.
    // Not at once: the starred chats' row can arrive a moment after the rest, bringing the carousel
    // (and its picture) with it, and the loader lifting before that let Home jump down.
    LaunchedEffect(carousel) {
        if (carousel) return@LaunchedEffect
        kotlinx.coroutines.delay(if (touch) NO_CAROUSEL_SETTLE_MS else NO_CAROUSEL_SETTLE_TV_MS)
        HomeFirstPaint.markReady()
    }
    // Recently added is a row of its own as well, under Continue watching (user, 2026-10-23),
    // unless Settings turned the row off and kept the carousel it feeds. Its "no chosen chats"
    // line shows either way, so the carousel's empty place is explained.
    val listed = remember(chipRows) { chipRows.filter { it !is HomeRow.Recent || it.asRow || it.noneChosen } }
    // The phone's chips (owner, v1.0.15, items 16 and 29): Shows, Movies, New & Hot
    // and Categories, under the header line and above the banner. They no longer
    // jump to a shelf: Shows and Movies turn Home itself into that shape (see
    // [chipRows]), New & Hot into the new arrivals, and Categories keeps opening
    // the categories picker on Home itself (item 23, below). Which chips stand is
    // read from the full rows, not the cut ones, so the way back and the way across
    // (Movies straight to Shows) stand in every shape. A chip whose shape the chats
    // cannot fill is not offered. Worked out here rather than in the list, because
    // the banner's place in the list, which the status bar colours and the
    // carousel's own turning read, depends on whether they stand there. A
    // television never has them: its list is exactly as it was.
    val fullCatalog = rows.filterIsInstance<HomeRow.Catalog>()
    val showsChipShown = fullCatalog.any { it.kind == "series" }
    val moviesChipShown = fullCatalog.any { it.kind == "movies" }
    val hotChipShown = rows.any { it is HomeRow.Recent && !it.noneChosen } ||
        fullCatalog.any { it.kind == "weekly" || it.kind == "trending" }
    val hasShelves = fullCatalog.isNotEmpty()
    val chipsShown = touch && (showsChipShown || moviesChipShown || hotChipShown || hasShelves)
    // The banner's index in the phone's list: the header line stands before it, and
    // the chips too when they stand (on a phone with an update card, the card stands
    // between them and the banner follows it: see the list below).
    val carouselIndex = if (!touch) 0 else 1 + (if (chipsShown) 1 else 0) + (if (showUpdateCard) 1 else 0)
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Kept across a turn of the phone: the show is looked up again in the rows once they are back.
    val opened = rememberOpenSeries { key, chatId -> HomeLaunch.find(key, chatId, rows) }
    // Recently added's See all page, by the row's key, so a turn of the phone keeps it open.
    var seeAllKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // A catalog shelf's See all page, by the shelf's key (owner, 2026-11-06).
    var catalogKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // The Categories index sheet (phone, v1.0.13): opened from the category row's last pill.
    var categoriesOpen by remember { mutableStateOf(false) }
    val catalogRow = catalogKey?.let { key -> rows.firstOrNull { it.key == key } as? HomeRow.Catalog }
    // Open link (v1.0.11): an address pasted on Home, played through the same stream mode
    // the built-in channel's posts use.
    var openLink by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }
    var linkInvalid by remember { mutableStateOf(false) }
    // A Drive file held back for its download quota (v1.0.13): the Open link box stays up
    // and says so, where the invalid note would stand.
    var linkQuota by remember { mutableStateOf(false) }
    // The links opened before, offered back in the box (owner, 2026-11-06).
    val linkContext = LocalContext.current
    val linkSettings = remember { SettingsStore(linkContext) }
    val linkHistory by remember(linkSettings) { linkSettings.linkHistory }.collectAsState(initial = emptyList())
    val seeAllRow = seeAllKey?.let { key -> rows.firstOrNull { it.key == key } as? HomeRow.Recent }
    // The row whose tiles the remote goes back to once its page closes.
    var returnTo by remember { mutableStateOf<String?>(null) }
    val listState = com.tmplayer.ui.components.rememberAheadListState(column = true)
    // Home pressed again on the bar: back to the top, the featured carousel whole.
    ScrollToTopOnReselect(TouchDestination.Home) { animate -> listState.toTop(animate) }
    // And Home read fresh on the same press (owner, v1.0.15): the shelves and banner
    // reload, and the refresh reads the Live row's posts again with them.
    RefreshOnReselect(TouchDestination.Home) { onRefreshHome() }
    // Back from Home's rows to the TV top bar: the same, as Home looks when it opens.
    if (!touch) {
        // The bar takes the page's ground behind it once the rows run up under it.
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
                .collect { TvBarSignals.homeScrolled.value = it }
        }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { TvBarSignals.homeScrolled.value = false } }
        val still = com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
        LaunchedEffect(listState, still) { TvBarSignals.homeToTop.collect { listState.toTop(!still) } }
    }
    // A show's card on the TV home screen asked for its page: opened once the show is in the rows.
    val showAsk by HomeLaunch.show.collectAsState()
    // Not before this window has focus: the launcher is still on top while the app comes up, and a
    // page opened then never got the remote's focus on its Play button.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Where the remote stands on Home, for putting it back after the player (see [HomeFocusMemory]).
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    val shellState by shellLifecycle.currentStateFlow.collectAsState()
    val windowFocusedNow by androidx.compose.runtime.rememberUpdatedState(windowFocused)
    // The Continue watching card a hold menu was opened on, and where it stood in the row: when
    // the menu takes it out of History the remote moves to the card that took its place (or, the
    // row gone, to the top of Home), rather than being left on nothing. Forgotten once the shell
    // is covered (the player), so a card finished there does not move the remote on the way back.
    var heldContinue by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val continueRefocus = remember { FocusRequester() }
    val continueRecords = (listed.firstOrNull { it is HomeRow.Continue } as? HomeRow.Continue)?.records.orEmpty()
    val heldContinueGone = heldContinue?.let { (key, _) ->
        continueRecords.none { SettingsStore.progressKey(it.chatId, it.messageId) == key }
    } == true
    val continueStandIn = if (heldContinueGone) continueRecords.getOrNull(minOf(heldContinue?.second ?: 0, continueRecords.lastIndex)) else null
    LaunchedEffect(shellState.isAtLeast(Lifecycle.State.RESUMED)) {
        if (!shellState.isAtLeast(Lifecycle.State.RESUMED)) heldContinue = null
    }
    LaunchedEffect(heldContinueGone, continueStandIn?.let { SettingsStore.progressKey(it.chatId, it.messageId) }) {
        if (!heldContinueGone) return@LaunchedEffect
        if (!touch) {
            withFrameNanos { }
            if (continueStandIn != null) runCatching { continueRefocus.requestFocus() } else runCatching { first.requestFocus() }
        }
        heldContinue = null
    }
    // The row (by key) or the carousel the remote is in right now, null when it is elsewhere.
    val focusedSpot = remember { arrayOfNulls<String>(1) }
    // Whether the remote has been in Home in this composition, so the first "no focus" report a
    // freshly built Home makes is not taken for the remote leaving it.
    val homeHad = remember { booleanArrayOf(false) }
    // Set once the remote has been put back after the player, which the first row's own focus
    // grab below then leaves alone.
    val restored = remember { booleanArrayOf(false) }
    // Each row's tiles, by row key, for putting the remote back into the row it was in.
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    if (!touch) {
        androidx.compose.runtime.DisposableEffect(shellLifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                // The player (another activity) covering the shell, with the remote on Home.
                if (event == Lifecycle.Event.ON_STOP && HomeFocusMemory.spot != null) HomeFocusMemory.away = true
            }
            shellLifecycle.addObserver(observer)
            onDispose {
                shellLifecycle.removeObserver(observer)
                // Home left for another page while the app is in use: nothing to put back. Gone
                // because the activity is being destroyed behind the player, it is kept for the
                // Home built when the viewer comes back.
                if (shellLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) HomeFocusMemory.forget()
            }
        }
    }
    LaunchedEffect(showAsk, rows, windowFocused) {
        val ask = showAsk ?: return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        val series = HomeLaunch.find(ask, rows) ?: return@LaunchedEffect
        opened.open(series)
        HomeLaunch.show.compareAndSet(ask, null)
    }

    // The carousel arrives after the rows under it (Recently added is read last), and a list keeps
    // its first row on screen when something is put above it: Home would open scrolled past the
    // carousel. Until the viewer has scrolled, the carousel's arrival takes Home back to its top.
    var scrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        scrolled = true
    }
    LaunchedEffect(carousel) {
        // On a television too: a carousel arriving after the rows otherwise sits above the screen,
        // out of reach of Up.
        if (carousel && !scrolled) listState.scrollToItem(0)
        // The carousel gone (no complete page once its fill decided) with the remote on its
        // stand-in: the remote moves to the first row, now at the top, rather than onto nothing.
        if (!carousel && !touch && focusedSpot[0] == FEATURED_KEY) {
            focusedSpot[0] = null
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    // Asked for as the row would be when it first came on screen, and again after a refresh.
    // The carousel is built from the starred chats' newest videos, so while it waits they are asked
    // for straight away, not only once their rows scroll into view (below the fold on a TV they
    // never did, and the carousel waited for good).
    if (recent != null) LaunchedEffect(recent.key, recent.loaded) {
        if (recent.loaded) return@LaunchedEffect
        onRowShown(recent)
        rows.filterIsInstance<HomeRow.Chat>().filterNot { it.loaded }.take(com.tmplayer.ui.browse.HomeViewModel.EAGER_CHATS).forEach(onRowShown)
    }
    val density = LocalDensity.current

    // "Surprise me", on a phone only: one press plays an unwatched film or the next unwatched
    // episode of a show, picked from the entries Home already holds. The television never
    // composes the pill, so its first tile and focus order stay exactly as they are. The pick
    // is worked out at the press, not while composing: the rows' entries are all it needs.
    val surpriseEntries = remember(chipRows) {
        chipRows.flatMap { row ->
            when (row) {
                is HomeRow.Chat -> row.entries
                is HomeRow.Recent -> row.entries
                is HomeRow.Catalog -> row.entries
                else -> emptyList()
            }
        }
    }
    val surpriseAvailable = touch && surpriseEntries.any { it is ShelfEntry.File || it is ShelfEntry.Show }
    val onSurprise: () -> Unit = {
        com.tmplayer.data.SurprisePick.pick(surpriseEntries, watch.finished, { watch.point(it)?.fraction ?: 0f })
            ?.let { item -> onPlayNow(item, chatTitle(item.chatId)) }
    }

    // On a phone Home shares a pager with Chats and stays composed beside it: only while most of
    // it is on screen does the carousel move on or the status bar take its colours from it.
    var shown by remember { mutableStateOf(true) }
    BoxWithConstraints(
        Modifier.fillMaxSize().then(
            if (touch) {
                Modifier.onGloballyPositioned { coordinates ->
                    val width = coordinates.size.width
                    shown = width > 0 && coordinates.boundsInWindow().width > width / 2f
                }
            } else {
                Modifier
            },
        ),
    ) {
        // Fixed from the window alone, so it is the same before and after the pages arrive.
        val carouselHeight = if (touch) featuredHeight(maxWidth, maxHeight - under) + under else TV_FEATURED_HEIGHT
        val carouselPx = with(density) { carouselHeight.roundToPx() }
        val underPx = with(density) { under.roundToPx() }
        // While the carousel's picture is under the status bar the bar's icons go light over it;
        // once it has scrolled away the rows pass under a strip of the page's own ground.
        val pictureShown = carousel && !featured.isNullOrEmpty()
        val overArt by remember(pictureShown, carouselPx, underPx, carouselIndex) {
            derivedStateOf {
                pictureShown && listState.firstVisibleItemIndex == carouselIndex &&
                    listState.firstVisibleItemScrollOffset < carouselPx - underPx
            }
        }
        if (touch) StatusIconsOverArt(overArt && shown)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateFlow.collectAsState()
        // The banner is on screen while it or anything above it (on a phone, the chips)
        // is the first thing standing: at rest the chips stand first with the banner
        // whole under them, and the banner still turns over.
        val carouselOnScreen by remember(carouselIndex) { derivedStateOf { listState.firstVisibleItemIndex <= carouselIndex } }
        // A sheet or a dialog takes the window's focus, which is what stops the pages moving under it.
        val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && windowFocused && carouselOnScreen && shown && opened.series == null
        // Tiles here may wear the show's or the film's picture (see LocalOnlineArt); a chat's grid keeps frames.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val rowStep = with(density) { TV_DOWN_STEP.toPx() }
        val headerOnArt = !touch && carousel && tvHeader != null
        // Under the TV top bar, Home's own list keeps still while the remote stays in its upper part
        // (the carousel's Play, the first row): the television's default moved every focused item
        // to a fixed height, which scrolled Home down and back each time Play took the remote.
        val barPx = with(density) { TV_BAR_HEIGHT.toPx() }
        // A row the remote moves into stands just under the bar, its heading in sight, wherever it
        // was reached from, as the web app's and Google TV's rows do. Brought to three tenths of the
        // way down instead, a row reached by Up sat with its heading under the bar and one reached
        // by Down with it in sight; the first row was held at the list's top with its tiles cut off
        // at the screen's foot, and a second Down moved the page that the next Left or Right took
        // back (user, 2026-10-28). Found by where the request lands: inside a row's own place.
        val rowKeys by androidx.compose.runtime.rememberUpdatedState(remember(listed) { listed.mapTo(HashSet()) { it.key } })
        // Without the carousel the bar stands above the list rather than over it.
        val rowLine by androidx.compose.runtime.rememberUpdatedState(if (headerOnArt) barPx else 0f)
        val calm = remember(barPx, listState) {
            calmVertical(barPx) { offset, size, _ ->
                val info = listState.layoutInfo
                for (row in info.visibleItemsInfo) {
                    // The item's offset counts from inside the list's top padding; the request's from the list's top.
                    val top = (row.offset - info.viewportStartOffset).toFloat()
                    if (offset < top - 1f || offset + size > top + row.size + 1f) continue
                    if (row.key !in rowKeys) return@calmVertical null
                    return@calmVertical top - rowLine
                }
                null
            }
        }
        val platformSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        // The states every tile reads, collected once for Home rather than per tile ([LocalTileShared]).
        val tileShared = com.tmplayer.ui.components.rememberTileShared()
        CompositionLocalProvider(
            LocalOnlineArt provides true,
            com.tmplayer.ui.components.LocalTileShared provides tileShared,
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if (!touch) calm else platformSpec,
        ) {
          // While a See all page stands over Home, the remote cannot move into the rows under it.
          val pageOpen by androidx.compose.runtime.rememberUpdatedState(seeAllRow != null)
          // The last key pressed anywhere on Home, which stops the TV carousel turning once nobody is there.
          val homeIdle = remember { HomeIdle() }
          Column(
              Modifier
                  .fillMaxSize()
                  .then(
                      if (touch) {
                          Modifier
                      } else {
                          Modifier.onPreviewKeyEvent {
                              homeIdle.touched()
                              false
                          }.onFocusChanged { state ->
                              if (state.hasFocus) {
                                  homeHad[0] = true
                              } else if (homeHad[0]) {
                                  homeHad[0] = false
                                  focusedSpot[0] = null
                                  // Moved off Home by the viewer (the top bar, a page over it):
                                  // nothing to put back. Lost because the player covered the
                                  // window, the place is kept.
                                  if (shellLifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && windowFocusedNow) HomeFocusMemory.forget()
                              }
                          }
                      },
                  )
                  .focusProperties { onEnter = { if (pageOpen) cancelFocusChange() } }
                  .focusGroup(),
          ) {
            if (!touch && !headerOnArt) tvHeader?.invoke(false)
            LazyColumn(
                state = listState,
                // The heading on the carousel's picture comes back whole with the carousel's buttons.
                modifier = Modifier.fillMaxSize().keepTopInSight(listState, enabled = !touch).then(
                    if (touch) {
                        Modifier
                    } else {
                        // Down from a row of tiles finds nothing when the next row is wholly below
                        // the screen (the row's own lazy layout answers the search first), which
                        // the carousel at the top made the usual case on Home: the list then moves
                        // down by a row's height and the remote follows into the row that appeared.
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if ((event.key == Key.DirectionDown || event.key == Key.DirectionUp) && staleRepeat(event)) return@onPreviewKeyEvent true
                            // Up the same way: the row above may be wholly over the screen now
                            // that every row stands at the bar.
                            if (event.key == Key.DirectionUp) {
                                if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)) return@onPreviewKeyEvent true
                                if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                                scope.launch {
                                    listState.scrollBy(-rowStep)
                                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                                }
                                return@onPreviewKeyEvent true
                            }
                            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            if (focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) return@onPreviewKeyEvent true
                            if (!listState.canScrollForward) return@onPreviewKeyEvent false
                            // From the last row there is nothing to move into: the list shows what
                            // is left under it (the favourites hint) but keeps the row whole, so the
                            // next Left or Right does not have to bring it back.
                            val lastKey = listed.lastOrNull()?.key
                            if (lastKey != null && focusedSpot[0] == lastKey) {
                                val info = listState.layoutInfo
                                val row = info.visibleItemsInfo.firstOrNull { it.key == lastKey } ?: return@onPreviewKeyEvent true
                                val room = (row.offset - info.viewportStartOffset - barPx).coerceIn(0f, rowStep)
                                if (room > 0f) scope.launch { listState.scrollBy(room) }
                                return@onPreviewKeyEvent true
                            }
                            scope.launch {
                                // In one step, as the rows move: a slide here drew a dozen slow frames.
                                listState.scrollBy(rowStep)
                                focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
                            }
                            true
                        }
                    },
                ),
                contentPadding = PaddingValues(
                    top = if (headerOnArt) 0.dp else if (!touch) Tv.FocusClearance else if (carousel) 0.dp else under + 4.dp,
                    bottom = bottom,
                ),
                verticalArrangement = Arrangement.spacedBy(if (touch) ROW_GAP_TOUCH else ROW_GAP_TV),
            ) {
                if (touch) {
                    // The header line (owner, v1.0.15, item 15, the Netflix shape):
                    // the J Player mark and name, the Home title, and the useful
                    // actions on one line, Downloads and the update notice among
                    // them, with the chips row under it (below). The banner stands
                    // under the header now, its own small mark gone from its corner
                    // (see the carousel item): the header is the mark's one home.
                    // With the banner the list's top padding is nothing, so the
                    // header keeps the status bar's room itself; without one the
                    // padding already gives it.
                    item(key = HEADER_KEY, contentType = HEADER_KEY) {
                        HomeHeaderLine(
                            start = start,
                            end = end,
                            top = if (carousel) under + 8.dp else 2.dp,
                            updateVersion = updateVersion,
                            onUpdate = onUpdate,
                            onOpenDownloads = onOpenDownloads,
                        )
                    }
                    if (showUpdateCard) {
                        // The update on Home itself (item 13): the card stands in
                        // the list under the header, and in the pinned bar over the
                        // rows once Home has scrolled (below), so it stays until
                        // the viewer updates or puts it away for the day.
                        item(key = UPDATE_KEY, contentType = UPDATE_KEY) {
                            HomeUpdateCard(
                                version = updateVersion ?: "",
                                points = updatePoints,
                                start = start,
                                end = end,
                                tv = false,
                                onUpdate = onUpdate,
                                onDismiss = dismissUpdateCard,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
                if (chipsShown) {
                    // The chips under the header line (items 16 and 29): rounded
                    // tags in a row that scrolls sideways, so Categories stays
                    // reachable when the row runs past the screen's edge. A tap on
                    // Shows or Movies turns Home itself into that shape, a tap on
                    // the same chip again brings the full Home back, and New & Hot
                    // does the same for the new arrivals.
                    item(key = CHIPS_KEY, contentType = CHIPS_KEY) {
                        HomeChipsRow(
                            start = start,
                            end = end,
                            top = 4.dp,
                            chip = homeChip,
                            shows = showsChipShown,
                            movies = moviesChipShown,
                            hot = hotChipShown,
                            categories = hasShelves,
                            onChip = { picked ->
                                homeChipName = if (picked == homeChip) com.tmplayer.data.HomeChip.All.name else picked.name
                            },
                            onCategories = { categoriesOpen = true },
                        )
                    }
                }
                if (carousel) {
                    if (!touch) item(key = FEATURED_KEY, contentType = FEATURED_KEY) {
                        TvFeaturedCarousel(
                            picks = featured,
                            height = carouselHeight,
                            start = start,
                            end = end,
                            watch = watch,
                            chatTitle = chatTitle,
                            active = active,
                            onPlay = onPlayNow,
                            onDetails = { pick ->
                                val series = pick.series
                                if (series != null) opened.open(series) else onDetails(pick.item, chatTitle(pick.item.chatId))
                            },
                            header = tvHeader?.let { heading -> { heading(true) } },
                            playFocus = tvPlayFocus,
                            idle = homeIdle,
                            trailerSwitch = trailerSwitch,
                            modifier = Modifier.onFocusChanged { state ->
                                if (state.hasFocus) {
                                    // Up from the first row, which moved Home up to stand whole,
                                    // brings the carousel back whole, picture and title.
                                    if (focusedSpot[0] != FEATURED_KEY && (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0)) {
                                        scope.launch { listState.scrollToItem(0) }
                                    }
                                    focusedSpot[0] = FEATURED_KEY
                                    HomeFocusMemory.spot = FEATURED_KEY
                                } else if (focusedSpot[0] == FEATURED_KEY) {
                                    focusedSpot[0] = null
                                }
                            },
                        )
                    } else item(key = FEATURED_KEY, contentType = FEATURED_KEY) {
                        // No mark in the picture's corner any more (owner, v1.0.15,
                        // item 15): the header line above the banner carries the
                        // J Player mark and name, on the page's own ground.
                        FeaturedCarousel(
                            picks = featured,
                            height = carouselHeight,
                            under = under,
                            start = start,
                            end = end,
                            watch = watch,
                            chatTitle = chatTitle,
                            active = active,
                            onPlay = onPlayNow,
                            onDetails = { pick ->
                                // Straight to the title's page (owner, v1.0.15): a banner tap
                                // and its info button skip the hold menu that a card's hold
                                // opens, as the television's banner already did by [onDetails].
                                val series = pick.series
                                if (series != null) opened.open(series) else onDetails(pick.item, chatTitle(pick.item.chatId))
                            },
                        )
                    }
                }
                // No carousel on a phone: no brand item either, the header line
                // (HEADER_KEY, above) heads the rows in its place.
                if (!touch && showUpdateCard) {
                    // The update on the TV Home too (item 13): its own item under
                    // the carousel (or at the list's head where there is none), in
                    // the D-pad's order between the carousel's Play and the rows,
                    // its Update button taking the remote. The carousel stays the
                    // list's first item, as its focus and scroll rules expect.
                    item(key = UPDATE_KEY, contentType = UPDATE_KEY) {
                        HomeUpdateCard(
                            version = updateVersion ?: "",
                            points = updatePoints,
                            start = start,
                            end = end,
                            tv = true,
                            onUpdate = onUpdate,
                            onDismiss = dismissUpdateCard,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        )
                    }
                }
                // The streams posted in the built-in films channel (v1.0.10), between the
                // carousel and the rows. The channels' cards that stood here are gone
                // (v1.0.11, owner): the Live row is what stays of the built-in sections.
                item(key = BUILT_IN_KEY, contentType = BUILT_IN_KEY) {
                    BuiltInHomeSections(
                        start = start,
                        end = end,
                        onPlayStream = onPlayStream,
                        onPlayStreamPair = onPlayStreamPair,
                    )
                }
                if (touch) {
                    // Surprise me and Share J Player side by side in one row (owner,
                    // 2026-11-06): the share pill stands here whether or not there is a
                    // film to be surprised by, so it is never lost with the surprise.
                    item(key = SURPRISE_KEY, contentType = SURPRISE_KEY) {
                        Row(
                            Modifier.padding(start = start, end = end, top = 2.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (surpriseAvailable) {
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Tone.surfaceHigh)
                                        .clickable(onClick = onSurprise)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(TmIcons.Play, contentDescription = null, tint = Tone.text, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    M3Text(s.homeSurpriseMe, style = M3MaterialTheme.typography.titleSmall, color = Tone.text)
                                }
                            }
                            SharePlayerPill()
                            // Open link joins the same row (owner, 2026-11-06): one row
                            // of actions, not a row of its own underneath.
                            OpenLinkPill { openLink = true }
                        }
                    }
                    // The header line and the chips stand at the top of the list,
                    // above the banner: see the HEADER_KEY and CHIPS_KEY items.
                }
                items(listed, key = { it.key }, contentType = { it::class }) { row ->
                    val loaded = row.isLoaded
                    // Asked for whenever the row comes on screen: the model fetches it the first
                    // time, and again only when a refresh passed it by (HomeViewModel.request).
                    LaunchedEffect(row.key) { onRowShown(row) }
                    val isFirst = row === listed.first()
                    val title = homeRowTitle(row, s)
                    // Only a row that leaves something out offers the rest of it.
                    val seeAll: (() -> Unit)? = when (row) {
                        is HomeRow.Continue -> onSeeContinue
                        is HomeRow.WatchLater -> onSeeWatchLater
                        is HomeRow.Chat -> { { onOpenChat(row.chatId) } }
                        is HomeRow.Recent -> { { seeAllKey = row.key } }
                        is HomeRow.NewEpisodes -> null
                        is HomeRow.Catalog -> { { catalogKey = row.key } }
                    }?.takeIf { row.hasMore }
                    // On a television "See all" is the row's last tile, where Right past the last
                    // video lands, as a television's rows end (user, 2026-10-28); a phone keeps it
                    // on the heading. On the heading it took a press of its own, Up or Right, and
                    // Up stopping there made going up two presses a row.
                    val seeAllEnd = if (touch || seeAll == null) null else SeeAllEnd(seeAll, poster = row is HomeRow.Recent || row is HomeRow.Chat || row is HomeRow.Catalog)
                    // This row's tiles, for the remote to be put back into: back from its See all
                    // page, or from the player.
                    val tilesFocus = remember { FocusRequester() }
                    if (!touch) androidx.compose.runtime.DisposableEffect(row.key) {
                        rowFocus[row.key] = tilesFocus
                        onDispose { if (rowFocus[row.key] === tilesFocus) rowFocus.remove(row.key) }
                    }
                    val rowSpot = if (touch) {
                        Modifier
                    } else {
                        Modifier.onFocusChanged { state ->
                            if (state.hasFocus) {
                                focusedSpot[0] = row.key
                                HomeFocusMemory.spot = row.key
                            } else if (focusedSpot[0] == row.key) {
                                focusedSpot[0] = null
                            }
                        }
                    }
                    val rowTiles = Modifier.focusRequester(tilesFocus)
                    Column(rowSpot) {
                        RowHeading(
                            title,
                            start = start,
                            end = end,
                            seeAll = seeAll.takeIf { touch },
                        )
                        if (!loaded) {
                            PlaceholderRow(start, poster = row is HomeRow.Recent || row is HomeRow.Chat || row is HomeRow.Catalog)
                            return@Column
                        }
                        when (row) {
                            is HomeRow.Continue -> TileRow(start, end, rowTiles, seeAll = seeAllEnd?.copy(width = continueWidth(touch))) {
                                items(row.records, key = { "r-${it.chatId}_${it.messageId}" }) { record ->
                                    val key = SettingsStore.progressKey(record.chatId, record.messageId)
                                    LaunchedEffect(key) { onArtWanted(record) }
                                    val item = art[key] ?: remember(record) { record.toMediaItem() }
                                    val chat = record.chatTitle.ifEmpty { chatTitle(record.chatId) }
                                    val strings = LocalStrings.current
                                    VideoTile(
                                        // The name under the picture, then Telegram's facts; none on the picture.
                                        title = remember(item, strings) { continueCardTitle(item, strings) },
                                        onClick = { onResume(record) },
                                        // The detail panel, as on every other tile here: it already
                                        // offers Resume, Start over and Mark watched for a video with
                                        // a saved place.
                                        onLongClick = {
                                            heldContinue = key to row.records.indexOf(record)
                                            onHoldMedia(item, chat)
                                        },
                                        modifier = Modifier.width(continueWidth(touch))
                                            .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier)
                                            .then(if (record === continueStandIn) Modifier.focusRequester(continueRefocus) else Modifier),
                                        meta = { ink ->
                                            Column {
                                                CaptionLine(telegramFacts(chat, item), ink)
                                                // The Netflix shape under a Continue card
                                                // (owner, v1.0.15, item 29): its info and
                                                // menu buttons under the facts, on a phone.
                                                // Info opens the title's page, the menu
                                                // the card's own menu, as a hold does. A
                                                // television keeps the card as it was:
                                                // the remote's hold opens the same menu.
                                                if (touch) {
                                                    Row(
                                                        Modifier.padding(top = 2.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        androidx.compose.material3.IconButton(
                                                            onClick = { onDetails(item, chat) },
                                                            modifier = Modifier.size(32.dp),
                                                        ) {
                                                            androidx.compose.material3.Icon(
                                                                TmIcons.Info,
                                                                contentDescription = strings.detailOpenDetails,
                                                                tint = Tone.muted,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                        androidx.compose.material3.IconButton(
                                                            onClick = {
                                                                heldContinue = key to row.records.indexOf(record)
                                                                onHoldMedia(item, chat)
                                                            },
                                                            modifier = Modifier.size(32.dp),
                                                        ) {
                                                            androidx.compose.material3.Icon(
                                                                TmIcons.MoreVert,
                                                                contentDescription = strings.commonMoreOptions,
                                                                tint = Tone.muted,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    ) {
                                        ContinueCardArt(record, item, Modifier.fillMaxSize())
                                    }
                                }
                            }
                            is HomeRow.WatchLater -> TileRow(start, end, rowTiles, seeAll = seeAllEnd?.copy(width = wideWidth(touch))) {
                                items(row.records, key = { "l-${it.id}" }) { record ->
                                    LaunchedEffect(record.key) { onLaterArtWanted(record) }
                                    val item = art[record.key] ?: remember(record) { record.toMediaItem() }
                                    val holdShow = LocalSeriesHold.current
                                    val stub = remember(record) { record.stubSeries() }
                                    MediaCard(
                                        item = item,
                                        watched = if (record.isShow) null else watch.point(item),
                                        // A show saved whole (HX20) is named as the show, its episode's picture on it.
                                        title = record.title.takeIf { record.isShow },
                                        onClick = { onOpenWatchLater(record) },
                                        // The detail panel, which offers Remove from Watch later; a show's menu for a show.
                                        onLongClick = if (stub != null && holdShow != null) {
                                            { holdShow(stub) }
                                        } else {
                                            { onHoldMedia(item, record.chatTitle.ifEmpty { chatTitle(record.chatId) }) }
                                        },
                                        dense = touch,
                                        modifier = Modifier.width(wideWidth(touch))
                                            .then(if (isFirst && record === row.records.first()) Modifier.focusRequester(first) else Modifier),
                                    )
                                }
                            }
                            is HomeRow.NewEpisodes -> TileRow(start, end, rowTiles) {
                                items(row.episodes, key = { "n-${it.item.id}" }) { episode ->
                                    val item = episode.item
                                    val chat = chatTitle(item.chatId)
                                    // The show's name, then which episode: the file name would say the
                                    // same less readably. Under it Telegram's facts, as on every other
                                    // row; the New badge on the art says it is new.
                                    MediaCard(
                                        item = item,
                                        watched = null,
                                        onClick = { onPlay(item, chatTitle(item.chatId)) },
                                        onLongClick = { onHoldMedia(item, chatTitle(item.chatId)) },
                                        dense = touch,
                                        title = if (episode.show.isBlank()) episode.code else "${episode.show}  ·  ${episode.code}",
                                        badge = { NewBadge(compact = touch) },
                                        // The Watch Now ribbon across the foot (owner,
                                        // v1.0.15, item 29): these episodes are the ones
                                        // to watch now, as Recently added wears its own
                                        // ribbon the same way.
                                        bottomTag = s.homeWatchNow,
                                        meta = { ink -> CaptionLine(telegramFacts(chat, item), ink) },
                                        modifier = Modifier.width(wideWidth(touch))
                                            .then(if (isFirst && episode === row.episodes.first()) Modifier.focusRequester(first) else Modifier),
                                    )
                                }
                            }
                            is HomeRow.Chat -> ShelfRows(row.entries, watch, touch, isFirst, first, { row.title }, onPlay, onHoldMedia, start, end, rowTiles, seeAllEnd) { opened.open(it) }
                            is HomeRow.Catalog -> ShelfRows(row.entries, watch, touch, isFirst, first, chatTitle, onPlay, onHoldMedia, start, end, rowTiles, seeAllEnd, ranked = row.kind == "top10", badge = if (row.kind == "top10") ({ Top10Badge(phone = touch) }) else null) { opened.open(it) }
                            is HomeRow.Recent -> if (row.noneChosen) {
                                com.tmplayer.ui.browse.HomeNoneChosenCard(
                                    start = start,
                                    end = end,
                                    focus = if (isFirst) first else null,
                                )
                            } else {
                                // Its tiles alone, as every row: the rest is on its See all page,
                                // which reads on through Home's chats. Read on in the row itself,
                                // it asked Telegram for more each time it neared its end, on every
                                // device (user, 2026-10-28: fewer requests, and See all for the rest).
                                // Back from its See all page, the remote lands on this row again.
                                if (!touch) LaunchedEffect(returnTo) {
                                    if (returnTo != row.key) return@LaunchedEffect
                                    withFrameNanos {}
                                    runCatching { tilesFocus.requestFocus() }
                                    returnTo = null
                                }
                                ShelfRows(row.entries, watch, touch, isFirst, first, chatTitle, onPlay, onHoldMedia, start, end, rowTiles, seeAllEnd, bottomTag = s.homeChannelRecent) { opened.open(it) }
                            }
                        }
                    }
                }
                // Where the starred chats' rows would be, a card saying how to get them; otherwise a
                // Home of Continue watching alone gives no clue that it could hold more. Never
                // focusable: the remote has nothing to do here, and the way to star a chat is in
                // the chat list, which the hint names.
                if (noFavourites) {
                    item(key = "favourites-hint", contentType = "favourites-hint") {
                        FavouritesHintCard(s.homeFavouritesHint(if (touch) "phone" else "tv"), start = start, end = end)
                    }
                }
            }
          }
        }
        // The header line and the chips, pinned (owner, v1.0.15, item 30): once the
        // banner has scrolled away, the same header and chips stand over the rows on
        // a frosted ground (the page's own colour, nearly solid), so they are never
        // a long scroll back to the top away, in either direction, and a tap on a
        // chip here cuts Home the same way it does at the top. Scrolling back up to
        // the banner hands over to the list's own header without a gap: the pinned
        // one stands exactly while the banner is out of sight.
        val headerPinned by remember(carouselIndex) {
            derivedStateOf { listState.firstVisibleItemIndex > carouselIndex }
        }
        if (touch && headerPinned) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Tone.background.copy(alpha = 0.94f))
                    .padding(bottom = 6.dp),
            ) {
                HomeHeaderLine(
                    start = start,
                    end = end,
                    top = under + 6.dp,
                    updateVersion = updateVersion,
                    onUpdate = onUpdate,
                    onOpenDownloads = onOpenDownloads,
                )
                if (showUpdateCard) {
                    HomeUpdateCard(
                        version = updateVersion ?: "",
                        points = updatePoints,
                        start = start,
                        end = end,
                        tv = false,
                        onUpdate = onUpdate,
                        onDismiss = dismissUpdateCard,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (chipsShown) {
                    HomeChipsRow(
                        start = start,
                        end = end,
                        top = 4.dp,
                        chip = homeChip,
                        shows = showsChipShown,
                        movies = moviesChipShown,
                        hot = hotChipShown,
                        categories = hasShelves,
                        onChip = { picked ->
                            homeChipName = if (picked == homeChip) com.tmplayer.data.HomeChip.All.name else picked.name
                        },
                        onCategories = { categoriesOpen = true },
                    )
                }
            }
        }
        if (touch && under > 0.dp) {
            val ground by androidx.compose.animation.core.animateFloatAsState(
                if (overArt) 0f else 1f,
                com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
                label = "statusGround",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(under)
                    .graphicsLayer { alpha = ground }
                    .background(Tone.background),
            )
        }
        ScrollToTop(listState)
        // Home's rows are not in an order a bubble could name, so the handle carries no label.
        FastScroller(listState)
    }

    // The Categories picker on Home itself (phone, owner, v1.0.14, item 23): the same
    // sheet Settings, Home, Categories offers, over the same store, so the two stay in
    // sync: every catalog group with its shown or hidden state, a tap turning it over,
    // and the move buttons writing the order whole, exactly as the Settings sheet does.
    // It replaces the index this chip opened before (v1.0.13), which only jumped to a
    // shelf; the Shows, Movies and Anime chips still jump, and a hidden shelf leaves
    // Home on the next build, as it does from Settings.
    if (categoriesOpen) {
        val ordered = (homeLayout.catalogOrder + com.tmplayer.data.HomeLayout.CATALOG_GROUPS).distinct()
        ChoiceSheet(
            title = s.settingsHomeCategories,
            note = s.settingsHomeCategoriesDetail,
            onDismiss = { categoriesOpen = false },
            onClose = { categoriesOpen = false },
        ) {
            ChoiceList {
                ordered.forEachIndexed { index, group ->
                    val shown = group !in homeLayout.hiddenCatalog
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceLine(
                            title = com.tmplayer.ui.settings.homeCategoryLabel(group, s),
                            detail = if (shown) s.settingsHomeCategoryShown else s.settingsHomeCategoryHidden,
                            selected = null,
                            icon = if (shown) TmIcons.Check else null,
                            modifier = Modifier.weight(1f),
                        ) {
                            val next = if (shown) homeLayout.hiddenCatalog + group else homeLayout.hiddenCatalog - group
                            scope.launch { bannerSettings.setHomeHiddenCatalog(next) }
                        }
                        androidx.compose.material3.IconButton(
                            enabled = index > 0,
                            onClick = {
                                val next = ordered.toMutableList()
                                val other = next[index - 1]
                                next[index - 1] = group
                                next[index] = other
                                scope.launch { bannerSettings.setHomeCatalogOrder(next) }
                            },
                        ) {
                            androidx.compose.material3.Icon(TmIcons.ChevronUp, contentDescription = s.settingsMoveUp)
                        }
                        androidx.compose.material3.IconButton(
                            enabled = index < ordered.lastIndex,
                            onClick = {
                                val next = ordered.toMutableList()
                                val other = next[index + 1]
                                next[index + 1] = group
                                next[index] = other
                                scope.launch { bannerSettings.setHomeCatalogOrder(next) }
                            },
                        ) {
                            androidx.compose.material3.Icon(TmIcons.ChevronDown, contentDescription = s.settingsMoveDown)
                        }
                    }
                }
            }
        }
    }

    if (openLink) {
        com.tmplayer.ui.components.TvConfirm(
            title = s.homeOpenLink,
            message = s.homeOpenLinkMessage,
            detail = when {
                linkQuota -> s.playerStreamDriveQuota
                linkInvalid -> s.homeOpenLinkInvalid
                else -> null
            },
            confirmLabel = s.commonPlay,
            destructive = false,
            icon = TmIcons.OpenExternal,
            focusConfirm = true,
            onConfirm = {
                // pastedAny: a link whose kind no ending names still opens (owner,
                // v1.0.14); the player asks the address itself what it is.
                val link = com.tmplayer.data.StreamLinks.pastedAny(linkText)
                val raw = Regex("""https?://\S+""").find(linkText)?.value
                if (link != null) {
                    linkInvalid = false
                    linkQuota = false
                    // Kept for the box's history, as pasted, Drive pages included.
                    if (raw != null) App.backgroundScope.launch { runCatching { linkSettings.addLinkHistory(raw) } }
                    if (link.source == "drive.google.com") {
                        // A large Drive file answers with a confirmation page first; the
                        // playable address is read off it before the player opens. The box
                        // stays up until the answer is known: a file Drive is holding back
                        // for its download quota says so here (v1.0.13) instead of opening
                        // a player that can only fail. A refusal opens the address as it
                        // always did, and the player says what it finds.
                        scope.launch {
                            when (val outcome = com.tmplayer.data.PageLinkResolver.resolveDriveOutcome(link)) {
                                is com.tmplayer.data.DriveOutcome.Playable -> {
                                    openLink = false
                                    onPlayStream(outcome.link)
                                }
                                com.tmplayer.data.DriveOutcome.Quota -> linkQuota = true
                                com.tmplayer.data.DriveOutcome.Refused -> {
                                    openLink = false
                                    onPlayStream(link)
                                }
                            }
                        }
                    } else {
                        openLink = false
                        onPlayStream(link)
                    }
                } else if (raw != null && com.tmplayer.data.StreamLinks.isPageLink(raw)) {
                    // A file host's page (GDFlix, HubCloud): read the page for the file
                    // address inside it, best effort. Nothing found: the dialog stays up
                    // and says so, in place, and nothing is retried.
                    scope.launch {
                        val resolved = com.tmplayer.data.PageLinkResolver.resolvePage(raw)
                        if (resolved == null) {
                            linkInvalid = true
                        } else {
                            openLink = false
                            App.backgroundScope.launch { runCatching { linkSettings.addLinkHistory(raw) } }
                            onPlayStream(resolved)
                        }
                    }
                } else {
                    // Not a playable address: the dialog stays up and says so, in place.
                    linkInvalid = true
                }
            },
            onDismiss = { openLink = false; linkInvalid = false; linkQuota = false },
            extra = {
                Column {
                    com.tmplayer.ui.auth.PaneField(
                        value = linkText,
                        onValueChange = { linkText = it; linkInvalid = false; linkQuota = false },
                        placeholder = s.homeOpenLinkHint,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                    )
                    // A file link can be saved instead of played: to the phone's own
                    // Downloads folder, through the system's download manager.
                    val parsed = com.tmplayer.data.StreamLinks.pasted(linkText)
                    if (parsed != null && !parsed.live) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Tone.surfaceHigh)
                                .clickable {
                                    com.tmplayer.data.LinkDownloader.download(linkContext, parsed)
                                    openLink = false
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TmIcons.Download, contentDescription = null, tint = Tone.text, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            M3Text(s.downloadsSaveToDownloads, style = M3MaterialTheme.typography.labelLarge, color = Tone.text)
                        }
                    }
                    if (linkHistory.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        linkHistory.take(5).forEach { past ->
                            M3Text(
                                past,
                                style = M3MaterialTheme.typography.bodySmall,
                                color = Tone.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { linkText = past; linkInvalid = false; linkQuota = false }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            },
        )
    }

    // The carousel alone on Home (every row off in Settings): the remote stands on its Play.
    val carouselOnly = !touch && carousel && listed.isEmpty() && !featured.isNullOrEmpty()
    LaunchedEffect(carouselOnly) {
        if (!carouselOnly || HomeFocusMemory.away || !mayTakeFocus()) return@LaunchedEffect
        withFrameNanos { }
        tvPlayFocus?.let { play -> runCatching { play.requestFocus() } }
    }

    // Somewhere for the remote to stand the moment Home opens. Keyed on the first row, so a row
    // arriving above (Continue appearing after a first play) moves focus to the new top.
    val firstKey = listed.firstOrNull()?.key
    val firstReady = listed.firstOrNull()?.isLoaded == true
    LaunchedEffect(firstKey, firstReady) {
        // Back from the player the remote goes back where it was (below), and a row arriving
        // above afterwards (Continue watching after a first play) does not move it.
        if (!touch && (HomeFocusMemory.away || (restored[0] && focusedSpot[0] != null))) return@LaunchedEffect
        if (!touch && firstReady && mayTakeFocus()) {
            runCatching { first.requestFocus() }
            // The focused row asks to be brought into view with room round it, which scrolled the
            // carousel above it half away; Home opens at its top, the carousel whole over the row.
            if (carousel) {
                withFrameNanos { }
                listState.scrollToItem(0)
            }
        }
    }

    // Back from the player (or anything else that covered the app) the remote goes back to the
    // carousel's Play or the row it was on. The player is another activity: the shell's view lost
    // the focus behind it, or was built again from scratch where the system had destroyed it, and
    // either way the remote came back to the first thing on screen, the top bar's Home tab.
    // Held for a few frames, so whatever else takes the focus as the shell comes back (the
    // window's own first focus) is put right.
    val shellResumed = shellState.isAtLeast(Lifecycle.State.RESUMED)
    if (!touch) LaunchedEffect(shellResumed, windowFocused, firstReady) {
        if (!shellResumed || !windowFocused || !firstReady || !HomeFocusMemory.away) return@LaunchedEffect
        HomeFocusMemory.away = false
        val spot = HomeFocusMemory.spot ?: return@LaunchedEffect
        restored[0] = true
        // The carousel's lazy index is 0; the rows follow it.
        suspend fun putBack(): Boolean {
            if (spot == FEATURED_KEY) {
                val play = tvPlayFocus ?: return false
                if (!carousel) return false
                // Offset too: Down from Play moves a tall carousel up inside item 0, which the
                // index alone does not show, and Play came back with the carousel shifted.
                if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) listState.scrollToItem(0)
                return runCatching { play.requestFocus() }.isSuccess
            }
            val at = listed.indexOfFirst { it.key == spot }
            if (at < 0) return false
            // The carousel and, on a television with one standing, the update card
            // (item 13) stand in the list before the rows.
            val index = at + (if (carousel) 1 else 0) + (if (showUpdateCard) 1 else 0)
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
                withFrameNanos { }
            }
            val tiles = rowFocus[spot] ?: return false
            return runCatching { tiles.requestFocus() }.isSuccess
        }
        var settled = 0
        for (frame in 0 until RESTORE_FRAMES) {
            withFrameNanos { }
            if (focusedSpot[0] == spot) {
                if (++settled >= RESTORE_SETTLED_FRAMES) break
                continue
            }
            settled = 0
            if (!putBack()) break
        }
        // The place is gone (the row emptied or the carousel left): the first row, as Home opens.
        if (focusedSpot[0] == null) runCatching { first.requestFocus() }
    }

    seeAllRow?.let { row ->
        RowSeeAll(
            row = row,
            title = homeRowTitle(row, LocalStrings.current),
            feed = remember(row.key) { feedOf(row) },
            watch = watch,
            start = start,
            end = end,
            bottom = bottom,
            chatTitle = chatTitle,
            onPlay = onPlay,
            onHold = onHoldMedia,
            onOpenSeries = { opened.open(it) },
            onClose = {
                returnTo = row.key
                seeAllKey = null
            },
        )
    }
    catalogRow?.let { row ->
        CatalogSeeAll(
            row = row,
            title = homeRowTitle(row, LocalStrings.current),
            watch = watch,
            start = start,
            end = end,
            bottom = bottom,
            chatTitle = chatTitle,
            onPlay = onPlay,
            onHold = onHoldMedia,
            onOpenSeries = { opened.open(it) },
            onClose = {
                returnTo = row.key
                catalogKey = null
            },
        )
    }
    opened.series?.let { series ->
        SeriesOpened(
            series = series,
            watch = watch,
            onPlay = { onPlayNow(it, chatTitle(it.chatId)) },
            onDismiss = { opened.open(null) },
            onLongClick = { onHoldMedia(it, chatTitle(it.chatId)) },
            onDownload = onDownloadSeason,
            // Home is outside every chat: the page names the show's and goes to it (HX20).
            chatTitle = chatTitle(series.cover.chatId),
            onOpenChat = { opened.open(null); onOpenChat(series.cover.chatId) },
        )
    }
}

/**
 * A starred chat's row or Recently added: one strip in the row's own order, films, shows and other
 * videos together, all as 2:3 posters ([SeriesShelf.posterKeys]).
 */
@Composable
private fun ShelfRows(
    entries: List<ShelfEntry>,
    watch: SeriesWatch,
    touch: Boolean,
    isFirstRow: Boolean,
    first: FocusRequester,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    start: Dp,
    end: Dp,
    modifier: Modifier,
    /** A television's See all tile at the row's end; null on a phone and for a row that holds everything. */
    seeAll: SeeAllEnd? = null,
    /** The Top 10 row: each tile stands beside its big rank number (owner, 2026-11-06). */
    ranked: Boolean = false,
    /** The Top 10 row's corner badge for every tile (owner, v1.0.13); null for other rows. */
    badge: (@Composable () -> Unit)? = null,
    /** A tag across the foot of every tile's art (Recently added, v1.0.13); null for other rows. */
    bottomTag: String? = null,
    onOpenSeries: (Series) -> Unit,
) {
    val poster = remember(entries) { SeriesShelf.posterKeys(entries).isNotEmpty() }
    TileRow(start, end, modifier, seeAll = seeAll?.copy(width = if (poster) posterWidth(touch) else wideWidth(touch), poster = poster)) {
        entries(entries, poster, watch, touch, if (isFirstRow) entries.firstOrNull() else null, first, chatTitle, onPlay, onHold, onOpenSeries, ranked, badge, bottomTag)
    }
}

private fun LazyListScope.entries(
    entries: List<ShelfEntry>,
    poster: Boolean,
    watch: SeriesWatch,
    touch: Boolean,
    lead: ShelfEntry?,
    first: FocusRequester,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    ranked: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
    bottomTag: String? = null,
) {
    items(entries.size, key = { entries[it].key }, contentType = { if (poster) "poster-${entries[it]::class.simpleName}" else entries[it]::class }) { index ->
        val entry = entries[index]
        val focus = if (entry === lead) Modifier.focusRequester(first) else Modifier
        val width = if (poster) posterWidth(touch) else wideWidth(touch)
        // On a phone the Top 10 tag is pinned to the art's top start corner, over the
        // tile, in the Netflix shape (owner, v1.0.14, item 13): the tile's own badge
        // slot stands at its top end, so a phone does not hand the tag over there and
        // lays it over the tile here instead. A television keeps the slot and the tag
        // it has always worn, exactly as it was.
        val pinned = touch && badge != null
        if (ranked) {
            Row(Modifier.width(width + 52.dp).then(focus), verticalAlignment = Alignment.Bottom) {
                // The giant rank number in the Netflix shape (owner, v1.0.15,
                // item 29): an outline, not a fill, standing beside the poster.
                M3Text(
                    "${index + 1}",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Black,
                        color = Tone.text,
                        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 4f,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    ),
                )
                if (pinned) {
                    Box(Modifier.width(width)) {
                        ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth(), null, bottomTag)
                        Box(Modifier.align(Alignment.TopStart)) { badge?.invoke() }
                    }
                } else {
                    ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.width(width), badge, bottomTag)
                }
            }
        } else if (pinned) {
            Box(Modifier.width(width)) {
                ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth().then(focus), null, bottomTag)
                Box(Modifier.align(Alignment.TopStart)) { badge?.invoke() }
            }
        } else {
            ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.width(width).then(focus), badge, bottomTag)
        }
    }
}

/**
 * Recently added's See all on a phone and a television: the row's tiles as a grid, read on
 * through Home's chats as it nears its end ([com.tmplayer.ui.browse.RowFeed], the same reading
 * the row itself pages with). Back, or the arrow on a phone, goes back to Home.
 */
@Composable
private fun RowSeeAll(
    row: HomeRow.Recent,
    title: String,
    feed: com.tmplayer.ui.browse.RowFeed?,
    watch: SeriesWatch,
    start: Dp,
    end: Dp,
    bottom: Dp,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    com.tmplayer.ui.nav.BackHandler { onClose() }
    val fed = feed?.state?.collectAsState()?.value
    LaunchedEffect(feed, row) { feed?.reshape(row) }
    val entries = fed?.entries ?: row.entries
    val poster = remember(entries) { SeriesShelf.posterKeys(entries).isNotEmpty() }
    val grid = com.tmplayer.ui.components.rememberAheadGridState()
    if (feed != null) {
        com.tmplayer.ui.components.LoadMoreAhead(
            grid,
            enabled = fed == null || fed.wants(scrolling = false) || (fed.wants(scrolling = true) && grid.isScrollInProgress),
        ) { feed.loadMore() }
    }
    // The remote lands on the first tile as the page opens.
    val firstTile = remember { FocusRequester() }
    if (!touch) LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { firstTile.requestFocus() }
    }
    val gap = if (touch) TILE_GAP_TOUCH else TILE_GAP_TV
    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            // Taps and the remote stop here: nothing reaches Home under the page.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .then(if (touch) Modifier.statusBarsPadding() else Modifier.padding(top = TV_BAR_HEIGHT)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (touch) 4.dp else start, end = end).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (touch) {
                androidx.compose.material3.IconButton(onClick = onClose) {
                    androidx.compose.material3.Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text)
                }
                M3Text(
                    title,
                    style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (poster) posterWidth(touch) else wideWidth(touch)),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = start,
                end = end,
                top = if (touch) 4.dp else Tv.FocusClearance,
                bottom = bottom + if (touch) 24.dp else Tv.FocusClearance,
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(if (touch) 16.dp else gap + Tv.FocusClearance),
        ) {
            items(
                entries.size,
                key = { entries[it].key },
                // As the row's own: a show's tile and a video's are built differently, so each reuses its own kind.
                contentType = { if (poster) "poster-${entries[it]::class.simpleName}" else entries[it]::class },
            ) { index ->
                val entry = entries[index]
                val focus = if (index == 0) Modifier.focusRequester(firstTile) else Modifier
                ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth().then(focus), bottomTag = s.homeChannelRecent)
            }
            if (fed != null && (fed.loading || fed.failure != null)) {
                item(key = "more-foot", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, contentType = "more-foot") {
                    com.tmplayer.ui.components.PageFoot(fed.loading, fed.failure, onRetry = { feed.loadMore() }) {
                        // A whole row of placeholders, as many as the grid fits across and as wide
                        // as its cells, so the next page arrives in the shape it is waiting in
                        // (user, 2026-10-23), rather than one card alone at the start of a line.
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val least = if (poster) posterWidth(touch) else wideWidth(touch)
                            val across = ((maxWidth + gap) / (least + gap)).toInt().coerceAtLeast(1)
                            val cell = (maxWidth - gap * (across - 1)) / across
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                repeat(across) { PlaceholderTile(cell, touch, poster) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A catalog shelf's See all page (owner, 2026-11-06): the shelf's whole list as a grid,
 * the same shell as Recently added's page, with nothing to read on for: the shelf's list
 * is already whole in memory ([HomeRow.Catalog.all]).
 */
@Composable
private fun CatalogSeeAll(
    row: HomeRow.Catalog,
    title: String,
    watch: SeriesWatch,
    start: Dp,
    end: Dp,
    bottom: Dp,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    com.tmplayer.ui.nav.BackHandler { onClose() }
    val entries = row.all
    val poster = remember(entries) { SeriesShelf.posterKeys(entries).isNotEmpty() }
    val grid = com.tmplayer.ui.components.rememberAheadGridState()
    val firstTile = remember { FocusRequester() }
    if (!touch) LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { firstTile.requestFocus() }
    }
    val gap = if (touch) TILE_GAP_TOUCH else TILE_GAP_TV
    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .then(if (touch) Modifier.statusBarsPadding() else Modifier.padding(top = TV_BAR_HEIGHT)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (touch) 4.dp else start, end = end).heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (touch) {
                androidx.compose.material3.IconButton(onClick = onClose) {
                    androidx.compose.material3.Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text)
                }
                M3Text(
                    title,
                    style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(if (poster) posterWidth(touch) else wideWidth(touch)),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = start,
                end = end,
                top = if (touch) 4.dp else Tv.FocusClearance,
                bottom = bottom + if (touch) 24.dp else Tv.FocusClearance,
            ),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(if (touch) 16.dp else gap + Tv.FocusClearance),
        ) {
            items(
                entries.size,
                key = { entries[it].key },
                contentType = { if (poster) "poster-${entries[it]::class.simpleName}" else entries[it]::class },
            ) { index ->
                val entry = entries[index]
                val focus = if (index == 0) Modifier.focusRequester(firstTile) else Modifier
                // As in the row itself: on a phone the Top 10 tag is pinned over the
                // tile's top start corner, not handed to the tile's top end slot.
                if (touch && row.kind == "top10") {
                    Box(Modifier.fillMaxWidth()) {
                        ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth().then(focus))
                        Box(Modifier.align(Alignment.TopStart)) { Top10Badge(phone = true) }
                    }
                } else {
                    ShelfTile(entry, poster, watch, touch, chatTitle, onPlay, onHold, onOpenSeries, Modifier.fillMaxWidth().then(focus), badge = if (row.kind == "top10") ({ Top10Badge() }) else null)
                }
            }
        }
    }
}

/** One of a shelf's tiles: a video's card, or a show's that opens its page. */
@Composable
private fun ShelfTile(
    entry: ShelfEntry,
    poster: Boolean,
    watch: SeriesWatch,
    touch: Boolean,
    chatTitle: (Long) -> String,
    onPlay: (MediaItem, String) -> Unit,
    onHold: (MediaItem, String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier,
    /** The Top 10 row's corner badge; null for other rows. A show's own "New Episode" tag gives way to it. */
    badge: (@Composable () -> Unit)? = null,
    /** A tag across the foot of the art (Recently added); null for other rows. */
    bottomTag: String? = null,
) {
    when (entry) {
        is ShelfEntry.File -> MediaCard(
            item = entry.item,
            watched = watch.point(entry.item),
            finished = watch.finished(entry.item),
            onClick = { onPlay(entry.item, chatTitle(entry.item.chatId)) },
            onLongClick = { onHold(entry.item, chatTitle(entry.item.chatId)) },
            dense = touch,
            poster = poster,
            badge = badge,
            bottomTag = bottomTag,
            // Its first bytes asked for while the card is in focus or under the finger.
            modifier = modifier,
        )
        is ShelfEntry.Show -> {
            val progress = remember(entry.series, watch) { watch.progress(entry.series) }
            // A show's card warms the episode its page would play.
            val next = remember(progress, watch) {
                (progress.next ?: entry.series.episodes.firstOrNull())?.let { watch.pick(it, progress) }
            }
            SeriesCard(
                series = entry.series,
                progress = progress,
                fresh = remember(entry.series, watch) { watch.newEpisodes(entry.series) > 0 },
                onClick = { onOpenSeries(entry.series) },
                dense = touch,
                poster = poster,
                badge = badge,
                bottomTag = bottomTag,
                modifier = modifier.warmUpOnIntent(next),
            )
        }
    }
}

/**
 * The Top 10 row's corner badge (owner, v1.0.13): a small red tag on the art, beside the big
 * rank numeral the row already stands each tile next to.
 *
 * On a phone ([phone], owner, v1.0.14, item 13) it takes the Netflix tag's shape instead:
 * pinned flush to the art's top start corner by its caller, the word over the number, the
 * number big and black weighted as the row's own rank numerals are, its corners rounded
 * where the art's corner and the tag's free corner ask for it. The words are the one
 * catalog line split at its last space, so no new text is wanted in any language: a line
 * with no space in it is worn whole, as the television wears it.
 */
@Composable
private fun Top10Badge(phone: Boolean = false) {
    val s = LocalStrings.current
    if (phone) {
        val label = s.homeRowTop10
        val word = label.substringBeforeLast(' ', "")
        val number = label.substringAfterLast(' ', "")
        Column(
            Modifier
                .clip(RoundedCornerShape(topStart = Corner.Medium, bottomEnd = 8.dp))
                .background(Tone.danger)
                .padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (word.isNotEmpty() && number.isNotEmpty()) {
                M3Text(
                    word.uppercase(),
                    style = M3MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                )
                M3Text(
                    number,
                    style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                    maxLines = 1,
                )
            } else {
                M3Text(
                    label,
                    style = M3MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        return
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(Tone.danger).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        M3Text(
            s.homeRowTop10,
            style = M3MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** One horizontal lazy row, padded to line up with the heading, remembering its tile for the D-pad. */
@Composable
private fun TileRow(
    start: Dp,
    end: Dp,
    modifier: Modifier = Modifier,
    row: androidx.compose.foundation.lazy.LazyListState = com.tmplayer.ui.components.rememberAheadListState(),
    /** On a television, a last tile that opens the rest of the row; null for none. */
    seeAll: SeeAllEnd? = null,
    content: LazyListScope.() -> Unit,
) {
    // Set once the remote has moved the row on a television, so new videos at its front do not
    // send it back to its start under the viewer.
    val moved = remember { booleanArrayOf(false) }
    com.tmplayer.ui.components.KeepAtStart(row) { moved[0] }
    if (!isTouch()) {
        // On a television the tiles fade out over the margins rather than being cut off at the
        // rail, and the tile the remote moves to stays inside them.
        // Left and Right move the focus across the tiles on screen, as a television's home screen
        // does, and the row moves only when the next tile is past its edge, by that tile, in one
        // step ([EdgeStep]). The remote held at the row's start with the row jumping under it on
        // every press read as the whole row swapping (user, 2026-10-28); slid, each press drew
        // about nine frames of some 40 ms on a 1 GB stick (Mi TV).
        val stepping = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.key != Key.DirectionRight && event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
            // A held button's repeats queue up behind slow frames; one already stale is
            // dropped, so the row stops where the viewer let go instead of running on.
            if (staleRepeat(event)) return@onPreviewKeyEvent true
            moved[0] = true
            false
        }
        EdgeStep(start, end) {
            LazyRow(
                state = row,
                // Coming back down into a row lands on the tile it was left on, not whichever is nearest.
                modifier = modifier.then(stepping).fillMaxWidth().focusRestorer().edgeFade(row, start, end, ground = Tone.background),
                // Room above and below for a focused tile to grow into without being clipped by the row.
                contentPadding = PaddingValues(start = start, end = end, top = Tv.FocusClearance, bottom = Tv.FocusClearance),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP_TV),
            ) {
                content()
                if (seeAll != null) item(key = SEE_ALL_TILE, contentType = SEE_ALL_TILE) { SeeAllTile(seeAll) }
            }
        }
        return
    }
    LazyRow(
        state = row,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = start, end = end),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP_TOUCH),
        content = content,
    )
}

/** The Telegram facts under a continue card: where it was posted, its quality and its size. */
@Composable
private fun telegramFacts(chat: String, item: MediaItem): String {
    val s = LocalStrings.current
    return remember(chat, item, s) {
        listOfNotNull(
            chat.ifBlank { null },
            item.qualityTags.firstOrNull(),
            item.sizeBytes.takeIf { it > 0 }?.let { s.formatter.size(it) },
        ).joinToString("  ·  ")
    }
}

/** One line of facts under a card, in the tile's [ink]: muted at rest, the full text under focus. */
@Composable
private fun CaptionLine(text: String, ink: androidx.compose.ui.graphics.ColorProducer) {
    TileCaption(text, if (isTouch()) M3MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelMedium, ink)
}

/**
 * A row's name, SemiBold: titleLarge on a phone and on a television. A row that leaves videos out
 * offers the rest at the end of the heading, as a link with a chevron and no fill ([SeeAllLink]);
 * on a television it takes focus only from the row under it (see [HomePane]).
 */
@Composable
private fun RowHeading(
    title: String,
    start: Dp,
    end: Dp,
    /** A phone's See all; a television's is the row's last tile ([SeeAllTile]). */
    seeAll: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end, bottom = if (touch) HEADING_TO_ROW else 0.dp)
            // Every heading is as tall as its button, with or without one, so the gap between a
            // title and its tiles is the same on every row.
            .heightIn(min = if (touch) SEE_ALL_CHIP else TV_SEE_ALL_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (touch) {
            M3Text(
                title,
                style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (seeAll != null) {
            // The touch target is Material's 48 dp round the link.
            SeeAllLink(
                label = s.homeSeeAll,
                onClick = seeAll,
                modifier = Modifier.padding(start = 12.dp).minimumInteractiveComponentSize(),
            )
        }
    }
}

/**
 * What is left of the built-in sections on Home (v1.0.11): the Live row of the streams
 * posted in the built-in films channel, and nothing else. The channel cards that stood
 * here in v1.0.10 are removed (owner, 2026-11-06): their membership guess could call a
 * stranger a member and leave a dead card on Home. The invite resolution stays, minus
 * the cards: the films channel's invite gives its chat id, its recent posts give the
 * streams, and only what the signed-in account can see ever lands here. On a television
 * the Share J Player pill heads this block instead (a phone puts it beside Surprise me),
 * so the two stay neighbours there too.
 */
@Composable
private fun BuiltInHomeSections(
    start: Dp,
    end: Dp,
    onPlayStream: (StreamLink) -> Unit,
    onPlayStreamPair: ((StreamLink, StreamLink) -> Unit)? = null,
) {
    if (!isTouch()) {
        Column {
            Row(Modifier.padding(start = start, end = end, top = 6.dp, bottom = 2.dp)) {
                SharePlayerPill()
            }
            LiveStreamsRow(start, end, onPlayStream, onPlayStreamPair)
        }
    } else {
        LiveStreamsRow(start, end, onPlayStream, onPlayStreamPair)
    }
}

/**
 * The Live row itself: the streams posted in the built-in films channel, loaded the one
 * way (the invite resolved, the account joined once per run where it is not a member,
 * the recent posts read fresh), the viewer's removals and seen marks applied, and the
 * cards as [StreamCard] draws them, with the hold menu (Save to Downloads for a file
 * link, Remove from Live). Home heads its built-in sections with it, and the Chats list
 * embeds it under the pinned channel entry on a phone (owner, v1.0.14), so the two are
 * the same row by construction, never a second reading of the posts. Nothing is drawn
 * while there is nothing to show.
 *
 * The posts are read again whenever [LiveRefresh] asks (owner, v1.0.15): arriving at
 * Chats or Home, coming back to the app, a pull or the Home button pressed again. They
 * were read once at the first composition before, so a post added to the channel, or
 * deleted from it, waited for the row to be built anew before the row showed it.
 */
@Composable
internal fun LiveStreamsRow(
    start: Dp,
    end: Dp,
    onPlayStream: (StreamLink) -> Unit,
    /** Two live streams side by side (item 11c, v1.0.15); null leaves the offer out. */
    onPlayStreamPair: ((StreamLink, StreamLink) -> Unit)? = null,
) {
    var streams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    // The streams the viewer took out of this row (v1.0.11, owner): kept in Settings by the
    // post each one came from ([HiddenStreams]), so every later load leaves them out the same
    // way, while a repost (a new post) shows again. The posts are read fresh from Telegram on
    // every load and kept nowhere else, so a stream whose post is deleted in the channel is
    // gone from the row on the next load with nothing to clear.
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val hidden by remember(settings) { settings.hiddenStreams }.collectAsState(initial = emptySet())
    // The streams already opened once (owner, 2026-11-06): the row marks the rest new
    // until their first opening, by the post each came from ([SeenStreams]).
    val seen by remember(settings) { settings.seenStreams }.collectAsState(initial = emptySet())
    var menuFor by remember { mutableStateOf<StreamLink?>(null) }
    // A live stream waiting for its side-by-side partner to be picked (item 11c, v1.0.15).
    var pairFor by remember { mutableStateOf<StreamLink?>(null) }
    // When the posts were last read, and whether a hand refresh landed while a read was
    // running and is still owed one (owner, v1.0.15: see [LiveRefresh]).
    var lastReadAt by remember { mutableStateOf(0L) }
    var owedRead by remember { mutableStateOf(false) }
    val readLock = remember { kotlinx.coroutines.sync.Mutex() }
    val readScope = rememberCoroutineScope()

    suspend fun readPosts() {
        val session = Td.awaitConnectedSessionOrNull() ?: return
        val actions = TelegramLinkActions(session.client)
        val channel = BuiltInChannels.J_MOVIES
        val preview = actions.previewInvite(channel.invite).getOrNull() ?: return
        var chatId = preview.chatId
        // The owner's direction (2026-11-06): the app joins his channel for the viewer,
        // once per run, only where their account is not a member, silently, and never in
        // a loop. A join that lands names the chat, and the Live row reads it at once.
        if (!preview.alreadyMember && !BuiltInChannels.jMoviesJoinAttempted) {
            BuiltInChannels.jMoviesJoinAttempted = true
            val joined = runCatching { actions.joinInvite(channel.invite) }.getOrNull()?.getOrNull() ?: 0L
            if (joined != 0L) chatId = joined
        }
        if (chatId == 0L) return
        // The resolved id is kept process wide: duplicate folding leads with this channel's
        // own post of a file wherever copies fold (owner, 2026-11-06).
        BuiltInChannels.jMoviesChatId = chatId
        val posts = runCatching { actions.recentPosts(chatId, TelegramLinkActions.LIVE_POSTS) }.getOrDefault(emptyList())
        streams = StreamLinks.inPosts(posts, chatId, preview.title.ifBlank { channel.name })
        lastReadAt = System.currentTimeMillis()
    }

    // One read at a time: a read already running is reading the newest posts anyway, and a
    // hand refresh that lands during one is owed a second read when it finishes, since the
    // post it was made for may have landed after that read began.
    fun readOnce() {
        if (!readLock.tryLock()) return
        readScope.launch {
            try {
                readPosts()
            } finally {
                readLock.unlock()
                if (owedRead) {
                    owedRead = false
                    readOnce()
                }
            }
        }
    }

    LaunchedEffect(Unit) { readOnce() }
    // A read that found no session, or no streams, is tried again when the session
    // (re)connects (item 2, v1.0.16): the row is composed before TDLib is up on a cold
    // start, and a first read silently answering nothing left the channel standing in
    // the Chats list with no streams under it until something else happened to ask.
    LaunchedEffect(Unit) {
        Td.connected.collect { connected -> if (connected && streams.isEmpty()) readOnce() }
    }
    // The asks (LiveRefresh): arriving at the page or back at the app, and the viewer's own
    // refreshes. An arrival waits a moment first (TDLib pulls in what it missed on coming
    // to the front, and the read should land after that, not before it) and is skipped
    // while the last read is still fresh, so a walk between Home and Chats is not a series
    // of reads. A forced ask, a hand's refresh, always reads. An empty row waits for no
    // gap at all (item 2, v1.0.16): it has nothing to show, and the streams it is missing
    // are the whole reason for the ask.
    LaunchedEffect(Unit) {
        LiveRefresh.asked.collect { forced ->
            if (forced) {
                if (readLock.isLocked) owedRead = true else readOnce()
            } else {
                kotlinx.coroutines.delay(LIVE_ARRIVAL_SETTLE_MS)
                if (!readLock.isLocked && (streams.isEmpty() || System.currentTimeMillis() - lastReadAt >= LIVE_ARRIVAL_GAP_MS)) readOnce()
            }
        }
    }

    val shown = HiddenStreams.visible(streams, hidden)
    val s = LocalStrings.current
    if (shown.isNotEmpty()) {
        Column {
            RowHeading(s.homeLiveTitle, start = start, end = end, seeAll = null)
            LazyRow(
                contentPadding = PaddingValues(start = start, end = end),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Keyed by the post each stream came from, not the address: a repost of
                // the same address is a second card, and the seen and hidden sets know
                // streams by their post the same way.
                items(shown, key = { if (it.messageId != 0L) "${it.chatId}:${it.messageId}" else it.url }) { link ->
                    StreamCard(
                        link,
                        isNew = !com.tmplayer.data.SeenStreams.seen(seen, link),
                        onClick = {
                            App.backgroundScope.launch { runCatching { settings.markStreamSeen(link) } }
                            onPlayStream(link)
                        },
                        onHold = { menuFor = link },
                    )
                }
            }
        }
    }
    menuFor?.let { link ->
        TvMenu(
            title = link.name,
            actions = listOfNotNull(
                // A file link can also be saved to the phone's Downloads folder (owner,
                // 2026-11-06); a live stream has no file to save.
                if (!link.live) {
                    MenuAction(
                        label = s.downloadsDownload,
                        icon = TmIcons.Download,
                        detail = s.downloadsSaveToDownloads,
                    ) {
                        menuFor = null
                        com.tmplayer.data.LinkDownloader.download(context, link)
                    }
                } else {
                    null
                },
                // Two live streams at once (item 11c, v1.0.15): this one and a partner
                // picked from the row's other live streams, in the menu that follows.
                if (link.live && onPlayStreamPair != null && shown.any { it.live && it.url != link.url }) {
                    MenuAction(
                        label = s.homeLiveSideBySide,
                        icon = TmIcons.List,
                        detail = s.homeLiveSideBySideDetail,
                    ) {
                        menuFor = null
                        pairFor = link
                    }
                } else {
                    null
                },
                MenuAction(
                    label = s.homeLiveRemove,
                    icon = TmIcons.Delete,
                    detail = s.homeLiveRemoveDetail,
                    destructive = true,
                ) {
                    menuFor = null
                    // Out of the row at once, and into the stored set on the app's scope, as
                    // the other Home writes run, so leaving Home straight away keeps it.
                    streams = streams.filterNot { it.url == link.url }
                    App.backgroundScope.launch {
                        runCatching { settings.setHiddenStreams(HiddenStreams.plus(settings.hiddenStreamsNow(), link)) }
                    }
                },
            ),
            onDismiss = { menuFor = null },
        )
    }
    pairFor?.let { first ->
        TvMenu(
            title = s.homeLiveSideBySidePick,
            actions = shown
                .filter { it.live && it.url != first.url }
                .map { second ->
                    MenuAction(
                        label = second.name,
                        icon = TmIcons.Play,
                    ) {
                        pairFor = null
                        onPlayStreamPair?.invoke(first, second)
                    }
                },
            onDismiss = { pairFor = null },
        )
    }
}

/**
 * Share J Player, as one pill: the app's release page and both built-in channels' invite
 * links, as one short message (the About page stays name, icon, version). On a phone it
 * stands beside Surprise me; on a television it heads the built-in sections.
 */
@Composable
private fun SharePlayerPill() {
    val s = LocalStrings.current
    val context = LocalContext.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Tone.surfaceHigh)
            .clickable {
                val text = buildString {
                    append(s.shareAppLine)
                    append('\n').append(BuiltInChannels.RELEASES_URL)
                    BuiltInChannels.ALL.forEach { channel ->
                        append('\n').append(channel.name).append(": ").append(channel.inviteUrl)
                    }
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                runCatching {
                    context.startActivity(Intent.createChooser(send, s.commonShare).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Share, contentDescription = null, tint = Tone.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        M3Text(s.shareAppTitle, style = M3MaterialTheme.typography.titleSmall, color = Tone.text)
    }
}

/** The Open link pill (v1.0.11): asks for an address and plays it in the player. */
@Composable
private fun OpenLinkPill(onClick: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Tone.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.OpenExternal, contentDescription = null, tint = Tone.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        M3Text(s.homeOpenLink, style = M3MaterialTheme.typography.titleSmall, color = Tone.text)
    }
}

/**
 * One stream's card in the Live row: the stream's own name (the post's words, or a name
 * from its link where the post gave none, v1.0.15), its source small under it, and its
 * badge. A press plays it; a long press, or a hold of OK on the remote, opens its menu
 * ([holdable], the tiles' own arrangement), whose one action takes the stream out of the row.
 *
 * On a phone the card is a wide one (owner, v1.0.14): a 16:9 cover on top, the post's own
 * photo where the post carried one (a plain stand-in where it did not), the badges over
 * the cover's corner and the name under it. The television keeps the compact card it has
 * always had: this composable serves both, so its layout below is untouched.
 */
@Composable
private fun StreamCard(link: StreamLink, isNew: Boolean = false, onClick: () -> Unit, onHold: () -> Unit) {
    val s = LocalStrings.current
    val touch = isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    Column(
        Modifier
            .width(if (touch) 272.dp else 200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Tone.surfaceHigh)
            .border(
                width = Focus.Edge,
                color = if (focused) Tone.accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .holdable(interactionSource = interactions, onClick = onClick, onHold = onHold)
            .then(if (touch) Modifier else Modifier.padding(14.dp)),
    ) {
        if (touch) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Tone.surface)) {
                if (link.artFileId > 0 || link.artMiniThumbnail != null) {
                    com.tmplayer.ui.components.MediaArt(
                        link.artMiniThumbnail,
                        link.artFileId,
                        Modifier.fillMaxSize(),
                        showCoverTitle = false,
                    )
                } else {
                    Icon(
                        TmIcons.Play,
                        contentDescription = null,
                        tint = Tone.muted,
                        modifier = Modifier.size(38.dp).align(Alignment.Center),
                    )
                }
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (link.live) LiveBadge()
                    if (isNew) NewBadge()
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                M3Text(
                    link.name,
                    style = M3MaterialTheme.typography.titleSmall,
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StreamSourceLine(link)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (link.live) {
                    LiveBadge()
                } else {
                    Icon(TmIcons.Play, contentDescription = null, tint = Tone.text, modifier = Modifier.size(18.dp))
                }
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    NewBadge()
                }
            }
            Spacer(Modifier.height(8.dp))
            M3Text(
                link.name,
                style = M3MaterialTheme.typography.titleSmall,
                color = Tone.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StreamSourceLine(link)
        }
    }
}

/**
 * A Live card's source under its name (owner, v1.0.15): the channel the stream was posted
 * in, small, never the headline. The name above is the stream's own (see
 * [com.tmplayer.data.StreamLinks]); where the source is the name (a pasted link's host
 * under its file name says nothing new) or there is none, no line is drawn.
 */
@Composable
private fun StreamSourceLine(link: StreamLink) {
    if (link.source.isBlank() || link.source == link.name) return
    M3Text(
        link.source,
        style = M3MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** The LIVE pill on a stream card, on the compact card and over the wide one's cover. */
@Composable
private fun LiveBadge() {
    val s = LocalStrings.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Tone.danger).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        M3Text(s.homeLiveBadge, style = M3MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/** The New pill on a stream card, beside the LIVE one or over the wide one's cover. */
@Composable
private fun NewBadge() {
    val s = LocalStrings.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Tone.accent).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        M3Text(s.homeNewBadge, style = M3MaterialTheme.typography.labelSmall, color = Tone.onAccent)
    }
}

/**
 * A row still asking Telegram: blank cards the size of the real ones, so nothing jumps when it
 * fills. The caption is the cards' own, in the same type with nothing visible in it, rather than
 * a guessed height: it then matches at any font scale.
 *
 * [poster] for a row whose cards are 2:3 ([SeriesShelf.posterKeys]: Recently added and a chat's
 * row). Drawn 16:9 there, the row grew by half when its posters came in (TV, 2026-10-27).
 */
@Composable
private fun PlaceholderRow(start: Dp, poster: Boolean = false) {
    val touch = isTouch()
    val width = if (poster) posterWidth(touch) else wideWidth(touch)
    Row(
        Modifier.fillMaxWidth().padding(start = start, top = if (touch) 0.dp else Tv.FocusClearance, bottom = if (touch) 0.dp else Tv.FocusClearance),
        horizontalArrangement = Arrangement.spacedBy(if (touch) TILE_GAP_TOUCH else TILE_GAP_TV),
    ) {
        repeat(PLACEHOLDER_TILES) { PlaceholderTile(width, touch, poster) }
    }
}

/** One blank card the size of a real one: a [PlaceholderRow]'s, and the foot of a row reading on. */
@Composable
private fun PlaceholderTile(width: Dp, touch: Boolean, poster: Boolean = false) {
    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (poster) 2f / 3f else 16f / 9f)
                .clip(RoundedCornerShape(Corner.Medium))
                .background(Tone.surface),
        )
        Spacer(Modifier.height(if (touch) 8.dp else 10.dp))
        if (touch) {
            M3Text(" ", style = M3MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            M3Text(" ", style = M3MaterialTheme.typography.labelMedium, maxLines = 1)
        } else {
            Text(" ", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(" ", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/**
 * A film's or a show's poster: 126 x 189 dp on both, Nuvio's measure, which on a phone held
 * upright shows about 2.8 across, the cut third saying there is more this way.
 */
@Suppress("UNUSED_PARAMETER")
private fun posterWidth(touch: Boolean): Dp = POSTER_WIDTH

private val POSTER_WIDTH = 126.dp

/**
 * Any other video's 16:9 card. A television's fits [Tv.FocusClearance] when it grows, and is about
 * the size Netflix and Google TV give a landscape card: five or so across rather than four
 * (user, 2026-10-23: the cards made everything look big and showed too little).
 */
private fun wideWidth(touch: Boolean): Dp = if (touch) 200.dp else 176.dp

/**
 * A Continue watching card, a little wider, as it carries its name on the art. On a television four
 * or so across, so the first row under the carousel fits whole on the first screen.
 */
private fun continueWidth(touch: Boolean): Dp = if (touch) 247.dp else 208.dp

private const val PLACEHOLDER_TILES = 5

/** The TV button's own height, which every TV row heading takes so the rows space evenly. */
private val TV_SEE_ALL_HEIGHT = 40.dp

/** A television's See all at a row's end: what it opens, and its tile's size. */
private data class SeeAllEnd(val onClick: () -> Unit, val width: Dp = 0.dp, val poster: Boolean = false)

private const val SEE_ALL_TILE = "see-all"

/**
 * A row's last tile on a television, where Right past the last video lands: the art the size of
 * the row's own, with the same focus, and "See all" on it in the accent with a chevron. Nothing under it:
 * a count read "0 videos" on Recently added, which cannot tell, and the words on the art read
 * at a glance (user, 2026-10-28).
 */
@Composable
private fun SeeAllTile(end: SeeAllEnd) {
    val s = LocalStrings.current
    VideoTile(
        title = s.homeSeeAll,
        onClick = end.onClick,
        poster = end.poster,
        showTitle = false,
        modifier = Modifier.width(end.width),
    ) {
        Column(
            // The app's accent, faint over the surface, so it reads as the row's way on without
            // shouting over the pictures beside it (user, 2026-10-28: "too big").
            Modifier.fillMaxSize().background(Tone.surface).background(Tone.accent.copy(alpha = 0.10f)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                s.homeSeeAll,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.accent,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/** The phone's See all chip. */
private val SEE_ALL_CHIP = 32.dp

/** Between a phone row's heading and its cards. */
private val HEADING_TO_ROW = 10.dp

private val ROW_GAP_TOUCH = 24.dp

/** A television's rows already keep [Tv.FocusClearance] above and below their tiles. */
private val ROW_GAP_TV = 12.dp

private val TILE_GAP_TOUCH = 10.dp
private val TILE_GAP_TV = 12.dp

/** Between a row's poster strip and its strip of cards, on a phone. */
private val STRIP_GAP = 12.dp


/** The lazy key of the phone's featured carousel at the top of Home. */
private const val FEATURED_KEY = "featured"
private const val SURPRISE_KEY = "surprise"
private const val CHIPS_KEY = "chips"
private const val BUILT_IN_KEY = "built-in"

/** How far Home moves down on a television when Down finds no row on screen below the remote. */
private val TV_DOWN_STEP = 200.dp

/**
 * The carousel's pages from the Recently added row [recent]: null while that row is still being
 * asked for or the fill is still deciding (the carousel then holds its frame with a stand-in, at
 * most [HomeFeatured.FILL_WAIT_MS] once the row is in), empty for nothing to feature, which hides
 * the carousel. Only complete pages ([com.tmplayer.ui.online.completeFeatured]), decided once
 * per fill so no page comes or goes while it is looked at; a refresh keeps the pages up until
 * the new ones are decided. With online lookups off there are no complete pages, so none at once.
 * The decided pages are [HomeFeatured.bannerSet]'s: the pinned titles ([pinEntries], named by
 * [pinKeys]) lead, and the rest fill toward the full set in its six films and four shows mix.
 */
@Composable
private fun rememberFeatured(
    recent: HomeRow.Recent?,
    pinEntries: List<ShelfEntry> = emptyList(),
    pinKeys: Set<String> = emptySet(),
): List<com.tmplayer.data.FeaturedPick>? {
    val online = com.tmplayer.online.OnlineMetadata.current
    val enabled = online?.settings?.collectAsState()?.value?.enabled == true
    val candidates = remember(recent, pinEntries) {
        val auto = recent?.takeIf { it.loaded }?.let { com.tmplayer.data.HomeFeatured.pick(it.entries, com.tmplayer.data.HomeFeatured.CANDIDATES) }
        // The pinned titles are candidates in their own right, ahead of the newest: the
        // newest-first candidate cut must not crowd a pinned title out of its own banner.
        val pins = pinEntries.takeIf { it.isNotEmpty() }?.let { com.tmplayer.data.HomeFeatured.pick(it, com.tmplayer.data.HomeFeatured.LIMIT) }
        when {
            auto == null && pins == null -> null
            else -> (pins.orEmpty() + auto.orEmpty()).distinctBy { it.key }
        }
    }
    var decided by remember { mutableStateOf<List<com.tmplayer.data.FeaturedPick>?>(null) }
    LaunchedEffect(candidates, online, enabled) {
        if (!enabled) {
            decided = emptyList()
            return@LaunchedEffect
        }
        val picks = candidates ?: return@LaunchedEffect
        if (picks.isEmpty()) {
            decided = picks
            return@LaunchedEffect
        }
        // The lookups still unanswered when the wait is up go on in this effect, so their answers
        // are on the device for the next fill; they stop with it.
        val keys = try {
            com.tmplayer.ui.online.completeFeatured(picks, this)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        decided = com.tmplayer.data.HomeFeatured.bannerSet(
            picks.filter { it.key in keys },
            pinned = { pick -> pinKeys.isNotEmpty() && com.tmplayer.data.VideoCopies.keyOf(pick.item) in pinKeys },
        )
    }
    return if (!enabled) emptyList() else decided
}

/** How long an empty Home waits for its rows before it takes itself for a first run. */
private const val ROWS_WAIT_MS = 10_000L

/** How long Home without a carousel waits for one to turn up before the cold start's loader lifts. */
private const val NO_CAROUSEL_SETTLE_MS = 1_500L

/** The same on a television, where getting to the rows fast matters more than a jump the focus hides. */
private const val NO_CAROUSEL_SETTLE_TV_MS = 300L


/**
 * A phone's "See all" at the end of a Home row's heading: muted words and a chevron with no fill at rest. Under
 * the remote, or pressed, the words and the chevron turn the accent, grow a little and are
 * underlined, so the place reads from across the room without a pill round it.
 */
@Composable
private fun SeeAllLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val lit = focused || pressed
    val still = com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
    val scale by androidx.compose.animation.core.animateFloatAsState(
        when {
            still -> 1f
            pressed -> com.tmplayer.ui.theme.Motion.PressScale
            focused -> com.tmplayer.ui.theme.Motion.FocusScale
            else -> 1f
        },
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllScale",
    )
    val ink by androidx.compose.animation.animateColorAsState(
        if (lit) Tone.accent else Tone.muted,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllInk",
    )
    val line by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1f else 0f,
        com.tmplayer.ui.theme.Motion.standard(com.tmplayer.ui.theme.Motion.Fast),
        label = "seeAllLine",
    )
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp)
            .drawBehind {
                if (line <= 0f) return@drawBehind
                val stroke = 2.dp.toPx()
                drawRect(ink, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - stroke), size = androidx.compose.ui.geometry.Size(size.width * line, stroke))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(label, style = M3MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
        androidx.compose.material3.Icon(TmIcons.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * A held D-pad's repeat that has waited longer than a couple of frames to be handled. Leanback
 * caps the moves it queues the same way (GridLayoutManager's pending moves): on a stick whose
 * frames take 40 ms, repeats pile up, and once the button is let go the focus kept moving.
 */
private fun staleRepeat(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
    val native = event.nativeKeyEvent
    return native.repeatCount > 0 && android.os.SystemClock.uptimeMillis() - native.eventTime > STALE_REPEAT_MS
}

/** How old a repeat may be and still move the focus: about two frames on a slow stick. */
private const val STALE_REPEAT_MS = 80L

/** How long an arrival ask for the Live row's posts waits, so TDLib's catch up lands first (see [LiveRefresh]). */
private const val LIVE_ARRIVAL_SETTLE_MS = 1_500L

/** The youngest a Live row read may be for an arrival ask to read again: arrivals are spaced, hands are not. */
private const val LIVE_ARRIVAL_GAP_MS = 20_000L

/**
 * Home's vertical list under the TV top bar: a focused item already standing between the bar's foot
 * ([barPx]) and three fifths of the way down stays where it is; anything above that band, under
 * the bar, or below it is brought to three tenths of the way down, as the television does.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun calmVertical(
    barPx: Float,
    /** A distance of its own for a request (the first row under the carousel), or null for the band's. */
    held: (offset: Float, size: Float, containerSize: Float) -> Float? = { _, _, _ -> null },
) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    // At once rather than slid: a slide down to the next row drew some seventeen frames on the
    // 1 GB stick, each 20 to 130 ms, so the page swam for half a second after every Up and Down.
    // The rows already move in one step ([EdgeStep]); the page now does too.
    @Deprecated("Matches the interface; the snap is the point.")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.snap()

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        held(offset, size, containerSize)?.let { return it }
        val lead = containerSize * 0.3f
        return when {
            offset < barPx -> offset - lead
            offset + size > containerSize -> offset - lead
            offset > containerSize * 0.6f -> offset - lead
            else -> 0f
        }
    }
}

/**
 * Where the remote stood on the television's Home: the carousel ([FEATURED_KEY]) or a row's key.
 * For the process rather than the screen, since the system may destroy the shell behind the
 * player and build Home again when the viewer comes back. [away] is set as the player (or
 * anything else) covers the app with the remote on Home, and cleared once it has been put back.
 */
internal object HomeFocusMemory {
    @Volatile var spot: String? = null
    @Volatile var away: Boolean = false

    /** The remote left Home on purpose: nothing to put back. */
    fun forget() {
        spot = null
        away = false
    }
}

/** How many frames after coming back the remote's place on Home is held against other claims. */
private const val RESTORE_FRAMES = 12

/** Frames in a row the remote has to stay put for its place to count as restored. */
private const val RESTORE_SETTLED_FRAMES = 3

/** The list key of the phone's header line at the top of Home (item 15). */
private const val HEADER_KEY = "header"

/** The list key of Home's update card, on a phone and a television (item 13). */
private const val UPDATE_KEY = "update-card"

/** The name's distance below the status bar on a phone's Home. */
internal val BRAND_TOP = 12.dp

/** The mark and "J Player" at the top of a phone's Home, in the colours for art over the carousel. */
@Composable
internal fun PhoneHomeBrand(modifier: Modifier, onArt: Boolean) {
    com.tmplayer.ui.components.BrandLockup(
        modifier,
        layout = com.tmplayer.ui.components.BrandLayout.Row,
        color = if (onArt) Tone.onArt else Tone.text,
    )
}

/**
 * Home's header line (owner, v1.0.15, item 15, the Netflix shape): the J Player
 * mark and name, the Home title, and the useful actions on one line: the update
 * notice where a newer version stands (it opens the update popup, as the rail
 * item does) and Downloads. One composable for the list's top and for the pinned
 * bar over the rows (item 30), so the two never drift apart.
 */
@Composable
private fun HomeHeaderLine(
    start: Dp,
    end: Dp,
    top: Dp,
    updateVersion: String?,
    onUpdate: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = end, top = top, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneHomeBrand(Modifier, onArt = false)
        Spacer(Modifier.width(10.dp))
        M3Text(
            s.browseTabHome,
            style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tone.text,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (updateVersion != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Tone.caution.copy(alpha = 0.16f))
                    .clickable(onClick = onUpdate)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TmIcons.Refresh, contentDescription = null, tint = Tone.caution, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                M3Text(
                    "${s.navUpdate} $updateVersion",
                    style = M3MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.caution,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Tone.surfaceHigh)
                .clickable(onClick = onOpenDownloads)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TmIcons.Download, contentDescription = null, tint = Tone.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            M3Text(s.navDownloads, style = M3MaterialTheme.typography.labelLarge, color = Tone.text, maxLines = 1)
        }
    }
}

/**
 * The chips row under the header line (owner, v1.0.15, items 16 and 29): Shows,
 * Movies, New & Hot and Categories in a row that scrolls sideways, the picked
 * chip filled with the accent. One composable for the list's top and the pinned
 * bar, as the header line is.
 */
@Composable
private fun HomeChipsRow(
    start: Dp,
    end: Dp,
    top: Dp,
    chip: com.tmplayer.data.HomeChip,
    shows: Boolean,
    movies: Boolean,
    hot: Boolean,
    categories: Boolean,
    onChip: (com.tmplayer.data.HomeChip) -> Unit,
    onCategories: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier
            .padding(top = top, bottom = 2.dp)
            .horizontalScroll(rememberScrollState())
            .padding(start = start, end = end),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (shows) HomeChipPill(s.homeRowShows, selected = chip == com.tmplayer.data.HomeChip.Shows) { onChip(com.tmplayer.data.HomeChip.Shows) }
        if (movies) HomeChipPill(s.homeRowMovies, selected = chip == com.tmplayer.data.HomeChip.Movies) { onChip(com.tmplayer.data.HomeChip.Movies) }
        if (hot) HomeChipPill(s.homeNewHot, selected = chip == com.tmplayer.data.HomeChip.NewHot) { onChip(com.tmplayer.data.HomeChip.NewHot) }
        if (categories) HomeChipPill(s.homeCategories, selected = false) { onCategories() }
    }
}

/** One of the header's chips: a rounded tag, filled when its shape is the one Home wears. */
@Composable
private fun HomeChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Tone.accent else Tone.surface)
            .border(1.dp, if (selected) Tone.accent else Tone.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(
            label,
            style = M3MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) Tone.onAccent else Tone.text,
            maxLines = 1,
        )
    }
}

/**
 * The update on Home itself (owner, v1.0.15, item 13): the newer version, its
 * What's New lines from the feed (the first few; the popup carries the whole
 * list) and an Update button, with a way to put the card away for the day. The
 * button opens the same popup the rail item opens, where the download and the
 * install are run from, as the popup has always run them. On a television the
 * card is an item in Home's list, and its buttons take the remote in the list's
 * own order.
 */
@Composable
private fun HomeUpdateCard(
    version: String,
    points: List<String>,
    start: Dp,
    end: Dp,
    tv: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val updateFocus = remember { FocusRequester() }
    Column(
        modifier
            .padding(start = start, end = end)
            .clip(RoundedCornerShape(16.dp))
            .background(Tone.surface)
            .border(1.dp, Tone.outline, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        M3Text(
            s.updateTitle(version),
            style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val shown = points.take(HOME_UPDATE_POINTS)
        if (shown.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            M3Text(s.settingsWhatsNew, style = M3MaterialTheme.typography.labelMedium, color = Tone.muted)
            Spacer(Modifier.height(4.dp))
            shown.forEach { point ->
                Row {
                    M3Text("•  ", style = M3MaterialTheme.typography.bodySmall, color = Tone.accent)
                    M3Text(
                        point,
                        style = M3MaterialTheme.typography.bodySmall,
                        color = Tone.text.copy(alpha = 0.86f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tv) {
                TmButton(onClick = onUpdate, modifier = Modifier.focusRequester(updateFocus)) {
                    Text(s.updateUpdateNow)
                }
                TmSecondaryButton(onClick = onDismiss) { Text(s.commonDismiss) }
            } else {
                androidx.compose.material3.Button(onClick = onUpdate) { M3Text(s.updateUpdateNow) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { M3Text(s.commonDismiss, color = Tone.muted) }
            }
        }
    }
}

/** What's New lines Home's update card carries: enough to say what the version brings, the popup has the rest. */
private const val HOME_UPDATE_POINTS = 4
