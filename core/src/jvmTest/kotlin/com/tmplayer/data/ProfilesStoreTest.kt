package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Viewer profiles at the stores (v1.0.15, item 1): the default profile reads the legacy
 * unprefixed keys an install already has, and a second profile's records never leak into the
 * first's flows, in either direction.
 */
class ProfilesStoreTest {

    private val dir = Files.createTempDirectory("tm-profiles").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))
    private val watched = WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME)))

    @After
    fun resetProfile() {
        ProfileScope.set(ViewerProfiles.DEFAULT_ID)
    }

    private fun watchedRecord(messageId: Long, at: Long) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = false,
    )

    private fun laterRecord(messageId: Long, at: Long) = WatchLaterRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", fileName = "ep$messageId.mkv",
        chatTitle = "Show", sizeBytes = 1_000, durationSec = 1_400, mimeType = "video/mp4", addedAt = at,
    )

    @Test
    fun `the default profile reads legacy keys and writes no others`() = runBlocking {
        settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, description = resumeLine())
        watched.markWatched(watchedRecord(2L, at = 10))
        watched.watchLater.add(laterRecord(3L, at = 5))

        val raw = settings.rawEntries().keys
        assertTrue(raw.contains("resume_-100_1"))
        assertTrue(raw.none { it.startsWith("pf_") })
        assertEquals(listOf(1L), settings.continueWatching.first().map { it.messageId })
        assertEquals(listOf(2L), watched.history.first().map { it.messageId })
        assertEquals(listOf(3L), watched.watchLater.items.first().map { it.messageId })
    }

    @Test
    fun `a second profile starts empty and its records never leak into the default`() = runBlocking {
        settings.saveResumePosition(-100L, 1L, 600_000L, 3_600_000L, description = resumeLine())
        watched.markWatched(watchedRecord(2L, at = 10))

        val kids = added("Kids", simple = true)
        settings.setActiveProfile(kids.id)
        assertEquals(kids.id, ProfileScope.current)
        assertTrue(settings.continueWatching.first().isEmpty())
        assertTrue(watched.history.first().isEmpty())
        assertTrue(watched.watchLater.items.first().isEmpty())
        assertEquals(0L, settings.resumePosition(-100L, 1L))

        settings.saveResumePosition(-100L, 9L, 600_000L, 3_600_000L, description = resumeLine())
        watched.markWatched(watchedRecord(9L, at = 20))
        assertEquals(listOf(9L), settings.continueWatching.first().map { it.messageId })
        assertTrue(settings.rawEntries().keys.contains("pf_${kids.id}_resume_-100_9"))

        settings.setActiveProfile(ViewerProfiles.DEFAULT_ID)
        assertEquals(listOf(1L), settings.continueWatching.first().map { it.messageId })
        assertEquals(listOf(2L), watched.history.first().map { it.messageId })
        assertEquals(0L, settings.resumePosition(-100L, 9L))
    }

    @Test
    fun `marking watched crosses off only the acting profile's watch later line`() = runBlocking {
        watched.watchLater.add(laterRecord(4L, at = 5))
        val kids = added("Kids", simple = false)
        settings.setActiveProfile(kids.id)
        watched.watchLater.add(laterRecord(4L, at = 6))
        watched.markWatched(watchedRecord(4L, at = 30))
        assertTrue(watched.watchLater.items.first().isEmpty())

        settings.setActiveProfile(ViewerProfiles.DEFAULT_ID)
        assertEquals(listOf(4L), watched.watchLater.items.first().map { it.messageId })
    }

    @Test
    fun `collections split by profile and the default keeps the plain key`() = runBlocking {
        settings.setCollections(listOf(VideoCollection("Mine", listOf("-100_1"))))
        val kids = added("Kids", simple = false)
        settings.setActiveProfile(kids.id)
        assertTrue(settings.collections.first().isEmpty())
        settings.setCollections(listOf(VideoCollection("Kids picks", listOf("-100_2"))))
        assertEquals(listOf("Kids picks"), settings.collectionsNow().map { it.name })

        settings.setActiveProfile(ViewerProfiles.DEFAULT_ID)
        assertEquals(listOf("Mine"), settings.collectionsNow().map { it.name })
        assertTrue(settings.rawEntries().containsKey("collections"))
    }

    @Test
    fun `deleting a profile takes its records and lands on the default`() = runBlocking {
        val kids = added("Kids", simple = false)
        settings.setActiveProfile(kids.id)
        settings.saveResumePosition(-100L, 9L, 600_000L, 3_600_000L, description = resumeLine())
        watched.markWatched(watchedRecord(9L, at = 20))

        settings.removeViewerProfile(kids.id)
        watched.clearProfile(ViewerProfiles.prefixFor(kids.id))
        assertEquals(ViewerProfiles.DEFAULT_ID, settings.activeProfileIdNow())
        assertTrue(settings.viewerProfilesNow().isEmpty())
        assertTrue(settings.rawEntries().keys.none { it.startsWith("pf_${kids.id}_") })

        settings.setActiveProfile(kids.id)
        assertEquals(ViewerProfiles.DEFAULT_ID, settings.activeProfileIdNow())
    }

    @Test
    fun `clearing watched empties every profile at once`() = runBlocking {
        watched.markWatched(watchedRecord(2L, at = 10))
        val kids = added("Kids", simple = false)
        settings.setActiveProfile(kids.id)
        watched.markWatched(watchedRecord(9L, at = 20))
        watched.clear()
        assertTrue(watched.history.first().isEmpty())
        settings.setActiveProfile(ViewerProfiles.DEFAULT_ID)
        assertTrue(watched.history.first().isEmpty())
    }

    /** Adds a profile in a test, failing the test rather than flowing on without one. */
    private suspend fun added(name: String, simple: Boolean): ViewerProfile =
        settings.addViewerProfile(name, simple) ?: throw AssertionError("profile not added")

    /** A stored resume description line for a video, as [ResumeRecord.encode] writes one. */
    private fun resumeLine(): String = ResumeRecord.encode(
        fileId = 7, title = "A video", chatTitle = "Show", sizeBytes = 1_000,
        durationSec = 3_600, updatedAt = 1_000L,
    )
}
