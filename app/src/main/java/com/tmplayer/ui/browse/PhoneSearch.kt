package com.tmplayer.ui.browse

import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.components.riseIn
import com.tmplayer.ui.components.rememberLessMotion
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.Micro
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AllChatsSearch
import com.tmplayer.data.ChatBrief
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.GlobalSearch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.ResultGroup
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import com.tmplayer.ui.components.PhoneSheet
import com.tmplayer.ui.components.PosterArt
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.components.Spinner
import com.tmplayer.ui.components.TouchPullRefresh
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.components.secondaryPress
import com.tmplayer.ui.components.TmSecondaryButton
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.data.HomeRow
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.tmplayer.ui.components.SlowAnswerNotice

/**
 * The phone's search across every chat (CP73): a page of its own over the browser, its field
 * docked at the bottom above the keyboard where the bar's magnifier was, with the close button in
 * the magnifier's own place.
 *
 * Before anything is typed it offers the way back to things: recent chats (starred first), recent
 * searches (fill one in with the arrow, take one off with its cross or a hold, clear them all with
 * a few seconds to undo), and up to three videos to carry on with. Typing lists at once the chats
 * whose names match and the videos this device already knows, then Telegram's answer once the
 * typing settles ([GlobalSearchViewModel]). Each video is one card however many chats it was
 * posted in; a tap opens it as a video's tile does.
 *
 * Back closes the keyboard first (the system does that), then the filter sheet, then the search.
 * There is no swipe down to close: the page is a list, and a list is swiped. A pull down on the
 * list is the one swipe with a job of its own: it runs the search again fresh
 * ([GlobalSearchViewModel.refresh]), on the typed results and on the page before typing alike.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PhoneSearchScreen(
    state: GlobalSearchState,
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    /** Goes on the field, which the bar's magnifier grows into. */
    fieldModifier: Modifier,
    chats: List<ChatSummary>,
    favourites: Set<Long>,
    /** Hidden while the kids lock is on, and while searches are not remembered. */
    recentSearches: List<String>,
    continueWatching: List<ResumeRecord>,
    continueArt: (ResumeRecord) -> MediaItem,
    progress: (MediaItem) -> Float?,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenGroup: (ResultGroup) -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onFilters: (SearchFilters) -> Unit,
    onSort: (SearchSort) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** A pull down on the list: the search runs again fresh, Telegram asked anew. */
    onRefresh: () -> Unit,
    /** A result row's play button: the title's default copy, played at once. */
    onPlayGroup: (ResultGroup) -> Unit,
    /** The scope row's and the nowhere-to-look notice's way into the chat picker. */
    onChooseChats: () -> Unit,
    /** The notice's other way out: Settings, where the Global Search switch lives. */
    onOpenGlobalSearch: () -> Unit,
    /** A Continue watching row's play button: that video, played at once. */
    onPlayItem: (MediaItem, String) -> Unit,
    /** Whether a show result has an episode posted since the viewer last watched it. */
    showHasNewEpisodes: (ResultGroup) -> Boolean = { false },
    onRemoveRecent: (String) -> Unit,
    /** Clears them all and hands back what was there, for the Undo. */
    onClearRecent: () -> List<String>,
    onRestoreRecent: (List<String>) -> Unit,
    onVoice: (() -> Unit)?,
    listState: LazyListState,
    /** The categories Home's chats hold, for the browse sections before typing (owner, v1.0.17). */
    categoryRows: List<HomeRow.Catalog> = emptyList(),
    /** Opens a category's page on Home: a row key, or "langpage-<code>" for a language. */
    onOpenCategory: (String) -> Unit = {},
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var allChats by rememberSaveable(state.query.text) { mutableStateOf(false) }

    BackHandler(enabled = !sheetOpen) { onClose() }

    // The keyboard comes up once the field has finished growing out of the magnifier. Asked for at
    // once, it rose during the growth, and the field chased a spot the keyboard kept moving.
    val entered = LocalSearchEntered.current
    LaunchedEffect(Unit) {
        snapshotFlow { entered() }.first { it }
        runCatching { focus.requestFocus() }
    }

    val query = text.text
    // A popup left over from the empty page (the cleared recents, with their Undo) does not
    // follow the viewer into the results: once words are typed, it goes. On the blank page it
    // goes by itself after a few seconds (see RECENT_CLEARED_MS where it is shown); Undo works
    // until either way out fires.
    LaunchedEffect(query) {
        if (query.isNotBlank()) snackbar.currentSnackbarData?.dismiss()
    }
    val byUsername = rememberUsernameChat(query)
    // Every chat's name matched off the main thread, the last answer kept up meanwhile, so the
    // field never waits for it between letters.
    var localChats by remember { mutableStateOf(emptyList<ChatSummary>()) }
    LaunchedEffect(chats, query, favourites) {
        localChats = if (query.isBlank()) emptyList() else withContext(Dispatchers.Default) { GlobalSearch.localChats(chats, query, favourites) }
    }
    val chatMatches = remember(localChats, byUsername) { withUsernameChat(localChats, byUsername) }
    // A pasted post or invite link, or a public chat to join (CP79).
    val offer = rememberLinkOffer(query, byUsername)
    val recentChats = remember(chats, favourites) { GlobalSearch.recentChats(chats, favourites) }
    val briefs = remember(state.chats, chats) { state.chats + chats.associate { it.id to ChatBrief.of(it) } }
    val titleOf: (Long) -> String = { id -> briefs[id]?.title.orEmpty() }

    // A pull down runs the search again fresh. The spinner stays for a short acknowledgement
    // at least, and until the fresh run lands at most, the way a pulled page of the browser
    // keeps its own (PhonePageFrame).
    var pulling by remember { mutableStateOf(false) }
    val runBusy by rememberUpdatedState(state.searching || state.stillSearching || state.settling)
    LaunchedEffect(pulling) {
        if (!pulling) return@LaunchedEffect
        delay(PULL_ACK_MS)
        withTimeoutOrNull(PULL_HOLD_MS) { snapshotFlow { runBusy }.first { busy -> !busy } }
        pulling = false
    }

    Box(Modifier.fillMaxSize().background(Tone.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            TouchPullRefresh(
                isRefreshing = pulling,
                onRefresh = {
                    if (!pulling) {
                        pulling = true
                        onRefresh()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item(key = "top") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(8.dp)) }
                if (query.isBlank()) {
                    emptySearch(
                        chats = recentChats,
                        recent = recentSearches.take(GlobalSearch.RECENT_SHOWN),
                        continueWatching = continueWatching.take(CONTINUE_SHOWN),
                        continueArt = continueArt,
                        onOpenChat = onOpenChat,
                        onPick = { picked ->
                            onText(TextFieldValue(picked, TextRange(picked.length)))
                            onSubmit()
                        },
                        onFill = { picked -> onText(TextFieldValue("$picked ", TextRange(picked.length + 1))) },
                        onRemove = onRemoveRecent,
                        onClearAll = {
                            val before = onClearRecent()
                            scope.launch {
                                // Away by itself after a few seconds (owner, v1.0.14): with
                                // Long alone the popup sat on the search home for good, since
                                // the only other way out is typing, which an idle home never
                                // does. The timer is this popup's own; Undo works until it
                                // fires, and the host's Long stays as the outer bound.
                                val timer = launch {
                                    delay(RECENT_CLEARED_MS)
                                    snackbar.currentSnackbarData?.dismiss()
                                }
                                val answer = snackbar.showSnackbar(s.searchRecentCleared, actionLabel = s.searchUndo, duration = SnackbarDuration.Long)
                                timer.cancel()
                                if (answer == SnackbarResult.ActionPerformed) onRestoreRecent(before)
                            }
                        },
                        onResume = onResume,
                        onPlayItem = onPlayItem,
                    )
                    browseCategories(rows = categoryRows, onOpenCategory = onOpenCategory)
                } else {
                    // The scope the search is looking in (owner, v1.0.16): the line says
                    // which, and tapping it goes to where that is decided, the picker for
                    // a picked scope, Settings (and its Global Search switch) for the
                    // account wide one.
                    item(key = "scope") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { if (state.scopeAll) onOpenGlobalSearch() else onChooseChats() }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.browseSearchScope, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.scopeAll) s.browseSearchScopeAllVideos else s.browseSearchScopeChats,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Tone.accent,
                            )
                        }
                    }
                    if (state.scopeMissing) {
                        item(key = "scope-missing") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(s.browseSearchScopeMissing, style = MaterialTheme.typography.bodyMedium, color = Tone.text)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = onChooseChats) { Text(s.pickerChooseChats) }
                                    TextButton(onClick = onOpenGlobalSearch) { Text(s.browseSearchTurnOnGlobal) }
                                }
                            }
                        }
                    }
                    offer?.let { found ->
                        item(key = "link-offer") {
                            com.tmplayer.ui.components.ChoiceLine(found.label, detail = found.detail, icon = TmIcons.Send) {
                                keyboard?.hide()
                                onClose()
                                LinkRequests.ask(found.link)
                            }
                        }
                    }
                    results(
                        state = state,
                        chatMatches = chatMatches,
                        allChats = allChats,
                        onShowAllChats = { allChats = true },
                        briefs = briefs,
                        progress = progress,
                        onOpenChat = onOpenChat,
                        onOpenGroup = { keyboard?.hide(); onOpenGroup(it) },
                        onPlayGroup = onPlayGroup,
                        showHasNewEpisodes = showHasNewEpisodes,
                        onRetry = onRetry,
                        onClearFilters = { onFilters(SearchFilters()) },
                        onSearchWord = { word -> onText(TextFieldValue(word, TextRange(word.length))); onSubmit() },
                    )
                }
            }
            // A long run of results is one hold away from its end.
            FastScroller(listState)
            }
            }

            // The filters once there is something to filter, or a filter is on.
            if (query.isNotBlank() && (state.groups.isNotEmpty() || !state.filters.isDefault || state.sort != SearchSort.BestMatch)) {
                FilterRow(state, onFilters = onFilters, onMore = { keyboard?.hide(); sheetOpen = true })
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = BAR_MARGIN),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SEARCH_GAP),
            ) {
                SearchField(
                    text = text,
                    onText = onText,
                    onSubmit = { keyboard?.hide(); onSubmit() },
                    onVoice = onVoice,
                    modifier = Modifier.weight(1f).then(fieldModifier).focusRequester(focus),
                )
                // Where the bar's magnifier was: the way back out.
                val closePress = remember { MutableInteractionSource() }
                com.tmplayer.ui.components.IconTip(s.searchClose) {
                    Box(
                        Modifier
                            .size(BAR_HEIGHT)
                            .pressScale(closePress)
                            .shadow(BAR_SHADOW, CircleShape)
                            .clip(CircleShape)
                            .background(FloatingTone.menu)
                            .border(floatingBorder(), CircleShape)
                            .clickable(interactionSource = closePress, indication = ripple(), role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TmIcons.Close, contentDescription = s.searchClose, tint = Tone.text, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = BAR_HEIGHT + BAR_MARGIN + BAR_GAP))
    }

    // The next page well before the end, the way a chat's grid pages.
    com.tmplayer.ui.components.LoadMoreAhead(
        listState,
        enabled = state.groups.isNotEmpty() && !state.loadingMore && !state.endReached,
        ahead = PAGE_LEAD - 1,
        onLoadMore = onLoadMore,
    )

    if (sheetOpen) {
        FilterSheet(state, onFilters = onFilters, onSort = onSort, onDismiss = { sheetOpen = false })
    }
}

