package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandGeometry {
    private static final double ROLL_BASE = -0.2617993877991494d;
    private static final double ROLL_SWING = Math.toRadians(25.0d);
    public static final double SOURCE_CENTER = 93.0d;
    public static final double SOURCE_RADIUS = 80.0d;
    public static final double TRAIL_PHASE_OFFSET = 0.82d;
    public static final double TRAIL_SAMPLE_PHASE_STEP = 0.04d;

    private FaceIslandGeometry() {
    }

    public static double pitchForPhase(double phase) {
        return (Math.sin(0.3d + phase) * 0.55d) + 0.35d;
    }

    public static double rollForPhase(double phase) {
        return (ROLL_BASE - (ROLL_SWING * Math.sin(phase))) + (Math.sin(2.0d * phase) * 0.4d);
    }

    public static void setRotation(double yaw, double pitch, double roll, double[] matrix) {
        if (matrix == null || matrix.length < 9) {
            throw new IllegalArgumentException("旋转矩阵至少需要 9 个数值");
        }
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);
        matrix[0] = cosRoll * cosYaw;
        matrix[1] = ((cosRoll * sinYaw) * sinPitch) - (sinRoll * cosPitch);
        matrix[2] = (cosRoll * sinYaw * cosPitch) + (sinRoll * sinPitch);
        matrix[3] = sinRoll * cosYaw;
        matrix[4] = (sinRoll * sinYaw * sinPitch) + (cosRoll * cosPitch);
        matrix[5] = ((sinRoll * sinYaw) * cosPitch) - (cosRoll * sinPitch);
        matrix[6] = -sinYaw;
        matrix[7] = cosYaw * sinPitch;
        matrix[8] = cosYaw * cosPitch;
    }

    public static void projectPoint(double x, double y, double z, double[] matrix, double[] output, int offset) {
        if (matrix == null || matrix.length < 9 || output == null || offset < 0 || offset + 2 >= output.length) {
            throw new IllegalArgumentException("投影矩阵或输出数组长度无效");
        }
        output[offset] = (matrix[0] * x) + SOURCE_CENTER + (matrix[1] * y) + (matrix[2] * z);
        output[offset + 1] = (matrix[3] * x) + SOURCE_CENTER + (matrix[4] * y) + (matrix[5] * z);
        output[offset + 2] = (matrix[6] * x) + (matrix[7] * y) + (matrix[8] * z);
    }

    public static double horizontalExtent(double yaw, double pitch, double roll) {
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);
        double xx = cosRoll * cosYaw;
        double xy = ((cosRoll * sinYaw) * sinPitch) - (sinRoll * cosPitch);
        return Math.sqrt((xx * xx) + (xy * xy));
    }

    public static double blendedHorizontalExtent(double yaw, double pitch, double roll, double settle) {
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosRoll = Math.cos(roll);
        double sinRoll = Math.sin(roll);
        double weight = clamp01(settle);
        double horizontalX = ((1.0d - weight) * cosRoll * cosYaw) + weight;
        double horizontalY = (1.0d - weight) * (((cosRoll * sinYaw) * sinPitch) - (sinRoll * cosPitch));
        return Math.sqrt((horizontalX * horizontalX) + (horizontalY * horizontalY));
    }

    public static void blendProjectedPoint(double x, double y, double targetX, double targetY, double settle, double[] output, int offset) {
        if (output == null || offset < 0 || offset + 1 >= output.length) {
            throw new IllegalArgumentException("收束输出数组长度无效");
        }
        double weight = clamp01(settle);
        output[offset] = ((targetX - x) * weight) + x;
        output[offset + 1] = ((targetY - y) * weight) + y;
    }

    public static double horizontalExtentForPhase(double phase) {
        return horizontalExtent(phase, pitchForPhase(phase), rollForPhase(phase));
    }

    public static double depthBrightness(double depth) {
        double brightness = ((0.2d * depth) / SOURCE_RADIUS) + 0.8d;
        if (brightness < ROLL_SWING) {
            return ROLL_SWING;
        }
        if (brightness > 1.0d) {
            return 1.0d;
        }
        return brightness;
    }

    private static double clamp01(double value) {
        if (value < ROLL_SWING) {
            return ROLL_SWING;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }
}
