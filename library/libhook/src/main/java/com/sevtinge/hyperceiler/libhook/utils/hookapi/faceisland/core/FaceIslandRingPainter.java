package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes8.dex */
public final class FaceIslandRingPainter {
    private static final double[] CIRCLE_COS = createCircleCoordinates(true);
    private static final double[] CIRCLE_SIN = createCircleCoordinates(false);
    private static final int DEPTH_BANDS = 16;
    private static final int HIGHLIGHT_COLOR = -3604539;
    private static final int HISTORY_SAMPLES = 34;
    private static final int RING_COLOR = -9175176;
    private static final int SAMPLE_COUNT = 144;
    private static final double TAU = 6.283185307179586d;
    private Bitmap historyBitmap;
    private final int historyBitmapSize;
    private final RectF historyBounds;
    private Canvas historyCanvas;
    private final float historyHalfExtent;
    private final float historyPointScale;
    private final float pointScale;
    private final Paint paint = new Paint(1);
    private final Paint historyPaint = new Paint(1);
    private final Paint bitmapPaint = new Paint(3);
    private final Path[] depthPaths = new Path[16];
    private final Path historyPath = new Path();
    private final int[] lastPointByBand = new int[16];
    private final double[] rotation = new double[9];
    private final double[] projectedPoint = new double[3];
    private final double[] blendedPoint = new double[2];
    private final float[] ringPoints = new float[432];
    private final Path highlightPath = new Path();

    public FaceIslandRingPainter(float glyphRadius) {
        this.pointScale = glyphRadius / 80.0f;
        this.historyPointScale = Math.max(this.pointScale, 0.114999995f);
        float blurRadius = this.historyPointScale * 14.0f;
        float trailStrokeWidth = this.historyPointScale * 11.0f;
        float blurPadding = (float) Math.ceil(((0.57735f * blurRadius) + 0.5f) * 3.0f);
        float halfExtent = (this.historyPointScale * 80.0f) + blurPadding + (trailStrokeWidth * 0.5f) + 0.5f;
        this.historyBitmapSize = Math.max(1, (int) Math.ceil(2.0f * halfExtent));
        this.historyHalfExtent = this.historyBitmapSize * 0.5f;
        float displayHalfExtent = (this.historyHalfExtent * this.pointScale) / this.historyPointScale;
        this.historyBounds = new RectF(-displayHalfExtent, -displayHalfExtent, displayHalfExtent, displayHalfExtent);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeJoin(Paint.Join.ROUND);
        this.historyPaint.setStyle(Paint.Style.STROKE);
        this.historyPaint.setStrokeCap(Paint.Cap.ROUND);
        this.historyPaint.setStrokeJoin(Paint.Join.ROUND);
        this.historyPaint.setColor(RING_COLOR);
        this.historyPaint.setStrokeWidth(trailStrokeWidth);
        this.historyPaint.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
        this.historyPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        for (int index = 0; index < 16; index++) {
            this.depthPaths[index] = new Path();
        }
    }

    public void draw(Canvas canvas, FaceIslandMotion.Frame frame, float ringAlpha, float historyAlpha, float projectionSettleAlpha) {
        if (ringAlpha <= 0.001f || frame.shape <= 0.001d) {
            return;
        }
        float overall = clamp(((float) frame.glowAlpha) * ringAlpha);
        float settle = clamp(projectionSettleAlpha);
        float trailStrength = clamp(historyAlpha);
        float historyStrength = clamp(trailStrength * overall);
        if (historyStrength > 0.001f) {
            drawHistory(canvas, frame.yaw, frame.pitch, frame.roll, frame.shape, historyStrength, settle);
        }
        drawRingPose(canvas, frame.yaw, frame.pitch, frame.roll, frame.shape, overall, clamp(((float) frame.highlightAlpha) * ringAlpha), settle, 0.8f, 1.0f, 1.5707963267948966d);
        double oldPhase = frame.yaw - 0.82d;
        double oldPitch = (frame.pitch + FaceIslandGeometry.pitchForPhase(oldPhase)) - FaceIslandGeometry.pitchForPhase(frame.yaw);
        double oldRoll = (frame.roll + FaceIslandGeometry.rollForPhase(oldPhase)) - FaceIslandGeometry.rollForPhase(frame.yaw);
        drawRingPose(canvas, oldPhase, oldPitch, oldRoll, frame.shape, overall * trailStrength, clamp(((float) frame.highlightAlpha) * ringAlpha * trailStrength), settle, 0.46f, 0.575f, 0.25d);
    }

