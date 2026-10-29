package com.retrosearch.play;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

final class GameSession {
    interface Listener { void ready(); void failed(String message); }
    private final AtomicInteger buttons = new AtomicInteger();
    private final Thread thread;
    private volatile boolean stopped, paused;
    GameSession(byte[] rom, String savePath, Listener listener) {
        thread = new Thread(() -> run(rom, savePath, listener), "SNES emulation");
        thread.start();
    }
    void button(int index, boolean down) {
        buttons.updateAndGet(mask -> down ? mask | (1 << index) : mask & ~(1 << index));
    }
    void pause(boolean value) { paused = value; if (value) buttons.set(0); }
    void stop() {
        stopped = true; buttons.set(0); thread.interrupt();
        try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void run(byte[] rom, String savePath, Listener listener) {
        AudioTrack audio = null;
        boolean loaded = false;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            loaded = NativeCore.load(rom, savePath);
            if (!loaded) throw new IllegalArgumentException("This game file could not be opened.");
            int rate = NativeCore.sampleRate();
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Audio output is unavailable.");
            audio = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(8192, minimum * 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (audio.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("Audio output is unavailable.");
            short[] pcm = new short[8192];
            long period = (long) (1_000_000_000.0 / NativeCore.fps());
            long next = System.nanoTime();
            boolean wasPaused = false;
            audio.play(); listener.ready();
            while (!stopped) {
                if (paused) {
                    NativeCore.buttons(0);
                    if (!wasPaused) { NativeCore.save(); audio.pause(); audio.flush(); wasPaused = true; }
                    LockSupport.parkNanos(20_000_000);
                    continue;
                }
                if (wasPaused) { audio.play(); next = System.nanoTime(); wasPaused = false; }
                NativeCore.buttons(buttons.get());
                int count = NativeCore.frame(pcm);
                int offset = 0;
                while (offset < count && !stopped && !paused) {
                    int written = audio.write(pcm, offset, count - offset, AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) throw new IllegalStateException("Audio output stopped.");
                    offset += written;
                }
                next += period;
                long wait = next - System.nanoTime();
                if (wait > 0) LockSupport.parkNanos(wait);
                else if (wait < -100_000_000) next = System.nanoTime();
            }
        } catch (Exception | LinkageError error) {
            if (!stopped) listener.failed(error.getMessage() == null ? "The game could not start." : error.getMessage());
        } finally {
            if (audio != null) { audio.pause(); audio.flush(); audio.release(); }
            if (loaded) NativeCore.close();
        }
    }
}
