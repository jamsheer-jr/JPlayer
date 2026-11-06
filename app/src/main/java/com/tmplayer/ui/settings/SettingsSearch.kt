package com.tmplayer.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.tmplayer.R
import com.tmplayer.data.Notice
import com.tmplayer.data.Updates
import com.tmplayer.i18n.Strings
import com.tmplayer.ui.about.About
import com.tmplayer.ui.auth.PaneField
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/**
 * One findable row of Settings (v1.0.11): the page it lives on and the words it answers to.
 *
 * [title] is the row's own title in the viewer's language, read from the same catalog the row
 * itself is drawn from, so a renamed row is found by its new name without this list changing.
 * [also] carries more of the row's own words where a subtitle holds the word a viewer would
 * search by (the cam and rip row's subtitle names HDCam and PreDVD, which its title does not).
 * [key] names the row for its landing: SettingsScreen keeps a JumpTarget under most keys, and
 * choosing a result opens the page and arrives on that target; a key with no target (a custom
 * row with no modifier to carry one, a row drawn by a section in another file) lands on the
 * page itself, at its top.
 */
data class SettingsSearchEntry(
    val key: String,
    val page: SettingsPage,
    val title: String,
    val also: String = "",
)

/**
 * The matching, kept pure so it can be tested without a screen: a query's words must all be
 * found, and a row whose title holds them outranks one found only by its subtitle's words,
 * which outranks one found only by its page's name. Within a rank the index order stands, so
 * the pages answer in the order Settings lists them.
 */
object SettingsSearch {

    const val MAX_RESULTS = 20

    fun results(
        entries: List<SettingsSearchEntry>,
        pageTitle: (SettingsPage) -> String,
        query: String,
    ): List<SettingsSearchEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val whole = words.joinToString(" ")
        return entries.mapNotNull { entry ->
            val title = entry.title.lowercase()
            val also = entry.also.lowercase()
            val page = pageTitle(entry.page).lowercase()
            val score = when {
                whole in title -> 3
                words.all { it in title } -> 2
                words.all { it in title || it in also } -> 1
                words.all { it in title || it in also || it in page } -> 0
                else -> return@mapNotNull null
            }
            entry to score
        }.sortedByDescending { it.second }.take(MAX_RESULTS).map { it.first }
    }
}

/**
 * Every row Settings draws in its own screen, in the order the pages and their rows come.
 * Rows drawn by a section living in another file (the online subtitles and the posters and
 * details sections) are listed with their page: the posters and details rows land on that
 * section's own jump target, the online subtitles rows land on their page. Rows whose wording
 * is the viewer's own (an account's name) or a value (the update a build is offered) are not
 * rows of wording and are left out.
 */
