package com.tmplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.App
import com.tmplayer.data.HiddenStreams
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.StreamLink
import com.tmplayer.ui.components.MenuAction
import com.tmplayer.ui.components.TvMenu
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone

/**
 * The Live Guide (item 15, v1.0.16): Live on a page of its own. Every stream stands as
 * a wide 16:9 card, the same card the Live row draws, full width: the stream's own
 * name, its channel small under it, its badges. A press plays it; a hold opens the
 * row's own menu (Save to Downloads for a file link, side by side for a live one,
 * Remove from Live). The page reads the posts through the row's own holder
 * ([rememberLiveStreams]), widened window, reconnect retry and refresh asks included,
 * and its Refresh is a forced ask on that same path. Reached from the Live row's
 * heading on Home and from the Chats Live heading; on a television the cards stack
 * the same way and the remote walks them in order.
 */
@Composable
internal fun LiveGuidePage(
    onBack: () -> Unit,
    onPlayStream: (StreamLink) -> Unit,
    onPlayStreamPair: ((StreamLink, StreamLink) -> Unit)? = null,
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val live = rememberLiveStreams()
    val settings = remember { SettingsStore(context) }
    val hidden by remember(settings) { settings.hiddenStreams }.collectAsState(initial = emptySet())
    val seen by remember(settings) { settings.seenStreams }.collectAsState(initial = emptySet())
    var menuFor by remember { mutableStateOf<StreamLink?>(null) }
    var pairFor by remember { mutableStateOf<StreamLink?>(null) }
    val shown = HiddenStreams.visible(live.streams, hidden)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize().background(Tone.background)) {
        Row(
            Modifier.fillMaxWidth().padding(top = statusTop, start = 4.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                M3Icon(TmIcons.ArrowBack, contentDescription = s.commonBack, tint = Tone.text, modifier = Modifier.size(24.dp))
            }
            M3Text(
                s.homeLiveTitle,
                style = M3MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { LiveRefresh.ask(forced = true) }) {
                M3Text(s.commonRefresh, color = Tone.accent)
            }
        }
        if (shown.isEmpty()) {
            M3Text(
                s.homeLiveEmpty,
                style = M3MaterialTheme.typography.bodyMedium,
                color = Tone.muted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shown, key = { if (it.messageId != 0L) "${it.chatId}:${it.messageId}" else it.url }) { link ->
                    StreamCard(
                        link,
                        isNew = !com.tmplayer.data.SeenStreams.seen(seen, link),
                        wide = true,
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
                    live.drop(link)
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
