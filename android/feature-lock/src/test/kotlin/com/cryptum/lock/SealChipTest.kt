package com.cryptum.lock

import org.junit.Assert.assertEquals
import org.junit.Test

class SealChipTest {

    @Test
    fun `hashGlyph is deterministic across calls`() {
        val first = hashGlyph("itm-001")
        val second = hashGlyph("itm-001")

        assertEquals(first, second)
    }

    @Test
    fun `hashGlyph is stable for a known id`() {
        val (a, b) = hashGlyph("itm-001")

        assertEquals(2, a.length)
        assertEquals(2, b.length)
        assertEquals(hashGlyph("itm-001"), a to b)
    }
}