    private void drawHistory(Canvas canvas, double currentYaw, double currentPitch, double currentRoll, double shape, float strength, float settle) {
        ensureHistoryBitmap();
        this.historyBitmap.eraseColor(0);
        this.historyCanvas.save();
        this.historyCanvas.translate(this.historyHalfExtent, this.historyHalfExtent);
        double currentPitchByPhase = FaceIslandGeometry.pitchForPhase(currentYaw);
        double currentRollByPhase = FaceIslandGeometry.rollForPhase(currentYaw);
        for (int distance = 34; distance >= 1; distance--) {
            double phase = currentYaw - (distance * 0.04d);
            double pitch = (currentPitch + FaceIslandGeometry.pitchForPhase(phase)) - currentPitchByPhase;
            double roll = (currentRoll + FaceIslandGeometry.rollForPhase(phase)) - currentRollByPhase;
            this.historyPath.reset();
            appendCircle(this.historyPath, phase, pitch, roll, shape, settle);
            double recency = 1.0d - (distance / 35.0d);
            float energy = (float) ((Math.pow(recency, 1.25d) * 0.055d) + 0.02d);
            this.historyPaint.setAlpha(Math.round(clamp(energy) * 255.0f));
            this.historyCanvas.drawPath(this.historyPath, this.historyPaint);
        }
        this.historyCanvas.restore();
        this.bitmapPaint.setAlpha(Math.round(255.0f * strength));
        canvas.drawBitmap(this.historyBitmap, (Rect) null, this.historyBounds, this.bitmapPaint);
    }

    private void ensureHistoryBitmap() {
        if (this.historyBitmap == null) {
            this.historyBitmap = Bitmap.createBitmap(this.historyBitmapSize, this.historyBitmapSize, Bitmap.Config.ARGB_8888);
            this.historyBitmap.setDensity(0);
            this.historyCanvas = new Canvas(this.historyBitmap);
        }
    }

    public void release() {
        this.historyBitmap = null;
        this.historyCanvas = null;
    }

    private void drawRingPose(Canvas canvas, double yaw, double pitch, double roll, double shape, float overall, float highlightAlpha, float settle, float energyStrength, float coreStrength, double highlightCenter) {
        if (overall <= 0.001f) {
            return;
        }
        buildDepthPaths(yaw, pitch, roll, shape, settle);
        buildHighlightPath(yaw, pitch, roll, shape, settle, highlightCenter);
        float energy = overall * energyStrength;
        drawDepthPaths(canvas, this.pointScale * 24.0f, 0.22f * energy);
        drawDepthPaths(canvas, this.pointScale * 10.0f, 0.38f * energy);
        drawDepthPaths(canvas, this.pointScale * 6.0f, overall * coreStrength);
        drawDepthPaths(canvas, this.pointScale * 13.0f, energy);
        drawHighlight(canvas, highlightAlpha * energyStrength);
    }

