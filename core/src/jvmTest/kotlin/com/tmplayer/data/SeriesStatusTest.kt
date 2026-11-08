package com.tmplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The series status store (item 9d, v1.0.15): round trips, replacement, and bad lines. */
class SeriesStatusTest {

    private fun record(key: String, status: SeriesStatus, at: Long) = SeriesStatusRecord(
        key = key,
        title = "The Show",
        chatId = -1001,
        messageId = 42,
        fileId = 7,
        status = status,
        at = at,
    )

    @Test
    fun `encode and decode round trip`() {
        val records = listOf(
            record("1|show", SeriesStatus.Watching, 300),
            record("2|other", SeriesStatus.Dropped, 100),
        )
        assertEquals(records, SeriesStatuses.decode(SeriesStatuses.encode(records)))
    }

    @Test
    fun `setting a status replaces the series' old one`() {
        val before = listOf(record("1|show", SeriesStatus.Watching, 100))
        val after = SeriesStatuses.with(before, record("1|show", SeriesStatus.Completed, 200))
        assertEquals(1, after.size)
        assertEquals(SeriesStatus.Completed, SeriesStatuses.statusOf(after, "1|show"))
    }

    @Test
    fun `clearing takes the record out and byStatus groups the lists`() {
        val records = listOf(
            record("1|a", SeriesStatus.Watching, 300),
            record("1|b", SeriesStatus.Watching, 200),
            record("1|c", SeriesStatus.Dropped, 100),
        )
        assertEquals(2, SeriesStatuses.byStatus(records, SeriesStatus.Watching).size)
        val cleared = SeriesStatuses.without(records, "1|a")
        assertNull(SeriesStatuses.statusOf(cleared, "1|a"))
        assertEquals(2, cleared.size)
    }

    @Test
    fun `bad lines lose only themselves`() {
        val good = SeriesStatuses.encode(listOf(record("1|show", SeriesStatus.Completed, 5)))
        val raw = "nonsense\n$good\n1|x\u001Ftitle\u001Fnotanumber\u001F2\u001F3\u001Fw\u001F9\n1|y\u001Ftitle\u001F1\u001F2\u001F3\u001Fz\u001F9"
        val decoded = SeriesStatuses.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("1|show", decoded[0].key)
        assertTrue(SeriesStatuses.decode(null).isEmpty())
        assertTrue(SeriesStatuses.decode("").isEmpty())
    }

    @Test
    fun `the cap holds and the oldest set goes first`() {
        var records = emptyList<SeriesStatusRecord>()
        for (n in 1..(SeriesStatuses.MAX_RECORDS + 5)) {
            records = SeriesStatuses.with(records, record("1|s$n", SeriesStatus.Watching, n.toLong()))
        }
        assertEquals(SeriesStatuses.MAX_RECORDS, records.size)
        assertNull(SeriesStatuses.statusOf(records, "1|s1"))
    }
}
