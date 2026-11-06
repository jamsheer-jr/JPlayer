package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleCleaningTest {

    @Test
    fun captureJunkComesOutWhereverItStands() {
        assertEquals("Night Train (2024)", TitleCleaning.strip("Night Train HDCam (2024)"))
        assertEquals("Night Train", TitleCleaning.strip("HDCAM Night Train"))
        assertEquals("Night Train", TitleCleaning.strip("Night Train CAMRip"))
        assertEquals("Night Train", TitleCleaning.strip("Night Train PreDVD"))
        assertEquals("Night Train", TitleCleaning.strip("Night Train [TS]"))
        assertEquals("Night Train", TitleCleaning.strip("Night Train HD-CAM"))
        assertEquals("Night Train", TitleCleaning.strip("Night Train DVDScr"))
    }

    @Test
    fun hdripAlwaysStays() {
        assertEquals("Night Train HDRip", TitleCleaning.strip("Night Train HDRip"))
        assertEquals("Night Train HDRip", TitleCleaning.strip("Night Train HDRip CAM"))
    }

    @Test
    fun wordsThatMerelyHoldAJunkTokenStay() {
        assertEquals("Camera Obscura", TitleCleaning.strip("Camera Obscura"))
        assertEquals("Tsar", TitleCleaning.strip("Tsar"))
        assertEquals("The Screening Room", TitleCleaning.strip("The Screening Room"))
    }

    @Test
    fun aTitleOfNothingButJunkIsLeftAlone() {
        assertEquals("CAM", TitleCleaning.strip("CAM"))
        assertEquals("", TitleCleaning.strip(""))
        assertEquals("Night Train", TitleCleaning.strip("Night Train"))
    }

    @Test
    fun offMeansUntouched() {
        TitleCleaning.hideTags = false
        assertEquals("Night Train HDCam", TitleCleaning.shown("Night Train HDCam"))
    }

    @Test
    fun theViewersOwnWordsComeOutByTheSameRule() {
        TitleCleaning.customWords = setOf("sample", "HD-CAM2")
        try {
            assertEquals("Night Train", TitleCleaning.strip("Night Train Sample"))
            assertEquals("Night Train", TitleCleaning.strip("Night Train [SAMPLE]"))
            // The dashed spelling the viewer typed catches the squashed one in a title,
            // and the plain one catches a dashed title word: the built in tokens' two prongs.
            assertEquals("Night Train", TitleCleaning.strip("Night Train HDCAM2"))
            assertEquals("Night Train", TitleCleaning.strip("Night Train HD-CAM2"))
            // A word that merely holds a custom word stays whole, as with the built ins.
            assertEquals("Sampler Night", TitleCleaning.strip("Sampler Night"))
            // The built ins still work beside the viewer's words, and HDRip still stays.
            assertEquals("Night Train HDRip", TitleCleaning.strip("Night Train HDRip CAM Sample"))
        } finally {
            TitleCleaning.customWords = emptySet()
        }
    }

    @Test
    fun customWordsHideOnlyWhileTheSwitchIsOn() {
        TitleCleaning.customWords = setOf("sample")
        try {
            TitleCleaning.hideTags = false
            assertEquals("Night Train Sample", TitleCleaning.shown("Night Train Sample"))
            TitleCleaning.hideTags = true
            assertEquals("Night Train", TitleCleaning.shown("Night Train Sample"))
        } finally {
            TitleCleaning.hideTags = false
            TitleCleaning.customWords = emptySet()
        }
    }

    @Test
    fun aCustomWordTheBuiltInsAlreadyCoverChangesNothing() {
        TitleCleaning.customWords = setOf("cam", "PreDVD")
        try {
            assertEquals("Night Train", TitleCleaning.strip("Night Train CAM"))
            assertEquals("Night Train HDRip", TitleCleaning.strip("Night Train HDRip PreDVD"))
        } finally {
            TitleCleaning.customWords = emptySet()
        }
    }

    @Test
    fun customWordsSurviveAStorageRoundTrip() {
        val words = linkedSetOf("sample", "HD-CAM2", "promo")
        assertEquals(words, TitleCleaning.parseCustomWords(TitleCleaning.encodeCustomWords(words)))
    }

    @Test
    fun damagedStoredWordsLoseOnlyTheDamage() {
        assertEquals(
            linkedSetOf("sample", "promo"),
            TitleCleaning.parseCustomWords("sample\n\n  \nSAMPLE\n promo \n"),
        )
        assertEquals(emptySet(), TitleCleaning.parseCustomWords(null))
        assertEquals(emptySet(), TitleCleaning.parseCustomWords(""))
        assertEquals(emptySet(), TitleCleaning.parseCustomWords("\n\n"))
        val many = (1..(TitleCleaning.MAX_CUSTOM_WORDS + 10)).joinToString("\n") { "word$it" }
        assertEquals(TitleCleaning.MAX_CUSTOM_WORDS, TitleCleaning.parseCustomWords(many).size)
    }
}
