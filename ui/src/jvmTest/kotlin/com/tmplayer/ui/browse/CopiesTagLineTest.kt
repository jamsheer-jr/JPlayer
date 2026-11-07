package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag line a copies row carries, read off the helpers the player sheet and the detail
 * page's chooser both draw from ([copyHeading] and [copySound]): the source, the codec,
 * the language and the audio, whenever the file name states them.
 */
class CopiesTagLineTest {

    private fun video(name: String) = MediaItem(
        chatId = 1,
        messageId = 1,
        fileId = 1,
        title = name,
        sizeBytes = 900L * 1024 * 1024,
        durationSec = 5_000,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = name,
    )

    @Test
    fun `a row carries its source, codec, language and audio`() {
        val copy = video("Harbour.Lights.2019.1080p.HDRip.x265.Tamil.AAC.2.0.mkv")
        val heading = copyHeading(copy)
        assertTrue(heading, heading.contains("HDRip"))
        assertTrue(heading, heading.contains("HEVC"))
        val sound = copySound(copy, java.util.Locale.ENGLISH)
        assertTrue(sound, sound != null && "Tamil" in sound)
        assertTrue(sound, sound != null && "AAC 2.0" in sound)
    }

    @Test
    fun `the PreDVD name the owner photographed reads its source and is a cam copy`() {
        val copy = video("Harbour.Lights.2019.HQ.PreDVD.x265.mkv")
        assertTrue(copyHeading(copy), copyHeading(copy).contains("PreDVD"))
        assertTrue(isCamCopy(copy))
    }

    @Test
    fun `HDRip is never a cam copy, and a WEB-DL is not one either`() {
        assertFalse(isCamCopy(video("Harbour.Lights.2019.HDRip.x264.mkv")))
        assertFalse(isCamCopy(video("Harbour.Lights.2019.1080p.WEB-DL.x264.mkv")))
    }
}
