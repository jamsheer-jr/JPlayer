package com.tmplayer.player

import com.tmplayer.data.ResumeState
import com.tmplayer.data.TrackChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPreferencesTest {

    // ---- the original soundtrack --------------------------------------------------------------

    @Test
    fun `a lone soundtrack is left alone`() {
        assertEquals(AudioPick.Leave, TrackRules.pickOriginalAudio(listOf(AudioTrackInfo())))
        assertEquals(AudioPick.Leave, TrackRules.pickOriginalAudio(emptyList()))
    }

    @Test
    fun `the track the file marks default is the original`() {
        val tracks = listOf(AudioTrackInfo(), AudioTrackInfo(isDefault = true), AudioTrackInfo())
        assertEquals(AudioPick.Track(1), TrackRules.pickOriginalAudio(tracks))
    }

    @Test
    fun `with none marked, the first soundtrack is the original`() {
        val tracks = listOf(AudioTrackInfo(), AudioTrackInfo(), AudioTrackInfo())
        assertEquals(AudioPick.Track(0), TrackRules.pickOriginalAudio(tracks))
    }


    private val japaneseAudio = TrackPreferences(audioLanguage = "ja", mode = SubtitleMode.Always)

    // ---- normalisation ----------------------------------------------------------------------

    @Test
    fun `three letter codes come down to two letters`() {
        assertEquals("ja", TrackLanguages.normalise("jpn"))
        assertEquals("en", TrackLanguages.normalise("eng"))
        assertEquals("ml", TrackLanguages.normalise("mal"))
        assertEquals("ta", TrackLanguages.normalise("TAM"))
    }

    @Test
    fun `bibliographic codes match their terminology twins`() {
        assertEquals("de", TrackLanguages.normalise("ger"))
        assertEquals("de", TrackLanguages.normalise("deu"))
        assertEquals("fr", TrackLanguages.normalise("fre"))
        assertEquals("zh", TrackLanguages.normalise("chi"))
        assertTrue(TrackLanguages.same("ger", "de"))
        assertTrue(TrackLanguages.same("fre", "fra"))
    }

    @Test
    fun `regions, underscores, old codes and names are folded`() {
        assertEquals("en", TrackLanguages.normalise("en-US"))
        assertEquals("pt", TrackLanguages.normalise("pt_BR"))
        assertEquals("he", TrackLanguages.normalise("iw"))
        assertEquals("ja", TrackLanguages.normalise("Japanese"))
        assertEquals("fil", TrackLanguages.normalise("fil"))
    }

    @Test
    fun `undetermined and junk are no language`() {
        for (code in listOf(null, "", " ", "und", "mul", "zxx", "x", "12", "Klingonish")) {
            assertNull(code, TrackLanguages.normalise(code))
        }
        assertFalse(TrackLanguages.same("und", "und"))
        assertFalse(TrackLanguages.same(null, null))
    }

    @Test
    fun `mpv gets every spelling a file might carry`() {
        assertEquals(listOf("de", "deu", "ger"), TrackLanguages.spellings("ger"))
        assertEquals(listOf("ja", "jpn"), TrackLanguages.spellings("ja"))
        assertEquals(emptyList<String>(), TrackLanguages.spellings(null))
    }

    @Test
    fun `stored settings read back normalised`() {
        val prefs = TrackPreferences.from("jpn", "eng", "foreign")
        assertEquals("ja", prefs.audioLanguage)
        assertEquals("en", prefs.subtitleLanguage)
        assertEquals(SubtitleMode.ForeignAudio, prefs.mode)
        val off = TrackPreferences.from(null, TrackPreferences.OFF, "nonsense")
        assertTrue(off.subtitlesOff)
        assertEquals(TrackPreferences.OFF, off.subtitleKey)
        assertEquals(SubtitleMode.AsTheVideoSays, off.mode)
    }

    @Test
    fun `the choices include the app languages and the common ones, once each`() {
        val choices = TrackLanguages.choices()
        for (code in listOf("en", "ja", "ko", "hi", "ml", "ta", "te", "es", "pt", "zh", "bn")) {
            assertTrue(code, code in choices)
        }
        assertEquals(choices.distinct(), choices)
    }

    // ---- precedence -------------------------------------------------------------------------

    @Test
    fun `the file's saved tracks come first`() {
        val resume = ResumeState(audioTrack = 1, audioLanguage = "eng", subtitleTrack = ResumeState.SUBTITLES_OFF)
        val series = TrackChoice(audioLanguage = "kor", textLanguage = "eng", subtitlesOn = true)
        val plan = TrackRules.plan(resume, series, japaneseAudio, "en")
        assertEquals("en", plan.audioLanguage)
        assertEquals(TrackSource.SavedFile, plan.audioSource)
        assertEquals(SubtitlePlan.Off(), plan.subtitles)
        assertEquals(TrackSource.SavedFile, plan.subtitleSource)
    }

    @Test
    fun `the series comes before settings`() {
        val series = TrackChoice(audioLanguage = "kor", textLanguage = "eng", subtitlesOn = true)
        val plan = TrackRules.plan(null, series, japaneseAudio, "en")
        assertEquals("ko", plan.audioLanguage)
        assertEquals(TrackSource.Series, plan.audioSource)
        assertEquals(SubtitlePlan.On("en"), plan.subtitles)
        assertEquals(TrackSource.Series, plan.subtitleSource)
    }

    @Test
    fun `a series watched without subtitles keeps them off`() {
        val plan = TrackRules.plan(null, TrackChoice(audioLanguage = "jpn"), japaneseAudio, "en")
        assertEquals(SubtitlePlan.Off(), plan.subtitles)
        assertEquals(TrackSource.Series, plan.subtitleSource)
    }

    @Test
    fun `settings fill what the file and the series leave open`() {
        // A resume that knew only its speed says nothing about tracks. The subtitle
        // want is the preferred order's head (item 14, v1.0.16): Malayalam first on
        // an untouched install, where the app's language stood before.
        val plan = TrackRules.plan(ResumeState(speed = 1.5f), TrackChoice(), japaneseAudio, "en")
        assertEquals("ja", plan.audioLanguage)
        assertEquals(TrackSource.Settings, plan.audioSource)
        assertEquals(SubtitlePlan.On("ml", fallBack = true), plan.subtitles)
        assertEquals(listOf("ml", "en"), plan.subtitleOrder)
        assertEquals(TrackSource.Settings, plan.subtitleSource)
    }

    @Test
    fun `automatic everywhere takes the preferred order, Malayalam first`() {
        // Item 14, v1.0.16, the owner's order overruling the old file-defaults plan:
        // with nothing remembered and nothing picked, a video opens with the highest
        // preferred language it has; with none of them, its own pick stands
        // (fallBack false), where SubtitlePlan.FileDefault stood before.
        val plan = TrackRules.plan(null, TrackChoice(), TrackPreferences(), "en")
        assertNull(plan.audioLanguage)
        assertEquals(TrackSource.FileDefaults, plan.audioSource)
        assertEquals(SubtitlePlan.On("ml", fallBack = false), plan.subtitles)
        assertEquals(listOf("ml", "en"), plan.subtitleOrder)
    }

    @Test
    fun `the stored order reads back, cleaned and deduplicated`() {
        assertEquals(listOf("en", "ml"), TrackPreferences.from(null, null, null, "en,ml,en,,xx").subtitleOrder)
        assertEquals(TrackPreferences.DEFAULT_ORDER, TrackPreferences.from(null, null, null, "").subtitleOrder)
        assertEquals(TrackPreferences.DEFAULT_ORDER, TrackPreferences.from(null, null, null, null).subtitleOrder)
        // A single picked language leads the wanted list, the order behind it.
        val prefs = TrackPreferences(subtitleLanguage = "ta", subtitleOrder = listOf("ml", "en"))
        assertEquals(listOf("ta", "ml", "en"), prefs.wantedSubtitleLanguages("en"))
    }

    @Test
    fun `off as the subtitle language beats any mode`() {
        val prefs = TrackPreferences(subtitlesOff = true, mode = SubtitleMode.Always)
        assertEquals(SubtitlePlan.Off(), TrackRules.plan(null, TrackChoice(), prefs, "en").subtitles)
    }

    @Test
    fun `foreign audio counts the subtitle language and the app language as yours`() {
        val prefs = TrackPreferences(subtitleLanguage = "ml", mode = SubtitleMode.ForeignAudio)
        val plan = TrackRules.plan(null, TrackChoice(), prefs, "en-US")
        assertEquals(SubtitlePlan.IfForeignAudio("ml", setOf("ml", "en")), plan.subtitles)
    }

    // ---- picking a track --------------------------------------------------------------------

    private val tracks = listOf(
        SubtitleTrackInfo("eng", forced = true),
        SubtitleTrackInfo("eng"),
        SubtitleTrackInfo("mal", isDefault = true),
    )

    @Test
    fun `a full track is picked over a forced one in the same language`() {
        assertEquals(SubtitlePick.Track(1), TrackRules.pickSubtitle(SubtitlePlan.On("en"), null, tracks))
    }

    @Test
    fun `always falls back to the default track, a remembered language does not`() {
        assertEquals(SubtitlePick.Track(2), TrackRules.pickSubtitle(SubtitlePlan.On("ko", fallBack = true), null, tracks))
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("ko"), null, tracks))
    }

    @Test
    fun `off unless chosen lets the forced track for the soundtrack through`() {
        val plan = SubtitlePlan.Off(allowForced = true)
        assertEquals(SubtitlePick.Track(0), TrackRules.pickSubtitle(plan, "en", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, "jpn", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(SubtitlePlan.Off(), "en", tracks))
    }

    @Test
    fun `foreign audio turns subtitles on only for a soundtrack in another language`() {
        val plan = SubtitlePlan.IfForeignAudio("en", setOf("en"))
        assertEquals(SubtitlePick.Track(1), TrackRules.pickSubtitle(plan, "jpn", tracks))
        assertEquals(SubtitlePick.Track(0), TrackRules.pickSubtitle(plan, "eng", tracks))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, "eng", tracks.drop(1)))
        assertEquals(SubtitlePick.None, TrackRules.pickSubtitle(plan, null, tracks.drop(1)))
    }

    @Test
    fun `the order is tried after the plan's own language`() {
        // Item 14, v1.0.16: Tamil is in no track; of the order, Malayalam stands
        // above English in the first, English above Malayalam in the second.
        assertEquals(
            SubtitlePick.Track(2),
            TrackRules.pickSubtitle(SubtitlePlan.On("ta"), null, tracks, order = listOf("ml", "en")),
        )
        assertEquals(
            SubtitlePick.Track(1),
            TrackRules.pickSubtitle(SubtitlePlan.On("ta"), null, tracks, order = listOf("en", "ml")),
        )
        // No order: the plan's language alone, as before.
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("ta"), null, tracks))
    }

    @Test
    fun `no tracks means nothing to do`() {
        assertEquals(SubtitlePick.Leave, TrackRules.pickSubtitle(SubtitlePlan.On("en", fallBack = true), "ja", emptyList()))
    }
}
