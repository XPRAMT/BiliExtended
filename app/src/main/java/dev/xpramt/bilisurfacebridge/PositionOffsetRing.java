package dev.xpramt.bilisurfacebridge;

/**
 * Smooths the IJK audio position the way Media3's AudioTrackPositionTracker smooths its timestamp offsets: it keeps the last
 * {@code window} values of {@code positionMs - nowMs * ringSpeed} and gives back {@code average + nowMs * ringSpeed}.  The speed is
 * fixed for the life of the ring, so a clock that runs at that speed always gives back the sample itself.
 *
 * Project-tuned, not upstream: a window of 60 samples (see {@code Media3FrameSynchronizer}).  Not thread-safe: the caller holds its
 * clock lock.  Kept free of Android and Xposed classes so that it can be unit tested on the JVM.
 */
final class PositionOffsetRing {
    static final int CAPACITY = 64;
    private static final double JUMP_MS = 400.0;
    private static final double SPEED_TOLERANCE = 0.15;

    private final double[] offsetsMs = new double[CAPACITY];
    private int window = 60;
    private int count;
    private int next;
    private double ringSpeed = 1.0;
    // A paused player keeps answering position polls with the SAME position while the time runs on (2026-11-11, phone, measured from the traces: Bilibili polls about every
    // 100 ms).  Each of those samples moved the offset by only 100 ms x speed, below JUMP_MS, so they all went into the average; after a resume the ring still held up to
    // `window` samples (~6 s) of the pause, the smoothed position lagged the real one by up to half the pause, and the picture was scheduled 0.2-0.4 s early, converging
    // only as the pause samples left the ring (4K60 x 0.5: 6-12 s of long intervals after every resume).  A frozen position empties the ring and is passed through;
    // smoothing starts again from the first sample after the resume.
    private static final long FROZEN_MIN_NS = 30_000_000L;
    private long lastPositionMs = Long.MIN_VALUE;
    private long lastNs;

    int count() {
        return count;
    }

    void setWindow(int newWindow) {
        if (newWindow != window) {
            window = newWindow;
            reset();
        }
    }

    void reset() {
        count = 0;
        next = 0;
    }

    /** Smoothed position for a sample taken at {@code nowNs}; {@code speed} is the speed the clock runs at. */
    double smooth(long nowNs, long positionMs, double speed) {
        final boolean frozen = positionMs == lastPositionMs && nowNs - lastNs >= FROZEN_MIN_NS;
        if (positionMs != lastPositionMs) {
            lastPositionMs = positionMs;
            lastNs = nowNs;
        }
        if (frozen) {
            count = 0;   // paused (or stalled): the position does not move, nothing to average
            next = 0;
            return positionMs;
        }
        if (count > 0 && Math.abs(speed - ringSpeed) > SPEED_TOLERANCE * ringSpeed) {
            count = 0;   // the speed changed
            next = 0;
        }
        if (count == 0) {
            ringSpeed = speed;
        }
        double offset = positionMs - nowNs / 1e6 * ringSpeed;
        if (count > 0 && Math.abs(offset - average()) > JUMP_MS) {
            count = 0;   // a seek
            next = 0;
            ringSpeed = speed;
            offset = positionMs - nowNs / 1e6 * ringSpeed;   // stored and added back with the SAME speed (nowMs is ~3e8)
        }
        offsetsMs[next] = offset;
        next = (next + 1) % window;
        if (count < window) {
            count++;
        }
        return average() + nowNs / 1e6 * ringSpeed;
    }

    private double average() {
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += offsetsMs[i];
        }
        return count == 0 ? 0.0 : sum / count;
    }
}
