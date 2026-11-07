package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Series row's deeper read (owner, v1.0.15): a series chat asked for the row's
 * sake gets its deeper read too, kept apart from the chat's own window ([HomeViewModel.series]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelSeriesTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun video(id: Long) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = "Video $id", sizeBytes = 600L * 1024 * 1024,
        durationSec = 2_600, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = id.toInt(),
    )

    private val window = listOf(video(1), video(2))
    private val deeper = (1L..12L).map(::video)

    private val source = object : HomeSource {
        override suspend fun chatMedia(chatId: Long) = window
        override suspend fun seriesMedia(chatId: Long) = deeper
        override suspend fun recentMedia() = emptyList<MediaItem>()
        override suspend fun mediaItem(chatId: Long, messageId: Long) = video(messageId)
    }

    @Test
    fun `a series chat's deeper read is kept apart from its window`() = runTest(dispatcher) {
        val model = HomeViewModel(source, disk = dispatcher)
        model.requestSeriesChats(listOf(7))
        advanceUntilIdle()
        assertEquals(deeper.map { it.messageId }, model.series.value.getValue(7).map { it.messageId })
        // The chat's own row keeps the window it was read with.
        assertEquals(window.map { it.messageId }, model.loaded.value.getValue(7).map { it.messageId })
    }

    @Test
    fun `a chat not asked for the series row gets no deeper read`() = runTest(dispatcher) {
        val model = HomeViewModel(source, disk = dispatcher)
        model.request(7)
        advanceUntilIdle()
        assertEquals(emptyMap<Long, List<MediaItem>>(), model.series.value)
    }
}
