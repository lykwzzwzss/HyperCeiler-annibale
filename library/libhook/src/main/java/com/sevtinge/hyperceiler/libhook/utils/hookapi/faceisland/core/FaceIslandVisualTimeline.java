package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandVisualTimeline {
    private FaceIslandMotion.Frame next;
    private FaceIslandMotion.Frame origin;
    private boolean successCompleted;
    private double yawOffset;
    private int mode = 1;
    private long scanEpoch = -1;
    private long resumeAt = -1;
    private long returnAt = -1;

    public void reset() {
        this.mode = 1;
        this.returnAt = -1L;
        this.resumeAt = -1L;
        this.scanEpoch = -1L;
        this.yawOffset = 0.0d;
        this.next = null;
        this.origin = null;
        this.successCompleted = false;
    }

    public void start(long now) {
        int before = phase(now);
        FaceIslandMotion.Frame beforeFrame = base(now);
        FaceIslandMotion.Frame afterFrame = base(1 + now);
        reset();
        this.mode = 2;
        if (before == 5 || before == 2) {
            this.origin = beforeFrame;
            this.next = afterFrame;
            this.resumeAt = now;
            this.scanEpoch = now - 400;
            this.yawOffset = this.origin.yaw - 2.0943951023931957d;
            return;
        }
        this.scanEpoch = now;
    }

    public void visible(long now) {
        if (this.mode != 2 || this.resumeAt >= 0) {
            return;
        }
        this.scanEpoch = now;
    }

    public void stop(long now, boolean wasVisible) {
        if (this.mode != 2) {
            return;
        }
        if (!wasVisible) {
            reset();
            return;
        }
        FaceIslandMotion.Frame before = base(now);
        FaceIslandMotion.Frame after = base(1 + now);
        this.origin = before;
        this.next = after;
        this.returnAt = now;
        this.mode = 5;
    }

    public long returnStartMs() {
        return this.returnAt;
    }

    public int phase(long now) {
        if (this.successCompleted) {
            return 4;
        }
        if (this.mode == 5 && now - this.returnAt >= 250) {
            return 1;
        }
        return this.mode;
    }

    public void completeSuccess() {
        this.successCompleted = true;
    }

    public FaceIslandMotion.Frame sample(boolean success, long successAt, long now) {
        if (!success) {
            return base(now);
        }
        if (this.successCompleted) {
            return FaceIslandMotion.sample(4, true, -1L, -1L, now);
        }
        return now < successAt ? base(now)
            : FaceIslandMotion.sampleSuccessFrom(base(successAt), base(1 + successAt), successAt, now);
    }

    private FaceIslandMotion.Frame base(long now) {
        if (this.mode == 5) {
            return FaceIslandMotion.sampleReturn(this.origin, this.next, this.returnAt, now);
        }
        if (this.mode != 2) {
            return FaceIslandMotion.sample(1, false, -1L, -1L, now);
        }
        FaceIslandMotion.Frame target = FaceIslandMotion.sampleScan(this.scanEpoch, now, this.yawOffset);
        if (this.resumeAt < 0 || now - this.resumeAt >= 200) {
            return target;
        }
        return FaceIslandMotion.sampleResume(this.origin, this.next,
            FaceIslandMotion.sampleScan(this.scanEpoch, this.resumeAt, this.yawOffset),
            FaceIslandMotion.sampleScan(this.scanEpoch, this.resumeAt + 1, this.yawOffset),
            target, this.resumeAt, now);
    }
}
