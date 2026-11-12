package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import java.util.Random;

import org.junit.Test;

/**
 * {@link PositionOffsetRing} is the position offset ring that used to live in Media3FrameSynchronizer.smoothPositionLocked.
 * {@link Reference} is that code as it was before the move (copied unchanged); both are fed the same sample sequences and must
 * give the same smoothed position for every sample, including the paths that are known to be wrong.
 */
public final class PositionOffsetRingTest {
    private static final long UPTIME_NS = 290_000_000_000_000L;   // about 3.4 days after boot, like the device

    /** The averaging branch of smoothPositionLocked and averageOffsetLocked, before the move. */
    private static final class Reference {
        static final int OFFSET_SAMPLES = 64;
        static final double OFFSET_JUMP_MS = 400.0;
        static final double RING_SPEED_TOLERANCE = 0.15;
        final double[] offsetsMs = new double[OFFSET_SAMPLES];
        int offsetWindow = 60;
        int offsetCount;
        int offsetNext;
        double ringSpeed = 1.0;

        void setWindow(int window) {
            if (window != offsetWindow) {
                offsetWindow = window;
                offsetCount = 0;
                offsetNext = 0;
            }
        }

        void reset() {
            offsetCount = 0;
            offsetNext = 0;
        }

        double smooth(long nowNs, long positionMs, double speed) {
            if (offsetCount > 0 && Math.abs(speed - ringSpeed) > RING_SPEED_TOLERANCE * ringSpeed) {
                offsetCount = 0;   // the speed changed
                offsetNext = 0;
            }
            if (offsetCount == 0) {
                ringSpeed = speed;
            }
            final double offset = positionMs - nowNs / 1e6 * ringSpeed;
            if (offsetCount > 0 && Math.abs(offset - averageOffsetLocked()) > OFFSET_JUMP_MS) {
                offsetCount = 0;   // a seek
                offsetNext = 0;
                ringSpeed = speed;
            }
            offsetsMs[offsetNext] = offset;
            offsetNext = (offsetNext + 1) % offsetWindow;
            if (offsetCount < offsetWindow) {
                offsetCount++;
            }
            return averageOffsetLocked() + nowNs / 1e6 * ringSpeed;
        }

        double averageOffsetLocked() {
            double sum = 0.0;
            for (int i = 0; i < offsetCount; i++) {
                sum += offsetsMs[i];
            }
            return offsetCount == 0 ? 0.0 : sum / offsetCount;
        }
    }

    private final Reference reference = new Reference();
    private final PositionOffsetRing ring = new PositionOffsetRing();
    private long tick;
    private double content = 20_000.0;

    private void feed(double speed, double clockSpeed, double jitterMs, double jumpMs, Random random) {
        tick++;
        long nowNs = UPTIME_NS + tick * 85_000_000L;
        content += 85.0 * clockSpeed + jumpMs;
        long positionMs = Math.round(content + random.nextGaussian() * jitterMs);
        double expected = reference.smooth(nowNs, positionMs, speed);
        double actual = ring.smooth(nowNs, positionMs, speed);
        assertEquals("sample " + tick, expected, actual, 0.0);
        assertEquals("ring count after sample " + tick, reference.offsetCount, ring.count());
    }

    @Test
    public void sameOutputAsBeforeTheMoveAtOneTwoAndFiveTimes() {
        Random random = new Random(7);
        for (double speed : new double[] {1.0, 2.0, 5.0}) {
            for (int i = 0; i < 400; i++) {
                feed(speed, speed, 14.0 * speed, 0.0, random);
            }
        }
    }

    @Test
    public void sameOutputAsBeforeTheMoveAcrossSpeedChanges() {
        // Exact speeds only: a speed estimate that wanders by a few per cent makes the offsets drift until the jump branch fires with
        // another speed, the case that was fixed on purpose (PositionOffsetRingJumpTest).
        Random random = new Random(11);
        double[] speeds = {1.0, 1.0, 2.0, 2.0, 5.0, 5.0, 2.0, 1.0, 1.0, 3.0, 3.0, 1.0};
        for (double speed : speeds) {
            for (int i = 0; i < 90; i++) {
                feed(speed, speed, 14.0 * speed, 0.0, random);
            }
        }
    }

    @Test
    public void sameOutputAsBeforeTheMoveAcrossPositionJumpsWithTheSameSpeed() {
        // A jump whose speed estimate differs from the ring's speed intentionally no longer matches the old code (that was the bug,
        // see PositionOffsetRingJumpTest); with the same speed both must still agree.
        Random random = new Random(13);
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 70; i++) {
                feed(5.0, 5.0, 70.0, 0.0, random);
            }
            feed(5.0, 5.0, 70.0, 500.0 + round * 230.0, random);
            for (int i = 0; i < 20; i++) {
                feed(5.0, 5.0, 70.0, 0.0, random);
            }
        }
    }

    @Test
    public void sameOutputAsBeforeTheMoveWhenTheWindowChangesAndTheRingIsReset() {
        Random random = new Random(17);
        for (int window : new int[] {60, 10, 30, 60}) {
            reference.setWindow(window);
            ring.setWindow(window);
            for (int i = 0; i < 150; i++) {
                feed(2.0, 2.0, 28.0, 0.0, random);
            }
            reference.reset();
            ring.reset();
            for (int i = 0; i < 40; i++) {
                feed(1.0, 1.0, 14.0, 0.0, random);
            }
        }
    }
}
