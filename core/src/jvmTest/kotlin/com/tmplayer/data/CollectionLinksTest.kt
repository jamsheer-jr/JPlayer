package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionLinksTest {

    @Test
    fun `a collection round trips, name and keys included`() {
        val collection = VideoCollection("Family films", listOf("-100123_456", "789_10"))
        assertEquals(collection, CollectionLinks.parse(CollectionLinks.build(collection)))
    }

    @Test
    fun `a name with spaces and its own language survives the link`() {
        val collection = VideoCollection("കുടുംബ ചിത്രങ്ങൾ & more", listOf("1_2"))
        assertEquals(collection, CollectionLinks.parse(CollectionLinks.build(collection)))
    }

    @Test
    fun `bad keys are dropped and a nameless link is refused`() {
        assertEquals(
            VideoCollection("Picks", listOf("1_2")),
            CollectionLinks.parse("jplayer://collection?n=Picks&k=1_2,nope,1_2"),
        )
        assertNull(CollectionLinks.parse("jplayer://collection?k=1_2"))
        assertNull(CollectionLinks.parse("jplayer://collection?n=%zz&k=1_2"))
        assertNull(CollectionLinks.parse("jplayer://party?c=1&m=2"))
        assertNull(CollectionLinks.parse(""))
        assertEquals(VideoCollection("Empty", emptyList()), CollectionLinks.parse("jplayer://collection?n=Empty"))
    }
}
