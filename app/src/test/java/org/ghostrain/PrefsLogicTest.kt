package org.ghostrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsLogicTest {
    @Test fun corruptedLayoutsLoadAsEmpty() {
        assertEquals(0, LayoutsRepo.load("not-json").size)
    }

    @Test fun orderMergesUnknownsAndDedupes() {
        assertEquals(
            listOf("net", "title", "ram", "disk", "bat", "cpu", "up"),
            HudPrefs.orderKeys("net,,net,bogus")
        )
    }

    @Test fun rainLenClampSwapsMinMax() {
        val s = RainSettings.fromMap(mapOf("rainMinLen" to 40, "rainMaxLen" to 6))
        assertTrue(s.minLen <= s.maxLen)
    }
}
