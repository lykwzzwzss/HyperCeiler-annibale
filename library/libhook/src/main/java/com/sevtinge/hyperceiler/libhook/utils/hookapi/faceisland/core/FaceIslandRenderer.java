package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes8.dex */
public final class FaceIslandRenderer extends View {
    private static final int FRAME_COLOR = -1376274;
    private static final float LOCK_BODY_BOTTOM = 19.45f;
    private static final float LOCK_BODY_LEFT = 6.5f;
    private static final float LOCK_BODY_RIGHT = 17.35f;
    private static final float LOCK_BODY_TOP = 10.9f;
    private static final float LOCK_CLOSED_FIXED_ROOT_X = 15.3f;
    private static final float LOCK_CLOSED_FREE_X = 8.5f;
    private static final float LOCK_CLOSED_SHACKLE_SPAN = 6.8f;
    private static final int LOCK_COLOR = -1;
    private static final float LOCK_DRAW_SIZE_DP = 23.4f;
    private static final float LOCK_OPEN_FIXED_ROOT_X = 14.0f;
    private static final float LOCK_OPEN_FREE_END_Y = 9.55f;
    private static final float LOCK_OPEN_SHACKLE_SPAN = 5.8f;
    private static final float LOCK_SHACKLE_CONTROL_Y = 3.75f;
    private static final float LOCK_SHACKLE_TOP = 7.35f;
    private static final int SIDE_FACE = 1;
    private static final int SIDE_LOCK = 0;
    private final FaceIslandArtwork artwork;
    private final boolean checkGlyphMode;
    private final FaceIslandController controller;
    private final long cycle;
    private final float density;
    private final boolean fake;
    private final float glyphRadius;
    private final FaceIslandHostView hostView;
    private final RectF lockBodyBounds;
    private final Paint paint;
    private final Path path;
    private final FaceIslandRingPainter ringPainter;
    private final int side;
    private final boolean small;
    private final long treeToken;
    private final long viewGen;
    private final Rect visibleRect;

    public FaceIslandRenderer(Context context, FaceIslandController controller, FaceIslandHostView hostView, int side, boolean fake, boolean small, boolean checkGlyphMode, long cycle, long viewGen, long treeToken) {
        super(context);
        this.paint = new Paint(5);
        this.path = new Path();
        this.lockBodyBounds = new RectF();
        this.visibleRect = new Rect();
        if (side != 0 && side != 1) {
            throw new IllegalArgumentException("side 只能是 0（锁）或 1（脸）");
        }
        if (controller == null) {
            throw new IllegalArgumentException("controller 不能为空");
        }
        if (hostView == null) {
            throw new IllegalArgumentException("hostView 不能为空");
        }
        this.controller = controller;
        this.hostView = hostView;
        this.side = side;
        this.fake = fake;
        this.small = small;
        this.checkGlyphMode = checkGlyphMode;
        this.cycle = cycle;
        this.viewGen = viewGen;
        this.treeToken = treeToken;
        this.density = getResources().getDisplayMetrics().density;
        this.glyphRadius = this.density * 9.2f;
        this.ringPainter = side == 1 ? new FaceIslandRingPainter(this.glyphRadius) : null;
        this.artwork = side == 1 ? FaceIslandArtwork.getInstance(context) : null;
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeJoin(Paint.Join.ROUND);
        this.paint.setColor(FRAME_COLOR);
        controller.registerRenderer(this);
    }

