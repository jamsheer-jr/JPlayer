package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBookTest {

    @Test
    fun `the list round trips, awkward names included`() {
        val entries = listOf(
            AccountEntry(slot = 0, userId = 111L, name = "First Person", username = "first"),
            AccountEntry(slot = 2, userId = 222L, name = "Tab\tand\nnewline \\ backslash", username = ""),
            AccountEntry(slot = 3, userId = 0L, name = "", username = ""),
        )
        assertEquals(entries, AccountBook.decode(AccountBook.encode(entries)))
    }

    @Test
    fun `damaged lines are skipped and a slot appears once`() {
        val text = "0\t111\tFirst\tfirst\n" +
            "not a slot\t222\tBad\tbad\n" +
            "1\tnope\tBad\tbad\n" +
            "0\t333\tDupe\tdupe\n" +
            "\n" +
            "2\t222\tSecond\t"
        assertEquals(
            listOf(
                AccountEntry(0, 111L, "First", "first"),
                AccountEntry(2, 222L, "Second", ""),
            ),
            AccountBook.decode(text),
        )
        assertEquals(emptyList<AccountEntry>(), AccountBook.decode(null))
        assertEquals(emptyList<AccountEntry>(), AccountBook.decode(""))
    }

    @Test
    fun `the list is capped and slots are never reused`() {
        val many = (0..9).map { AccountEntry(it, it.toLong(), "N$it", "") }
        assertEquals(AccountBook.MAX_ACCOUNTS, AccountBook.decode(AccountBook.encode(many)).size)
        assertEquals(1, AccountBook.nextSlot(emptyList()))
        assertEquals(3, AccountBook.nextSlot(listOf(AccountEntry(0, 1L, "", ""), AccountEntry(2, 2L, "", ""))))
    }

    @Test
    fun `identity replaces its own slot and removal leaves the rest`() {
        val entries = listOf(AccountEntry(0, 1L, "Old", ""), AccountEntry(1, 2L, "Two", "two"))
        val updated = AccountBook.withIdentity(entries, AccountEntry(0, 1L, "New", "new"))
        assertEquals(listOf(AccountEntry(0, 1L, "New", "new"), AccountEntry(1, 2L, "Two", "two")), updated)
        assertEquals(listOf(AccountEntry(1, 2L, "Two", "two")), AccountBook.without(updated, 0))
        assertTrue(AccountBook.withIdentity(entries, AccountEntry(4, 4L, "Four", "")).any { it.slot == 4 })
    }
}
