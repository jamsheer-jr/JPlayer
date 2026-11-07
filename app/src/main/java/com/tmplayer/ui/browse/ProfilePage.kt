package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.App
import com.tmplayer.data.Account
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.HistoryEdits
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.VideoCollection
import com.tmplayer.data.WatchLaterRecord
import com.tmplayer.data.WatchedRecord
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.components.ViewingSummary
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.LocalOnlineArt
import com.tmplayer.ui.online.OnlineArt
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.launch

/**
 * The phone's Profile page (v1.0.14, item 15): the Netflix "My Netflix" shape over J Player's
 * own lists, replacing the avatar's popover menu on the phone. The television keeps its account
 * menu on its top bar and never composes this page.
 *
 * Every section draws the records the browser already holds (Continue watching, Watched, Watch
 * later, the starred chats, the collections and the stats behind them), and every row leads to
 * the screen that already owns that list: Downloads, History's tabs, Favourites, Settings and
 * the account page inside it, Home for the collections. Nothing here asks Telegram for anything
 * the existing sections do not already ask for: a card's picture comes through the same cached
 * lookup History's cards use ([rememberRecordItem]).
 *
 * The popover's entries, and where each lives now: Your viewing counts in the viewing section
 * (the counts and [StatsSummary] together), Favourites in its own section and in that section's
 * heading, Downloads in the Downloads card at the top, History in the History row near the foot
 * (and Watched in the Recently watched row and the viewing counts), Watch later in the Watch
 * later row under the My List card, Settings in the Settings row at the foot, Update in its row
 * at the foot when there is one, and New collection at the foot of the Collections section.
 */
