package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveHeadsTest {

    @Test
    fun `archive signatures read as archives and video heads do not`() {
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x50, 0x4B, 0x07, 0x08)))
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)))
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)))
        assertTrue(ArchiveHeads.isArchive(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)))
        // An MKV's EBML head and an MP4's ftyp are videos, not archives.
        assertFalse(ArchiveHeads.isArchive(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
        assertFalse(ArchiveHeads.isArchive(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)))
        // Too short to carry a signature is nothing.
        assertFalse(ArchiveHeads.isArchive(byteArrayOf(0x50, 0x4B)))
        assertFalse(ArchiveHeads.isArchive(ByteArray(0)))
    }

    @Test
    fun `keys name a joined set by its chat and shared stem, and round trip`() {
        val piece = MediaItem(
            chatId = 7, messageId = 1, fileId = 1, title = "Film.2026.1080p.mkv.001",
            sizeBytes = 1, durationSec = 60, mimeType = "video/x-matroska",
            thumbnailFileId = 0, miniThumbnail = null, date = 1,
            fileName = "Film.2026.1080p.mkv.001",
        )
        val joined = piece.copy(pieces = listOf(piece, piece.copy(messageId = 2, fileName = "Film.2026.1080p.mkv.002")))
        assertEquals("7|film 2026 1080p", ArchiveHeads.keyOf(joined))
        // A whole file has no key: only a folded set can be condemned.
        assertNull(ArchiveHeads.keyOf(piece))
        assertTrue(ArchiveHeads.hides(joined, setOf("7|film 2026 1080p")))
        assertFalse(ArchiveHeads.hides(joined, emptySet()))

        val stored = ArchiveHeads.encode(setOf("7|film 2026 1080p", "-3|other title"))
        assertEquals(setOf("7|film 2026 1080p", "-3|other title"), ArchiveHeads.decode(stored))
        // Lines that are not keys are dropped, never kept.
        assertEquals(emptySet<String>(), ArchiveHeads.decode("nonsense\n|no chat\n12x|bad chat"))
        assertEquals(emptySet<String>(), ArchiveHeads.decode(null))
    }
}
