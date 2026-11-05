# J Player rebrand notes

Date: 2026-11-05. Scope: the Android app in this fork only. Nothing committed or pushed.
Desktop, webapp and site modules were left alone except where one shared resource carries the
Android display name (see BRAND_NAME below).

## Files changed (8, one line each)

| File | Change |
| --- | --- |
| `settings.gradle.kts` | `rootProject.name` "TMPlayer" to "JPlayer" |
| `app/build.gradle.kts` | `applicationId` "com.tmplayer" to "com.jplayer.app" |
| `app/src/main/res/values/strings.xml` | `app_name` "TMPlayer" to "J Player" (the launcher label; the main manifest already points at `@string/app_name`) |
| `app/src/preview/label/values/strings.xml` | `app_name` "TMPlayer Test" to "J Player Test" |
| `app/src/promo/label/values/strings.xml` | `app_name` "TMPlayer Promo" to "J Player Promo" |
| `app/src/promo/shots-label/values/strings.xml` | `app_name` "TMPlayer" to "J Player" |
| `fastlane/metadata/android/en-US/title.txt` | store title "TMPlayer" to "J Player" |
| `ui/src/shared/kotlin/com/tmplayer/ui/components/AppLogo.kt` | `BRAND_NAME` "TMPlayer" to "J Player": the wordmark drawn beside the logo mark in the in-app headers (Home, TV top bar, update card) and used as its content description |

Deliberately not changed, per the brief:

- `namespace = "com.tmplayer"` in `app/build.gradle.kts` and every Kotlin package
  (`com.tmplayer.*`): internal only, and `BuildConfig` references in code depend on the
  namespace staying put.
- Theme and style names (`Theme.TMPlayer`, `Theme.TMPlayer.Player`,
  `TMPlayer.Leanback.ActionIcons` in `app/src/main/res/values*/themes.xml`): internal names.
- The build type suffixes in `app/build.gradle.kts` (`.test` at line 264, `.promo` at line
  272) name no package themselves; they now stack on the new id, giving `com.jplayer.app.test`
  and `com.jplayer.app.promo`.

Deep links and providers checked, nothing to fix:

- No deep link scheme or host names the app: the manifest handles Telegram's links
  (`https` on `t.me`, `telegram.me`, `telegram.dog`, and the `tg` scheme with hosts
  `resolve`, `privatepost`, `join`). A rebrand does not touch them.
- Provider authorities use the `${applicationId}` placeholder
  (`${applicationId}.tvart`, `${applicationId}.updates`), so they follow the new id by
  themselves. The Cast options provider meta-data names a class in the unchanged internal
  package, so it stays valid.

## What remains branded

Icons: the artwork itself was not regenerated, and the mark is a drawn play glyph with no
name text in it (checked: no "TMPlayer" text in the drawable or mipmap XML). Assets a new
icon set would replace:

- `app/src/main/res/drawable/ic_launcher_foreground.xml`,
  `app/src/main/res/drawable/ic_launcher_background.xml`,
  `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`,
  `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`,
  `app/src/main/res/mipmap/ic_launcher.xml`, `app/src/main/res/mipmap/ic_launcher_round.xml`
- `app/src/main/res/drawable/tv_banner.xml` (Android TV banner)
- `fastlane/metadata/android/en-US/images/icon.png` (store listing icon)
- `art/logo.svg` and `art/player.svg` (source artwork)
- The in-app logo mark is drawn in code:
  `ui/src/shared/kotlin/com/tmplayer/ui/components/LogoMark.kt`

In-app prose: the UI text catalog still says TMPlayer throughout.
`core/src/commonMain/resources/i18n/en.json` alone has 213 mentions (About and Support
screens, backup and restore dialogs, storage wording, notifications), and the same prose
exists in the other 20 locale catalogs beside it. This pass did not sweep them, for three
reasons: the catalog is shared by every platform, not Android only; the repo rules in
AGENTS.md want a renamed string translated into all catalogs before a release, which is a
release pass of its own; and a few strings are tied to functional names (next point).

Functional names that still say TMPlayer, left alone on purpose:

