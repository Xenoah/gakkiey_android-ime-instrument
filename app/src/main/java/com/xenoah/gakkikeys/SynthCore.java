package com.xenoah.gakkikeys;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java polyphonic synthesizer (no Android dependencies).
 * All sound is generated in real time: drums, piano (additive) and a subtractive synth.
 * UI thread posts events; the audio thread owns every voice.
 */
public final class SynthCore {
    public static final int DRUM = 0, PIANO = 1, SYNTH = 2, SE = 3, DTMF = 4;
    /** Number of one-shot sound effects. */
    public static final int SE_COUNT = 18;

    // Drum indices
    public static final int KICK = 0, SNARE = 1, CLAP = 2, RIM = 3, CHAT = 4, OHAT = 5,
            CRASH = 6, COWBELL = 7, TOM_L = 8, TOM_M = 9, TOM_H = 10, BASS808 = 11;
    public static final String[] DRUM_NAMES = {"KICK", "SNARE", "CLAP", "RIM", "C.HAT", "O.HAT",
            "CRASH", "COWBELL", "TOM L", "TOM M", "TOM H", "808"};

    public static final String[] SYNTH_PRESETS = {"LEAD", "SQUARE", "PAD", "BASS"};

    private static final int MAX_VOICES = 32;
    private static final int TABLE = 4096;
    private static final float[] SINE = new float[TABLE + 1];