@Composable
internal fun ProfilePage(
    account: Account?,
    favorites: Set<Long>,
    chats: List<ChatSummary>,
    continueWatching: List<ResumeRecord>,
    watchedHistory: List<WatchedRecord>,
    watchLater: List<WatchLaterRecord>,
    collections: List<VideoCollection>,
    downloadCount: Int,
    /** The popover's own counts, kept as they were: part watched videos, and finished ones. */
    watchingCount: Int,
    watchedCount: Int,
    updateVersion: String?,
    watch: SeriesWatch,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    /** The account header: the account page inside Settings, where accounts are switched. */
    onAccount: () -> Unit,
    onUpdate: () -> Unit,
    onFavourites: () -> Unit,
    onHistory: (HistoryTab) -> Unit,
    /** Home, where the collections stand as rows and titles are found for My List. */
    onHome: () -> Unit,
    onOpenChat: (ChatSummary) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onRestart: (ResumeRecord) -> Unit,
    onMarkWatched: (ResumeRecord) -> Unit,
    onPlayItem: (MediaItem, String) -> Unit,
    onSetWatched: (MediaItem, String, Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    TouchPage(TouchPageSpec(current = null, inProfile = true))
    val s = LocalStrings.current
    val context = LocalContext.current
    val settings = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val historyRemoval = rememberHistoryRemoval()
    // One card for the copies of one video, as the History tab and Home fold them.
    val continuing = remember(continueWatching) { HomeRows.foldCopies(continueWatching) }
    val watched = remember(watchedHistory) { HomeRows.foldWatched(watchedHistory) }
    val starredChats = remember(chats, favorites) { chats.filter { it.id in favorites } }
    // The overflow menu of one Continue watching card, with the actions its History card offers.
    var continueMenu by remember { mutableStateOf<ResumeRecord?>(null) }
    // A title's detail page, stacked the way the browser stacks its pages.
    var detailStack by remember { mutableStateOf<List<Pair<MediaItem, String>>>(emptyList()) }
    val openDetail: (MediaItem, String) -> Unit = { item, title -> detailStack = detailStack + (item to title) }
    // The New collection name prompt, the same sheet the popover opened (v1.0.13).
    var namingCollection by remember { mutableStateOf(false) }
    // Back up and Restore on this page too (owner, v1.0.14, item 26): Settings, About's
    // own actions over the same flows, their messages on this page's message line, and a
    // picked file's plan confirmed here before anything is merged.
    var restorePlan by remember { mutableStateOf<com.tmplayer.data.RestorePlan?>(null) }
    val backupActions = com.tmplayer.ui.settings.rememberBackupActions(
        onPlan = { restorePlan = it },
        say = onMessage,
    )

    // The page stands on its own under the status bar, with no bar of its own over it, so the
    // list itself keeps the header clear of the status bar (owner, v1.0.15: the account header
    // crowded the top of his phone). At the foot it keeps the floating bar's room and a full
    // row's worth more: with only a small extra the last rows still slid under the floating
    // pill, their titles half covered, his Watch later row among them.
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(
        Modifier.fillMaxSize().background(Tone.background),
        contentPadding = PaddingValues(top = statusTop, bottom = LocalBottomChrome.current + 56.dp),
    ) {
        item(key = "account") {
            ProfileAccountHeader(account, onAccount)
        }
        item(key = "downloads") {
            ProfileWideCard(
                icon = TmIcons.Download,
                title = s.navDownloads,
                detail = s.profileDownloadsDetail,
                badge = downloadCount.takeIf { it > 0 }?.toString(),
                onClick = onDownloads,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
        if (continuing.isNotEmpty()) {
            item(key = "continue-heading") {
                ProfileHeading(s.homeRowContinue)
            }
            item(key = "continue-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(continuing, key = { "c-${it.chatId}_${it.messageId}" }) { record ->
                        ProfileContinueCard(
                            record = record,
                            onPlay = { onResume(record) },
                            onInfo = { openDetail(record.toMediaItem(), record.chatTitle) },
                            onMenu = { continueMenu = record },
                        )
                    }
                }
            }
        }
        item(key = "my-list") {
            ProfileMyListCard(
                onBrowse = onHome,
                onOpen = { onHistory(HistoryTab.WatchLater) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
        }
        if (watchLater.isNotEmpty()) {
            item(key = "later-heading") {
                ProfileHeading(s.browseTabWatchLater, onClick = { onHistory(HistoryTab.WatchLater) })
            }
            item(key = "later-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(watchLater, key = { "l-${it.id}" }) { record ->
                        ProfilePosterCard(
                            fallback = record.toMediaItem(),
                            title = if (record.isShow) record.title else null,
                            onClick = { openDetail(record.toMediaItem(), record.chatTitle) },
                        )
                    }
                }
            }
        }
        if (watched.isNotEmpty()) {
            item(key = "watched-heading") {
                ProfileHeading(s.profileRecentlyWatched, onClick = { onHistory(HistoryTab.Watched) })
            }
            item(key = "watched-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(watched, key = { "w-${it.key}" }) { record ->
                        ProfilePosterCard(
                            fallback = record.toMediaItem(),
                            title = null,
                            onClick = { openDetail(record.toMediaItem(), record.chatTitle) },
                        )
                    }
                }
            }
        }
        item(key = "favourites-heading") {
            ProfileHeading(s.browseTabFavourites, onClick = onFavourites)
        }
        if (starredChats.isEmpty()) {
            item(key = "favourites-empty") {
                M3Text(
                    s.browseEmptyFavouritesTouch,
                    style = M3MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
            }
        } else {
            item(key = "favourites-row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(starredChats, key = { "f-${it.id}" }) { chat ->
                        ProfileChatChip(chat, onClick = { onOpenChat(chat) })
                    }
                }
            }
        }
        item(key = "collections-heading") {
            ProfileHeading(s.settingsCatCollections)
        }
        items(collections, key = { "col-${it.name}" }) { collection ->
            ProfileEntryRow(
                icon = TmIcons.Folder,
                label = collection.name,
                badge = s.browseVideosCount(collection.keys.size),
                onClick = onHome,
            )
        }
        item(key = "new-collection") {
            ProfileEntryRow(
                icon = TmIcons.Add,
                label = s.detailNewCollection,
                badge = null,
                onClick = { namingCollection = true },
            )
        }
        item(key = "viewing") {
            Column {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(Corner.Medium))
                        .background(Tone.surfaceHigh),
                ) {
                    ViewingSummary(
                        watching = watchingCount,
                        watched = watchedCount,
                        starred = favorites.size,
                        onWatching = { onHistory(HistoryTab.Continue) },
                        onWatched = { onHistory(HistoryTab.Watched) },
                        onStarred = onFavourites,
                    )
                }
                StatsSummary(watched, continuing, 16.dp, 16.dp)
            }
        }
        item(key = "history") {
            ProfileEntryRow(
                icon = TmIcons.History,
                label = s.browseTabHistory,
                badge = null,
                onClick = { onHistory(HistoryTab.Continue) },
            )
        }
        item(key = "settings") {
            ProfileEntryRow(
                icon = TmIcons.Settings,
                label = s.navSettings,
                badge = null,
                onClick = onSettings,
            )
        }
        item(key = "backup") {
            ProfileEntryRow(
                icon = TmIcons.Download,
                label = s.backupBackUp,
                badge = null,
                onClick = backupActions.backUp,
            )
        }
        item(key = "restore") {
            ProfileEntryRow(
                icon = TmIcons.Refresh,
                label = s.backupRestore,
                badge = null,
                onClick = backupActions.restore,
            )
        }
        if (updateVersion != null) {
            item(key = "update") {
                ProfileEntryRow(
                    icon = TmIcons.Refresh,
                    label = s.navUpdate,
                    badge = updateVersion,
                    ink = Tone.caution,
                    onClick = onUpdate,
                )
            }
        }
    }

    restorePlan?.let { plan ->
        com.tmplayer.ui.components.TvConfirm(
            title = s.backupRestoreTitle,
            message = com.tmplayer.data.BackupWords.restoreMessage(plan),
            detail = s.backupRestoreUntouched,
            confirmLabel = s.backupRestoreConfirm,
            destructive = false,
            onConfirm = {
                restorePlan = null
                backupActions.apply(plan.backup)
            },
            onDismiss = { restorePlan = null },
        )
    }

    continueMenu?.let { record ->
        TvMenu(
            title = record.title,
            subtitle = s.browseWatchedUpTo(s.formatter.clock(record.positionMs)),
            onDismiss = { continueMenu = null },
            actions = listOf(
                MenuAction(s.commonResume, TmIcons.Play, detail = s.browseResumeDetail) {
                    continueMenu = null
                    onResume(record)
                },
                MenuAction(s.gridPlayFromStart, TmIcons.Refresh) {
                    continueMenu = null
                    onRestart(record)
                },
                MenuAction(
                    label = s.gridMarkWatched,
                    icon = TmIcons.Check,
                    detail = s.browseMarkWatchedDetail,
                ) {
                    continueMenu = null
                    onMarkWatched(record)
                },
                MenuAction(
                    label = s.browseRemoveFromHistory,
                    icon = TmIcons.History,
                    detail = s.browseRemoveFromHistoryDetail,
                    destructive = true,
                ) {
                    continueMenu = null
                    historyRemoval.run({ s.browseRemovedFromHistory(it) }) { st, w ->
                        HistoryEdits.removeContinueCards(st, w, listOf(record))
                    }
                },
            ),
        )
    }

    detailStack.lastOrNull()?.let { (item, title) ->
        MediaDetailOpened(
            item = item,
            chatTitle = title,
            watched = watch.point(item),
            finished = watch.finished(item),
            onPlay = { onPlayItem(item, title) },
            onSetWatched = { onSetWatched(item, title, it) },
            onDownload = {
                scope.launch {
                    queueDownloads(context, SettingsStore(context), listOf(item)) { title }?.let(onMessage)
                }
            },
            onOpenChat = chats.firstOrNull { it.id == item.chatId }?.let { chat -> { onOpenChat(chat) } },
            onDismiss = { detailStack = emptyList() },
            onOpenItem = { other ->
                detailStack = detailStack + (other to (chats.firstOrNull { it.id == other.chatId }?.title ?: title))
            },
            onBack = if (detailStack.size > 1) ({ detailStack = detailStack.dropLast(1) }) else null,
        )
    }

    if (namingCollection) {
        com.tmplayer.player.BookmarkNoteSheet(
            title = s.detailNewCollection,
            initial = "",
            saveLabel = s.gridRenameSave,
            onSave = { typed ->
                // An empty collection: it stands in the store (and so in the detail page's own
                // list) at once, and Home draws no row for a collection holding nothing. A name
                // already taken, or the cap reached, saves nothing, as the detail page's path
                // refuses a collection it cannot hold.
                val name = typed.trim()
                if (name.isNotEmpty()) {
                    App.backgroundScope.launch {
                        val existing = settings.collectionsNow()
                        if (existing.size < com.tmplayer.data.Collections.MAX_COLLECTIONS &&
                            existing.none { it.name.equals(name, ignoreCase = true) }
                        ) {
                            settings.setCollections(
                                existing + com.tmplayer.data.VideoCollection(
                                    name.take(com.tmplayer.data.Collections.MAX_NAME),
                                    emptyList(),
                                ),
                            )
                        }
                    }
                }
                namingCollection = false
            },
            onDismiss = { namingCollection = false },
            onClose = { namingCollection = false },
            hint = s.detailCollectionName,
            maxLength = com.tmplayer.data.Collections.MAX_NAME,
        )
    }
}

