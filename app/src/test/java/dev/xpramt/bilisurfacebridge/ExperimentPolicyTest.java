package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ExperimentPolicyTest {
    /** The expression that Media3FrameSynchronizer.refreshExperimentLocked used before the extraction (pasted). */
    private static int oldWindow(int exp) {
        return exp == 1 ? 10 : exp == 2 ? 30 : 60;
    }

    @Test
    public void windowIsTheSameAsBeforeForEveryValue() {
        for (int exp = -5; exp <= 50; exp++) {
            assertEquals("exp " + exp, oldWindow(exp), ExperimentPolicy.windowFor(exp));
        }
    }

    @Test
    public void existingMeaningsAreKept() {
        assertEquals(10, ExperimentPolicy.windowFor(1));
        assertEquals(30, ExperimentPolicy.windowFor(2));
        assertEquals(60, ExperimentPolicy.windowFor(0));
        assertEquals(60, ExperimentPolicy.windowFor(4));
    }
}
