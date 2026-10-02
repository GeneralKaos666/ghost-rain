package org.ghostrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun emptyTitleHidesLine() { assertNull( HudLines.titleOrNull("") ) }

    @Test fun blankTitleHidesLine() { assertNull(HudLines.titleOrNull("   ")) }

    @Test fun nonBlankTitleShows() { assertEquals("HI", HudLines.titleOrNull("HI")) }

    @Test fun redactLockedHidesIp() {
        assertEquals("NET  [locked]", HudLines.netLine("1.2.3.4", locked = true, redact = true))
    }

    @Test fun redactUnlockedShowsIp() {
        assertEquals("NET  1.2.3.4", HudLines.netLine("1.2.3.4", locked = false, redact = true))
    }

    @Test fun redactDisabledShowsIpOnLock() {
        assertEquals("NET  1.2.3.4", HudLines.netLine("1.2.3.4", locked = true, redact = false))
    }

    @Test fun reorderSavePreservesUnknowns() {
        assertEquals(
            "title,net,ram,disk,bat,cpu,up,bogus",
            HudPrefs.mergeOrderOnSave(
                listOf("title", "net", "ram", "disk", "bat", "cpu", "up"),
                "net,bogus"
            )
        )
    }
}
