package dev.xpramt.bilisurfacebridge;

import java.lang.reflect.Method;

/**
 * Hidden switch for the diagnostic output that is not needed in normal use: per-second statistics,
 * per-frame CSV telemetry and the native raw sample file.  Off by default.  Switch it on and off
 * from a computer, at any time, without restarting Bilibili or the phone:
 *
 * <pre>
 * adb shell setprop debug.biliextended.log 1
 * adb shell setprop debug.biliextended.log 0
 * </pre>
 *
 * Both the Java and the native side re-read the property every two seconds.  A system property of
 * this kind is also cleared by a reboot, which leaves the switch off.
 */
final class DebugLog {
    static final String PROPERTY = "debug.biliextended.log";
    static final String STAT_PROPERTY = "debug.biliextended.stat";
    private static final long RECHECK_NS = 2_000_000_000L;

    private static volatile boolean enabled;
    private static volatile long checkedNs;
    private static volatile boolean statEnabled;
    private static volatile long statCheckedNs;
    private static Method getter;

    private DebugLog() {
    }

    static boolean enabled() {
        long now = System.nanoTime();
        if (now - checkedNs >= RECHECK_NS || checkedNs == 0L) {
            checkedNs = now;
            enabled = read();
        }
        return enabled;
    }

    /**
     * Only the once-a-second native status line (frames shown, dropped, scheduler counters), without
     * the per-frame CSV: for measuring with as little overhead as possible.
     * {@code adb shell setprop debug.biliextended.stat 1}
     */
    static boolean statEnabled() {
        long now = System.nanoTime();
        if (now - statCheckedNs >= RECHECK_NS || statCheckedNs == 0L) {
            statCheckedNs = now;
            statEnabled = read(STAT_PROPERTY);
        }
        return statEnabled;
    }

    /**
     * Master switch of everything that only a measurement needs (user, 2026-11-11: "all monitoring off by default, on only while a test runs"): the per-frame
     * trace slices (bsbR/F/D/K/P), the decode-latency ring, the rate state file.  On while {@code debug.biliextended.stat} or {@code .log} is 1; a normal
     * user never sets either, so none of it runs.  speeds.py / probe_reuse.py / stat_window.py set the property for the length of a test and remove it again.
     */
    static boolean monitor() {
        return statEnabled() || enabled();
    }

    private static final java.util.HashMap<String, Integer> INT_CACHE = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Long> INT_CHECKED = new java.util.HashMap<>();

    /** Integer system property, re-read every two seconds (experiment switches). */
    static synchronized int readInt(String property, int fallback) {
        long now = System.nanoTime();
        Long checked = INT_CHECKED.get(property);
        if (checked == null || now - checked >= RECHECK_NS) {
            INT_CHECKED.put(property, now);
            int value = fallback;
            try {
                if (getter == null) {
                    getter = Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class, String.class);
                }
                value = Integer.parseInt(String.valueOf(getter.invoke(null, property, String.valueOf(fallback))).trim());
            } catch (Throwable ignored) {
            }
            INT_CACHE.put(property, value);
        }
        Integer cached = INT_CACHE.get(property);
        return cached == null ? fallback : cached;
    }

    private static boolean read() {
        return read(PROPERTY);
    }

    private static boolean read(String property) {
        try {
            if (getter == null) {
                getter = Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class, String.class);
            }
            return "1".equals(getter.invoke(null, property, "0"));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