- `core/src/commonMain/kotlin/com/tmplayer/data/StorageRelocationPlan.kt`:
  `APP_FOLDER = "TMPlayer"`, the on-disk Downloads folder name. Renaming it would orphan
  the folder existing installs already use.
- `core/src/commonMain/kotlin/com/tmplayer/data/AccountSessions.kt`: session recognition
  looks for "TMPlayer" in the application name of the account's other sessions.
- User-Agent strings sent to outside services: `Updates.kt` ("TMPlayer"),
  `core/src/commonMain/kotlin/com/tmplayer/online/OnlineSubtitles.kt`
  ("TMPlayer v$appVersion"), `core/src/pureMain/kotlin/com/tmplayer/online/OnlineMetadata.kt`
  ("TMPlayer/$appVersion (+https://tmplayer.org)"), and the DLNA User-Agent in
  `core/src/commonMain/kotlin/com/tmplayer/cast/dlna/`. Changing these changes what those
  services see and, for OpenSubtitles, may matter to the app key arrangement.
- `core/src/commonMain/kotlin/com/tmplayer/data/Updates.kt`: the downloaded update file is
  named `TMPlayer-${version}.apk` in the cache.
- Log tags across the app module ("TMPlayer", "TMPlayerDlna", "TMPlayerCast"): not user
  visible.

Store copy and links that still point at the original project:

- `fastlane/metadata/android/en-US/full_description.txt` still names TMPlayer 4 times
  (the short description does not name it). Only the title was in this pass's scope.
- `ui/src/shared/kotlin/com/tmplayer/ui/about/About.kt`: source code link
  `https://github.com/dracu-lah/TMPlayer`, plus `https://tmplayer.org/` pages (privacy,
  legal, support), `hello@tmplayer.org`, `https://github.com/sponsors/dracu-lah`.
- `core/src/commonMain/kotlin/com/tmplayer/data/Feedback.kt`: the report-an-issue link
  (`github.com/dracu-lah/TMPlayer/issues/new`) and `hello@tmplayer.org`.
- `ui/src/shared/kotlin/com/tmplayer/ui/onboarding/Onboarding.kt`:
  `https://tmplayer.org/translate/`.
- `ui/src/androidMain/kotlin/com/tmplayer/ui/online/TrailerPlayer.kt`: trailers are
  embedded with `https://tmplayer.org` as the page origin (YouTube embed requirement).
- `core/src/commonMain/kotlin/com/tmplayer/online/MsoneApi.kt`: the Malayalam subtitle
  gateway is the original project's `https://subs.tmplayer.org`.

## Update feed findings (reported, not changed)

The self-update mechanism (internally "HX5") still reads the original project's feed:

- `core/src/commonMain/kotlin/com/tmplayer/data/UpdateFeed.kt`:
  `FEED_URL = "https://tmplayer.org/latest.json"` (asked first), and the GitHub fallback
  built from `REPO = "dracu-lah/TMPlayer"`: `api.github.com/repos/dracu-lah/TMPlayer/releases/latest`,
  the release list, and `RELEASES_PAGE = "https://github.com/dracu-lah/TMPlayer/releases"`.
- `core/src/commonMain/kotlin/com/tmplayer/data/UpdateWords.kt` and
  `core/src/commonMain/kotlin/com/tmplayer/data/WhatsNew.kt`
  (`CHANGELOG = "https://tmplayer.org/changelog/"`).
- Android side of the mechanism: `app/src/main/java/com/tmplayer/data/AndroidUpdates.kt`,
  `app/src/main/java/com/tmplayer/data/SelfInstaller.kt`,
  `app/src/main/java/com/tmplayer/data/SelfInstallRules.kt`,
  `app/src/main/java/com/tmplayer/data/UpdatedReceiver.kt` (the "app updated" notification
  after `MY_PACKAGE_REPLACED`).

Why not changed here: these constants are the upstream project's own feed, not a config
slot waiting for a fork value. This fork (jamsheer-jr/TMPlayer) publishes no releases and
serves no `latest.json` yet, so pointing the app at it now would leave update checks
fetching nothing, or, worse, mixing the fork's GitHub list with the upstream site's feed.
Two consequences to decide when the fork starts publishing:

