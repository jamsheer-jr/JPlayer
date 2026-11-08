package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFactsTest {

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
            setOf("PreDVD", "DVDcam", "DVDScr", "HDTS", "HDTC", "HDcam", "TS", "CAM"),
            MediaFacts.CAM_SOURCES,
        )
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
