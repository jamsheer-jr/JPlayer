package com.tmplayer.ui.online

import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.NameInitial
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.FilmographyEntry
import com.tmplayer.online.HeroPreview
import com.tmplayer.online.KnownMedia
import com.tmplayer.online.KnownTitle
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaPerson
import com.tmplayer.online.MetaProvider
import com.tmplayer.online.MetaRef
import com.tmplayer.online.PersonFilmography
import com.tmplayer.online.withProviderScore
import androidx.compose.ui.text.font.FontWeight
import com.tmplayer.online.MetaScore
import com.tmplayer.online.ScoreSource
import com.tmplayer.online.MetaTrailer
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.auth.QrCode
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * The detail page's extras in Compose, shared by the phone, the television and the desktop: the
 * facts line, the cast, the trailer and "More like this". Like the rest of this package, each
 * piece draws nothing while lookups are off, before the answer arrives, or when the provider had
 * nothing, so the page is the page it was without them.
 *
 * Memory on a 1 GB television: the cast is ten faces at most, fetched at TMDB's w185 and decoded
 * at the size they are drawn; none is fetched until its row is on screen, and the lazy row only
 * composes the faces in view. "More like this" is worked out only once its place is on screen.
 */

/** The extras for [info], once asked for; null while off, before they arrive, or with no match. */
@Composable
fun rememberMetaExtras(info: MetaInfo?): MetaExtras? = rememberMetaExtrasState(info, infoSettled = true).value

/**
 * [rememberMetaExtras], with whether the answer is in ([MetaState.settled]). Without [info] they
 * are settled once [infoSettled] is (no match, so no extras to come). An answer kept from before
 * the scores were asked for is the value at once and not settled until the scores have been asked.
 */
@Composable
fun rememberMetaExtrasState(info: MetaInfo?, infoSettled: Boolean): MetaState<MetaExtras> {
    info ?: return MetaState(null, infoSettled)
    val online = OnlineMetadata.current ?: return MetaState(null, true)
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return MetaState(null, true)
    val language by Translator.active.collectAsState()
    val known = remember(info.provider, info.kind, info.id, language.tag) { online.peekExtras(info) }
    val done = known?.scoresAsked == true
    val extras by produceState(MetaState(known, done), info.provider, info.kind, info.id, language.tag) {
        value = MetaState(known, done)
        // An answer kept from before the scores were asked for is shown at once, then refreshed with them.
        if (!done) value = MetaState(withContext(Dispatchers.IO) { runCatching { online.extras(info) }.getOrNull() } ?: known, true)
    }
    return extras
}

/**
 * "Drama, Crime  ·  56 min  ·  5 seasons  ·  TV-MA", or null with nothing to say. The scores are
 * not in it: [MetaScoresRow] draws them under it, each with its site's mark. A page whose own
 * facts line already says the length or the number of seasons leaves them out here
 * ([runtime], [seasons] false), so neither is said twice.
 */
fun metaFactsLine(extras: MetaExtras, s: Messages, runtime: Boolean = true, seasons: Boolean = true): String? = listOfNotNull(
    extras.genres.take(3).joinToString(", ").ifBlank { null },
    extras.runtimeMin?.takeIf { runtime }?.let { s.formatter.duration(it * 60L) },
    extras.seasons?.takeIf { seasons }?.let { s.metadataSeasons(it) },
    extras.certification,
).joinToString("  ·  ").ifBlank { null }

/** "Directed by …" for a film, "Created by …" for a show, or null. */
fun metaPeopleLine(extras: MetaExtras, s: Messages): String? = when {
    extras.directors.isNotEmpty() -> s.metadataDirectedBy(extras.directors.joinToString(", "))
    extras.creators.isNotEmpty() -> s.metadataCreatedBy(extras.creators.joinToString(", "))
    else -> null
}

/**
 * The facts line, the scores under it, and the director or creators. [runtime] false leaves the
 * length out, for a page that has already said the file's own.
 */
