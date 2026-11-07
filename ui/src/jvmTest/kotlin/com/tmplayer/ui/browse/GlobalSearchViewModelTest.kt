package com.tmplayer.ui.browse

import com.tmplayer.data.AllChatsCursor
import com.tmplayer.data.AllChatsPage
import com.tmplayer.data.ChatGate
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SearchChatType
import com.tmplayer.data.SearchFilters
import com.tmplayer.data.SearchQuality
import com.tmplayer.data.SearchSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        ChatGate.set(null)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long, name: String, chat: Long = id % 3 + 1) = MediaItem(
        chatId = chat,
        messageId = id,
        fileId = id.toInt(),
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = id.toInt(),
        fileName = name,
    )

    private val done = AllChatsCursor(videoDone = true, documentDone = true)
    private val asked = mutableListOf<Pair<String, SearchFilters>>()

    /** Telegram as a fixture: three thin pages for "harbour", nothing for anything else. */
    private val source = VideoSearchSource { query, cursor, filters ->
        asked += query to filters
        when {
            query != "harbour" -> AllChatsPage(emptyList(), done)
            cursor.videoOffset.isEmpty() -> AllChatsPage(listOf(video(1, "Harbour.Lights.2019.1080p.mkv")), AllChatsCursor(videoOffset = "2", documentDone = true))
            cursor.videoOffset == "2" -> AllChatsPage(listOf(video(2, "Harbour.Rain.2020.720p.mkv")), AllChatsCursor(videoOffset = "3", documentDone = true))
            else -> AllChatsPage(listOf(video(3, "Harbour.Wind.2021.2160p.mkv")), AllChatsCursor(videoOffset = "4", documentDone = true))
        }
    }

    @Test
    fun `typing settles before Telegram is asked, and only the last query goes`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("ha")
        model.type("harb")
        model.type("harbour")
        // Waiting for the typing to rest is not searching yet: no spinner, no "Nothing found".
        assertFalse(model.state.value.searching)
        assertTrue(model.state.value.settling)
        assertTrue(model.state.value.hiddenBy.isEmpty())
        advanceUntilIdle()
        // One query asked (its later pages included), never one per letter.
        assertEquals(listOf("harbour"), asked.map { it.first }.distinct())
        assertFalse(model.state.value.searching)
        assertFalse(model.state.value.settling)
    }

    @Test
    fun `the same words are never asked twice`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("harbour")
        advanceUntilIdle()
        val before = asked.size
        // Typed again with a space, submitted with the search key, and a quality added to it.
        model.type("harbour ")
        model.submit("harbour")
        model.type("harbour 4k")
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertEquals(listOf(3L), model.state.value.shown.map { it.lead.messageId })
    }

    @Test
    fun `the search key skips the wait, clearing drops what was waiting`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 10_000)
        model.type("harbour")
        model.submit("harbour")
        assertTrue(model.state.value.searching)
        dispatcher.scheduler.runCurrent()
        assertEquals("harbour", asked.first().first)
        advanceUntilIdle()
        val before = asked.size
        model.type("harbour wind")
        model.type("")
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertTrue(model.state.value.groups.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `one letter is not sent to Telegram`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("h")
        advanceUntilIdle()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `what was listed and still matches stays up until the new answer`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 100)
        model.type("harbour")
        advanceUntilIdle()
        model.type("harbour wind")
        // Still settling: the Wind card answers the new words and stays up, so the list never
        // flashes empty between one letter and the next.
        assertTrue(3L in model.state.value.groups.map { it.lead.messageId })
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a thin first answer keeps asking, three pages at most`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(3, asked.size)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.shown.map { it.lead.messageId })
        assertFalse(model.state.value.stillSearching)
    }

    @Test
    fun `what the app already knows shows before Telegram answers`() = runTest(dispatcher) {
        val local = video(40, "Harbour_Notes_2024.mkv")
        val model = GlobalSearchViewModel(source, known = { listOf(local) }, work = dispatcher, settleMs = 1_000)
        model.type("harbour notes")
        // Worked out off the main thread, but before the settle is over.
        dispatcher.scheduler.runCurrent()
        assertTrue(model.state.value.settling)
        assertEquals(listOf(40L), model.state.value.groups.map { it.lead.messageId })
    }

    @Test
    fun `a quality or a sort never asks again, a chat type does`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        val before = asked.size
        model.setFilters(SearchFilters(quality = SearchQuality.UHD))
        model.setSort(SearchSort.Newest)
        advanceUntilIdle()
        assertEquals(before, asked.size)
        assertEquals(listOf(3L), model.state.value.shown.map { it.lead.messageId })
        model.setFilters(SearchFilters(chatType = SearchChatType.Channels))
        advanceUntilIdle()
        assertTrue(asked.size > before)
        assertEquals(SearchChatType.Channels, asked.last().second.chatType)
    }

    @Test
    fun `a search that sat with results is remembered, one typed through is not`() = runTest(dispatcher) {
        val remembered = mutableListOf<String>()
        val model = GlobalSearchViewModel(source, onSearched = { remembered += it }, known = { emptyList() }, work = dispatcher, settleMs = 0, rememberMs = 2_000)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf("harbour"), remembered)
        model.type("nothing here")
        advanceUntilIdle()
        assertEquals(listOf("harbour"), remembered)
    }

    @Test
    fun `offline lists the local half and asks again on reconnect`() = runTest(dispatcher) {
        var online = false
        val flaky = VideoSearchSource { query, cursor, filters ->
            if (!online) throw VideoSearchSource.Offline()
            source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(flaky, known = { listOf(video(50, "Harbour.Days.2018.mkv")) }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertTrue(model.state.value.offline)
        assertEquals(listOf(50L), model.state.value.groups.map { it.lead.messageId })
        online = true
        model.reconnected()
        advanceUntilIdle()
        assertFalse(model.state.value.offline)
        assertTrue(model.state.value.groups.size > 1)
    }

    @Test
    fun `a flood wait counts down, then asks again`() = runTest(dispatcher) {
        var calls = 0
        val waiting = VideoSearchSource { query, cursor, filters ->
            calls++
            if (calls == 1) throw IllegalStateException("FLOOD_WAIT_3")
            source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(waiting, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        dispatcher.scheduler.advanceTimeBy(500)
        dispatcher.scheduler.runCurrent()
        assertEquals(3, model.state.value.floodWait)
        advanceUntilIdle()
        assertEquals(0, model.state.value.floodWait)
        assertTrue(model.state.value.groups.isNotEmpty())
    }

    @Test
    fun `a re-run for the same words never takes the results away`() = runTest(dispatcher) {
        var answerEmpty = false
        val flaky = VideoSearchSource { query, cursor, filters ->
            if (answerEmpty) AllChatsPage(emptyList(), done) else source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(flaky, known = { emptyList() }, work = dispatcher, settleMs = 0)
        val emissions = mutableListOf<GlobalSearchState>()
        backgroundScope.launch { model.state.toList(emissions) }
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), model.state.value.groups.map { it.lead.messageId })
        emissions.clear()
        // The settled search runs again for the same words (Retry, a reconnect, the search
        // key while settling) and this time Telegram's first page comes back empty, as it
        // does when its whole word matching or a spellings try finds nothing on a re-ask.
        answerEmpty = true
        model.retry()
        advanceUntilIdle()
        // Nothing for these words was ever replaced by an empty list in between, and what
        // was shown is still shown once the re-run has landed.
        assertTrue(emissions.isNotEmpty())
        assertTrue(emissions.none { it.query.text == "harbour" && it.groups.isEmpty() })
        assertEquals(listOf(1L, 2L, 3L), model.state.value.groups.map { it.lead.messageId })
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a pull refresh asks Telegram again for the same words, typing them again does not`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), model.state.value.shown.map { it.lead.messageId })
        val before = asked.size
        // The pull is the viewer asking for fresh answers: the same words go to Telegram again.
        model.refresh()
        advanceUntilIdle()
        assertTrue(asked.size > before)
        assertEquals(listOf(1L, 2L, 3L), model.state.value.shown.map { it.lead.messageId })
        assertFalse(model.state.value.busy)
        // The snapshot rule for the viewer's own typing stands: the same words, asked for by
        // typing or the search key, are not asked twice.
        val afterRefresh = asked.size
        model.type("harbour ")
        model.submit("harbour")
        advanceUntilIdle()
        assertEquals(afterRefresh, asked.size)
    }

    @Test
    fun `a pull refresh takes the fresh answer, even an empty one, where a re-run keeps what was shown`() = runTest(dispatcher) {
        var answerEmpty = false
        val flaky = VideoSearchSource { query, cursor, filters ->
            if (answerEmpty) AllChatsPage(emptyList(), done) else source.page(query, cursor, filters)
        }
        val model = GlobalSearchViewModel(flaky, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), model.state.value.groups.map { it.lead.messageId })
        answerEmpty = true
        // A re-run (Retry) keeps the shown cards when its first page comes back empty.
        model.retry()
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), model.state.value.groups.map { it.lead.messageId })
        // The pull asks for fresh answers, so an empty fresh answer is the answer: the list
        // takes it, as a changed query's empty answer does.
        model.refresh()
        advanceUntilIdle()
        assertTrue(model.state.value.groups.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a new query still starts clean and an empty answer empties the list`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L), model.state.value.groups.map { it.lead.messageId })
        model.type("nothing here")
        advanceUntilIdle()
        assertEquals("nothing here", model.state.value.query.text)
        assertTrue(model.state.value.groups.isEmpty())
        assertTrue(model.state.value.shown.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `clearing the words still clears the results`() = runTest(dispatcher) {
        val model = GlobalSearchViewModel(source, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        assertTrue(model.state.value.groups.isNotEmpty())
        model.type("")
        advanceUntilIdle()
        assertTrue(model.state.value.groups.isEmpty())
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `cam copies are excluded from the shown groups and counted, and included when the switch is off`() = runTest(dispatcher) {
        val camSource = VideoSearchSource { query, cursor, filters ->
            if (query != "harbour") {
                AllChatsPage(emptyList(), done)
            } else {
                AllChatsPage(
                    listOf(
                        video(61, "Harbour.Lights.2019.1080p.WEB-DL.mkv"),
                        video(62, "Harbour.Lights.2019.PreDVD.mkv"),
                        video(63, "Harbour.Shadows.2020.CAM.mkv"),
                    ),
                    done,
                )
            }
        }
        val model = GlobalSearchViewModel(camSource, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        // The Lights card keeps only its WEB-DL copy, so its lead and its copy count follow;
        // the all cam Shadows card is gone altogether, and both hidden copies are counted.
        assertEquals(listOf(61L), model.state.value.shown.map { it.lead.messageId })
        assertEquals(listOf(61L), model.state.value.shown.single().copies.map { it.messageId })
        assertEquals(2, model.state.value.camHidden)
        // The switch only screens the answer already listed: off, and both copies are back
        // without Telegram being asked again.
        model.setHideCamCopies(false)
        assertEquals(0, model.state.value.camHidden)
        assertEquals(2, model.state.value.shown.size)
        assertEquals(
            listOf(61L, 62L),
            model.state.value.shown.first { it.lead.messageId == 61L }.copies.map { it.messageId },
        )
    }

    @Test
    fun `videos under five minutes are excluded from the shown groups and counted, unstated lengths stay`() = runTest(dispatcher) {
        val shortSource = VideoSearchSource { query, cursor, filters ->
            if (query != "harbour") {
                AllChatsPage(emptyList(), done)
            } else {
                AllChatsPage(
                    listOf(
                        video(71, "Harbour.Lights.2019.1080p.WEB-DL.mkv"),
                        video(72, "Harbour.Lights.2019.720p.WEB-DL.mkv").copy(durationSec = 120),
                        video(73, "Harbour.Shadows.2020.1080p.WEB-DL.mkv").copy(durationSec = 0),
                    ),
                    done,
                )
            }
        }
        val model = GlobalSearchViewModel(shortSource, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        // The Lights card keeps only its long copy; the Shadows card stands on its
        // unstated length copy, which is never hidden as short; one copy is counted.
        assertEquals(1, model.state.value.shortHidden)
        assertEquals(
            listOf(71L),
            model.state.value.shown.first { it.lead.messageId == 71L }.copies.map { it.messageId },
        )
        assertTrue(model.state.value.shown.any { it.lead.messageId == 73L })
        model.setHideShortVideos(false)
        assertEquals(0, model.state.value.shortHidden)
        assertEquals(
            listOf(71L, 72L),
            model.state.value.shown.first { it.lead.messageId == 71L }.copies.map { it.messageId },
        )
    }

    @Test
    fun `the search ask is per chat while a choice narrows or is unread, account wide only on a known every chat`() {
        // Owner, v1.0.15: "Global Search off" must take effect. A picked set asks per chat;
        // a stored answer still being read (null ids, not known) must not be taken for the
        // viewer choosing every chat; only a known, narrowing-nothing answer asks widely.
        assertEquals(SearchAsk.PerChat, searchAsk(added = setOf(2L, 3L), scopeKnown = true))
        assertEquals(SearchAsk.PerChat, searchAsk(added = setOf(2L, 3L), scopeKnown = false))
        assertEquals(SearchAsk.PerChat, searchAsk(added = null, scopeKnown = false))
        assertEquals(SearchAsk.AllChats, searchAsk(added = null, scopeKnown = true))
    }

    @Test
    fun `a deleted video's card goes when it is opened`() = runTest(dispatcher) {
        val gone = object : VideoSearchSource {
            override suspend fun page(query: String, cursor: AllChatsCursor, filters: SearchFilters) = source.page(query, cursor, filters)
            override suspend fun stillThere(item: MediaItem): Boolean = item.messageId != 1L
        }
        val model = GlobalSearchViewModel(gone, known = { emptyList() }, work = dispatcher, settleMs = 0)
        model.type("harbour")
        advanceUntilIdle()
        val first = model.state.value.groups.first().lead
        assertFalse(model.check(first))
        assertFalse(model.state.value.groups.any { g -> g.copies.any { it.messageId == 1L } })
    }
}
