package com.tmplayer.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.data.MediaItem
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.flow.StateFlow

/*
 * The Up Next queue in the player (item 33, v1.0.15), in the pickers' panel as everything
 * else the player asks is: the list in the order it will play, and one video's sheet to
 * play it now, move it, or take it off. The queue itself is [UpNextQueue], the device's
 * one list; these sheets only show it and report the choice.
 */

/** What the queue sheets can do, all of it the player's: the sheets only show and report. */
class QueueSheetActions(
    /** The queue as it stands, following edits while the sheet is up. */
    val items: StateFlow<List<MediaItem>>,
    /** Plays [item] now, stepping to it the way the player steps to any video. */
    val play: (MediaItem) -> Unit,
    val remove: (MediaItem) -> Unit,
    val move: (from: Int, to: Int) -> Unit,
    val clear: () -> Unit,
)

/** The queue in playing order; a line opens what can be done with that video. */
@Composable
internal fun QueueSheet(
    items: List<MediaItem>,
    onPick: (MediaItem) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    ChoiceSheet(
        title = s.playerUpNext,
        note = if (items.isEmpty()) s.playerUpNextEmpty else null,
        onDismiss = onDismiss,
        onClose = onClose,
        ignoreRelease = !touch,
        fullScreen = true,
    ) {
        ChoiceList {
            items.forEachIndexed { index, item ->
                ChoiceLine(
                    title = item.title,
                    icon = TmIcons.Play,
                    modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                ) { onPick(item) }
            }
            if (items.size > 1) {
                ChoiceLine(title = s.playerUpNextClear, icon = TmIcons.Delete) { onClear() }
            }
        }
        FocusOnOpen(focus)
    }
}

/** One queued video: play it now, move it up or down the order, or take it off. */
@Composable
internal fun QueueItemSheet(
    item: MediaItem,
    first: Boolean,
    last: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val touch = isTouch()
    val focus = remember { FocusRequester() }
    ChoiceSheet(
        title = item.title,
        onDismiss = onDismiss,
        onClose = onClose,
        ignoreRelease = !touch,
        fullScreen = true,
    ) {
        ChoiceList {
            ChoiceLine(s.commonPlay, icon = TmIcons.Play, modifier = Modifier.focusRequester(focus)) { onPlay() }
            if (!first) ChoiceLine(s.playerUpNextMoveUp, icon = TmIcons.ChevronUp) { onMoveUp() }
            if (!last) ChoiceLine(s.playerUpNextMoveDown, icon = TmIcons.ChevronDown) { onMoveDown() }
            ChoiceLine(s.playerUpNextRemove, icon = TmIcons.Delete) { onRemove() }
        }
        FocusOnOpen(focus)
    }
}
