package dev.xpramt.bilisurfacebridge;

import java.util.Locale;

/** Pure rules of the vertical speed slider (user 2026-11-15): its stops, their labels, and stop <-> position mapping. */
final class SpeedSliderScale {
    /** Bottom to top. 1/3 is the exact float 1.0f / 3.0f, shown as "0.33X". */
    static final float[] STOPS = {
            0.25f, SpeedMenuItems.ONE_THIRD, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f,
            3.0f, 3.5f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f, 9.0f, 10.0f};

    private SpeedSliderScale() {
    }

    /** The stop closest to `speed` (the panel's current speed may be off by float rounding). */
    static int nearestIndex(float speed) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < STOPS.length; i++) {
            float diff = Math.abs(STOPS[i] - speed);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /** Stops are evenly spaced; index 0 sits at `bottom`, the last at `top` (top < bottom in screen coordinates). */
    static float positionOf(int index, float top, float bottom) {
        int last = STOPS.length - 1;
        return bottom - (bottom - top) * index / last;
    }

    /** The stop under a touch at `y`, clamped to the ends. */
    static int indexAt(float y, float top, float bottom) {
        int last = STOPS.length - 1;
        if (bottom <= top) {
            return 0;
        }
        float fraction = (bottom - y) / (bottom - top);
        int index = Math.round(fraction * last);
        return Math.max(0, Math.min(last, index));
    }

    /** "0.25X", "0.33X", "0.5X", "1.0X", "1.75X", "10.0X" -- Bilibili's own menu style. */
    static String label(float speed) {
        if (Math.abs(speed - SpeedMenuItems.ONE_THIRD) < 0.001f) {
            return "0.33X";
        }
        float hundredths = Math.round(speed * 100f);
        if (hundredths % 10f != 0f) {
            return String.format(Locale.ROOT, "%.2fX", speed);
        }
        return String.format(Locale.ROOT, "%.1fX", speed);
    }
}
