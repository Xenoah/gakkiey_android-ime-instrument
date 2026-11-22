package com.xenoah.gakkikeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tiny Klatt-style formant speech synthesizer driven by English IPA phonemes.
 * Monophonic (one voice), rendered by {@link SynthCore} on the audio thread.
 * Pure Java, no Android dependencies.
 */
public final class Speech {

    /** The 44 phonemes of (British) English, in keyboard order. */
    public static final String[] VOWELS = {"iː", "ɪ", "e", "æ", "ʌ", "ɑː", "ɒ", "ɔː", "ʊ", "uː", "ə", "ɜː"};
    public static final String[] DIPHTHONGS = {"eɪ", "aɪ", "ɔɪ", "aʊ", "əʊ", "ɪə", "eə", "ʊə"};
    public static final String[] CONSONANTS = {"p", "b", "t", "d", "k", "ɡ", "f", "v", "θ", "ð",
            "s", "z", "ʃ", "ʒ", "h", "tʃ", "dʒ", "m", "n", "ŋ", "l", "r", "j", "w"};
    public static final String STRESS = "ˈ";

    // parameter indices
    private static final int F1 = 0, F2 = 1, F3 = 2, B1 = 3, B2 = 4, B3 = 5, AV = 6, AH = 7, AF = 8, FC = 9, FB = 10, NP = 11;

    private static final Map<String, float[]> VOWEL_F = new HashMap<>();

    static {
        v("iː", 280, 2250, 2890); v("ɪ", 400, 1920, 2560); v("e", 550, 1770, 2490); v("æ", 690, 1660, 2490);
        v("ʌ", 640, 1190, 2390); v("ɑː", 710, 1100, 2540); v("ɒ", 590, 880, 2540); v("ɔː", 450, 740, 2640);
        v("ʊ", 450, 1030, 2380); v("uː", 310, 870, 2250); v("ə", 500, 1500, 2500); v("ɜː", 560, 1480, 2520);
        v("a", 780, 1300, 2500); v("o", 480, 820, 2600);
    }

    private static void v(String k, float f1, float f2, float f3) {
        VOWEL_F.put(k, new float[]{f1, f2, f3});
    }

    private static boolean isVowel(String p) {
        for (String s : VOWELS) if (s.equals(p)) return true;
        for (String s : DIPHTHONGS) if (s.equals(p)) return true;
        return false;
    }

    private static boolean isLong(String p) {
        return p.endsWith("ː");
    }

    // ------------------------------------------------------------------ segments
    private static final class Seg {
        final float[] t = new float[NP];
        int dur;        // samples, -1 = hold until release
        float glide;    // seconds (time constant toward target)
        float f0mul = 1f;
    }

    private Seg seg(float f1, float f2, float f3, float av, float ah, float af, float fc, float fb, float ms, float glide) {
        Seg s = new Seg();
        s.t[F1] = f1; s.t[F2] = f2; s.t[F3] = f3;
        s.t[B1] = 70; s.t[B2] = 100; s.t[B3] = 160;
        s.t[AV] = av; s.t[AH] = ah; s.t[AF] = af; s.t[FC] = fc; s.t[FB] = fb;
        s.dur = ms < 0 ? -1 : (int) (ms * 0.001f * sr);
        s.glide = glide;
        return s;
    }

    private Seg vowelSeg(String key, float ms, float glide) {
        float[] f = VOWEL_F.get(key);
        if (f == null) f = VOWEL_F.get("ə");
        return seg(f[0], f[1], f[2], 1f, 0.02f, 0f, 3000, 1000, ms, glide);
    }

    /** Formants of the neighbouring vowel (for consonant colouring / h). */
    private float[] ctx(String next) {
        if (next == null) return VOWEL_F.get("ə");
        String k = next;
        if (!VOWEL_F.containsKey(k)) {
            if (k.length() > 1 && VOWEL_F.containsKey(k.substring(0, 1))) k = k.substring(0, 1);
            else k = "ə";
        }
        return VOWEL_F.get(k);
    }

