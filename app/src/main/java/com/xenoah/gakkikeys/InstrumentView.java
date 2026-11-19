package com.xenoah.gakkikeys;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** The whole keyboard: top bar + drum pads / piano keys / synth keys, all multi-touch. */
public final class InstrumentView extends View {

    public interface Host {
        void onNextIme();
        void onPickIme();
        void onHide();
        void onCommit(String text);
        /** Typing-keyboard key. kana = romaji-kana mode. */
        void onTypeKey(String code, boolean kana);
        boolean isComposing();
        void onModeChanged(boolean instrument);
    }

    private static final String[] DO_RE_MI = {"ド", "ド#", "レ", "レ#", "ミ", "ファ", "ファ#", "ソ", "ソ#", "ラ", "ラ#", "シ"};
    private static final String[] NOTE = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] DRUM_TEXT = {"ドン", "タン", "パン", "カッ", "ツ", "シャー", "ジャーン", "カン", "トン", "トン", "タン", "ブーン"};
    /** Pad layout (row-major, 4x3) -> drum index. Bottom row = kick/snare like an MPC. */
    private static final int[] PAD_MAP = {
            SynthCore.CRASH, SynthCore.OHAT, SynthCore.CHAT, SynthCore.COWBELL,
            SynthCore.TOM_H, SynthCore.TOM_M, SynthCore.TOM_L, SynthCore.BASS808,
            SynthCore.KICK, SynthCore.SNARE, SynthCore.CLAP, SynthCore.RIM};

    private static final int MODE_DRUM = 0, MODE_PIANO = 1, MODE_SYNTH = 2, MODE_TYPE = 3;

    // bar button ids
    private static final int B_DRUM = 1, B_PIANO = 2, B_SYNTH = 3, B_OCT_DN = 4, B_OCT = 5, B_OCT_UP = 6,
            B_EXTRA = 7, B_TXT = 8, B_GLOBE = 9, B_HIDE = 10, B_TYPE = 11;

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
        final float w;
        TKey(String code, float w) { this.code = code; this.w = w; }
    }

    private static final class Touch {
        int type; // 1 note, 2 drum, 3 button
        int midi;
        int voiceKey;
        int pad;
        int btn;
        float y;
        TKey tkey;
    }

    private final Host host;
    private final SynthCore synth;
    private final SharedPreferences prefs;
    private final float dp;

    private int mode;
    private int octave; // C of lowest key = octave (C4 = 4)
    private int preset;
    private boolean sustain;
    private boolean textOut;
    private boolean kanaLang = true;
    private boolean symPage;
    private int shift; // 0 off, 1 once, 2 caps lock
    private long lastShiftTap;
    private final List<TKey> tkeys = new ArrayList<>();
    private TKey delKey;

    private final List<Btn> bar = new ArrayList<>();
    private final List<Key> keys = new ArrayList<>();
    private final RectF[] pads = new RectF[12];
    private final long[] padFlash = new long[12];
    private final int[] padColor = new int[12];
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final int[] heldCount = new int[128];

    private float barH;
    private final RectF body = new RectF();
    private long globeDownAt;
    private boolean globeLongDone;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int BG = 0xFF0D0E14;
    private static final int BAR_BG = 0xFF161822;
    private static final int ACCENT = 0xFFFF4FA3;
    private static final int CYAN = 0xFF38E1FF;

    private final Runnable delRepeat = new Runnable() {
        @Override public void run() {
            if (delKey == null) return;
            host.onTypeKey("DEL", kanaLang);
            invalidate();
            postDelayed(this, 55);
        }
    };

    private final Runnable globeLong = new Runnable() {
        @Override public void run() {
            globeLongDone = true;
            host.onPickIme();
        }
    };

    public InstrumentView(Context ctx, SynthCore synth, Host host) {
        super(ctx);
        this.host = host;
        this.synth = synth;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        dp = dm.density;
        prefs = ctx.getSharedPreferences("gakki", Context.MODE_PRIVATE);
        mode = prefs.getInt("mode", MODE_PIANO);
        octave = prefs.getInt("octave", 4);
        preset = prefs.getInt("preset", 0);
        textOut = prefs.getBoolean("text", false);
        kanaLang = prefs.getBoolean("kana", true);
        if (mode < MODE_DRUM || mode > MODE_TYPE) mode = MODE_PIANO;
        for (int i = 0; i < 12; i++) {
            pads[i] = new RectF();
            padColor[i] = Color.HSVToColor(new float[]{(300f + i * 27f) % 360f, 0.75f, 1f});
        }
        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setHapticFeedbackEnabled(true);
    }

    private void save() {
        prefs.edit().putInt("mode", mode).putInt("octave", octave).putInt("preset", preset)
                .putBoolean("text", textOut).apply();
    }

    // ------------------------------------------------------------------ layout
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

        bar.clear();
        float tabW = (mode == MODE_DRUM || mode == MODE_TYPE) ? 1.4f : 1.0f;
        bar.add(new Btn(B_DRUM, tabW));
        bar.add(new Btn(B_PIANO, tabW));
        bar.add(new Btn(B_SYNTH, tabW));
        bar.add(new Btn(B_TYPE, tabW));
        if (mode == MODE_PIANO || mode == MODE_SYNTH) {
            bar.add(new Btn(B_OCT_DN, 0.8f));
            bar.add(new Btn(B_OCT, 0.9f));
            bar.add(new Btn(B_OCT_UP, 0.8f));
            bar.add(new Btn(B_EXTRA, 1.15f));
        }
        if (mode != MODE_TYPE) bar.add(new Btn(B_TXT, 0.9f));
        bar.add(new Btn(B_GLOBE, 0.9f));
        bar.add(new Btn(B_HIDE, 0.8f));
        float total = 0;
        for (Btn b : bar) total += b.weight;
        float pad = 3 * dp, x = pad;
        float unit = (w - pad * 2) / total;
        for (Btn b : bar) {
            b.r.set(x + pad * 0.5f, pad * 1.5f, x + b.weight * unit - pad * 0.5f, barH - pad * 1.5f);
            x += b.weight * unit;
        }

        // drum pads 4x3
        float gap = 5 * dp;
        float pw = (body.width() - gap * 5) / 4f;
        float ph = (body.height() - gap * 4) / 3f;
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 4; c++) {
                float l = gap + c * (pw + gap), t = body.top + gap + r * (ph + gap);
                pads[r * 4 + c].set(l, t, l + pw, t + ph);
            }

        // keys: 14 white keys (2 octaves); fewer on narrow screens
        keys.clear();
        int whites = w / dp < 330 ? 10 : 14;
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
        layoutTyping();
        invalidate();
    }

    // ------------------------------------------------------------------ typing keyboard
    private void layoutTyping() {
        tkeys.clear();
        String[][] rows;
        if (symPage) {
            rows = new String[][]{
                    {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
                    kanaLang ? new String[]{"@", "#", "%", "&", "*", "-", "+", "=", "「", "」"}
                             : new String[]{"@", "#", "%", "&", "*", "-", "+", "=", "(", ")"},
                    kanaLang ? new String[]{"!", "?", "~", ":", ";", "/", "・", "…", "DEL"}
                             : new String[]{"!", "?", "'", "\"", ":", ";", "/", "_", "DEL"}};
        } else {
            rows = new String[][]{
                    {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
                    {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
                    {"SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL"}};
        }
        float gap = 3 * dp;
        float rowH = (body.height() - gap) / 4f;
        float unit = body.width() / 10f;
        for (int r = 0; r < 3; r++) {
            String[] row = rows[r];
            float total = 0;
            float[] w = new float[row.length];
            for (int i = 0; i < row.length; i++) {
                w[i] = (row[i].equals("SHIFT") || row[i].equals("DEL")) ? 1.5f : 1f;
                if (symPage && r == 2 && row[i].equals("DEL")) w[i] = 2f;
                total += w[i];
            }
            float x = (body.width() - total * unit) / 2f;
            float top = body.top + gap + r * rowH;
            for (int i = 0; i < row.length; i++) {
                TKey k = new TKey(row[i], w[i]);
                k.r.set(x, top, x + w[i] * unit, top + rowH);
                x += w[i] * unit;
                tkeys.add(k);
            }
        }
        String[] bottom = kanaLang
                ? new String[]{"SYM", "LANG", "、", "SPACE", "ー", "。", "ENTER"}
                : new String[]{"SYM", "LANG", ",", "SPACE", ".", "ENTER"};
        float[] bw = kanaLang ? new float[]{1.3f, 1.1f, 0.9f, 3.0f, 0.9f, 0.9f, 1.9f}
                              : new float[]{1.3f, 1.1f, 1f, 3.7f, 1f, 1.9f};
        float total = 0;
        for (float f : bw) total += f;
        float x = 0, top = body.top + gap + 3 * rowH, u = body.width() / total;
        for (int i = 0; i < bottom.length; i++) {
            TKey k = new TKey(bottom[i], bw[i]);
            k.r.set(x, top, x + bw[i] * u, top + rowH);
            x += bw[i] * u;
            tkeys.add(k);
        }
    }

    private String tLabel(TKey k) {
        switch (k.code) {
            case "SHIFT": return shift == 2 ? "⇪" : "⇧";
            case "DEL": return "⌫";
            case "SYM": return symPage ? (kanaLang ? "あいう" : "ABC") : "123";
            case "LANG": return kanaLang ? "あ→A" : "A→あ";
            case "SPACE": return kanaLang ? (host.isComposing() ? "カナ" : "空白") : "space";
            case "ENTER": return host.isComposing() ? "確定" : "⏎";
        }
        if (!symPage && shift > 0 && k.code.length() == 1) return k.code.toUpperCase();
        return k.code;
    }

    private void drawTyping(Canvas c) {
        float r = 7 * dp, in = 2.5f * dp;
        for (TKey k : tkeys) {
            boolean down = false;
            for (int i = 0; i < touches.size(); i++) if (touches.valueAt(i).tkey == k) down = true;
            boolean fn = k.code.length() > 1;
            boolean accent = k.code.equals("ENTER") || (k.code.equals("SHIFT") && shift > 0);
            int col = down ? ACCENT : accent ? 0xFF2A6F86 : fn ? 0xFF262A3A : 0xFF323748;
            fill.setShader(null);
            fill.setColor(col);
            RectF kr = new RectF(k.r.left + in, k.r.top + in, k.r.right - in, k.r.bottom - in);
            c.drawRoundRect(kr, r, r, fill);
            String lb = tLabel(k);
            text.setColor(0xFFF2F3F8);
            text.setTextSize((lb.length() > 2 ? 13 : 19) * dp);
            float y = kr.centerY() - (text.descent() + text.ascent()) / 2;
            c.drawText(lb, kr.centerX(), y, text);
        }
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
            case "SYM": symPage = !symPage; layoutTyping(); break;
            case "LANG":
                host.onTypeKey("FLUSH", kanaLang);
                kanaLang = !kanaLang; symPage = false;
                prefs.edit().putBoolean("kana", kanaLang).apply();
                layoutTyping(); break;
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
                if (!symPage && shift > 0) out = out.toUpperCase();
                host.onTypeKey(out, kanaLang && !symPage);
                if (shift == 1) shift = 0;
            }
        }
        invalidate();
    }

    /** Called when a new text field gets focus. */
    public void onStartInput(int inputType) {
        int cls = inputType & android.text.InputType.TYPE_MASK_CLASS;
        int var = inputType & android.text.InputType.TYPE_MASK_VARIATION;
        if (mode != MODE_TYPE) return;
        if (cls == android.text.InputType.TYPE_CLASS_NUMBER || cls == android.text.InputType.TYPE_CLASS_PHONE
                || cls == android.text.InputType.TYPE_CLASS_DATETIME) {
            symPage = true;
        } else if (cls == android.text.InputType.TYPE_CLASS_TEXT && (
                var == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || var == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == android.text.InputType.TYPE_TEXT_VARIATION_URI)) {
            kanaLang = false;
            symPage = false;
        } else {
            kanaLang = prefs.getBoolean("kana", true);
            symPage = false;
        }
        shift = 0;
        layoutTyping();
        invalidate();
    }

    public boolean isInstrumentMode() {
        return mode != MODE_TYPE;
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        drawBar(c);
        boolean animating = false;
        if (mode == MODE_DRUM) animating = drawPads(c);
        else if (mode == MODE_TYPE) drawTyping(c);
        else drawKeys(c, mode == MODE_SYNTH);
        if (animating) postInvalidateOnAnimation();
    }

    private String label(Btn b) {
        switch (b.id) {
            case B_DRUM: return "🥁";
            case B_PIANO: return "🎹";
            case B_SYNTH: return "🎛";
            case B_TYPE: return "⌨";
            case B_OCT_DN: return "◀";
            case B_OCT: return "C" + octave;
            case B_OCT_UP: return "▶";
            case B_EXTRA: return mode == MODE_PIANO ? "SUS" : SynthCore.SYNTH_PRESETS[preset];
            case B_TXT: return "あ";
            case B_GLOBE: return "🌐";
            case B_HIDE: return "▼";
        }
        return "";
    }

    private boolean isOn(Btn b) {
        switch (b.id) {
            case B_DRUM: return mode == MODE_DRUM;
            case B_PIANO: return mode == MODE_PIANO;
            case B_SYNTH: return mode == MODE_SYNTH;
            case B_TYPE: return mode == MODE_TYPE;
            case B_EXTRA: return mode == MODE_PIANO && sustain;
            case B_TXT: return textOut;
        }
        return false;
    }

    private void drawBar(Canvas c) {
        fill.setShader(null);
        fill.setColor(BAR_BG);
        c.drawRect(0, 0, getWidth(), barH, fill);
        text.setTextSize(12.5f * dp);
        for (Btn b : bar) {
            boolean on = isOn(b);
            boolean tab = b.id <= B_SYNTH || b.id == B_TYPE;
            if (b.id != B_OCT) {
                fill.setColor(on ? (tab ? ACCENT : CYAN) : 0xFF232634);
                c.drawRoundRect(b.r, 8 * dp, 8 * dp, fill);
            }
            text.setTextSize((tab ? 17f : 12.5f) * dp);
            text.setColor(on ? 0xFF0D0E14 : (b.id == B_OCT ? CYAN : 0xFFDDE0EA));
            if (b.id == B_EXTRA && mode == MODE_SYNTH) text.setColor(CYAN);
            float y = b.r.centerY() - (text.descent() + text.ascent()) / 2;
            c.drawText(label(b), b.r.centerX(), y, text);
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
            int dim = blend(0xFF1A1C26, col, 0.22f + 0.78f * flash);
            fill.setColor(dim);
            c.drawRoundRect(p, r, r, fill);
            stroke.setColor(col);
            stroke.setStrokeWidth((1.5f + 3f * flash) * dp);
            c.drawRoundRect(p, r, r, stroke);
            text.setColor(flash > 0.4f ? 0xFF0D0E14 : 0xFFF2F3F8);
            text.setTextSize(14 * dp);
            float y = p.centerY() - (text.descent() + text.ascent()) / 2;
            c.drawText(SynthCore.DRUM_NAMES[PAD_MAP[i]], p.centerX(), y, text);
        }
        return anim;
    }

    private void drawKeys(Canvas c, boolean synthMode) {
        float r = 6 * dp;
        float h = body.height();
        for (Key k : keys) {
            if (k.black) continue;
            boolean down = heldCount[k.midi] > 0;
            if (synthMode) {
                fill.setShader(null);
                fill.setColor(down ? blend(0xFF1B1E2C, ACCENT, 0.85f) : 0xFF1B1E2C);
            } else {
                fill.setShader(null);
                fill.setColor(down ? 0xFFFFB3D6 : 0xFFF0F0F4);
            }
            RectF kr = new RectF(k.r.left + 1.5f * dp, k.r.top, k.r.right - 1.5f * dp, k.r.bottom - 2 * dp);
            c.drawRoundRect(kr, r, r, fill);
            if (synthMode) {
                stroke.setColor(down ? ACCENT : 0xFF2E7F99);
                stroke.setStrokeWidth(1.2f * dp);
                c.drawRoundRect(kr, r, r, stroke);
            }
            int pc = k.midi % 12;
            text.setTextSize(11 * dp);
            text.setColor(synthMode ? (down ? 0xFFFFFFFF : 0xFF6FD9F2) : 0xFF5A5D6E);
            c.drawText(DO_RE_MI[pc], kr.centerX(), kr.bottom - 10 * dp, text);
            if (pc == 0) {
                text.setTextSize(10 * dp);
                text.setColor(synthMode ? 0xFF9AA0B8 : 0xFF9A9DAE);
                c.drawText("C" + (k.midi / 12 - 1), kr.centerX(), kr.bottom - 26 * dp, text);
            }
        }
        for (Key k : keys) {
            if (!k.black) continue;
            boolean down = heldCount[k.midi] > 0;
            fill.setShader(null);
            if (synthMode) fill.setColor(down ? ACCENT : 0xFF0A0B10);
            else fill.setColor(down ? ACCENT : 0xFF1A1B22);
            c.drawRoundRect(k.r, r * 0.7f, r * 0.7f, fill);
            if (synthMode) {
                stroke.setColor(down ? 0xFFFFFFFF : 0xFF2E7F99);
                stroke.setStrokeWidth(1f * dp);
                c.drawRoundRect(k.r, r * 0.7f, r * 0.7f, stroke);
            }
        }
        if (synthMode) {
            // brightness hint gradient on the left edge
            fill.setShader(new LinearGradient(0, body.top, 0, body.bottom, CYAN, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, body.top, 3 * dp, body.bottom, fill);
            fill.setShader(null);
            // finger lines
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

    // ------------------------------------------------------------------ touch
    private Btn hitBar(float x, float y) {
        if (y > barH) return null;
        Btn best = null;
        for (Btn b : bar) {
            if (x >= b.r.left - 3 * dp && x <= b.r.right + 3 * dp) best = b;
        }
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
        int action = e.getActionMasked();
        switch (action) {
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
                up(e.getPointerId(idx), e.getX(idx), e.getY(idx), false);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) up(e.getPointerId(i), 0, 0, true);
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
                globeDownAt = SystemClock.uptimeMillis();
                globeLongDone = false;
                postDelayed(globeLong, 450);
            } else {
                pressButton(b.id);
            }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            return;
        }
        if (mode == MODE_TYPE) {
            TKey k = hitTKey(x, y);
            if (k == null) return;
            t.type = 4;
            t.tkey = k;
            touches.put(id, t);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
            t.pad = p;
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
        startNote(t, k, x, y);
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        invalidate();
    }

    private void startNote(Touch t, Key k, float x, float y) {
        if (mode == MODE_SYNTH) synth.synthOn(t.voiceKey, k.midi, 0.85f, preset, brightFromY(y));
        else synth.pianoOn(t.voiceKey, k.midi, velFromY(k, y));
        heldCount[k.midi]++;
        if (textOut) host.onCommit(DO_RE_MI[k.midi % 12]);
    }

    private void move(int id, float x, float y) {
        Touch t = touches.get(id);
        if (t == null || t.type != 1) return;
        Key k = hitKey(x, y);
        if (mode == MODE_SYNTH) {
            t.y = y;
            synth.setBright(t.voiceKey, brightFromY(Math.max(body.top, y)));
            invalidate();
            if (k != null && k.midi != t.midi) { // legato glide
                heldCount[t.midi]--;
                t.midi = k.midi;
                heldCount[t.midi]++;
                synth.setPitch(t.voiceKey, k.midi);
                if (textOut) host.onCommit(DO_RE_MI[k.midi % 12]);
                invalidate();
            }
        } else if (k != null && k.midi != t.midi) { // glissando
            synth.noteOff(t.voiceKey);
            heldCount[t.midi]--;
            t.midi = k.midi;
            t.voiceKey = synth.newKey();
            startNote(t, k, x, y);
            invalidate();
        }
    }

    private void up(int id, float x, float y, boolean cancel) {
        Touch t = touches.get(id);
        if (t == null) return;
        touches.remove(id);
        if (t.type == 1) {
            synth.noteOff(t.voiceKey);
            heldCount[t.midi] = Math.max(0, heldCount[t.midi] - 1);
            invalidate();
        } else if (t.type == 4) {
            if (t.tkey != null && t.tkey == delKey) { removeCallbacks(delRepeat); delKey = null; }
            invalidate();
        } else if (t.type == 3 && t.btn == B_GLOBE) {
            removeCallbacks(globeLong);
            if (!globeLongDone && !cancel) host.onNextIme();
        }
    }

    private void pressButton(int id) {
        switch (id) {
            case B_DRUM: setMode(MODE_DRUM); break;
            case B_PIANO: setMode(MODE_PIANO); break;
            case B_SYNTH: setMode(MODE_SYNTH); break;
            case B_TYPE: setMode(MODE_TYPE); break;
            case B_OCT_DN: if (octave > 1) { octave--; releaseAll(); relayout(); save(); } break;
            case B_OCT_UP: if (octave < 6) { octave++; releaseAll(); relayout(); save(); } break;
            case B_EXTRA:
                if (mode == MODE_PIANO) { sustain = !sustain; synth.setSustain(sustain); }
                else { preset = (preset + 1) % SynthCore.SYNTH_PRESETS.length; save(); }
                break;
            case B_TXT: textOut = !textOut; save(); break;
            case B_HIDE: host.onHide(); break;
        }
        invalidate();
    }

    private void setMode(int m) {
        if (m == mode) return;
        releaseAll();
        if (mode == MODE_TYPE) host.onTypeKey("FLUSH", kanaLang);
        mode = m;
        save();
        relayout();
        host.onModeChanged(mode != MODE_TYPE);
    }

    /** Stop every sounding note (used on mode change / when keyboard hides). */
    public void releaseAll() {
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if (t.type == 1) synth.noteOff(t.voiceKey);
        }
        touches.clear();
        removeCallbacks(delRepeat);
        delKey = null;
        for (int i = 0; i < heldCount.length; i++) heldCount[i] = 0;
        if (sustain) { sustain = false; synth.setSustain(false); }
        invalidate();
    }
}
