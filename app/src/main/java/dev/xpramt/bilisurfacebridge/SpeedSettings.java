package dev.xpramt.bilisurfacebridge;

/** Value rules for the playback-speed options (pure logic, shared by hooks and UI). */
final class SpeedSettings {
    static final float MIN_RATE = 2.0f;
    static final float MAX_RATE = 10.0f;
    static final float DEFAULT_LONG_PRESS_RATE = 3.0f;

    private SpeedSettings() {
    }

    /** The native ceiling to request: 0 (do nothing) when disabled or the value is unusable. */
    static float effectiveCapLimit(boolean enabled, float configured) {
        if (!enabled || Float.isNaN(configured) || configured <= 1.0f) {
            return 0.0f;
        }
        return Math.min(configured, MAX_RATE);
    }

    /** The long-press rate to force, or NaN to keep Bilibili's own (preference never set). */
    static float longPressOverride(boolean present, float stored) {
        return present ? clampRate(stored) : Float.NaN;
    }

    /** Clamps a user-chosen rate into the supported 2-10 range; NaN becomes the default. */
    static float clampRate(float rate) {
        if (Float.isNaN(rate)) {
            return DEFAULT_LONG_PRESS_RATE;
        }
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
    }
}