    /** Build segments for one phoneme. hold = sustain the steady part while the key is held. */
    private void build(List<Seg> out, String p, String next, boolean stressed, boolean last, boolean hold) {
        float len = (stressed ? 1.3f : 1f) * (last ? 1.35f : 1f);
        float[] c = ctx(next);
        if (isVowel(p)) {
            boolean diph = !VOWEL_F.containsKey(p);
            if (!diph) {
                Seg s = vowelSeg(p, hold ? -1 : (isLong(p) ? 210 : 120) * len, 0.025f);
                if (stressed) s.f0mul = 1.18f;
                out.add(s);
            } else {
                String a = p.substring(0, 1), b = p.substring(1);
                if (p.equals("aɪ") || p.equals("aʊ")) a = "a";
                if (p.equals("ɔɪ")) a = "o";
                if (b.equals("ɪ")) b = "ɪ";
                Seg s1 = vowelSeg(a, 110 * len, 0.03f);
                if (stressed) s1.f0mul = 1.18f;
                Seg s2 = vowelSeg(b, hold ? -1 : 140 * len, 0.07f);
                if (stressed) s2.f0mul = 1.1f;
                out.add(s1);
                out.add(s2);
            }
            return;
        }
        switch (p) {
            // approximants
            case "l": out.add(seg(360, 1300, 2700, 0.6f, 0, 0, 3000, 1000, hold ? -1 : 75, 0.02f)); break;
            case "r": out.add(seg(420, 1250, 1600, 0.65f, 0, 0, 3000, 1000, hold ? -1 : 75, 0.025f)); break;
            case "j": out.add(seg(260, 2150, 3000, 0.6f, 0, 0, 3000, 1000, hold ? -1 : 65, 0.02f)); break;
            case "w": out.add(seg(290, 620, 2150, 0.6f, 0, 0, 3000, 1000, hold ? -1 : 65, 0.02f)); break;
            // nasals
            case "m": nasal(out, 280, 1000, 2200, hold); break;
            case "n": nasal(out, 280, 1650, 2600, hold); break;
            case "ŋ": nasal(out, 280, 2050, 2700, hold); break;
            // fricatives: (voiced?, centre, bandwidth, level)
            case "s": fric(out, c, false, 6200, 2600, 0.9f, hold, len); break;
            case "z": fric(out, c, true, 6200, 2600, 0.6f, hold, len); break;
            case "ʃ": fric(out, c, false, 2900, 1600, 1.0f, hold, len); break;
            case "ʒ": fric(out, c, true, 2900, 1600, 0.65f, hold, len); break;
            case "f": fric(out, c, false, 4500, 6000, 0.35f, hold, len); break;
            case "v": fric(out, c, true, 4500, 6000, 0.22f, hold, len); break;
            case "θ": fric(out, c, false, 5500, 5500, 0.3f, hold, len); break;
            case "ð": fric(out, c, true, 5500, 5500, 0.2f, hold, len); break;
            case "h": out.add(seg(c[0], c[1], c[2], 0f, 0.9f, 0f, 3000, 1000, hold ? -1 : 80 * len, 0.01f)); break;
            // plosives: closure, burst, aspiration / voicing
            case "p": plosive(out, c, false, 1100, 1800, 0.5f, 0.55f * c[1]); break;
            case "b": plosive(out, c, true, 1100, 1800, 0.35f, 0.55f * c[1]); break;
            case "t": plosive(out, c, false, 4200, 2500, 0.8f, 1800); break;
            case "d": plosive(out, c, true, 4200, 2500, 0.5f, 1800); break;
            case "k": plosive(out, c, false, 2000, 1200, 0.8f, c[1] * 1.1f); break;
            case "ɡ": plosive(out, c, true, 2000, 1200, 0.55f, c[1] * 1.1f); break;
            case "tʃ":
                out.add(seg(c[0], c[1], c[2], 0f, 0f, 0f, 3000, 1000, 55, 0.005f));
                out.add(seg(c[0], 1900, c[2], 0f, 0f, 1.1f, 3000, 1500, 95 * len, 0.004f));
                break;
            case "dʒ":
                out.add(seg(c[0], c[1], c[2], 0.12f, 0f, 0f, 3000, 1000, 45, 0.005f));
                out.add(seg(280, 1900, c[2], 0.45f, 0f, 0.7f, 3000, 1500, 85 * len, 0.004f));
                break;
            default: break; // unknown: silence
        }
    }

