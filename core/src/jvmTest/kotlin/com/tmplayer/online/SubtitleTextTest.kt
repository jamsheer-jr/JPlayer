package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/** Subtitle files in the encodings people actually save them in, read back as the same text. */
class SubtitleTextTest {

    private val cue = "1\n00:00:01,000 --> 00:00:02,500\n"

    private fun bytes(text: String, charset: String, bom: ByteArray = ByteArray(0)): ByteArray =
        bom + text.toByteArray(Charset.forName(charset))

    @Test
    fun `utf-8 with and without a byte order mark`() {
        val text = cue + "Ça va? Привет, مرحبا\n"
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-8")))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-8", bom)))
    }

    @Test
    fun `utf-16 both ways round, with a mark and without`() {
        val text = cue + "مرحبا بالعالم\nHello\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16LE", le)))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16BE", be)))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16LE")))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16BE")))
    }

    @Test
    fun `utf-32 with a mark`() {
        val text = cue + "Hello\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-32LE", le)))
    }

    @Test
    fun `a code page is read as the subtitle's language wrote it`() {
        val arabic = cue + "مرحبا بالعالم\n"
        assertEquals(arabic, SubtitleText.decode(bytes(arabic, "windows-1256"), "ar"))
        assertEquals(arabic, SubtitleText.decode(bytes(arabic, "windows-1256"), "fa-IR"))
        val russian = cue + "Привет, мир\n"
        assertEquals(russian, SubtitleText.decode(bytes(russian, "windows-1251"), "ru"))
        val greek = cue + "Καλημέρα\n"
        assertEquals(greek, SubtitleText.decode(bytes(greek, "windows-1253"), "ell"))
        val polish = cue + "Zażółć gęślą jaźń\n"
        assertEquals(polish, SubtitleText.decode(bytes(polish, "windows-1250"), "pl"))
    }

    @Test
    fun `the western page when the language is unknown or written in it`() {
        val french = cue + "Ça va très bien, merci\n"
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252")))
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252"), "fr"))
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252"), "nonsense"))
    }

    @Test
    fun `chinese tags pick the simplified or the traditional page`() {
        assertEquals("GBK", SubtitleText.codePageFor("zh").name())
        assertEquals("GBK", SubtitleText.codePageFor("zh-CN").name())
        assertEquals("Big5", SubtitleText.codePageFor("zh-TW").name())
        assertEquals("Big5", SubtitleText.codePageFor("zh_Hant_HK").name())
        assertEquals("windows-1252", SubtitleText.codePageFor(null).name())
    }

    @Test
    fun `an online download is read in the hit's language, zipped or not`() {
        val arabic = cue + "مرحبا\n"
        val plain = SubtitleText.from(bytes(arabic, "windows-1256"), "film.srt", language = "ar")
        assertEquals(arabic, plain?.text)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("film.ar.srt"))
                z.write(bytes(arabic, "windows-1256"))
                z.closeEntry()
            }
        }.toByteArray()
        val zipped = SubtitleText.from(zip, "film.zip", language = "ar")
        assertNotNull(zipped)
        assertEquals(arabic, zipped?.text)
    }

    @Test
    fun `a season pack gives the asked episode its own file, named` () {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Show.S01E01.srt"))
                z.write(bytes(cue + "First episode\n", "UTF-8"))
                z.closeEntry()
                z.putNextEntry(ZipEntry("Show.S01E02.srt"))
                z.write(bytes(cue + "Second episode\n", "UTF-8"))
                z.closeEntry()
            }
        }.toByteArray()
        val second = SubtitleText.from(zip, "show.zip", season = 1, episode = 2, strict = true)
        assertNotNull(second)
        assertTrue(second!!.text.contains("Second episode"))
        assertEquals("Show.S01E02.srt", second.entryName)
        // The pack's entries stand by name for a hand pick, and the picked one reads.
        assertEquals(listOf("Show.S01E01.srt", "Show.S01E02.srt"), SubtitleText.archiveEntries(zip))
        val picked = SubtitleText.fromArchiveEntry(zip, "Show.S01E01.srt")
        assertNotNull(picked)
        assertTrue(picked!!.text.contains("First episode"))
        assertEquals("Show.S01E01.srt", picked.entryName)
        assertNull(SubtitleText.fromArchiveEntry(zip, "Show.S01E09.srt"))
        assertTrue(SubtitleText.archiveEntries(byteArrayOf(1, 2, 3)).isEmpty())
        // Strict never answers with another episode's file.
        assertNull(SubtitleText.from(zip, "show.zip", season = 1, episode = 7, strict = true))
        // A series pick survives its stored string, and nonsense reads as no pick.
        val hit = com.tmplayer.online.SubtitleHit(
            provider = com.tmplayer.online.SubtitleProvider.Msone,
            id = "https://malayalamsubtitles.org/show-season-1/",
            language = "ml",
            release = "Show Season 1",
            pageUrl = "https://malayalamsubtitles.org/show-season-1/",
        )
        val pick = com.tmplayer.online.MsoneSeriesPick.decode(
            com.tmplayer.online.MsoneSeriesPick(hit, 1, 2).encode(),
        )
        assertNotNull(pick)
        assertEquals(hit.id, pick!!.hit.id)
        assertEquals(1, pick.appliedSeason)
        assertEquals(2, pick.appliedEpisode)
        assertNull(com.tmplayer.online.MsoneSeriesPick.decode("not json"))
        assertNull(com.tmplayer.online.MsoneSeriesPick.decode(null))
    }
}