    private void buildDepthPaths(double yaw, double pitch, double roll, double shape, float settle) {
        FaceIslandGeometry.setRotation(yaw, pitch, roll, this.rotation);
        for (int band = 0; band < 16; band++) {
            this.depthPaths[band].reset();
            this.lastPointByBand[band] = -1;
        }
        double radius = 80.0d * shape;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            FaceIslandGeometry.projectPoint(radius * CIRCLE_COS[index], radius * CIRCLE_SIN[index], 0.0d, this.rotation, this.projectedPoint, 0);
            FaceIslandGeometry.blendProjectedPoint(this.projectedPoint[0] - 93.0d, this.projectedPoint[1] - 93.0d, radius * CIRCLE_COS[index], radius * CIRCLE_SIN[index], settle, this.blendedPoint, 0);
            int offset = index * 3;
            this.ringPoints[offset] = (float) (this.blendedPoint[0] * this.pointScale);
            this.ringPoints[offset + 1] = (float) (this.blendedPoint[1] * this.pointScale);
            this.ringPoints[offset + 2] = (float) (this.projectedPoint[2] * (1.0d - settle));
        }
        for (int index2 = 0; index2 < SAMPLE_COUNT; index2++) {
            int next = (index2 + 1) % SAMPLE_COUNT;
            int startOffset = index2 * 3;
            int endOffset = next * 3;
            double depth = (this.ringPoints[startOffset + 2] + this.ringPoints[endOffset + 2]) * 0.5d;
            int band2 = depthBand(depth);
            Path path = this.depthPaths[band2];
            int i = this.lastPointByBand[band2];
            float[] fArr = this.ringPoints;
            if (i == index2) {
                path.lineTo(fArr[endOffset], this.ringPoints[endOffset + 1]);
            } else {
                path.moveTo(fArr[startOffset], this.ringPoints[startOffset + 1]);
                path.lineTo(this.ringPoints[endOffset], this.ringPoints[endOffset + 1]);
            }
            this.lastPointByBand[band2] = next;
        }
    }

    private void buildHighlightPath(double yaw, double pitch, double roll, double shape, float settle, double centerAngle) {
        FaceIslandGeometry.setRotation(yaw, pitch, roll, this.rotation);
        this.highlightPath.reset();
        double radius = 80.0d * shape;
        for (int index = 0; index < 32; index++) {
            double angle = (centerAngle - 0.55d) + ((index * 1.1d) / 31.0d);
            FaceIslandGeometry.projectPoint(radius * Math.cos(angle), radius * Math.sin(angle), 0.0d, this.rotation, this.projectedPoint, 0);
            FaceIslandGeometry.blendProjectedPoint(this.projectedPoint[0] - 93.0d, this.projectedPoint[1] - 93.0d, radius * Math.cos(angle), radius * Math.sin(angle), settle, this.blendedPoint, 0);
            float x = (float) (this.blendedPoint[0] * this.pointScale);
            float y = (float) (this.blendedPoint[1] * this.pointScale);
            Path path = this.highlightPath;
            if (index == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
    }

    private void appendCircle(Path path, double yaw, double pitch, double roll, double shape, float settle) {
        FaceIslandGeometry.setRotation(yaw, pitch, roll, this.rotation);
        double radius = 80.0d * shape;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            FaceIslandGeometry.projectPoint(radius * CIRCLE_COS[index], radius * CIRCLE_SIN[index], 0.0d, this.rotation, this.projectedPoint, 0);
            FaceIslandGeometry.blendProjectedPoint(this.projectedPoint[0] - 93.0d, this.projectedPoint[1] - 93.0d, radius * CIRCLE_COS[index], radius * CIRCLE_SIN[index], settle, this.blendedPoint, 0);
            float x = (float) (this.blendedPoint[0] * this.historyPointScale);
            float y = (float) (this.blendedPoint[1] * this.historyPointScale);
            if (index == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    private void drawDepthPaths(Canvas canvas, float width, float alpha) {
        if (alpha <= 0.001f) {
            return;
        }
        this.paint.setColor(RING_COLOR);
        this.paint.setStrokeWidth(width);
        for (int band = 0; band < 16; band++) {
            if (!this.depthPaths[band].isEmpty()) {
                double depth = ((((band + 0.5d) / 16.0d) * 2.0d) - 1.0d) * 80.0d;
                this.paint.setAlpha(Math.round(clamp(alpha) * 255.0f * ((float) FaceIslandGeometry.depthBrightness(depth))));
                canvas.drawPath(this.depthPaths[band], this.paint);
            }
        }
    }

    private void drawHighlight(Canvas canvas, float alpha) {
        if (alpha <= 0.001f || this.highlightPath.isEmpty()) {
            return;
        }
        this.paint.setColor(HIGHLIGHT_COLOR);
        this.paint.setAlpha(Math.round(clamp(alpha) * 255.0f));
        this.paint.setStrokeWidth(this.pointScale * 5.0f);
        canvas.drawPath(this.highlightPath, this.paint);
    }

    private static int depthBand(double depth) {
        int band = (int) (((depth / 80.0d) + 1.0d) * 0.5d * 16.0d);
        if (band < 0) {
            return 0;
        }
        if (band >= 16) {
            return 15;
        }
        return band;
    }

    private static double[] createCircleCoordinates(boolean cosine) {
        double[] values = new double[SAMPLE_COUNT];
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            double angle = (index * TAU) / 144.0d;
            values[index] = cosine ? Math.cos(angle) : Math.sin(angle);
        }
        return values;
    }

    private static float clamp(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        if (value > 1.0f) {
            return 1.0f;
        }
        return value;
    }
}