1. Until the feed is repointed, a J Player build will offer genuine TMPlayer updates,
   download the upstream APK (named `TMPlayer-<version>.apk`), and then fail to install it:
   the package name and signing key differ from `com.jplayer.app`. Repointing `FEED_URL`
   and `REPO` is a five line change once the fork serves a feed in the same format.
2. After repointing, self-update only works between J Player builds signed with the same
   key (release signing comes from `keystore.properties`, see `app/build.gradle.kts`), and
   version codes follow the tag scheme in `docs/RELEASING.md`, so J Player tags must keep
   producing higher codes than any build already handed out.
3. The F-Droid variant is unaffected: `-PtmFdroid=true` turns the update check off
   entirely (`BuildConfig.UPDATE_CHECK`, read into `Updates.enabled`).

## Subtitle mapping (for the follow-up feature work)

Player: Media3 ExoPlayer 1.11.1 (`gradle/libs.versions.toml`: `media3 = "1.11.1"`, with the
nextlib FFmpeg extension `nextlib-media3ext`). The player is built in
`app/src/main/java/com/tmplayer/player/PlayerActivity.kt` (`ExoPlayer.Builder`, around
line 3619 of that file). Track choice (audio, video, text) is
`app/src/main/java/com/tmplayer/player/TrackPickerFragment.kt`, shown from PlayerActivity
and applied with Media3 `TrackSelectionOverride`.

Supported today on Android:

- Embedded subtitle tracks in the video file: yes, through Media3, picked in
  TrackPickerFragment.
- External subtitle file: yes, already shipped. `app/src/main/java/com/tmplayer/player/SubtitleFiles.kt`
  (class `SubtitleFiles`, held as a field of PlayerActivity) takes a file through the
  system document picker (`ACTION_OPEN_DOCUMENT`, so no storage permission), copies it to
  the app cache, and attaches it as a Media3 `MediaItem.SubtitleConfiguration`, rebuilding
  the media item at the current position and selecting the new track when it appears.
  Format rules live in `app/src/main/java/com/tmplayer/player/ExternalSubtitle.kt`: srt,
  ass, ssa and vtt as they stand, MicroDVD `.sub` rewritten as SubRip first, VobSub refused
  with an explanation. Encoding repair is `app/src/main/java/com/tmplayer/player/SubtitleEncoding.kt`.
  Look and placement: `app/src/main/java/com/tmplayer/player/SubtitleLook.kt`,
  `app/src/main/java/com/tmplayer/player/SubtitleLift.kt`, and
  `core/src/pureMain/kotlin/com/tmplayer/player/SubtitleStyle.kt`.
- External subtitle by URL: no arbitrary-URL loader exists. The only URL-fed subtitles are
  the provider downloads below and the LAN URLs the cast server hands out in
  `app/src/main/java/com/tmplayer/player/PlayerCast.kt`.
- Online subtitles: `core/src/commonMain/kotlin/com/tmplayer/online/OnlineSubtitles.kt`
  orchestrates `OpenSubtitlesApi.kt`, `SubdlApi.kt` and `MsoneApi.kt`. Downloaded files
  are normalised by `core/src/commonMain/kotlin/com/tmplayer/online/SubtitleText.kt`
  (charset detection, unzip, converted to UTF-8 text in srt, ass, ssa or vtt) and cached
  by `core/src/commonMain/kotlin/com/tmplayer/online/SubtitleCache.kt`. Android settings
  UI: `app/src/main/java/com/tmplayer/ui/settings/OnlineSubtitlesSettings.kt`. Note the
  OpenSubtitles app key is the original project's, injected at build time
  (`OPENSUBTITLES_API_KEY`); a build with no key hides the whole online feature
  (`OnlineSubtitles.inBuild`).

Translation: no subtitle text translation engine exists today. The "machine translation"
switch in the online subtitle settings only includes or excludes releases that providers
already carry as machine translated (the filter is in `OnlineSubtitles.kt`). Hook points
when the feature is built:

