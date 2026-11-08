package com.tmplayer.player

import com.tmplayer.data.MediaItem
import com.tmplayer.data.UpNextQueue as CoreUpNextQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Up Next queue's home on this device (item 33, v1.0.15): one list for the whole app,
 * whoever's profile is active, because it is a plan for this sitting's watching rather
 * than a list anybody keeps. It lives in memory only: it survives screens, players and
 * chats within the run, and a restart starts it empty, which the item accepts. The order
 * and the edits are the pure [CoreUpNextQueue]'s; this holder is only the state.
 */
object UpNextQueue {

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())

    /** The queue as it stands, head first: what plays next is at the front. */
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    fun playNext(item: MediaItem) {
        _items.value = CoreUpNextQueue.playNext(_items.value, item)
    }

    fun add(item: MediaItem) {
        _items.value = CoreUpNextQueue.add(_items.value, item)
    }

    /** Takes the head off the queue for the player to start, or null when it is empty. */
    fun takeNext(): MediaItem? {
        val (next, rest) = CoreUpNextQueue.takeNext(_items.value)
        if (next != null) _items.value = rest
        return next
    }

    fun remove(id: String) {
        _items.value = CoreUpNextQueue.remove(_items.value, id)
    }

    fun move(from: Int, to: Int) {
        _items.value = CoreUpNextQueue.move(_items.value, from, to)
    }

    fun clear() {
        _items.value = emptyList()
    }
}
