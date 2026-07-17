// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * GHOST-8 matrix-rain live wallpaper with a single configurable HUD (one config,
 * no home/lock branching). Per WALLPAPER_SPEC.md: stateful falling columns,
 * per-glyph brightness, heavy negative space, per-glyph "shimmer".
 *
 * The HUD's NET line still respects the real lock state at runtime (IP only when
 * unlocked); in the wallpaper-picker preview (isPreview()) it shows the IP so the
 * preview reflects what you built.
 */
public class MatrixWallpaperService extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new MatrixEngine();
    }

    class MatrixEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Random rnd = new Random();
        private boolean visible;
        private int w, h, cols;
        private float cell;

        private float[] headY, speed;
        private int[] len;
        private char[][] cellGlyphs;
        private char[] latin, symbols;
        private long lastFrame;

        private Paint rain, hudText, panel;
        private Typeface vt;

        private SharedPreferences prefs;
        private KeyguardManager km;
        private ConnectivityManager netCm;
        private ConnectivityManager.NetworkCallback netCb;
        private boolean showHud = true, showHudLock = true;
        private float hudPos = 0.5f, hudX = 0.5f, hudScale = 1.0f;            // home
        private float hudPosLock = 0.5f, hudXLock = 0.5f, hudScaleLock = 1.0f; // lock
        private float shimmerP = 0.60f;   // rain shimmer is global (same on both screens)
        // Rain customization (global, not per-screen)
        private float rainSpeedMul = 1.0f;
        private int rainHue = 120;              // default green
        private float rainFontSizeMul = 1.0f;
        private boolean glyphKatakana = true, glyphDigits = true, glyphLatin = true, glyphSymbols = true;
        private int rainMinLen = 6, rainMaxLen = 32;
        private int frameDelay = 33;             // ms (~30fps)
        private final float[] hsvTemp = new float[3];

        private long lastStats = 0;
        private boolean lastLocked = false;
        private String[] hud = new String[0];
        private long cpuIdleLast = -1, cpuTotalLast = -1;
        private int cpuPercent = -1;

        private final Runnable frame = new Runnable() {
            public void run() {
                drawFrame();
                if (visible) handler.postDelayed(this, frameDelay);   // configurable fps
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            latin = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();   // no I/O (look like 1/0)
            symbols = "+=<>/\\|[]{}#$%&*".toCharArray();

            try { vt = Typeface.createFromAsset(getAssets(), "VT323-Regular.ttf"); }
            catch (Exception e) { vt = Typeface.MONOSPACE; }

            rain = new Paint(Paint.ANTI_ALIAS_FLAG);
            rain.setTypeface(Typeface.MONOSPACE);   // fallback covers katakana

            hudText = new Paint(Paint.ANTI_ALIAS_FLAG);
            hudText.setTypeface(vt);
            hudText.setColor(0xFF00FF66);

            panel = new Paint();
            panel.setColor(0xC8000A00);

            prefs = getSharedPreferences("matrix", Context.MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(this);
            readPrefs();
            km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            // Force an immediate stat refresh whenever the active network changes,
            // so the IP updates instantly on Wi-Fi <-> cellular switches.
            netCm = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netCm != null) {
                netCb = new ConnectivityManager.NetworkCallback() {
                    public void onAvailable(Network n) { lastStats = 0; }
                    public void onLost(Network n) { lastStats = 0; }
                };
                try { netCm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }
            }
        }

        private void readPrefs() {
            showHud = prefs.getBoolean("hud", true);
            showHudLock = prefs.getBoolean("hudLock", true);
            hudPos = prefs.getInt("hudPos", 50) / 100f;
            hudX = prefs.getInt("hudX", 50) / 100f;
            hudScale = prefs.getInt("hudScale", 100) / 100f;
            hudPosLock = prefs.getInt("hudPosLock", 50) / 100f;
            hudXLock = prefs.getInt("hudXLock", 50) / 100f;
            hudScaleLock = prefs.getInt("hudScaleLock", 100) / 100f;
            shimmerP = prefs.getInt("shimmer", 60) / 100f;   // 0..1 (100 = every glyph every frame)
            rainSpeedMul = prefs.getInt("rainSpeed", 100) / 100f;
            rainHue = Math.max(0, Math.min(360, prefs.getInt("rainHue", 120)));
            rainFontSizeMul = prefs.getInt("rainFontSize", 100) / 100f;
            glyphKatakana = prefs.getBoolean("glyphKatakana", true);
            glyphDigits = prefs.getBoolean("glyphDigits", true);
            glyphLatin = prefs.getBoolean("glyphLatin", true);
            glyphSymbols = prefs.getBoolean("glyphSymbols", true);
            int rawMin = Math.max(1, Math.min(50, prefs.getInt("rainMinLen", 6)));
            int rawMax = Math.max(1, Math.min(50, prefs.getInt("rainMaxLen", 32)));
            rainMinLen = Math.min(rawMin, rawMax);
            rainMaxLen = Math.max(rawMin, rawMax);
            frameDelay = Math.max(16, Math.min(100, Math.round(1000f / Math.max(10, Math.min(60, prefs.getInt("rainFps", 30))))));
        }

        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            readPrefs();
            if ("rainFontSize".equals(key) && w > 0 && h > 0) initColumns();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            w = width; h = height;
            initColumns();
        }
        private void initColumns() {
            cell = getResources().getDisplayMetrics().density * 13f * rainFontSizeMul;
            rain.setTextSize(cell * 0.92f);
            cols = Math.max(1, Math.round(w / cell));
            headY = new float[cols];
            speed = new float[cols];
            len = new int[cols];
            cellGlyphs = new char[cols][];
            for (int i = 0; i < cols; i++) respawn(i, true);
            lastFrame = 0;
        }

        private char randGlyph() {
            // Build weighted distribution from enabled glyph types
            int wKata = glyphKatakana ? 40 : 0;
            int wDig  = glyphDigits   ? 25 : 0;
            int wLat  = glyphLatin    ? 23 : 0;
            int wSym  = glyphSymbols  ? 12 : 0;
            int total = wKata + wDig + wLat + wSym;
            if (total == 0) return ' ';   // fallback — nothing enabled
            int r = rnd.nextInt(total);
            if (glyphKatakana) {
                if (r < wKata) return (char) (0x30A1 + rnd.nextInt(83));
                r -= wKata;
            }
            if (glyphDigits) {
                if (r < wDig) return (char) ('0' + rnd.nextInt(10));
                r -= wDig;
            }
            if (glyphLatin) {
                if (r < wLat) return latin[rnd.nextInt(latin.length)];
                r -= wLat;
            }
            return symbols[rnd.nextInt(symbols.length)];
        }

        private void respawn(int i, boolean initial) {
            len[i] = rainMinLen + rnd.nextInt(rainMaxLen - rainMinLen + 1);
            cellGlyphs[i] = new char[len[i]];
            for (int j = 0; j < len[i]; j++) cellGlyphs[i][j] = randGlyph();
            speed[i] = h * (0.12f + rnd.nextFloat() * 0.42f) * rainSpeedMul;
            if (initial) {
                headY[i] = rnd.nextFloat() * (h + len[i] * cell) - len[i] * cell;
            } else {
                headY[i] = -len[i] * cell;
                if (rnd.nextFloat() < 0.35f) headY[i] -= rnd.nextFloat() * h;
            }
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            handler.removeCallbacks(frame);
            if (v) { lastFrame = 0; lastStats = 0; handler.post(frame); }   // refresh stats on resume
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            handler.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacks(frame);
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
            if (netCm != null && netCb != null) {
                try { netCm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
            }
            super.onDestroy();
        }

        private void drawFrame() {
            long now = SystemClock.elapsedRealtime();
            float dt = (lastFrame == 0) ? 0.033f : Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;

            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                c.drawColor(0xFF000000);
                if (cellGlyphs != null) {
                    hsvTemp[0] = rainHue;
                    hsvTemp[1] = 0.30f;
                    hsvTemp[2] = 1.0f;
                    int leadColor = Color.HSVToColor(255, hsvTemp);
                    for (int i = 0; i < cols; i++) {
                        headY[i] += speed[i] * dt;                   // FALL
                        if (headY[i] - len[i] * cell > h) respawn(i, false);

                        float x = i * cell;
                        for (int j = 0; j < len[i]; j++) {
                            if (rnd.nextFloat() < shimmerP) cellGlyphs[i][j] = randGlyph();  // SHIMMER
                            float y = headY[i] - j * cell;
                            if (y < -cell || y > h + cell) continue;
                            if (j == 0) {
                                rain.setColor(leadColor);
                            } else {
                                float t = j / (float) Math.max(len[i] - 1, 1);
                                hsvTemp[1] = 1.0f;
                                hsvTemp[2] = (float) Math.pow(1 - t, 1.4);
                                if (hsvTemp[2] < 0.08f) hsvTemp[2] = 0.08f;
                                rain.setColor(Color.HSVToColor(255, hsvTemp));
                            }
                            c.drawText(String.valueOf(cellGlyphs[i][j]), x, y, rain);
                        }
                    }
                }
                boolean locked = !isPreview() && km != null && km.isKeyguardLocked();
                if (locked != lastLocked) { lastStats = 0; lastLocked = locked; }  // rebuild HUD instantly on (un)lock
                if (locked ? showHudLock : showHud) drawHud(c, locked);
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
        }

        private void drawHud(Canvas c, boolean locked) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastStats > 1000 || hud.length == 0) {
                hud = gatherStats(locked);
                lastStats = now;
            }
            int n = hud.length;
            if (n == 0) return;
            float uScale = locked ? hudScaleLock : hudScale;
            float uX = locked ? hudXLock : hudX;
            float uPos = locked ? hudPosLock : hudPos;
            float size = cell * 1.05f * uScale;
            hudText.setTextSize(size);
            hudText.setTextAlign(Paint.Align.CENTER);
            float lh = size * 1.35f;
            float panelW = 0;
            for (String s : hud) panelW = Math.max(panelW, hudText.measureText(s));
            float padX = size, padY = size * 0.6f;

            float half = panelW / 2 + padX;
            float cx = uX * w;
            if (cx < half) cx = half;
            if (cx > w - half) cx = w - half;

            float blockTop = uPos * h - (n * lh) / 2f;
            float minTop = padY, maxTop = h - n * lh - padY;
            if (blockTop < minTop) blockTop = minTop;
            if (maxTop > minTop && blockTop > maxTop) blockTop = maxTop;

            float firstBaseline = blockTop + lh * 0.8f;
            c.drawRect(cx - panelW / 2 - padX, blockTop - padY,
                    cx + panelW / 2 + padX, blockTop + n * lh + padY, panel);
            for (int i = 0; i < n; i++) c.drawText(hud[i], cx, firstBaseline + i * lh, hudText);
        }

        private static final String DEFAULT_ORDER = "title,ram,disk,bat,cpu,net,up";

        private String[] orderKeys() {
            List<String> keys = new ArrayList<String>();
            for (String k : prefs.getString("order", DEFAULT_ORDER).split(",")) {
                k = k.trim();
                if (k.length() > 0 && !keys.contains(k)) keys.add(k);
            }
            for (String k : DEFAULT_ORDER.split(",")) if (!keys.contains(k)) keys.add(k);
            return keys.toArray(new String[0]);
        }

        private String[] gatherStats(boolean locked) {
            String suf = locked ? "Lock" : "";
            List<String> L = new ArrayList<String>();
            for (String key : orderKeys()) {
                if (!elOn(key, locked)) continue;
                String line = lineFor(key, locked, suf);
                if (line != null) L.add(line);
            }
            return L.toArray(new String[0]);
        }

        private String lineFor(String key, boolean locked, String suf) {
            if (key.equals("title")) {
                String tt = prefs.getString("title" + suf, "KEEP//HUD");
                return tt.length() > 0 ? tt : null;
            }
            if (key.equals("ram")) {
                try {
                    long total, used;
                    long[] m = memInfo();   // {MemTotal, AnonPages} kB - matches Settings' used
                    if (m != null) {
                        total = m[0]; used = m[1];
                    } else {
                        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                        am.getMemoryInfo(mi);
                        total = mi.totalMem; used = mi.totalMem - mi.availMem;
                    }
                    int pct = (int) (used * 100 / Math.max(1, total));
                    return "RAM  " + bar(pct) + " " + pct + "%";
                } catch (Exception e) { return null; }
            }
            if (key.equals("disk")) {
                try {
                    StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
                    long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
                    int pct = (int) ((total - free) * 100 / Math.max(1, total));
                    return "DISK " + bar(pct) + " " + gib(total - free) + "/" + gib(total) + "G";
                } catch (Exception e) { return null; }
            }
            if (key.equals("bat")) {
                try {
                    Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (b != null) {
                        int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        boolean chg = status == BatteryManager.BATTERY_STATUS_CHARGING
                                || status == BatteryManager.BATTERY_STATUS_FULL;
                        int pct = (int) (lvl * 100 / Math.max(1, scale));
                        return "BAT  " + bar(pct) + " " + pct + "%" + (chg ? " +" : "");
                    }
                } catch (Exception e) { }
                return null;
            }
            if (key.equals("cpu")) return cpu();
            if (key.equals("net")) {
                if (locked && prefs.getBoolean("redactIp" + suf, true)) return "NET  [locked]";
                String ip = ip();
                return "NET  " + (ip != null ? ip : "--");
            }
            if (key.equals("up")) return "UP   " + uptime();
            return null;
        }

        private boolean elOn(String name, boolean locked) {
            return prefs.getBoolean("el_" + name + (locked ? "Lock" : ""), true);
        }

        private String bar(int pct) {
            int n = 8, f = Math.max(0, Math.min(n, (pct * n + 50) / 100));
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < n; i++) s.append(i < f ? '#' : '\u00B7');   // '#'=full, middle-dot=empty
            return s.append(']').toString();
        }

        private long gib(long bytes) { return bytes / (1024L * 1024L * 1024L); }

        /**
         * {MemTotal, usedKb} from /proc/meminfo. "Used" = AnonPages (anonymous app
         * memory), which matches the figure Android/GrapheneOS Settings reports as
         * used (cache + reclaimable count as free). Returns null if unreadable.
         */
        private long[] memInfo() {
            try {
                RandomAccessFile r = new RandomAccessFile("/proc/meminfo", "r");
                long total = -1, anon = -1;
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("MemTotal:")) total = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    else if (line.startsWith("AnonPages:")) anon = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    if (total >= 0 && anon >= 0) break;
                }
                r.close();
                if (total > 0 && anon >= 0) return new long[]{ total, anon };
            } catch (Exception ignored) { }
            return null;
        }

        private String uptime() {
            long s = SystemClock.elapsedRealtime() / 1000;
            long d = s / 86400; s %= 86400;
            long hh = s / 3600; s %= 3600;
            long mm = s / 60;
            if (d > 0) return d + "d " + String.format(Locale.US, "%02d:%02d", hh, mm);
            return String.format(Locale.US, "%02d:%02d", hh, mm);
        }

        private String ip() {
            // Examine ALL networks and prefer Wi-Fi by transport (cellular often stays
            // up after a Wi-Fi switch, and getActiveNetwork() can lag on cellular).
            String wV4 = null, wV6 = null, cV4 = null, cV6 = null, oV4 = null, oV6 = null;
            try {
                ConnectivityManager cm = (ConnectivityManager)
                        getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network n : cm.getAllNetworks()) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                        LinkProperties lp = cm.getLinkProperties(n);
                        if (nc == null || lp == null) continue;
                        // Skip VPNs (Tailscale etc.) - their network reports the underlying
                        // transport, so its tunnel IP was masquerading as the Wi-Fi IP.
                        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                        boolean wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                                || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                        boolean cell = nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress a = la.getAddress();
                            if (a == null || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                            String host = a.getHostAddress();
                            if (host == null) continue;
                            boolean v6 = host.indexOf(':') >= 0;
                            if (v6) host = prettyV6(host);
                            if (wifi)      { if (v6) { if (wV6 == null) wV6 = host; } else if (wV4 == null) wV4 = host; }
                            else if (cell) { if (v6) { if (cV6 == null) cV6 = host; } else if (cV4 == null) cV4 = host; }
                            else           { if (v6) { if (oV6 == null) oV6 = host; } else if (oV4 == null) oV4 = host; }
                        }
                    }
                }
            } catch (Exception ignored) { }
            if (wV4 != null) return wV4;
            if (oV4 != null) return oV4;
            if (cV4 != null) return cV4;
            if (wV6 != null) return wV6;
            if (oV6 != null) return oV6;
            if (cV6 != null) return cV6;

            // Fallback: enumerate interfaces directly.
            String wifi = null, other = null, v6 = null;
            try {
                Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
                while (ifs != null && ifs.hasMoreElements()) {
                    NetworkInterface ni = ifs.nextElement();
                    try {
                        if (ni.isLoopback() || !ni.isUp()) continue;
                    } catch (Exception e) { continue; }
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                        String host = a.getHostAddress();
                        if (host == null) continue;
                        if (host.indexOf(':') >= 0) {           // IPv6 (globally routable off Wi-Fi)
                            if (v6 == null) v6 = prettyV6(host);
                            continue;
                        }
                        String nm = ni.getName();
                        if (nm != null && nm.startsWith("wlan")) wifi = host;   // prefer Wi-Fi LAN
                        else if (other == null) other = host;
                    }
                }
            } catch (Exception ignored) { }
            if (wifi != null) return wifi;
            if (other != null) return other;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int v = wm.getConnectionInfo().getIpAddress();
                    if (v != 0) return (v & 0xff) + "." + ((v >> 8) & 0xff) + "."
                            + ((v >> 16) & 0xff) + "." + ((v >> 24) & 0xff);
                }
            } catch (Exception ignored) { }
            return v6;   // IPv6 fallback (e.g. IPv6-only cellular)
        }

        /** Compact IPv6 for the HUD: first two groups + ".." + last two (e.g. 2607:fb90:..:5678:9abc). */
        private String prettyV6(String raw) {
            try {
                String[] g = expandV6(raw.split("%")[0]);
                if (g == null) return raw;
                return g[0] + ":" + g[1] + ":..:" + g[6] + ":" + g[7];
            } catch (Exception e) { return raw; }
        }

        /** Expand an IPv6 (handling ::) to 8 leading-zero-stripped hex groups, or null. */
        private String[] expandV6(String h) {
            try {
                List<String> groups = new ArrayList<String>();
                int dc = h.indexOf("::");
                if (dc >= 0) {
                    String left = h.substring(0, dc), right = h.substring(dc + 2);
                    String[] l = left.isEmpty() ? new String[0] : left.split(":");
                    String[] r = right.isEmpty() ? new String[0] : right.split(":");
                    for (String s : l) groups.add(s);
                    for (int i = 0, z = 8 - l.length - r.length; i < z; i++) groups.add("0");
                    for (String s : r) groups.add(s);
                } else {
                    for (String s : h.split(":")) groups.add(s);
                }
                if (groups.size() != 8) return null;
                String[] out = new String[8];
                for (int i = 0; i < 8; i++) out[i] = Integer.toHexString(Integer.parseInt(groups.get(i), 16) & 0xffff);
                return out;
            } catch (Exception e) { return null; }
        }

        private String cpu() {
            try {
                RandomAccessFile r = new RandomAccessFile("/proc/stat", "r");
                String line = r.readLine();
                r.close();
                if (line != null && line.startsWith("cpu")) {
                    String[] t = line.trim().split("\\s+");
                    long idle = Long.parseLong(t[4]);
                    long total = 0;
                    for (int i = 1; i < t.length; i++) total += Long.parseLong(t[i]);
                    if (cpuTotalLast >= 0) {
                        long dt = total - cpuTotalLast, di = idle - cpuIdleLast;
                        if (dt > 0) cpuPercent = (int) ((dt - di) * 100 / dt);
                    }
                    cpuTotalLast = total;
                    cpuIdleLast = idle;
                    if (cpuPercent >= 0) return "CPU  " + bar(cpuPercent) + " " + cpuPercent + "%";
                }
            } catch (Exception ignored) { }
            return "CPU  " + Runtime.getRuntime().availableProcessors() + " cores";
        }
    }
}