- (a) External file or URL loading: translate at the text level inside the existing
  attach path. For local files, in `SubtitleFiles.kt` after the cache copy and format
  conversion (`ExternalSubtitle.kt` holds the format rules the translated file must
  still obey). For a future URL loader, at the same point: download to cache, normalise,
  translate, then attach through the same `MediaItem.SubtitleConfiguration` rebuild
  SubtitleFiles already performs.
- (b) Downloaded subtitles: in the normalisation step,
  `core/src/commonMain/kotlin/com/tmplayer/online/SubtitleText.kt`, as used by
  `OnlineSubtitles.kt`, so the translated text is what `SubtitleCache.kt` stores and
  every platform reads. Translating the whole file text there keeps timing lines and
  markup handling in one place (`SubtitleTextTest` and `GarbageInputTest` in core are
  where its parser tests live).
- Cue-level interception (only if per-cue live translation is ever wanted) would sit in
  PlayerActivity where cues reach the app's own rendering and look code
  (`SubtitleLook.kt`, `SubtitleLift.kt`); the file-level hooks above are simpler and
  cover both planned features.

## Follow-up pass, 2026-11-05: subtitle by link, subtitle translation, fork update feed

Three features built on the map above. Nothing committed or pushed, and **nothing is
compile-verified: this machine has no Android toolchain**, so no build, no test run and no
device check backs any of it. The new core tests are written but unrun.

### 1. Load subtitle from URL

Files changed:

- `app/src/main/java/com/tmplayer/player/SubtitleFiles.kt`: `loadFromUrl()` validates the
  pasted address (a full http or https link), downloads it with the same size cap as a
  picked file, following redirects by hand (HttpURLConnection will not follow an http to
  https redirect itself), takes the file name from `Content-Disposition` or the link, and
  then runs the bytes through the exact code a picked file takes: `copy()` was split so
  both roads end in `store()`, which decodes, converts MicroDVD `.sub` to SubRip and
  refuses VobSub, and the stored file goes through translation (below) and the existing
  `attach()` as a `MediaItem.SubtitleConfiguration`. `store()` also opens a zip for the
  subtitle inside it (`SubtitleText.from`), which both roads gain. Failures each get their
  own sentence: a bad link, a failed download, a link that does not name a subtitle, plus
  the existing too-big and VobSub wordings.
- `app/src/main/java/com/tmplayer/player/PlayerSheets.kt`: a `SubtitleUrl` sheet
  (TvConfirm with a link field), opened by `showSubtitleUrl()`, the same prompt shape as
  Settings' SubDL key dialog.
- `app/src/main/java/com/tmplayer/player/PlayerMenu.kt`,
  `app/src/main/java/com/tmplayer/player/PlayerMorePanel.kt`,
  `app/src/main/java/com/tmplayer/player/PlayerActivity.kt`: a `LoadSubtitleUrl` phone
  menu entry directly under Load a subtitle file, opening the prompt through the existing
  `showSheet` helper.
- `app/src/main/java/com/tmplayer/player/TrackPickerHost.kt` and
  `TrackPickerFragment.kt`: a `loadSubtitleUrl` host hook; the subtitle picker's online
  page shows "Load subtitles from a link" beside the file line, under the same conditions
  (a search that found nothing). The prompt works on a television too, unlike the document
  picker, so the hook is offered there as well.

How it is reached: player More menu (phone), or Subtitles, Search online, when the search
finds nothing: "Load subtitles from a link", paste the address, Load.

### 2. Subtitle translation

