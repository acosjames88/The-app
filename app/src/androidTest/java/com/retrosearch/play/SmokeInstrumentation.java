package com.retrosearch.play;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Executes an actual original SNES ROM, then tests the native Activity and Surface. */
public final class SmokeInstrumentation extends Instrumentation {
    private int checks;
    private void check(boolean result, String name) {
        if (!result) throw new AssertionError(name);
        checks++; System.out.println("PASS: " + name);
    }
    private void frames(int count, short[] pcm) { for (int i = 0; i < count; i++) check(NativeCore.frame(pcm) > 0, "ROM frame generates stereo PCM " + i); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity launched = null;
        try {
            byte[] rom;
            try (InputStream in = getTargetContext().getAssets().open("star-catcher.sfc")) { rom = RomRepository.read(in, RomRepository.MAX_ROM); }
            check(rom.length == 32768, "original bundled SNES ROM is 32 KiB");
            RomRepository.validate(rom);
            File saves = new File(getTargetContext().getFilesDir(), "native-test"); saves.mkdirs();
            File save = new File(saves, "game.srm"); save.delete();
            short[] pcm = new short[8192];
            check(NativeCore.load(rom, save.getAbsolutePath()), "bundled native core loads actual SNES ROM");
            check(NativeCore.fps() > 59 && NativeCore.fps() < 61, "NTSC timing");
            check(NativeCore.sampleRate() == 32040, "SNES core reports expected audio rate");
            frames(90, pcm);
            long[] stats = NativeCore.stats();
            check(stats[0] == 90 && stats[1] != 0 && stats[2] >= 256 && stats[3] >= 224, "real SNES video frames are rendered");
            byte[] ram = NativeCore.sram();
            check(ram.length >= 2048 && ram[0] == 15 && ram[1] == 15, "emulated 65816 CPU writes starting position into SRAM");
            long initialHash = stats[1];
            NativeCore.buttons(1 << 7); frames(30, pcm);
            ram = NativeCore.sram();
            check((ram[0] & 255) > 15 && (ram[3] & 1) != 0, "SNES joypad Right moves the actual emulated game");
            check(NativeCore.stats()[1] != initialHash, "game movement changes real frame pixels");
            NativeCore.buttons(0); int x = ram[0] & 255; frames(10, pcm);
            check((NativeCore.sram()[0] & 255) == x, "released Right stops movement");
            // Reach the initial star at 22,10 using the real core input callback.
            int guard = 0;
            while ((NativeCore.sram()[0] & 255) < 22 && guard++ < 30) { NativeCore.buttons(1 << 7); NativeCore.frame(pcm); }
            NativeCore.buttons(0); NativeCore.frame(pcm);
            guard = 0;
            while ((NativeCore.sram()[1] & 255) > 10 && guard++ < 40) { NativeCore.buttons(1 << 4); NativeCore.frame(pcm); }
            NativeCore.buttons(0); frames(5, pcm);
            check((NativeCore.sram()[2] & 255) >= 1, "actual game collects a star and increments score");
            frames(30, pcm); // Let the star-collection beep stop before testing B.
            long beforeSound = NativeCore.stats()[4];
            NativeCore.buttons(1); boolean audible = false;
            for (int i = 0; i < 20; i++) {
                int count = NativeCore.frame(pcm);
                for (int p = 0; p < count; p++) if (pcm[p] != 0) audible = true;
            }
            NativeCore.buttons(0);
            check(audible && NativeCore.stats()[4] > beforeSound && (NativeCore.sram()[3] & 0x80) != 0,
                "SPC700 program generates nonzero stereo sound after B");
            NativeCore.save(); byte[] expected = NativeCore.sram();
            check(save.isFile() && Arrays.equals(expected, Files.readAllBytes(save.toPath())), "battery RAM is saved exactly");
            NativeCore.close();
            check(NativeCore.load(rom, save.getAbsolutePath()), "native core reopens a game");
            check(Arrays.equals(expected, NativeCore.sram()), "saved SRAM is restored before execution");
            NativeCore.close();

            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launched = startActivitySync(intent); waitForIdleSync();
            MainActivity activity = (MainActivity) launched;
            runOnMainSync(() -> check(noBrowser(activity.getWindow().getDecorView()), "native catalog has no browser"));
            runOnMainSync(() -> activity.startGame(rom, "activity-test"));
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (NativeCore.stats()[0] < 45 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            check(NativeCore.stats()[0] >= 45, "native Activity runs the bundled SNES game");
            runOnMainSync(() -> check(noBrowser(activity.getWindow().getDecorView()), "game display has no browser"));
            final SurfaceView[] surface = new SurfaceView[1];
            runOnMainSync(() -> surface[0] = findSurface(activity.getWindow().getDecorView()));
            check(surface[0] != null, "native SurfaceView displays game");
            Bitmap bitmap = Bitmap.createBitmap(surface[0].getWidth(), surface[0].getHeight(), Bitmap.Config.ARGB_8888);
            CountDownLatch copied = new CountDownLatch(1); int[] copyStatus = {-1};
            PixelCopy.request(surface[0], bitmap, status -> { copyStatus[0] = status; copied.countDown(); }, new Handler(Looper.getMainLooper()));
            check(copied.await(5, TimeUnit.SECONDS) && copyStatus[0] == PixelCopy.SUCCESS, "displayed Surface pixels are captured");
            HashSet<Integer> colors = new HashSet<>();
            for (int y = 0; y < bitmap.getHeight(); y += 4)
                for (int xx = 0; xx < bitmap.getWidth(); xx += 4) colors.add(bitmap.getPixel(xx, y));
            check(colors.size() >= 4, "actual displayed game has background, text, player, and star pixels");
            try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getFilesDir(), "native-gameplay.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bitmap.recycle();
            x = NativeCore.sram()[0] & 255;
            runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            SystemClock.sleep(300);
            runOnMainSync(() -> activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)));
            check((NativeCore.sram()[0] & 255) > x, "Activity routes physical D-pad input to the SNES CPU");
            runOnMainSync(activity::onBackPressed); SystemClock.sleep(100);
            long pausedFrames = NativeCore.stats()[0]; SystemClock.sleep(150);
            check(NativeCore.stats()[0] == pausedFrames, "Back pauses actual SNES execution");
            runOnMainSync(activity::showGames); waitForIdleSync();
            check(activity.session == null && NativeCore.sram().length == 0, "Games stops and unloads the native core");
            runOnMainSync(() -> activity.startGame(rom, "activity-test"));
            deadline = SystemClock.uptimeMillis() + 5000;
            while (NativeCore.stats()[0] < 20 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            check(NativeCore.stats()[0] >= 20 && activity.session != null, "a second game session starts cleanly");
            runOnMainSync(activity::showGames); waitForIdleSync();
            result.putString("stream", "\nPASS: " + checks + " checks; real SNES CPU, video, movement, scoring, nonzero audio, battery saves, native Surface, pause, and restart.\nPhysical Fire TV, Bluetooth pairing, and commercial game compatibility are unverified.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            try { NativeCore.close(); } catch (Throwable ignored) {}
            result.putString("stream", "\nFAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (launched != null) { Activity end = launched; runOnMainSync(end::finish); }
        }
    }
    private boolean noBrowser(View view) {
        if (view.getClass().getName().startsWith("android.webkit.")) return false;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++)
            if (!noBrowser(((ViewGroup)view).getChildAt(i))) return false;
        return true;
    }
    private SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView) return (SurfaceView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            SurfaceView found = findSurface(((ViewGroup)view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}

