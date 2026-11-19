package com.xenoah.gakkikeys;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/** Low-latency AudioTrack output that pulls audio from {@link SynthCore}. */
public final class SoundEngine implements Runnable {
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;
    private SynthCore core;
    private int burst = 256;

    public synchronized SynthCore core(Context ctx) {
        if (core == null) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int sr = 48000;
            try {
                String s = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                if (s != null) sr = Integer.parseInt(s);
                String b = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
                if (b != null) burst = Math.max(64, Math.min(1024, Integer.parseInt(b)));
            } catch (Exception ignored) {
            }
            core = new SynthCore(sr);
        }
        return core;
    }

    public synchronized void start(Context ctx) {
        if (running) return;
        SynthCore c = core(ctx);
        int sr = (int) c.sampleRate();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes, burst * 2 * 4 * 2);
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } catch (Exception e) {
            track = null;
            return;
        }
        running = true;
        thread = new Thread(this, "GakkiAudio");
        thread.start();
    }

    public void stop() {
        Thread t;
        synchronized (this) {
            if (!running) return;
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack tr = track;
        SynthCore c = core;
        if (tr == null || c == null) return;
        int frames = burst;
        float[] buf = new float[frames * 2];
        try {
            tr.play();
            while (running) {
                c.render(buf, frames);
                int w = tr.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (w < 0) break;
            }
        } catch (Exception ignored) {
        } finally {
            try {
                tr.pause();
                tr.flush();
                tr.release();
            } catch (Exception ignored) {
            }
        }
    }
}