@Composable
fun MetaFactsLines(extras: MetaExtras?, modifier: Modifier = Modifier, maxLines: Int = 2, runtime: Boolean = true) {
    extras ?: return
    val s = LocalStrings.current
    val facts = metaFactsLine(extras, s, runtime = runtime)
    val people = metaPeopleLine(extras, s)
    val scored = extras.scores.isNotEmpty() || extras.rating != null
    if (facts == null && people == null && !scored) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
        if (scored) MetaScoresRow(extras, maxLines = maxLines)
        people?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Each site's score with that site's own mark: IMDb's badge then "7.8", Rotten Tomatoes' tomato
 * (or splat, under 60) then "92%", Metacritic's roundel then "78" on its band's colour, and so on,
 * wrapping to another line when narrow, no more than [maxLines]. [limit] keeps only the first few
 * (the Home carousel's one line), [centered] centres them under centred text. An answer kept from
 * before the scores were asked for has only the provider's rating: it is shown as [provider]'s own
 * score, with that site's mark, like any other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaScoresRow(
    extras: MetaExtras,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    limit: Int = Int.MAX_VALUE,
    centered: Boolean = false,
    provider: MetaProvider = MetaProvider.Tmdb,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    logoHeight: Dp = LOGO_HEIGHT,
) {
    val scores = remember(extras, provider) { extras.withProviderScore(provider).scores }
    if (scores.isEmpty()) return
    FlowRow(
        modifier,
        horizontalArrangement = if (centered) Arrangement.spacedBy(SCORE_GAP, Alignment.CenterHorizontally) else Arrangement.spacedBy(SCORE_GAP),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxLines = maxLines,
    ) {
        for (score in scores.take(limit)) ScoreItem(score, style, logoHeight = logoHeight)
    }
}

/**
 * A card's one score ([MetaScore.cardPick]) with its site's mark, in a small dark pill for the
 * corner of a poster or a tile: only from what is already on this device
 * ([OnlineMetadata.cachedCardScore]), never a lookup per card, and nothing at all, no placeholder,
 * when none is kept. [meta] is the card's match, or null for none.
 */
@Composable
fun CardScore(meta: MetaInfo?, modifier: Modifier = Modifier, compact: Boolean = false) {
    meta ?: return
    val online = OnlineMetadata.current ?: return
    val settings by com.tmplayer.ui.components.rememberMetaSettings(online)
    if (!settings.enabled) return
    val known = remember(meta) { online.peekCardScore(meta) }
    val score by produceState(known?.score, meta) {
        if (known == null) value = withContext(Dispatchers.IO) { runCatching { online.cachedCardScore(meta) }.getOrNull() }
    }
    val shown = score ?: return
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    Box(
        modifier
            // A rounded fill, not a clip layer per card, as the tiles' other badges.
            .background(com.tmplayer.ui.components.TileBadgeFill, CircleShape)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 3.dp),
    ) {
        ScoreItem(shown, style, ink = Color.White, logoHeight = if (compact) 11.dp else 12.dp, gap = 4.dp)
    }
}

@Composable
private fun ScoreItem(
    score: MetaScore,
    style: androidx.compose.ui.text.TextStyle,
    ink: Color = Tone.text,
    logoHeight: Dp = LOGO_HEIGHT,
    gap: Dp = 6.dp,
) {
    val s = LocalStrings.current
    val site = scoreSiteName(score.source)
    val whole = score.value.roundToInt()
    val percent = score.source == ScoreSource.RottenTomatoes || score.source == ScoreSource.AniList
    val value = when {
        // percent() rounds down, so half a point more keeps 29 from coming out as 28.
        percent -> s.formatter.percent((whole + 0.5) / 100.0)
        score.outOfHundred -> s.formatter.number(whole)
        else -> s.formatter.decimal(score.value, 1)
    }
    val spoken = when {
        percent -> s.metadataScorePerCent(site = site, score = s.formatter.number(whole))
        score.outOfHundred -> s.metadataScoreOutOfHundred(site = site, score = value)
        else -> s.metadataScoreOutOfTen(site = site, score = value)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val logo = scoreLogo(score)
        if (logo != null) {
            Image(
                logo,
                contentDescription = null,
                modifier = Modifier.height(if (score.source == ScoreSource.Tmdb) logoHeight * TMDB_SHARE else logoHeight)
                    .aspectRatio(logo.viewportWidth / logo.viewportHeight),
            )
        } else {
            // TVmaze: no vector mark to hand, so its name in its place.
            Text(site, style = style, color = if (ink == Tone.text) Tone.muted else ink.copy(alpha = 0.8f), maxLines = 1)
        }
        if (score.source == ScoreSource.Metacritic) {
            // Metacritic's own way: the number on its band's colour, green, yellow or red.
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(metacriticBand(score.value)).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(value, style = style, color = Color(0xFF131313), maxLines = 1)
            }
        } else {
            Text(value, style = style, color = ink, maxLines = 1)
        }
    }
}

/** Brand names stay as the sites write them, in every language. */
private fun scoreSiteName(source: ScoreSource): String = when (source) {
    ScoreSource.Imdb -> "IMDb"
    ScoreSource.RottenTomatoes -> "Rotten Tomatoes"
    ScoreSource.Metacritic -> "Metacritic"
    ScoreSource.Tmdb -> "TMDB"
    ScoreSource.AniList -> "AniList"
    ScoreSource.TvMaze -> "TVmaze"
}