fun settingsSearchEntries(s: Strings): List<SettingsSearchEntry> = buildList {
    fun add(key: String, page: SettingsPage, title: String, also: String = "") {
        add(SettingsSearchEntry(key, page, title, also))
    }

    // ---- Appearance ----------------------------------------------------------------------
    add("appearance.theme", SettingsPage.Appearance, s.settingsTheme)
    add("appearance.accent", SettingsPage.Appearance, s.settingsAccent)
    add("appearance.pure_black", SettingsPage.Appearance, s.settingsPureBlack)
    add("appearance.typeface", SettingsPage.Appearance, s.settingsTypeface)
    add("appearance.high_contrast", SettingsPage.Appearance, s.settingsHighContrast)
    add("appearance.language", SettingsPage.Appearance, s.settingsLanguage)
    add("appearance.interface_size", SettingsPage.Appearance, s.interfaceSize)
    add(
        "appearance.lighter_pictures", SettingsPage.Appearance, s.settingsLighterPictures,
        s.settingsLighterPicturesOn + " " + s.settingsLighterPicturesOff,
    )
    add(
        "appearance.reduce_motion", SettingsPage.Appearance, s.settingsReduceMotion,
        s.settingsReduceMotionOn + " " + s.settingsReduceMotionOff,
    )
    add(
        "appearance.defaults", SettingsPage.Appearance, s.settingsAppearanceDefaults,
        s.settingsAppearanceUndo + " " + s.settingsAppearanceDefaultsDetail,
    )
    add(
        "appearance.wallpaper_colours", SettingsPage.Appearance, s.settingsWallpaperColours,
        s.settingsWallpaperColoursOn + " " + s.settingsWallpaperColoursOff,
    )

    // ---- Playback ------------------------------------------------------------------------
    add(
        "playback.autoplay", SettingsPage.Playback, s.settingsAutoplay,
        s.settingsAutoplayOn + " " + s.settingsAutoplayOff,
    )
    add("playback.decoder", SettingsPage.Playback, s.settingsDecoder)
    add("playback.detail_first", SettingsPage.Playback, s.settingsDetailFirst)
    add(
        "playback.hide_rip_tags", SettingsPage.Playback, s.settingsHideRipTags,
        s.settingsHideRipTagsOn + " " + s.settingsHideRipTagsOff,
    )
    add("playback.download_first", SettingsPage.Playback, s.settingsDownloadFirst)
    add(
        "playback.downmix", SettingsPage.Playback, s.settingsDownmix,
        s.settingsDownmixOn + " " + s.settingsDownmixOff,
    )
    add(
        "playback.even_volume", SettingsPage.Playback, s.playerEvenVolume,
        s.settingsEvenVolumeOn + " " + s.settingsEvenVolumeOff,
    )
    add(
        "playback.background_audio", SettingsPage.Playback, s.settingsBackgroundAudio,
        s.settingsBackgroundAudioOn + " " + s.settingsBackgroundAudioOff,
    )
    add(
        "playback.pip_on_leave", SettingsPage.Playback, s.settingsPictureInPictureLeave,
        s.settingsPictureInPictureLeaveOn + " " + s.settingsPictureInPictureLeaveOff,
    )
    add("playback.audio_language", SettingsPage.Playback, s.settingsAudioLanguage)
    add("playback.trickplay", SettingsPage.Playback, s.settingsTrickplay)
    add("playback.controls_timeout", SettingsPage.Playback, s.settingsControlsTimeout, s.settingsControlsTimeoutDetail)
    add("playback.resume_rewind", SettingsPage.Playback, s.settingsResumeRewind, s.settingsResumeRewindDetail)

    // ---- Subtitles -----------------------------------------------------------------------
    add("subtitles.style", SettingsPage.Subtitles, s.settingsSubtitleStyle)
    add("subtitles.mode", SettingsPage.Subtitles, s.settingsSubtitleMode)
    add("subtitles.language", SettingsPage.Subtitles, s.settingsSubtitleLanguage)
    add("subtitles.learnt", SettingsPage.Subtitles, s.settingsLearntLanguages)
    // The online subtitles section (OnlineSubtitlesSettings): its page, no row targets.
    add("subtitles.msone", SettingsPage.Subtitles, s.msoneTitle)
    add("subtitles.online", SettingsPage.Subtitles, s.onlineTitle)
    add("subtitles.online_sign_in", SettingsPage.Subtitles, s.onlineSignIn)
    add("subtitles.online_sign_out", SettingsPage.Subtitles, s.onlineSignOut)
    add("subtitles.online_machine", SettingsPage.Subtitles, s.onlineMachineToggle)
    add("subtitles.online_translate", SettingsPage.Subtitles, s.onlineTranslateTitle)

    // ---- Gestures (a phone's page) --------------------------------------------------------
    add("gestures.haptics", SettingsPage.Gestures, s.settingsHaptics, s.settingsHapticsDetail)
    add("gestures.tap_plays", SettingsPage.Gestures, s.settingsTapPlays)
    add("gestures.double_tap", SettingsPage.Gestures, s.settingsDoubleTap)
    add("gestures.double_tap_pause", SettingsPage.Gestures, s.settingsDoubleTapPause)
    add("gestures.hold_speed", SettingsPage.Gestures, s.settingsHoldSpeed)
    add("gestures.seek", SettingsPage.Gestures, s.settingsSeekGesture)
    add("gestures.brightness", SettingsPage.Gestures, s.settingsBrightnessGesture)
    add("gestures.volume", SettingsPage.Gestures, s.settingsVolumeGesture)

    // ---- Library --------------------------------------------------------------------------
    add("library.size_limits", SettingsPage.Library, s.settingsSizeLimits)
    add("library.open_last_chat", SettingsPage.Library, s.settingsOpenLastChat)
    add("library.forget_last_chat", SettingsPage.Library, s.settingsForgetLastChat, s.settingsForgetLastChatDetail)
    add(
        "library.remember_searches", SettingsPage.Library, s.settingsRememberSearches,
        s.settingsRememberSearchesOn + " " + s.settingsRememberSearchesOff,
    )
    add(
        "library.resume_sync", SettingsPage.Library, s.settingsResumeSync,
        s.settingsResumeSyncOn + " " + s.settingsResumeSyncOff,
    )
    add("library.add_chats", SettingsPage.Library, s.pickerRowTitle)
    add("library.clear_favourites", SettingsPage.Library, s.settingsClearFavourites)
    add("library.clear_history", SettingsPage.Library, s.browseClearHistory)
    // The posters and details section (OnlineMetadataSettings): its rows land on the
    // section's own jump target, the one Setup choices' Posters step already arrives on.
    add(
        "library.metadata_toggle", SettingsPage.Library, s.metadataToggle,
        s.metadataToggleOn + " " + s.metadataToggleOff,
    )
    add("library.metadata_skip_times", SettingsPage.Library, s.metadataSkipTimes)
    add("library.metadata_key", SettingsPage.Library, s.metadataKey)
    add("library.metadata_omdb_key", SettingsPage.Library, s.metadataOmdbKey)
    add("library.metadata_clear_cache", SettingsPage.Library, s.metadataClearCache)

    // ---- Home -----------------------------------------------------------------------------
    add(
        "home.carousel", SettingsPage.Home, s.settingsHomeCarousel,
        s.settingsHomeCarouselOn + " " + s.settingsHomeCarouselOff + " " + s.settingsHomeCarouselNoOnline,
    )
    add(
        "home.continue", SettingsPage.Home, s.settingsHomeContinue,
        s.settingsHomeContinueOn + " " + s.settingsHomeContinueOff,
    )
    add(
        "home.recent", SettingsPage.Home, s.settingsHomeRecent,
        s.settingsHomeRecentOn + " " + s.settingsHomeRecentOff,
    )
    add(
        "home.starred_rows", SettingsPage.Home, s.settingsHomeStarredRows,
        s.settingsHomeStarredRowsOn + " " + s.settingsHomeStarredRowsOff,
    )
    add(
        "home.catalog", SettingsPage.Home, s.settingsHomeCatalog,
        s.settingsHomeCatalogOn + " " + s.settingsHomeCatalogOff,
    )

    // ---- TV home --------------------------------------------------------------------------
    add(
        "tvhome.watch_next", SettingsPage.TvHome, s.settingsWatchNext,
        s.settingsWatchNextOn + " " + s.settingsWatchNextOff,
    )
    add(
        "tvhome.channels", SettingsPage.TvHome, s.settingsTvChannels,
        s.settingsTvChannelsOn + " " + s.settingsTvChannelsOff,
    )

    // ---- Notifications ----------------------------------------------------------------------
    add("notice.downloads", SettingsPage.Notifications, s.settingsAlertDownloads)
    add("notice.new_episodes", SettingsPage.Notifications, s.settingsAlertEpisodes)
    add("notice.new_videos", SettingsPage.Notifications, s.settingsNewVideos)
    add("notice.missing_pieces", SettingsPage.Notifications, s.settingsAlertPieces)
    add("notice.resume", SettingsPage.Notifications, s.settingsResumeNotice)
    if (Updates.enabled) {
        add("notice.update_ready", SettingsPage.Notifications, s.settingsAlertUpdate)
    }

    // ---- Storage ----------------------------------------------------------------------------
    add("storage.free_up", SettingsPage.Storage, s.downloadsFreeUpSpace)
    add("storage.location", SettingsPage.Storage, s.storageLocation)
    add("storage.remove_after_watching", SettingsPage.Storage, s.downloadsRemoveAfterWatching)
    add(
        "storage.low_storage", SettingsPage.Storage, s.settingsLowStorage,
        s.settingsLowStorageOn + " " + s.settingsLowStorageOff,
    )
    add("notice.low_storage", SettingsPage.Storage, s.settingsLowStorageAlert)
    add(
        "storage.wifi_only", SettingsPage.Storage, s.settingsWifiOnly,
        s.settingsWifiOnlyOn + " " + s.settingsWifiOnlyOff,
    )
    add(
        "storage.save_mobile_data", SettingsPage.Storage, s.settingsSaveMobileData,
        s.settingsSaveMobileDataOn + " " + s.settingsSaveMobileDataOff,
    )

    // ---- Account ------------------------------------------------------------------------------
    add("account.set_pin", SettingsPage.Account, s.lockSetPin)
    add("account.change_pin", SettingsPage.Account, s.lockChangePin)
    add("account.lock_on_open", SettingsPage.Account, s.lockOnOpen)
    add("account.kids", SettingsPage.Account, s.lockKids)
    add("account.allowed_chats", SettingsPage.Account, s.lockAllowedChats)
    add("account.remove_pin", SettingsPage.Account, s.lockRemovePin)
    add("account.devices", SettingsPage.Account, s.settingsDevices)
    add("account.add", SettingsPage.Account, s.settingsAccountAdd)
    add("account.sign_out", SettingsPage.Account, s.settingsSignOut)

    // ---- About ----------------------------------------------------------------------------------
    add("about.check_updates", SettingsPage.About, s.settingsCheckUpdates)
    add("about.update_notify", SettingsPage.About, s.settingsUpdateNotify, s.settingsUpdateNotifyDetail)
    add("about.test_versions", SettingsPage.About, s.settingsTestVersions)
    add("about.auto_install", SettingsPage.About, s.settingsAutoInstall)
    add("about.backup", SettingsPage.About, s.backupBackUp)
    add("about.restore", SettingsPage.About, s.backupRestore)
    add("about.proxy", SettingsPage.About, s.proxyTitle)
    add("about.logs", SettingsPage.About, s.logsSave)
    add("about.name_report", SettingsPage.About, s.logsNameReport)
    add(
        "about.crash_reports", SettingsPage.About, s.settingsCrashReports,
        s.settingsCrashReportsOn + " " + s.settingsCrashReportsOff,
    )
    add("about.support", SettingsPage.About, About.SUPPORT_TITLE, About.SUPPORT_THANKS_TITLE)
    add("about.about", SettingsPage.About, s.settingsAbout)
}

