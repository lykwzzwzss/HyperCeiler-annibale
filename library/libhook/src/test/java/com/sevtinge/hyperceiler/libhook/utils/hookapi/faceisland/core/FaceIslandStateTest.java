package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class FaceIslandStateTest {
    private FaceIslandState accepted(long hold) {
        FaceIslandState state = new FaceIslandState();
        state.begin(0, 100, hold);
        state.authStart(110, 1);
        assertTrue(state.success(0, "FACE", true, 120, 120));
        return state;
    }

    @Test public void acceptedFaceWaitsForSwipeRegardlessOfHold() {
        FaceIslandState state = accepted(0);
        assertFalse(state.tick(10000));
        assertTrue(state.active);
        assertEquals(-1, state.closeAtMs);
    }

    @Test public void zeroHoldClosesAtCommittedDismissal() {
        FaceIslandState state = accepted(0);
        state.dismissed(500, 0);
        assertTrue(state.tick(500));
        assertFalse(state.active);
    }

    @Test public void customHoldStartsAtDismissalAndDoesNotSlideWithHeartbeats() {
        FaceIslandState state = accepted(800);
        state.dismissed(500, 800);
        state.dismissed(900, 800);
        assertEquals(1300, state.closeAtMs);
        assertFalse(state.tick(1299));
        assertTrue(state.tick(1300));
    }

    @Test public void preHideCallbackDoesNotStartDesktopHold() {
        FaceIslandState state = accepted(800);
        state.requestHide();
        assertTrue(state.hideAnimationFinished(200));
        assertEquals(-1, state.closeAtMs);
        state.dismissed(600, 800);
        assertEquals(1400, state.closeAtMs);
    }

    @Test public void changingHoldUsesTheOriginalDismissalTime() {
        FaceIslandState state = accepted(800);
        state.dismissed(500, 800);
        state.updateExitHold(2000);
        assertEquals(2500, state.closeAtMs);
        state.updateExitHold(0);
        assertTrue(state.tick(600));
    }

    @Test public void cancelledSwipeRestoresAnAcceptedLockedState() {
        FaceIslandState state = accepted(800);
        state.dismissed(500, 800);
        state.cancelHideRequest();
        assertFalse(state.tick(5000));
        assertTrue(state.active);
        assertTrue(state.successLatched);
        state.dismissed(6000, 800);
        assertEquals(6800, state.closeAtMs);
    }

    @Test public void credentialDismissalAlsoHonorsConfiguredZero() {
        FaceIslandState state = new FaceIslandState();
        state.begin(0, 100, 0);
        state.dismissed(500, 0);
        assertTrue(state.tick(500));
    }

    @Test public void holdClampsAndNewCyclesResetDismissal() {
        FaceIslandState state = accepted(800);
        state.dismissed(500, 20000);
        assertEquals(5500, state.closeAtMs);
        state.begin(0, 600, 800);
        assertEquals(-1, state.closeAtMs);
        state.dismissed(700, -10);
        assertTrue(state.tick(700));
    }
}