private fun scoreLogo(score: MetaScore): ImageVector? = when (score.source) {
    ScoreSource.Imdb -> ScoreLogos.Imdb
    ScoreSource.RottenTomatoes -> if (score.fresh) ScoreLogos.TomatoFresh else ScoreLogos.TomatoRotten
    ScoreSource.Metacritic -> ScoreLogos.Metacritic
    ScoreSource.Tmdb -> com.tmplayer.ui.about.TmdbLogo
    ScoreSource.AniList -> ScoreLogos.AniList
    ScoreSource.TvMaze -> null
}

/** Metacritic's bands: 61 and over favourable, 40 to 60 mixed, under 40 unfavourable. */
private fun metacriticBand(value: Double): Color = when {
    value >= 61 -> Color(0xFF00CE7A)
    value >= 40 -> Color(0xFFFFBD3F)
    else -> Color(0xFFFF6874)
}

private val LOGO_HEIGHT: Dp = 16.dp

/** TMDB's mark is a long wordmark; at this share of the others' height its letters stand as tall as the text's. */
private const val TMDB_SHARE = 11f / 16f
private val SCORE_GAP: Dp = 14.dp

// ---- the trailer ------------------------------------------------------------------------------

/**
 * Opens trailers (owner, v1.0.14, the order flipped): the app's own player first, wherever
 * the platform has one ([playing], drawn by [TrailerHost]), so a trailer plays without
 * leaving the app. The YouTube app is the fallback, not the first step: the player hands
 * over to it by itself when the embed cannot play (see TrailerPlayer), and the "Watch on
 * YouTube" over the picture hands over whenever the viewer asks, which is also the way
 * out of the one failure no signal reports, sound over a black picture. A set with no
 * WebView never tries the embed and goes straight to [openElsewhere], and what nothing
 * on the device could open is remembered ([unopened]) so its QR code can be shown in its
 * place, for a phone to scan.
 *
 * Until v1.0.14 the app came first, for the embed's real weaknesses (black screens on
 * some phones and TVs, trailers YouTube will not embed); those are now the fallback's
 * job rather than a reason to leave the app for every trailer.
 */
@Stable
class TrailerLauncher internal constructor(
    private val opener: (MetaTrailer) -> Boolean,
    private val inApp: Boolean,
) {
    var unopened by mutableStateOf<MetaTrailer?>(null)
        private set

    /** The trailer playing inside the app, if any. */
    var playing by mutableStateOf<MetaTrailer?>(null)
        private set

    fun open(trailer: MetaTrailer) {
        unopened = null
        if (inApp) {
            playing = trailer
        } else {
            openElsewhere(trailer)
        }
    }

    /** YouTube's own app or the browser, for a trailer that will not play embedded. */
    fun openElsewhere(trailer: MetaTrailer) {
        playing = null
        unopened = if (opener(trailer)) null else trailer
    }

    /** Closes the in-app player. */
    fun close() {
        playing = null
    }
}

@Composable
fun rememberTrailerLauncher(): TrailerLauncher {
    val opener = rememberTrailerOpener()
    val inApp = rememberInAppTrailers()
    return remember(opener, inApp) { TrailerLauncher(opener, inApp) }
}

/** The in-app trailer player, while [launcher] has one playing. Place it once beside the trailer button. */
@Composable
fun TrailerHost(launcher: TrailerLauncher) {
    val trailer = launcher.playing ?: return
    TrailerPlayer(
        trailer,
        onClose = launcher::close,
        onOpenElsewhere = { launcher.openElsewhere(trailer) },
        fallback = { TrailerQr(trailer, text = LocalStrings.current.metadataTrailerScanPhone) },
    )
}

/**
 * Which hero preview may play: only the newest page's. A show's page left open under a
 * video's page would otherwise go on playing (audibly, once unmuted) under it, so each
 * preview claims a number as it first qualifies, and only the latest claim draws a player.
 * A page that loses its claim keeps its artwork for the rest of that visit rather than
 * starting again when the page over it closes.
 */
private object HeroPreviewClaims {
    var latest by mutableStateOf(0)
        private set
    private var counter = 0

    fun claim(): Int {
        counter += 1
        latest = counter
        return counter
    }
}

/**
 * The TV Home banner's trailer preview (owner, v1.0.15, item 11b): the focused card's
 * trailer playing muted in the banner area, while [HeroPreview.bannerPreview] allows it.
 * The focus-safety is the host's shape, not this composable's: the caller draws it inside
 * the banner while the remote rests on the carousel's own buttons, shows it only after
 * the focus has dwelt, and stops composing it the moment focus leaves, which disposes of
 * the embed. The embed itself takes no focus and no keys (see TrailerPlayer), it has no
 * sound button here (a banner is not a page), and a trailer that cannot play on the stick
 * hands the banner back to its artwork through the failure path.
 */