/** The field: a rounded pill with the magnifier, the text, and Clear while there is text or the microphone while there is none. */
@Composable
private fun SearchField(
    text: TextFieldValue,
    onText: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onVoice: (() -> Unit)?,
    modifier: Modifier,
) {
    val s = LocalStrings.current
    // While the field has the keyboard its edge and its magnifier take the accent, softly, so the
    // eye finds where the typing goes.
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(
        if (focused) Tone.accent.copy(alpha = 0.6f) else Color.Transparent,
        Motion.standard(Motion.Fast),
        label = "searchEdge",
    )
    val glass by animateColorAsState(if (focused) Tone.accent else Tone.muted, Motion.standard(Motion.Fast), label = "searchGlass")
    Row(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .height(BAR_HEIGHT)
            .shadow(BAR_SHADOW, CircleShape)
            .clip(CircleShape)
            .background(FloatingTone.menu)
            .border(floatingBorder(), CircleShape)
            .border(1.5.dp, edge, CircleShape)
            // Clear and the microphone draw a 40 dp circle in a 48 dp target: 8 dp here puts it
            // 12 dp from the round end, as from the top and bottom of the 64 dp pill.
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TmIcons.Search, contentDescription = null, tint = glass, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Tone.text),
            cursorBrush = SolidColor(Tone.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.text.isEmpty()) {
                        Text(s.searchFieldHint, style = MaterialTheme.typography.bodyLarge, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
        // Clear grows in as the first letter is typed and the microphone comes back as the last
        // goes, one turning into the other rather than blinking.
        val end = when {
            text.text.isNotEmpty() -> FieldEnd.Clear
            onVoice != null -> FieldEnd.Voice
            else -> FieldEnd.None
        }
        val still = rememberLessMotion()
        AnimatedContent(
            targetState = end,
            transitionSpec = {
                if (still) {
                    fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast))
                } else {
                    (fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Micro.release(), initialScale = 0.5f)) togetherWith
                        (fadeOut(Motion.standard(Motion.Fast)) + scaleOut(Motion.standard(Motion.Fast), targetScale = 0.5f))
                }
            },
            label = "searchFieldEnd",
        ) { shown ->
            when (shown) {
                FieldEnd.Clear -> com.tmplayer.ui.components.IconTip(s.browseClearSearch) {
                    IconButton(onClick = { onText(TextFieldValue("")) }) {
                        Icon(TmIcons.Close, contentDescription = s.browseClearSearch, tint = Tone.muted)
                    }
                }
                FieldEnd.Voice -> com.tmplayer.ui.components.IconTip(s.browseSearchByVoice) {
                    IconButton(onClick = { onVoice?.invoke() }) {
                        Icon(TmIcons.Mic, contentDescription = s.browseSearchByVoice, tint = Tone.muted)
                    }
                }
                FieldEnd.None -> Unit
            }
        }
    }
}

