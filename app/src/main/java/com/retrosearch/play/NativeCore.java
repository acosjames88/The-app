package com.retrosearch.play;

import android.view.Surface;

/** A bundled SNES core, with no browser or downloaded executable code. */
final class NativeCore {
    static { System.loadLibrary("snesplayer"); }
    private NativeCore() {}
    static native boolean load(byte[] rom, String savePath);
    static native void surface(Surface surface);
    static native int frame(short[] audio);
    static native void buttons(int mask);
    static native double fps();
    static native int sampleRate();
    static native void save();
    static native void close();
    static native long[] stats();
    static native byte[] sram();
}