@Composable
fun TvBannerTrailerPreview(trailer: MetaTrailer?, switchOn: Boolean, modifier: Modifier = Modifier) {
    trailer ?: return
    val inApp = rememberInAppTrailers()
    val metered = rememberMeteredConnection()
    val reduced = com.tmplayer.ui.theme.rememberReducedMotion()
    if (!HeroPreview.bannerPreview(switchOn, trailer, metered, inAppPlayer = inApp, reducedMotion = reduced)) return
    val claim = remember { HeroPreviewClaims.claim() }
    if (HeroPreviewClaims.latest != claim) return
    var failed by remember(trailer.key) { mutableStateOf(false) }
    if (failed) return
    HeroTrailerPlayer(trailer, muted = true, onFailed = { failed = true }, modifier = modifier)
}

/** The trailer's YouTube link as a QR code on a white plate, with a sentence saying what it is for. */
@Composable
fun TrailerQr(trailer: MetaTrailer, modifier: Modifier = Modifier, text: String? = null) {
    val s = LocalStrings.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, trailer.key) {
        value = withContext(Dispatchers.Default) { QrCode.render(trailer.watchUrl, QR_PIXELS) }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(QR_SIZE).clip(RoundedCornerShape(Corner.Medium)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let { Image(it, contentDescription = s.metadataTrailerQr, modifier = Modifier.fillMaxSize().padding(8.dp)) }
        }
        Text(text ?: s.metadataTrailerScan, style = MaterialTheme.typography.bodyMedium, color = Tone.text, modifier = Modifier.weight(1f))
    }
}

/** The trailer's button glyph. */
val TrailerIcon: ImageVector get() = TmIcons.Trailer

