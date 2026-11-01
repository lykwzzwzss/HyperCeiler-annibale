package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandState {
    public static final long EXIT_HOLD_MS = 400;
    public static final long MAX_SUCCESS_EXIT_HOLD_MS = 5000;
    public static final long MIN_SUCCESS_EXIT_HOLD_MS = 0;
    public static final long SUCCESS_GLYPH_HOLD_MS = 800;
    public static final long SUCCESS_MS = 800;
    public boolean active;
    public boolean authRunning;
    private String credentialSource;
    public long cycle;
    public boolean detectRunning;
    public boolean faceSuccess;
    private boolean hideRequested;
    public long round;
    public boolean successLatched;
    public long viewGen;
    public long authRequest = -1;
    public int user = -1;
    public long scanStartMs = -1;
    public long visibleStartMs = -1;
    public long successStartMs = -1;
    public long goingAwayMs = -1;
    public long closeAtMs = -1;
    public long successGlyphVisibleAtMs = -1;
    public String source = "NONE";
    public String lastCleanup = "INIT";
    public final FaceIslandVisualTimeline visual = new FaceIslandVisualTimeline();
    private long producedCycle = -1;
    private long producedRound = -1;
    private long producedAt = -1;
    private long authStartedAt = -1;
    private long stoppedScanStart = -1;
    private long stoppedVisibleStart = -1;
    private int producedUser = -1;
    private long credentialCycle = -1;
    private long credentialAt = -1;
    private int credentialUser = -1;
    private long successExitHoldMs = 800;

    public void begin(int nextUser, long now) {
        begin(nextUser, now, 800L);
    }

    public void begin(int nextUser, long now, long nextSuccessExitHoldMs) {
        end("NEW_CYCLE");
        this.cycle++;
        this.user = nextUser;
        this.active = true;
        this.round = 0L;
        this.viewGen++;
        this.successExitHoldMs = clampSuccessExitHoldMs(nextSuccessExitHoldMs);
    }

    public void end(String reason) {
        this.faceSuccess = false;
        this.successLatched = false;
        this.detectRunning = false;
        this.authRunning = false;
        this.active = false;
        this.closeAtMs = -1L;
        this.goingAwayMs = -1L;
        this.successStartMs = -1L;
        this.visibleStartMs = -1L;
        this.scanStartMs = -1L;
        this.successGlyphVisibleAtMs = -1L;
        this.successExitHoldMs = 800L;
        this.hideRequested = false;
        this.source = "NONE";
        this.authRequest = -1L;
        this.authStartedAt = -1L;
        this.stoppedVisibleStart = -1L;
        this.stoppedScanStart = -1L;
        this.visual.reset();
        this.lastCleanup = reason;
        clearProduced();
        clearCredential();
    }

    public long newView() {
        long j = this.viewGen + 1;
        this.viewGen = j;
        return j;
    }

    public boolean visible(long expectedCycle, long generation, long now) {
        if (!this.active || expectedCycle != this.cycle || generation != this.viewGen || this.scanStartMs < 0
            || this.visibleStartMs >= 0 || this.visual.phase(now) != 2
            || (this.successLatched && (!this.faceSuccess || now >= this.successStartMs))) {
            return false;
        }
        this.visibleStartMs = now;
        this.scanStartMs = now;
        this.visual.visible(now);
        if (this.successLatched) {
            this.successStartMs = 200 + now;
            recalculateClose();
            return true;
        }
        return true;
    }

    public void detection(boolean running) {
        this.detectRunning = running;
    }

    public void authStart(long now, long request) {
        authStart(now, request, now);
    }

    public void authStart(long now, long request, long visualNow) {
        if (!this.active || this.successLatched || request < 0 || request == this.authRequest) {
            return;
        }
        boolean hadVisible = this.visibleStartMs >= 0 || this.stoppedVisibleStart >= 0;
        this.authRunning = true;
        this.authRequest = request;
        this.authStartedAt = now;
        this.stoppedVisibleStart = -1L;
        this.stoppedScanStart = -1L;
        this.round++;
        this.scanStartMs = now;
        this.visibleStartMs = -1L;
        this.visual.start(hadVisible ? Math.max(now, visualNow) : now);
        clearProduced();
        clearCredential();
    }

    /** 无独立视觉时钟时按事件时间收尾，保留给只有 now/request 的调用点。 */
    public void authStop(long now, long request) {
        authStop(now, request, now);
    }

    public void authStop(long now, long request, long visualNow) {
        if (request < 0 || request == this.authRequest) {
            this.authRunning = false;
            if (!this.successLatched && !matchesProduced(this.user, this.authRequest, now)) {
                this.visual.stop(Math.max(now, visualNow), this.visibleStartMs >= 0);
                if (this.scanStartMs >= 0) {
                    this.stoppedScanStart = this.scanStartMs;
                    this.stoppedVisibleStart = this.visibleStartMs;
                }
                this.visibleStartMs = -1L;
                this.scanStartMs = -1L;
            }
        }
    }

    public boolean produced(int resultUser, long request, long now) {
        if (!this.active || request != this.authRequest || request < 0 || this.authStartedAt < 0
            || now < this.authStartedAt || this.user != resultUser || this.successLatched) {
            return false;
        }
        this.producedCycle = this.cycle;
        this.producedRound = this.round;
        this.producedUser = resultUser;
        this.producedAt = now;
        if (this.scanStartMs < 0 && this.stoppedScanStart >= 0) {
            this.scanStartMs = this.stoppedScanStart;
            this.visibleStartMs = this.stoppedVisibleStart;
            if (this.stoppedVisibleStart < 0) {
                this.visual.start(this.stoppedScanStart);
            }
        }
        clearCredential();
        return true;
    }

    public boolean matchesProduced(int resultUser, long request, long now) {
        return this.active && this.producedCycle == this.cycle && this.producedRound == this.round
            && request >= 0 && request == this.authRequest && this.producedUser == resultUser
            && this.producedAt >= 0 && now >= this.producedAt && now - this.producedAt <= 2000;
    }

    public boolean success(int resultUser, String successSource, boolean fromFace, long now, long visualNow) {
        if (!this.active || resultUser != this.user || this.successLatched) {
            return false;
        }
        this.successLatched = true;
        this.faceSuccess = fromFace;
        this.source = successSource;
        this.authRunning = false;
        boolean hadVisible = this.visibleStartMs >= 0 || this.stoppedVisibleStart >= 0;
        long start = hadVisible ? Math.max(now, visualNow) : now;
        this.successStartMs = (!fromFace || this.visibleStartMs < 0) ? start : Math.max(start, this.visibleStartMs + 200);
        clearCredential();
        recalculateClose();
        return true;
    }

    public void requestHide() {
        if (this.active) {
            this.hideRequested = true;
        }
    }

    public boolean hideAnimationFinished(long now) {
        if (!this.active || !this.hideRequested) {
            return false;
        }
        this.hideRequested = false;
        goingAway(now);
        return true;
    }

    public void cancelHideRequest() {
        this.hideRequested = false;
    }

    public void goingAway(long now) {
        if (this.active) {
            if (this.goingAwayMs < 0) {
                this.goingAwayMs = now;
            }
            recalculateClose();
        }
    }

    public void expectFirstProcessFrame(long now) {
        if (this.successLatched && this.faceSuccess && this.scanStartMs >= 0 && this.visibleStartMs < 0) {
            this.successStartMs = 200 + now;
            recalculateClose();
        }
    }

    public void cancelExit(boolean authenticationValid, long now) {
        if (this.active) {
            this.hideRequested = false;
            this.closeAtMs = -1L;
            this.goingAwayMs = -1L;
            if (!authenticationValid) {
                this.authRunning = false;
                this.faceSuccess = false;
                this.successLatched = false;
                this.authRequest = -1L;
                this.authStartedAt = -1L;
                this.stoppedVisibleStart = -1L;
                this.stoppedScanStart = -1L;
                this.visibleStartMs = -1L;
                this.scanStartMs = -1L;
                this.successStartMs = -1L;
                this.successGlyphVisibleAtMs = -1L;
                this.visual.reset();
                this.source = "NONE";
                clearProduced();
                clearCredential();
            }
        }
    }

    private void recalculateClose() {
        long j;
        if (this.goingAwayMs < 0) {
            this.closeAtMs = -1L;
            return;
        }
        boolean z = this.successLatched;
        long j2 = this.goingAwayMs;
        if (z) {
            j = j2 + this.successExitHoldMs;
        } else {
            j = j2 + EXIT_HOLD_MS;
        }
        this.closeAtMs = j;
    }

    public void completeSuccessVisual() {
        if (!this.active || !this.successLatched) {
            return;
        }
        this.visual.completeSuccess();
    }

    private static long clampSuccessExitHoldMs(long value) {
        return Math.max(MIN_SUCCESS_EXIT_HOLD_MS, Math.min(MAX_SUCCESS_EXIT_HOLD_MS, value));
    }

    public boolean successGlyphVisible(long expectedCycle, long generation, long now) {
        if (!this.active || expectedCycle != this.cycle || generation != this.viewGen || !this.successLatched
            || this.successGlyphVisibleAtMs >= 0 || now < this.successStartMs + SUCCESS_GLYPH_HOLD_MS) {
            return false;
        }
        if (this.closeAtMs >= 0 && now >= this.closeAtMs) {
            return false;
        }
        this.successGlyphVisibleAtMs = now;
        recalculateClose();
        return true;
    }

    public boolean tick(long now) {
        if (!this.active) {
            return false;
        }
        if (this.closeAtMs >= 0 && now >= this.closeAtMs) {
            end("NORMAL_CLOSE");
            return true;
        }
        if (!this.authRunning && !this.successLatched && this.producedAt >= 0 && now - this.producedAt > 2000) {
            this.visual.stop(now, this.visibleStartMs >= 0);
            this.visibleStartMs = -1L;
            this.scanStartMs = -1L;
            clearProduced();
        }
        return false;
    }

    public int phase(long now) {
        if (!this.active) {
            return 0;
        }
        if (this.successLatched && this.visual.phase(now) == 4) {
            return 4;
        }
        if (!this.successLatched || now < this.successStartMs) {
            return this.visual.phase(now);
        }
        return now >= this.successStartMs + SUCCESS_MS ? 4 : 3;
    }

    public FaceIslandMotion.Frame frame(long now) {
        return this.active ? this.visual.sample(this.successLatched, this.successStartMs, now)
            : FaceIslandMotion.sample(0, false, -1L, -1L, now);
    }

    public void credentialChecked(int checkedUser, String kind, long now) {
        clearCredential();
        if (!this.active || this.user != checkedUser || this.successLatched || kind == null) {
            return;
        }
        this.credentialCycle = this.cycle;
        this.credentialUser = checkedUser;
        this.credentialSource = kind;
        this.credentialAt = now;
    }

    public String credentialFinish(int finishUser, String mode, long now) {
        String result = this.credentialSource;
        if (!this.active || this.credentialCycle != this.cycle || this.credentialUser != finishUser
            || result == null || !result.equals(mode) || now < this.credentialAt) {
            result = null;
        }
        clearCredential();
        return result;
    }

    private void clearProduced() {
        this.producedAt = -1L;
        this.producedRound = -1L;
        this.producedCycle = -1L;
        this.producedUser = -1;
    }

    public void clearCredential() {
        this.credentialAt = -1L;
        this.credentialCycle = -1L;
        this.credentialUser = -1;
        this.credentialSource = null;
    }
}
