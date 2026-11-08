package com.tmplayer.ui.browse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A category page asked for from outside Home (owner, v1.0.17): the search page's
 * browse sections name the category (a catalog row's key, or "langpage-<code>" for
 * a language's merged page), the shell brings Home forward, and HomePane takes the
 * ask and opens the page. The shape of [ChannelPickRequest], kept apart because
 * Home is the only taker.
 */
object CategoryOpenRequest {
    private val _pending = MutableStateFlow<String?>(null)

    /** The category asked for and not yet opened, if any. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun ask(key: String) {
        _pending.value = key
    }

    /** The asked-for category, once; null after: Home opens it and takes it. */
    fun take(): String? = _pending.value.also { _pending.value = null }
}
