// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.view.MotionEvent;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-screen HUD configs (HOME vs LOCK) chosen by a target toggle, plus named
 * screen-agnostic layouts you can apply to whichever screen you're editing.
 * The wallpaper engine renders the HOME config when unlocked, LOCK when locked.
 * Glyph shimmer (the rain) is global. A live preview shows the edited screen.
 */
public class SettingsActivity extends Activity {

    // Screen-agnostic config keys (a layout = a snapshot of these for one screen).
    private static final String[] L_BOOL = {
            "hud", "el_title", "el_ram", "el_disk", "el_bat", "el_cpu", "el_net", "el_up", "redactIp"
    };
    private static final String[] L_INT = { "hudX", "hudPos", "hudScale" };
    private static final int[] L_INT_DEF = { 50, 50, 100 };
    private static final String DEF_ORDER = "title,ram,disk,bat,cpu,net,up";

    private SharedPreferences p;
    private PreviewView preview;
    private LinearLayout elementsBox;
    private Button layoutBtn, btnHome, btnLock;
    private String currentLayout = null;
    private boolean editingLock = false;
    private boolean suppressRedact = false;
    private final List<Runnable> refreshers = new ArrayList<Runnable>();

    private String keyOf(String base, boolean targetAware) {
        return (targetAware && editingLock) ? base + "Lock" : base;
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        p = getSharedPreferences("matrix", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GHOST RAIN // SETTINGS");
        title.setTextColor(0xFF00FF66);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, lp());

        TextView banner = new TextView(this);
        banner.setText("SETUP - DO THIS NOW, AND AFTER EVERY APP UPDATE:\n"
                + "  1.  Tap  > SET GHOST RAIN WALLPAPER  below\n"
                + "  2.  Choose  BOTH  (home + lock screen)\n"
                + "The wallpaper will NOT change/update until you re-set it.\n\n"
                + "Tip: if the lock-screen clock covers the HUD, set the lock clock size to Small "
                + "(in your phone's lock screen / wallpaper settings).");
        banner.setTextColor(0xFFFFD24D);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        banner.setBackgroundColor(0xFF241B00);
        int bp = dp(12);
        banner.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(8);
        root.addView(banner, blp);

        Button set = new Button(this);
        set.setText("> SET GHOST RAIN WALLPAPER");
        set.setAllCaps(false);
        set.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setWallpaper(); }
        });
        root.addView(set, lp());

        // --- Editing target (HOME / LOCK) ---
        TextView et = new TextView(this);
        et.setText("Editing screen:");
        et.setTextColor(0xFF00CC44);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setPadding(0, dp(16), 0, dp(4));
        root.addView(et, lp());

        LinearLayout toggle = new LinearLayout(this);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        btnHome = new Button(this);
        btnLock = new Button(this);
        btnHome.setText("HOME"); btnHome.setAllCaps(false);
        btnLock.setText("LOCK"); btnLock.setAllCaps(false);
        btnHome.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setTarget(false); }
        });
        btnLock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setTarget(true); }
        });
        toggle.addView(btnHome, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggle.addView(btnLock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(toggle, lp());

        // --- Preview ---
        preview = new PreviewView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        plp.topMargin = dp(10);
        plp.bottomMargin = dp(4);
        root.addView(preview, plp);

        // --- Layouts (apply to the screen being edited) ---
        TextView ll = new TextView(this);
        ll.setText("Layouts (apply to edited screen)");
        ll.setTextColor(0xFF00CC44);
        ll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        ll.setPadding(0, dp(12), 0, dp(4));
        root.addView(ll, lp());

        layoutBtn = new Button(this);
        layoutBtn.setAllCaps(false);
        layoutBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLayoutPicker(); }
        });
        root.addView(layoutBtn, lp());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        addNav(btnRow, "New", new View.OnClickListener() {
            public void onClick(View v) { newLayout(); }
        });
        addNav(btnRow, "Save", new View.OnClickListener() {
            public void onClick(View v) { saveDialog(); }
        });
        addNav(btnRow, "Delete", new View.OnClickListener() {
            public void onClick(View v) { deleteCurrent(); }
        });
        root.addView(btnRow, lp());

        // --- Config controls (per-screen unless noted) ---
        addCheck(root, "Show HUD overlay", "hud", true, 18);
        addSlider(root, "Glyph shimmer (both screens)", "shimmer", 0, 100, 60, "%", false);
        // --- Rain customization (global, not per-screen) ---
        TextView rv = new TextView(this);
        rv.setText("RAIN CUSTOMIZATION (global, not per-screen)");
        rv.setTextColor(0xFF00CC44);
        rv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        rv.setPadding(0, dp(16), 0, dp(4));
        root.addView(rv, lp());
        addSlider(root, "Rain speed", "rainSpeed", 10, 300, 100, "%", false);
        final TextView hueLabel = new TextView(this);
        hueLabel.setTextColor(0xFF00CC44);
        hueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hueLabel.setPadding(0, dp(12), 0, dp(4));
        hueLabel.setText("Rain color hue:  " + p.getInt("rainHue", 120) + "\u00B0");
        root.addView(hueLabel, lp());
        root.addView(new HuePicker(this, p.getInt("rainHue", 120), hueLabel), lp());
        addSlider(root, "Glyph font size", "rainFontSize", 50, 200, 100, "%", false);
        addSlider(root, "Column min length", "rainMinLen", 1, 50, 6, "", false);
        addSlider(root, "Column max length", "rainMaxLen", 1, 50, 32, "", false);
        addSlider(root, "Frame rate", "rainFps", 10, 60, 30, " fps", false);
        TextView gv = new TextView(this);
        gv.setText("Glyph set:");
        gv.setTextColor(0xFF00CC44);
        gv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        gv.setPadding(0, dp(12), 0, dp(4));
        root.addView(gv, lp());
        addCheck(root, "Katakana", "glyphKatakana", false, 15);
        addCheck(root, "Digits", "glyphDigits", false, 15);
        addCheck(root, "Latin", "glyphLatin", false, 15);
        addCheck(root, "Symbols", "glyphSymbols", false, 15);
        addTitleField(root);
        addSlider(root, "Horizontal position", "hudX", 0, 100, 50, "%", true);
        addSlider(root, "Vertical position", "hudPos", 0, 100, 50, "%", true);
        addSlider(root, "Size", "hudScale", 50, 200, 100, "%", true);

        TextView elv = new TextView(this);
        elv.setText("HUD elements  (check = show, arrows = reorder):");
        elv.setTextColor(0xFF00CC44);
        elv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        elv.setPadding(0, dp(16), 0, dp(4));
        root.addView(elv, lp());

        elementsBox = new LinearLayout(this);
        elementsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(elementsBox, lp());

        addRedactCheck(root);

        TextView hint = new TextView(this);
        hint.setText("Pick HOME or LOCK, build a look (or load a layout), then SET the wallpaper "
                + "and choose Both. New = fresh config for this screen.");
        hint.setTextColor(0xFF338844);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(0, dp(14), 0, 0);
        root.addView(hint, lp());

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        setTarget(false);   // styles toggle, refreshes controls + preview
        updateLayoutBtn();
    }

    private void setTarget(boolean lock) {
        editingLock = lock;
        styleToggle(btnHome, !lock);
        styleToggle(btnLock, lock);
        refreshAll();
    }

    private void styleToggle(Button b, boolean selected) {
        b.setTextColor(selected ? 0xFF000000 : 0xFF00FF66);
        b.setBackgroundColor(selected ? 0xFF00FF66 : 0xFF002200);
    }

    // --- controls -----------------------------------------------------------

    private void addNav(LinearLayout row, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void addSlider(LinearLayout root, final String label, final String base,
                           final int min, int max, final int def, final String unit,
                           final boolean targetAware) {
        final TextView lbl = new TextView(this);
        lbl.setTextColor(0xFF00CC44);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lbl.setPadding(0, dp(12), 0, dp(4));
        final SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);

        Runnable refresh = new Runnable() {
            public void run() {
                int v = p.getInt(keyOf(base, targetAware), def);
                bar.setProgress(v - min);
                lbl.setText(label + ":  " + v + unit);
            }
        };
        refresh.run();
        refreshers.add(refresh);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int v = min + progress;
                lbl.setText(label + ":  " + v + unit);
                p.edit().putInt(keyOf(base, targetAware), v).apply();
                updatePreview();
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(lbl, lp());
        root.addView(bar, lp());
    }

    private void addCheck(LinearLayout root, String label, final String base,
                          final boolean targetAware, int sizeSp) {
        final CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(0xFF00FF66);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);

        Runnable refresh = new Runnable() {
            public void run() { cb.setChecked(p.getBoolean(keyOf(base, targetAware), true)); }
        };
        refresh.run();
        refreshers.add(refresh);

        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean c) {
                p.edit().putBoolean(keyOf(base, targetAware), c).apply();
                updatePreview();
            }
        });
        root.addView(cb, lp());
    }

    private void addRedactCheck(LinearLayout root) {
        final CheckBox cb = new CheckBox(this);
        cb.setText("Redact IP on lock screen");
        cb.setTextColor(0xFF00FF66);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);

        Runnable refresh = new Runnable() {
            public void run() {
                suppressRedact = true;
                cb.setChecked(p.getBoolean(keyOf("redactIp", true), true));
                suppressRedact = false;
            }
        };
        refresh.run();
        refreshers.add(refresh);

        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (suppressRedact) return;
                if (checked) {
                    p.edit().putBoolean(keyOf("redactIp", true), true).apply();
                    updatePreview();
                    return;
                }
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Show IP on the lock screen?")
                        .setMessage("Not recommended. Your device IP will be visible to anyone "
                                + "who can see your lock screen, without unlocking. Are you sure?")
                        .setPositiveButton("Show it anyway", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                p.edit().putBoolean(keyOf("redactIp", true), false).apply();
                                updatePreview();
                            }
                        })
                        .setNegativeButton("Keep redacted", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                suppressRedact = true; cb.setChecked(true); suppressRedact = false;
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) {
                                suppressRedact = true; cb.setChecked(true); suppressRedact = false;
                            }
                        })
                        .show();
            }
        });
        root.addView(cb, lp());
    }

    private void addTitleField(LinearLayout root) {
        TextView lbl = new TextView(this);
        lbl.setText("Title text:");
        lbl.setTextColor(0xFF00CC44);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lbl.setPadding(0, dp(12), 0, dp(4));
        root.addView(lbl, lp());

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setTextColor(0xFF00FF66);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        Runnable refresh = new Runnable() {
            public void run() {
                String v = p.getString(keyOf("title", true), "KEEP//HUD");
                if (!et.getText().toString().equals(v)) et.setText(v);
            }
        };
        refresh.run();
        refreshers.add(refresh);

        et.addTextChangedListener(new TextWatcher() {
            public void afterTextChanged(Editable s) {
                p.edit().putString(keyOf("title", true), s.toString()).apply();
                updatePreview();
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
        root.addView(et, lp());
    }

    private void refreshAll() {
        for (Runnable r : refreshers) r.run();
        rebuildElements();
        updatePreview();
    }

    private String[] orderKeys() {
        List<String> keys = new ArrayList<String>();
        for (String k : p.getString("order", DEF_ORDER).split(",")) {
            k = k.trim();
            if (k.length() > 0 && !keys.contains(k)) keys.add(k);
        }
        for (String k : DEF_ORDER.split(",")) if (!keys.contains(k)) keys.add(k);
        return keys.toArray(new String[0]);
    }

    private String labelFor(String key) {
        if (key.equals("title")) return "Title";
        if (key.equals("ram")) return "RAM";
        if (key.equals("disk")) return "Disk";
        if (key.equals("bat")) return "Battery";
        if (key.equals("cpu")) return "CPU";
        if (key.equals("net")) return "Network / IP";
        if (key.equals("up")) return "Uptime";
        return key;
    }

    private void rebuildElements() {
        if (elementsBox == null) return;
        elementsBox.removeAllViews();
        String[] order = orderKeys();
        for (int i = 0; i < order.length; i++) {
            final String key = order[i];
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            final CheckBox cb = new CheckBox(this);
            cb.setText(labelFor(key));
            cb.setTextColor(0xFF00FF66);
            cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cb.setChecked(p.getBoolean(keyOf("el_" + key, true), true));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean c) {
                    p.edit().putBoolean(keyOf("el_" + key, true), c).apply();
                    updatePreview();
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(orderBtn("\u25B2", idx > 0, new View.OnClickListener() {
                public void onClick(View v) { moveOrder(idx, -1); }
            }));
            row.addView(orderBtn("\u25BC", idx < order.length - 1, new View.OnClickListener() {
                public void onClick(View v) { moveOrder(idx, 1); }
            }));

            elementsBox.addView(row, lp());
        }
    }

    private Button orderBtn(String label, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setEnabled(enabled);
        b.setOnClickListener(l);
        return b;
    }

    private void moveOrder(int idx, int dir) {
        List<String> order = new ArrayList<String>();
        for (String k : orderKeys()) order.add(k);
        int j = idx + dir;
        if (j < 0 || j >= order.size()) return;
        String tmp = order.get(idx);
        order.set(idx, order.get(j));
        order.set(j, tmp);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < order.size(); k++) { if (k > 0) sb.append(','); sb.append(order.get(k)); }
        p.edit().putString("order", sb.toString()).apply();
        rebuildElements();
        updatePreview();
    }

    // --- layouts (screen-agnostic; apply to the edited screen) --------------

    private JSONArray loadLayouts() {
        try { return new JSONArray(p.getString("layouts", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private void saveLayout(String name) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            for (String k : L_BOOL) o.put(k, p.getBoolean(keyOf(k, true), true));
            for (int i = 0; i < L_INT.length; i++) o.put(L_INT[i], p.getInt(keyOf(L_INT[i], true), L_INT_DEF[i]));
            o.put("title", p.getString(keyOf("title", true), "KEEP//HUD"));

            JSONArray arr = loadLayouts();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                if (!e.optString("name").equals(name)) out.put(e);
            }
            out.put(o);
            p.edit().putString("layouts", out.toString()).apply();
            currentLayout = name;
            updateLayoutBtn();
            toast("Saved \"" + name + "\"");
        } catch (Exception e) {
            toast("Save failed");
        }
    }

    private void applyLayout(JSONObject o) {
        SharedPreferences.Editor e = p.edit();
        for (String k : L_BOOL) e.putBoolean(keyOf(k, true), o.optBoolean(k, true));
        for (int i = 0; i < L_INT.length; i++) e.putInt(keyOf(L_INT[i], true), o.optInt(L_INT[i], L_INT_DEF[i]));
        e.putString(keyOf("title", true), o.optString("title", "KEEP//HUD"));
        e.apply();
        refreshAll();
    }

    private void deleteLayout(String name) {
        try {
            JSONArray arr = loadLayouts();
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                if (!e.optString("name").equals(name)) out.put(e);
            }
            p.edit().putString("layouts", out.toString()).apply();
            if (name.equals(currentLayout)) currentLayout = null;
            updateLayoutBtn();
        } catch (Exception ignored) { }
    }

    private void updateLayoutBtn() {
        layoutBtn.setText(currentLayout != null ? currentLayout : "(unsaved layout)");
    }

    private void showLayoutPicker() {
        final JSONArray arr = loadLayouts();
        if (arr.length() == 0) { toast("No saved layouts yet - tap Save"); return; }
        final String[] names = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) names[i] = arr.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this)
                .setTitle("Load layout into " + (editingLock ? "LOCK" : "HOME"))
                .setItems(names, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        JSONObject o = arr.optJSONObject(which);
                        if (o != null) {
                            applyLayout(o);
                            currentLayout = names[which];
                            updateLayoutBtn();
                            toast("Loaded \"" + names[which] + "\"");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void newLayout() {
        SharedPreferences.Editor e = p.edit();
        for (String k : L_BOOL) e.putBoolean(keyOf(k, true), true);
        for (int i = 0; i < L_INT.length; i++) e.putInt(keyOf(L_INT[i], true), L_INT_DEF[i]);
        e.putString(keyOf("title", true), "KEEP//HUD");
        e.apply();
        currentLayout = null;
        refreshAll();
        updateLayoutBtn();
        toast("New config for " + (editingLock ? "LOCK" : "HOME"));
    }

    private void deleteCurrent() {
        if (currentLayout == null) { toast("This layout isn't saved yet"); return; }
        confirmDelete(currentLayout);
    }

    private void saveDialog() {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(currentLayout == null ? "" : currentLayout);
        new AlertDialog.Builder(this)
                .setTitle("Save layout as")
                .setView(et)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String n = et.getText().toString().trim();
                        if (n.length() > 0) saveLayout(n);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(final String name) {
        new AlertDialog.Builder(this)
                .setMessage("Delete layout \"" + name + "\"?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { deleteLayout(name); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- preview ------------------------------------------------------------

    private void updatePreview() {
        if (preview == null) return;
        List<String> lines = new ArrayList<String>();
        if (p.getBoolean(keyOf("hud", true), true)) {
            for (String key : orderKeys()) {
                if (!p.getBoolean(keyOf("el_" + key, true), true)) continue;
                String line = sampleLine(key);
                if (line != null) lines.add(line);
            }
        }
        preview.set(p.getInt(keyOf("hudX", true), 50) / 100f,
                p.getInt(keyOf("hudPos", true), 50) / 100f,
                p.getInt(keyOf("hudScale", true), 100) / 100f,
                lines.toArray(new String[0]));
    }

    private String sampleLine(String key) {
        if (key.equals("title")) {
            String t = p.getString(keyOf("title", true), "KEEP//HUD");
            return t.length() > 0 ? t : null;
        }
        if (key.equals("ram"))  return "RAM  [####\u00B7\u00B7\u00B7\u00B7] 62%";
        if (key.equals("disk")) return "DISK [######\u00B7\u00B7] 92/128G";
        if (key.equals("bat"))  return "BAT  [#######\u00B7] 84% +";
        if (key.equals("cpu"))  return "CPU  [##\u00B7\u00B7\u00B7\u00B7\u00B7\u00B7] 18%";
        if (key.equals("net")) {
            boolean redact = p.getBoolean(keyOf("redactIp", true), true);
            return (editingLock && redact) ? "NET  [locked]" : "NET  192.168.7.127";
        }
        if (key.equals("up"))   return "UP   3d 04:12";
        return null;
    }

    private void setWallpaper() {
        try {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, MatrixWallpaperService.class));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings -> Wallpaper -> Ghost Rain",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    private int dp(float d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Screen-proportioned preview that renders the actual HUD lines at scale/pos. */
    private class PreviewView extends View {
        private float px = 0.5f, py = 0.5f, scale = 1f;
        private String[] lines = new String[0];
        private final int sw, sh;
        private final float aspect;
        private final Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint boxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint meas = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewView(Context c) {
            super(c);
            sw = getResources().getDisplayMetrics().widthPixels;
            sh = getResources().getDisplayMetrics().heightPixels;
            aspect = sw / (float) Math.max(1, sh);
            Typeface vt;
            try { vt = Typeface.createFromAsset(getAssets(), "VT323-Regular.ttf"); }
            catch (Exception e) { vt = Typeface.MONOSPACE; }
            meas.setTypeface(vt);
            lineP.setTypeface(vt);
            lineP.setColor(0xFF00FF66);
            lineP.setTextAlign(Paint.Align.CENTER);
            screen.setColor(0xFF000000);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            border.setColor(0xFF00FF33);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(dp(1));
            box.setColor(0xFF00FF66);
            boxFill.setColor(0x3300FF66);
        }

        void set(float x, float y, float s, String[] ls) {
            px = x; py = y; scale = s; lines = ls;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int vw = getWidth(), vh = getHeight();
            float rw, rh;
            if (vw / (float) vh > aspect) { rh = vh; rw = vh * aspect; }
            else { rw = vw; rh = vw / aspect; }
            float left = (vw - rw) / 2f, top = (vh - rh) / 2f;
            c.drawRect(left, top, left + rw, top + rh, screen);
            c.drawRect(left, top, left + rw, top + rh, border);
            if (lines.length == 0) return;

            float density = getResources().getDisplayMetrics().density;
            float size = density * 13f * 1.05f * scale;
            meas.setTextSize(size);
            float panelW = 0;
            for (String s : lines) panelW = Math.max(panelW, meas.measureText(s));
            float padX = size, padY = size * 0.6f;
            float lh = size * 1.35f;
            float pScale = rw / sw;

            float bw = Math.min(rw, (panelW + 2 * padX) * pScale);
            float bh = Math.min(rh, (lines.length * lh + 2 * padY) * pScale);
            float bcx = left + clamp(px, 0, 1) * rw;
            float bcy = top + clamp(py, 0, 1) * rh;
            bcx = clamp(bcx, left + bw / 2, left + rw - bw / 2);
            bcy = clamp(bcy, top + bh / 2, top + rh - bh / 2);

            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, boxFill);
            c.drawRect(bcx - bw / 2, bcy - bh / 2, bcx + bw / 2, bcy + bh / 2, box);

            lineP.setTextSize(size * pScale);
            float plh = lh * pScale;
            float baseline = bcy - bh / 2 + padY * pScale + (size * pScale) * 0.8f;
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], bcx, baseline + i * plh, lineP);
        }

        private float clamp(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }
    /**
     * Horizontal hue bar for picking the rain color. Touching or dragging
     * selects a hue degree (0-360) and persists to "rainHue" in prefs.
     */
    private class HuePicker extends View {
        private int hue;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextView label;
        private int thumbR;
        private static final int BAR_HEIGHT_DP = 18;

        HuePicker(Context c, int initialHue, TextView labelView) {
            super(c);
            hue = Math.max(0, Math.min(360, initialHue));
            label = labelView;
            thumbFill.setColor(0xFFFFFFFF);
            thumbFill.setAntiAlias(true);
            thumbStroke.setColor(0xFF222222);
            thumbStroke.setStyle(Paint.Style.STROKE);
            thumbStroke.setStrokeWidth(dp(1.5f));
            thumbStroke.setAntiAlias(true);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(0xFF555555);
            borderPaint.setStrokeWidth(dp(0.5f));
            borderPaint.setAntiAlias(true);
            setFocusable(true);
            setClickable(true);
        }

        void setHue(int h) {
            hue = Math.max(0, Math.min(360, h));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            thumbR = dp(10);
            int[] colors = new int[7];
            for (int i = 0; i < 7; i++) {
                colors[i] = Color.HSVToColor(255, new float[]{i * 60f, 1f, 1f});
            }
            float[] pos = {0, 1f / 6f, 2f / 6f, 3f / 6f, 4f / 6f, 5f / 6f, 1f};
            barPaint.setShader(new LinearGradient(0, 0, w, 0, colors, pos, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(MeasureSpec.getSize(ws), dp(44));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int barH = dp(BAR_HEIGHT_DP);
            int barTop = (getHeight() - barH) / 2;

            float barL = thumbR + dp(3);
            float barR = w - thumbR - dp(3);
            float cy = barTop + barH / 2f;

            // Hue gradient bar
            c.drawRoundRect(barL, barTop, barR, barTop + barH, dp(4), dp(4), barPaint);
            c.drawRoundRect(barL, barTop, barR, barTop + barH, dp(4), dp(4), borderPaint);

            // Thumb indicator
            float thumbCX = barL + (barR - barL) * (hue / 360f);
            c.drawCircle(thumbCX, cy, thumbR, thumbFill);
            c.drawCircle(thumbCX, cy, thumbR, thumbStroke);

            // Inner dot showing the selected color
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.HSVToColor(255, new float[]{hue, 1f, 1f}));
            c.drawCircle(thumbCX, cy, thumbR * 0.45f, dot);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    float barL = thumbR + dp(3);
                    float barR = getWidth() - thumbR - dp(3);
                    float x = Math.max(barL, Math.min(barR, e.getX()));
                    int newHue = Math.round(360 * (x - barL) / (barR - barL));
                    newHue = Math.max(0, Math.min(360, newHue));
                    if (newHue != hue) {
                        hue = newHue;
                        p.edit().putInt("rainHue", hue).apply();
                        if (label != null) label.setText("Rain color hue:  " + hue + "\u00B0");
                        invalidate();
                        updatePreview();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    performClick();
                    return true;
                }
            }
            return super.onTouchEvent(e);
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
