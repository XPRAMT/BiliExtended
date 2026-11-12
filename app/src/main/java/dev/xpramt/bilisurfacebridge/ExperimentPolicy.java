package dev.xpramt.bilisurfacebridge;

/**
 * Meaning of the runtime switch {@code debug.biliextended.exp} (re-read every two seconds).
 * Java side: 1 = ring window 10, 2 = ring window 30, anything else = 60 (see {@link #windowFor}).
 * Native side (native_refresh_cap.cpp, lateFrameSkipMode): the late-frame skip (Media3 shouldDropFrame, -30 ms) is ON, with a 1.5 s hold after a playback-rate decrease, when the
 * property is unset; "3" = ON without the hold (A/B, behaviour of 721d174); "0" = OFF (rollback).  "0", "3" and unset give the same ring window (60).
 * Kept free of Android and Xposed classes so that it can be unit tested on the JVM.
 */
final class ExperimentPolicy {
    /** Window of the position offset ring: 1 = the old 10 samples, 2 = 30, anything else (0, 4, ...) = 60. */
    static int windowFor(int experiment) {
        return experiment == 1 ? 10 : experiment == 2 ? 30 : 60;
    }

    private ExperimentPolicy() {
    }
}
