package com.tmplayer.player

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.fragment.app.FragmentActivity
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.FloatingWindow
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.MediaPreview
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.TMPlayerTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * The end screen rail (owner, v1.0.15, item 35): when a video ends and there is more to
 * offer, the dead end becomes Replay and Next episode first (in that focus order), then
 * "More from this chat" and the detail pages' own "More like this", each card playing
 * what it names. It is a window over the player in the episode list's shape
 * ([PlayerEpisodes]): full screen on a phone, a panel inside the overscan on a
 * television, and Back closes it back to the plain finished sheet, whose Watch again
 * keeps its place for anything this rail does not cover (a stream, a video nothing is
 * known about, a chat that could not be read).
 *
 * The activity gathers the state and keeps every action; this only draws it.
 */
class PlayerEndRail(
    activity: FragmentActivity,
    root: ViewGroup,
    private val actions: Actions,
    private val onClosed: () -> Unit,
) {

    /** What the rail can be pressed to do; the player owns all of it. */
    class Actions(
        /** Watch the finished video again from its first frame. */
        val replay: () -> Unit,
        /** Play the series' next episode; only wired while [State.next] is there. */
        val next: () -> Unit,
        /** Play the card's video, stepping to it as the player steps anywhere. */
        val open: (MediaItem) -> Unit,
    )

    /** One showing of the rail, gathered by the player when a video ends. */
    class State(
        /** The next episode, when the series has one: its line stands second. */
        val next: MediaItem?,
        /** More of the chat the video came from, the video itself left out. */
        val fromChat: List<MediaItem>,
        /** The finished title's match and its extras, for More like this; null when unmatched. */
        val self: MetaInfo?,
        val extras: MetaExtras?,
    ) {
        /** Anything beyond Replay worth opening a window for. */
        val hasMore: Boolean get() = next != null || fromChat.isNotEmpty() || (self != null && extras != null)
    }

    private val shown = mutableStateOf<State?>(null)

    val isOpen: Boolean get() = shown.value != null

    init {
        val view = ComposeView(activity).apply {
            // Not named Content: inside apply that resolves to ComposeView.Content (see PlayerMorePanel).
            setContent { TMPlayerTheme { RailContent() } }
        }
        root.addView(view, ViewGroup.LayoutParams(0, 0))
    }

    fun open(state: State) {
        shown.value = state
    }

    fun close() {
        if (shown.value == null) return
        shown.value = null
        onClosed()
    }

    @Composable
    private fun RailContent() {
        val state = shown.value ?: return
        val s = LocalStrings.current
        val touch = isTouch()
        val screen = LocalConfiguration.current
        val phone = touch && minOf(screen.screenWidthDp, screen.screenHeightDp) < PHONE_BELOW
        FloatingWindow(onDismiss = ::close, immersive = phone) {
            Column(
                Modifier
                    .then(if (phone) Modifier.fillMaxSize() else Modifier.width(RAIL_WIDTH))
                    .background(FloatingTone.dialog)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                    .padding(horizontal = if (phone) 16.dp else Tv.SafeH, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(s.playerTheEnd, style = MaterialTheme.typography.titleLarge, color = Tone.text)
                // Replay first, Next episode second: the two ways onward from an ending,
                // in the order the remote meets them.
                val firstFocus = remember { FocusRequester() }
                ChoiceList {
                    ChoiceLine(
                        title = s.playerWatchAgain,
                        icon = TmIcons.Refresh,
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { close(); actions.replay() }
                    state.next?.let { next ->
                        ChoiceLine(title = s.playerNextEpisode, icon = TmIcons.SkipNext) { close(); actions.next() }
                    }
                }
                FocusOnOpen(firstFocus)
                if (state.fromChat.isNotEmpty()) {
                    Text(s.playerMoreFromChat, style = MaterialTheme.typography.titleSmall, color = Tone.text)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(state.fromChat, key = { it.id }) { item ->
                            EndCard(item) { actions.open(item) }
                        }
                    }
                }
                if (state.self != null && state.extras != null) {
                    MoreLikeThisRow(state.self, state.extras, onOpen = { item -> close(); actions.open(item) })
                }
            }
        }
    }

    /** One "More from this chat" card: the video's own picture and its name, a step to it. */
    @Composable
    private fun EndCard(item: MediaItem, onClick: () -> Unit) {
        Column(
            Modifier
                .width(if (isTouch()) 132.dp else 176.dp)
                .androidx.compose.foundation.clickable(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MediaPreview(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                fallbackLabel = item.title,
                modifier = Modifier.fillMaxWidth().androidx.compose.foundation.layout.aspectRatio(16f / 9f),
            )
            Text(
                item.title,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.text,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }

    private companion object {
        /** A phone's smaller side below this is a phone, as the episode list reads it. */
        const val PHONE_BELOW = 600
        val RAIL_WIDTH = 880.dp
    }
}