/** "Watch trailer" as an outlined pill, for the series page's row beside the play button. */
@Composable
fun TrailerPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    Row(
        modifier
            .height(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(if (focused) Tone.focusFill else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else Tone.muted.copy(alpha = 0.6f), CircleShape)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val ink = if (focused) Tone.onFocusFill else Tone.text
        Icon(TrailerIcon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(s.metadataTrailer, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

// ---- the cast ---------------------------------------------------------------------------------

/** True once any of this has been on screen, and from then on: the gate for fetching what it shows. */
private fun Modifier.onceOnScreen(seen: Boolean, onSeen: () -> Unit): Modifier =
    if (seen) this else onGloballyPositioned { if (!it.boundsInWindow().isEmpty) onSeen() }

/** A heading, then the cast as a row of faces with their names and parts. Nothing without a cast. */
@Composable
fun MetaCastRow(
    cast: List<MetaPerson>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * A face pressed (owner, v1.0.14): where the page can open the person's titles it
     * hands the person here and draws [PersonTitlesDialog]; null keeps the older press,
     * which only enlarges the face.
     */
    onOpenPerson: ((MetaPerson) -> Unit)? = null,
) {
    if (cast.isEmpty()) return
    val s = LocalStrings.current
    val tv = !isTouch()
    var seen by remember { mutableStateOf(false) }
    Column(modifier.onceOnScreen(seen) { seen = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataCast, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.padding(contentPadding.startOnly()))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
            // Room for a focused face to grow without its edge being cut off.
            contentPadding = PaddingValues(
                start = contentPadding.startOnly().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + if (tv) 6.dp else 0.dp,
                end = 6.dp,
                top = if (tv) 6.dp else 0.dp,
                bottom = if (tv) 6.dp else 0.dp,
            ),
        ) {
            items(cast) { person -> PersonCard(person, loadPhoto = seen, onOpenPerson = onOpenPerson) }
        }
    }
}

/**
 * The cast row's place while the extras are on their way: its heading and a row of soft faces with
 * a line of name under each, about as tall as the row it stands for, so the cast comes in where
 * its place was rather than pushing what is under it down.
 */
@Composable
fun MetaCastPlaceholder(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val face = if (tv) FACE_TV else FACE
    val shimmer = com.tmplayer.ui.components.rememberShimmer()
    Column(modifier.semantics { contentDescription = s.metadataCast }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataCast, style = MaterialTheme.typography.titleSmall, color = Tone.text)
        Row(
            Modifier.padding(start = if (tv) 6.dp else 0.dp, top = if (tv) 6.dp else 0.dp, bottom = if (tv) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
        ) {
            repeat(CAST_PLACEHOLDERS) {
                Column(Modifier.width(face + 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.size(face), CircleShape)
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(face * 0.8f).height(10.dp))
                    com.tmplayer.ui.components.SkeletonBox(shimmer, Modifier.width(face * 0.55f).height(10.dp))
                }
            }
        }
    }
}

/** How many soft faces stand in for the cast. */
private const val CAST_PLACEHOLDERS = 5

@Composable
private fun PersonCard(person: MetaPerson, loadPhoto: Boolean, onOpenPerson: ((MetaPerson) -> Unit)? = null) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val face = if (tv) FACE_TV else FACE
    var enlarged by remember { mutableStateOf(false) }
    if (enlarged) PersonEnlarged(person) { enlarged = false }
    Column(
        Modifier
            .width(face + 16.dp)
            // A face is small in a row; a press shows it large, with the name and the part.
            // Where the page opens the person's titles, that is the press instead.
            .clickable(
                interactionSource = interactions,
                indication = if (tv) null else androidx.compose.foundation.LocalIndication.current,
                onClick = { if (onOpenPerson != null) onOpenPerson(person) else enlarged = true },
            )
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(face)
                .clip(CircleShape)
                .background(Tone.surfaceHigh)
                .border(if (focused) Focus.Edge else 0.dp, if (focused) Tone.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials(person.name), style = MaterialTheme.typography.titleMedium, color = Tone.muted)
            if (loadPhoto) MetaPicture(person.photoUrl, Modifier.fillMaxSize(), maxWidth = FACE_PIXELS)
        }
        Text(
            person.name,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Tone.accent else Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (person.role.isNotBlank()) {
            Text(
                person.role,
                style = MaterialTheme.typography.bodySmall,
                color = Tone.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One of the cast, as large as the screen allows: the photo in its own proportions, the name and
 * the part under it, Close at the top end. Back, a tap outside or Close puts it away.
 */
@Composable
private fun PersonEnlarged(person: MetaPerson, onClose: () -> Unit) {
    val tv = !isTouch()
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            val photoHeight = (maxHeight * 0.7f).coerceAtMost(maxWidth * 1.2f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .height(photoHeight)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Corner.Large))
                        .background(Tone.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(person.name), style = MaterialTheme.typography.displayMedium, color = Tone.muted)
                    MetaPicture(person.photoUrl, Modifier.fillMaxSize(), maxWidth = ENLARGED_PIXELS)
                }
                Text(person.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                if (person.role.isNotBlank()) {
                    Text(person.role, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusVisibleAsState()
            // The corner is this plain box's, not the tip's (owner's phone, v1.0.17):
            // on a phone the tip is Material's TooltipBox, whose own layout centred
            // the button over the middle instead of holding the corner it was given.
            Box(Modifier.align(Alignment.TopEnd).padding(if (tv) 32.dp else 16.dp)) {
            com.tmplayer.ui.components.IconTip(
                LocalStrings.current.commonClose,
                side = com.tmplayer.ui.components.TipSide.Below,
            ) {
                Box(
                    Modifier
                        .size(if (tv) 48.dp else 40.dp)
                        .clip(CircleShape)
                        .background(if (focused) Tone.focusFill else Color.White.copy(alpha = 0.15f))
                        .focusRequester(close)
                        .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TmIcons.Close,
                        contentDescription = LocalStrings.current.commonClose,
                        tint = if (focused) Tone.onFocusFill else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            }
        }
        if (tv) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    }
}

/** One title of a person's, as [PersonTitlesDialog] lists it: its match, and a copy to open. */
private data class PersonTitle(val info: com.tmplayer.online.MetaInfo, val item: MediaItem)

/**
 * The titles [name] stands in among the videos the app has loaded: every loaded item's
 * match is asked for the people its kept extras name (the cast, then whoever directed or
 * created it), the same stored extras a search by a person's name reads (owner, v1.0.10),
 * cache-only, so a title whose extras were never fetched holds nobody to match and
 * nothing is asked for here. One entry per matched title, however many chats hold a
 * copy of it; films and shows stand together.
 */
private fun personTitles(name: String): List<PersonTitle> {
    val online = com.tmplayer.online.OnlineMetadata.current ?: return emptyList()
    val wanted = name.trim().lowercase()
    if (wanted.isEmpty()) return emptyList()
    val byMatch = LinkedHashMap<String, PersonTitle>()
    for (item in com.tmplayer.online.KnownMedia.all()) {
        val query = item.metaQuery?.showOnly() ?: continue
        val info = (runCatching { online.peek(query) }.getOrNull() as? com.tmplayer.online.MetaResult.Found)?.info ?: continue
        val extras = runCatching { online.storedExtras(info) }.getOrNull() ?: continue
        val people = extras.cast.map { it.name } + extras.directors + extras.creators
        if (people.none { it.trim().lowercase() == wanted }) continue
        byMatch.putIfAbsent(info.provider.name + ":" + info.id, PersonTitle(info, item))
    }
    return byMatch.values.toList()
}

/** What a person's page lists: their filmography where TMDB has one, and what the chats hold besides. */
private class PersonPageData(
    /** The person's TMDB credits marked against the viewer's titles; null when TMDB has none for them. */
    val filmography: List<FilmographyEntry>?,
    /** Titles in the chats the filmography does not already cover (a director's, say): all playable. */
    val extraHeld: List<PersonTitle>,
)

/**
 * The person's page, as [PersonPageData] builds it: the titles in the viewer's chats
 * ([personTitles], the name match the search by a person reads) set the playable copies
 * first, then TMDB's credits for the person are marked against every matched title the
 * app has listed ([OnlineMetadata.knownTitles]), so a credit the chats hold is upgraded
 * to playable even where the name match missed it. A fetch that fails, or a person TMDB
 * does not know, leaves the in-chats list alone, exactly the v1.0.14 page.
 */
private suspend fun personPage(person: MetaPerson): PersonPageData {
    val held = personTitles(person.name)
    val online = OnlineMetadata.current ?: return PersonPageData(null, held)
    val credits = runCatching { online.personFilmography(person) }.getOrNull()
        ?: return PersonPageData(null, held)
    val heldByKey = held.associateBy { MetaRef.matchKey(it.info.provider, it.info.kind, it.info.id) }
    val entries = PersonFilmography.mark(credits, online.knownTitles(KnownMedia.all())).map { entry ->
        if (entry.playable) entry else heldByKey[entry.credit.ref.matchKey]?.let { entry.copy(item = it.item) } ?: entry
    }
    val covered = credits.mapTo(HashSet()) { it.ref.matchKey }
    return PersonPageData(entries, held.filter { MetaRef.matchKey(it.info.provider, it.info.kind, it.info.id) !in covered })
}

/** How many missing credits one page opening asks Telegram for at most. */
private const val PERSON_SEARCH_ASKS = 10

/**
 * [entries] with the still missing credits asked for on Telegram itself (owner,
 * v1.0.17): the local marking only knows the titles the app has already listed, and
 * most of a viewer's titles sit in chats nothing has listed yet, so they read "Not
 * in your chats" wrongly. A few credits at a time, in the list's own order (newest
 * first), one page each through the same ask the search screen makes
 * ([com.tmplayer.ui.browse.VideoSearchSource.Telegram]), so the chats choice and
 * the Global Search switch rule it the same way. A find counts when its name reads
 * as the credit's title and its year does not fight the credit's; finds are noted
 * into [com.tmplayer.online.KnownMedia], so the rest of the app keeps them.
 * Offline, or Telegram refusing, simply leaves the local marking as it stood.
 */
private suspend fun personSearchFill(entries: List<FilmographyEntry>): List<FilmographyEntry> {
    if (entries.none { !it.playable }) return entries
    val found = HashMap<String, MediaItem>()
    var asks = 0
    for (entry in entries) {
        if (entry.playable) continue
        if (asks >= PERSON_SEARCH_ASKS) break
        val credit = entry.credit
        val title = credit.ref.title.trim()
        if (title.length < 3) continue
        asks++
        val page = try {
            com.tmplayer.ui.browse.VideoSearchSource.Telegram.page(
                title,
                com.tmplayer.data.AllChatsCursor(),
                com.tmplayer.data.SearchFilters(),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            break
        }
        if (page.items.isEmpty()) continue
        com.tmplayer.online.KnownMedia.note(page.items)
        val wanted = com.tmplayer.data.Fuzzy.tokens(title).toSet()
        val hit = page.items.firstOrNull { item ->
            val parsed = com.tmplayer.data.MediaName.parse(item.fileName, item.caption)
            val yearsOk = credit.year == null || parsed.year == null || credit.year == parsed.year
            yearsOk && com.tmplayer.data.Fuzzy.tokens(parsed.title).toSet() == wanted
        }
        if (hit != null) found[credit.ref.matchKey] = hit
    }
    if (found.isEmpty()) return entries
    return entries.map { entry -> found[entry.credit.ref.matchKey]?.let { entry.copy(item = it) } ?: entry }
}

/**
 * A cast member's page (owner, v1.0.14, widened v1.0.15 item 17), opened by a press on
 * their face in the cast row: the face large with the name and the part, then their whole
 * filmography from TMDB, newest first. A title one of the viewer's chats holds is marked
 * "In your chats" and opens by the page's own opener; the rest stand as filmography
 * entries marked "Not in your chats", the same honesty as a season list's missing
 * episodes, and are not presses. Credits the local marking misses are then asked
 * for on Telegram itself ([personSearchFill]), so a title held in a chat nothing
 * has listed yet is upgraded in place rather than standing as missing. Where TMDB has nothing for the person, only the titles
 * in the chats are listed, as the page always did. Back, a press outside the card, or
 * the close button leaves; on a television the close button takes the remote first, as
 * the enlarged face's does, and Down walks to the rows.
 */
@Composable
fun PersonTitlesDialog(person: MetaPerson, onDismiss: () -> Unit, onOpenItem: (MediaItem) -> Unit) {
    val tv = !isTouch()
    val s = LocalStrings.current
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    val page by produceState<PersonPageData?>(null, person.name) {
        value = withContext(Dispatchers.Default) { personPage(person) }
        // The full search (owner, v1.0.17): credits the local marking left unheld
        // are asked for on Telegram itself, and the rows upgrade in place as the
        // answers land.
        val local = value
        val films = local?.filmography
        if (local != null && films != null) {
            val filled = withContext(Dispatchers.Default) { personSearchFill(films) }
            if (filled !== films) value = PersonPageData(filled, local.extraHeld)
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 520.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(Tone.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(person.name), style = MaterialTheme.typography.headlineMedium, color = Tone.muted)
                    MetaPicture(person.photoUrl, Modifier.fillMaxSize(), maxWidth = FACE_PIXELS)
                }
                Text(person.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                if (person.role.isNotBlank()) {
                    Text(person.role, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
                if (page == null) {
                    // The page asks TMDB and the chats behind itself; until the first
                    // answer lands the wait must read as loading, not as a stuck
                    // page (owner, v1.0.21).
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Text(s.commonLoading, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                page?.let { data ->
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (data.filmography != null && data.extraHeld.isNotEmpty()) {
                            item(key = "held-heading") {
                                Text(s.metadataInYourChats, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                            }
                        }
                        items(data.extraHeld, key = { "held-" + it.info.provider.name + "-" + it.info.id }) { title ->
                            PersonTitleRow(title) { onOpenItem(title.item) }
                        }
                        if (data.filmography != null) {
                            item(key = "filmography-heading") {
                                Text(s.metadataFilmography, style = MaterialTheme.typography.titleSmall, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            }
                        }
                        items(data.filmography.orEmpty(), key = { "credit-" + it.credit.ref.matchKey }) { entry ->
                            FilmographyRow(entry) { entry.item?.let(onOpenItem) }
                        }
                    }
                }
            }
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusVisibleAsState()
            // The corner is this plain box's, not the tip's (owner's phone, v1.0.17):
            // on a phone the tip is Material's TooltipBox, whose own layout centred
            // the button over the middle instead of holding the corner it was given.
            Box(Modifier.align(Alignment.TopEnd).padding(if (tv) 32.dp else 16.dp)) {
            com.tmplayer.ui.components.IconTip(
                LocalStrings.current.commonClose,
                side = com.tmplayer.ui.components.TipSide.Below,
            ) {
                Box(
                    Modifier
                        .size(if (tv) 48.dp else 40.dp)
                        .clip(CircleShape)
                        .background(if (focused) Tone.focusFill else Color.White.copy(alpha = 0.15f))
                        .focusRequester(close)
                        .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TmIcons.Close,
                        contentDescription = LocalStrings.current.commonClose,
                        tint = if (focused) Tone.onFocusFill else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            }
        }
        if (tv) LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
    }
}

/** One row of [PersonTitlesDialog]: the poster, the title and its year, a press to open it. */
@Composable
private fun PersonTitleRow(title: PersonTitle, onOpen: () -> Unit) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.focusFill else Tone.surfaceHigh)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(Corner.Small)).background(Tone.surface),
            contentAlignment = Alignment.Center,
        ) {
            MetaPicture(title.info.posterUrl, Modifier.fillMaxSize(), maxWidth = 240)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title.info.title, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 2)
            title.info.year?.let { year ->
                Text(year.toString(), style = MaterialTheme.typography.bodySmall, color = if (focused) Tone.onFocusFill else Tone.muted)
            }
        }
    }
}

/**
 * One row of the filmography: the poster, the title, its year and the part played or the job
 * done, then where it stands with the viewer's chats. A title the chats hold is a press that
 * opens it, marked in the accent; one they do not hold is dimmed, marked as not here, and no
 * press at all, so a remote never lands on a dead row.
 */
@Composable
private fun FilmographyRow(entry: FilmographyEntry, onOpen: () -> Unit) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val playable = entry.playable
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = if (focused) Tone.onFocusFill else Tone.text
    val credit = entry.credit
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.Medium))
            .background(if (focused) Tone.focusFill else Tone.surfaceHigh)
            .then(
                if (playable) {
                    Modifier.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(Corner.Small)).background(Tone.surface),
            contentAlignment = Alignment.Center,
        ) {
            MetaPicture(credit.posterUrl, Modifier.fillMaxSize(), maxWidth = 240)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                credit.ref.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (playable) ink else ink.copy(alpha = 0.65f),
                maxLines = 2,
            )
            val facts = listOfNotNull(
                credit.year?.toString(),
                credit.role.ifBlank { null },
            ).joinToString("  ·  ")
            if (facts.isNotBlank()) {
                Text(facts, style = MaterialTheme.typography.bodySmall, color = if (focused) Tone.onFocusFill else Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (playable) s.metadataInYourChats else s.metadataNotInYourChats,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    focused -> Tone.onFocusFill
                    playable -> Tone.accent
                    else -> Tone.muted
                },
                maxLines = 1,
            )
        }
    }
}

private fun initials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.let { listOfNotNull(it.firstOrNull(), it.drop(1).lastOrNull()) }
        .mapNotNull(NameInitial::of).joinToString("")

// ---- more like this ---------------------------------------------------------------------------

/**
 * What the provider recommends for [self], narrowed to the videos the viewer has in their chats,
 * as a row of posters; [onOpen] opens one's detail. Nothing when none of them is here, or when
 * there is nowhere to open them.
 */
@Composable
fun MoreLikeThisRow(
    self: MetaInfo,
    extras: MetaExtras,
    onOpen: ((MediaItem) -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (onOpen == null || (extras.similar.isEmpty() && extras.genres.isEmpty())) return
    val online = OnlineMetadata.current ?: return
    val s = LocalStrings.current
    val tv = !isTouch()
    // Back from a video opened here: the row is wanted at once, to put the focus back on its tile.
    val back = com.tmplayer.ui.browse.LocalDetailReturn.current
    var seen by remember { mutableStateOf(back != null) }
    val titles by produceState(emptyList<KnownTitle>(), self.id, extras, seen) {
        if (seen) value = withContext(Dispatchers.IO) {
            val known = KnownMedia.all()
            // The provider's own similar titles first; when it named none, the viewer's own
            // titles alike by genre (J Player, 2026-11-06).
            online.moreLikeThis(self, extras, known)
                .ifEmpty { online.moreLikeThisByGenre(self, extras, known) }
        }
    }
    if (titles.isEmpty()) {
        // A place holder one pixel tall, so being scrolled to is what starts the work.
        if (!seen) Spacer(modifier.fillMaxWidth().height(1.dp).onceOnScreen(seen) { seen = true })
        return
    }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val returnFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val returnView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val returnId = back?.toId?.takeIf { id -> titles.any { it.item.id == id } }
    if (back != null && returnId != null) {
        // The tile Back came from: scrolled to in the row, then given the remote's focus (which
        // brings the row into view on the page), or on a touch screen just brought into view.
        LaunchedEffect(back, returnId) {
            if (back.done) return@LaunchedEffect
            rowState.scrollToItem(titles.indexOfFirst { it.item.id == returnId })
            repeat(RETURN_FOCUS_TRIES) {
                androidx.compose.runtime.withFrameNanos { }
                if (back.done) return@LaunchedEffect
                val placed = if (tv) runCatching { returnFocus.requestFocus() }.isSuccess else true
                if (placed && back.claim()) {
                    runCatching { returnView.bringIntoView() }
                    return@LaunchedEffect
                }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.metadataMoreLikeThis, style = MaterialTheme.typography.titleSmall, color = Tone.text, modifier = Modifier.padding(contentPadding.startOnly()))
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp),
            contentPadding = PaddingValues(
                start = contentPadding.startOnly().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + if (tv) 6.dp else 0.dp,
                end = 6.dp,
                top = if (tv) 6.dp else 0.dp,
                bottom = if (tv) 6.dp else 0.dp,
            ),
        ) {
            items(titles, key = { it.item.id }) { known ->
                KnownPoster(
                    known,
                    onClick = { onOpen(known.item) },
                    modifier = if (known.item.id == returnId) {
                        Modifier.focusRequester(returnFocus).bringIntoViewRequester(returnView)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun KnownPoster(known: KnownTitle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val width = if (tv) POSTER_TV else POSTER
    val shape = RoundedCornerShape(Corner.Medium)
    Column(Modifier.width(width).then(modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Tone.surfaceHigh)
                .border(if (focused) Focus.Edge else 0.dp, if (focused) Tone.accent else Color.Transparent, shape)
                .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick),
        ) {
            MediaArt(known.item.miniThumbnail, known.item.thumbnailFileId, Modifier.fillMaxSize()) {}
            MetaPicture(known.info.posterUrl, Modifier.fillMaxSize(), maxWidth = 240)
        }
        Text(
            known.info.title + (known.info.year?.takeIf { known.info.kind == MetaKind.Film }?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Tone.accent else Tone.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun PaddingValues.startOnly(): PaddingValues =
    PaddingValues(start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))

private val FACE: Dp = 72.dp
private val FACE_TV: Dp = 88.dp

/** A face is drawn at most 88 dp, so about 160 px decoded is plenty for a face that small. */
private const val FACE_PIXELS = 160

/** The enlarged photo: sharp at its size on a 1080p screen. */
private const val ENLARGED_PIXELS = 780

/** Frames the tile Back came from is asked for the focus over, once its row is drawn. */
private const val RETURN_FOCUS_TRIES = 10

/** The beat a detail page is given to settle before its hero embed is mounted (item 11, v1.0.17). */

private val POSTER: Dp = 112.dp
private val POSTER_TV: Dp = 140.dp
private val QR_SIZE: Dp = 132.dp
private const val QR_PIXELS = 360
