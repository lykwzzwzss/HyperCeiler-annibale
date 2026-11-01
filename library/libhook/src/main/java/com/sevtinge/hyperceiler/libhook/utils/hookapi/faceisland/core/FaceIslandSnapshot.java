package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandSnapshot {
    public final long cycle;
    public final boolean debugPreview;
    public final boolean faceSuccess;
    public final FaceIslandMotion.Frame frame;
    public final long nowMs;
    public final int phase;
    public final long returnStartMs;
    public final long round;
    public final long scanStartMs;
    public final String source;
    public final long successStartMs;
    public final long viewGen;

    public FaceIslandSnapshot(FaceIslandState state, long now) {
        this(state, now, false);
    }

    public FaceIslandSnapshot(FaceIslandState state, long now, boolean debugPreview) {
        this.cycle = state.cycle;
        this.debugPreview = debugPreview;
        this.round = state.round;
        this.viewGen = state.viewGen;
        this.nowMs = now;
        this.scanStartMs = state.scanStartMs;
        this.successStartMs = state.successStartMs;
        this.returnStartMs = state.visual.returnStartMs();
        this.phase = state.phase(now);
        this.faceSuccess = state.faceSuccess;
        this.source = state.source;
        this.frame = state.frame(now);
    }
}
