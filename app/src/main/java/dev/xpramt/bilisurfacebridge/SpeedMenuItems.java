package dev.xpramt.bilisurfacebridge;

import java.util.ArrayList;
import java.util.List;

/** Pure rules for the extra entries added to Bilibili's playback-speed menu. */
final class SpeedMenuItems {
    /** Inserted at the top of the menu, in this order (all enabled). */
    static final float[] EXTRA = {10.0f, 9.0f, 8.0f, 7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.5f};
    /** Appended at the bottom (slow motion).  1/3 is the exact float 1.0f / 3.0f (shown as "0.33"), never 0.33f. */
    static final float ONE_THIRD = 1.0f / 3.0f;
    static final float[] EXTRA_LOW = {ONE_THIRD, 0.25f};

    private SpeedMenuItems() {
    }

    /** True for a menu speed this module supplies itself (Bilibili's own 3.0 included). */
    static boolean isExtra(float speed) {
        for (float extra : EXTRA) {
            if (Math.abs(extra - speed) < 0.001f) {
                return true;
            }
        }
        for (float extra : EXTRA_LOW) {
            if (Math.abs(extra - speed) < 0.001f) {
                return true;
            }
        }
        return false;
    }

    /** The resulting speed order for an existing menu; mirrors what the hook does to the list. */
    static List<Float> merge(List<Float> existing) {
        List<Float> result = new ArrayList<>();
        for (float extra : EXTRA) {
            result.add(extra);
        }
        for (Float speed : existing) {
            if (speed != null && !isExtra(speed)) {
                result.add(speed);
            }
        }
        for (float extra : EXTRA_LOW) {
            result.add(extra);
        }
        return result;
    }
}
