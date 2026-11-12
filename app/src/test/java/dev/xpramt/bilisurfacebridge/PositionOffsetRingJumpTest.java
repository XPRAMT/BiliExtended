package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ring stores {@code positionMs - nowMs * ringSpeed} and gives back {@code average + nowMs * ringSpeed}.  nowMs is the uptime
 * (about 290,000,000 ms), so both speeds must be the same number: a difference of 0.1 turns into a position error of about
 * 29,000 s.  The jump branch (a clock jump of more than 400 ms) used to compute the offset with the old ring speed and only then
 * switch to the speed of the new sample.  On the device that gave release times thousands of seconds ahead of now, and every
 * frame after that was skipped as "the same VSync" until the speed estimates of two neighbouring samples happened to be equal.
 */
public final class PositionOffsetRingJumpTest {
    private static final long UPTIME_NS = 290_000_000_000_000L;
    private long tick;

    private double feed(PositionOffsetRing ring, double positionMs, double speed) {
        tick++;
        return ring.smooth(UPTIME_NS + tick * 100_000_000L, Math.round(positionMs), speed);
    }

    @Test
    public void steadyFiveTimesStaysOnTheSample() {
        PositionOffsetRing ring = new PositionOffsetRing();
        double smoothed = 0;
        for (int i = 0; i < 100; i++) {
            smoothed = feed(ring, 35_000 + i * 500.0, 5.0);
        }
        assertEquals(35_000 + 99 * 500.0, smoothed, 1_000.0);
    }

    @Test
    public void jumpWithTheSameSpeedStaysOnTheSample() {
        PositionOffsetRing ring = new PositionOffsetRing();
        for (int i = 0; i < 80; i++) {
            feed(ring, 35_000 + i * 500.0, 5.0);
        }
        double sample = 35_000 + 80 * 500.0 + 700.0;
        assertEquals(sample, feed(ring, sample, 5.0), 1_000.0);
    }

    @Test
    public void jumpWithAnotherSpeedEstimateDoesNotShiftThePositionByTheUptime() {
        PositionOffsetRing ring = new PositionOffsetRing();
        for (int i = 0; i < 80; i++) {
            feed(ring, 35_000 + i * 500.0, 5.02);
        }
        // the clock jumps ahead by 700 ms (a stale-sample catch-up) and the speed estimate is now 4.90 (inside the 15 % tolerance)
        double sample = 35_000 + 80 * 500.0 + 700.0;
        double smoothed = feed(ring, sample, 4.90);
        assertTrue("error " + (smoothed - sample) + " ms", Math.abs(smoothed - sample) < 1_000.0);
    }

    @Test
    public void aPauseDoesNotLeaveItsSamplesInTheAverage() {
        // 0.5x: 6 s of playback, a 4 s pause (the position stays while the time runs on), then playback again.  Before the fix the ring kept the pause samples and the
        // smoothed position lagged the real one by up to 1 s of content for ~6 s after the resume (the picture 0.2-0.4 s early on the phone).
        PositionOffsetRing ring = new PositionOffsetRing();
        double content = 10_000;
        for (int i = 0; i < 60; i++) {
            content += 50.0;
            feed(ring, content, 0.5);
        }
        for (int i = 0; i < 40; i++) {
            assertEquals("a frozen position is passed through", content, feed(ring, content, 0.5), 0.6);
        }
        double worst = 0;
        for (int i = 0; i < 30; i++) {
            content += 50.0;
            worst = Math.max(worst, Math.abs(feed(ring, content, 0.5) - content));
        }
        assertTrue("worst error after the resume " + worst + " ms", worst < 5.0);
    }

    @Test
    public void repeatedJumpsWithChangingSpeedEstimatesNeverBlowUp() {
        PositionOffsetRing ring = new PositionOffsetRing();
        double content = 35_000;
        double worst = 0;
        for (int i = 0; i < 400; i++) {
            content += 500.0;
            boolean jump = i % 9 == 0 && i > 70;                        // a catch-up of the clock every 9th sample
            if (jump) {
                content += 450.0 + (i % 5) * 120.0;
            }
            double speed = 5.0 + ((i * 37) % 21 - 10) * 0.012;         // 4.88 .. 5.12, a different estimate nearly every sample
            double smoothed = feed(ring, content, speed);
            if (i > 70) {
                worst = Math.max(worst, Math.abs(smoothed - content));
            }
        }
        assertTrue("worst error " + worst + " ms", worst < 2_000.0);
    }
}