    @Override // android.view.View
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.round(this.density * 32.0f);
        int height = Math.round(this.density * 34.0f);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean notifyVisible = false;
        boolean actuallyVisible = isShown() && hasVisibleAlpha() && getGlobalVisibleRect(this.visibleRect) && !this.visibleRect.isEmpty();
        if (actuallyVisible && !this.fake) {
            notifyVisible = true;
        }
        FaceIslandSnapshot validation = this.controller.snapshot();
        if (!belongsToCurrentTree(validation)) {
            return;
        }
        if (notifyVisible && this.side == 1) {
            this.controller.onRendererVisible(this.cycle, this.viewGen);
        }
        FaceIslandSnapshot first = this.controller.snapshot();
        if (!belongsToCurrentTree(first)) {
            return;
        }
        FaceIslandMotion.Frame frame = first.frame;
        if (notifyVisible && this.side == 1) {
            this.controller.onRendererFrame(this.cycle, this.viewGen, frame.phase);
        }
        if (first.phase == 0 || frame.phase == 0) {
            return;
        }
        canvas.save();
        canvas.translate(getWidth() * 0.5f, getHeight() * 0.5f);
        if (this.side == 0) {
            drawLock(canvas, frame, first);
        } else {
            drawFace(canvas, frame);
        }
        canvas.restore();
        if (actuallyVisible && isAnimating(frame, first)) {
            postInvalidateOnAnimation();
        }
    }

    @Override // android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override // android.view.View
    protected void onDetachedFromWindow() {
        if (this.ringPainter != null) {
            this.ringPainter.release();
        }
        super.onDetachedFromWindow();
    }

    private boolean belongsToCurrentTree(FaceIslandSnapshot snapshot) {
        return this.hostView.isRendererCurrent(this.fake, this.small, this.cycle, this.viewGen, this.treeToken, snapshot);
    }

    private boolean hasVisibleAlpha() {
        View current = this;
        while (current != null) {
            if (current.getAlpha() <= 0.0f) {
                return false;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private boolean isAnimating(FaceIslandMotion.Frame frame, FaceIslandSnapshot snapshot) {
        if (frame.phase == 2 || frame.phase == 5) {
            return true;
        }
        if (frame.phase == 3 && snapshot.successStartMs >= 0 && snapshot.nowMs - snapshot.successStartMs < 800) {
            return true;
        }
        // 预览没有真实认证帧驱动，过渡阶段也要持续重绘，否则只会画出静态轮廓。
        return snapshot.debugPreview && frame.phase >= 2;
    }

    private void drawFace(Canvas canvas, FaceIslandMotion.Frame frame) {
        this.ringPainter.draw(canvas, frame, (float) frame.ringAlpha, (float) frame.trailAlpha, (float) frame.projectionSettle);
        drawArtwork(canvas, (float) frame.referenceAlpha, (float) frame.artworkRingAlpha, (float) frame.faceAlpha);
    }

    private void drawArtwork(Canvas canvas, float referenceAlpha, float successRingAlpha, float successFaceAlpha) {
        canvas.save();
        float scale = this.glyphRadius / 80.0f;
        canvas.scale(scale, scale);
        canvas.translate(-93.0f, -93.0f);
        this.artwork.drawReference(canvas, referenceAlpha);
        this.artwork.drawSuccessOuterRing(canvas, successRingAlpha);
        this.artwork.drawSuccessFeatures(canvas, successFaceAlpha, this.checkGlyphMode);
        canvas.restore();
    }

    private void drawLock(Canvas canvas, FaceIslandMotion.Frame frame, FaceIslandSnapshot snapshot) {
        float shakeX;
        float scale = (this.density * LOCK_DRAW_SIZE_DP) / 24.0f;
        canvas.scale(scale, scale);
        if (frame.phase == 5) {
            shakeX = (float) FaceIslandMotion.failureShakeOffset(snapshot.returnStartMs, snapshot.nowMs);
        } else {
            shakeX = 0.0f;
        }
        canvas.translate(shakeX - 12.0f, -12.0f);
        float openProgress = clamp01((-((float) frame.shackleAngle)) / 28.0f);
        this.paint.setColor(-1);
        this.paint.setAlpha(255);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeWidth((this.density * 1.9f) / scale);
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeJoin(Paint.Join.ROUND);
        drawShackle(canvas, openProgress);
        this.paint.setStyle(Paint.Style.FILL);
        this.lockBodyBounds.set(LOCK_BODY_LEFT, LOCK_BODY_TOP, LOCK_BODY_RIGHT, LOCK_BODY_BOTTOM);
        canvas.drawRoundRect(this.lockBodyBounds, 1.45f, 1.45f, this.paint);
    }

    private void drawShackle(Canvas canvas, float progress) {
        float fixedRoot = lerp(LOCK_CLOSED_FIXED_ROOT_X, LOCK_OPEN_FIXED_ROOT_X, progress);
        float signedSpan = lerp(-6.8f, LOCK_OPEN_SHACKLE_SPAN, progress);
        float freeX = fixedRoot + signedSpan;
        float freeEndY = lerp(11.0f, LOCK_OPEN_FREE_END_Y, progress);
        this.path.reset();
        this.path.moveTo(fixedRoot, 11.15f);
        this.path.lineTo(fixedRoot, LOCK_SHACKLE_TOP);
        this.path.cubicTo(fixedRoot, LOCK_SHACKLE_CONTROL_Y, freeX, LOCK_SHACKLE_CONTROL_Y, freeX, LOCK_SHACKLE_TOP);
        this.path.lineTo(freeX, freeEndY);
        canvas.drawPath(this.path, this.paint);
    }

    private static float lerp(float start, float end, float progress) {
        return ((end - start) * progress) + start;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
