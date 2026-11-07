package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerProfilesTest {

    @Test
    fun `the default profile has no prefix and later profiles carry theirs`() {
        assertEquals("", ViewerProfiles.prefixFor(ViewerProfiles.DEFAULT_ID))
        assertEquals("pf_p2_", ViewerProfiles.prefixFor("p2"))
        assertEquals("pf_p2_", ViewerProfiles.prefixOf("pf_p2_resume_-100_5"))
        assertEquals("", ViewerProfiles.prefixOf("resume_-100_5"))
        assertEquals("resume_-100_5", ViewerProfiles.stripPrefix("pf_p2_resume_-100_5"))
        assertEquals("resume_-100_5", ViewerProfiles.stripPrefix("resume_-100_5"))
    }

    @Test
    fun `a stored list round trips, the default first`() {
        val profiles = listOf(
            ViewerProfile("p2", "Kids", simple = true),
            ViewerProfile("", "Mine"),
            ViewerProfile("p3", "Gran"),
        )
        val back = ViewerProfiles.decode(ViewerProfiles.encode(profiles))
        assertEquals(listOf("", "p2", "p3"), back.map { it.id })
        assertEquals("Mine", back[0].name)
        assertTrue(back[1].simple)
        assertFalse(back[2].simple)
    }

    @Test
    fun `the default profile is never simple and never written while unnamed`() {
        assertEquals("", ViewerProfiles.encode(listOf(ViewerProfile("", ""))))
        val back = ViewerProfiles.decode(ViewerProfiles.encode(listOf(ViewerProfile("", "Mine", simple = true))))
        assertFalse(back.single().simple)
    }

    @Test
    fun `damaged lines lose only themselves`() {
        val raw = listOf(
            "p2\u001FKids\u001Fs",
            "no separators here",
            "p2\u001FAgain\u001Ff",
            "bad id!\u001FName\u001Ff",
            "p3\u001F\u001Ff",
            "p4\u001FGran\u001Fx",
        ).joinToString("\n")
        val back = ViewerProfiles.decode(raw)
        assertEquals(listOf("p2", "p4"), back.map { it.id })
        assertTrue(back[0].simple)
        assertFalse(back[1].simple)
    }

    @Test
    fun `ids are handed out in order and the cap holds`() {
        assertEquals("p2", ViewerProfiles.newId(emptyList()))
        assertEquals("p3", ViewerProfiles.newId(listOf("p2")))
        assertEquals("p2", ViewerProfiles.newId(listOf("p3")))
        val full = (2..5).map { ViewerProfile("p$it", "P$it") }
        assertFalse(ViewerProfiles.canAdd(full))
        assertTrue(ViewerProfiles.canAdd(full.drop(1)))
        assertTrue(ViewerProfiles.decode(ViewerProfiles.encode(full + ViewerProfile("p9", "Extra"))).size <= ViewerProfiles.MAX_PROFILES)
    }

    @Test
    fun `names are cleaned for storage`() {
        assertEquals("A B", ViewerProfiles.cleanName("  A   B  "))
        assertEquals("", ViewerProfiles.cleanName("   "))
        assertEquals(ViewerProfiles.MAX_NAME, ViewerProfiles.cleanName("x".repeat(60)).length)
    }
}
