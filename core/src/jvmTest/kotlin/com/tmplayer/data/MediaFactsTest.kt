package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFactsTest {

    private fun item(fileName: String, caption: String = "") = MediaItem(
        chatId = 1,
        messageId = 1,
        fileId = 1,
        title = fileName,
        sizeBytes = 1_000_000_000,
        durationSec = 9_000,
        mimeType = "video/x-matroska",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 1,
        fileName = fileName,
        caption = caption,
    )

    @Test
    fun `languages stated only in the caption are the copy's languages`() {
        // The owner's Jailer copies (v1.0.35): a bare release name says no language,
        // the post's caption names the audio, and a copies list read the name alone.
        val facts = MediaFacts.of(item(
            "Jailer.2023.1080p.AMZN.WEB-DL.DD+5.1.Atmos.H.265-CPTN5DW.mkv",
            caption = "Jailer (2023)\nAudio: Tamil | Hindi | Telugu",
        ))
        assertEquals(listOf("ta", "hi", "te"), facts.languages)
        // The name's own languages stand first, the caption's join them.
        val named = MediaFacts.of(item("Jailer 2023 Tamil.mkv", caption = "Audio: Hindi"))
        assertEquals(listOf("ta", "hi"), named.languages)
        // A name alone, as the facts have always read, is unchanged.
        assertEquals(listOf("ta"), MediaFacts.read("Jailer 2023 Tamil.mkv").languages)
    }

    @Test
    fun `a scene release names its codecs, audio and source`() {
        val facts = MediaFacts.read("Harbour.Notes.S01E04.1080p.WEB-DL.DDP5.1.x265-GROUP.mkv")
        assertEquals("1080p", facts.resolution)
        assertEquals("HEVC", facts.videoCodec)
        assertEquals("E-AC3 5.1", facts.audio)
        assertEquals("WEB-DL", facts.source)
        assertEquals("MKV", facts.container)
        assertNull(facts.dynamicRange)
        assertTrue(facts.tracks.isEmpty())
    }

    @Test
    fun `a split piece's facts are the whole video's, not the piece suffix's`() {
        // The owner's file (2026-11-08): a two part set whose page read "Video: 001",
        // the first piece's suffix standing as the container. The facts are read off
        // the name the pieces share, so the suffix never stands as one.
        val facts = MediaFacts.read("Mindiyum_Paranjum_2026_2160p_SNXT_WEB_DL_DDP5_1_H_265_PMi_XDMovies.001")
        assertNull(facts.container)

        val dotted = MediaFacts.read("Harbour.Notes.2026.2160p.WEB-DL.H.265-GROUP.mkv.001")
        assertEquals("4K", dotted.resolution)
        assertEquals("HEVC", dotted.videoCodec)
        assertEquals("WEB-DL", dotted.source)
        assertEquals("MKV", dotted.container)
    }

    @Test
    fun `a stated picture size beats the name, and says its pixels`() {
        val facts = MediaFacts.read("clip-720p.mp4", width = 1920, height = 1080)
        assertEquals("1080p", facts.resolution)
        assertEquals("1920 × 1080", facts.pixels)
    }

    @Test
    fun `either side decides the class of a stated size`() {
        assertEquals("4K", MediaFacts.resolutionOf(3840, 1600))
        assertEquals("1080p", MediaFacts.resolutionOf(1080, 1920))
        assertEquals("720p", MediaFacts.resolutionOf(1280, 536))
        assertEquals("SD", MediaFacts.resolutionOf(640, 360))
        assertNull(MediaFacts.resolutionOf(0, 0))
    }

    @Test
    fun `H dot 264 is a codec and never a channel layout`() {
        val facts = MediaFacts.read("Film.2024.720p.BluRay.H.264.AAC2.0.mp4")
        assertEquals("H.264", facts.videoCodec)
        assertEquals("AAC 2.0", facts.audio)
        assertEquals("Blu-ray", facts.source)
    }

    @Test
    fun `dynamic range and Atmos`() {
        val facts = MediaFacts.read("Film.2160p.UHD.BluRay.DV.HDR10.TrueHD.Atmos.7.1.HEVC.mkv")
        assertEquals("4K", facts.resolution)
        assertEquals("Dolby Vision", facts.dynamicRange)
        assertEquals("TrueHD Atmos 7.1", facts.audio)
        assertEquals("HEVC", facts.videoCodec)
    }

    @Test
    fun `tracks the name announces`() {
        val facts = MediaFacts.read("Show (2025) Dual Audio [Hindi + English] 1080p ESub.mkv")
        assertEquals(listOf(MediaFacts.Track.DualAudio, MediaFacts.Track.Subtitles), facts.tracks)
        assertEquals(
            listOf(MediaFacts.Track.MultiAudio),
            MediaFacts.read("Show.S01E01.MULTi.1080p.mkv").tracks,
        )
    }

    @Test
    fun `a plain name says nothing it does not know`() {
        val facts = MediaFacts.read("birthday highlights.mp4")
        assertNull(facts.resolution)
        assertNull(facts.videoCodec)
        assertNull(facts.audio)
        assertNull(facts.source)
        assertEquals("MP4", facts.container)
        // An episode code with a dot after it is not a channel layout.
        assertNull(MediaFacts.read("Show.S01E02.1080p.mkv").audio)
        // Nor is a word that merely contains a codec's letters.
        assertNull(MediaFacts.read("savvy.dtsomething.mkv").audio)
    }

    @Test
    fun `the owner's platform tags read as their services`() {
        assertEquals("Disney+ Hotstar", MediaFacts.read("Im.Game.2026.720p.DS4K.HS.WEBRip.mkv").platform)
        assertEquals("Disney+ Hotstar", MediaFacts.read("Show.2025.JHS.WEB-DL.mkv").platform)
        assertEquals("Netflix", MediaFacts.read("Show.2025.NF.WEB-DL.mkv").platform)
        assertEquals("Prime Video", MediaFacts.read("Film.2026.AMZN.WEB-DL.mkv").platform)
        assertEquals("ZEE5", MediaFacts.read("Film.2026.Z5.WEB-DL.mkv").platform)
        assertEquals("ZEE5", MediaFacts.read("Film.2026.ZEE5.WEB-DL.mkv").platform)
        assertEquals("SonyLIV", MediaFacts.read("Film.2026.SLIV.WEB-DL.mkv").platform)
        assertEquals("SonyLIV", MediaFacts.read("Film.2026.SONYLIV.WEB-DL.mkv").platform)
        assertEquals("SunNXT", MediaFacts.read("Film.2026.SNXT.WEB-DL.mkv").platform)
        assertEquals("SunNXT", MediaFacts.read("Film.2026.SUNXT.WEB-DL.mkv").platform)
        assertEquals("ManoramaMAX", MediaFacts.read("Film.2026.MMAX.WEB-DL.mkv").platform)
        assertEquals("Simply South", MediaFacts.read("Film.2026.SS.WEB-DL.mkv").platform)
        assertEquals("Jio", MediaFacts.read("Film.2026.JC.WEB-DL.mkv").platform)
        assertEquals("Jio", MediaFacts.read("Film.2026.JIO.WEB-DL.mkv").platform)
        assertEquals("Aha", MediaFacts.read("Film.2026.AHA.WEB-DL.mkv").platform)
        assertEquals("iQIYI", MediaFacts.read("Drama.2026.IQ.WEB-DL.mkv").platform)
        assertEquals("Rakuten Viki", MediaFacts.read("Drama.2026.VIKI.WEB-DL.mkv").platform)
        assertNull(MediaFacts.read("Chennai.Story.2026.WEB-DL.mkv").platform)
        assertNull(MediaFacts.read("Harbour.Notes.S01E04.1080p.WEB-DL.mkv").platform)
    }

    @Test
    fun `AI counts only as space AI space or dot AI dot`() {
        assertTrue(MediaFacts.read("Film.2026.1080p.AI.WEB-DL.mkv").aiEnhanced)
        assertTrue(MediaFacts.read("Film 2026 1080p AI Upscaled.mkv").aiEnhanced)
        assertTrue(MediaFacts.read("Film.1080p.DS4K.AI.HS.WEBRip.mkv").aiEnhanced)
        assertTrue(!MediaFacts.read("Film.1080p.DS4K.HS.WEBRip.mkv").aiEnhanced)
        assertTrue(!MediaFacts.read("Chennai.Express.2013.BluRay.mkv").aiEnhanced)
        assertTrue(!MediaFacts.read("Airlift.2016.BluRay.mkv").aiEnhanced)
        assertTrue(!MediaFacts.read("Film.[AI].1080p.mkv").aiEnhanced)
        assertTrue(!MediaFacts.read("AI.Film.2026.mkv").aiEnhanced)
    }

    @Test
    fun `remastered in every spelling uploaders type`() {
        assertTrue(MediaFacts.read("Classic.1975.Remastered.BluRay.mkv").remastered)
        assertTrue(MediaFacts.read("Classic.1975.Reamster.BluRay.mkv").remastered)
        assertTrue(MediaFacts.read("Classic.1975.Rmastrd.BluRay.mkv").remastered)
        assertTrue(MediaFacts.read("Classic.1975.Remasterd.BluRay.mkv").remastered)
        assertTrue(!MediaFacts.read("Film.2026.BluRay.mkv").remastered)
    }

    @Test
    fun `the owner's HQ PreDVD name reads PreDVD`() {
        val facts = MediaFacts.read("Jailer 2 (2026) Tamil HQ PreDVD - 1080p - x264 - HQ Clean.mkv")
        assertEquals("PreDVD", facts.source)
        assertEquals("1080p", facts.resolution)
        assertEquals("H.264", facts.videoCodec)
        assertTrue(MediaFacts.isCamSource(facts.source))
    }

    @Test
    fun `BDRip and BRRip read as their own sources, not Blu-ray`() {
        assertEquals("BDRip", MediaFacts.read("Film.2026.1080p.BDRip.x264.mkv").source)
        assertEquals("BDRip", MediaFacts.read("Film 2026 1080p BD-Rip x265.mkv").source)
        assertEquals("BRRip", MediaFacts.read("Film.2026.720p.BRRip.x264.mkv").source)
        assertEquals("BRRip", MediaFacts.read("Film 2026 720p BR Rip x265.mkv").source)
    }

    @Test
    fun `plain Blu-ray and REMUX names still read Blu-ray`() {
        assertEquals("Blu-ray", MediaFacts.read("Film.2026.1080p.BluRay.x264.mkv").source)
        assertEquals("Blu-ray", MediaFacts.read("Film.2026.2160p.Blu-ray.REMUX.HEVC.mkv").source)
        assertEquals("Blu-ray", MediaFacts.read("Film.2026.1080p.BDRemux.mkv").source)
        assertEquals("Blu-ray", MediaFacts.read("Film 2026 1080p Remux.mkv").source)
        assertFalse(MediaFacts.isCamSource("Blu-ray"))
    }

    @Test
    fun `the cam family reads its own labels`() {
        assertEquals("PreDVD", MediaFacts.read("Film.2026.PreDVD.x264.mkv").source)
        assertEquals("PreDVD", MediaFacts.read("Film 2026 Pre DVD x264.mkv").source)
        assertEquals("HDTS", MediaFacts.read("Film.2026.HDTS.x264.mkv").source)
        assertEquals("HDTS", MediaFacts.read("Film 2026 HD-TS x264.mkv").source)
        assertEquals("TS", MediaFacts.read("Film.2026.TS.x264.mkv").source)
        assertEquals("TS", MediaFacts.read("Film 2026 Telesync x264.mkv").source)
        assertEquals("CAM", MediaFacts.read("Film.2026.CAM.x264.mkv").source)
        assertEquals("CAM", MediaFacts.read("Film.2026.CamRip.x264.mkv").source)
        for (label in listOf("PreDVD", "HDTS", "TS", "CAM")) {
            assertTrue(label, MediaFacts.isCamSource(label))
        }
    }

    @Test
    fun `the rest of the owner's cam family reads its own labels`() {
        // HDcam read as CAM before v1.0.16; the owner's tag list names it as its own
        // tag, so it reads as its own label now and stands in the family beside CAM.
        assertEquals("HDcam", MediaFacts.read("Film.2026.HDCAM.x264.mkv").source)
        assertEquals("HDcam", MediaFacts.read("Film 2026 HD Cam x264.mkv").source)
        assertEquals("DVDcam", MediaFacts.read("Film.2026.DVDcam.x264.mkv").source)
        assertEquals("DVDcam", MediaFacts.read("Film 2026 DVD Cam x264.mkv").source)
        assertEquals("DVDScr", MediaFacts.read("Film.2026.DVDScr.x264.mkv").source)
        assertEquals("DVDScr", MediaFacts.read("Film.2026.DVDScreener.x264.mkv").source)
        assertEquals("HDTC", MediaFacts.read("Film.2026.HDTC.x264.mkv").source)
        assertEquals("HDTC", MediaFacts.read("Film 2026 Telecine x264.mkv").source)
        for (label in listOf("DVDcam", "DVDScr", "HDTC", "HDcam")) {
            assertTrue(label, MediaFacts.isCamSource(label))
        }
        assertEquals(
            setOf("PreDVD", "PDVD", "PreHD", "DVDcam", "DVDScr", "BDScr", "Screener", "Workprint", "HDTS", "HDTC", "TC", "HDcam", "TS", "CAM"),
            MediaFacts.CAM_SOURCES,
        )
    }

    @Test
    fun `every theatre print spelling reads and stands in the cam family`() {
        // The owner's phone (v1.0.23): this exact name played unhidden, because
        // PreDVDRip glued the Rip onto the tag and no source read at all.
        val kalki = MediaFacts.read("Kalki_2898_AD_2024_Malayalam_720p_Proper_HQ_REAL_PreDVDRip_@AVA.mkv")
        assertEquals("PreDVD", kalki.source)
        assertTrue(MediaFacts.isCam(kalki))
        assertEquals("PreDVD", MediaFacts.read("Film.2026.Pre DVD Rip.x264.mkv").source)
        assertEquals("PDVD", MediaFacts.read("Film.2026.PDVD.x264.mkv").source)
        assertEquals("PDVD", MediaFacts.read("Film.2026.PDVDRip.x264.mkv").source)
        assertEquals("TS", MediaFacts.read("Film.2026.TSRip.x264.mkv").source)
        assertEquals("HDTS", MediaFacts.read("Film.2026.HDTSRip.x264.mkv").source)
        assertEquals("TC", MediaFacts.read("Film.2026.TC.x264.mkv").source)
        assertEquals("TC", MediaFacts.read("Film.2026.TCRip.x264.mkv").source)
        assertEquals("HDTC", MediaFacts.read("Film.2026.HDTCRip.x264.mkv").source)
        assertEquals("HDcam", MediaFacts.read("Film.2026.HDCamRip.x264.mkv").source)
        assertEquals("CAM", MediaFacts.read("Film 2026 Theatre Print x264.mkv").source)
        assertEquals("CAM", MediaFacts.read("Film 2026 Theater Print x264.mkv").source)
        assertEquals("BDScr", MediaFacts.read("Film.2026.BDScr.x264.mkv").source)
        assertEquals("Screener", MediaFacts.read("Film.2026.Screener.x264.mkv").source)
        assertEquals("Screener", MediaFacts.read("Film.2026.WebScreener.x264.mkv").source)
        assertEquals("Screener", MediaFacts.read("Film.2026.SCR.x264.mkv").source)
        assertEquals("Workprint", MediaFacts.read("Film.2026.Workprint.x264.mkv").source)
        assertEquals("Workprint", MediaFacts.read("Film.2026.WP.x264.mkv").source)
        for (label in listOf("PDVD", "TC", "BDScr", "Screener", "Workprint")) {
            assertTrue(label, MediaFacts.isCamSource(label))
        }
        // Not theatre prints, and never read as cam family: a retail R5 disc and
        // a title that merely holds the word Camera.
        assertFalse(MediaFacts.isCam(MediaFacts.read("Film.2026.R5.1080p.x264.mkv")))
        assertNull(MediaFacts.read("Camera Obscura 2020 1080p.mkv").source)
    }

    @Test
    fun `the owner's PRE-HD names read PreHD and stand in the cam family`() {
        // His Copies list (2026-11-08): both spellings stood unhidden beside the
        // WEB-DL copies, at a stated 1080p, so the height never gave them away.
        val spaced = MediaFacts.read("L2 Empuraan (2025) Tamil PRE-HD - 1080p - x264 - Original Au.mkv")
        assertEquals("PreHD", spaced.source)
        assertEquals("1080p", spaced.resolution)
        assertTrue(MediaFacts.isCamSource(spaced.source))
        assertTrue(MediaFacts.isCam(spaced))
        val underscored = MediaFacts.read("L2_Empuraan_2025_Malayalam PRE_HD_1080p_x264_Original.mkv")
        assertEquals("PreHD", underscored.source)
        assertEquals("PreHD", MediaFacts.read("Film.2026.PreHD.x264.mkv").source)
        assertEquals("PreHD", MediaFacts.read("Film 2026 Pre HD x264.mkv").source)
        // A word that merely starts alike is not the tag.
        assertNull(MediaFacts.read("Prehistoric Planet 2026 1080p.mkv").source)
    }

    @Test
    fun `the HC subtitle and line audio tags stand a copy in the hidden family`() {
        // The owner's channel searches (2026-11-11): whole pages of copies whose
        // names carry nothing but the HC tag stood unhidden beside the copies he
        // wanted, most of them plain HDRip, which is never hidden for its source.
        val jailer = MediaFacts.read("Jailer 2 (2026) Tamil HQ HDRip - x264 - AAC - 250MB - HC-Esub.mkv")
        assertEquals("HDRip", jailer.source)
        assertTrue(jailer.hcSubs)
        assertTrue(MediaFacts.isCam(jailer))
        assertTrue(MediaFacts.read("Anecdotes of Ape Mountain (2026) HQ HDRip - x264 - [Tamil + Hindi] - (AAC 2.0) - 300MB - HC-Sub.mkv").hcSubs)
        assertTrue(MediaFacts.read("Maargan (2025) [Tamil - HQ HDTS - x264 - AAC - 250MB - HC Esub - HQ Line Audio].mkv").hcSubs)
        assertTrue(MediaFacts.read("Film (2026) 1080p WEB-DL x264 Hardcoded Subs.mkv").hcSubs)
        // The bare tag his Jailer 2 copies end in (2026-11-11, after v1.0.35):
        // no Sub after it at all, and those copies stood unhidden on his page.
        val bare = MediaFacts.read("Jailer 2 (2026) Tamil HQ HDRip - x264 - AAC - 700MB - HC.mkv")
        assertTrue(bare.hcSubs)
        assertTrue(MediaFacts.isCam(bare))
        assertTrue(MediaFacts.isCam(MediaFacts.read("Film (2026) HQ WEB-DL - x264 - AAC - 1.6GB - HQ Line Audio.mkv")))
        // Not the tags: a plain ESub is a subtitle track half a library carries,
        // and a name with no tag at all stands as it always did.
        assertFalse(MediaFacts.isCam(MediaFacts.read("Film (2026) 1080p WEB-DL x264 ESub.mkv")))
        assertFalse(MediaFacts.isCam(MediaFacts.read("Film (2026) 1080p WEB-DL x264.mkv")))
    }

    @Test
    fun `a clean audio tag marks any copy as cam family`() {
        val tagged = MediaFacts.read("Film.2026.HDTS.Clean.Audio.x264.mkv")
        assertTrue(tagged.cleanAudio)
        assertTrue(MediaFacts.isCam(tagged))
        // The tag alone, on a source that is no cam source, still marks the copy.
        val onWeb = MediaFacts.read("Film.2026.1080p.WEB-DL.CleanAudio.x265.mkv")
        assertEquals("WEB-DL", onWeb.source)
        assertTrue(onWeb.cleanAudio)
        assertTrue(MediaFacts.isCam(onWeb))
        // The short spelling, from his Jailer 2 copies: "HQ Clean" is the same tag.
        val hqClean = MediaFacts.read("Jailer 2 2026 Hindi HDRip x264 - HQ Clean - AAC - 700MB.mkv")
        assertEquals("HDRip", hqClean.source)
        assertTrue(hqClean.cleanAudio)
        assertTrue(MediaFacts.isCam(hqClean))
        assertTrue(MediaFacts.isCam(MediaFacts.read("Jailer 2 2026 Kannada HDRip x264 - HQ-Clean - AAC - 700M.mkv")))
        assertTrue(MediaFacts.isCam(MediaFacts.read("Jailer 2 2026 Hindi HDRip x264 - HQ Clean Audio - AAC - 700MB.mkv")))
        // A tagless HDRip stands, and "HQ" on its own is not the tag.
        assertFalse(MediaFacts.isCam(MediaFacts.read("Jailer 2 2026 Telugu HDRip - x264 - AAC - 400MB.mkv")))
        assertFalse(MediaFacts.read("Jailer 2 2026 HQ 1080p WEB-DL x264.mkv").cleanAudio)
        // A word that merely starts alike is not the tag.
        assertFalse(MediaFacts.read("Cleaning Day 2026 1080p.mkv").cleanAudio)
        assertFalse(MediaFacts.read("Film.2026.1080p.WEB-DL.DDP5.1.x265.mkv").cleanAudio)
    }

    @Test
    fun `HDRip and WEB-DL names are unchanged and HDRip is not a cam source`() {
        assertEquals("HDRip", MediaFacts.read("Film.2026.720p.HDRip.x264.mkv").source)
        assertEquals("WEB-DL", MediaFacts.read("Film.2026.1080p.WEB-DL.x265.mkv").source)
        assertEquals("WEBRip", MediaFacts.read("Film.2026.1080p.WEBRip.x265.mkv").source)
        assertEquals("HDTV", MediaFacts.read("Show.S01E01.HDTV.x264.mkv").source)
        assertEquals("DVD", MediaFacts.read("Film.2005.DVDRip.XviD.mkv").source)
        assertFalse(MediaFacts.isCamSource("HDRip"))
        assertFalse(MediaFacts.isCamSource("WEB-DL"))
        assertFalse(MediaFacts.isCamSource(null))
        assertFalse(MediaFacts.isCamSource("Blu-ray"))
    }

    @Test
    fun `a cam like word inside a longer word does not fire`() {
        assertNull(MediaFacts.read("Cambridge Nights 2026 1080p.mkv").source)
        assertNull(MediaFacts.read("Campfire Tales 2026 1080p.mkv").source)
        assertNull(MediaFacts.read("The Tsunami Story 2026 1080p.mkv").source)
    }

    @Test
    fun `every codec spelling the owner named reads`() {
        assertEquals("HEVC", MediaFacts.read("Film.2026.x265.mkv").videoCodec)
        assertEquals("HEVC", MediaFacts.read("Film.2026.h265.mkv").videoCodec)
        assertEquals("HEVC", MediaFacts.read("Film.2026.H.265.mkv").videoCodec)
        assertEquals("HEVC", MediaFacts.read("Film.2026.HEVC.mkv").videoCodec)
        assertEquals("H.264", MediaFacts.read("Film.2026.x264.mkv").videoCodec)
        assertEquals("H.264", MediaFacts.read("Film.2026.h264.mkv").videoCodec)
        assertEquals("H.264", MediaFacts.read("Film.2026.H.264.mkv").videoCodec)
        assertEquals("H.264", MediaFacts.read("Film.2026.AVC.mkv").videoCodec)
        assertEquals("AV1", MediaFacts.read("Film.2026.AV1.mkv").videoCodec)
        assertEquals("E-AC3 5.1", MediaFacts.read("Film.2026.WEB-DL.DDP5.1.x265.mkv").audio)
    }

    @Test
    fun `HDR and Dolby Vision together read as HDR DV`() {
        assertEquals("HDR DV", MediaFacts.read("Film.2160p.HDRDV.WEB-DL.mkv").dynamicRange)
        assertEquals("HDR DV", MediaFacts.read("Film.2160p.HDR.DV.WEB-DL.mkv").dynamicRange)
        assertEquals("HDR DV", MediaFacts.read("Film.2160p.DV.HDR.WEB-DL.mkv").dynamicRange)
        assertEquals("Dolby Vision", MediaFacts.read("Film.2160p.DV.WEB-DL.mkv").dynamicRange)
        assertEquals("SDR", MediaFacts.read("Film.1080p.SDR.WEB-DL.mkv").dynamicRange)
        assertEquals("HDR", MediaFacts.read("Film.2160p.HDR.WEB-DL.mkv").dynamicRange)
    }
}