Design, matched to the whole-file subtitle translator the viewer already uses ("Simple
File Translator": file in, language chosen, fully translated file out, timings intact):

- New `core/src/commonMain/kotlin/com/tmplayer/online/SubtitleTranslator.kt`. The file is
  taken apart into timing-carrying lines and word-carrying cues; only the words move. Cues
  are sent in batches (up to 40 cues, about 3,000 characters) as **one continuous text with
  a numbered marker per cue** (`<<1>>`, `<<2>>`, ...), so the engine translates a run of
  dialogue with its context, not isolated fragments, and the answer is split back into cues
  on the markers. This is deliberately not per-cue translation. Fallbacks, in order: an
  answer whose markers were lost but whose line count matches is mapped line for line; a
  batch that fails both is retried cue by cue; a cue that still fails keeps its original
  words; a wholly failed translation returns null and the caller uses the original file.
  Cue timings are preserved exactly by construction: SubRip and WebVTT timing lines pass
  through word for word (headers, cue numbers, NOTE blocks untouched), and SubStation
  Alpha timings sit inside the Dialogue line ahead of the words. The original file is never
  mutated: callers write the result as a new file.
- The endpoint is Google Translate's keyless one, `translate_a/t` with `client=gtx` (the
  address named in the brief), with `translate_a/single` asked when the first answer
  cannot be read; `parseAnswer` reads both known response shapes and refuses anything
  else. No key, no account. Note: from this machine both endpoints answer with Google's
  bot-check redirect (a datacentre address), so the live round trip is **untested**; the
  parser and the mapping are covered by the new tests against canned answers of both
  shapes.
- Trade-offs, stated: a cue's words are flattened to one line (line breaks become spaces,
  `<i>` style markup is dropped); SubStation Alpha dialogue carrying override tags
  (`{\an8}`) is left untranslated rather than risk the tags.

Hooks, per the map above:

- Online downloads (`core/.../online/OnlineSubtitles.kt`): translation sits in `save()`,
  where `SubtitleText`'s normalised text lands, before `SubtitleCache.putFile`, so the
  translated file is what is cached and what every platform attaches. It is cached under
  its own key (`<hit id>|<language>`); the original is cached under the hit's key as well,
  so turning translation off costs no new download. `download()` checks the translated
  key first, and when only the original is cached it translates that copy on the spot
  instead of spending a download. `blockedFor()` counts either cached shape as kept.
- Local files and links (`SubtitleFiles.maybeTranslate()`): after `store()`, before
  `attach()`, the same engine over `UrlConnectionTransport`. The translated file is kept
  in the subtitles cache folder under a name made from the source file's SHA-256 and the
  language (`tr-<hash>-<language>.<ext>`): that is the per-file-plus-language cache, so
  loading the same file again finds its translation. Its track label names the language
  the side-file way (`film.ml.srt`). `attachFile()` (the online path) is deliberately
  untouched: core has already translated before the file reaches it.
- The setting: `translateTo` on `OnlineAccount`, persisted as `translate_to` in
  `OnlineSubtitlesStore`, written by `OnlineSubtitles.setTranslateTo()`. A subtitle
  already in the chosen language is not translated (compared by base language code).

UI, following the house patterns:

- Subtitle picker: a "Translate subtitles" line under the tracks (language globe icon,
  current language or Off as its detail) opening a Translate page: Off, then the offered
  languages. Malayalam (`ml`) is first in `SubtitleTranslator.LANGUAGES`, then English,
  Hindi, Tamil, Telugu, Kannada, Bengali, Arabic, Urdu, Spanish, French, German, Russian,
  Portuguese, Indonesian, Turkish. Names come from the app's own language namer.
- Settings, Online subtitles: a "Translate subtitles" row (current choice as its subtitle)
  opening the same list as a ChoiceSheet. The row lives inside the existing section, which
  Settings only draws in a build carrying an OpenSubtitles key; the picker page is not
  gated that way, since the setting and the translation need no key.
- Choosing a language does not reload subtitles already playing; it counts from the next
  download or load.

Strings: English keys added to `core/src/commonMain/resources/i18n/en.json` in sorted
order (`online.translate_*`, `player.load_subtitle_url`, `player.subtitle_url_*`,
`tracks.translate_failed`, `tracks.translating`, `tracks.url_*`, 3,119 keys verified
sorted and the file valid JSON). Per the repo rules in AGENTS.md, the same keys need
translating into the other 20 catalogs before a release; until then those locales fall
back to the English text key by key, which the catalog test allows.