    static {
        for (int i = 0; i <= TABLE; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
    }

    private static float sin(double ph) { // ph in cycles, [0,1)
        double x = ph * TABLE;
        int i = (int) x;
        float f = (float) (x - i);
        return SINE[i] + (SINE[i + 1] - SINE[i]) * f;
    }

    private static double wrap(double ph) {
        return ph - Math.floor(ph);
    }

    private static float polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return (float) (t + t - t * t - 1.0);
        } else if (t > 1.0 - dt) {
            t = (t - 1.0) / dt;
            return (float) (t * t + t + t + 1.0);
        }
        return 0f;
    }

    public static double midiToHz(double midi) {
        return 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0);
    }

    // ---------------------------------------------------------------- events
    private static final int EV_ON = 0, EV_OFF = 1, EV_PITCH = 2, EV_BRIGHT = 3, EV_SUSTAIN = 4, EV_ALLOFF = 5;

    private static final class Ev {
        int type, key, kind, sub;
        float a, b, c;
    }

    private final ConcurrentLinkedQueue<Ev> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger keyGen = new AtomicInteger(1);

    public int newKey() {
        return keyGen.getAndIncrement();
    }

    private void post(int type, int key, int kind, int sub, float a, float b, float c) {
        Ev e = new Ev();
        e.type = type; e.key = key; e.kind = kind; e.sub = sub; e.a = a; e.b = b; e.c = c;
        queue.add(e);
    }

    /** One-shot drum hit. */
    public void drum(int pad, float vel) {
        post(EV_ON, newKey(), DRUM, pad, 0, vel, 0);
    }

    /** Piano note. bright unused. */
    public void pianoOn(int key, int midi, float vel) {
        post(EV_ON, key, PIANO, 0, midi, vel, 0);
    }

    /** Synth note with preset and brightness 0..1. */
    public void synthOn(int key, int midi, float vel, int preset, float bright) {
        post(EV_ON, key, SYNTH, preset, midi, vel, bright);
    }

    /** One-shot sound effect. ratio = pitch multiplier. */
    public void se(int idx, float ratio, float vel) {
        post(EV_ON, newKey(), SE, ((idx % SE_COUNT) + SE_COUNT) % SE_COUNT, ratio, vel, 0);
    }

    /** Telephone DTMF tone for 0-9, *, # (and A-D); sounds until noteOff. */
    public void dtmfOn(int key, char c, float vel) {
        post(EV_ON, key, DTMF, c, 0, vel, 0);
    }

    public void noteOff(int key) {
        post(EV_OFF, key, 0, 0, 0, 0, 0);
    }

    public void setPitch(int key, float midi) {
        post(EV_PITCH, key, 0, 0, midi, 0, 0);
    }

    public void setBright(int key, float bright) {
        post(EV_BRIGHT, key, 0, 0, bright, 0, 0);
    }

    public void setSustain(boolean on) {
        post(EV_SUSTAIN, 0, 0, on ? 1 : 0, 0, 0, 0);
    }

    public void allOff() {
        post(EV_ALLOFF, 0, 0, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------- voices
    private static final class Voice {
        boolean active;
        int key, kind, sub;
        long serial;
        float vel;
        double freq, target;
        float bright, brightT;
        int t;
        boolean released, held;
        float rel = 1f, relDec = 1f;
        final double[] ph = new double[8];
        final double[] pf = new double[8];
        final float[] env = new float[8];
        final float[] dec = new float[8];
        // generic envelopes
        int stage; // synth ADSR: 0 A,1 D,2 S,3 R
        float level, fenv, fdec;
        float aux, auxDec;
        // SVF
        float ic1, ic2, a1, a2, a3, k;
        // one-pole states
        float hp1x, hp1y, hp2x, hp2y, lp1;
        int seed = 0x1234567;
        float send;
        int maxT;
    }

    private final Voice[] voices = new Voice[MAX_VOICES];
    private long serial;
    private boolean sustain;
    private final float sr;

    // Reverb (small Schroeder/Freeverb style, mono)
    private final float[][] comb;
    private final int[] combIdx;
    private final float[] combLp;
    private final float[][] ap;
    private final int[] apIdx;

    private float[] mix = new float[0], send = new float[0];

    public SynthCore(float sampleRate) {
        this.sr = sampleRate;
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
        int[] cl = {1116, 1188, 1277, 1356, 1422, 1491};
        comb = new float[cl.length][];
        for (int i = 0; i < cl.length; i++) comb[i] = new float[Math.max(1, (int) (cl[i] * sr / 44100f))];
        combIdx = new int[cl.length];
        combLp = new float[cl.length];
        int[] al = {556, 441, 341};
        ap = new float[al.length][];
        for (int i = 0; i < al.length; i++) ap[i] = new float[Math.max(1, (int) (al[i] * sr / 44100f))];
        apIdx = new int[al.length];
    }

    public float sampleRate() {
        return sr;
    }

    private Voice alloc() {
        Voice best = null;
        for (Voice v : voices) if (!v.active) return v;
        // steal: oldest released, else oldest
        for (Voice v : voices) if (v.released && (best == null || v.serial < best.serial)) best = v;
        if (best == null) for (Voice v : voices) if (best == null || v.serial < best.serial) best = v;
        return best;
    }

    private Voice find(int key) {
        for (Voice v : voices) if (v.active && v.key == key) return v;
        return null;
    }

    private float noise(Voice v) {
        int x = v.seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        v.seed = x;
        return x * (1f / 2147483648f);
    }

    private void setSvf(Voice v, float fc, float res) {
        if (fc > sr * 0.45f) fc = sr * 0.45f;
        if (fc < 20f) fc = 20f;
        float g = (float) Math.tan(Math.PI * fc / sr);
        v.k = 2f - 2f * res;
        v.a1 = 1f / (1f + g * (g + v.k));
        v.a2 = g * v.a1;
        v.a3 = g * v.a2;
    }

    /** mode 0 = LP, 1 = BP, 2 = HP */
    private static float svf(Voice v, float x, int mode) {
        float v3 = x - v.ic2;
        float v1 = v.a1 * v.ic1 + v.a2 * v3;
        float v2 = v.ic2 + v.a2 * v.ic1 + v.a3 * v3;
        v.ic1 = 2 * v1 - v.ic1;
        v.ic2 = 2 * v2 - v.ic2;
        if (mode == 0) return v2;
        if (mode == 1) return v1;
        return x - v.k * v1 - v2;
    }

    private float decayCoef(float seconds) {
        return (float) Math.exp(-1.0 / (seconds * sr));
    }

    private void handle(Ev e) {
        switch (e.type) {
            case EV_ON: {
                if (e.kind == DRUM) {
                    if (e.sub == CHAT) { // choke open hat
                        for (Voice v : voices)
                            if (v.active && v.kind == DRUM && v.sub == OHAT) { v.released = true; v.relDec = decayCoef(0.015f); }
                    }
                    if (e.sub == OHAT || e.sub == CRASH || e.sub == BASS808) { // retrigger same cymbal: fade previous
                        for (Voice v : voices)
                            if (v.active && v.kind == DRUM && v.sub == e.sub) { v.released = true; v.relDec = decayCoef(0.03f); }
                    }
                }
                Voice v = alloc();
                startVoice(v, e);
                break;
            }
            case EV_OFF: {
                Voice v = find(e.key);
                if (v == null || v.kind == DRUM || v.kind == SE) break;
                if (v.kind == PIANO && sustain) { v.held = true; break; }
                release(v);
                break;
            }
            case EV_PITCH: {
                Voice v = find(e.key);
                if (v != null) v.target = midiToHz(e.a);
                break;
            }
            case EV_BRIGHT: {
                Voice v = find(e.key);
                if (v != null) v.brightT = e.a;
                break;
            }
            case EV_SUSTAIN: {
                sustain = e.sub != 0;
                if (!sustain) for (Voice v : voices) if (v.active && v.held) { v.held = false; release(v); }
                break;
            }
            case EV_ALLOFF: {
                sustain = false;
                for (Voice v : voices) if (v.active) { v.held = false; v.released = true; v.relDec = decayCoef(0.05f); v.stage = 3; }
                break;
            }
        }
    }

    private void release(Voice v) {
        if (v.released) return;
        v.released = true;
        if (v.kind == PIANO) {
            // damper: low notes ring a bit longer
            float t = (float) Math.max(0.08, Math.min(0.35, 0.35 * 110.0 / v.freq));
            v.relDec = decayCoef(t);
        } else if (v.kind == SYNTH) {
            v.stage = 3;
        } else if (v.kind == DTMF) {
            v.relDec = decayCoef(0.008f);
        }
    }

    private static final float[][] SYN = {
            // A, D, S, R, cutoffEnvAmt, fenvDecay, res, detuneCents, oscType(0 saw2,1 square+sub,2 saw4,3 saw+subsine), octave, send, glide
            {0.004f, 0.25f, 0.75f, 0.25f, 3.0f, 0.25f, 0.55f, 7f, 0, 0, 0.22f, 0.03f},
            {0.002f, 0.18f, 0.6f, 0.15f, 2.0f, 0.15f, 0.30f, 0f, 1, 0, 0.15f, 0.02f},
            {0.45f, 1.2f, 0.85f, 1.3f, 0.6f, 1.5f, 0.20f, 12f, 2, 0, 0.40f, 0.08f},
            {0.001f, 0.30f, 0.35f, 0.10f, 4.0f, 0.18f, 0.60f, 4f, 3, -12, 0.05f, 0.04f},
    };

    private void startVoice(Voice v, Ev e) {
        v.active = true;
        v.key = e.key;
        v.kind = e.kind;
        v.sub = e.sub;
        v.serial = ++serial;
        v.vel = Math.max(0.05f, Math.min(1f, e.b));
        v.t = 0;
        v.released = false;
        v.held = false;
        v.rel = 1f;
        v.relDec = 1f;
        v.ic1 = v.ic2 = 0;
        v.hp1x = v.hp1y = v.hp2x = v.hp2y = v.lp1 = 0;
        v.level = 0;
        v.stage = 0;
        v.seed = 0x9E3779B9 ^ (int) (serial * 2654435761L);
        for (int i = 0; i < 8; i++) { v.ph[i] = 0; v.env[i] = 0; v.dec[i] = 1; v.pf[i] = 0; }

        if (v.kind == PIANO) {
            double f = midiToHz(e.a);
            v.freq = v.target = f;
            float baseTau = (float) Math.max(0.7, Math.min(9.0, 4.0 * Math.sqrt(261.6 / f)));
            float brightExp = 1.7f - 0.9f * v.vel;
            double B = 0.00035;
            for (int n = 1; n <= 7; n++) {
                double fn = f * n * Math.sqrt(1 + B * n * n);
                int i = n - 1;
                if (fn > sr * 0.45) { v.env[i] = 0; continue; }
                v.pf[i] = fn / sr;
                v.ph[i] = (n * 0.137) % 1.0;
                v.env[i] = (float) (1.0 / Math.pow(n, brightExp));
                if (f < 150 && n == 2) v.env[i] *= 1.3f; // fuller bass
                v.dec[i] = decayCoef(baseTau / (1f + 0.7f * (n - 1)));
            }
            // detuned second string on fundamental
            v.pf[7] = f * 1.0012 / sr;
            v.env[7] = 0.45f;
            v.dec[7] = decayCoef(baseTau * 0.9f);
            v.aux = 0.5f * v.vel; // hammer noise
            v.auxDec = decayCoef(0.004f);
            setSvf(v, (float) Math.min(f * 6, 9000), 0.1f);
            v.send = 0.22f;
            v.maxT = (int) (sr * 14);
        } else if (v.kind == SYNTH) {
            float[] p = SYN[Math.max(0, Math.min(SYN.length - 1, v.sub))];
            double f = midiToHz(e.a + p[9]);
            v.freq = v.target = f;
            v.bright = v.brightT = e.c;
            v.fenv = 1f;
            v.fdec = decayCoef(p[5]);
            v.send = p[10];
            v.maxT = Integer.MAX_VALUE;
            for (int i = 0; i < 8; i++) v.ph[i] = (i * 0.318) % 1.0;
            setSvf(v, 1000, p[6]);
        } else if (v.kind == DTMF) {
            String keys = "123A456B789C*0#D";
            int i = keys.indexOf(Character.toUpperCase((char) v.sub));
            if (i < 0) i = 13;
            double[] row = {697, 770, 852, 941}, col = {1209, 1336, 1477, 1633};
            v.pf[0] = row[i / 4] / sr;
            v.pf[1] = col[i % 4] / sr;
            v.send = 0f;
            v.maxT = (int) (sr * 6);
        } else if (v.kind == SE) {
            v.freq = e.a <= 0 ? 1.0 : e.a;
            v.send = 0.08f;
            float[] len = {0.08f, 0.40f, 0.20f, 0.20f, 0.08f, 0.13f, 0.05f, 1.2f, 0.6f,
                    0.15f, 0.26f, 0.20f, 0.03f, 0.10f, 0.7f, 0.11f, 1.0f, 0.15f};
            v.maxT = (int) (sr * len[v.sub]);
            if (v.sub == 6) setSvf(v, 3000, 0.4f);
            if (v.sub == 13) setSvf(v, 1800, 0.6f);
            if (v.sub == 17) setSvf(v, (float) (1800 * v.freq), 0.3f);
        } else { // DRUM
            v.freq = 0;
            v.send = 0.10f;
            switch (v.sub) {
                case KICK: v.maxT = (int) (sr * 0.9); v.send = 0.04f; break;
                case SNARE: v.maxT = (int) (sr * 0.6); setSvf(v, 4500, 0.1f); break;
                case CLAP: v.maxT = (int) (sr * 0.7); setSvf(v, 1300, 0.45f); v.send = 0.2f; break;
                case RIM: v.maxT = (int) (sr * 0.15); setSvf(v, 2000, 0.6f); break;
                case CHAT: v.maxT = (int) (sr * 0.25); setSvf(v, 7500, 0.2f); break;
                case OHAT: v.maxT = (int) (sr * 1.6); setSvf(v, 7000, 0.2f); break;
                case CRASH: v.maxT = (int) (sr * 3.5); setSvf(v, 5000, 0.1f); v.send = 0.25f; break;
                case COWBELL: v.maxT = (int) (sr * 0.8); setSvf(v, 1700, 0.55f); break;
                case TOM_L: case TOM_M: case TOM_H: v.maxT = (int) (sr * 1.0); v.send = 0.15f; break;
                case BASS808: v.maxT = (int) (sr * 2.2); v.send = 0.03f; break;
                default: v.maxT = (int) (sr * 0.5);
            }
            if (v.sub == CHAT || v.sub == OHAT || v.sub == CRASH) {
                double[] mf = {205.3, 304.4, 369.6, 522.7, 540.0, 800.0};
                double mul = v.sub == CRASH ? 1.35 : 1.6;
                for (int i = 0; i < 6; i++) v.pf[i] = mf[i] * mul / sr;
            }
            if (v.sub == COWBELL) { v.pf[0] = 540.0 / sr; v.pf[1] = 800.0 / sr; }
        }
    }

    // ---------------------------------------------------------------- render
    /** Render interleaved stereo float frames. */
    public void render(float[] out, int frames) {
        Ev e;
        while ((e = queue.poll()) != null) handle(e);
        if (mix.length < frames) { mix = new float[frames]; send = new float[frames]; }
        for (int i = 0; i < frames; i++) { mix[i] = 0; send[i] = 0; }

        for (Voice v : voices) {
            if (!v.active) continue;
            switch (v.kind) {
                case PIANO: renderPiano(v, frames); break;
                case SYNTH: renderSynth(v, frames); break;
                case SE: renderSE(v, frames); break;
                case DTMF: renderDtmf(v, frames); break;
                default: renderDrum(v, frames); break;
            }
        }

        for (int i = 0; i < frames; i++) {
            float wet = reverb(send[i]);
            float s = (mix[i] + wet) * 0.55f;
            // soft clip
            if (s > 3f) s = 1f;
            else if (s < -3f) s = -1f;
            else s = s * (27f + s * s) / (27f + 9f * s * s);
            out[2 * i] = s;
            out[2 * i + 1] = s;
        }
    }

    private float reverb(float x) {
        float in = x * 0.25f;
        float sum = 0;
        for (int c = 0; c < comb.length; c++) {
            float[] buf = comb[c];
            int idx = combIdx[c];
            float y = buf[idx];
            combLp[c] = y * 0.7f + combLp[c] * 0.3f;
            buf[idx] = in + combLp[c] * 0.80f;
            combIdx[c] = (idx + 1) % buf.length;
            sum += y;
        }
        float y = sum;
        for (int a = 0; a < ap.length; a++) {
            float[] buf = ap[a];
            int idx = apIdx[a];
            float b = buf[idx];
            buf[idx] = y + b * 0.5f;
            y = b - y;
            apIdx[a] = (idx + 1) % buf.length;
        }
        return y * 0.5f;
    }

    private void renderPiano(Voice v, int frames) {
        float atk = 1f / (0.0015f * sr);
        float gain = v.vel * 0.30f;
        for (int i = 0; i < frames; i++) {
            float s = 0;
            for (int n = 0; n < 8; n++) {
                if (v.env[n] < 1e-5f) continue;
                s += sin(v.ph[n]) * v.env[n];
                v.ph[n] += v.pf[n];
                if (v.ph[n] >= 1) v.ph[n] -= 1;
                v.env[n] *= v.dec[n];
            }
            if (v.aux > 1e-4f) {
                s += svf(v, noise(v), 1) * v.aux;
                v.aux *= v.auxDec;
            }
            float a = Math.min(1f, v.t * atk);
            if (v.released) v.rel *= v.relDec;
            float o = s * gain * a * v.rel;
            mix[i] += o;
            send[i] += o * v.send;
            v.t++;
        }
        if (v.rel < 1e-4f || (v.env[0] < 2e-4f && v.env[7] < 2e-4f) || v.t > v.maxT) v.active = false;
    }

    private void renderSynth(Voice v, int frames) {
        float[] p = SYN[Math.max(0, Math.min(SYN.length - 1, v.sub))];
        float aRate = 1f / (Math.max(0.001f, p[0]) * sr);
        float dCoef = decayCoef(p[1] * 0.4f);
        float sus = p[2];
        float rCoef = decayCoef(p[3] * 0.4f);
        float glide = 1f - decayCoef(p[11]);
        int osc = (int) p[8];
        double det = Math.pow(2, p[7] / 1200.0);
        float res = p[6];
        float gain = 0.75f * (0.4f + 0.6f * v.vel);

        for (int i = 0; i < frames; i++) {
            v.freq += (v.target - v.freq) * glide;
            v.bright += (v.brightT - v.bright) * 0.003f;
            // ADSR
            switch (v.stage) {
                case 0: v.level += aRate; if (v.level >= 1f) { v.level = 1f; v.stage = 1; } break;
                case 1: v.level = sus + (v.level - sus) * dCoef; break;
                case 3: v.level *= rCoef; break;
            }
            if (v.released) v.rel *= v.relDec; // used only by all-off
            v.fenv *= v.fdec;

            if ((v.t & 31) == 0) {
                double base = 70.0 * Math.pow(2.0, v.bright * 7.2); // ~70Hz .. 10kHz
                double fc = base * (1.0 + p[4] * v.fenv * (0.5 + 0.5 * v.vel));
                setSvf(v, (float) Math.min(fc, sr * 0.45), res);
            }

            double f = v.freq;
            double dt = f / sr;
            float x;
            switch (osc) {
                case 1: { // square + sub square
                    double ph = v.ph[0];
                    float sq = (ph < 0.5 ? 1f : -1f) + polyBlep(ph, dt) - polyBlep(wrap(ph + 0.5), dt);
                    double ph2 = v.ph[1];
                    float sub = (ph2 < 0.5 ? 1f : -1f) + polyBlep(ph2, dt * 0.5) - polyBlep(wrap(ph2 + 0.5), dt * 0.5);
                    x = sq * 0.7f + sub * 0.35f;
                    v.ph[0] = wrap(ph + dt);
                    v.ph[1] = wrap(ph2 + dt * 0.5);
                    break;
                }
                case 2: { // 4 detuned saws
                    x = 0;
                    double[] m = {1.0, det, 1.0 / det, det * det};
                    for (int o = 0; o < 4; o++) {
                        double d = dt * m[o];
                        x += (float) (2 * v.ph[o] - 1) - polyBlep(v.ph[o], d);
                        v.ph[o] = wrap(v.ph[o] + d);
                    }
                    x *= 0.3f;
                    break;
                }
                case 3: { // saw + sub sine
                    float saw = (float) (2 * v.ph[0] - 1) - polyBlep(v.ph[0], dt);
                    float saw2 = (float) (2 * v.ph[1] - 1) - polyBlep(v.ph[1], dt * det);
                    x = (saw + saw2) * 0.4f + sin(v.ph[2]) * 0.7f;
                    v.ph[0] = wrap(v.ph[0] + dt);
                    v.ph[1] = wrap(v.ph[1] + dt * det);
                    v.ph[2] = wrap(v.ph[2] + dt);
                    break;
                }
                default: { // 2 detuned saws
                    float s1 = (float) (2 * v.ph[0] - 1) - polyBlep(v.ph[0], dt);
                    float s2 = (float) (2 * v.ph[1] - 1) - polyBlep(v.ph[1], dt * det);
                    x = (s1 + s2) * 0.5f;
                    v.ph[0] = wrap(v.ph[0] + dt);
                    v.ph[1] = wrap(v.ph[1] + dt * det);
                }
            }
            float y = svf(v, x, 0);
            float o = y * v.level * v.rel * gain;
            mix[i] += o;
            send[i] += o * v.send;
            v.t++;
        }
        if ((v.stage == 3 && v.level < 1e-4f) || v.rel < 1e-4f) v.active = false;
    }

    private void renderDtmf(Voice v, int frames) {
        float atk = 1f / (0.004f * sr);
        float g = 0.28f * v.vel;
        for (int i = 0; i < frames; i++) {
            float s = sin(v.ph[0]) + sin(v.ph[1]);
            v.ph[0] += v.pf[0]; if (v.ph[0] >= 1) v.ph[0] -= 1;
            v.ph[1] += v.pf[1]; if (v.ph[1] >= 1) v.ph[1] -= 1;
            if (v.released) v.rel *= v.relDec;
            float o = s * g * Math.min(1f, v.t * atk) * v.rel;
            mix[i] += o;
            v.t++;
        }
        if (v.rel < 1e-4f || v.t > v.maxT) v.active = false;
    }

    private static float sq(double ph) { return ph < 0.5 ? 1f : -1f; }

    private static float tri(double ph) { return (float) (1.0 - 4.0 * Math.abs(ph - 0.5)); }

    private void renderSE(Voice v, int frames) {
        float invSr = 1f / sr;
        double r = v.freq;
        float g = 0.6f * v.vel;
        for (int i = 0; i < frames; i++) {
            float t = v.t * invSr;
            double f = 0, f2 = 0;
            float s;
            float env;
            switch (v.sub) {
                case 0: f = 880 * r; s = sq(v.ph[0]) * 0.6f; env = (float) Math.exp(-t / 0.03); break; // blip
                case 1: f = (t < 0.07f ? 988 : 1319) * r; s = sq(v.ph[0]) * 0.55f;                     // coin
                    env = t < 0.07f ? 0.8f : (float) Math.exp(-(t - 0.07f) / 0.12); break;
                case 2: f = 300 * r * (1 + 2 * Math.min(t / 0.15, 1)); s = sq(v.ph[0]) * 0.5f;         // jump
                    env = (float) Math.exp(-t / 0.09); break;
                case 3: f = 1800 * r * Math.exp(-t / 0.06) + 150; s = (float) (2 * v.ph[0] - 1) * 0.6f; // laser
                    env = (float) Math.exp(-t / 0.08); break;
                case 4: f = 700 * r * Math.exp(-t / 0.02) + 150; s = sin(v.ph[0]);                      // pop
                    env = (float) Math.exp(-t / 0.025); break;
                case 5: f = Math.min(3000, 250 * r * Math.exp(t / 0.05)); s = sin(v.ph[0]);             // bubble
                    env = (float) Math.exp(-t / 0.045) * Math.min(1f, t / 0.005f); break;
                case 6: f = 150 * r; s = svf(v, noise(v), 1) * 1.6f * (float) Math.exp(-t / 0.006)       // typewriter
                        + sin(v.ph[0]) * 0.5f * (float) Math.exp(-t / 0.01); env = 1f; break;
                case 7: f = 1568 * r; f2 = 2349 * r; s = sin(v.ph[0]) * 0.6f + sin(v.ph[1]) * 0.4f;     // chime
                    env = (float) Math.exp(-t / 0.35); break;
                case 8: f = 180 * r * (1 + 0.4 * Math.sin(2 * Math.PI * 18 * t) * Math.exp(-t / 0.15)); // boing
                    s = sin(v.ph[0]) * 0.7f + tri(v.ph[0]) * 0.3f; env = (float) Math.exp(-t / 0.25); break;
                case 9: f = 1500 * r * Math.exp(-t / 0.04) + 100; s = sq(v.ph[0]) * 0.45f + noise(v) * 0.25f; // zap
                    env = (float) Math.exp(-t / 0.05); break;
                case 10: { int step = (int) (t / 0.035f); f = 523 * r * Math.pow(2, step * 4 / 12.0);   // power-up
                    s = sq(v.ph[0]) * 0.5f; env = t < 0.18f ? 0.8f : (float) Math.exp(-(t - 0.18f) / 0.03); break; }
                case 11: f = 1200 * r * Math.exp(-t / 0.05) + 300; s = sin(v.ph[0]);                     // drop
                    env = (float) Math.exp(-t / 0.06); break;
                case 12: f = 2200 * r; s = sin(v.ph[0]); env = (float) Math.exp(-t / 0.004); break;      // tick
                case 13: f = 750 * r; s = sin(v.ph[0]) * (float) Math.exp(-t / 0.02)                      // wood
                        + svf(v, noise(v), 1) * (float) Math.exp(-t / 0.005); env = 1f; break;
                case 14: f = 1319 * r; s = tri(v.ph[0]); env = (float) Math.exp(-t / 0.2); break;        // tri bell
                case 15: f = 1000 * r; s = Math.max(-0.6f, Math.min(0.6f, sin(v.ph[0]) * 1.5f));         // beep
                    env = t < 0.08f ? 1f : (float) Math.exp(-(t - 0.08f) / 0.008); break;
                case 16: f = 880 * r; f2 = 880 * 5.4 * r;                                                // kalimba
                    s = sin(v.ph[0]) + sin(v.ph[1]) * 0.3f * (float) Math.exp(-t / 0.03); env = (float) Math.exp(-t / 0.3); break;
                default: s = svf(v, noise(v), 0) * 1.2f; env = (float) Math.exp(-t / 0.05); break;     // noise hit
            }
            v.ph[0] = wrap(v.ph[0] + f / sr);
            if (f2 > 0) v.ph[1] = wrap(v.ph[1] + f2 / sr);
            float o = s * env * g;
            mix[i] += o;
            send[i] += o * v.send;
            v.t++;
        }
        if (v.t > v.maxT) v.active = false;
    }

    private float hp1(Voice v, float x, float a) {
        v.hp1y = a * (v.hp1y + x - v.hp1x);
        v.hp1x = x;
        return v.hp1y;
    }

    private void renderDrum(Voice v, int frames) {
        float invSr = 1f / sr;
        float g = 0.9f * v.vel;
        for (int i = 0; i < frames; i++) {
            float t = v.t * invSr;
            float s;
            switch (v.sub) {
                case KICK: {
                    double f = 46 + 120 * Math.exp(-t / 0.032);
                    v.ph[0] = wrap(v.ph[0] + f / sr);
                    float body = sin(v.ph[0]) * (float) Math.exp(-t / 0.38);
                    float click = noise(v) * (float) Math.exp(-t / 0.0015) * 0.35f;
                    s = (body * 1.3f + click);
                    s = s * 1.2f / (1f + Math.abs(s) * 0.3f);
                    break;
                }
                case SNARE: {
                    v.ph[0] = wrap(v.ph[0] + (185 + 40 * Math.exp(-t / 0.01)) / sr);
                    v.ph[1] = wrap(v.ph[1] + 330.0 / sr);
                    float tone = (sin(v.ph[0]) * 0.6f + sin(v.ph[1]) * 0.3f) * (float) Math.exp(-t / 0.07);
                    float n = svf(v, noise(v), 2) * (float) Math.exp(-t / 0.16);
                    s = tone * 0.8f + n * 0.75f;
                    break;
                }
                case CLAP: {
                    float env = 0;
                    float[] starts = {0f, 0.011f, 0.022f, 0.031f};
                    for (int k = 0; k < 3; k++) if (t >= starts[k] && t < starts[k + 1]) env = (float) Math.exp(-(t - starts[k]) / 0.004);
                    if (t >= 0.031f) env = (float) Math.exp(-(t - 0.031f) / 0.13);
                    s = svf(v, noise(v), 1) * env * 2.2f;
                    break;
                }
                case RIM: {
                    v.ph[0] = wrap(v.ph[0] + 1720.0 / sr);
                    v.ph[1] = wrap(v.ph[1] + 470.0 / sr);
                    float env = (float) Math.exp(-t / 0.011);
                    s = (sin(v.ph[0]) * 0.6f + sin(v.ph[1]) * 0.5f) * env + svf(v, noise(v), 1) * env * 0.6f;
                    break;
                }
                case CHAT: case OHAT: case CRASH: {
                    float metal = 0;
                    for (int k = 0; k < 6; k++) {
                        v.ph[k] += v.pf[k];
                        if (v.ph[k] >= 1) v.ph[k] -= 1;
                        metal += v.ph[k] < 0.5 ? 1f : -1f;
                    }
                    float src = metal * 0.12f + noise(v) * (v.sub == CRASH ? 0.9f : 0.6f);
                    float hp = svf(v, src, 2);
                    hp = hp1(v, hp, 0.8f);
                    float tau = v.sub == CHAT ? 0.045f : (v.sub == OHAT ? 0.42f : 1.3f);
                    float env = (float) Math.exp(-t / tau);
                    if (v.sub == CRASH) env = env * 0.75f + (float) Math.exp(-t / 0.08) * 0.25f;
                    s = hp * env * (v.sub == CRASH ? 0.9f : 0.75f);
                    break;
                }
                case COWBELL: {
                    float a = v.ph[0] < 0.5 ? 1f : -1f;
                    float b = v.ph[1] < 0.5 ? 1f : -1f;
                    v.ph[0] += v.pf[0]; if (v.ph[0] >= 1) v.ph[0] -= 1;
                    v.ph[1] += v.pf[1]; if (v.ph[1] >= 1) v.ph[1] -= 1;
                    float env = (float) (Math.exp(-t / 0.02) * 0.6 + Math.exp(-t / 0.25) * 0.4);
                    s = svf(v, (a + b) * 0.5f, 1) * env * 1.4f;
                    break;
                }
                case TOM_L: case TOM_M: case TOM_H: {
                    double f0 = v.sub == TOM_L ? 92 : (v.sub == TOM_M ? 130 : 182);
                    double f = f0 * (1 + 0.45 * Math.exp(-t / 0.06));
                    v.ph[0] = wrap(v.ph[0] + f / sr);
                    float tau = v.sub == TOM_L ? 0.42f : (v.sub == TOM_M ? 0.34f : 0.28f);
                    s = sin(v.ph[0]) * (float) Math.exp(-t / tau) + noise(v) * (float) Math.exp(-t / 0.004) * 0.25f;
                    break;
                }
                case BASS808: {
                    double f = 49 * (1 + 1.2 * Math.exp(-t / 0.018));
                    v.ph[0] = wrap(v.ph[0] + f / sr);
                    float x = sin(v.ph[0]) * (float) Math.exp(-t / 0.9) * 1.6f;
                    s = (float) Math.tanh(x);
                    break;
                }
                default: s = 0;
            }
            if (v.released) v.rel *= v.relDec;
            float o = s * g * v.rel;
            mix[i] += o;
            send[i] += o * v.send;
            v.t++;
        }
        if (v.t > v.maxT || v.rel < 1e-4f) v.active = false;
    }
}
