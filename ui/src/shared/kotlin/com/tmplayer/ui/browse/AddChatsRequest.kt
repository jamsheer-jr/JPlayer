package com.tmplayer.ui.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Add chats" from a place that cannot open the picker itself (HX27): an empty Chats page, an
 * empty Home, the foot of a search that found nothing because few chats are added. The asking
 * screen leaves the request here and whichever shell is up opens the picker and takes it, so it
 * opens once and each platform keeps its own way of showing a full screen.
 *
 * The same shape as [SearchRequest], for the same reason: a state the screens draw stays a value
 * that compares equal to itself, so no lambda travels through it.
 */
object AddChatsRequest {
    private val _pending = MutableStateFlow(false)

    /** Whether the picker has been asked for and not yet opened. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun ask() {
        _pending.value = true
    }

    /** True once for an asked-for picker, false after: the shell opens it and takes it. */
    fun take(): Boolean = _pending.value.also { _pending.value = false }
}

/** Which channel assignment a [ChannelPickRequest] is for. */
enum class ChannelPickTarget { Movies, Shows }

/**
 * "Movie channels" or "Show channels" from Settings' Home page (item 3, v1.0.16):
 * the same ask-and-take shape as [AddChatsRequest], carrying which assignment the
 * picker is for, so the shell opens the one picker screen with its view model aimed
 * at that assignment's stored list.
 */
object ChannelPickRequest {
    private val _pending = MutableStateFlow<ChannelPickTarget?>(null)

    /** The assignment the picker has been asked for and not yet opened for. */
    val pending: StateFlow<ChannelPickTarget?> = _pending.asStateFlow()

    fun ask(target: ChannelPickTarget) {
        _pending.value = target
    }

    /** The asked-for target, once; null after: the shell opens the picker and takes it. */
    fun take(): ChannelPickTarget? = _pending.value.also { _pending.value = null }
}
