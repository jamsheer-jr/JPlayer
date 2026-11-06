package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {

    private var nextId = 1L

    private fun video(chatId: Long, fileName: String) = MediaItem(
        chatId = chatId,
        messageId = nextId++,
        fileId = 1,
        title = fileName,
        sizeBytes = 1,
        durationSec = 60,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = nextId.toInt(),
        fileName = fileName,
    )

    private fun chat(id: Long) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel)

    @Test
    fun `encode and decode round trip, names with tabs and commas included`() {
        val list = listOf(
            VideoCollection("Family, films", listOf("7_11", "7_12")),
            VideoCollection("Odd\tname", listOf("-3_9")),
        )
        assertEquals(list, Collections.decode(Collections.encode(list)))
    }

    @Test
    fun `decode keeps the good lines of a damaged value`() {
        val raw = "no tab here\nGood\t7_1,bad,7_2\n\t7_3\nGood\t7_4\nOther\t-1_2"
        val decoded = Collections.decode(raw)
        assertEquals(listOf("Good", "Other"), decoded.map { it.name })
        assertEquals(listOf("7_1", "7_2"), decoded[0].keys)
        assertEquals(listOf("-1_2"), decoded[1].keys)
        assertTrue(Collections.decode(null).isEmpty())
        assertTrue(Collections.decode("").isEmpty())
    }

    @Test
    fun `toggling adds, removes, starts and retires a collection`() {
        var list = Collections.toggled(emptyList(), "Weekend", "7_1")
        assertEquals(listOf(VideoCollection("Weekend", listOf("7_1"))), list)
        list = Collections.toggled(list, "weekend", "7_2")
        assertEquals(listOf("7_1", "7_2"), list[0].keys)
        assertTrue(Collections.contains(list, "WEEKEND", "7_2"))
        list = Collections.toggled(list, "Weekend", "7_1")
        assertEquals(listOf("7_2"), list[0].keys)
        list = Collections.toggled(list, "Weekend", "7_2")
        assertTrue(list.isEmpty())
        // A key that is not a progress key changes nothing.
        assertEquals(list, Collections.toggled(list, "Weekend", "junk"))
    }

    @Test
    fun `a collection becomes a Home row with its titles, one tile being enough`() {
        val one = video(1, "Alpha 2024.mp4")
        val two = video(1, "Beta 2024.mp4")
        val three = video(1, "Gamma 2024.mp4")
        val mine = VideoCollection("Mine", listOf(ProgressKeys.of(two.chatId, two.messageId)))
        val rows = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(one, two, three)),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            collections = listOf(mine),
        )
        val shelf = rows.filterIsInstance<HomeRow.Catalog>().firstOrNull { it.key == "catalog-collection-0" }
        assertEquals("Mine", shelf?.label)
        assertEquals(listOf(two.messageId), shelf?.entries?.map { (it as ShelfEntry.File).item.messageId })
        // A collection whose titles are not on Home draws no row.
        val ghost = VideoCollection("Ghost", listOf("9_9"))
        val rows2 = HomeRows.build(
            continueWatching = emptyList(),
            favourites = listOf(chat(1)),
            loaded = mapOf(1L to listOf(one, two, three)),
            recent = emptyList(),
            layout = HomeLayout(catalogRows = true),
            collections = listOf(ghost),
        )
        assertTrue(rows2.none { it.key == "catalog-collection-0" })
    }
}
