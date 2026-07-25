// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
private const val BAR_HEIGHT_DP = 18


/**
 * Per-screen HUD configs (HOME vs LOCK) chosen by a target toggle, plus named
 * screen-agnostic layouts you can apply to whichever screen you're editing.
 * The wallpaper engine renders the HOME config when unlocked, LOCK when locked.
 * Glyph shimmer (the rain) is global. A live preview shows the edited screen.
 */
class SettingsActivity : Activity() {

    companion object {

        // Screen-agnostic config keys (a layout = a snapshot of these for one screen).
        private val L_BOOL = arrayOf(
                "hud", "el_title", "el_ram", "el_disk", "el_bat", "el_cpu", "el_net", "el_up", "redactIp"
        )
        private val L_INT = arrayOf("hudX", "hudPos", "hudScale")
        private val L_INT_DEF = intArrayOf(50, 50, 100)
        private const val DEF_ORDER = "title,ram,disk,bat,cpu,net,up"
    }

    private lateinit var p: SharedPreferences
    private lateinit var preview: PreviewView
    private lateinit var elementsBox: LinearLayout
    private lateinit var layoutBtn: Button
    private lateinit var btnHome: Button
    private lateinit var btnLock: Button
    private var currentLayout: String? = null
    private var editingLock = false
    private var suppressRedact = false
    private val refreshers = mutableListOf<Runnable>()

