package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SpeedMenuItemsTest {
    @Test
    public void extrasAreDetectedIncludingBilibiliThree() {
        assertTrue(SpeedMenuItems.isExtra(3.0f));
        assertTrue(SpeedMenuItems.isExtra(10.0f));
        assertTrue(SpeedMenuItems.isExtra(2.5f));
        assertFalse(SpeedMenuItems.isExtra(2.0f));
        assertFalse(SpeedMenuItems.isExtra(1.25f));
        assertTrue(SpeedMenuItems.isExtra(1.0f / 3.0f));
        assertTrue(SpeedMenuItems.isExtra(0.25f));
        assertEquals(1.0f / 3.0f, SpeedMenuItems.ONE_THIRD, 0.0f);
    }

    @Test
    public void mergePutsExtrasFirstAndKeepsTheRest() {
        List<Float> merged = SpeedMenuItems.merge(
                Arrays.asList(3.0f, 2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f));
        assertEquals(Arrays.asList(10.0f, 9.0f, 8.0f, 7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.5f,
                2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f, 1.0f / 3.0f, 0.25f), merged);
    }

    @Test
    public void mergeWithoutBilibiliThreeAndRepeatedMergeAgree() {
        List<Float> once = SpeedMenuItems.merge(Arrays.asList(2.0f, 1.0f, 0.5f));
        assertEquals(Arrays.asList(10.0f, 9.0f, 8.0f, 7.0f, 6.0f, 5.0f, 4.0f, 3.0f, 2.5f, 2.0f, 1.0f, 0.5f, 1.0f / 3.0f, 0.25f), once);
        assertEquals(once, SpeedMenuItems.merge(once));
    }
}
