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
}