    private fun keyOf(base: String, targetAware: Boolean): String {
        return if (targetAware && editingLock) base + "Lock" else base
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        p = getSharedPreferences("matrix", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "GHOST RAIN // SETTINGS"
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title, lp())

        val banner = TextView(this).apply {
            text = "SETUP - DO THIS NOW, AND AFTER EVERY APP UPDATE:\n" +
                    "  1.  Tap  > SET GHOST RAIN WALLPAPER  below\n" +
                    "  2.  Choose  BOTH  (home + lock screen)\n" +
                    "The wallpaper will NOT change/update until you re-set it.\n\n" +
                    "Tip: if the lock-screen clock covers the HUD, set the lock clock size to Small " +
                    "(in your phone's lock screen / wallpaper settings)."
            setTextColor(0xFFFFD24D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(0xFF241B00.toInt())
            val bp = dp(12)
            setPadding(bp, bp, bp, bp)
        }
        root.addView(banner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        val set = Button(this).apply {
            text = "> SET GHOST RAIN WALLPAPER"
            isAllCaps = false
            setOnClickListener { setWallpaper() }
        }
        root.addView(set, lp())

        // --- Editing target (HOME / LOCK) ---
        val et = TextView(this).apply {
            text = "Editing screen:"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(et, lp())

        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            btnHome = Button(this@SettingsActivity).apply {
                text = "HOME"
                isAllCaps = false
                setOnClickListener { setTarget(false) }
            }
            btnLock = Button(this@SettingsActivity).apply {
                text = "LOCK"
                isAllCaps = false
                setOnClickListener { setTarget(true) }
            }
            addView(btnHome, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnLock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(toggle, lp())

        // --- Preview ---
        preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            topMargin = dp(10)
            bottomMargin = dp(4)
        })

        // --- Layouts (apply to the screen being edited) ---
        val ll = TextView(this).apply {
            text = "Layouts (apply to edited screen)"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(ll, lp())

        layoutBtn = Button(this).apply {
            isAllCaps = false
            setOnClickListener { showLayoutPicker() }
        }
        root.addView(layoutBtn, lp())

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addNav(this, "New") { newLayout() }
            addNav(this, "Save") { saveDialog() }
            addNav(this, "Delete") { deleteCurrent() }
        }
        root.addView(btnRow, lp())

        // --- Config controls (per-screen unless noted) ---
        addCheck(root, "Show HUD overlay", "hud", true, 18)
        addSlider(root, "Glyph shimmer (both screens)", "shimmer", 0, 100, 60, "%", false)
        // --- Rain customization (global, not per-screen) ---
        val rv = TextView(this).apply {
            text = "RAIN CUSTOMIZATION (global, not per-screen)"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(rv, lp())
        addSlider(root, "Rain speed", "rainSpeed", 10, 300, 100, "%", false)
        val hueLabel = TextView(this).apply {
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(4))
            text = "Rain color hue:  ${p.getInt("rainHue", 120)}\u00B0"
        }
        root.addView(hueLabel, lp())
        root.addView(HuePicker(this, p.getInt("rainHue", 120), hueLabel), lp())
        addSlider(root, "Glyph font size", "rainFontSize", 50, 200, 100, "%", false)
        addSlider(root, "Column min length", "rainMinLen", 1, 50, 6, "", false)
        addSlider(root, "Column max length", "rainMaxLen", 1, 50, 32, "", false)
        addSlider(root, "Frame rate", "rainFps", 10, 60, 30, " fps", false)
        val gv = TextView(this).apply {
            text = "Glyph set:"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(gv, lp())
        addCheck(root, "Katakana", "glyphKatakana", false, 15)
        addCheck(root, "Digits", "glyphDigits", false, 15)
        addCheck(root, "Latin", "glyphLatin", false, 15)
        addCheck(root, "Symbols", "glyphSymbols", false, 15)
        addTitleField(root)
        addSlider(root, "Horizontal position", "hudX", 0, 100, 50, "%", true)
        addSlider(root, "Vertical position", "hudPos", 0, 100, 50, "%", true)
        addSlider(root, "Size", "hudScale", 50, 200, 100, "%", true)

        val elv = TextView(this).apply {
            text = "HUD elements  (check = show, arrows = reorder):"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(elv, lp())

        elementsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(elementsBox, lp())

        addRedactCheck(root)

        val hint = TextView(this).apply {
            text = "Pick HOME or LOCK, build a look (or load a layout), then SET the wallpaper " +
                    "and choose Both. New = fresh config for this screen."
            setTextColor(0xFF338844.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(hint, lp())

        val sv = ScrollView(this).apply {
            addView(root)
        }
        setContentView(sv)

        setTarget(false)   // styles toggle, refreshes controls + preview
        updateLayoutBtn()
    }

    private fun setTarget(lock: Boolean) {
        editingLock = lock
        styleToggle(btnHome, !lock)
        styleToggle(btnLock, lock)
        refreshAll()
    }

    private fun styleToggle(b: Button, selected: Boolean) {
        b.setTextColor(if (selected) 0xFF000000.toInt() else 0xFF00FF66.toInt())
        b.setBackgroundColor(if (selected) 0xFF00FF66.toInt() else 0xFF002200.toInt())
    }

    // --- controls -----------------------------------------------------------

    private fun addNav(row: LinearLayout, text: String, onClick: () -> Unit) {
        row.addView(Button(this).apply {
            setText(text)
            isAllCaps = false
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun addSlider(root: LinearLayout, label: String, base: String,
                          min: Int, max: Int, def: Int, unit: String,
                          targetAware: Boolean) {
        val lbl = TextView(this).apply {
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(4))
        }
        val bar = SeekBar(this).apply {
            setMax(max - min)
        }

        val refresh = Runnable {
            val v = p.getInt(keyOf(base, targetAware), def)
            bar.progress = v - min
            lbl.text = "$label:  $v$unit"
        }
        refresh.run()
        refreshers.add(refresh)

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + progress
                lbl.text = "$label:  $v$unit"
                p.edit().putInt(keyOf(base, targetAware), v).apply()
                updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { }
            override fun onStopTrackingTouch(sb: SeekBar?) { }
        })
        root.addView(lbl, lp())
        root.addView(bar, lp())
    }

    private fun addCheck(root: LinearLayout, label: String, base: String,
                         targetAware: Boolean, sizeSp: Int) {
        val cb = CheckBox(this).apply {
            text = label
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        }

        val refresh = Runnable { cb.isChecked = p.getBoolean(keyOf(base, targetAware), true) }
        refresh.run()
        refreshers.add(refresh)

        cb.setOnCheckedChangeListener { _, c ->
            p.edit().putBoolean(keyOf(base, targetAware), c).apply()
            updatePreview()
        }
        root.addView(cb, lp())
    }

    private fun addRedactCheck(root: LinearLayout) {
        val cb = CheckBox(this).apply {
            text = "Redact IP on lock screen"
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        val refresh = Runnable {
            suppressRedact = true
            cb.isChecked = p.getBoolean(keyOf("redactIp", true), true)
            suppressRedact = false
        }
        refresh.run()
        refreshers.add(refresh)

        cb.setOnCheckedChangeListener { _, checked ->
            if (suppressRedact) return@setOnCheckedChangeListener
            if (checked) {
                p.edit().putBoolean(keyOf("redactIp", true), true).apply()
                updatePreview()
            } else {
                AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Show IP on the lock screen?")
                        .setMessage("Not recommended. Your device IP will be visible to anyone "
                                + "who can see your lock screen, without unlocking. Are you sure?")
                        .setPositiveButton("Show it anyway") { _, _ ->
                            p.edit().putBoolean(keyOf("redactIp", true), false).apply()
                            updatePreview()
                        }
                        .setNegativeButton("Keep redacted") { _, _ ->
                            suppressRedact = true
                            cb.isChecked = true
                            suppressRedact = false
                        }
                        .setOnCancelListener {
                            suppressRedact = true
                            cb.isChecked = true
                            suppressRedact = false
                        }
                        .show()
            }
        }
        root.addView(cb, lp())
    }

    private fun addTitleField(root: LinearLayout) {
        val lbl = TextView(this).apply {
            text = "Title text:"
            setTextColor(0xFF00CC44.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(lbl, lp())

        val et = EditText(this).apply {
            isSingleLine = true
            setTextColor(0xFF00FF66.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        val refresh = Runnable {
            val v = p.getString(keyOf("title", true), "KEEP//HUD")
            if (et.text.toString() != v) et.setText(v)
        }
        refresh.run()
        refreshers.add(refresh)

        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                p.edit().putString(keyOf("title", true), s.toString()).apply()
                updatePreview()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
        })
        root.addView(et, lp())
    }

    private fun refreshAll() {
        for (r in refreshers) r.run()
        rebuildElements()
        updatePreview()
    }

    private fun orderKeys(): Array<String> {
        val keys = mutableListOf<String>()
        for (k in p.getString("order", DEF_ORDER)!!.split(",")) {
            val tk = k.trim()
            if (tk.isNotEmpty() && tk !in keys) keys.add(tk)
        }
        for (k in DEF_ORDER.split(",")) if (k !in keys) keys.add(k)
        return keys.toTypedArray()
    }

    private fun labelFor(key: String): String {
        return when (key) {
            "title" -> "Title"
            "ram" -> "RAM"
            "disk" -> "Disk"
            "bat" -> "Battery"
            "cpu" -> "CPU"
            "net" -> "Network / IP"
            "up" -> "Uptime"
            else -> key
        }
    }

    private fun rebuildElements() {
        elementsBox.removeAllViews()
        val order = orderKeys()
        for ((idx, key) in order.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val cb = CheckBox(this).apply {
                text = labelFor(key)
                setTextColor(0xFF00FF66.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isChecked = p.getBoolean(keyOf("el_$key", true), true)
                setOnCheckedChangeListener { _, c ->
                    p.edit().putBoolean(keyOf("el_$key", true), c).apply()
                    updatePreview()
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(orderBtn("\u25B2", idx > 0) { moveOrder(idx, -1) })
            row.addView(orderBtn("\u25BC", idx < order.size - 1) { moveOrder(idx, 1) })

            elementsBox.addView(row, lp())
        }
    }

    private fun orderBtn(label: String, enabled: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            isEnabled = enabled
            setOnClickListener { onClick() }
        }
    }

    private fun moveOrder(idx: Int, dir: Int) {
        val order = mutableListOf<String>().apply {
            for (k in orderKeys()) add(k)
        }
        val j = idx + dir
        if (j < 0 || j >= order.size) return
        val tmp = order[idx]
        order[idx] = order[j]
        order[j] = tmp
        val sb = StringBuilder()
        for (k in order.indices) {
            if (k > 0) sb.append(',')
            sb.append(order[k])
        }
        p.edit().putString("order", sb.toString()).apply()
        rebuildElements()
        updatePreview()
    }

    // --- layouts (screen-agnostic; apply to the edited screen) --------------

    private fun loadLayouts(): JSONArray {
        return try { JSONArray(p.getString("layouts", "[]")) }
        catch (_: Exception) { JSONArray() }
    }

    private fun saveLayout(name: String) {
        try {
            val o = JSONObject().apply {
                put("name", name)
                for (k in L_BOOL) put(k, p.getBoolean(keyOf(k, true), true))
                for (i in L_INT.indices) put(L_INT[i], p.getInt(keyOf(L_INT[i], true), L_INT_DEF[i]))
                put("title", p.getString(keyOf("title", true), "KEEP//HUD"))
            }

            val arr = loadLayouts()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("name") != name) out.put(e)
            }
            out.put(o)
            p.edit().putString("layouts", out.toString()).apply()
            currentLayout = name
            updateLayoutBtn()
            toast("Saved \"$name\"")
        } catch (_: Exception) {
            toast("Save failed")
        }
    }

    private fun applyLayout(o: JSONObject) {
        val e = p.edit()
        for (k in L_BOOL) e.putBoolean(keyOf(k, true), o.optBoolean(k, true))
        for (i in L_INT.indices) e.putInt(keyOf(L_INT[i], true), o.optInt(L_INT[i], L_INT_DEF[i]))
        e.putString(keyOf("title", true), o.optString("title", "KEEP//HUD"))
        e.apply()
        refreshAll()
    }

    private fun deleteLayout(name: String) {
        try {
            val arr = loadLayouts()
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("name") != name) out.put(e)
            }
            p.edit().putString("layouts", out.toString()).apply()
            if (name == currentLayout) currentLayout = null
            updateLayoutBtn()
        } catch (_: Exception) { }
    }

    private fun updateLayoutBtn() {
        layoutBtn.text = currentLayout ?: "(unsaved layout)"
    }

    private fun showLayoutPicker() {
        val arr = loadLayouts()
        if (arr.length() == 0) { toast("No saved layouts yet - tap Save"); return }
        val names = Array(arr.length()) { i -> arr.optJSONObject(i).optString("name") }
        AlertDialog.Builder(this)
                .setTitle("Load layout into " + if (editingLock) "LOCK" else "HOME")
                .setItems(names) { _, which ->
                    val o = arr.optJSONObject(which)
                    if (o != null) {
                        applyLayout(o)
                        currentLayout = names[which]
                        updateLayoutBtn()
                        toast("Loaded \"" + names[which] + "\"")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    private fun newLayout() {
        val e = p.edit()
        for (k in L_BOOL) e.putBoolean(keyOf(k, true), true)
        for (i in L_INT.indices) e.putInt(keyOf(L_INT[i], true), L_INT_DEF[i])
        e.putString(keyOf("title", true), "KEEP//HUD")
        e.apply()
        currentLayout = null
        refreshAll()
        updateLayoutBtn()
        toast("New config for " + if (editingLock) "LOCK" else "HOME")
    }

    private fun deleteCurrent() {
        if (currentLayout == null) { toast("This layout isn't saved yet"); return }
        confirmDelete(currentLayout!!)
    }

    private fun saveDialog() {
        val et = EditText(this).apply {
            isSingleLine = true
            setText(currentLayout ?: "")
        }
        AlertDialog.Builder(this)
                .setTitle("Save layout as")
                .setView(et)
                .setPositiveButton("Save") { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isNotEmpty()) saveLayout(n)
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
                .setMessage("Delete layout \"$name\"?")
                .setPositiveButton("Delete") { _, _ -> deleteLayout(name) }
                .setNegativeButton("Cancel", null)
                .show()
    }

    // --- preview ------------------------------------------------------------

    private fun updatePreview() {
        val lines = mutableListOf<String>()
        if (p.getBoolean(keyOf("hud", true), true)) {
            for (key in orderKeys()) {
                if (!p.getBoolean(keyOf("el_$key", true), true)) continue
                val line = sampleLine(key)
                if (line != null) lines.add(line)
            }
        }
        preview.set(
                p.getInt(keyOf("hudX", true), 50) / 100f,
                p.getInt(keyOf("hudPos", true), 50) / 100f,
                p.getInt(keyOf("hudScale", true), 100) / 100f,
                lines.toTypedArray())
    }

    private fun sampleLine(key: String): String? {
        return when (key) {
            "title" -> {
                val t = p.getString(keyOf("title", true), "KEEP//HUD")
                if (t!!.isNotEmpty()) t else null
            }
            "ram" -> "RAM  [####\u00B7\u00B7\u00B7\u00B7] 62%"
            "disk" -> "DISK [######\u00B7\u00B7] 92/128G"
            "bat" -> "BAT  [#######\u00B7] 84% +"
            "cpu" -> "CPU  [##\u00B7\u00B7\u00B7\u00B7\u00B7\u00B7] 18%"
            "net" -> {
                val redact = p.getBoolean(keyOf("redactIp", true), true)
                if (editingLock && redact) "NET  [locked]" else "NET  192.168.7.127"
            }
            "up" -> "UP   3d 04:12"
            else -> null
        }
    }

    private fun setWallpaper() {
        try {
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@SettingsActivity, MatrixWallpaperService::class.java))
            }
            startActivity(i)
        } catch (_: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                Toast.makeText(this, "Open Settings -> Wallpaper -> Ghost Rain",
                        Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun lp(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(d: Int): Int {
        return (d * resources.displayMetrics.density).roundToInt()
    }

    private fun dp(d: Float): Int {
        return (d * resources.displayMetrics.density).roundToInt()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    /** Screen-proportioned preview that renders the actual HUD lines at scale/pos. */
    inner class PreviewView(ctx: Context) : View(ctx) {
        private var px = 0.5f
        private var py = 0.5f
        private var scale = 1f
        private var lines = emptyArray<String>()
        private val sw: Int
        private val sh: Int
        private val aspect: Float
        private val screen = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val meas = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            sw = resources.displayMetrics.widthPixels
            sh = resources.displayMetrics.heightPixels
            aspect = sw / maxOf(1, sh).toFloat()
            val vt = try {
                Typeface.createFromAsset(context.assets, "VT323-Regular.ttf")
            } catch (_: Exception) {
                Typeface.MONOSPACE
            }
            meas.typeface = vt
            lineP.typeface = vt
            lineP.color = 0xFF00FF66.toInt()
            lineP.textAlign = Paint.Align.CENTER
            screen.color = 0xFF000000.toInt()
            border.style = Paint.Style.STROKE
            border.strokeWidth = dp(1).toFloat()
            border.color = 0xFF00FF33.toInt()
            box.style = Paint.Style.STROKE
            box.strokeWidth = dp(1).toFloat()
            box.color = 0xFF00FF66.toInt()
            boxFill.color = 0x3300FF66.toInt()
        }

        fun set(x: Float, y: Float, s: Float, ls: Array<String>) {
            px = x; py = y; scale = s; lines = ls
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val vw = width
            val vh = height
            val (rw, rh) = if (vw / vh.toFloat() > aspect) {
                Pair(vh * aspect, vh.toFloat())
            } else {
                Pair(vw.toFloat(), vw / aspect)
            }
            val left = (vw - rw) / 2f
            val top = (vh - rh) / 2f
            c.drawRect(left, top, left + rw, top + rh, screen)
            c.drawRect(left, top, left + rw, top + rh, border)
            if (lines.isEmpty()) return

            val density = resources.displayMetrics.density
            val size = density * 13f * 1.05f * scale
            meas.textSize = size
            var panelW = 0f
            for (s in lines) panelW = maxOf(panelW, meas.measureText(s))
            val padX = size
            val padY = size * 0.6f
            val lh = size * 1.35f
            val pScale = rw / sw

            val bw = minOf(rw, (panelW + 2 * padX) * pScale)
            val bh = minOf(rh, (lines.size * lh + 2 * padY) * pScale)
            val bcx = (left + clamp(px, 0f, 1f) * rw).coerceIn(left + bw / 2, left + rw - bw / 2)
            val bcy = (top + clamp(py, 0f, 1f) * rh).coerceIn(top + bh / 2, top + rh - bh / 2)

            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, boxFill)
            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, box)

            lineP.textSize = size * pScale
            val plh = lh * pScale
            val baseline = bcy - bh / 2 + padY * pScale + (size * pScale) * 0.8f
            for (i in lines.indices) c.drawText(lines[i], bcx, baseline + i * plh, lineP)
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float {
            return v.coerceIn(lo, hi)
        }
    }

    /**
     * Horizontal hue bar for picking the rain color. Touching or dragging
     * selects a hue degree (0-360) and persists to "rainHue" in prefs.
     */
    inner class HuePicker(ctx: Context, initialHue: Int, private val label: TextView?) : View(ctx) {
        private var hue = initialHue.coerceIn(0, 360)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            isAntiAlias = true
        }
        private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF222222.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f).toFloat()
            isAntiAlias = true
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF555555.toInt()
            strokeWidth = dp(0.5f).toFloat()
            isAntiAlias = true
        }
        private var thumbR = 0

        init {
            isFocusable = true
            isClickable = true
        }

        fun setHue(h: Int) {
            hue = h.coerceIn(0, 360)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            thumbR = dp(10)
            val colors = IntArray(7) { i -> Color.HSVToColor(255, floatArrayOf(i * 60f, 1f, 1f)) }
            val pos = floatArrayOf(0f, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f)
            barPaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, colors, pos, Shader.TileMode.CLAMP)
        }

        override fun onMeasure(ws: Int, hs: Int) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dp(44))
        }

        override fun onDraw(c: Canvas) {
            val w = width
            val barH = dp(BAR_HEIGHT_DP)
            val barTop = (height - barH) / 2

            val barL = (thumbR + dp(3)).toFloat()
            val barR = (w - thumbR - dp(3)).toFloat()
            val cy = barTop + barH / 2f

            // Hue gradient bar
            c.drawRoundRect(barL, barTop.toFloat(), barR, (barTop + barH).toFloat(), dp(4).toFloat(), dp(4).toFloat(), barPaint)
            c.drawRoundRect(barL, barTop.toFloat(), barR, (barTop + barH).toFloat(), dp(4).toFloat(), dp(4).toFloat(), borderPaint)

            // Thumb indicator
            val thumbCX = barL + (barR - barL) * (hue / 360f)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbFill)
            c.drawCircle(thumbCX, cy, thumbR.toFloat(), thumbStroke)

            // Inner dot showing the selected color
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(255, floatArrayOf(hue.toFloat(), 1f, 1f))
            }
            c.drawCircle(thumbCX, cy, thumbR * 0.45f, dot)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val barL = (thumbR + dp(3)).toFloat()
                    val barR = (width - thumbR - dp(3)).toFloat()
                    val x = e.x.coerceIn(barL, barR)
                    val newHue = (360 * (x - barL) / (barR - barL)).roundToInt().coerceIn(0, 360)
                    if (newHue != hue) {
                        hue = newHue
                        p.edit().putInt("rainHue", hue).apply()
                        label?.text = "Rain color hue:  $hue\u00B0"
                        invalidate()
                        updatePreview()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        override fun performClick(): Boolean {
            return super.performClick()
        }

    }
}