/**
 * The keys whose rows carry a landing target in SettingsScreen: choosing a result with one
 * of these keys opens its page and arrives on the row itself. The keys left out are the rows
 * with no modifier to carry a target (the theme picker and the accent swatches, both at the
 * head of their page), the online subtitles section's rows (drawn in another file), the
 * storage card and the downloads location row (custom rows), and Sign out (a note under the
 * Account page, not a row): those land on their page.
 */
val settingsSearchAnchors: Set<String> = setOf(
    "appearance.pure_black", "appearance.typeface", "appearance.high_contrast",
    "appearance.language", "appearance.interface_size", "appearance.lighter_pictures",
    "appearance.reduce_motion", "appearance.defaults", "appearance.wallpaper_colours",
    "playback.autoplay", "playback.decoder", "playback.detail_first", "playback.hide_rip_tags",
    "playback.download_first", "playback.downmix", "playback.even_volume",
    "playback.background_audio", "playback.pip_on_leave", "playback.audio_language",
    "playback.trickplay", "playback.controls_timeout", "playback.resume_rewind",
    "subtitles.style", "subtitles.mode", "subtitles.language", "subtitles.learnt",
    "gestures.haptics", "gestures.tap_plays", "gestures.double_tap", "gestures.double_tap_pause",
    "gestures.hold_speed", "gestures.seek", "gestures.brightness", "gestures.volume",
    "library.size_limits", "library.open_last_chat", "library.forget_last_chat",
    "library.remember_searches", "library.resume_sync", "library.add_chats",
    "library.clear_favourites", "library.clear_history",
    "library.metadata_toggle", "library.metadata_skip_times", "library.metadata_key",
    "library.metadata_omdb_key", "library.metadata_clear_cache",
    "home.carousel", "home.continue", "home.recent", "home.starred_rows", "home.catalog",
    "tvhome.watch_next", "tvhome.channels",
    "notice.downloads", "notice.new_episodes", "notice.new_videos", "notice.missing_pieces",
    "notice.resume", "notice.update_ready", "notice.low_storage",
    "storage.remove_after_watching", "storage.low_storage", "storage.wifi_only",
    "storage.save_mobile_data",
    "account.set_pin", "account.change_pin", "account.lock_on_open", "account.kids",
    "account.allowed_chats", "account.remove_pin", "account.devices", "account.add",
    "about.check_updates", "about.update_notify", "about.test_versions", "about.auto_install",
    "about.backup", "about.restore", "about.proxy", "about.logs", "about.name_report",
    "about.crash_reports", "about.support", "about.about",
)

