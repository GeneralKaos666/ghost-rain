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
}
