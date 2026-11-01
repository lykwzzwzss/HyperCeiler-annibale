package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandMotion {
    public static final long FACE_REVEAL_DURATION_MS = 320;
    private static final double FAILURE_SHAKE_AMPLITUDE = 3.0d;
    private static final double FAILURE_SHAKE_CYCLES = 2.0d;
    public static final long FAILURE_SHAKE_DURATION_MS = 250;
    private static final double MIN_PROJECTED_WIDTH = 1.0E-6d;
    public static final int PHASE_CLOSED = 0;
    public static final int PHASE_IDLE = 1;
    public static final int PHASE_RETURNING = 5;
    public static final int PHASE_SCANNING = 2;
    public static final int PHASE_SUCCESS_HOLD = 4;
    public static final int PHASE_SUCCESS_TRANSITION = 3;
    public static final long RESUME_MS = 200;
    public static final long RETURN_MS = 250;
    public static final long RING_SETTLE_DURATION_MS = 380;
    private static final double SCAN_ANGULAR_SPEED = 5.235987755982989d;
    public static final long SCAN_CYCLE_MS = 1200;
    public static final long SCAN_INTRO_MS = 400;
    public static final long SHACKLE_DURATION_MS = 200;
    public static final double SHACKLE_OPEN_ANGLE = -28.0d;
    public static final long SHACKLE_START_MS = 420;
    public static final long SUCCESS_CONVERGE_MS = 420;
    public static final long SUCCESS_TOTAL_MS = 800;
    private static final double TAU = 6.283185307179586d;

    private FaceIslandMotion() {
    }

    public static final class Frame {
        public final double artworkRingAlpha;
        public final boolean complete;
        public final double faceAlpha;
        public final double glowAlpha;
        public final double highlightAlpha;
        public final int phase;
        public final double pitch;
        public final double projectionSettle;
        public final double projectionX;
        public final double referenceAlpha;
        public final double ringAlpha;
        public final double roll;
        public final double shackleAngle;
        public final double shape;
        public final double trailAlpha;
        public final double yaw;

        /**
         * 由 smali 还原：14 参构造器按 phase/shape/artworkRing 推导 referenceAlpha 与
         * ringAlpha，再委托给 16 参构造器。
         */
        private Frame(int phase, double shape, double yaw, double roll, double pitch,
                      double projectionX, double trailAlpha, double glowAlpha, double highlightAlpha,
                      double faceAlpha, double shackleAngle, double artworkRingAlpha,
                      double projectionSettle, boolean complete) {
            this(phase, shape, yaw, roll, pitch, projectionX, trailAlpha, glowAlpha, highlightAlpha,
                faceAlpha, shackleAngle, artworkRingAlpha, projectionSettle, complete,
                referenceAlpha(phase, shape, artworkRingAlpha),
                ringAlpha(phase, artworkRingAlpha));
        }

        private static double referenceAlpha(int phase, double shape, double artworkRingAlpha) {
            if (phase == 1) {
                return 1.0d;
            }
            if (phase == 2 || phase == 3) {
                return (1.0d - shape) * (1.0d - artworkRingAlpha);
            }
            return 0.0d;
        }

        private static double ringAlpha(int phase, double artworkRingAlpha) {
            if (phase == 2) {
                return 1.0d;
            }
            if (phase == 3) {
                return 1.0d - artworkRingAlpha;
            }
            return 0.0d;
        }

        private Frame(int phase, double shape, double yaw, double roll, double pitch,
                      double projectionX, double trailAlpha, double glowAlpha, double highlightAlpha,
                      double faceAlpha, double shackleAngle, double artworkRingAlpha,
                      double projectionSettle, boolean complete, double referenceAlpha, double ringAlpha) {
            this.phase = phase;
            this.shape = FaceIslandMotion.clamp01(shape);
            this.yaw = FaceIslandMotion.finiteOrZero(yaw);
            this.roll = FaceIslandMotion.finiteOrZero(roll);
            this.pitch = FaceIslandMotion.finiteOrZero(pitch);
            this.projectionX = FaceIslandMotion.clampProjectedWidth(projectionX);
            this.trailAlpha = FaceIslandMotion.clamp01(trailAlpha);
            this.glowAlpha = FaceIslandMotion.clamp01(glowAlpha);
            this.highlightAlpha = FaceIslandMotion.clamp01(highlightAlpha);
            this.faceAlpha = FaceIslandMotion.clamp01(faceAlpha);
            this.shackleAngle = FaceIslandMotion.finiteOrZero(shackleAngle);
            this.artworkRingAlpha = FaceIslandMotion.clamp01(artworkRingAlpha);
            this.projectionSettle = FaceIslandMotion.clamp01(projectionSettle);
            this.referenceAlpha = FaceIslandMotion.clamp01(referenceAlpha);
            this.ringAlpha = FaceIslandMotion.clamp01(ringAlpha);
            this.complete = complete;
        }
    }

    public static Frame sample(int phase, boolean faceSuccess, long scanStartMs, long successStartMs, long nowMs) {
        if (phase <= 0) {
            return closedFrame();
        }
        if (phase == 1) {
            return idleFrame();
        }
        if (phase == 2) {
            if (faceSuccess && successStartMs >= 0 && nowMs >= successStartMs) {
                return sampleSuccess(scanStartMs, successStartMs, nowMs);
            }
            return sampleScan(scanStartMs, nowMs);
        }
        if (phase == 4) {
            return successHold();
        }
        if (phase == 3 || (faceSuccess && successStartMs >= 0 && nowMs >= successStartMs)) {
            long start = successStartMs < 0 ? nowMs : successStartMs;
            return sampleSuccess(scanStartMs, start, nowMs);
        }
        return idleFrame();
    }

    public static Frame sampleScan(long scanStartMs, long nowMs) {
        return sampleScan(scanStartMs, nowMs, 0.0d);
    }

    public static Frame sampleScan(long scanStartMs, long nowMs, double yawOffset) {
        double yaw;
        double trail;
        double shape;
        if (scanStartMs >= 0) {
            double elapsed = nonNegativeDifference(nowMs, scanStartMs);
            if (elapsed < 400.0d) {
                double progress = elapsed / 400.0d;
                double shape2 = smoothStep(progress);
                double trail2 = (0.82d * shape2) + 0.18d;
                yaw = (SCAN_ANGULAR_SPEED * elapsed) / 1000.0d;
                trail = trail2;
                shape = shape2;
            } else {
                double cycle = positiveModulo(elapsed - 400.0d, 1200.0d);
                yaw = SCAN_ANGULAR_SPEED * ((cycle / 1000.0d) + 0.4d);
                trail = 1.0d;
                shape = 1.0d;
            }
            double cycle2 = finiteOrZero(yawOffset);
            double yaw2 = yaw + cycle2;
            double pitch = FaceIslandGeometry.pitchForPhase(yaw2);
            double roll = FaceIslandGeometry.rollForPhase(yaw2);
            double width = projectedWidth(yaw2, pitch, roll);
            return frame(2, shape, yaw2, roll, pitch, width, trail, 1.0d, 1.0d, 0.0d, 0.0d, 0.0d, 0.0d, false);
        }
        return idleFrame();
    }

    public static Frame sampleReturn(Frame origin, Frame next, long startMs, long nowMs) {
        double elapsed = nonNegativeDifference(nowMs, startMs);
        double yawSpeed = angleVelocity(origin.yaw, next.yaw);
        double yawEnd = origin.yaw + ((yawSpeed * 250.0d) / 2000.0d);
        if (elapsed >= 250.0d) {
            return visualFrame(1, 0.0d, yawEnd, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }
        double t = elapsed / 250.0d;
        double shape = hermite(origin.shape, 0.0d, velocity(origin.shape, next.shape), 0.0d, 250.0d, t);
        double yaw = hermite(origin.yaw, yawEnd, yawSpeed, 0.0d, 250.0d, t);
        double pitch = hermite(origin.pitch, 0.0d, velocity(origin.pitch, next.pitch), 0.0d, 250.0d, t);
        double roll = hermite(origin.roll, 0.0d, velocity(origin.roll, next.roll), 0.0d, 250.0d, t);
        return visualFrame(5, shape, yaw, pitch, roll,
            fade(origin.trailAlpha, next.trailAlpha, 250.0d, t),
            fade(origin.glowAlpha, next.glowAlpha, 250.0d, t),
            fade(origin.highlightAlpha, next.highlightAlpha, 250.0d, t),
            hermite(origin.referenceAlpha, 1.0d, velocity(origin.referenceAlpha, next.referenceAlpha), 0.0d, 250.0d, t),
            origin.ringAlpha * (1.0d - smoothStep(t)), 0.0d, 0.0d, 0.0d, 0.0d);
    }

    public static double failureShakeOffset(long startMs, long nowMs) {
        if (startMs < 0 || nowMs <= startMs) {
            return 0.0d;
        }
        double elapsed = nonNegativeDifference(nowMs, startMs);
        if (elapsed >= 250.0d) {
            return 0.0d;
        }
        double progress = elapsed / 250.0d;
        double envelope = 1.0d - smoothStep(progress);
        return FAILURE_SHAKE_AMPLITUDE * envelope * Math.sin(12.566370614359172d * progress);
    }

    public static Frame sampleResume(Frame origin, Frame next, Frame targetStart, Frame targetNext, Frame targetNow, long startMs, long nowMs) {
        double elapsed = nonNegativeDifference(nowMs, startMs);
        if (elapsed >= 200.0d) {
            return targetNow;
        }
        double t = elapsed / 200.0d;
        return visualFrame(2,
            resume(origin.shape, next.shape, targetStart.shape, targetNext.shape, targetNow.shape, t),
            resumeAngle(origin.yaw, next.yaw, targetStart.yaw, targetNext.yaw, targetNow.yaw, t),
            resume(origin.pitch, next.pitch, targetStart.pitch, targetNext.pitch, targetNow.pitch, t),
            resume(origin.roll, next.roll, targetStart.roll, targetNext.roll, targetNow.roll, t),
            resume(origin.trailAlpha, next.trailAlpha, targetStart.trailAlpha, targetNext.trailAlpha, targetNow.trailAlpha, t),
            resume(origin.glowAlpha, next.glowAlpha, targetStart.glowAlpha, targetNext.glowAlpha, targetNow.glowAlpha, t),
            resume(origin.highlightAlpha, next.highlightAlpha, targetStart.highlightAlpha, targetNext.highlightAlpha, targetNow.highlightAlpha, t),
            resume(origin.referenceAlpha, next.referenceAlpha, targetStart.referenceAlpha, targetNext.referenceAlpha, targetNow.referenceAlpha, t),
            resume(origin.ringAlpha, next.ringAlpha, targetStart.ringAlpha, targetNext.ringAlpha, targetNow.ringAlpha, t),
            0.0d, 0.0d, 0.0d, 0.0d);
    }

    public static Frame sampleSuccessFrom(Frame origin, Frame next, long startMs, long nowMs) {
        double artworkRing;
        double elapsed = nonNegativeDifference(nowMs, startMs);
        if (elapsed >= 800.0d) {
            return successHold();
        }
        double t = Math.min(1.0d, elapsed / 420.0d);
        double yawSpeed = angleVelocity(origin.yaw, next.yaw);
        double yaw = hermite(origin.yaw, origin.yaw + ((yawSpeed * 420.0d) / 2000.0d), yawSpeed, 0.0d, 420.0d, t);
        double shape = hermite(origin.shape, 1.0d, velocity(origin.shape, next.shape), 0.0d, 420.0d, t);
        double pitch = hermite(origin.pitch, 0.0d, velocity(origin.pitch, next.pitch), 0.0d, 420.0d, t);
        double d = origin.roll;
        double d2 = origin.roll;
        double pitch2 = next.roll;
        double roll = hermite(d, 0.0d, velocity(d2, pitch2), 0.0d, 420.0d, t);
        if (origin.ringAlpha > 0.001d) {
            artworkRing = smoothStep((elapsed - 420.0d) / 380.0d);
        } else {
            artworkRing = smoothStep(elapsed / 420.0d);
        }
        double reference = fade(origin.referenceAlpha, next.referenceAlpha, 420.0d, t);
        double shackle = unlockProgress((elapsed - 420.0d) / 200.0d) * (-28.0d);
        double fade = fade(origin.trailAlpha, next.trailAlpha, 420.0d, t);
        double d3 = origin.glowAlpha;
        double d4 = origin.glowAlpha;
        double roll2 = next.glowAlpha;
        return visualFrame(3, shape, yaw, pitch, roll, fade,
            hermite(d3, 0.36d, velocity(d4, roll2), 0.0d, 420.0d, t),
            fade(origin.highlightAlpha, next.highlightAlpha, 420.0d, t), reference,
            origin.ringAlpha * (1.0d - artworkRing), smootherStep(t), artworkRing,
            smoothStep((elapsed - 420.0d) / 320.0d), shackle);
    }

    private static Frame visualFrame(int phase, double shape, double yaw, double pitch, double roll,
                                     double trail, double glow, double highlight, double reference,
                                     double ring, double settle, double artworkRing, double face, double shackle) {
        double width = FaceIslandGeometry.blendedHorizontalExtent(yaw, pitch, roll, settle);
        return new Frame(phase, shape, yaw, roll, pitch, width, trail, glow, highlight, face, shackle,
            artworkRing, settle, false, reference, ring);
    }

    private static double fade(double value, double next, double duration, double t) {
        return hermite(value, 0.0d, velocity(value, next), 0.0d, duration, t);
    }

    private static double velocity(double value, double next) {
        return (next - value) * 1000.0d;
    }

    private static double angleVelocity(double value, double next) {
        double delta = next - value;
        while (delta > 3.141592653589793d) {
            delta -= TAU;
        }
        while (delta < -3.141592653589793d) {
            delta += TAU;
        }
        return 1000.0d * delta;
    }

    private static double resume(double origin, double next, double start, double startNext, double target, double t) {
        return target + hermite(origin - start, 0.0d, velocity(origin, next) - velocity(start, startNext), 0.0d, 200.0d, t);
    }

    private static double resumeAngle(double origin, double next, double start, double startNext, double target, double t) {
        return target + hermite(origin - start, 0.0d, angleVelocity(origin, next) - angleVelocity(start, startNext), 0.0d, 200.0d, t);
    }

    private static Frame sampleSuccess(long scanStartMs, long successStartMs, long nowMs) {
        Frame origin;
        double d;
        double elapsed = nonNegativeDifference(nowMs, successStartMs);
        if (elapsed >= 800.0d) {
            return successHold();
        }
        boolean hasScan = scanStartMs >= 0;
        if (hasScan) {
            origin = sampleScan(scanStartMs, successStartMs);
        } else {
            origin = idleFrame();
        }
        double shapeVelocity = hasScan ? scanShapeVelocity(scanStartMs, successStartMs) : 0.0d;
        double yawVelocity = hasScan ? SCAN_ANGULAR_SPEED : 0.0d;
        double pitchVelocity = hasScan ? Math.cos(origin.yaw + 0.3d) * 0.55d * SCAN_ANGULAR_SPEED : 0.0d;
        if (hasScan) {
            d = (((-Math.toRadians(25.0d)) * Math.cos(origin.yaw)) + (Math.cos(origin.yaw * FAILURE_SHAKE_CYCLES) * 0.8d)) * SCAN_ANGULAR_SPEED;
        } else {
            d = 0.0d;
        }
        double rollVelocity = d;
        double trailVelocity = hasScan ? scanTrailVelocity(scanStartMs, successStartMs) : 0.0d;
        double progress = Math.min(1.0d, elapsed / 420.0d);
        double yawTarget = origin.yaw + ((yawVelocity * 420.0d) / 2000.0d);
        double shape = hermite(origin.shape, 1.0d, shapeVelocity, 0.0d, 420.0d, progress);
        double yaw = hermite(origin.yaw, yawTarget, yawVelocity, 0.0d, 420.0d, progress);
        double roll = hermite(origin.roll, 0.0d, rollVelocity, 0.0d, 420.0d, progress);
        double pitch = hermite(origin.pitch, 0.0d, pitchVelocity, 0.0d, 420.0d, progress);
        double trail = hasScan ? hermite(origin.trailAlpha, 0.0d, trailVelocity, 0.0d, 420.0d, progress) : 0.0d;
        double glow = hermite(origin.glowAlpha, 0.36d, 0.0d, 0.0d, 420.0d, progress);
        double face = smoothStep((elapsed - 420.0d) / 320.0d);
        double ring = smoothStep((elapsed - 420.0d) / 380.0d);
        double projectionSettle = smootherStep(progress);
        double projection = FaceIslandGeometry.blendedHorizontalExtent(yaw, pitch, roll, projectionSettle);
        double shackleProgress = unlockProgress((elapsed - 420.0d) / 200.0d);
        return frame(3, shape, yaw, roll, pitch, projection, trail, glow, 1.0d - smoothStep(progress),
            face, shackleProgress * (-28.0d), ring, projectionSettle, false);
    }

    private static double scanShapeVelocity(long scanStartMs, long atMs) {
        double elapsed = nonNegativeDifference(atMs, scanStartMs);
        if (elapsed >= 400.0d) {
            return 0.0d;
        }
        double progress = elapsed / 400.0d;
        return (smoothStepDerivative(progress) * 1000.0d) / 400.0d;
    }

    private static double scanTrailVelocity(long scanStartMs, long atMs) {
        return scanShapeVelocity(scanStartMs, atMs) * 0.82d;
    }

    private static double projectedWidth(double yaw, double pitch, double roll) {
        return Math.max(MIN_PROJECTED_WIDTH, FaceIslandGeometry.horizontalExtent(yaw, pitch, roll));
    }

    private static Frame closedFrame() {
        return frame(0, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, false);
    }

    private static Frame idleFrame() {
        return frame(1, 0.0d, 0.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, false);
    }

    private static Frame successHold() {
        return frame(4, 1.0d, 0.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.36d, 0.0d, 1.0d, -28.0d, 1.0d, 1.0d, true);
    }

    private static Frame frame(int phase, double shape, double yaw, double roll, double pitch,
                               double projectionX, double trailAlpha, double glowAlpha, double highlightAlpha,
                               double faceAlpha, double shackleAngle, double artworkRingAlpha,
                               double projectionSettle, boolean complete) {
        return new Frame(phase, shape, yaw, roll, pitch, projectionX, trailAlpha, glowAlpha, highlightAlpha,
            faceAlpha, shackleAngle, artworkRingAlpha, projectionSettle, complete);
    }

    private static double hermite(double start, double end, double velocityPerSecond, double endVelocityPerSecond, double durationMs, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        double startTangent = (velocityPerSecond * durationMs) / 1000.0d;
        double endTangent = (endVelocityPerSecond * durationMs) / 1000.0d;
        return ((((t3 * FAILURE_SHAKE_CYCLES) - (t2 * FAILURE_SHAKE_AMPLITUDE)) + 1.0d) * start)
            + (((t3 - (FAILURE_SHAKE_CYCLES * t2)) + t) * startTangent)
            + ((((-2.0d) * t3) + (FAILURE_SHAKE_AMPLITUDE * t2)) * end)
            + ((t3 - t2) * endTangent);
    }

    private static double smoothStep(double t) {
        double value = clamp01(t);
        return value * value * (FAILURE_SHAKE_AMPLITUDE - (FAILURE_SHAKE_CYCLES * value));
    }

    private static double smootherStep(double t) {
        double value = clamp01(t);
        return value * value * value * ((((6.0d * value) - 15.0d) * value) + 10.0d);
    }

    private static double smoothStepDerivative(double t) {
        double value = clamp01(t);
        return 6.0d * value * (1.0d - value);
    }

    private static double unlockProgress(double t) {
        double targetX = clamp01(t);
        if (targetX <= 0.0d || targetX >= 1.0d) {
            return targetX;
        }
        double low = 0.0d;
        double high = 1.0d;
        for (int iteration = 0; iteration < 14; iteration++) {
            double u = (low + high) * 0.5d;
            double inverse = 1.0d - u;
            double x = (inverse * FAILURE_SHAKE_AMPLITUDE * inverse * u * 0.35d)
                + (FAILURE_SHAKE_AMPLITUDE * inverse * u * u * 0.2d) + (u * u * u);
            if (x < targetX) {
                low = u;
            } else {
                high = u;
            }
        }
        double u2 = (low + high) * 0.5d;
        return (FAILURE_SHAKE_AMPLITUDE * (1.0d - u2) * u2 * u2) + (u2 * u2 * u2);
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0d ? result + modulus : result;
    }

    private static double nonNegativeDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0.0d;
        }
        return later - earlier;
    }

    public static double clamp01(double value) {
        if (Double.isNaN(value) || value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    public static double finiteOrZero(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return value;
    }

    public static double clampProjectedWidth(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < MIN_PROJECTED_WIDTH) {
            return MIN_PROJECTED_WIDTH;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
