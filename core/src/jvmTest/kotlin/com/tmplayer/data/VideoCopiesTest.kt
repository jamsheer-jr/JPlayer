package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A place, a pick and a habit belong to the video, not to the copy that was played: switching
 * from the 720p to the 1080p carries on where it was, and Continue watching keeps one card.
 */
class VideoCopiesTest {

    private fun store() = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-copies").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun video(
        chat: Long,
        message: Long,
        name: String,
        size: Long = 900L shl 20,
        date: Int = message.toInt(),
        locality: MediaItem.Locality = MediaItem.Locality.Remote,
        width: Int = 0,
        height: Int = 0,
    ) = MediaItem(
        chatId = chat, messageId = message, fileId = message.toInt(), title = name, sizeBytes = size,
        durationSec = 7_200, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null,
        date = date, fileName = name, onDevice = locality != MediaItem.Locality.Remote, locality = locality,
        width = width, height = height,
    )

    private fun describe(item: MediaItem, at: Long) = ResumeRecord.encode(
        fileId = item.fileId, title = item.title, chatTitle = "Films", sizeBytes = item.sizeBytes,
        durationSec = item.durationSec, updatedAt = at, fileName = item.fileName,
    )

    private val hd = video(1, 10, "Night.Train.2019.1080p.WEB-DL.x265.mkv")
    private val sd = video(1, 11, "Night.Train.2019.720p.HDRip.x264.mkv", size = 700L shl 20)
    private val uhd = video(2, 50, "Night Train (2019) 2160p HEVC.mkv", size = 6_000L shl 20)
    private val other = video(1, 12, "Low.Tide.2022.1080p.mkv")

    // ---- the same file posted twice ----------------------------------------------------------

    @Test
    fun `the same file posted twice is one copy, whether forwarded or uploaded again`() {
        val forwarded = hd.copy(chatId = 3, messageId = 70) // the same Telegram file
        val again = video(1, 13, hd.fileName) // the same name and size, a new upload
        val files = VideoCopies.distinctFiles(listOf(hd, sd, forwarded, again, uhd))
        assertEquals(listOf(hd.id, sd.id, uhd.id), files.map { it.first.id })
        assertEquals(listOf(hd.id, forwarded.id, again.id), files[0].second.map { it.id })
        // The player lists the post playing for its file.
        assertEquals(listOf(again.id, sd.id, uhd.id), VideoCopies.oneOfEachFile(listOf(hd, sd, forwarded, again, uhd), again.id).map { it.id })
    }

    @Test
    fun `a copy in a chat the kids lock hides is never offered`() {
        val files = VideoCopies.distinctFiles(listOf(hd, sd, uhd), allowed = setOf(1L))
        assertEquals(listOf(hd.id, sd.id), files.map { it.first.id })
        assertEquals(listOf(hd.id, sd.id, uhd.id), VideoCopies.visible(listOf(hd, sd, uhd), allowed = null).map { it.id })
        assertEquals(listOf(hd.id, sd.id), VideoCopies.oneOfEachFile(listOf(hd, sd, uhd), hd.id, allowed = setOf(1L)).map { it.id })
    }

    // ---- keying ------------------------------------------------------------------------------

    @Test
    fun `copies of one film share a key, in one chat or two, and another film does not`() {
        assertEquals(VideoCopies.keyOf(hd), VideoCopies.keyOf(sd))
        assertEquals(VideoCopies.keyOf(hd), VideoCopies.keyOf(uhd))
        assert(VideoCopies.keyOf(other) != VideoCopies.keyOf(hd))
        assertNull(VideoCopies.keyOfTitle("Birthday highlights.mp4"))
    }

    @Test
    fun `a copy resumes from the newest place among the video's copies`() {
        val records = listOf(
            ResumeRecord(1, 10, 10, hd.title, "Films", 0, 0, 1_200_000, 7_200_000, updatedAt = 100),
            ResumeRecord(2, 50, 50, uhd.title, "Films", 0, 0, 3_000_000, 7_200_000, updatedAt = 200),
            ResumeRecord(1, 12, 12, other.title, "Films", 0, 0, 900_000, 7_200_000, updatedAt = 300),
        )
        assertEquals(3_000_000L, VideoCopies.resumeOf(sd, records)?.positionMs)
        assertEquals(3_000_000L, VideoCopies.resumeOf(hd, records)?.positionMs)
        assertEquals(900_000L, VideoCopies.resumeOf(other, records)?.positionMs)
    }

    @Test
    fun `a place carries over as it is, scaled for another frame rate, and not into another cut`() {
        assertEquals(600_000L, VideoCopies.carryOver(600_000, 7_200_000, 7_250_000))
        // 25 fps against 23.976: four per cent shorter, so the same scene comes four per cent sooner.
        assertEquals(576_000L, VideoCopies.carryOver(600_000, 7_200_000, 6_912_000))
        assertNull(VideoCopies.carryOver(600_000, 7_200_000, 8_400_000))
        assertEquals(600_000L, VideoCopies.carryOver(600_000, 0, 8_400_000))
        val extended = video(3, 70, "Night.Train.2019.Extended.1080p.mkv").copy(durationSec = 9_000)
        val records = listOf(ResumeRecord(1, 10, 10, hd.title, "Films", 0, 0, 1_200_000, 7_200_000, updatedAt = 100))
        assertNull(VideoCopies.resumeOf(extended, records))
    }

    @Test
    fun `old records only count as copies when their title names a year or an episode`() {
        // A caption's bare code, or a name whose only tag is its quality, groups nothing.
        val caption = ResumeRecord(1, 20, 20, "S02E04", "Shows", 0, 0, 600_000, 2_400_000, updatedAt = 1)
        val plain = ResumeRecord(1, 21, 21, "Lecture 1080p", "Class", 0, 0, 600_000, 2_400_000, updatedAt = 2)
        val plainToo = ResumeRecord(1, 22, 22, "Lecture 720p", "Class", 0, 0, 600_000, 2_400_000, updatedAt = 3)
        assertNull(VideoCopies.keyOf(caption))
        assertNull(VideoCopies.keyOf(plain))
        assertEquals(listOf(caption, plain, plainToo), HomeRows.foldCopies(listOf(caption, plain, plainToo)))
        // A record that kept its file name and caption is read like the video itself.
        val named = caption.copy(fileName = "harbour_notes_720p.mkv", caption = "Harbour Notes S02E04")
        assertEquals(VideoCopies.keyOf(named.toMediaItem()), VideoCopies.keyOf(named))
        assertEquals("episode:harbour notes|2|4", VideoCopies.keyOf(named))
    }

    @Test
    fun `a record keeps the file name and caption, and reads older lines as before`() {
        val line = ResumeRecord.encode(9, "S02E04", "Shows", 1, 2, 3, fileName = "harbour_notes_720p.mkv", caption = "Harbour Notes S02E04\nmore")
        val record = ResumeRecord.decode("1_2", line, 10, 20)!!
        assertEquals("harbour_notes_720p.mkv", record.fileName)
        assertEquals("Harbour Notes S02E04", record.caption)
        assertNull(record.state)
        val old = ResumeRecord.decode("1_2", ResumeRecord.encode(9, "S02E04", "Shows", 1, 2, 3), 10, 20)!!
        assertNull(old.fileName)
        assertEquals("S02E04", old.toMediaItem().fileName)
    }

    // ---- the store, and what was stored before ----------------------------------------------

    @Test
    fun `places stay per copy, and the newest wins on open`() = runBlocking {
        val settings = store()
        // Two copies of one film, both with a place, as earlier builds left them.
        settings.restoreEntries {
            mapOf(
                "resume_1_10" to 1_200_000L, "duration_1_10" to 7_200_000L, "meta_1_10" to describe(hd, 100),
                "resume_2_50" to 3_000_000L, "duration_2_50" to 7_200_000L, "meta_2_50" to describe(uhd, 200),
            )
        }
        // Nothing is moved or dropped by reading: both places are still there.
        assertEquals(2, settings.continueWatching.first().size)
        assertEquals(3_000_000L, settings.resumeRecordFor(sd)?.positionMs)
        assertEquals(3_000_000L, settings.resumeRecordFor(hd)?.positionMs)
        assertEquals(3_000_000L - ResumeRewind.DEFAULT_MS, settings.resumeStartFor(hd))
        assertNull(settings.resumeRecordFor(other))
        // Continue watching shows the film once, at its newest place.
        val folded = HomeRows.foldCopies(settings.continueWatching.first())
        assertEquals(listOf(50L), folded.map { it.messageId })
    }

    @Test
    fun `switching copy mid play carries the place and leaves one card`() = runBlocking {
        val settings = store()
        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 100))
        settings.saveResumePosition(1, 12, 600_000, 7_200_000, describe(other, 110))
        // The viewer switches to the 720p at 30:00: it opens there, then carries on.
        assertEquals(1_800_000L, settings.resumeRecordFor(sd)?.positionMs)
        settings.saveResumePosition(1, 11, 1_810_000, 7_200_000, describe(sd, 120))
        val folded = HomeRows.foldCopies(settings.continueWatching.first())
        assertEquals(listOf(11L, 12L), folded.map { it.messageId })
        assertEquals(1_810_000L, settings.resumeRecordFor(hd)?.positionMs)
        // The 1080p's own line is still there, untouched.
        assertEquals(1_800_000L, settings.resumePosition(1, 10))
    }

    @Test
    fun `finishing or starting over in one copy clears the others`() = runBlocking {
        val settings = store()
        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 100))
        settings.saveResumePosition(2, 50, 2_000_000, 7_200_000, describe(uhd, 200))
        settings.saveResumePosition(1, 12, 600_000, 7_200_000, describe(other, 210))
        settings.clearResumePosition(sd.chatId, sd.messageId, sd)
        assertEquals(listOf(12L), settings.continueWatching.first().map { it.messageId })

        settings.saveResumePosition(1, 10, 1_800_000, 7_200_000, describe(hd, 300))
        // The credits of the 4K copy.
        settings.saveResumePosition(2, 50, 7_000_000, 7_200_000, describe(uhd, 400))
        assertEquals(listOf(12L), settings.continueWatching.first().map { it.messageId })
    }

    // ---- which copy plays --------------------------------------------------------------------

    @Test
    fun `the pick order is chosen, on device, started, usual quality, newest`() {
        val newest = video(1, 20, "Night.Train.2019.2160p.mkv", date = 900)
        val small = video(1, 21, "Night.Train.2019.720p.mkv", date = 500)
        val full = video(1, 22, "Night.Train.2019.1080p.mkv", date = 400)
        val kept = video(1, 23, "Night.Train.2019.480p.mkv", date = 300, locality = MediaItem.Locality.Downloaded)
        val all = listOf(newest, small, full, kept)
        val habit = QualityHabit(mapOf(1080 to 9, 2160 to 1))

        assertEquals(small, VideoCopies.pick(all, chosen = small.id, habit = habit))
        assertEquals(kept, VideoCopies.pick(all, habit = habit))
        val remote = all - kept
        assertEquals(small, VideoCopies.pick(remote, started = { it == small }, habit = habit))
        assertEquals(full, VideoCopies.pick(remote, habit = habit))
        assertEquals(newest, VideoCopies.pick(remote))
        // A pick that no longer exists (the message was deleted) is passed over.
        assertEquals(full, VideoCopies.pick(remote, chosen = "1:999", habit = habit))
    }

    @Test
    fun `saving data plays the smallest adequate copy, and the standing winners still win`() {
        val uhdCopy = video(1, 60, "Film.2024.2160p.mkv", size = 6_000L shl 20, date = 400)
        val hdCopy = video(1, 61, "Film.2024.1080p.mkv", size = 2_000L shl 20, date = 300)
        val light = video(1, 62, "Film.2024.480p.mkv", size = 500L shl 20, date = 200)
        val lighter = video(1, 63, "Film.2024.360p.mkv", size = 300L shl 20, date = 100)
        val all = listOf(uhdCopy, hdCopy, light, lighter)
        val habit = QualityHabit(mapOf(1080 to 9))
        // The 360p copy: the lowest height at the floor, ahead of habit and newness.
        assertEquals(lighter, VideoCopies.pick(all, habit = habit, saveData = true))
        // Off, the same list picks by habit as before.
        assertEquals(hdCopy, VideoCopies.pick(all, habit = habit))
        // A chosen copy is the viewer's decision, a downloaded one costs no data, and a
        // started one is the resume itself: all three still win over the saving.
        assertEquals(uhdCopy, VideoCopies.pick(all, chosen = uhdCopy.id, habit = habit, saveData = true))
        val kept = video(1, 64, "Film.2024.1080p.mkv", locality = MediaItem.Locality.Downloaded)
        assertEquals(kept, VideoCopies.pick(all + kept, habit = habit, saveData = true))
        assertEquals(hdCopy, VideoCopies.pick(all, started = { it == hdCopy }, habit = habit, saveData = true))
        // The language pool still bounds the saving: a Tamil 720p beats an unnamed 360p.
        val tamil = video(1, 65, "Film.2024.Tamil.720p.mkv", size = 800L shl 20)
        assertEquals(tamil, VideoCopies.pick(all + tamil, habit = habit, language = "ta", saveData = true))
    }

    @Test
    fun `the smallest adequate copy reads heights, sizes and the floor as written`() {
        // Nothing reaches the floor: the highest below it is the best of what there is.
        val tiny = video(1, 70, "Clip.240p.mkv", size = 50L shl 20)
        val small = video(1, 71, "Clip.144p.mkv", size = 20L shl 20)
        assertEquals(tiny, VideoCopies.smallestAdequate(listOf(small, tiny)))
        // At the same height the smaller file wins, then the newer.
        val big480 = video(1, 72, "Film.480p.mkv", size = 900L shl 20, date = 10)
        val small480 = video(1, 73, "Film.480p.mkv", size = 400L shl 20, date = 5)
        assertEquals(small480, VideoCopies.smallestAdequate(listOf(big480, small480)))
        // Heights from Telegram's own picture size count too, and an unknown height stands
        // behind every known one; among unknown heights the smallest file wins.
        val wide = video(1, 74, "Film.mkv", width = 1920, height = 800, size = 100L shl 20)
        val unknown = video(1, 75, "Film copy.mkv", size = 10L shl 20)
        assertEquals(wide, VideoCopies.smallestAdequate(listOf(unknown, wide)))
        val unknownBig = video(1, 76, "Film copy two.mkv", size = 30L shl 20)
        assertEquals(unknown, VideoCopies.smallestAdequate(listOf(unknownBig, unknown)))
        assertNull(VideoCopies.smallestAdequate(emptyList()))
    }

    @Test
    fun `the viewer's audio language comes before the usual quality, and leads the next episode`() {
        val tamil = video(1, 30, "Film.2020.Tamil.720p.mkv", date = 100)
        val hindi = video(1, 31, "Film.2020.Hindi.1080p.mkv", date = 200)
        val habit = QualityHabit(mapOf(1080 to 5))
        assertEquals(tamil, VideoCopies.pick(listOf(tamil, hindi), habit = habit, language = "ta"))
        assertEquals(hindi, VideoCopies.pick(listOf(tamil, hindi), habit = habit, language = "fr"))
        val next = listOf(
            video(1, 40, "Show.S01E02.Hindi.720p.mkv"),
            video(1, 41, "Show.S01E02.Tamil.1080p.mkv"),
        )
        assertEquals(41L, EpisodeCopies.closest(video(1, 39, "Show.S01E01.Tamil.720p.mkv"), next)?.messageId)
    }

    @Test
    fun `the usual quality takes the nearest copy, the lower one on a tie`() {
        val a = video(1, 1, "Film.2020.720p.mkv")
        val b = video(1, 2, "Film.2020.1440p.mkv")
        assertEquals(a, VideoCopies.pick(listOf(a, b), habit = QualityHabit(mapOf(1080 to 3))))
        // Read off Telegram's own picture size when the name says nothing.
        val wide = video(1, 3, "Film (2020).mkv", width = 1920, height = 800)
        val small = video(1, 4, "Film (2020) small.mkv", width = 854, height = 480)
        assertEquals(1080, VideoCopies.heightOf(wide))
        assertEquals(wide, VideoCopies.pick(listOf(small, wide), habit = QualityHabit(mapOf(1080 to 1))))
    }

    @Test
    fun `the habit counts plays, survives a round trip and halves past its cap`() {
        var habit = QualityHabit()
        repeat(3) { habit = habit.noted(1080) }
        habit = habit.noted(2160).noted(null)
        assertEquals(1080, habit.usual)
        assertEquals(habit, QualityHabit.decode(habit.encode()))
        assertEquals(QualityHabit(), QualityHabit.decode("junk,1080:x,:3"))
        repeat(QualityHabit.CAP) { habit = habit.noted(1080) }
        assert(habit.counts.getValue(1080) <= QualityHabit.CAP)
    }

    @Test
    fun `the store remembers a pick per video and the habit`() = runBlocking {
        val settings = store()
        settings.setCopyPick(sd)
        assertEquals(sd.id, settings.copyPickFor(hd))
        assertEquals(sd.id, settings.copyPickFor(uhd))
        assertNull(settings.copyPickFor(other))
        settings.notePlayedQuality(hd)
        settings.notePlayedQuality(hd)
        settings.notePlayedQuality(uhd)
        assertEquals(1080, settings.qualityHabitNow().usual)
    }

    @Test
    fun `the next episode is the copy most like the one playing`() {
        val show = (1..2).flatMap { n ->
            listOf(
                video(4, 100L + n, "Harbour.Lights.S01E0$n.1080p.mkv", size = 1_800L shl 20),
                video(4, 200L + n, "Harbour.Lights.S01E0$n.720p.mkv", size = 350L shl 20),
            )
        }
        val steps = EpisodeNeighbours.around(show[1], show)
        assertEquals(202L, steps.next?.messageId)
        val other = EpisodeNeighbours.around(show[0], show)
        assertEquals(102L, other.next?.messageId)
    }

    @Test
    fun `a height written without its p still names the copy's quality`() {
        val bare = video(1, 60, "Night Train 2019 Malayalam HQ HDRip - 1080.mkv")
        assertEquals(1080, VideoCopies.heightOf(bare))
        assertEquals("1080p", bare.copyQuality())
        assertEquals("HDRip", MediaFacts.of(bare).source)
    }

    @Test
    fun `the page's quality badge names the copy as the copy line does`() {
        // The name says 1080p while Telegram states 1280 x 720: the badge followed the size and
        // said 720p over a copy line of "1080p  ·  HEVC" (Mi TV, 2026-10-23).
        val copy = video(1, 70, "Night Train 2019 1080p WEBRip x265.mkv", width = 1280, height = 720)
        assertEquals("1080p", VideoCopies.qualityLabel(copy))
        assertEquals(copy.copyQuality(), VideoCopies.qualityLabel(copy))
        // Only the stated size: it decides, a scope 4K film included.
        assertEquals("720p", VideoCopies.qualityLabel(video(1, 71, "Night Train.mkv", width = 1280, height = 720)))
        assertEquals("4K", VideoCopies.qualityLabel(video(1, 72, "Night Train.mkv", width = 3840, height = 1600)))
        // Nothing to go on: no badge, and the copy line falls back to the size.
        assertNull(VideoCopies.qualityLabel(video(1, 73, "Night Train.mkv")))
    }

    @Test
    fun `the top bar badge bands a height as UHD, HD or SD`() {
        assertEquals("UHD", VideoCopies.badgeForHeight(2160))
        assertEquals("HD", VideoCopies.badgeForHeight(1080))
        assertEquals("SD", VideoCopies.badgeForHeight(720))
        assertEquals("SD", VideoCopies.badgeForHeight(480))
        assertEquals("SD", VideoCopies.badgeForHeight(360))
        assertNull(VideoCopies.badgeForHeight(0))
    }

    @Test
    fun `a search card counts files as the page counts versions`() {
        val one = video(1, 80, "Night Train 2019 1080p.mkv")
        // The same file forwarded to a second chat: one version, though two posts.
        val forwarded = one.copy(chatId = 2, messageId = 81)
        val other = video(1, 82, "Night Train 2019 720p.mkv", size = 500L shl 20)
        val group = ResultGroup("night train", listOf(one, forwarded, other))
        assertEquals(2, group.fileCount)
        assertEquals(VideoCopies.distinctFiles(group.asItem().versions).size, group.fileCount)
    }

    // ---- the badge's tier climb ---------------------------------------------------------------

    @Test
    fun `the badge climbs to the next tier the copies have, and wraps at the top`() {
        val copies = listOf(sd, hd, uhd)
        assertEquals(hd.id, VideoCopies.nextTierCopy(copies, sd.id)?.id)
        assertEquals(uhd.id, VideoCopies.nextTierCopy(copies, hd.id)?.id)
        assertEquals(sd.id, VideoCopies.nextTierCopy(copies, uhd.id)?.id)
    }

    @Test
    fun `the badge skips a tier the copies do not have, and takes the tier's best by the order`() {
        assertEquals(uhd.id, VideoCopies.nextTierCopy(listOf(sd, uhd), sd.id)?.id)
        assertEquals(sd.id, VideoCopies.nextTierCopy(listOf(sd, uhd), uhd.id)?.id)
        val hdBig = video(2, 60, "Night.Train.2019.1080p.BluRay.mkv", size = 2_000L shl 20)
        // The order is picture, then codec, then size (item 13, v1.0.16): the HEVC hd
        // copy beats the bigger BluRay copy with no codec in its name.
        assertEquals(hd.id, VideoCopies.nextTierCopy(listOf(sd, hd, hdBig), sd.id)?.id)
    }

    @Test
    fun `the chooser lists each level once, lowest first, with its best copy and the playing level marked`() {
        val hdBig = video(2, 60, "Night.Train.2019.1080p.BluRay.mkv", size = 2_000L shl 20)
        val choices = VideoCopies.tierChoices(listOf(uhd, hd, sd, hdBig), hd.id)
        // The levels (owner, v1.0.18): the light 720p stands at SD, the 1080p copies at HD+.
        assertEquals(listOf("SD", "HD+", "UHD"), choices.map { it.tier })
        assertEquals(hd.id, choices[1].copy.id)
        assertEquals(listOf(false, true, false), choices.map { it.current })
        val unknown = video(1, 99, "Mystery.File.mkv")
        assertEquals(listOf("SD"), VideoCopies.tierChoices(listOf(sd, unknown), sd.id).map { it.tier })
    }

    @Test
    fun `the chooser reads a heavy 720p as HD and a heavy 1080p as FHD`() {
        // The owner's own screenshot (v1.0.18): the levels are judged on each
        // resolution's size, so a 720p of 3.69 GB stands at HD and a 1080p of
        // 3.54 GB at FHD, where the badge's plain height bands said SD and HD.
        val gb = 1024L * 1024 * 1024
        val heavy720 = video(1, 70, "Film.2026.720p.mkv", size = 3 * gb)
        val heavy1080 = video(1, 71, "Film.2026.1080p.mkv", size = 3 * gb)
        val choices = VideoCopies.tierChoices(listOf(heavy720, heavy1080), heavy1080.id)
        assertEquals(listOf("HD", "FHD"), choices.map { it.tier })
        assertEquals(heavy720.id, choices[0].copy.id)
        assertEquals(heavy1080.id, choices[1].copy.id)
        assertEquals(listOf(false, true), choices.map { it.current })
    }

    @Test
    fun `the chooser reads an episode on the same levels as a film`() {
        val gb = 1024L * 1024 * 1024
        val ep720 = video(1, 72, "Show.S01E01.720p.mkv", size = 3 * gb)
        val ep1080 = video(1, 73, "Show.S01E01.1080p.mkv", size = 300L shl 20)
        val ep2160 = video(1, 74, "Show.S01E01.2160p.mkv")
        val choices = VideoCopies.tierChoices(listOf(ep720, ep1080, ep2160), ep720.id, series = true)
        assertEquals(listOf("HD", "HD+", "UHD"), choices.map { it.tier })
        assertEquals(listOf(true, false, false), choices.map { it.current })
    }

    @Test
    fun `the chooser still names a film whose only copy is under the floor`() {
        val tiny = video(1, 75, "Film.2026.360p.mkv", size = 300L shl 20)
        val choices = VideoCopies.tierChoices(listOf(tiny), tiny.id)
        assertEquals(listOf("SD"), choices.map { it.tier })
        assertEquals(listOf(true), choices.map { it.current })
    }

    @Test
    fun `one tier only, or the playing copy unknown, the badge changes nothing`() {
        assertNull(VideoCopies.nextTierCopy(listOf(hd, other), hd.id))
        assertNull(VideoCopies.nextTierCopy(listOf(hd), hd.id))
        assertNull(VideoCopies.nextTierCopy(emptyList(), hd.id))
        assertNull(VideoCopies.nextTierCopy(listOf(sd, hd), "no-such-copy"))
    }

    // ---- the choosers' grouping -----------------------------------------------------------------

    @Test
    fun `tiers group by height, tallest first, the biggest of a height first`() {
        val hdSmall = video(2, 61, "Night.Train.2019.1080p.BluRay.mkv", size = 800L shl 20)
        val tiers = VideoCopies.tiersOf(listOf(sd, hdSmall, uhd, hd)) { it }
        assertEquals(listOf(2160, 1080, 720), tiers.map { it.first })
        assertEquals(listOf(hd.id, hdSmall.id), tiers[1].second.map { it.id })
    }

    @Test
    fun `within one height the tiers order by codec before size`() {
        // Item 13, v1.0.16: the owner's ranking, resolution, then codec (HEVC above
        // H.264), then size. The bigger x264 stands under the smaller x265.
        val avc = video(2, 71, "Night.Train.2019.1080p.BluRay.x264.mkv", size = 1_600L shl 20)
        val hevc = video(2, 72, "Night.Train.2019.1080p.BluRay.x265.mkv", size = 900L shl 20)
        val tiers = VideoCopies.tiersOf(listOf(avc, hevc)) { it }
        assertEquals(listOf(1080), tiers.map { it.first })
        assertEquals(listOf(hevc.id, avc.id), tiers[0].second.map { it.id })
        // And the search's order, the same ranking over the same copies.
        assertEquals(listOf(hevc.id, avc.id), GlobalSearch.copyOrder(listOf(avc, hevc)).map { it.id })
    }

    @Test
    fun `within one height the original language stands before another language`() {
        // Owner, v1.0.24: the copies in the title's own language first. The
        // bigger Hindi dub of a Korean title stands under the smaller
        // untagged rip (a name stating no language reads as the original),
        // and a copy naming the original language counts as one too.
        val dub = video(2, 81, "Seoul.Story.2023.1080p.Hindi.WEB-DL.x265.mkv", size = 1_600L shl 20)
        val plain = video(2, 82, "Seoul.Story.2023.1080p.WEB-DL.x265.mkv", size = 900L shl 20)
        val named = video(2, 83, "Seoul.Story.2023.1080p.Korean.WEB-DL.x264.mkv", size = 800L shl 20)
        val tiers = VideoCopies.tiersOf(listOf(dub, plain, named), { it }, originalLanguage = "ko")
        assertEquals(listOf(plain.id, named.id, dub.id), tiers[0].second.map { it.id })
        // No original language known: the order stands as it always was.
        val plainTiers = VideoCopies.tiersOf(listOf(plain, dub)) { it }
        assertEquals(listOf(dub.id, plain.id), plainTiers[0].second.map { it.id })
        assertTrue(VideoCopies.isOriginalLanguage(plain, "ko"))
        assertTrue(VideoCopies.isOriginalLanguage(named, "ko"))
        assertFalse(VideoCopies.isOriginalLanguage(dub, "ko"))
        assertFalse(VideoCopies.isOriginalLanguage(plain, null))
        assertFalse(VideoCopies.isOriginalLanguage(plain, ""))
    }

    @Test
    fun `a copy stating no quality forms the last group, keyed by no height`() {
        // Sent as a file, its name carrying no marker: nothing about it states a picture size.
        val unstated = video(1, 98, "Jailer 2 (2026) Tamil HQ PreDVD - x264 - HQ Clean - AAC.mkv", size = 461L shl 20)
        val tiers = VideoCopies.tiersOf(listOf(unstated, sd, hd)) { it }
        assertEquals(listOf(1080, 720, null), tiers.map { it.first })
        assertEquals(listOf(unstated.id), tiers[2].second.map { it.id })
        assertEquals(emptyList<Pair<Int?, List<MediaItem>>>(), VideoCopies.tiersOf(emptyList<MediaItem>()) { it })
    }

    @Test
    fun `tiers group rows by the copy each row stands for, keeping the rows themselves`() {
        val rows = listOf(sd to "the 720p post", hd to "the 1080p post")
        val tiers = VideoCopies.tiersOf(rows) { it.first }
        assertEquals(listOf(1080, 720), tiers.map { it.first })
        assertEquals("the 1080p post", tiers[0].second.single().second)
    }

    // ---- the built-in channel's copies ----------------------------------------------------------

    // ---- the owner's copy levels (item 22, v1.0.17) ------------------------------------------

    @Test
    fun `a movie copy stands at the level its height and size say`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(VideoCopies.CopyLevel.Uhd, VideoCopies.levelOf(video(1, 20, "Film.2026.2160p.mkv", size = 900L shl 20)))
        // 1440p stands at QHD (owner, v1.0.18: QHD joins the list).
        assertEquals(VideoCopies.CopyLevel.Qhd, VideoCopies.levelOf(video(1, 35, "Film.2026.1440p.mkv", size = 3 * gb)))
        // At 1080p the high copies (2 GB and up) are FHD; the lighter ones HD+.
        assertEquals(VideoCopies.CopyLevel.Fhd, VideoCopies.levelOf(video(1, 21, "Film.2026.1080p.mkv", size = 3 * gb)))
        assertEquals(VideoCopies.CopyLevel.Fhd, VideoCopies.levelOf(video(1, 22, "Film.2026.1080p.mkv", size = 2 * gb)))
        assertEquals(VideoCopies.CopyLevel.HdPlus, VideoCopies.levelOf(video(1, 23, "Film.2026.1080p.mkv", size = 1500L shl 20)))
        // At 720p a copy of 1.5 GB and up is HD; a lighter one is SD.
        assertEquals(VideoCopies.CopyLevel.Hd, VideoCopies.levelOf(video(1, 24, "Film.2026.720p.mkv", size = 2 * gb)))
        assertEquals(VideoCopies.CopyLevel.Hd, VideoCopies.levelOf(video(1, 36, "Film.2026.720p.mkv", size = 1536L shl 20)))
        assertEquals(VideoCopies.CopyLevel.Sd, VideoCopies.levelOf(video(1, 25, "Film.2026.720p.mkv", size = 900L shl 20)))
        // Under 720p is SD at any of the owner's sizes.
        assertEquals(VideoCopies.CopyLevel.Sd, VideoCopies.levelOf(video(1, 26, "Film.2026.480p.mkv", size = 400L shl 20)))
        assertEquals(VideoCopies.CopyLevel.Sd, VideoCopies.levelOf(video(1, 27, "Film.2026.480p.mkv", size = 900L shl 20)))
        assertEquals(VideoCopies.CopyLevel.Below, VideoCopies.levelOf(video(1, 28, "Film.2026.360p.mkv", size = 300L shl 20)))
        // No stated height, no level: size alone never invents one.
        assertEquals(VideoCopies.CopyLevel.Unknown, VideoCopies.levelOf(video(1, 29, "Film.2026.mkv", size = 5 * gb)))
    }

    @Test
    fun `an episode copy stands at the same levels, with no floor`() {
        assertEquals(VideoCopies.CopyLevel.Uhd, VideoCopies.seriesLevelOf(video(1, 30, "Show.S01E01.2160p.mkv")))
        assertEquals(VideoCopies.CopyLevel.HdPlus, VideoCopies.seriesLevelOf(video(1, 31, "Show.S01E01.1080p.mkv", size = 300L shl 20)))
        assertEquals(VideoCopies.CopyLevel.Sd, VideoCopies.seriesLevelOf(video(1, 32, "Show.S01E01.720p.mkv")))
        assertEquals(VideoCopies.CopyLevel.Sd, VideoCopies.seriesLevelOf(video(1, 33, "Show.S01E01.360p.mkv", size = 150L shl 20)))
        assertEquals(VideoCopies.CopyLevel.Unknown, VideoCopies.seriesLevelOf(video(1, 34, "Show.S01E01.mkv")))
    }

    @Test
    fun `a file the built-in channel posted leads with its own post`() {
        val elsewhere = video(7, 90, "Night.Train.2019.1080p.WEB-DL.x265.mkv")
        val builtIn = video(42, 91, "Night.Train.2019.1080p.WEB-DL.x265.mkv")
        BuiltInChannels.jMoviesChatId = 42L
        try {
            val files = VideoCopies.distinctFiles(listOf(elsewhere, builtIn), allowed = null)
            assertEquals(1, files.size)
            assertEquals(builtIn.id, files.single().first.id)
            assertEquals(listOf(elsewhere.id, builtIn.id), files.single().second.map { it.id })
            // And the player's list offers that channel's post for the file.
            assertEquals(
                builtIn.id,
                VideoCopies.oneOfEachFile(listOf(elsewhere, builtIn), playing = "none", allowed = null).single().id,
            )
        } finally {
            BuiltInChannels.jMoviesChatId = null
        }
    }

    @Test
    fun `split pieces fold into one copy and a lone piece is no copy at all`() {
        // Owner, v1.0.18: part files (.001 to .011) are not copies. A cut film's
        // pieces stand as the one joined video beside the whole copy.
        val whole = video(1, 80, "Film.2026.1080p.mkv", size = 2_000L shl 20)
        val piece1 = video(1, 81, "Film.2026.1080p.mkv.001", size = 500L shl 20)
        val piece2 = video(1, 82, "Film.2026.1080p.mkv.002", size = 500L shl 20)
        val piece3 = video(1, 83, "Film.2026.1080p.mkv.003", size = 400L shl 20)
        val files = VideoCopies.distinctFiles(listOf(whole, piece1, piece2, piece3), allowed = null)
        assertEquals(2, files.size)
        val joined = files.map { it.first }.first { it.pieces.isNotEmpty() }
        assertEquals(3, joined.pieces.size)
        assertEquals(1_400L shl 20, joined.sizeBytes)
        // A lone piece beside the whole copy leaves the whole copy alone in the list.
        val lone = video(1, 84, "Film.2026.1080p.mkv.007", size = 500L shl 20)
        val only = VideoCopies.distinctFiles(listOf(whole, lone), allowed = null)
        assertEquals(listOf(whole.id), only.map { it.first.id })
        // A lone piece whose name could be an ordinary file's is no copy either.
        val maybe = video(1, 85, "Film.2026.720p.001", size = 500L shl 20)
        val whole720 = video(1, 86, "Film.2026.720p.mkv", size = 900L shl 20)
        val films = VideoCopies.distinctFiles(listOf(whole720, maybe), allowed = null)
        assertEquals(listOf(whole720.id), films.map { it.first.id })
    }

    @Test
    fun `a joined set proven an archive by its bytes is no copy`() {
        // Owner, 2026-11-08: a split set whose name denies being an archive in every
        // spelling is condemned by its first bytes alone (ArchiveHeads), and then it
        // leaves the copies like any other non video. Unproven, it stands as before.
        val piece1 = video(1, 91, "Film.2026.1080p.mkv.001", size = 500L shl 20)
        val piece2 = video(1, 92, "Film.2026.1080p.mkv.002", size = 500L shl 20)
        val standing = VideoCopies.distinctFiles(listOf(piece1, piece2), allowed = null)
        assertEquals(1, standing.size)
        val joined = standing.first().first
        assertEquals("1|film 2026 1080p", ArchiveHeads.keyOf(joined))
        val key = "1|film 2026 1080p"
        try {
            ArchiveHeads.follow(setOf(key))
            val files = VideoCopies.distinctFiles(listOf(piece1, piece2), allowed = null)
            assertTrue(files.isEmpty())
        } finally {
            ArchiveHeads.follow(emptySet())
        }
    }
}