    private void nasal(List<Seg> out, float f1, float f2, float f3, boolean hold) {
        Seg s = seg(f1, f2, f3, 0.45f, 0, 0, 3000, 1000, hold ? -1 : 85, 0.015f);
        s.t[B1] = 110; s.t[B2] = 300; s.t[B3] = 400; // damped, murmur-like
        out.add(s);
    }

    private void fric(List<Seg> out, float[] c, boolean voiced, float fc, float fb, float lvl, boolean hold, float len) {
        out.add(seg(c[0] * 0.8f, c[1], c[2], voiced ? 0.35f : 0f, 0f, lvl, fc, fb, hold ? -1 : 110 * len, 0.008f));
    }

    private void plosive(List<Seg> out, float[] c, boolean voiced, float bc, float bb, float burst, float locus) {
        out.add(seg(c[0] * 0.5f, locus, c[2], voiced ? 0.1f : 0f, 0f, 0f, bc, bb, voiced ? 50 : 65, 0.004f)); // closure
        out.add(seg(c[0] * 0.5f, locus, c[2], 0f, 0f, burst, bc, bb, 12, 0.001f));                            // burst
        if (!voiced) out.add(seg(c[0], locus, c[2], 0f, 0.7f, 0f, bc, bb, 45, 0.01f));                       // aspiration
    }

    // ------------------------------------------------------------------ events
    private static final class Ev {
        List<Seg> segs;
        int key;
        boolean release;
        float f0;
    }

    private final ConcurrentLinkedQueue<Ev> queue = new ConcurrentLinkedQueue<>();
    private final float sr;

    public Speech(float sr) {
        this.sr = sr;
    }

    /** Speak a token list (phonemes, "ˈ" stress marks, " " word breaks). */
    public void say(List<String> tokens, float f0) {
        List<Seg> segs = new ArrayList<>();
        boolean stress = false;
        int lastVowel = -1;
        for (int i = 0; i < tokens.size(); i++) if (isVowel(tokens.get(i))) lastVowel = i;
        for (int i = 0; i < tokens.size(); i++) {
            String p = tokens.get(i);
            if (p.equals(STRESS)) { stress = true; continue; }
            if (p.equals(" ")) { segs.add(seg(500, 1500, 2500, 0, 0, 0, 3000, 1000, 90, 0.02f)); continue; }
            String next = null;
            for (int j = i + 1; j < tokens.size(); j++) {
                String q = tokens.get(j);
                if (isVowel(q)) { next = q; break; }
                if (q.equals(" ")) break;
            }
            if (next == null && isVowel(p)) next = p;
            if (next == null) for (int j = i - 1; j >= 0; j--) if (isVowel(tokens.get(j))) { next = tokens.get(j); break; }
            boolean v = isVowel(p);
            build(segs, p, next, v && stress, i == lastVowel, false);
            if (v) stress = false;
        }
        post(segs, 0, false, f0);
    }

    /** Sound a single phoneme; vowels/continuants sustain until {@link #release(int)}. */
    public void hold(int key, String p, float f0) {
        List<Seg> segs = new ArrayList<>();
        build(segs, p, isVowel(p) ? p : "ə", false, false, true);
        post(segs, key, false, f0);
    }

    public void release(int key) {
        Ev e = new Ev();
        e.key = key;
        e.release = true;
        queue.add(e);
    }

    private void post(List<Seg> segs, int key, boolean release, float f0) {
        Ev e = new Ev();
        e.segs = segs;
        e.key = key;
        e.release = release;
        e.f0 = f0;
        queue.add(e);
    }

    // ------------------------------------------------------------------ voice state (audio thread)
    private List<Seg> segs;
    private int segIdx, segPos, key, total, done;
    private boolean active, fading, released;
    private float fade = 1f;
    private final float[] cur = new float[NP];
    private float f0Base = 120f, f0 = 120f;
    private double gph, vibPh;
    private float prevFlow;
    private int seed = 0x2468ACE;
    // resonators: 0..2 formants, 3,4 fixed higher formants, 5 frication
    private final float[] ra = new float[6], rb = new float[6], rc = new float[6], y1 = new float[6], y2 = new float[6];

    private float noise() {
        int x = seed;
        x ^= x << 13; x ^= x >>> 17; x ^= x << 5;
        seed = x;
        return x * (1f / 2147483648f);
    }

