package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SpeedSettingsTest {
    @Test
    public void capLimitDefaultsToTen() {
        assertEquals(10.0f, SpeedSettings.effectiveCapLimit(true, 10.0f), 0.0f);
    }

    @Test
    public void disabledOrUnusableLimitMeansNoChange() {
        assertEquals(0.0f, SpeedSettings.effectiveCapLimit(false, 10.0f), 0.0f);
        assertEquals(0.0f, SpeedSettings.effectiveCapLimit(true, 0.0f), 0.0f);
        assertEquals(0.0f, SpeedSettings.effectiveCapLimit(true, -3.0f), 0.0f);
        assertEquals(0.0f, SpeedSettings.effectiveCapLimit(true, Float.NaN), 0.0f);
    }

    @Test
    public void capLimitIsBoundedAtTen() {
        assertEquals(10.0f, SpeedSettings.effectiveCapLimit(true, 50.0f), 0.0f);
        assertEquals(4.0f, SpeedSettings.effectiveCapLimit(true, 4.0f), 0.0f);
    }

    @Test
    public void longPressOverrideOnlyWhenPreferenceExists() {
        assertEquals(true, Float.isNaN(SpeedSettings.longPressOverride(false, 5.0f)));
        assertEquals(5.0f, SpeedSettings.longPressOverride(true, 5.0f), 0.0f);
        assertEquals(10.0f, SpeedSettings.longPressOverride(true, 40.0f), 0.0f);
    }

    @Test
    public void rateClampsToSupportedRange() {
        assertEquals(2.0f, SpeedSettings.clampRate(0.5f), 0.0f);
        assertEquals(10.0f, SpeedSettings.clampRate(99f), 0.0f);
        assertEquals(5.0f, SpeedSettings.clampRate(5f), 0.0f);
        assertEquals(3.0f, SpeedSettings.clampRate(Float.NaN), 0.0f);
    }
}
