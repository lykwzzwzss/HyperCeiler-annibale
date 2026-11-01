package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaVolumeStepPolicyTest {
    @Test public void everySupportedCountTraversesNativeRangeInExactlyThatManyPresses() {
        for (int steps = 16; steps <= 45; steps++) {
            assertTraversal(150, steps, false);
            assertTraversal(150, steps, true);
        }
    }

    @Test public void twentyAndTwentyFourAndThirtyPreserveNativeBluetoothRange() {
        for (int steps : new int[]{20, 24, 30}) assertTraversal(150, steps, true);
        assertEquals(5, MediaVolumeStepPolicy.distance(0, 0, 150, 30, 1));
        assertEquals(7, MediaVolumeStepPolicy.distance(6, 0, 150, 24, 1));
        assertEquals(6, MediaVolumeStepPolicy.distance(13, 0, 150, 24, 1));
    }

    @Test public void offGridSliderValuesDoNotCreateTinyFirstPresses() {
        assertEquals(8, MediaVolumeStepPolicy.distance(30, 0, 150, 24, 1));
        assertEquals(5, MediaVolumeStepPolicy.distance(30, 0, 150, 24, -1));
        assertEquals(7, MediaVolumeStepPolicy.distance(31, 0, 150, 24, 1));
        assertEquals(6, MediaVolumeStepPolicy.distance(31, 0, 150, 24, -1));
        for (int steps = 16; steps <= 45; steps++) {
            for (int current = 0; current <= 150; current++) {
                int up = MediaVolumeStepPolicy.distance(current, 0, 150, steps, 1);
                int down = MediaVolumeStepPolicy.distance(current, 0, 150, steps, -1);
                assertTrue(current + up <= 150);
                assertTrue(current - down >= 0);
                if (current < 150) assertTrue(up > 0);
                if (current > 0) assertTrue(down > 0);
            }
        }
    }

    @Test public void unsupportedRequestsAndRemoteSynchronizationAreExcluded() {
        assertTrue(MediaVolumeStepPolicy.applies(3, 1, 1, false, "android"));
        assertTrue(MediaVolumeStepPolicy.applies(3, -1, 0, false, "com.android.systemui"));
        assertFalse(MediaVolumeStepPolicy.applies(0, 1, 0, false, "android"));
        assertFalse(MediaVolumeStepPolicy.applies(3, 100, 0, false, "android"));
        assertFalse(MediaVolumeStepPolicy.applies(3, -100, 0, false, "android"));
        assertFalse(MediaVolumeStepPolicy.applies(3, 1, 0x40, false, "android"));
        assertFalse(MediaVolumeStepPolicy.applies(3, 1, 0, true, "android"));
        assertFalse(MediaVolumeStepPolicy.applies(3, 1, 0, false, "com.android.bluetooth"));
    }

    @Test public void limitsAndOtherNativeRangesRemainBounded() {
        assertEquals(0, MediaVolumeStepPolicy.distance(150, 0, 150, 24, 1));
        assertEquals(0, MediaVolumeStepPolicy.distance(0, 0, 150, 24, -1));
        assertEquals(0, MediaVolumeStepPolicy.distance(0, 0, 0, 24, 1));
        assertEquals(0, MediaVolumeStepPolicy.distance(10, 0, 150, 0, 1));
        assertEquals(0, MediaVolumeStepPolicy.distance(10, 0, 150, 24, 100));
        int current = 10;
        for (int i = 0; i < 30; i++) current += MediaVolumeStepPolicy.distance(current, 10, 310, 30, 1);
        assertEquals(310, current);
    }

    private static void assertTraversal(int max, int steps, boolean bluetoothRoundTrip) {
        int current = 0;
        for (int i = 0; i < steps; i++) {
            int distance = MediaVolumeStepPolicy.distance(current, 0, max, steps, 1);
            assertTrue("up steps=" + steps + " press=" + i, distance > 0);
            current += distance;
            if (bluetoothRoundTrip) current = roundTrip(current, max);
            if (i + 1 < steps) assertTrue("reached max early: " + steps, current < max);
        }
        assertEquals("up " + steps, max, current);
        for (int i = 0; i < steps; i++) {
            int distance = MediaVolumeStepPolicy.distance(current, 0, max, steps, -1);
            assertTrue("down steps=" + steps + " press=" + i, distance > 0);
            current -= distance;
            if (bluetoothRoundTrip) current = roundTrip(current, max);
            if (i + 1 < steps) assertTrue("reached zero early: " + steps, current > 0);
        }
        assertEquals("down " + steps, 0, current);
    }

    private static int roundTrip(int volume, int max) {
        int remote = (int) Math.round((double) volume * 127 / max);
        return (int) Math.round((double) remote * max / 127);
    }
}