/**
 * The account at the top of the page: the signed in Telegram account's picture and name, with
 * its @username under the name and its own phone number under that, where the account gives
 * one. A tap opens the account page inside Settings, where the accounts on this device are
 * switched and another is added.
 */
@Composable
private fun ProfileAccountHeader(account: Account?, onAccount: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onAccount)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(M3MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (account != null) {
                MediaPreview(
                    miniThumbnail = account.miniThumbnail,
                    thumbnailFileId = account.photoFileId,
                    fallbackLabel = account.name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                M3Icon(TmIcons.Account, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(34.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            M3Text(
                account?.name?.ifBlank { s.navYou } ?: s.navYou,
                style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val username = account?.username?.takeIf { it.isNotBlank() }
            if (username != null) {
                M3Text(
                    "@$username",
                    style = M3MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The account's own number under the name (owner, v1.0.14): only where the
            // account gives one; an account that returns none shows no line rather than
            // an invented one.
            val phoneNumber = account?.phoneNumber?.takeIf { it.isNotBlank() }
            if (phoneNumber != null) {
                M3Text(
                    phoneNumber,
                    style = M3MaterialTheme.typography.bodyMedium,
                    color = Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (account != null) {
            M3Icon(TmIcons.ChevronDown, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(22.dp))
        }
    }
}

/** A section's name over its row; with [onClick] the whole heading goes to that section's list. */
@Composable
private fun ProfileHeading(text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        M3Text(
            text,
            style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            M3Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** A wide card with an icon, a title and one line under it: Downloads on this page. */
@Composable
private fun ProfileWideCard(
    icon: ImageVector,
    title: String,
    detail: String,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            M3Icon(icon, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            M3Text(
                title,
                style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                M3Text(
                    badge,
                    style = M3MaterialTheme.typography.labelMedium,
                    color = Tone.onAccent,
                    modifier = Modifier.clip(CircleShape).background(Tone.accent).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            M3Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
        }
        M3Text(
            detail,
            style = M3MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.padding(start = 36.dp, top = 6.dp),
        )
    }
}

/**
 * The My List card: what Watch later is for, and a button that takes the viewer to Home to find
 * titles for it. The card itself opens the Watch later list, so the saved titles are one tap
 * away here as well as in the row under it.
 */
@Composable
private fun ProfileMyListCard(onBrowse: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(Tone.surfaceHigh)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            M3Icon(TmIcons.Clock, contentDescription = null, tint = Tone.text, modifier = Modifier.size(24.dp))
            M3Text(
                s.profileMyListTitle,
                style = M3MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        M3Text(
            s.profileMyListDetail,
            style = M3MaterialTheme.typography.bodyMedium,
            color = Tone.muted,
            modifier = Modifier.padding(start = 36.dp, top = 6.dp),
        )
        Row(
            Modifier
                .padding(start = 36.dp, top = 12.dp)
                .clip(CircleShape)
                .background(Tone.surface)
                .clickable(onClick = onBrowse)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            M3Text(
                s.profileBrowseMyList,
                style = M3MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
            )
        }
    }
}

/**
 * One Continue watching card: portrait art with a big round play button at its centre (a tap
 * anywhere on the art resumes the title where it stopped), the watched share as a bar under
 * the art, then an info button (the title's detail page) and an overflow menu carrying the
 * actions the History card offers for the same title.
 */
@Composable
private fun ProfileContinueCard(
    record: ResumeRecord,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    onMenu: () -> Unit,
) {
    val s = LocalStrings.current
    val fallback = remember(record) { record.toMediaItem() }
    val item = rememberRecordItem(record.chatId, record.messageId, fallback)
    val title = rememberVideoName(fallback)
    Column(Modifier.width(132.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Corner.Medium))
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            ProfileArt(item, Modifier.fillMaxSize())
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                M3Icon(TmIcons.PlaySolid, contentDescription = s.commonResume, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        PillProgress(record.fraction, Modifier.fillMaxWidth().padding(top = 8.dp))
        M3Text(
            title,
            style = M3MaterialTheme.typography.bodyMedium,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onInfo) {
                M3Icon(TmIcons.Info, contentDescription = s.detailOpenDetails, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = onMenu) {
                M3Icon(TmIcons.MoreVert, contentDescription = s.commonMoreOptions, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** A plain portrait card for a saved or finished title: its picture and its name under it. */
@Composable
private fun ProfilePosterCard(fallback: MediaItem, title: String?, onClick: () -> Unit) {
    val item = rememberRecordItem(fallback.chatId, fallback.messageId, fallback)
    val generated = rememberVideoName(fallback)
    val name = title ?: generated
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(Corner.Medium)),
        ) {
            ProfileArt(item, Modifier.fillMaxSize())
        }
        M3Text(
            name,
            style = M3MaterialTheme.typography.bodyMedium,
            color = Tone.text,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A card's picture: the title's own art where there is any, else its generated cover. */
@Composable
private fun ProfileArt(item: MediaItem, modifier: Modifier = Modifier) {
    val cover = rememberCover(item)
    CompositionLocalProvider(LocalOnlineArt provides true) {
        OnlineArt(item, modifier) {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize()) {
                GeneratedCover(cover, Modifier.fillMaxSize(), showTitle = false)
            }
        }
    }
}

/** One starred chat in the Favourites row: its picture and its name, a tap opens the chat. */
@Composable
private fun ProfileChatChip(chat: ChatSummary, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(M3MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            MediaPreview(
                miniThumbnail = chat.miniThumbnail,
                thumbnailFileId = chat.photoFileId,
                fallbackLabel = chat.title,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
        M3Text(
            chat.title,
            style = M3MaterialTheme.typography.bodySmall,
            color = Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** One plain row of the page's foot: an icon, a name, a count when there is one, a chevron. */
@Composable
private fun ProfileEntryRow(
    icon: ImageVector,
    label: String,
    badge: String?,
    ink: Color = Tone.text,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        M3Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        M3Text(
            label,
            style = M3MaterialTheme.typography.bodyLarge,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            M3Text(badge, style = M3MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 1)
        }
        M3Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
    }
}