/** A notification's search key, the one its entry in the index and its row's target share. */
fun noticeSearchKey(notice: Notice): String = when (notice) {
    Notice.Downloads -> "notice.downloads"
    Notice.NewEpisodes -> "notice.new_episodes"
    Notice.NewVideos -> "notice.new_videos"
    Notice.MissingPieces -> "notice.missing_pieces"
    Notice.Resume -> "notice.resume"
    Notice.UpdateReady -> "notice.update_ready"
    Notice.Support -> "notice.support"
    Notice.LowStorage -> "notice.low_storage"
}

/** The icon a search result carries: its page's own, the one the category list gives it. */
@Composable
internal fun settingsSearchPageIcon(page: SettingsPage): ImageVector = when (page) {
    SettingsPage.Appearance -> TmIcons.Palette
    SettingsPage.Playback -> TmIcons.Play
    SettingsPage.Subtitles -> ImageVector.vectorResource(R.drawable.ic_subtitles)
    SettingsPage.Gestures -> ImageVector.vectorResource(R.drawable.ic_player_forward)
    SettingsPage.Library -> TmIcons.Grid
    SettingsPage.Home -> TmIcons.Home
    SettingsPage.TvHome -> TmIcons.Home
    SettingsPage.Notifications -> TmIcons.Bell
    SettingsPage.Storage -> TmIcons.Download
    SettingsPage.Account -> TmIcons.Lock
    SettingsPage.About -> TmIcons.Info
    SettingsPage.SetupChoices -> TmIcons.Checklist
}

/**
 * The root list's search field (v1.0.11): the sign in field ([PaneField]), so a remote's
 * keyboard and a phone's behave as they do there, standing at the head of the list where a
 * category row would be, in the list's own insets.
 */
@Composable
internal fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    val s = LocalStrings.current
    PaneField(
        value = query,
        onValueChange = onQuery,
        placeholder = s.settingsSearchSettings,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTouch()) 16.dp else 0.dp, vertical = 8.dp),
    )
}
