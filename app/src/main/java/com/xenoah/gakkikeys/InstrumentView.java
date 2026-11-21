package com.xenoah.gakkikeys;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The whole keyboard: top bar + one of
 * 🥁 drum pads / 🎹 keys (piano + synth sounds, chosen from the ⚙ menu) / ⌨ typing keyboard,
 * with a ⚙ settings overlay. Everything is multi-touch.
 */
public final class InstrumentView extends View {

    public interface Host {
        void onNextIme();
        void onPickIme();
        void onHide();
        void onCommit(String text);
        /** Typing-keyboard key. kana = romaji-kana composing allowed. */
        void onTypeKey(String code, boolean kana);
        boolean isComposing();
    }

    private static final String[] DO_RE_MI = {"ド", "ド#", "レ", "レ#", "ミ", "ファ", "ファ#", "ソ", "ソ#", "ラ", "ラ#", "シ"};
    private static final String[] NOTE = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] DRUM_TEXT = {"ドン", "タン", "パン", "カッ", "ツ", "シャー", "ジャーン", "カン", "トン", "トン", "タン", "ブーン"};
    /** Pad layout (row-major, 4x3) -> drum index. Bottom row = kick/snare like an MPC. */
    private static final int[] PAD_MAP = {
            SynthCore.CRASH, SynthCore.OHAT, SynthCore.CHAT, SynthCore.COWBELL,
            SynthCore.TOM_H, SynthCore.TOM_M, SynthCore.TOM_L, SynthCore.BASS808,
            SynthCore.KICK, SynthCore.SNARE, SynthCore.CLAP, SynthCore.RIM};

    /** Red / orange / yellow / cream, like the TR-808 step buttons. */
    private static final int[] PAD_808_COLORS = {0xFFE5452F, 0xFFF08A24, 0xFFF2CF3C, 0xFFEDE6D3};

    private static final int MODE_DRUM = 0, MODE_KEYS = 1, MODE_TYPE = 3, MODE_PHONE = 4;
    private static final int PAGE_LETTERS = 0, PAGE_NUM = 1, PAGE_SYM = 2;

    /** Sounds selectable for 🎹: 0 = piano, 1.. = synth presets. */
    private static final String[] SOUNDS = {"ピアノ", "LEAD", "SQUARE", "PAD", "BASS"};
    private static final String[] WIDTHS = {"1oct", "1.5", "2oct", "3oct"};
    private static final int[] WIDTH_WHITES = {8, 11, 15, 22};
    private static final int[] WIDTH_OCTS = {1, 2, 2, 3};
    private static final String[] LABELS = {"ドレミ", "CDE", "なし"};
    private static final String[] SE_VOLS = {"OFF", "小", "中", "大", "🎲"};
    private static final float[] SE_VOL = {0f, 0.35f, 0.65f, 1f};
    private static final String[] ONOFF = {"ON", "OFF"};

    // bar button ids
    private static final int B_DRUM = 1, B_KEYS = 2, B_TYPE = 3, B_OCT_DN = 4, B_OCT = 5, B_OCT_UP = 6,
            B_SUS = 7, B_MENU = 8, B_TXT = 9, B_GLOBE = 10, B_HIDE = 11, B_PHONE = 12;

    private static final class Btn {
        final int id;
        final float weight;
        final RectF r = new RectF();
        Btn(int id, float weight) { this.id = id; this.weight = weight; }
    }

    private static final class Key {
        final RectF r = new RectF();
        int midi;
        boolean black;
    }

    private static final class TKey {
        final RectF r = new RectF();
        final String code;
        TKey(String code) { this.code = code; }
    }

    /** One menu option cell. */
    private static final class Opt {
        final int row, idx;
        final RectF r = new RectF();
        Opt(int row, int idx) { this.row = row; this.idx = idx; }
    }

    private static final class Touch {
        int type; // 1 note, 2 drum, 3 bar button, 4 typing key, 5 menu, 6 phone key
        int midi;
        int voiceKey;
        int btn;
        float y;
        TKey tkey;
    }

    private final Host host;
    private final SynthCore synth;
    private final SharedPreferences prefs;
    private final float dp;

    private int mode;
    private int octave;  // C of lowest key (C4 = 4)
    private int sound;   // index into SOUNDS
    private int widthIdx;
    private int labelIdx;
    private int seVolIdx;
    private int seSeed;
    private boolean dtmf;
    private boolean sustain;
    private boolean textOut;
    private boolean menuOpen;

    private boolean kanaLang = true;
    private int page = PAGE_LETTERS;
    private int shift; // 0 off, 1 once, 2 caps lock
    private long lastShiftTap;

    private final List<Btn> bar = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final List<TKey> tkeys = new ArrayList<>();
    private final List<Opt> opts = new ArrayList<>();
    private final RectF[] pads = new RectF[12];
    private final long[] padFlash = new long[12];
    private final int[] padColor = new int[12];
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final int[] heldCount = new int[128];
    private TKey delKey;

    // ☎ phone mode
    private static final String[] PHONE_KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
    private final RectF[] phoneRects = new RectF[12];
    private final RectF phoneDisplay = new RectF();
    private final RectF phoneDel = new RectF();
    private final StringBuilder dialed = new StringBuilder();
    private final int[] phoneHeld = new int[12];

    private float barH;
    private final RectF body = new RectF();
    private boolean globeLongDone;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private static final int BG = 0xFF0D0E14;
    private static final int BAR_BG = 0xFF161822;
    private static final int ACCENT = 0xFFFF4FA3;
    private static final int CYAN = 0xFF38E1FF;

    private final Runnable globeLong = new Runnable() {
        @Override public void run() {
            globeLongDone = true;
            host.onPickIme();
        }
    };

    private final Runnable delRepeat = new Runnable() {
        @Override public void run() {
            if (delKey == null) return;
            host.onTypeKey("DEL", kanaLang);
            invalidate();
            postDelayed(this, 55);
        }
    };

    public InstrumentView(Context ctx, SynthCore synth, Host host) {
        super(ctx);
        this.host = host;
        this.synth = synth;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        dp = dm.density;
        prefs = ctx.getSharedPreferences("gakki", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", MODE_KEYS);
        if (mode == 2) { mode = MODE_KEYS; sound = 1 + prefs.getInt("preset", 0); } // old SYNTH tab
        if (mode != MODE_DRUM && mode != MODE_KEYS && mode != MODE_TYPE && mode != MODE_PHONE) mode = MODE_KEYS;
        octave = prefs.getInt("octave", 4);
        sound = prefs.getInt("sound", sound);
        widthIdx = prefs.getInt("width", 0);
        labelIdx = prefs.getInt("labels", 0);
        seVolIdx = prefs.getInt("seVol", 2);
        seSeed = prefs.getInt("seSeed", (int) System.nanoTime());
        dtmf = prefs.getBoolean("dtmf", true);
        textOut = prefs.getBoolean("text", false);
        kanaLang = prefs.getBoolean("kana", true);
        sound = clamp(sound, 0, SOUNDS.length - 1);
        widthIdx = clamp(widthIdx, 0, WIDTHS.length - 1);
        labelIdx = clamp(labelIdx, 0, LABELS.length - 1);
        seVolIdx = clamp(seVolIdx, 0, SE_VOL.length - 1);
        octave = clamp(octave, 1, maxOctave());
        for (int i = 0; i < 12; i++) {
            phoneRects[i] = new RectF();
            pads[i] = new RectF();
            padColor[i] = PAD_808_COLORS[i % 4]; // TR-808 button colours
        }
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setHapticFeedbackEnabled(true);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int maxOctave() {
        return 7 - WIDTH_OCTS[widthIdx];
    }

    private boolean isPiano() {
        return sound == 0;
    }

    private void save() {
        prefs.edit().putInt("mode", mode).putInt("octave", octave).putInt("sound", sound)
                .putInt("width", widthIdx).putInt("labels", labelIdx).putInt("seVol", seVolIdx)
                .putInt("seSeed", seSeed).putBoolean("dtmf", dtmf).putBoolean("text", textOut).apply();
    }

    // ================================================================== layout
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = (int) Math.min(290 * dp, dm.heightPixels * 0.48f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        relayout();
    }

    private void relayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        barH = 44 * dp;
        body.set(0, barH, w, h);
        layoutBar();
        layoutPads();
        layoutKeys();
        layoutTyping();
        layoutPhone();
        layoutMenu();
        invalidate();
    }

    private void layoutPhone() {
        float pad = 6 * dp;
        float dispH = Math.min(46 * dp, body.height() * 0.2f);
        phoneDisplay.set(pad, body.top + pad, body.width() - pad, body.top + pad + dispH);
        phoneDel.set(phoneDisplay.right - dispH * 1.4f, phoneDisplay.top, phoneDisplay.right, phoneDisplay.bottom);
        float top = phoneDisplay.bottom + pad;
        // keep the keypad phone-shaped: limit width on wide screens
        float kw = Math.min((body.width() - pad * 4) / 3f, 150 * dp);
        float kh = (body.bottom - top - pad * 4) / 4f;
        float left = (body.width() - (kw * 3 + pad * 2)) / 2f;
        for (int i = 0; i < 12; i++) {
            int r = i / 3, c = i % 3;
            float x = left + c * (kw + pad), y = top + r * (kh + pad);
            phoneRects[i].set(x, y, x + kw, y + kh);
        }
    }

    private void layoutBar() {
        bar.clear();
        float tabW = mode == MODE_KEYS ? 1.0f : 1.35f;
        bar.add(new Btn(B_DRUM, tabW));
        bar.add(new Btn(B_KEYS, tabW));
        bar.add(new Btn(B_PHONE, tabW));
        bar.add(new Btn(B_TYPE, tabW));
        if (mode == MODE_KEYS) {
            bar.add(new Btn(B_OCT_DN, 0.75f));
            bar.add(new Btn(B_OCT, 0.85f));
            bar.add(new Btn(B_OCT_UP, 0.75f));
            if (isPiano()) bar.add(new Btn(B_SUS, 0.95f));
        }
        bar.add(new Btn(B_MENU, 0.85f));
        if (mode != MODE_TYPE) bar.add(new Btn(B_TXT, 0.85f));
        bar.add(new Btn(B_GLOBE, 0.85f));
        bar.add(new Btn(B_HIDE, 0.75f));
        float total = 0;
        for (Btn b : bar) total += b.weight;
        float pad = 3 * dp, x = pad;
        float unit = (getWidth() - pad * 2) / total;
        for (Btn b : bar) {
            b.r.set(x + pad * 0.5f, pad * 1.5f, x + b.weight * unit - pad * 0.5f, barH - pad * 1.5f);
            x += b.weight * unit;
        }
    }

    private void layoutPads() {
        float gap = 5 * dp;
        float pw = (body.width() - gap * 5) / 4f;
        float ph = (body.height() - gap * 4) / 3f;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 4; c++) {
                float l = gap + c * (pw + gap), t = body.top + gap + r * (ph + gap);
                pads[r * 4 + c].set(l, t, l + pw, t + ph);
            }
    }

    private void layoutKeys() {
        keys.clear();
        int whites = WIDTH_WHITES[widthIdx];
        float kw = body.width() / whites;
        int[] whiteSemis = {0, 2, 4, 5, 7, 9, 11};
        int base = 12 * (octave + 1);
        for (int i = 0; i < whites; i++) {
            Key k = new Key();
            k.midi = base + 12 * (i / 7) + whiteSemis[i % 7];
            k.r.set(body.left + i * kw, body.top + 2 * dp, body.left + (i + 1) * kw, body.bottom);
            keys.add(k);
        }
        float bh = body.height() * 0.6f, bw = kw * 0.62f;
        for (int i = 0; i < whites - 1; i++) {
            int s = i % 7;
            if (s == 2 || s == 6) continue; // no black after E, B
            Key k = new Key();
            k.black = true;
            k.midi = base + 12 * (i / 7) + whiteSemis[s] + 1;
            float cx = body.left + (i + 1) * kw;
            k.r.set(cx - bw / 2, body.top + 2 * dp, cx + bw / 2, body.top + bh);
            keys.add(k);
        }
    }

    // ------------------------------------------------------------------ typing layout
    private void addRow(String[] codes, float[] weights, float top, float h) {
        float total = 0;
        for (float f : weights) total += f;
        float u = body.width() / total, x = 0;
        for (int i = 0; i < codes.length; i++) {
            TKey k = new TKey(codes[i]);
            k.r.set(x, top, x + weights[i] * u, top + h);
            x += weights[i] * u;
            tkeys.add(k);
        }
    }

    private static float[] ones(int n) {
        float[] f = new float[n];
        java.util.Arrays.fill(f, 1f);
        return f;
    }

    private void layoutTyping() {
        tkeys.clear();
        float gap = 3 * dp;
        float rowH = (body.height() - gap) / 4f;
        float y0 = body.top + gap;
        if (page == PAGE_NUM) {
            // phone keypad (DTMF) with side columns
            float[] w = {1.1f, 1.6f, 1.6f, 1.6f, 1.1f};
            addRow(new String[]{"SYM2", "1", "2", "3", "DEL"}, w, y0, rowH);
            addRow(new String[]{"-", "4", "5", "6", "SPACE"}, w, y0 + rowH, rowH);
            addRow(new String[]{",", "7", "8", "9", "."}, w, y0 + rowH * 2, rowH);
            addRow(new String[]{"ALPHA", "*", "0", "#", "ENTER"}, w, y0 + rowH * 3, rowH);
            return;
        }
        if (page == PAGE_SYM) {
            addRow(new String[]{"@", "#", "%", "&", "*", "-", "+", "=", "(", ")"}, ones(10), y0, rowH);
            addRow(kanaLang ? new String[]{"!", "?", "「", "」", ":", ";", "/", "・", "…", "~"}
                            : new String[]{"!", "?", "'", "\"", ":", ";", "/", "_", "\\", "~"}, ones(10), y0 + rowH, rowH);
            addRow(new String[]{"[", "]", "{", "}", "<", ">", "^", "|", "DEL"},
                    new float[]{1, 1, 1, 1, 1, 1, 1, 1, 2}, y0 + rowH * 2, rowH);
            addRow(new String[]{"ALPHA", "NUM", kanaLang ? "、" : ",", "SPACE", kanaLang ? "。" : ".", "ENTER"},
                    new float[]{1.3f, 1.1f, 1f, 3.7f, 1f, 1.9f}, y0 + rowH * 3, rowH);
            return;
        }
        // letters: rows 0-2 keep 10-key unit width, centred
        String[][] rows = {
                {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
                {"SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL"}};
        float unit = body.width() / 10f;
        for (int r = 0; r < 3; r++) {
            String[] row = rows[r];
            float[] w = new float[row.length];
            float total = 0;
            for (int i = 0; i < row.length; i++) {
                w[i] = (row[i].length() > 1) ? 1.5f : 1f;
                total += w[i];
            }
            float x = (body.width() - total * unit) / 2f;
            float top = y0 + r * rowH;
            for (int i = 0; i < row.length; i++) {
                TKey k = new TKey(row[i]);
                k.r.set(x, top, x + w[i] * unit, top + rowH);
                x += w[i] * unit;
                tkeys.add(k);
            }
        }
        if (kanaLang)
            addRow(new String[]{"NUM", "LANG", "、", "SPACE", "ー", "。", "ENTER"},
                    new float[]{1.3f, 1.1f, 0.9f, 3.0f, 0.9f, 0.9f, 1.9f}, y0 + rowH * 3, rowH);
        else
            addRow(new String[]{"NUM", "LANG", ",", "SPACE", ".", "ENTER"},
                    new float[]{1.3f, 1.1f, 1f, 3.7f, 1f, 1.9f}, y0 + rowH * 3, rowH);
    }

    // ------------------------------------------------------------------ menu layout
    private static final String[] MENU_ROWS = {"音色", "幅", "表示", "タイプ音", "DTMF"};

    private String[] menuOptions(int row) {
        switch (row) {
            case 0: return SOUNDS;
            case 1: return WIDTHS;
            case 2: return LABELS;
            case 3: return SE_VOLS;
            default: return ONOFF;
        }
    }

    private int menuSelected(int row) {
        switch (row) {
            case 0: return sound;
            case 1: return widthIdx;
            case 2: return labelIdx;
            case 3: return seVolIdx;
            default: return dtmf ? 0 : 1;
        }
    }

    private void layoutMenu() {
        opts.clear();
        float pad = 6 * dp;
        float labelW = 64 * dp;
        float rowH = (body.height() - pad * 2) / MENU_ROWS.length;
        for (int r = 0; r < MENU_ROWS.length; r++) {
            String[] o = menuOptions(r);
            float top = body.top + pad + r * rowH;
            float x0 = labelW, w = (body.width() - labelW - pad) / o.length;
            for (int i = 0; i < o.length; i++) {
                Opt op = new Opt(r, i);
                op.r.set(x0 + i * w + 2 * dp, top + 3 * dp, x0 + (i + 1) * w - 2 * dp, top + rowH - 3 * dp);
                opts.add(op);
            }
        }
    }

    private void pressOpt(Opt o) {
        switch (o.row) {
            case 0:
                if (o.idx != sound) { releaseAll(); sound = o.idx; }
                break;
            case 1:
                releaseAll();
                widthIdx = o.idx;
                octave = clamp(octave, 1, maxOctave());
                break;
            case 2: labelIdx = o.idx; break;
            case 3:
                if (o.idx == SE_VOLS.length - 1) { // 🎲 reshuffle
                    seSeed = new Random().nextInt();
                    if (seVolIdx == 0) seVolIdx = 2;
                    synth.se(new Random(seSeed).nextInt(SynthCore.SE_COUNT), 1f, SE_VOL[seVolIdx]);
                } else {
                    seVolIdx = o.idx;
                    if (seVolIdx > 0) synth.se(seFor("a"), seRatio("a"), SE_VOL[seVolIdx]);
                }
                break;
            case 4: dtmf = o.idx == 0; break;
        }
        save();
        relayout();
    }

    // ================================================================== drawing
    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        drawBar(c);
        boolean animating = false;
        if (menuOpen) drawMenu(c);
        else if (mode == MODE_DRUM) animating = drawPads(c);
        else if (mode == MODE_TYPE) drawTyping(c);
        else if (mode == MODE_PHONE) drawPhone(c);
        else drawKeys(c, !isPiano());
        if (animating) postInvalidateOnAnimation();
    }

    private void centerText(Canvas c, String s, float cx, float cy) {
        c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text);
    }

    private String label(Btn b) {
        switch (b.id) {
            case B_DRUM: return "🥁";
            case B_KEYS: return "🎹";
            case B_TYPE: return "⌨";
            case B_PHONE: return "☎";
            case B_OCT_DN: return "◀";
            case B_OCT: return "C" + octave;
            case B_OCT_UP: return "▶";
            case B_SUS: return "SUS";
            case B_MENU: return menuOpen ? "✕" : "⚙";
            case B_TXT: return "あ";
            case B_GLOBE: return "🌐";
            case B_HIDE: return "▼";
        }
        return "";
    }

    private boolean isOn(Btn b) {
        switch (b.id) {
            case B_DRUM: return mode == MODE_DRUM;
            case B_KEYS: return mode == MODE_KEYS;
            case B_TYPE: return mode == MODE_TYPE;
            case B_PHONE: return mode == MODE_PHONE;
            case B_SUS: return sustain;
            case B_TXT: return textOut;
            case B_MENU: return menuOpen;
        }
        return false;
    }

    private void drawBar(Canvas c) {
        fill.setShader(null);
        fill.setColor(BAR_BG);
        c.drawRect(0, 0, getWidth(), barH, fill);
        for (Btn b : bar) {
            boolean on = isOn(b);
            boolean tab = b.id <= B_TYPE || b.id == B_PHONE;
            if (b.id != B_OCT) {
                fill.setColor(on ? (tab ? ACCENT : CYAN) : 0xFF232634);
                c.drawRoundRect(b.r, 8 * dp, 8 * dp, fill);
            }
            text.setTextSize((tab ? 17f : 12.5f) * dp);
            text.setColor(on ? 0xFF0D0E14 : (b.id == B_OCT ? CYAN : 0xFFDDE0EA));
            centerText(c, label(b), b.r.centerX(), b.r.centerY());
        }
    }

    private void drawMenu(Canvas c) {
        float pad = 6 * dp;
        float rowH = (body.height() - pad * 2) / MENU_ROWS.length;
        text.setTextSize(12.5f * dp);
        for (int r = 0; r < MENU_ROWS.length; r++) {
            text.setColor(0xFF9AA0B8);
            centerText(c, MENU_ROWS[r], 32 * dp, body.top + pad + r * rowH + rowH / 2);
        }
        for (Opt o : opts) {
            boolean sel = menuSelected(o.row) == o.idx;
            boolean action = o.row == 3 && o.idx == SE_VOLS.length - 1;
            fill.setShader(null);
            fill.setColor(sel ? (o.row == 0 ? ACCENT : CYAN) : action ? 0xFF3A2E52 : 0xFF232634);
            c.drawRoundRect(o.r, 8 * dp, 8 * dp, fill);
            String s = menuOptions(o.row)[o.idx];
            text.setTextSize((s.length() > 4 ? 11.5f : 13f) * dp);
            text.setColor(sel ? 0xFF0D0E14 : 0xFFF2F3F8);
            centerText(c, s, o.r.centerX(), o.r.centerY());
        }
    }

    private boolean drawPads(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean anim = false;
        float r = 12 * dp;
        for (int i = 0; i < 12; i++) {
            RectF p = pads[i];
            float flash = Math.max(0f, 1f - (now - padFlash[i]) / 260f);
            if (flash > 0) anim = true;
            int col = padColor[i];
            fill.setShader(null);
            fill.setColor(blend(0xFF1A1C26, col, 0.22f + 0.78f * flash));
            c.drawRoundRect(p, r, r, fill);
            stroke.setColor(col);
            stroke.setStrokeWidth((1.5f + 3f * flash) * dp);
            c.drawRoundRect(p, r, r, stroke);
            text.setColor(flash > 0.4f ? 0xFF0D0E14 : 0xFFF2F3F8);
            text.setTextSize(14 * dp);
            centerText(c, SynthCore.DRUM_NAMES[PAD_MAP[i]], p.centerX(), p.centerY());
        }
        return anim;
    }

    private String noteLabel(int midi) {
        if (labelIdx == 0) return DO_RE_MI[midi % 12];
        if (labelIdx == 1) return NOTE[midi % 12];
        return null;
    }

    private void drawKeys(Canvas c, boolean synthMode) {
        float r = 6 * dp;
        float kwidth = body.width() / WIDTH_WHITES[widthIdx];
        float fs = Math.min(13, Math.max(8, kwidth / dp * 0.32f));
        for (Key k : keys) {
            if (k.black) continue;
            boolean down = heldCount[k.midi] > 0;
            fill.setShader(null);
            if (synthMode) fill.setColor(down ? blend(0xFF1B1E2C, ACCENT, 0.85f) : 0xFF1B1E2C);
            else fill.setColor(down ? 0xFFFFB3D6 : 0xFFF0F0F4);
            tmp.set(k.r.left + 1.5f * dp, k.r.top, k.r.right - 1.5f * dp, k.r.bottom - 2 * dp);
            c.drawRoundRect(tmp, r, r, fill);
            if (synthMode) {
                stroke.setColor(down ? ACCENT : 0xFF2E7F99);
                stroke.setStrokeWidth(1.2f * dp);
                c.drawRoundRect(tmp, r, r, stroke);
            }
            String lb = noteLabel(k.midi);
            if (lb != null) {
                text.setTextSize(fs * dp);
                text.setColor(synthMode ? (down ? 0xFFFFFFFF : 0xFF6FD9F2) : 0xFF5A5D6E);
                c.drawText(lb, tmp.centerX(), tmp.bottom - 10 * dp, text);
            }
            if (k.midi % 12 == 0) {
                text.setTextSize(Math.min(10, fs) * dp);
                text.setColor(synthMode ? 0xFF9AA0B8 : 0xFF9A9DAE);
                c.drawText("C" + (k.midi / 12 - 1), tmp.centerX(), tmp.bottom - (lb != null ? 26 : 10) * dp, text);
            }
        }
        for (Key k : keys) {
            if (!k.black) continue;
            boolean down = heldCount[k.midi] > 0;
            fill.setShader(null);
            fill.setColor(down ? ACCENT : (synthMode ? 0xFF0A0B10 : 0xFF1A1B22));
            c.drawRoundRect(k.r, r * 0.7f, r * 0.7f, fill);
            if (synthMode) {
                stroke.setColor(down ? 0xFFFFFFFF : 0xFF2E7F99);
                stroke.setStrokeWidth(1f * dp);
                c.drawRoundRect(k.r, r * 0.7f, r * 0.7f, stroke);
            }
        }
        if (synthMode) {
            fill.setShader(new LinearGradient(0, body.top, 0, body.bottom, CYAN, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, body.top, 3 * dp, body.bottom, fill);
            fill.setShader(null);
            for (int i = 0; i < touches.size(); i++) {
                Touch t = touches.valueAt(i);
                if (t.type != 1) continue;
                Key k = keyFor(t.midi);
                if (k == null) continue;
                float ly = Math.max(body.top, Math.min(body.bottom, t.y));
                stroke.setColor(0xDDFFFFFF);
                stroke.setStrokeWidth(2.5f * dp);
                c.drawLine(k.r.left + 3 * dp, ly, k.r.right - 3 * dp, ly, stroke);
            }
            // sound name
            text.setTextSize(10 * dp);
            text.setColor(0x996FD9F2);
            c.drawText(SOUNDS[sound], body.right - 26 * dp, body.bottom - 4 * dp, text);
        }
    }

    private static final String[] PHONE_SUB = {"", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};

    private String tLabel(TKey k) {
        switch (k.code) {
            case "SHIFT": return shift == 2 ? "⇪" : "⇧";
            case "DEL": return "⌫";
            case "NUM": return "123";
            case "SYM2": return "#+=";
            case "ALPHA": return kanaLang ? "あいう" : "ABC";
            case "LANG": return kanaLang ? "あ→A" : "A→あ";
            case "SPACE": return kanaLang ? (host.isComposing() ? "カナ" : "空白") : "space";
            case "ENTER": return host.isComposing() ? "確定" : "⏎";
        }
        if (page == PAGE_LETTERS && shift > 0 && k.code.length() == 1) return k.code.toUpperCase();
        return k.code;
    }

    private boolean isDialKey(TKey k) {
        return page == PAGE_NUM && k.code.length() == 1 && "0123456789*#".contains(k.code);
    }

    private void drawTyping(Canvas c) {
        float r = 7 * dp, in = 2.5f * dp;
        for (TKey k : tkeys) {
            boolean down = false;
            for (int i = 0; i < touches.size(); i++) if (touches.valueAt(i).tkey == k) down = true;
            boolean fn = k.code.length() > 1;
            boolean dial = isDialKey(k);
            boolean accent = k.code.equals("ENTER") || (k.code.equals("SHIFT") && shift > 0);
            int col = down ? ACCENT : accent ? 0xFF2A6F86 : dial ? 0xFF3A4058 : fn ? 0xFF262A3A : 0xFF323748;
            fill.setShader(null);
            fill.setColor(col);
            tmp.set(k.r.left + in, k.r.top + in, k.r.right - in, k.r.bottom - in);
            c.drawRoundRect(tmp, r, r, fill);
            String lb = tLabel(k);
            text.setColor(0xFFF2F3F8);
            if (dial) {
                int d = Character.isDigit(k.code.charAt(0)) ? k.code.charAt(0) - '0' : -1;
                String sub = d >= 0 ? (d == 0 ? "+" : PHONE_SUB[d]) : "";
                text.setTextSize(22 * dp);
                float cy = tmp.centerY() - (sub.isEmpty() ? 0 : 6 * dp);
                centerText(c, lb, tmp.centerX(), cy);
                if (!sub.isEmpty()) {
                    text.setTextSize(9.5f * dp);
                    text.setColor(0xFF9AA0B8);
                    centerText(c, sub, tmp.centerX(), cy + 17 * dp);
                }
            } else {
                text.setTextSize((lb.length() > 2 ? 13 : 19) * dp);
                centerText(c, lb, tmp.centerX(), tmp.centerY());
            }
        }
    }

    private void drawPhone(Canvas c) {
        float r = 10 * dp;
        // display
        fill.setShader(null);
        fill.setColor(0xFF0A1A14);
        c.drawRoundRect(phoneDisplay, r, r, fill);
        stroke.setColor(0xFF1F5A44);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawRoundRect(phoneDisplay, r, r, stroke);
        String num = dialed.length() == 0 ? "☎ ダイヤル" : dialed.toString();
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize((dialed.length() == 0 ? 15 : 22) * dp);
        text.setColor(dialed.length() == 0 ? 0xFF2F7A5C : 0xFF7CFFC4);
        float maxW = phoneDel.left - phoneDisplay.left - 16 * dp;
        while (text.measureText(num) > maxW && num.length() > 1) num = num.substring(1);
        c.drawText(num, phoneDel.left - 8 * dp, phoneDisplay.centerY() - (text.descent() + text.ascent()) / 2, text);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(18 * dp);
        text.setColor(0xFF7CFFC4);
        centerText(c, "⌫", phoneDel.centerX(), phoneDel.centerY());
        // keypad
        for (int i = 0; i < 12; i++) {
            RectF k = phoneRects[i];
            boolean down = phoneHeld[i] > 0;
            fill.setColor(down ? 0xFF7CFFC4 : 0xFF2A2E3E);
            c.drawRoundRect(k, r, r, fill);
            if (!down) {
                stroke.setColor(0xFF3C4258);
                c.drawRoundRect(k, r, r, stroke);
            }
            String key = PHONE_KEYS[i];
            int d = Character.isDigit(key.charAt(0)) ? key.charAt(0) - '0' : -1;
            String sub = d >= 0 ? (d == 0 ? "+" : PHONE_SUB[d]) : "";
            float big = Math.min(26 * dp, k.height() * 0.48f);
            text.setTextSize(big);
            text.setColor(down ? 0xFF0D0E14 : 0xFFF2F3F8);
            float cy = k.centerY() - (sub.isEmpty() ? 0 : big * 0.22f);
            centerText(c, key, k.centerX(), cy);
            if (!sub.isEmpty()) {
                text.setTextSize(Math.min(10 * dp, big * 0.42f));
                text.setColor(down ? 0xFF0D0E14 : 0xFF8FA0B8);
                centerText(c, sub, k.centerX(), cy + big * 0.62f);
            }
        }
    }

    private Key keyFor(int midi) {
        for (Key k : keys) if (k.midi == midi) return k;
        return null;
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ================================================================== random SE per key
    private Random keyRandom(String code) {
        return new Random(seSeed * 1000003L + code.toLowerCase().hashCode());
    }

    private int seFor(String code) {
        return keyRandom(code).nextInt(SynthCore.SE_COUNT);
    }

    private float seRatio(String code) {
        Random r = keyRandom(code);
        r.nextInt(SynthCore.SE_COUNT);
        return (float) Math.pow(2.0, (r.nextInt(13) - 5) / 12.0); // -5..+7 semitones
    }

    private void playSE(String code) {
        if (seVolIdx <= 0 || seVolIdx >= SE_VOL.length) return;
        synth.se(seFor(code), seRatio(code), SE_VOL[seVolIdx]);
    }

    // ================================================================== touch
    private Btn hitBar(float x, float y) {
        if (y > barH) return null;
        Btn best = null;
        for (Btn b : bar) if (x >= b.r.left - 3 * dp && x <= b.r.right + 3 * dp) best = b;
        return best;
    }

    private Key hitKey(float x, float y) {
        if (y < body.top) return null;
        for (Key k : keys) if (k.black && k.r.contains(x, y)) return k;
        for (Key k : keys) if (!k.black && x >= k.r.left && x < k.r.right) return k;
        return null;
    }

    private int hitPad(float x, float y) {
        for (int i = 0; i < 12; i++) if (pads[i].contains(x, y)) return i;
        return -1;
    }

    private TKey hitTKey(float x, float y) {
        TKey best = null;
        float bd = Float.MAX_VALUE;
        for (TKey k : tkeys) {
            if (k.r.contains(x, y)) return k;
            float dx = Math.max(0, Math.max(k.r.left - x, x - k.r.right));
            float dy = Math.max(0, Math.max(k.r.top - y, y - k.r.bottom));
            float d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = k; }
        }
        return bd < (12 * dp) * (12 * dp) ? best : null;
    }

    private float brightFromY(float y) {
        float f = (y - body.top) / body.height();
        return Math.max(0.05f, Math.min(1f, 1f - f * 0.95f));
    }

    private float velFromY(Key k, float y) {
        float f = (y - k.r.top) / k.r.height();
        return Math.max(0.3f, Math.min(1f, 0.35f + 0.7f * f));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                down(e.getPointerId(idx), e.getX(idx), e.getY(idx));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                up(e.getPointerId(idx), false);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) up(e.getPointerId(i), true);
                break;
        }
        return true;
    }

    private void down(int id, float x, float y) {
        Touch t = new Touch();
        Btn b = hitBar(x, y);
        if (b != null) {
            t.type = 3;
            t.btn = b.id;
            touches.put(id, t);
            if (b.id == B_GLOBE) {
                globeLongDone = false;
                postDelayed(globeLong, 450);
            } else {
                pressButton(b.id);
            }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            return;
        }
        if (menuOpen) {
            for (Opt o : opts) {
                if (o.r.contains(x, y)) {
                    t.type = 5;
                    touches.put(id, t);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    pressOpt(o);
                    return;
                }
            }
            return;
        }
        if (mode == MODE_PHONE) {
            if (phoneDel.contains(x, y) || (phoneDisplay.contains(x, y) && dialed.length() > 0)) {
                if (phoneDel.contains(x, y)) {
                    if (dialed.length() > 0) dialed.setLength(dialed.length() - 1);
                    if (textOut) host.onTypeKey("DEL", false);
                } else {
                    dialed.setLength(0); // tap the display to clear
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return;
            }
            for (int i = 0; i < 12; i++) {
                if (!phoneRects[i].contains(x, y)) continue;
                t.type = 6;
                t.midi = i;
                t.voiceKey = synth.newKey();
                touches.put(id, t);
                synth.dtmfOn(t.voiceKey, PHONE_KEYS[i].charAt(0), 1f);
                phoneHeld[i]++;
                if (dialed.length() > 40) dialed.delete(0, dialed.length() - 40);
                dialed.append(PHONE_KEYS[i]);
                if (textOut) host.onCommit(PHONE_KEYS[i]);
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return;
            }
            return;
        }
        if (mode == MODE_TYPE) {
            TKey k = hitTKey(x, y);
            if (k == null) return;
            t.type = 4;
            t.tkey = k;
            touches.put(id, t);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (isDialKey(k)) {
                if (dtmf) {
                    t.voiceKey = synth.newKey();
                    synth.dtmfOn(t.voiceKey, k.code.charAt(0), 1f);
                }
            } else {
                playSE(k.code);
            }
            pressTKey(k);
            return;
        }
        if (mode == MODE_DRUM) {
            int p = hitPad(x, y);
            if (p < 0) return;
            RectF r = pads[p];
            float dx = (x - r.centerX()) / (r.width() / 2), dy = (y - r.centerY()) / (r.height() / 2);
            float vel = 1f - 0.4f * Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy));
            int drum = PAD_MAP[p];
            synth.drum(drum, vel);
            padFlash[p] = SystemClock.uptimeMillis();
            t.type = 2;
            touches.put(id, t);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (textOut) host.onCommit(DRUM_TEXT[drum]);
            invalidate();
            return;
        }
        Key k = hitKey(x, y);
        if (k == null) return;
        t.type = 1;
        t.midi = k.midi;
        t.voiceKey = synth.newKey();
        t.y = y;
        touches.put(id, t);
        startNote(t, k, y);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }

    private void startNote(Touch t, Key k, float y) {
        if (isPiano()) synth.pianoOn(t.voiceKey, k.midi, velFromY(k, y));
        else synth.synthOn(t.voiceKey, k.midi, 0.85f, sound - 1, brightFromY(y));
        heldCount[k.midi]++;
        if (textOut) host.onCommit(DO_RE_MI[k.midi % 12]);
    }

    private void move(int id, float x, float y) {
        Touch t = touches.get(id);
        if (t == null || t.type != 1) return;
        Key k = hitKey(x, y);
        if (!isPiano()) {
            t.y = y;
            synth.setBright(t.voiceKey, brightFromY(Math.max(body.top, y)));
            if (k != null && k.midi != t.midi) { // legato glide
                heldCount[t.midi]--;
                t.midi = k.midi;
                heldCount[t.midi]++;
                synth.setPitch(t.voiceKey, k.midi);
                if (textOut) host.onCommit(DO_RE_MI[k.midi % 12]);
            }
            invalidate();
        } else if (k != null && k.midi != t.midi) { // glissando
            synth.noteOff(t.voiceKey);
            heldCount[t.midi]--;
            t.midi = k.midi;
            t.voiceKey = synth.newKey();
            startNote(t, k, y);
            invalidate();
        }
    }

    private void up(int id, boolean cancel) {
        Touch t = touches.get(id);
        if (t == null) return;
        touches.remove(id);
        if (t.type == 1) {
            synth.noteOff(t.voiceKey);
            heldCount[t.midi] = Math.max(0, heldCount[t.midi] - 1);
            invalidate();
        } else if (t.type == 6) {
            synth.noteOff(t.voiceKey);
            phoneHeld[t.midi] = Math.max(0, phoneHeld[t.midi] - 1);
            invalidate();
        } else if (t.type == 4) {
            if (t.voiceKey != 0) synth.noteOff(t.voiceKey);
            if (t.tkey != null && t.tkey == delKey) { removeCallbacks(delRepeat); delKey = null; }
            invalidate();
        } else if (t.type == 3 && t.btn == B_GLOBE) {
            removeCallbacks(globeLong);
            if (!globeLongDone && !cancel) host.onNextIme();
        }
    }

    private void pressTKey(TKey k) {
        switch (k.code) {
            case "SHIFT": {
                long now = SystemClock.uptimeMillis();
                if (shift == 0) shift = (now - lastShiftTap < 300) ? 2 : 1;
                else if (shift == 1 && now - lastShiftTap < 300) shift = 2;
                else shift = 0;
                lastShiftTap = now;
                break;
            }
            case "NUM": setPage(PAGE_NUM); break;
            case "SYM2": setPage(PAGE_SYM); break;
            case "ALPHA": setPage(PAGE_LETTERS); break;
            case "LANG":
                host.onTypeKey("FLUSH", kanaLang);
                kanaLang = !kanaLang;
                prefs.edit().putBoolean("kana", kanaLang).apply();
                layoutTyping();
                break;
            case "DEL":
                host.onTypeKey("DEL", kanaLang);
                delKey = k;
                postDelayed(delRepeat, 420);
                break;
            case "SPACE": case "ENTER":
                host.onTypeKey(k.code, kanaLang);
                break;
            default: {
                String out = k.code;
                if (page == PAGE_LETTERS && shift > 0) out = out.toUpperCase();
                host.onTypeKey(out, kanaLang && page == PAGE_LETTERS);
                if (shift == 1) shift = 0;
            }
        }
        invalidate();
    }

    private void setPage(int p) {
        page = p;
        shift = 0;
        layoutTyping();
    }

    private void pressButton(int id) {
        switch (id) {
            case B_DRUM: setMode(MODE_DRUM); break;
            case B_KEYS: setMode(MODE_KEYS); break;
            case B_TYPE: setMode(MODE_TYPE); break;
            case B_PHONE: setMode(MODE_PHONE); break;
            case B_OCT_DN: if (octave > 1) { octave--; releaseAll(); save(); relayout(); } break;
            case B_OCT_UP: if (octave < maxOctave()) { octave++; releaseAll(); save(); relayout(); } break;
            case B_SUS: sustain = !sustain; synth.setSustain(sustain); break;
            case B_MENU: menuOpen = !menuOpen; releaseAll(); relayout(); break;
            case B_TXT: textOut = !textOut; save(); break;
            case B_HIDE: host.onHide(); break;
        }
        invalidate();
    }

    private void setMode(int m) {
        menuOpen = false;
        if (m == mode) { relayout(); return; }
        releaseAll();
        if (mode == MODE_TYPE) host.onTypeKey("FLUSH", kanaLang);
        mode = m;
        save();
        relayout();
    }

    /** Called when a new text field gets focus. */
    public void onStartInput(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int var = inputType & InputType.TYPE_MASK_VARIATION;
        kanaLang = prefs.getBoolean("kana", true);
        page = PAGE_LETTERS;
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME) {
            page = PAGE_NUM;
        } else if (cls == InputType.TYPE_CLASS_TEXT && (
                var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                        || var == InputType.TYPE_TEXT_VARIATION_URI)) {
            kanaLang = false;
        }
        shift = 0;
        layoutTyping();
        invalidate();
    }

    /** Stop every sounding note (used on mode change / when keyboard hides). */
    public void releaseAll() {
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if ((t.type == 1 || t.type == 4 || t.type == 6) && t.voiceKey != 0) synth.noteOff(t.voiceKey);
        }
        touches.clear();
        removeCallbacks(delRepeat);
        delKey = null;
        for (int i = 0; i < heldCount.length; i++) heldCount[i] = 0;
        for (int i = 0; i < phoneHeld.length; i++) phoneHeld[i] = 0;
        if (sustain) { sustain = false; synth.setSustain(false); }
        invalidate();
    }
}