/** What sits at the field's end: Clear while there is text, else the microphone where there is one. */
private enum class FieldEnd { Clear, Voice, None }

/** What an empty search offers: recent chats, recent searches, and videos to carry on with. */
@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.emptySearch(
    chats: List<ChatSummary>,
    recent: List<String>,
    continueWatching: List<ResumeRecord>,
    continueArt: (ResumeRecord) -> MediaItem,
    onOpenChat: (ChatSummary) -> Unit,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onResume: (ResumeRecord) -> Unit,
    onPlayItem: (MediaItem, String) -> Unit,
) {
    if (chats.isNotEmpty()) {
        item(key = "chats-h") { Heading(LocalStrings.current.searchChatsHeading) }
        item(key = "chats") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(chats, key = { it.id }) { chat ->
                    Column(
                        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(chat) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ChatAvatar(ChatBrief.of(chat), 56.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(chat.title, style = MaterialTheme.typography.labelSmall, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (recent.isNotEmpty()) {
        item(key = "recent-h") {
            val s = LocalStrings.current
            Row(
                Modifier
                    .then(if (com.tmplayer.ui.theme.Motion.reduced) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Heading(s.browseRecentSearches) }
                TextButton(onClick = onClearAll) { Text(s.searchClearRecent) }
            }
        }
        items(recent, key = { "r-$it" }) { entry ->
            val s = LocalStrings.current
            Row(
                Modifier
                    // A search taken off folds away and the rest close up; one added fades in.
                    .then(if (com.tmplayer.ui.theme.Motion.reduced) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .secondaryPress { onRemove(entry) }
                    .combinedClickable(onClick = { onPick(entry) }, onLongClick = { onRemove(entry) })
                    .padding(start = 16.dp, end = 4.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TmIcons.History, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Text(entry, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                com.tmplayer.ui.components.IconTip(s.searchFillIn(entry)) {
                    IconButton(onClick = { onFill(entry) }) {
                        Icon(TmIcons.ArrowUpLeft, contentDescription = s.searchFillIn(entry), tint = Tone.muted, modifier = Modifier.size(20.dp))
                    }
                }
                com.tmplayer.ui.components.IconTip(s.searchRemoveRecent(entry)) {
                    IconButton(onClick = { onRemove(entry) }) {
                        Icon(TmIcons.Close, contentDescription = s.searchRemoveRecent(entry), tint = Tone.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (continueWatching.isNotEmpty()) {
        item(key = "continue-h") { Heading(LocalStrings.current.homeRowContinue) }
        items(continueWatching, key = { "c-${it.chatId}_${it.messageId}" }) { record ->
            val item = continueArt(record)
            // The same full width row shape as the typed results (item 14): the row resumes,
            // its round button plays at once.
            NetflixSearchRow(
                item = item,
                title = record.title,
                facts = record.chatTitle,
                fraction = record.fraction(),
                fresh = false,
                onPlay = { onPlayItem(item, record.chatTitle) },
                modifier = Modifier.clickable { onResume(record) },
            )
        }
    }
}

private fun ResumeRecord.fraction(): Float? =
    if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** What a search with text lists: matching chats, then the videos, then how the search is going. */
private fun androidx.compose.foundation.lazy.LazyListScope.results(
    state: GlobalSearchState,
    chatMatches: List<ChatSummary>,
    allChats: Boolean,
    onShowAllChats: () -> Unit,
    briefs: Map<Long, ChatBrief>,
    progress: (MediaItem) -> Float?,
    onOpenChat: (ChatSummary) -> Unit,
    onOpenGroup: (ResultGroup) -> Unit,
    onPlayGroup: (ResultGroup) -> Unit,
    showHasNewEpisodes: (ResultGroup) -> Boolean,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onSearchWord: (String) -> Unit,
) {
    if (chatMatches.isNotEmpty()) {
        item(key = "chats-h") { Heading(LocalStrings.current.searchChatsHeading) }
        val shown = if (allChats) chatMatches else chatMatches.take(GlobalSearch.CHAT_PREVIEW)
        items(shown, key = { "chat-${it.id}" }) { chat ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenChat(chat) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ChatAvatar(ChatBrief.of(chat), 40.dp)
                Text(chat.title, style = MaterialTheme.typography.bodyLarge, color = Tone.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (!allChats && chatMatches.size > GlobalSearch.CHAT_PREVIEW) {
            item(key = "chats-all") {
                TextButton(onClick = onShowAllChats, modifier = Modifier.padding(start = 8.dp)) {
                    Text(LocalStrings.current.searchShowAllChats(chatMatches.size))
                }
            }
        }
    }
    item(key = "videos-h") { Heading(LocalStrings.current.searchVideosHeading) }
    searchStatus(state)?.let { status ->
        item(key = "status") {
            Note(status, spinner = state.stillSearching || state.floodWait > 0) {
                // Offline too: the search runs again on its own once back, and this is the way
                // to ask sooner, so the line is never one with nothing to press.
                if (state.error != null || state.offline) TmSecondaryButton(onClick = onRetry) { Text(LocalStrings.current.commonRetry) }
            }
        }
    }
    // The cam copies the Playback switch keeps out of these results, counted the way the
    // chats count theirs: a plain line over the results, in the status line's own style.
    if (state.camHidden > 0) {
        item(key = "cam-hidden") { Note(LocalStrings.current.browseHiddenByCam(state.camHidden)) }
    }
    // The videos under 5 minutes the other Playback switch keeps out, counted the same way.
    if (state.shortHidden > 0) {
        item(key = "short-hidden") { Note(LocalStrings.current.browseHiddenByShort(state.shortHidden)) }
    }
    val shown = state.shown
    when {
        // While the typing settles too, rather than a heading over nothing until Telegram is asked.
        shown.isEmpty() && (state.searching || state.settling) -> {
            items(SKELETONS, key = { "sk-$it" }) {
                SearchResultSkeleton(Modifier.padding(horizontal = 8.dp))
            }
            // A first page that will not come: Telegram is slow, with Retry, after the usual wait.
            if (state.searching) {
                item(key = "slow") { SlowAnswerNotice(key = state.query, onRetry = onRetry, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) }
            }
        }
        shown.isEmpty() && !state.busy && state.error == null -> item(key = "none") {
            val s = LocalStrings.current
            // Rises in a beat after the search settles, rather than snapping in over the skeletons,
            // under the empty pages' picture ([EmptyArt]) drawing itself in.
            Column(
                Modifier.fillMaxWidth().riseIn().padding(horizontal = 16.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val hidden = state.hiddenBy
                com.tmplayer.ui.components.EmptyArt(
                    if (hidden.isNotEmpty()) TmIcons.FilterHorizontal else TmIcons.SearchRemove,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (hidden.isNotEmpty()) {
                    hidden.forEach { Text(it.message(state), style = MaterialTheme.typography.bodyLarge, color = Tone.text, textAlign = TextAlign.Center) }
                    TmSecondaryButton(onClick = onClearFilters) { Text(s.searchClearFilters) }
                } else {
                    Text(s.searchNone(state.query.text), style = MaterialTheme.typography.titleMedium, color = Tone.text, textAlign = TextAlign.Center)
                    Text(s.searchNoneDetail, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, textAlign = TextAlign.Center)
                    AllChatsSearch.fallbackQuery(state.query.server)?.let { word ->
                        TmSecondaryButton(onClick = { onSearchWord(word) }) { Text(s.searchSearchFor(word)) }
                    }
                }
            }
        }
        else -> items(shown, key = { it.key }) { group ->
            // The Netflix row shape (owner, 2026-11-07, item 14): a landscape thumbnail at
            // the start, the title and its facts beside it, and a round play button at the
            // end edge. The row itself opens the title's page, as the card did; the button
            // plays the default copy at once. The item key is the card's own, so the list
            // keeps its places and its scroll.
            NetflixSearchRow(
                item = remember(group) { group.asItem() },
                title = remember(group) { GlobalSearch.titleOf(group) },
                facts = remember(group) { resultFacts(group) },
                fraction = group.copies.firstNotNullOfOrNull { progress(it) },
                fresh = remember(group) { showHasNewEpisodes(group) },
                onPlay = { onPlayGroup(group) },
                // Results fade in as they arrive and slide to their places when the order or a
                // filter changes, rather than the list redrawing at once.
                modifier = Modifier
                    .then(if (shown.size <= ANIMATED_RESULTS_MAX && !com.tmplayer.ui.theme.Motion.reduced) Modifier.animateItem() else Modifier)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenGroup(group) },
                chatsLine = {
                    val s = LocalStrings.current
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AvatarStack(group.chatIds, briefs, Modifier.weight(1f, fill = false))
                        // Files, as the page counts its versions: the same file in two chats is one.
                        val files = remember(group.copies) { group.fileCount }
                        if (files > 1 && !group.isShow) CopiesChip(files)
                        if (group.copies.any { it.onDevice }) {
                            Icon(TmIcons.Download, contentDescription = s.searchOnDevice, tint = Tone.accent, modifier = Modifier.size(14.dp))
                        }
                        if (group.copies.all { !it.canBeSaved }) {
                            Icon(TmIcons.Lock, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(14.dp))
                        }
                    }
                },
            )
        }
    }
    if (state.loadingMore) item(key = "more") { Note(LocalStrings.current.gridLoadingMore, spinner = true) }
}

/**
 * One title as a full width row (owner, 2026-11-07, item 14, the Netflix search shape): a
 * landscape thumbnail at the start with its badges on the art, the title and its facts beside
 * it, and a round outline play button pinned at the end edge. The caller's modifier carries
 * the row's own tap, which opens the title's page; the button plays at once, and its own tap
 * does not reach the row's. The badges the row's data carries are drawn and no others: a show
 * with a new episode wears Home's own [NewBadge] tag across the foot of its art, the way the
 * screenshot's rows wear theirs. A result group carries no Recently added or Top 10 flag, so
 * no such tag is drawn here rather than one being invented.
 */
@Composable
private fun NetflixSearchRow(
    item: MediaItem,
    title: String,
    facts: String,
    fraction: Float?,
    fresh: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Under the facts: a result's chats and copies line. Null for a Continue watching row. */
    chatsLine: (@Composable () -> Unit)? = null,
) {
    val s = LocalStrings.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(SEARCH_THUMB_WIDTH).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Tone.surfaceHigh),
        ) {
            val meta = rememberMeta(item)
            PosterArt(
                miniThumbnail = item.miniThumbnail,
                thumbnailFileId = item.thumbnailFileId,
                cover = rememberCover(item),
                posterUrl = meta?.wideUrl ?: meta?.posterUrl,
                modifier = Modifier.fillMaxSize(),
                compactCover = true,
                showCoverTitle = false,
            )
            if (fresh) {
                NewBadge(compact = true, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
            }
            if (fraction != null && fraction > 0f) {
                ProgressLine(fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Tone.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts.isNotBlank()) {
                Text(facts, style = MaterialTheme.typography.bodySmall, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            chatsLine?.invoke()
        }
        Box(
            Modifier
                .size(SEARCH_PLAY_SIZE)
                .clip(CircleShape)
                .border(1.5.dp, Tone.text.copy(alpha = 0.7f), CircleShape)
                .clickable(role = Role.Button, onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TmIcons.PlaySolid, contentDescription = s.commonPlay, tint = Tone.text, modifier = Modifier.size(20.dp))
        }
    }
}

/** The quick filters over the field: the kind of chat, and the rest behind Filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(state: GlobalSearchState, onFilters: (SearchFilters) -> Unit, onMore: () -> Unit) {
    val s = LocalStrings.current
    val extras = listOf(
        state.filters.quality != SearchQuality.Any,
        state.filters.hideSmall,
        !state.filters.includeArchived,
        state.sort != SearchSort.BestMatch,
    ).count { it }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        item(key = "more") {
            FilterChip(
                selected = extras > 0,
                onClick = onMore,
                label = { Text(if (extras > 0) "${s.searchFilters} $extras" else s.searchFilters) },
                leadingIcon = { Icon(TmIcons.FilterHorizontal, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
        }
        items(SearchChatType.entries, key = { it.name }) { type ->
            val chipTick = com.tmplayer.ui.components.rememberToggleTick()
            FilterChip(
                selected = state.filters.chatType == type,
                onClick = {
                    if (state.filters.chatType != type) chipTick(true)
                    onFilters(state.filters.copy(chatType = type))
                },
                label = { Text(type.label) },
            )
        }
    }
}

/** Everything else: the order, the picture size, small clips, the archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(state: GlobalSearchState, onFilters: (SearchFilters) -> Unit, onSort: (SearchSort) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    PhoneSheet(onDismissRequest = onDismiss) {
        Heading(s.searchSort)
        SearchSort.entries.forEach { sort ->
            ChoiceLine(sort.label, chosen = state.sort == sort) { onSort(sort) }
        }
        Heading(s.searchFilters)
        SearchQuality.entries.forEach { quality ->
            ChoiceLine(quality.label, chosen = state.filters.quality == quality) { onFilters(state.filters.copy(quality = quality)) }
        }
        SwitchLine(s.searchFilterHideSmall, state.filters.hideSmall) { onFilters(state.filters.copy(hideSmall = it)) }
        SwitchLine(s.searchFilterIncludeArchived, state.filters.includeArchived) { onFilters(state.filters.copy(includeArchived = it)) }
        Spacer(Modifier.height(16.dp))
    }
}

/** One choice in the sheet: no radio button, the chosen line tinted and ticked, as in the Sort popover. */
@Composable
private fun ChoiceLine(label: String, chosen: Boolean, onClick: () -> Unit) {
    com.tmplayer.ui.components.ChoiceRow(label, icon = null, chosen = chosen, modifier = Modifier.padding(horizontal = 8.dp), onClick = onClick)
}

@Composable
private fun SwitchLine(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val tick = com.tmplayer.ui.components.rememberToggleTick()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { tick(!on); onChange(!on) }.padding(horizontal = 16.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tone.text, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Tone.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun Note(text: String, spinner: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spinner) Spinner(size = 18.dp, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, modifier = Modifier.weight(1f, fill = false))
        action?.invoke()
    }
}

/** How long a pull's spinner stays after the pull, as the acknowledgement of it. */
private const val PULL_ACK_MS = 900L

/** The longest the pull's spinner waits on a fresh run that says it is still going. */
private const val PULL_HOLD_MS = 8_000L

/** How long the "Recent searches cleared" popup stays before it dismisses itself, its Undo live throughout. */
private const val RECENT_CLEARED_MS = 4_500L

/** A search row's landscape thumbnail: wide enough to recognise, narrow enough to leave the title its room. */
private val SEARCH_THUMB_WIDTH = 132.dp

/** A search row's round play button, a full touch target at the row's end edge. */
private val SEARCH_PLAY_SIZE = 44.dp

/** Videos to carry on with on an empty search: three is a glance, more is History. */
private const val CONTINUE_SHOWN = 3

private const val PAGE_LEAD = 6

/** The longest list of results that slides to a new order rather than being redrawn in it. */
private const val ANIMATED_RESULTS_MAX = 300

private val SKELETONS = List(5) { it }

/**
 * Browse before typing (owner, v1.0.17), in the streaming apps' shape: the genres
 * the chats hold as two column tiles, then the featured collections as a list,
 * languages first. Every tap opens that category's page on Home.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.browseCategories(
    rows: List<HomeRow.Catalog>,
    onOpenCategory: (String) -> Unit,
) {
    val genres = rows.filter { it.kind.startsWith("genre-") || it.kind.startsWith("series-genre-") }
        .distinctBy { it.label }
        .take(8)
    if (genres.isNotEmpty()) {
        item(key = "browse-genres-head") {
            val s = LocalStrings.current
            Text(
                s.searchBrowseGenres,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
        }
        item(key = "browse-genres") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { row ->
                            Box(
                                Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(12.dp)).background(Tone.surfaceHigh)
                                    .clickable(role = Role.Button) { onOpenCategory(row.key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Tone.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    val codes = LinkedHashSet<String>()
    rows.forEach { row ->
        when {
            row.kind.startsWith("series-lang-") -> codes += row.kind.removePrefix("series-lang-")
            row.kind.startsWith("lang-") -> codes += row.kind.removePrefix("lang-")
        }
    }
    val specials = listOf("uhd", "hdr", "remastered", "best-quality", "top-rated", "foreign", "binge", "ml-audio")
        .mapNotNull { kind -> rows.firstOrNull { it.kind == kind } }
    if (codes.isNotEmpty() || specials.isNotEmpty()) {
        item(key = "browse-collections-head") {
            val s = LocalStrings.current
            Text(
                s.searchFeaturedCollections,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Tone.text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        items(
            codes.size + specials.size,
            key = { index -> if (index < codes.size) "browse-lang-${codes.elementAt(index)}" else "browse-col-${specials[index - codes.size].kind}" },
        ) { index ->
            val s = LocalStrings.current
            if (index < codes.size) {
                val code = codes.elementAt(index)
                BrowseCollectionLine(com.tmplayer.data.HomeRows.languageName(code) ?: code) { onOpenCategory("langpage-$code") }
            } else {
                val row = specials[index - codes.size]
                BrowseCollectionLine(homeRowTitle(row, s)) { onOpenCategory(row.key) }
            }
        }
    }
}

/** One featured collection line: the name and a chevron, the whole line a tap. */
@Composable
private fun BrowseCollectionLine(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Icon(TmIcons.ChevronRight, contentDescription = null, tint = Tone.muted, modifier = Modifier.size(20.dp))
    }
}
