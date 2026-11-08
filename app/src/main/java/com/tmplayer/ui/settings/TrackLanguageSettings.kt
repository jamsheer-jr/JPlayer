package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.player.SubtitleMode
import com.tmplayer.player.TrackLanguages
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings

/** Which of the app-wide track settings is open over the page. */
enum class TrackLanguagePicker { Audio, Subtitle, Mode, Order }

/**
 * Settings, the preferred audio language, the preferred subtitle language and when subtitles come
 * on, each as a [ChoiceSheet] of radio lines, so a remote walks them the way it walks every other
 * picker. A pick is saved and closes the sheet; the note says where these sit in the order, since
 * a series that remembers its own choice is the first thing a viewer will meet that ignores them.
 */
@Composable
fun TrackLanguageSheet(
    picker: TrackLanguagePicker,
    prefs: TrackPreferences,
    onChange: (TrackPreferences) -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val current = remember { FocusRequester() }
    // Sorted by name in the UI language, so read again whenever that changes.
    val languages = remember(s.messages.tag) { TrackLanguages.choices() }
    fun pick(next: TrackPreferences) {
        onChange(next)
        onClose()
    }
    fun Modifier.landing(selected: Boolean) = if (selected) focusRequester(current) else this
    val title = when (picker) {
        TrackLanguagePicker.Audio -> s.settingsAudioLanguage
        TrackLanguagePicker.Subtitle -> s.settingsSubtitleLanguage
        TrackLanguagePicker.Mode -> s.settingsSubtitleMode
        TrackLanguagePicker.Order -> s.settingsSubtitleOrder
    }
    ChoiceSheet(
        title = title,
        note = if (picker == TrackLanguagePicker.Order) s.settingsSubtitleOrderDetail else s.settingsTrackLanguagesNote,
        onDismiss = onClose, onClose = onClose,
    ) {
        ChoiceList {
            when (picker) {
                TrackLanguagePicker.Audio -> {
                    val auto = prefs.audioLanguage == null
                    ChoiceLine(
                        title = s.settingsLanguageAutomatic,
                        detail = s.settingsAudioLanguageAuto,
                        selected = auto,
                        modifier = Modifier.landing(auto),
                    ) { pick(prefs.copy(audioLanguage = null)) }
                    languages.forEach { code ->
                        val selected = prefs.audioLanguage == code
                        ChoiceLine(title = TrackLanguages.name(code), selected = selected, modifier = Modifier.landing(selected)) {
                            pick(prefs.copy(audioLanguage = code))
                        }
                    }
                }
                TrackLanguagePicker.Subtitle -> {
                    val auto = prefs.subtitleLanguage == null && !prefs.subtitlesOff
                    ChoiceLine(
                        title = s.settingsLanguageAutomatic,
                        detail = s.settingsSubtitleLanguageAuto,
                        selected = auto,
                        modifier = Modifier.landing(auto),
                    ) { pick(prefs.copy(subtitleLanguage = null, subtitlesOff = false)) }
                    ChoiceLine(
                        title = s.commonOff,
                        detail = s.settingsSubtitleLanguageOff,
                        selected = prefs.subtitlesOff,
                        modifier = Modifier.landing(prefs.subtitlesOff),
                    ) { pick(prefs.copy(subtitleLanguage = null, subtitlesOff = true)) }
                    languages.forEach { code ->
                        val selected = !prefs.subtitlesOff && prefs.subtitleLanguage == code
                        ChoiceLine(title = TrackLanguages.name(code), selected = selected, modifier = Modifier.landing(selected)) {
                            pick(prefs.copy(subtitleLanguage = code, subtitlesOff = false))
                        }
                    }
                }
                TrackLanguagePicker.Mode -> SubtitleMode.entries.forEach { mode ->
                    val selected = prefs.mode == mode
                    ChoiceLine(title = mode.label, detail = mode.detail, selected = selected, modifier = Modifier.landing(selected)) {
                        pick(prefs.copy(mode = mode))
                    }
                }
                // The order itself (item 14, v1.0.16): every language the app knows,
                // the chosen ones ticked with their place. A tap takes a language out,
                // or puts it in at the end, so the order is built by choosing; the
                // sheet stays open, since an order is more than one tap.
                TrackLanguagePicker.Order -> {
                    ChoiceLine(
                        title = s.settingsSubtitleOrderReset,
                        selected = prefs.subtitleOrder == TrackPreferences.DEFAULT_ORDER,
                    ) { onChange(prefs.copy(subtitleOrder = TrackPreferences.DEFAULT_ORDER)) }
                    languages.forEach { code ->
                        val place = prefs.subtitleOrder.indexOf(code)
                        ChoiceLine(
                            title = TrackLanguages.name(code),
                            detail = if (place >= 0) (place + 1).toString() else null,
                            selected = place >= 0,
                        ) {
                            onChange(
                                prefs.copy(
                                    subtitleOrder = if (place >= 0) prefs.subtitleOrder - code else prefs.subtitleOrder + code,
                                ),
                            )
                        }
                    }
                }
            }
        }
        FocusOnOpen(current)
    }
}
