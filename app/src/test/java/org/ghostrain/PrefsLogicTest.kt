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

    // Phase 3 (Task 9): one-time SharedPreferences -> DataStore migration
    // preserves every key/value, drops nothing it supports.

    @Test fun migrationPreservesEveryKeyAndValue() {
        val all = mapOf<String, Any?>(
            "hud" to true,
            "hudLock" to false,
            "hudX" to 50,
            "hudPosLock" to 25,
            "hudScale" to 100,
            "hudDynamic" to true,
            "hudDynamicLock" to false,
            "el_ram" to true,
            "el_netLock" to false,
            "redactIp" to true,
            "title" to "KEEP//HUD",
            "titleLock" to "LOCKED",
            "order" to "net,title,ram,disk,bat,cpu,up",
            "layouts" to "[]",
            "rainSpeed" to 100,
            "rainHue" to 120,
            "rainFontSize" to 100,
            "glyphKatakana" to true,
            "rainMinLen" to 6,
            "rainMaxLen" to 32,
            "rainFps" to 30,
            "shimmer" to 60,
            "ui_open_screen" to true,
            "ui_open_hud" to false,
            "ui_open_rain" to false,
            "lastAppliedVersion" to 23,
            "updatePromptDismissed" to -1,
            "someFloat" to 1.5f,
            "someLong" to 9L,
            "someSet" to setOf("a", "b"),
            "futureUnknown" to "kept-verbatim"
        )
        assertEquals(all, MatrixDataStore.migratableEntries(all))
    }

    @Test fun migrationDropsOnlyUnsupportedTypes() {
        val out = MatrixDataStore.migratableEntries(
            mapOf("bad" to 1.5, "alsoBad" to setOf(1, 2), "ok" to 1)
        )
        assertEquals(mapOf("ok" to 1), out)
    }

    // Phase 3 (Task 9): "Match system color" is opt-in, default OFF, and
    // toggle-OFF output is byte-identical to previous builds.

    @Test fun dynamicHudDefaultsOff() {
        assertEquals(false, MatrixDataStore.HUD_DYNAMIC_DEFAULT)
    }

    @Test fun dynamicOffKeepsLegacyColors() {
        val c = MatrixDataStore.hudColors(
            dynamic = false,
            systemText = 0xFF123456.toInt(),
            systemPanel = 0xFF654321.toInt()
        )
        assertEquals(0xFF00FF66.toInt(), c.text)
        assertEquals(0xC8000A00.toInt(), c.panel)
    }

    @Test fun dynamicOnUsesSystemPalette() {
        val c = MatrixDataStore.hudColors(
            dynamic = true,
            systemText = 0xFF123456.toInt(),
            systemPanel = 0xFF654321.toInt()
        )
        assertEquals(0xFF123456.toInt(), c.text)
        assertEquals(0xFF654321.toInt(), c.panel)
    }

    // Phase 3 (Task 9): extended NET line; no-detail output unchanged.

    @Test fun netLineWithoutDetailIsByteIdentical() {
        assertEquals(
            HudLines.netLine("1.2.3.4", locked = false, redact = true),
            HudLines.netLine("1.2.3.4", locked = false, redact = true,
                transport = null, ssid = null, signalLevel = -1)
        )
        assertEquals(
            HudLines.netLine(null, locked = false, redact = false),
            HudLines.netLine(null, locked = false, redact = false,
                transport = null, ssid = null, signalLevel = -1)
        )
    }

    @Test fun netLineFullDetail() {
        assertEquals(
            "NET  1.2.3.4 Wi-Fi Home 4/4",
            HudLines.netLine("1.2.3.4", locked = false, redact = false,
                transport = "Wi-Fi", ssid = "\"Home\"", signalLevel = 4)
        )
    }

    @Test fun netLineUnknownSsidFallsBackToTransport() {
        assertEquals(
            "NET  1.2.3.4 Wi-Fi",
            HudLines.netLine("1.2.3.4", locked = false, redact = false,
                transport = "Wi-Fi", ssid = "<unknown ssid>", signalLevel = 4)
        )
        assertEquals(
            "NET  10.0.0.1 cell",
            HudLines.netLine("10.0.0.1", locked = false, redact = false,
                transport = "cell", ssid = null, signalLevel = -1)
        )
    }

    @Test fun netLineRedactStillWinsWithDetail() {
        assertEquals(
            "NET  [locked]",
            HudLines.netLine("1.2.3.4", locked = true, redact = true,
                transport = "Wi-Fi", ssid = "Home", signalLevel = 4)
        )
    }

    @Test fun cleanSsidNormalizes() {
        assertEquals("Home", HudLines.cleanSsid("\"Home\""))
        assertEquals("Home", HudLines.cleanSsid("Home"))
        assertEquals(null, HudLines.cleanSsid("<unknown ssid>"))
        assertEquals(null, HudLines.cleanSsid(null))
        assertEquals(null, HudLines.cleanSsid("   "))
    }
}
