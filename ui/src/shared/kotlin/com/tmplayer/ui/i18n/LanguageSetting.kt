package com.tmplayer.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Updates
import com.tmplayer.data.WhatsNew
import com.tmplayer.data.points
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Strings
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.onboarding.Onboarding
import com.tmplayer.ui.onboarding.systemLanguageName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The Settings language row's second line: the language picked, named in itself, or "System
 * default" with the language that currently resolves to.
 */
fun Strings.languageRowDetail(saved: String): String =
    if (saved.isBlank()) {
        settingsLanguageDetailSystem(systemLanguageName() ?: Languages.find(Languages.ENGLISH)!!.name)
    } else if (saved.equals(Languages.PSEUDO, ignoreCase = true)) {
        Onboarding.PSEUDO_NAME
    } else {
        Languages.find(saved)?.name ?: saved
    }

/**
 * The language in use, named in itself, for a one-line summary such as Settings' Appearance row
 * ("Dark · English"): the picked language, or the one "System default" resolves to, without the
 * "System default" wording, which read oddly beside the theme.
 */
fun languageShortName(saved: String): String = when {
    saved.isBlank() -> systemLanguageName() ?: Languages.find(Languages.ENGLISH)?.name ?: "English"
    saved.equals(Languages.PSEUDO, ignoreCase = true) -> Onboarding.PSEUDO_NAME
    else -> Languages.find(saved)?.name ?: saved
}

/**
 * The one-time "Now in Español" card's state, the same on every device: [language] is the tag
 * to announce, or null when there is nothing to say, and [dismiss] remembers it was said. See
 * [LanguageNotice] for the rule.
 */
class LanguageNoticeState internal constructor(
    val language: String?,
    val dismiss: () -> Unit,
)

@Composable
fun rememberLanguageNotice(settings: SettingsStore): LanguageNoticeState {
    val scope = rememberCoroutineScope()
    val messages by Translator.active.collectAsState()
    // Null until read, so the card cannot flash up for the frame before the stored values arrive.
    val saved by settings.language.collectAsState(initial = null)
    val announced by settings.languageAnnounced.collectAsState(initial = null)
    val active = messages.tag
    val show = saved != null && announced != null &&
        LanguageNotice.shouldShow(saved!!, active, announced!!, Translator.hasCatalog(active))
    return LanguageNoticeState(
        language = if (show) active else null,
        dismiss = { scope.launch { settings.setLanguageAnnounced(active) } },
    )
}

/**
 * Whether the "What's new" sheet is up, and what it says. Decided once per launch from the
 * last version this install opened and [installed]; the version is remembered as soon as it
 * is read, so a crash with the sheet open does not bring it back. [hold] keeps it waiting
 * (behind the tour, or while signed out).
 *
 * The sheet's words are the update feed's own change list for the version just installed
 * (owner, v1.0.14), read from [Updates.lastKnown]: nothing is bundled. So an update's
 * decision waits for the feed to name this very version: the launch check may still be on
 * its way, and the scheduler's throttle can skip that check entirely, in which case this
 * asks once itself. A launch whose feed never answers decides nothing and remembers
 * nothing, so a later launch can still show the sheet.
 */
class WhatsNewState internal constructor() {
    var showing by mutableStateOf(false)

    /** The version the sheet names, and the feed's change list it lists. */
    var version by mutableStateOf("")
    var points by mutableStateOf<List<String>>(emptyList())
}

@Composable
fun rememberWhatsNew(settings: SettingsStore, installed: String, hold: Boolean = false): WhatsNewState {
    val state = remember { WhatsNewState() }
    var decided by remember { mutableStateOf(false) }
    LaunchedEffect(hold) {
        if (hold || decided) return@LaunchedEffect
        val lastSeen = runCatching { settings.lastSeenVersion.first() }.getOrNull() ?: return@LaunchedEffect
        if (WhatsNew.release(lastSeen) == null) {
            // A fresh install, or one from before this existed: remember and show nothing,
            // settled by the pure rules without the feed.
            decided = true
            val launch = runCatching { WhatsNew.onLaunch(lastSeen, installed, installed) }.getOrNull()
                ?: return@LaunchedEffect
            launch.remember?.let { runCatching { settings.setLastSeenVersion(it) } }
            return@LaunchedEffect
        }
        val wanted = WhatsNew.release(installed) ?: return@LaunchedEffect
        var release = Updates.lastKnown?.takeIf { it.version == wanted }
        if (release == null) {
            // The launch check may still be fetching: give it a little while first.
            val deadline = System.currentTimeMillis() + 15_000L
            while (release == null && System.currentTimeMillis() < deadline) {
                delay(500)
                release = Updates.lastKnown?.takeIf { it.version == wanted }
            }
        }
        if (release == null) {
            // The scheduler's throttle skipped this launch's check: ask once directly.
            runCatching { Updates.check(quiet = true) }
            release = Updates.lastKnown?.takeIf { it.version == wanted }
        }
        val feed = release ?: return@LaunchedEffect
        decided = true
        val launch = runCatching { WhatsNew.onLaunch(lastSeen, installed, feed.version) }.getOrNull()
            ?: return@LaunchedEffect
        launch.remember?.let { runCatching { settings.setLastSeenVersion(it) } }
        if (launch.show && feed.points.isNotEmpty()) {
            state.version = feed.version
            state.points = feed.points
            state.showing = true
        }
    }
    return state
}
