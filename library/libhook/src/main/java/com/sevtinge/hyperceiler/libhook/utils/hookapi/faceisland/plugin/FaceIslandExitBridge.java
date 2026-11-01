package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import com.sevtinge.hyperceiler.common.log.XposedLog;

public final class FaceIslandExitBridge {
    private static final String KEY = "face_island_unlock";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Session current;

    private FaceIslandExitBridge() {
    }

    /**
     * 由旧版完整反编译还原；0.4 的常量与字段保持一致。
     */
    public static boolean begin(Context context, Object controller, Object data, long token, long durationMs) {
        Bitmap cleanup = null;
        Session session = null;
        if (Looper.myLooper() != Looper.getMainLooper() || context == null || controller == null || data == null) {
            return false;
        }
        try {
            Object displayManager = context.getSystemService("display");
            Object display = displayManager == null ? null
                : FaceIslandExitCapture.call(displayManager, "getDisplay", new Class[]{Integer.TYPE}, 0);
            XposedLog.d("FaceIslandExit", "begin token=" + token + " display=" + displayState(display)
                + " duration=" + durationMs);
            if (displayState(display) != 2) {
                return false;
            }
            Object content = FaceIslandExitCapture.call(controller, "getView");
            Object source = FaceIslandExitCapture.call(content, "getViewFromList", new Class[]{String.class}, KEY);
            if (source == null) {
                return false;
            }
            if (FaceIslandExitCapture.optional(source, "getCurrentIslandData") != data) {
                source = FaceIslandExitCapture.optional(source, "getRealView");
            }
            if (!(source instanceof View) || FaceIslandExitCapture.optional(source, "getCurrentIslandData") != data) {
                return false;
            }
            Object background = FaceIslandExitCapture.call(source, "getBackgroundView");
            Object bigIslandView = FaceIslandExitCapture.call(source, "getBigIslandView");
            if (!(background instanceof View) || !(bigIslandView instanceof View) || background == content) {
                return false;
            }
            FaceIslandExitCapture capture = FaceIslandExitCapture.take(
                (View) background, (View) source, (View) bigIslandView);
            cleanup = capture.bitmap;
            Object extras = FaceIslandExitCapture.optional(data, "getExtras");
            long cycle = extras instanceof Bundle ? ((Bundle) extras).getLong("face_island_cycle", -1L) : -1L;
            WindowManager windows = (WindowManager) context.getSystemService("window");
            if (windows == null) {
                throw new IllegalStateException("window_manager_missing");
            }
            ExitView view = new ExitView(context, capture);
            FaceIslandExitHandoff handoff = new FaceIslandExitHandoff(
                (View) background, (View) source, data, token, cycle, capture);
            final Session next = new Session(token, cycle, display, windows, view, handoff,
                Math.max(1L, Math.min(300L, durationMs > 0 ? durationMs : 250L)));
            session = next;
            view.firstDraw = new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda3
                @Override
                public void run() {
                    FaceIslandExitBridge.lambda$begin$0(next);
                }
            };
            view.hardwareDraw = new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda4
                @Override
                public void run() {
                    FaceIslandExitBridge.registerFrameCommit(next);
                }
            };
            int overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                capture.screenRect.width(), capture.screenRect.height(), 2017,
                overlayFlags, android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = 51;
            params.x = capture.screenRect.left;
            params.y = capture.screenRect.top;
            configureScreenCoordinates(params);
            params.setTitle("FaceIslandExit");
            windows.addView(view, params);
            Session previous = current;
            current = next;
            if (previous != null) {
                finish(previous, "REPLACED", !previous.firstFrameReady);
            }
            handoff.install();
            next.deadline = new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda5
                @Override
                public void run() {
                    FaceIslandExitBridge.lambda$begin$2(next);
                }
            };
            MAIN.postDelayed(next.deadline, next.durationMs + 150);
            return true;
        } catch (Throwable th) {
            // 视图可能仍在窗口上绘制；只有确认已经摘掉视图才能回收 Bitmap。
            boolean detached = true;
            if (session != null) {
                detached = finish(session, "BEGIN_ERROR", true);
            }
            if (detached) {
                recycle(cleanup);
            } else {
                XposedLog.w("FaceIslandExit", "bitmap kept for attached exit view");
            }
            return false;
        }
    }

    /**
     * 热重载清理：立刻收起当前退场浮层，避免旧 generation 的窗口残留。
     */
    public static void reset() {
        MAIN.removeCallbacksAndMessages(null);
        Session session = current;
        current = null;
        if (session != null) {
            finish(session, "HOT_RELOAD", true);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static void lambda$begin$0(Session session) {
        if (session.commitRegistered) {
            return;
        }
        firstDraw(session);
    }

    private static void lambda$begin$2(Session session) {
        if (current == session) {
            finish(session, "WATCHDOG", !session.firstFrameReady);
        }
    }

    private static void registerFrameCommit(final Session session) {
        if (current != session || session.finished || session.commitRegistered) {
            return;
        }
        session.hardwareDraw = true;
        session.frameCommit = new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda6
            @Override
            public void run() {
                MAIN.post(new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda1
                    @Override
                    public void run() {
                        FaceIslandExitBridge.lambda$registerFrameCommit$3(session);
                    }
                });
            }
        };
        try {
            FaceIslandExitCapture.call(session.view.getViewTreeObserver(),
                "registerFrameCommitCallback", new Class[]{Runnable.class}, session.frameCommit);
            session.commitRegistered = true;
            scheduleHardwareFallback(session, 48L);
        } catch (Throwable th) {
            scheduleHardwareFallback(session, 0L);
        }
    }

    private static void lambda$registerFrameCommit$3(Session session) {
        if (current != session || session.finished || session.firstDrawStarted) {
            return;
        }
        Runnable fallback = session.frameReadyFallback;
        if (fallback != null) {
            MAIN.removeCallbacks(fallback);
            session.frameReadyFallback = null;
        }
        firstDraw(session);
    }

    private static void scheduleHardwareFallback(final Session session, long delayMs) {
        if (current != session || session.finished || session.firstDrawStarted) {
            return;
        }
        Runnable fallback = new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda0
            @Override
            public void run() {
                FaceIslandExitBridge.lambda$scheduleHardwareFallback$5(session);
            }
        };
        session.frameReadyFallback = fallback;
        MAIN.postDelayed(fallback, Math.max(0L, delayMs));
    }

    private static void lambda$scheduleHardwareFallback$5(Session session) {
        session.frameReadyFallback = null;
        if (current != session || session.finished || session.firstDrawStarted) {
            return;
        }
        session.fallbackUsed = true;
        firstDraw(session);
    }

    public static void cancel(final long token) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda7
                @Override
                public void run() {
                    FaceIslandExitBridge.cancel(token);
                }
            });
            return;
        }
        Session session = current;
        if (token >= 0 && session != null && session.token == token) {
            finish(session, "HOST_CANCEL", !session.firstFrameReady);
        }
    }

    public static boolean removeSourceWhenReady(long token, Runnable removal) {
        Session session = current;
        if (Looper.myLooper() != Looper.getMainLooper() || session == null || session.token != token
            || session.sourceRemoval != null || removal == null) {
            return false;
        }
        session.sourceRemoval = removal;
        if (session.animationAt >= 0) {
            removeSource(session);
        }
        return true;
    }

    private static boolean removeSource(Session session) {
        Runnable removal = session.sourceRemoval;
        session.sourceRemoval = null;
        if (removal == null) {
            return false;
        }
        try {
            removal.run();
            session.sourceRemovalDispatched = true;
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private static void firstDraw(final Session session) {
        if (current != session || session.finished || session.firstDrawStarted) {
            return;
        }
        session.firstDrawStarted = true;
        Runnable fallback = session.frameReadyFallback;
        if (fallback != null) {
            MAIN.removeCallbacks(fallback);
            session.frameReadyFallback = null;
        }
        int state = displayState(session.display);
        session.firstFrameReady = true;
        if (state != 2) {
            finish(session, "DISPLAY_STATE_" + state, true);
            return;
        }
        session.handoff.commit();
        session.animationAt = SystemClock.uptimeMillis();
        removeSource(session);
        ValueAnimator animator = ValueAnimator.ofFloat(0.0f, 1.0f);
        session.animator = animator;
        animator.setDuration(session.durationMs);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge$$ExternalSyntheticLambda2
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                FaceIslandExitBridge.lambda$firstDraw$7(session, valueAnimator);
            }
        });
        animator.addListener(new AnimatorListenerAdapter() { // from class: miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge.1
            @Override
            public void onAnimationEnd(Animator animation) {
                if (FaceIslandExitBridge.current == session) {
                    FaceIslandExitBridge.finish(session, "COMPLETE", false);
                }
            }
        });
        animator.start();
    }

    private static void lambda$firstDraw$7(Session session, ValueAnimator animation) {
        if (current != session) {
            return;
        }
        int state = displayState(session.display);
        if (state != 2) {
            finish(session, "DISPLAY_STATE_" + state, false);
        } else {
            session.view.setProgress(((Float) animation.getAnimatedValue()).floatValue());
        }
    }

    /**
     * 结束一次退场动画。
     *
     * @return 视图是否已从窗口摘除；返回 false 时调用方不能回收正在绘制的 Bitmap。
     */
    private static boolean finish(Session session, String reason, boolean rollback) {
        if (session.finished) {
            return session.viewDetached;
        }
        XposedLog.d("FaceIslandExit", "finish reason=" + reason + " rollback=" + rollback
            + " token=" + session.token);
        session.finished = true;
        if (current == session) {
            current = null;
        }
        if (session.deadline != null) {
            MAIN.removeCallbacks(session.deadline);
        }
        if (session.frameReadyFallback != null) {
            MAIN.removeCallbacks(session.frameReadyFallback);
            session.frameReadyFallback = null;
        }
        session.view.firstDraw = null;
        if (session.commitRegistered) {
            try {
                FaceIslandExitCapture.call(session.view.getViewTreeObserver(),
                    "unregisterFrameCommitCallback", new Class[]{Runnable.class}, session.frameCommit);
            } catch (Exception e) {
            }
        }
        if (session.animator != null) {
            session.animator.removeAllUpdateListeners();
            session.animator.removeAllListeners();
            if (session.animator.isStarted()) {
                session.animator.cancel();
            }
            session.animator = null;
        }
        session.handoff.release(rollback);
        removeSource(session);
        try {
            if (session.view.getParent() != null) {
                session.windows.removeViewImmediate(session.view);
            }
            session.viewDetached = session.view.getParent() == null;
        } catch (Throwable th) {
            // removeViewImmediate 失败时视图仍在窗口里绘制，Bitmap 必须留着。
            XposedLog.w("FaceIslandExit", "remove exit view failed: " + th);
        }
        session.view.release();
        SystemClock.uptimeMillis();
        return session.viewDetached;
    }

    private static int displayState(Object display) {
        Object value = FaceIslandExitCapture.optional(display, "getState");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return -1;
    }

    private static void configureScreenCoordinates(WindowManager.LayoutParams params) throws Exception {
        WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(params, 3);
        FaceIslandExitCapture.call(params, "setFitInsetsTypes", new Class[]{Integer.TYPE}, 0);
    }

    static final class Session {
        ValueAnimator animator;
        boolean commitRegistered;
        final long cycle;
        Runnable deadline;
        final Object display;
        final long durationMs;
        boolean fallbackUsed;
        boolean finished;
        boolean firstDrawStarted;
        boolean firstFrameReady;
        Runnable frameCommit;
        Runnable frameReadyFallback;
        final FaceIslandExitHandoff handoff;
        boolean hardwareDraw;
        Runnable sourceRemoval;
        boolean sourceRemovalDispatched;
        final long token;
        final ExitView view;
        boolean viewDetached;
        final WindowManager windows;
        final long createdAt = SystemClock.uptimeMillis();
        long animationAt = -1;

        Session(long token, long cycle, Object display, WindowManager windows, ExitView view,
                FaceIslandExitHandoff handoff, long durationMs) {
            this.token = token;
            this.cycle = cycle;
            this.display = display;
            this.windows = windows;
            this.view = view;
            this.handoff = handoff;
            this.durationMs = durationMs;
        }
    }

    static final class ExitView extends View {
        private Runnable firstDraw;
        private Runnable hardwareDraw;
        private final FaceIslandExitPainter painter;
        private float progress;
        private final float sourceAlpha;

        ExitView(Context context, FaceIslandExitCapture capture) {
            super(context);
            this.painter = new FaceIslandExitPainter(capture);
            this.sourceAlpha = capture.alpha;
            setFocusable(false);
            setClickable(false);
            setLongClickable(false);
            setBackgroundColor(0);
        }

        void setProgress(float value) {
            this.progress = Math.max(0.0f, Math.min(1.0f, value));
            invalidate();
        }

        void release() {
            invalidate();
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int alpha = Math.round(this.sourceAlpha * 255.0f
                * (1.0f - Math.max(0.0f, (this.progress - 0.72f) / 0.28f)));
            this.painter.drawWindow(canvas, this.progress, alpha);
            if (canvas.isHardwareAccelerated()) {
                Runnable callback = this.hardwareDraw;
                if (callback != null) {
                    this.hardwareDraw = null;
                    callback.run();
                    return;
                }
                return;
            }
            Runnable callback2 = this.firstDraw;
            if (callback2 != null) {
                this.firstDraw = null;
                FaceIslandExitBridge.MAIN.post(callback2);
            }
        }
    }
}
