// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

/**
 * Single source of HUD pref-key conventions and element-order logic, shared by
 * the wallpaper engine, the Settings screen, and (Tasks 4-5) the Compose UI.
 *
 * Pure Kotlin with no Android dependency so it runs on the JVM unit-test
 * source set. Previously these rules were duplicated between
 * [MatrixWallpaperService] (engine) and `SettingsActivity`.
 */
object HudPrefs {

    /** Canonical HUD element order; also the fallback when no stored order exists. */
    const val DEFAULT_ORDER = "title,ram,disk,bat,cpu,net,up"

    /** The canonical element keys, in [DEFAULT_ORDER] sequence. */
    val defaultOrder: List<String> = DEFAULT_ORDER.split(",")

    /**
     * Per-screen pref key for [base]: the LOCK variant is the base key plus a
     * `Lock` suffix (e.g. `hud`/`hudLock`, `el_ram`/`el_ramLock`). Pass
     * `editingLock = true` when reading/writing the lock-screen config.
     */
    fun keyOf(base: String, editingLock: Boolean): String =
            if (editingLock) base + "Lock" else base

    /**
     * Merge a stored `order` CSV with [DEFAULT_ORDER]: stored known keys first
     * (trimmed, empties skipped, duplicates collapsed), then any canonical keys
     * missing from the stored value. Unknown keys are dropped — the engine has
     * no line renderer for them (its `lineFor ... else -> null` skips them, so
     * engine output is unchanged), and keeping them would only leave phantom
     * rows in the reorder UI.
     */
    fun orderKeys(orderRaw: String?): List<String> {
        val keys = mutableListOf<String>()
        orderRaw?.split(",")?.forEach { k ->
            val tk = k.trim()
            if (tk.isNotEmpty() && tk in defaultOrder && tk !in keys) keys.add(tk)
        }
        for (k in defaultOrder) if (k !in keys) keys.add(k)
        return keys
    }

    /**
     * Persist a reordered display list without losing stored unknown tokens:
     * [displayed] (built from [orderKeys], which drops unknowns) is kept in
     * order, then any stored tokens that are neither canonical nor already in
     * the displayed list are appended verbatim (trimmed, deduped) in stored
     * order. A reorder-save must never delete tokens the UI cannot show.
     */
    fun mergeOrderOnSave(displayed: List<String>, storedRaw: String?): String {
        val out = displayed.toMutableList()
        storedRaw?.split(",")?.forEach { k ->
            val tk = k.trim()
            if (tk.isNotEmpty() && tk !in defaultOrder && tk !in out) out.add(tk)
        }
        return out.joinToString(",")
    }
}

/**
 * HUD line helpers. Pure Kotlin; see [HudPrefs] for why this lives outside the
 * Android classes.
 */
object HudLines {

    /**
     * The title line, or null when there is nothing to show. Blank (empty or
     * whitespace-only) titles produce no line.
     */
    fun titleOrNull(raw: String?): String? =
            if (raw.isNullOrBlank()) null else raw

    /**
     * The NET line: `[locked]` on the real lock screen when redaction is on,
     * otherwise the IP (or `--` when none was detected).
     *
     * Detail overload (Phase 3): appends the transport label (`Wi-Fi` /
     * `cell` / `eth`), plus the Wi-Fi SSID and `level/4` signal when the
     * SSID is actually known. A null/blank transport returns output
     * byte-identical to the plain overload; an unknown SSID (modern Android
     * returns `<unknown ssid>` without location permission — which this
     * app deliberately does not request) falls back to the transport label
     * only. Redaction still wins over all detail.
     */
    fun netLine(ip: String?, locked: Boolean, redact: Boolean): String =
            netLine(ip, locked, redact, transport = null, ssid = null, signalLevel = -1)

    fun netLine(
            ip: String?,
            locked: Boolean,
            redact: Boolean,
            transport: String?,
            ssid: String?,
            signalLevel: Int
    ): String {
        if (locked && redact) return "NET  [locked]"
        val base = "NET  ${ip ?: "--"}"
        val t = transport?.takeIf { it.isNotBlank() } ?: return base
        val sb = StringBuilder(base).append(' ').append(t)
        val clean = cleanSsid(ssid) ?: return sb.toString()
        sb.append(' ').append(clean)
        if (signalLevel in 0..4) sb.append(' ').append(signalLevel).append("/4")
        return sb.toString()
    }

    /**
     * Normalize a `WifiInfo.ssid` for the HUD: null, blank, `<unknown
     * ssid>`, and empty-after-unquoting all mean "SSID unavailable" (null).
     * Older APIs quote the SSID (`"Home"`); the quotes are stripped since
     * the HUD delimits fields with spaces, not quotes.
     */
    fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.equals("<unknown ssid>", ignoreCase = true)) return null
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.isEmpty() || s.equals("<unknown ssid>", ignoreCase = true)) return null
        return s
    }
}