Tests (written, unrun): `core/src/jvmTest/kotlin/com/tmplayer/online/SubtitleTranslatorTest.kt`
(timings preserved across srt, vtt and ass, both answer shapes, marker loss and blob
fallbacks, service down giving null, `splitMarked` and `parseAnswer` units, Malayalam
first) and new lines in `core/src/jvmTest/kotlin/com/tmplayer/data/GarbageInputTest.kt`
(`SubtitleTranslator` against the garbage corpus, plus a `translateParsersNeverThrow`
method, per AGENTS.md's parser rule).

### 3. Update feed repoint

- `core/src/commonMain/kotlin/com/tmplayer/data/UpdateFeed.kt`: `FEED_URL` is now
  `https://jamsheer-jr.github.io/TMPlayer/latest.json` and `REPO` is `jamsheer-jr/TMPlayer`,
  which carries the GitHub fallback (`api.github.com/repos/jamsheer-jr/TMPlayer/...`) and
  `RELEASES_PAGE` with it. **Assumption, stated plainly:** the fork's GitHub Pages site
  (the user's `jamsheer-jr.github.io` site, which exists) serves a `latest.json` in the
  existing schema 1 format from a `TMPlayer` path of that site, and the fork publishes
  releases under `jamsheer-jr/TMPlayer`; until both exist, update checks find nothing,
  which is the intended state (see the earlier finding: before this change a J Player
  build would have offered upstream TMPlayer updates it cannot install).
- `core/src/commonMain/kotlin/com/tmplayer/data/Updates.kt`: `RELEASES_PAGE`, the page
  the Android update dialogs print and `SelfInstaller`'s conflict message points at, now
  reads `github.com/jamsheer-jr/TMPlayer/releases`.
- `AndroidUpdates.kt`, `SelfInstaller.kt`, `SelfInstallRules.kt`, `UpdatedReceiver.kt`
  were checked: none carries an upstream URL of its own (they work from `Release` data and
  `Updates.RELEASES_PAGE`). The downloaded update's cache file name
  (`TMPlayer-<version>.apk`) is unchanged, as the rebrand pass left it: it is a private
  cache name, not an address. About, Feedback and support links are untouched, as
  instructed; `WhatsNew.CHANGELOG` (`tmplayer.org/changelog/`) was also left: it is a
  reading link in Settings and the What's New sheet, not part of the feed, and the fork
  serves no changelog page yet. Flagged for the release pass.

## Risks and open points

- J Player installs as a new app beside TMPlayer (new applicationId): no settings,
  downloads index or Telegram session carry over; users sign in again. Expected for a
  rebrand, but worth saying in the release notes.
- `BRAND_NAME` sits in the ui module's shared source set, so desktop builds from this
  tree will also show "J Player" in the lockup. That follows the shared-resource rule for
  this pass; if the desktop app must keep the TMPlayer name, the constant needs a per
  platform split instead.
- The i18n prose sweep (213 mentions in en.json, plus 20 translated catalogs) is the
  largest remaining piece of visible branding and should be planned with the translation
  step AGENTS.md requires before a release.
- The update feed decision (above) should be made before any J Player APK is handed
  out, so no build ever offers an upstream update it cannot install.
- Online subtitles depend on upstream arrangements (the OpenSubtitles app key and the
  subs.tmplayer.org gateway). A public J Player release needs its own key, or the
  feature stays hidden in builds made without one.

## Follow-up pass 2, 2026-11-05: remaining-features audit and key wiring map

### A. Audit of the remaining asked-for features (Android)

Verdicts come from reading the player code, the settings, the manifest and the i18n
catalogs, not from running the app: there is no Android toolchain on this machine, so
nothing here is compile-verified or device-verified either.

| Feature | Verdict | Where it already lives |
| --- | --- | --- |
| Playback speed control | Exists | `PlaybackSpeed` in `app/.../player/PlayerActivity.kt`: a speed sheet (`showSpeed`), an opening-speed setting with per-scope rules (`settings.openingSpeed`), and the speed is kept in the resume state |
| Offline save / export of a video | Exists | `app/.../data/FolderExport.kt`: Save to gallery writes through MediaStore into `Movies/TMPlayer` (`saveToGalleryNow`, straight from a video's page), export to a viewer-chosen folder goes through SAF, and `FrameSaver.kt` saves stills to `Pictures/TMPlayer` the same way |
| Picture-in-picture (phone) | Exists | `android:supportsPictureInPicture="true"` on the player activity in `app/src/main/AndroidManifest.xml`, `PictureInPictureRules.kt`, and the PiP handling throughout PlayerActivity; phones only, TV is excluded by form factor |
| Sleep timer | Exists | `SleepTimer` in PlayerActivity and `app/.../player/BackgroundPlayback.kt`: minute presets or the end of the video, in the player menu on phone and TV, working with the "Still watching?" prompt |
| Favourites / bookmarks | Exists, three shapes | Starred chats with a Favourites tab (`SettingsStore` favorites, gated by `KidsLock` for the kids lock); per-video position bookmarks in the player (`BookmarkStore`, `BookmarkJump`, their own bookmarks DataStore in `AndroidPlatform.kt`); and a Watch later list |
| Malayalam UI language | Exists, left alone | `core/src/commonMain/resources/i18n/ml.json` sits beside `en.json` with 3,069 of its 3,119 keys (about 98 percent); keys it lacks fall back to English under the catalog rules. Per the brief it was not touched and the catalog was not machine-translated |

So this pass implements nothing under A: every feature on the list already ships in the
fork. The one gap worth naming is the 50 or so `en.json` keys `ml.json` has not caught up
with yet, a small human translation job for the release pass, not a missing feature.

### B. How build-time keys are injected, and what his keys need

The one mechanism: `app/build.gradle.kts` reads `local.properties` first, then the
process environment, then an empty default, and writes each value into a `:app`
`BuildConfig` field. `app/src/main/java/com/tmplayer/App.kt` hands the fields to core
(the `OnlineSubtitles` constructor, `OnlineMetadata(buildKey, omdbKey)`). In CI,
`.github/workflows/release.yml` and `.github/workflows/test-build.yml` export the
repository secrets under the same names and write them into a generated
`local.properties` in their "Write Telegram credentials" step; `ci.yml` reads no secrets
at all, by design. No key values are recorded in these notes.

| Key | BuildConfig field and route | CI secret name | Notes |
| --- | --- | --- | --- |
| Telegram `TG_API_ID`, `TG_API_HASH` | int and String fields in `:app`, read from `local.properties` only (the gradle script has no env fallback for these two; CI bridges the gap by writing the file) | `TG_API_ID`, `TG_API_HASH` | release.yml fails the build when `TG_API_ID` is unset, since the APK could not sign in. No change needed |
| TMDB | `TMDB_API_KEY` in `:app` (local.properties, else env, else empty), passed at App.kt as `OnlineMetadata`'s `buildKey` | `TMDB_API_KEY` | His key under that secret name drives TMDB lookups with no code change. Viewers can also enter a TMDB key of their own under Settings, Posters and details (kept per device, never backed up); the build key is the build's own |
| SubDL | None existed: see the change below | `SUBDL_API_KEY` (new) | By upstream design the SubDL key is the viewer's own, entered under Settings, Online subtitles and kept as `subdl_key` in `OnlineSubtitlesStore`, handed to `SubdlApi` per search |
| OpenSubtitles | `OPENSUBTITLES_API_KEY` in `:app` (same route); it gates the whole online subtitle feature through `OnlineSubtitles.inBuild` | `OPENSUBTITLES_API_KEY` | His builds need a key of his own registered with OpenSubtitles, or online subtitles stay hidden, as before |
| MSone gateway | `MSONE_GATEWAY_KEY` in `:app` (same route), passed as the `msoneKey` constructor argument | `MSONE_GATEWAY_KEY` (`MSONE_GATEWAY_KEY_WEB` in webapp.yml) | This is the upstream project's private key for its subs.tmplayer.org gateway. The fork has none, so MSone stays off in J Player builds; nothing to wire |
| OMDb (IMDb, Rotten Tomatoes, Metacritic scores) | `OMDB_API_KEY` in `:app` (same route), passed as `OnlineMetadata`'s `omdbKey` | `OMDB_API_KEY` | Optional; without it a detail page shows the provider's own score alone |
| Update feed | No key and no secret: `FEED_URL` and `REPO` constants, repointed to the fork in the previous pass | None | Nothing to set |
| Release signing | `keystore.properties` beside the build (gitignored), read by `app/build.gradle.kts` | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | release.yml decodes the keystore from `KEYSTORE_BASE64` and writes `keystore.properties` from the other three. Needed before any signed J Player release |
| Sentry (optional, not asked for) | `SENTRY_DSN`, `SENTRY_DSN_DESKTOP` BuildConfig fields by the same local.properties or env route; `SENTRY_AUTH_TOKEN` is env only, for symbol upload | Same names | Off and undrawn in any build without a DSN |

The one code change this pass made, so his SubDL key can ship in his builds the way his
TMDB key already can (before it, a `SUBDL_API_KEY` secret would have reached nothing):

- `app/build.gradle.kts`: a `SUBDL_API_KEY` BuildConfig field, local.properties first,
  then the environment, then an empty default, written in the same shape as the
  `MSONE_GATEWAY_KEY` field above it.
- `app/src/main/java/com/tmplayer/App.kt`: `subdlKey = BuildConfig.SUBDL_API_KEY` passed
  into the `OnlineSubtitles` constructor, beside the two keys already passed there.
- `core/src/commonMain/kotlin/com/tmplayer/online/OnlineSubtitles.kt`: a defaulted
  constructor property `buildSubdlKey` (the `msoneKey` pattern), and the SubDL fallback
  search now runs with `a.subdlKey.ifBlank { buildSubdlKey }`: the viewer's own key when
  they have entered one, the build's otherwise. A keyless build behaves exactly as
  before, and every other call site (desktop, tests) takes the blank default and is
  unchanged.
- `.env.example`: `SUBDL_API_KEY` added to the cheat-sheet lists and given its own short
  section between OpenSubtitles and TMDB.
- `.github/workflows/release.yml` (the android job's credentials step only; the desktop
  jobs were left alone, since the desktop build has no such field) and
  `.github/workflows/test-build.yml`: the secret is exported and written into
  `local.properties` with the others.

Two honest limits of that change. The Settings, Online subtitles row still describes the
viewer's own SubDL key only, so a build carrying a key works without the row saying so;
wording for that wants a new string and the translation pass. And if SubDL refuses the
build's key, the existing `subdlRefused` flag pauses SubDL until a key is saved in
Settings, the same rule a refused viewer key has today.

What the user sets as repository secrets for his own builds, names only: `TG_API_ID` and
`TG_API_HASH` (required), `TMDB_API_KEY`, `SUBDL_API_KEY`, `OPENSUBTITLES_API_KEY` (his
own, or no online subtitles), `OMDB_API_KEY` if wanted, and the four `KEYSTORE_` /
`KEY_` signing secrets before a signed release. Nothing else needs changing in code:
TMDB already reads its key from the env-backed field, and Telegram, OpenSubtitles, MSone
and OMDb were already wired the same way.

## MX style UI (asked 2026-11-05, after v1.0.3)

He sent three MX Player screenshots and wants that arrangement: top title bar
with subtitle/audio/decoder icons, a circle shortcut row, seekbar above a
lock/prev/play/next/aspect/PiP row; a subtitle panel list (Open, Online
subtitles, Translate, Synchronization -/+ steppers, Panel, Customization);
a circle icon quick settings grid.

Current phone controls are Views, not Compose: res/layout/player_controls.xml
(591 lines) wired by player/PlayerControls.kt (setUpPhone). The layout already
has a topbar (back, title, cast, more), a centre cluster (prev/play/next) and
a bottom cluster (title, times, timebar, a pill row of round buttons:
subtitles, audio, speed, shape, rotate, lock, tv_more; copy and episodes at
the ends). So stage 1 is a rearrangement, not a rewrite: move title to the
topbar only, put the transport row (lock, prev, play, next, PiP) under the
timebar MX style, and add a decoder badge beside the topbar icons.
Stage 2: subtitle panel in MX list shape (the More menu already carries Open
file/link, Online via the picker, Translate, Size, Position, Sync as of the
post-v1.0.3 commit). Stage 3: the quick settings circle grid.