    private void setRes(int i, float f, float bw) {
        if (f > sr * 0.45f) f = sr * 0.45f;
        double r = Math.exp(-Math.PI * bw / sr);
        rc[i] = (float) (-r * r);
        rb[i] = (float) (2 * r * Math.cos(2 * Math.PI * f / sr));
        ra[i] = 1f - rb[i] - rc[i];
    }

    private float res(int i, float x) {
        float y = ra[i] * x + rb[i] * y1[i] + rc[i] * y2[i];
        y2[i] = y1[i];
        y1[i] = y;
        return y;
    }

    private void start(Ev e) {
        boolean wasActive = active && !fading;
        segs = e.segs;
        segIdx = 0;
        segPos = 0;
        key = e.key;
        active = segs != null && !segs.isEmpty();
        fading = false;
        released = false;
        fade = 1f;
        f0Base = e.f0;
        total = 0;
        done = 0;
        if (active) for (Seg s : segs) total += Math.max(0, s.dur);
        if (active && !wasActive) { // jump to the first target, start from silence
            System.arraycopy(segs.get(0).t, 0, cur, 0, NP);
            cur[AV] = 0; cur[AH] = 0; cur[AF] = 0;
            f0 = f0Base;
            for (int i = 0; i < 6; i++) { y1[i] = 0; y2[i] = 0; }
        }
        setRes(3, 3300, 250);
        setRes(4, 3750, 200);
    }

    /** Mix speech into the given mono buffers. */
    public void render(float[] mix, float[] send, int frames) {
        Ev e;
        while ((e = queue.poll()) != null) {
            if (e.release) {
                if (active && key == e.key && e.key != 0) released = true; // held part ends ~60 ms later
            } else {
                start(e);
            }
        }
        if (!active) return;
        float fadeStep = 1f / (0.04f * sr);
        for (int i = 0; i < frames; i++) {
            Seg s = segs.get(segIdx);
            float a = 1f - (float) Math.exp(-1.0 / (Math.max(0.001f, s.glide) * sr));
            for (int k = 0; k < NP; k++) cur[k] += (s.t[k] - cur[k]) * a;
            // f0: declination over the utterance, stress accents, gentle vibrato
            float prog = total > 0 ? Math.min(1f, done / (float) total) : 0f;
            float target = f0Base * s.f0mul * (1f - 0.18f * prog);
            f0 += (target - f0) * 0.0015f;
            vibPh += 5.2 / sr;
            if (vibPh >= 1) vibPh -= 1;
            float ff = f0 * (1f + 0.012f * (float) Math.sin(2 * Math.PI * vibPh));
            if ((i & 31) == 0) {
                setRes(0, cur[F1], cur[B1]);
                setRes(1, cur[F2], cur[B2]);
                setRes(2, cur[F3], cur[B3]);
                setRes(5, cur[FC], cur[FB]);
            }
            // glottal source (Rosenberg pulse, differentiated)
            gph += ff / sr;
            if (gph >= 1) gph -= 1;
            float flow;
            if (gph < 0.4) flow = 0.5f * (1f - (float) Math.cos(Math.PI * gph / 0.4));
            else if (gph < 0.56) flow = (float) Math.cos(Math.PI * (gph - 0.4) / 0.32);
            else flow = 0f;
            float src = (flow - prevFlow) * 6f;
            prevFlow = flow;
            float n = noise();
            float voiced = src * cur[AV] + n * cur[AH] * 0.35f;
            float y = res(0, voiced);
            y = res(1, y);
            y = res(2, y);
            y = res(3, y);
            y = res(4, y);
            float fr = res(5, n) * cur[AF] * 0.3f;
            float o = (y * 2.0f + fr) * fade;
            mix[i] += o;
            send[i] += o * 0.12f;

            if (fading) {
                fade -= fadeStep;
                if (fade <= 0) { active = false; return; }
                continue;
            }
            int dur = s.dur >= 0 ? s.dur : (released ? (int) (0.06f * sr) : -1);
            if (dur >= 0) {
                segPos++;
                done++;
                if (segPos >= dur) {
                    segPos = 0;
                    segIdx++;
                    if (segIdx >= segs.size()) { segIdx = segs.size() - 1; fading = true; }
                }
            }
        }
    }
}
