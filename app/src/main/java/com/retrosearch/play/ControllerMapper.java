package com.retrosearch.play;

import android.view.KeyEvent;

/** Android's standardized physical button positions -> libretro's SNES input IDs. */
public final class ControllerMapper {
    private ControllerMapper() {}
    public static int button(int key, boolean alternateRemote) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            case KeyEvent.KEYCODE_BUTTON_A: return 0; // Xbox A / PS Cross -> SNES B
            case KeyEvent.KEYCODE_BUTTON_B: return 8;
            case KeyEvent.KEYCODE_BUTTON_X: return 1;
            case KeyEvent.KEYCODE_BUTTON_Y: return 9;
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2: return 11;
            case KeyEvent.KEYCODE_BUTTON_START: return 3;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            case KeyEvent.KEYCODE_DPAD_CENTER: return alternateRemote ? 9 : 0;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return alternateRemote ? 2 : 3;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return alternateRemote ? 10 : 1;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return alternateRemote ? 11 : 8;
            default: return -1;
        }
    }
    /** Hat takes priority over stick; neutral events return zero to release inputs. */
    public static int directions(float x, float y, float hatX, float hatY, float flat) {
        float dead = Math.max(0.25f, Math.min(0.95f, flat));
        if (Math.abs(hatX) > dead || Math.abs(hatY) > dead) { x = hatX; y = hatY; }
        int mask = 0;
        if (y < -dead) mask |= 1 << 4;
        if (y > dead) mask |= 1 << 5;
        if (x < -dead) mask |= 1 << 6;
        if (x > dead) mask |= 1 << 7;
        return mask;
    }
}
