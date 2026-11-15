package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SpeedSliderScaleTest {
    @Test
    public void stopsAsRequested() {
        float[] expected = {0.1f, 0.25f, 1f / 3f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f, 4f, 5f, 6f, 7f, 8f, 9f, 10f};
        assertEquals(expected.length, SpeedSliderScale.STOPS.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SpeedSliderScale.STOPS[i], 0.0001f);
        }
    }

    @Test
    public void labels() {
        assertEquals("0.1X", SpeedSliderScale.label(0.1f));
        assertEquals("0.25X", SpeedSliderScale.label(0.25f));
        assertEquals("0.33X", SpeedSliderScale.label(1f / 3f));
        assertEquals("0.5X", SpeedSliderScale.label(0.5f));
        assertEquals("1.0X", SpeedSliderScale.label(1f));
        assertEquals("1.75X", SpeedSliderScale.label(1.75f));
        assertEquals("3.5X", SpeedSliderScale.label(3.5f));
        assertEquals("10.0X", SpeedSliderScale.label(10f));
    }

    @Test
    public void positionsRoundTrip() {
        float top = 100f;
        float bottom = 1900f;
        assertEquals(bottom, SpeedSliderScale.positionOf(0, top, bottom), 0.001f);
        assertEquals(top, SpeedSliderScale.positionOf(SpeedSliderScale.STOPS.length - 1, top, bottom), 0.001f);
        for (int i = 0; i < SpeedSliderScale.STOPS.length; i++) {
            assertEquals(i, SpeedSliderScale.indexAt(SpeedSliderScale.positionOf(i, top, bottom), top, bottom));
        }
        assertEquals(0, SpeedSliderScale.indexAt(5000f, top, bottom));
        assertEquals(SpeedSliderScale.STOPS.length - 1, SpeedSliderScale.indexAt(-50f, top, bottom));
    }

    @Test
    public void nearest() {
        assertEquals(5, SpeedSliderScale.nearestIndex(1.0f));
        assertEquals(2, SpeedSliderScale.nearestIndex(0.3333f));
        assertEquals(0, SpeedSliderScale.nearestIndex(0.1f));
        assertEquals(19, SpeedSliderScale.nearestIndex(10f));
        assertEquals(8, SpeedSliderScale.nearestIndex(1.74f));
    }
}
