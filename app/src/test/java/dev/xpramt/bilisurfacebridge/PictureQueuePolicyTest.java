package dev.xpramt.bilisurfacebridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PictureQueuePolicyTest {
    @Test
    public void unsetOrOutOfRangeKeepsTheShippedTwelve() {
        assertEquals(12, PictureQueuePolicy.resolve(12));
        assertEquals(12, PictureQueuePolicy.resolve(0));
        assertEquals(12, PictureQueuePolicy.resolve(-5));
        assertEquals(12, PictureQueuePolicy.resolve(3));
        assertEquals(12, PictureQueuePolicy.resolve(11));
        assertEquals(12, PictureQueuePolicy.resolve(17));
        assertEquals(12, PictureQueuePolicy.resolve(1000));
    }

    @Test
    public void thirteenToSixteenIsTaken() {
        assertEquals(13, PictureQueuePolicy.resolve(13));
        assertEquals(14, PictureQueuePolicy.resolve(14));
        assertEquals(16, PictureQueuePolicy.resolve(16));
    }
}
