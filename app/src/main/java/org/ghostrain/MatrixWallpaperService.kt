// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * GHOST-8 matrix-rain live wallpaper with a single configurable HUD (one config,
 * no home/lock branching). Per WALLPAPER_SPEC.md: stateful falling columns,
 * per-glyph brightness, heavy negative space, per-glyph "shimmer".
 *
 * The HUD's NET line still respects the real lock state at runtime (IP only when
 * unlocked); in the wallpaper-picker preview (isPreview()) it shows the IP so the
 * preview reflects what you built.
 */
private const val DEFAULT_ORDER = "title,ram,disk,bat,cpu,net,up"

class MatrixWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MatrixEngine()

    inner class MatrixEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var w = 0
        private var h = 0
        private var lastFrame = 0L

        private lateinit var hudText: Paint
        private lateinit var panel: Paint
        private lateinit var vt: Typeface

        private lateinit var prefs: SharedPreferences
        private lateinit var km: KeyguardManager
        private var netCm: ConnectivityManager? = null
        private var netCb: ConnectivityManager.NetworkCallback? = null
        private var showHud = true
        private var showHudLock = true
        private var hudPos = 0.5f
        private var hudX = 0.5f
        private var hudScale = 1.0f            // home
        private var hudPosLock = 0.5f
        private var hudXLock = 0.5f
        private var hudScaleLock = 1.0f        // lock
        private lateinit var rainRenderer: RainRenderer
        private lateinit var rainSettings: RainSettings

        private var lastStats = 0L
        private var lastLocked = false
        private var hud = emptyArray<String>()
        private var cpuIdleLast = -1L
        private var cpuTotalLast = -1L
        private var cpuPercent = -1

        private val frame: Runnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, rainSettings.frameDelayMs.toLong())
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            rainRenderer = RainRenderer(resources.displayMetrics.density)

            vt = try {
                Typeface.createFromAsset(assets, "VT323-Regular.ttf")
            } catch (_: Exception) {
                Typeface.MONOSPACE
            }

            hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = vt
                color = 0xFF00FF66.toInt()
            }

            panel = Paint().apply {
                color = 0xC8000A00.toInt()
            }

            prefs = getSharedPreferences("matrix", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
            readPrefs()
            km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            // Force an immediate stat refresh whenever the active network changes,
            // so the IP updates instantly on Wi-Fi <-> cellular switches.
            netCm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (netCm != null) {
                netCb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { lastStats = 0 }
                    override fun onLost(n: Network) { lastStats = 0 }
                }
                try { netCm!!.registerDefaultNetworkCallback(netCb!!) } catch (_: Exception) { }
            }
        }

        private fun readPrefs() {
            showHud = prefs.getBoolean("hud", true)
            showHudLock = prefs.getBoolean("hudLock", true)
            hudPos = prefs.getInt("hudPos", 50) / 100f
            hudX = prefs.getInt("hudX", 50) / 100f
            hudScale = prefs.getInt("hudScale", 100) / 100f
            hudPosLock = prefs.getInt("hudPosLock", 50) / 100f
            hudXLock = prefs.getInt("hudXLock", 50) / 100f
            hudScaleLock = prefs.getInt("hudScaleLock", 100) / 100f
            rainSettings = RainSettings.fromPrefs(prefs)
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            readPrefs()
            if ("rainFontSize" == key && w > 0 && h > 0) rainRenderer.resize(w, h, rainSettings)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width
            h = height
            rainRenderer.resize(w, h, rainSettings)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame)
            if (v) {
                lastFrame = 0
                lastStats = 0
                handler.post(frame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
            netCm?.let { cm ->
                netCb?.let { cb ->
                    try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
                }
            }
            super.onDestroy()
        }

        private fun drawFrame() {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrame == 0L) 0.033f else minOf(0.1f, (now - lastFrame) / 1000f)
            lastFrame = now

            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas()
                if (c == null) return
                c.drawColor(0xFF000000.toInt())
                rainRenderer.draw(c, dt, rainSettings)
                val locked = !isPreview && km.isKeyguardLocked()
                if (locked != lastLocked) {
                    lastStats = 0
                    lastLocked = locked
                }  // rebuild HUD instantly on (un)lock
                if (if (locked) showHudLock else showHud) drawHud(c, locked)
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c)
            }
        }

        private fun drawHud(c: Canvas, locked: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStats > 1000 || hud.isEmpty()) {
                hud = gatherStats(locked)
                lastStats = now
            }
            val n = hud.size
            if (n == 0) return
            val uScale = if (locked) hudScaleLock else hudScale
            val uX = if (locked) hudXLock else hudX
            val uPos = if (locked) hudPosLock else hudPos
            val size = rainRenderer.cell * 1.05f * uScale
            hudText.textSize = size
            hudText.textAlign = Paint.Align.CENTER
            val lh = size * 1.35f
            var panelW = 0f
            for (s in hud) panelW = maxOf(panelW, hudText.measureText(s))
            val padX = size
            val padY = size * 0.6f

            val half = panelW / 2 + padX
            var cx = uX * w
            if (cx < half) cx = half
            if (cx > w - half) cx = w - half

            var blockTop = uPos * h - (n * lh) / 2f
            val minTop = padY
            val maxTop = h - n * lh - padY
            if (blockTop < minTop) blockTop = minTop
            if (maxTop > minTop && blockTop > maxTop) blockTop = maxTop

            val firstBaseline = blockTop + lh * 0.8f
            c.drawRect(cx - panelW / 2 - padX, blockTop - padY,
                    cx + panelW / 2 + padX, blockTop + n * lh + padY, panel)
            for (i in 0 until n) c.drawText(hud[i], cx, firstBaseline + i * lh, hudText)
        }


        private fun orderKeys(): Array<String> {
            val keys = mutableListOf<String>()
            for (k in prefs.getString("order", DEFAULT_ORDER)!!.split(",")) {
                val tk = k.trim()
                if (tk.isNotEmpty() && tk !in keys) keys.add(tk)
            }
            for (k in DEFAULT_ORDER.split(",")) if (k !in keys) keys.add(k)
            return keys.toTypedArray()
        }

        private fun gatherStats(locked: Boolean): Array<String> {
            val suf = if (locked) "Lock" else ""
            val l = mutableListOf<String>()
            for (key in orderKeys()) {
                if (!elOn(key, locked)) continue
                val line = lineFor(key, locked, suf)
                if (line != null) l.add(line)
            }
            return l.toTypedArray()
        }

        private fun lineFor(key: String, locked: Boolean, suf: String): String? {
            return when (key) {
                "title" -> {
                    val tt = prefs.getString("title$suf", "KEEP//HUD")
                    if (tt!!.isNotEmpty()) tt else null
                }
                "ram" -> {
                    try {
                        val total: Long
                        val used: Long
                        val m = memInfo()   // {MemTotal, AnonPages} kB - matches Settings' used
                        if (m != null) {
                            total = m[0]
                            used = m[1]
                        } else {
                            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                            val mi = ActivityManager.MemoryInfo()
                            am.getMemoryInfo(mi)
                            total = mi.totalMem
                            used = mi.totalMem - mi.availMem
                        }
                        val pct = (used * 100 / maxOf(1L, total)).toInt()
                        "RAM  ${bar(pct)} $pct%"
                    } catch (_: Exception) { null }
                }
                "disk" -> {
                    try {
                        val fs = StatFs(Environment.getDataDirectory().path)
                        val total = fs.totalBytes
                        val free = fs.availableBytes
                        val pct = ((total - free) * 100 / maxOf(1L, total)).toInt()
                        "DISK ${bar(pct)} ${gib(total - free)}/${gib(total)}G"
                    } catch (_: Exception) { null }
                }
                "bat" -> {
                    try {
                        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        if (b != null) {
                            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                            val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                            val chg = status == BatteryManager.BATTERY_STATUS_CHARGING
                                    || status == BatteryManager.BATTERY_STATUS_FULL
                            val pct = (lvl * 100 / maxOf(1, scale)).toInt()
                            "BAT  ${bar(pct)} $pct%" + if (chg) " +" else ""
                        } else null
                    } catch (_: Exception) { null }
                }
                "cpu" -> cpu()
                "net" -> {
                    if (locked && prefs.getBoolean("redactIp$suf", true)) "NET  [locked]"
                    else {
                        val ip = ip()
                        "NET  ${ip ?: "--"}"
                    }
                }
                "up" -> "UP   ${uptime()}"
                else -> null
            }
        }

        private fun elOn(name: String, locked: Boolean): Boolean {
            return prefs.getBoolean("el_$name" + if (locked) "Lock" else "", true)
        }

        private fun bar(pct: Int): String {
            val n = 8
            val f = maxOf(0, minOf(n, (pct * n + 50) / 100))
            val s = StringBuilder("[")
            for (i in 0 until n) s.append(if (i < f) '#' else '\u00B7')   // '#'=full, middle-dot=empty
            return s.append(']').toString()
        }

        private fun gib(bytes: Long): Long = bytes / (1024L * 1024L * 1024L)

        /**
         * Returns {MemTotal, usedKb} from /proc/meminfo. "Used" = AnonPages (anonymous app
         * memory), which matches the figure Android/GrapheneOS Settings reports as
         * used (cache + reclaimable count as free). Returns null if unreadable.
         */
        private fun memInfo(): LongArray? {
            try {
                RandomAccessFile("/proc/meminfo", "r").use { r ->
                    var total = -1L
                    var anon = -1L
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        val l = line!!
                        if (l.startsWith("MemTotal:")) total = l.replace(Regex("[^0-9]"), "").toLong()
                        else if (l.startsWith("AnonPages:")) anon = l.replace(Regex("[^0-9]"), "").toLong()
                        if (total >= 0 && anon >= 0) break
                    }
                    if (total > 0 && anon >= 0) return longArrayOf(total, anon)
                }
            } catch (_: Exception) { }
            return null
        }

        private fun uptime(): String {
            val s = SystemClock.elapsedRealtime() / 1000
            val d = s / 86400
            var rem = s % 86400
            val hh = rem / 3600
            rem %= 3600
            val mm = rem / 60
            return if (d > 0) "${d}d " + String.format(Locale.US, "%02d:%02d", hh, mm)
            else String.format(Locale.US, "%02d:%02d", hh, mm)
        }

        private fun ip(): String? {
            // Examine ALL networks and prefer Wi-Fi by transport (cellular often stays
            // up after a Wi-Fi switch, and getActiveNetwork() can lag on cellular).
            var wV4: String? = null
            var wV6: String? = null
            var cV4: String? = null
            var cV6: String? = null
            var oV4: String? = null
            var oV6: String? = null
            try {
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    for (n in cm.allNetworks) {
                        val nc = cm.getNetworkCapabilities(n) ?: continue
                        val lp = cm.getLinkProperties(n) ?: continue
                        // Skip VPNs (Tailscale etc.) - their network reports the underlying
                        // transport, so its tunnel IP was masquerading as the Wi-Fi IP.
                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                        val wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        val cell = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        for (la in lp.linkAddresses) {
                            val a = la.address ?: continue
                            if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                            var host = a.hostAddress ?: continue
                            val v6 = host.indexOf(':') >= 0
                            if (v6) host = prettyV6(host)
                            when {
                                wifi -> if (v6) { if (wV6 == null) wV6 = host } else if (wV4 == null) wV4 = host
                                cell -> if (v6) { if (cV6 == null) cV6 = host } else if (cV4 == null) cV4 = host
                                else -> if (v6) { if (oV6 == null) oV6 = host } else if (oV4 == null) oV4 = host
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
            // Priority chain
            return wV4 ?: oV4 ?: cV4 ?: wV6 ?: oV6 ?: cV6 ?: fallbackIp()
        }

        /** Fallback IP detection: enumerate interfaces directly, then try WifiManager. */
        private fun fallbackIp(): String? {
            var wifi: String? = null
            var other: String? = null
            var v6: String? = null
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()
                while (ifs != null && ifs.hasMoreElements()) {
                    val ni = ifs.nextElement()
                    try { if (ni.isLoopback || !ni.isUp) continue } catch (_: Exception) { continue }
                    val addrs = ni.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                        var host = a.hostAddress ?: continue
                        if (host.indexOf(':') >= 0) {           // IPv6 (globally routable off Wi-Fi)
                            if (v6 == null) v6 = prettyV6(host)
                            continue
                        }
                        val nm = ni.name
                        if (nm != null && nm.startsWith("wlan")) wifi = host   // prefer Wi-Fi LAN
                        else if (other == null) other = host
                    }
                }
            } catch (_: Exception) { }
            if (wifi != null) return wifi
            if (other != null) return other
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    val v = wm.connectionInfo.ipAddress
                    if (v != 0) return "${v and 0xff}.${(v shr 8) and 0xff}.${(v shr 16) and 0xff}.${(v shr 24) and 0xff}"
                }
            } catch (_: Exception) { }
            return v6   // IPv6 fallback (e.g. IPv6-only cellular)
        }

        /** Compact IPv6 for the HUD: first two groups + ".." + last two (e.g. 2607:fb90:..:5678:9abc). */
        private fun prettyV6(raw: String): String {
            return try {
                val g = expandV6(raw.split("%")[0])
                if (g != null) "${g[0]}:${g[1]}:..:${g[6]}:${g[7]}"
                else raw
            } catch (_: Exception) { raw }
        }

        /** Expand an IPv6 (handling ::) to 8 leading-zero-stripped hex groups, or null. */
        private fun expandV6(h: String): Array<String>? {
            return try {
                val groups = mutableListOf<String>()
                val dc = h.indexOf("::")
                if (dc >= 0) {
                    val left = h.substring(0, dc)
                    val right = h.substring(dc + 2)
                    val l = if (left.isEmpty()) emptyArray() else left.split(":").toTypedArray()
                    val r = if (right.isEmpty()) emptyArray() else right.split(":").toTypedArray()
                    groups.addAll(l)
                    for (z in 0 until 8 - l.size - r.size) groups.add("0")
                    groups.addAll(r)
                } else {
                    groups.addAll(h.split(":"))
                }
                if (groups.size != 8) return null
                Array(8) { i -> Integer.toHexString(Integer.parseInt(groups[i], 16) and 0xffff) }
            } catch (_: Exception) { null }
        }

        private fun cpu(): String {
            try {
                RandomAccessFile("/proc/stat", "r").use { r ->
                    val line = r.readLine()
                    if (line != null && line.startsWith("cpu")) {
                        val t = line.trim().split("\\s+".toRegex())
                        val idle = t[4].toLong()
                        var total = 0L
                        for (i in 1 until t.size) total += t[i].toLong()
                        if (cpuTotalLast >= 0) {
                            val dt = total - cpuTotalLast
                            val di = idle - cpuIdleLast
                            if (dt > 0) cpuPercent = ((dt - di) * 100 / dt).toInt()
                        }
                        cpuTotalLast = total
                        cpuIdleLast = idle
                        if (cpuPercent >= 0) return "CPU  ${bar(cpuPercent)} $cpuPercent%"
                    }
                }
            } catch (_: Exception) { }
            return "CPU  ${Runtime.getRuntime().availableProcessors()} cores"
        }
    }
}
