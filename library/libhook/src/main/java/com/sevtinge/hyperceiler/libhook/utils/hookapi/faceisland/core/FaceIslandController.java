package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandPrefs;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin.FaceIslandExitBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public final class FaceIslandController {
    static final String DEBUG_KEY = "face_island_debug_preview";
    private static final FaceIslandController INSTANCE = new FaceIslandController();
    static final String KEY = "face_island_unlock";
    /** 预览时间线：扫描入场 1.6 秒后进入成功态，成功过渡 800ms 后退场，5 秒为看门狗。 */
    private static final long PREVIEW_SCAN_MS = 1600L;
    private static final long PREVIEW_SUCCESS_MS = 800L;
    private static final long PREVIEW_TIMEOUT_MS = 5000L;
    private long appliedInvalidationSequence;
    private boolean asleep;
    private boolean cancellation;
    private boolean closedUntilRelock;
    private Context context;
    private long cycleStartedMs;
    private Object data;
    private volatile boolean debugPreview;
    private long debugPreviewSuppressedUntilMs;
    private long previewToken;
    private boolean displaySuppressed;
    /** 运行时 kill-switch，随设置开关变化。 */
    private volatile boolean enabled = true;
    private boolean entryInvalidated;
    private boolean exitBridgeActive;
    private long exitToken;
    private boolean interactive;
    private Object islandOwner;
    private Object mediator;
    private Object monitor;
    private long nextExitToken;
    private boolean observedAuth;
    private boolean observedDetect;
    private FaceIslandFingerprintFlow.Token pendingFingerprint;
    private boolean pendingFingerprintDirect;
    private Object publishedContent;
    private FaceIslandHostView publishedHost;
    private String publishedKey;
    private Object publishedReal;
    private boolean removing;
    private Object repository;
    private long retryAtMs;
    private boolean retryPending;
    private boolean showing;
    private boolean sleepRequested;
    private boolean snapshotWasInvalid;
    private boolean started;
    final FaceIslandState state = new FaceIslandState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong invalidationSequence = new AtomicLong();
    private volatile long invalidatedAtMs = -1;
    private final ArrayList<WeakReference<View>> renderers = new ArrayList<>();
    private final FaceIslandWakeBuffer<FaceIslandAuthRequest> wakeEvents = new FaceIslandWakeBuffer<>();
    private final FaceIslandEntryState entryState = new FaceIslandEntryState();
    private int appliedWidthPercent = -1;
    private int appliedIconEdgeOffsetDp = -1;
    private String appliedGlyphMode;
    private long scheduledCloseAt = -1;
    private final Runnable closeDeadline = () -> {
        scheduledCloseAt = -1;
        try { refresh(SystemClock.elapsedRealtime()); }
        catch (Throwable error) { XposedLog.w("FaceIslandCtl", "dismiss refresh failed", error); }
    };
    private long lastPublishMs = -10000;
    private long lastShowRequest = -1;
    private long latestAuthRequest = -1;
    private boolean publishMissingLogged;
    private final Runnable heartbeat = new Runnable() { // from class: com.android.systemui.faceisland.FaceIslandController.1
        @Override
        public void run() {
            try {
                FaceIslandController.this.refresh(SystemClock.elapsedRealtime());
            } catch (Throwable th) {
            }
            if (!FaceIslandController.this.started) {
                return;
            }
            FaceIslandController.this.main.postDelayed(this, FaceIslandController.this.state.active ? 80L : 500L);
        }
    };

    private FaceIslandController() {
    }

    static FaceIslandController get() {
        return INSTANCE;
    }

    long screenBoundary() {
        return this.invalidationSequence.get();
    }

    long showRequestIdentity() {
        Object value = FaceIslandReflect.optionalField(this.mediator, "mLastShowRequest");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return -1L;
    }

    void recordInvalidation(long at) {
        this.invalidatedAtMs = at;
        this.invalidationSequence.incrementAndGet();
    }

    synchronized void recordSystemSnapshot(boolean invalid, long at) {
        if (invalid) {
            if (!this.snapshotWasInvalid) {
                recordInvalidation(at);
            }
        }
        this.snapshotWasInvalid = invalid;
    }

    synchronized void recordSleepStarted(long at) {
        this.snapshotWasInvalid = true;
        recordInvalidation(at);
    }

    void dispatch(final String event, final Object owner, final int user, final int code, final boolean flag, final String detail, final long occurredAt) {
        final long boundary = this.invalidationSequence.get();
        final long lockRequest = showRequestIdentity();
        Runnable task = new Runnable() { // from class: com.android.systemui.faceisland.FaceIslandController.2
            @Override
            public void run() {
                try {
                    FaceIslandController.this.handle(event, owner, user, code, flag, detail, occurredAt, boundary, lockRequest);
                } catch (Throwable th) {
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            this.main.post(task);
        }
    }

    private void handle(String str, Object obj, int i, int i2, boolean z, String str2, long j, long j2, long j3) throws Exception {
        if (str.startsWith("BIND_")) {
            if ("BIND_REPOSITORY".equals(str)) {
                if (this.repository != obj) {
                    this.wakeEvents.clear();
                }
                this.repository = obj;
            }
            if ("BIND_MONITOR".equals(str)) {
                this.monitor = obj;
            }
            if ("BIND_MEDIATOR".equals(str)) {
                if (this.mediator != obj) {
                    this.wakeEvents.clear();
                }
                this.mediator = obj;
            }
            if ("BIND_ISLAND".equals(str)) {
                this.islandOwner = obj;
            }
            Object optionalField = FaceIslandReflect.optionalField(obj, "context");
            if (!(optionalField instanceof Context)) {
                optionalField = FaceIslandReflect.optionalField(obj, "mContext");
            }
            if (optionalField instanceof Context) {
                this.context = (Context) optionalField;
            }
            this.enabled = FaceIslandCompat.isSupportedDevice() && FaceIslandPrefs.moduleEnabled();
            if (this.enabled) resumeHeartbeat();
            return;
        }
        if (!this.enabled || !FaceIslandHooks.isEnabled()) return;
        if (j < this.invalidatedAtMs || j2 < this.invalidationSequence.get()) {
            return;
        }
        if (this.debugPreview && isRealAuthenticationEvent(str)) {
            stopDebugPreview("REAL_AUTH_EVENT");
        }
        if ("FINGERPRINT_PRODUCED".equals(str) || "FINGERPRINT_POLICY_END".equals(str)) {
            return;
        }
        if ("SLEEP_FINISHED".equals(str)) {
            if (obj == this.mediator) {
                if (FaceIslandReflect.boolField(this.mediator, "mDeviceInteractive") && !FaceIslandReflect.boolField(this.mediator, "mGoingToSleep")) {
                    return;
                }
                this.sleepRequested = true;
                applyInvalidation();
                return;
            }
            return;
        }
        if ("WAKE_BEGIN".equals(str)) {
            if (obj != this.mediator) {
                return;
            }
            this.sleepRequested = false;
            cancelSleepExit();
            syncSystem(SystemClock.elapsedRealtime());
            updatePresentation(SystemClock.elapsedRealtime());
            return;
        }
        if ("SYSTEM_SLEEP".equals(str) || "SLEEP_BEGIN".equals(str)) {
            if (obj != this.mediator) {
                return;
            }
            if ("SLEEP_BEGIN".equals(str)) {
                this.sleepRequested = true;
            }
            applyInvalidation();
            return;
        }
        long elapsedRealtime = SystemClock.elapsedRealtime();
        if ("FINGERPRINT_ACCEPTED".equals(str) || "FINGERPRINT_DIRECT_ACCEPTED".equals(str)) {
            FaceIslandFingerprintFlow.Token token = obj instanceof FaceIslandFingerprintFlow.Token ? (FaceIslandFingerprintFlow.Token) obj : null;
            if (token != null && token.acceptedAt >= 0) {
                FaceIslandFingerprintFlow.Token token2 = token;
                if (!FaceIslandFingerprintFlow.valid(token2, this.monitor, currentUser(), j2, j3, elapsedRealtime)) {
                    return;
                }
                syncSystem(elapsedRealtime);
                if (this.closedUntilRelock) {
                    return;
                }
                this.pendingFingerprintDirect = "FINGERPRINT_DIRECT_ACCEPTED".equals(str);
                this.pendingFingerprint = token2;
                syncSystem(elapsedRealtime);
                replayFingerprint(elapsedRealtime);
                updatePresentation(elapsedRealtime);
                return;
            }
            return;
        }
        syncSystem(elapsedRealtime);
        if ("SYSTEM_STATE".equals(str)) {
            updatePresentation(elapsedRealtime);
            return;
        }
        FaceIslandAuthRequest faceIslandAuthRequest = obj instanceof FaceIslandAuthRequest ? (FaceIslandAuthRequest) obj : null;
        if (faceIslandAuthRequest != null && (faceIslandAuthRequest.screenBoundary != j2 || faceIslandAuthRequest.showRequest != this.lastShowRequest || faceIslandAuthRequest.repository != this.repository || faceIslandAuthRequest.user != currentUser())) {
            updatePresentation(elapsedRealtime);
            return;
        }
        if ("AUTH_START".equals(str) && faceIslandAuthRequest != null) {
            if (faceIslandAuthRequest.id >= this.latestAuthRequest) {
                this.latestAuthRequest = faceIslandAuthRequest.id;
            } else {
                updatePresentation(elapsedRealtime);
                return;
            }
        }
        if (("HIDE".equals(str) || "HIDE_CANCELLED".equals(str)) && (obj != this.mediator || j3 != this.lastShowRequest)) {
            updatePresentation(elapsedRealtime);
            return;
        }
        if ("HIDE_ANIMATION_FINISHED".equals(str) && j3 != this.lastShowRequest) {
            updatePresentation(elapsedRealtime);
            return;
        }
        boolean z2 = faceIslandAuthRequest != null && faceIslandAuthRequest.id == this.state.authRequest && faceIslandAuthRequest.screenBoundary == j2 && j >= faceIslandAuthRequest.startedAt;
        if (!this.state.active || (j < this.cycleStartedMs && !z2)) {
            if (deferWakeEvent(str, obj, i, i2, z, str2, j, j2, j3)) {
                if (this.state.active) {
                    replayWakeEvents(SystemClock.elapsedRealtime(), this.lastShowRequest);
                }
                updatePresentation(SystemClock.elapsedRealtime());
                return;
            }
            return;
        }
        handleActiveEvent(str, obj, i, i2, z, str2, j);
        updatePresentation(elapsedRealtime);
    }

    /**
     * 由旧版完整反编译还原，并合入 0.4 的 HIDE / HIDE_ANIMATION_FINISHED 状态机。
     */
    private void handleActiveEvent(String str, Object obj, int i, int i2, boolean z, String str2, long j) throws Exception {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        if ("AUTH_START".equals(str)) {
            FaceIslandAuthRequest faceIslandAuthRequest = obj instanceof FaceIslandAuthRequest ? (FaceIslandAuthRequest) obj : null;
            if (faceIslandAuthRequest != null && faceIslandAuthRequest.user == this.state.user && faceIslandAuthRequest.repository == this.repository) {
                this.observedAuth = true;
                this.retryPending = false;
                FaceIslandCredentialFlow.clear();
                this.state.authStart(j, faceIslandAuthRequest.id, elapsedRealtime);
            }
            return;
        }
        if ("AUTH_STOP".equals(str)) {
            long id = obj instanceof FaceIslandAuthRequest ? ((FaceIslandAuthRequest) obj).id : -1L;
            if (id >= 0) {
                if (id == this.state.authRequest) {
                    this.observedAuth = false;
                }
                this.state.authStop(j, id, elapsedRealtime);
            }
            return;
        }
        if ("DETECTION".equals(str)) {
            this.observedDetect = readFlowBool(this.repository, "_isDetectRunning");
            this.state.detection(this.observedDetect);
            return;
        }
        if ("FACE_PRODUCED".equals(str)) {
            this.state.produced(i, obj instanceof FaceIslandAuthRequest ? ((FaceIslandAuthRequest) obj).id : -1L, j);
            return;
        }
        if ("FACE_ACCEPTED".equals(str)) {
            long request = obj instanceof FaceIslandAuthRequest ? ((FaceIslandAuthRequest) obj).id : -1L;
            String reason = this.state.matchesProduced(i, request, j) ? faceBlockReason(i) : "NO_MATCHING_PRODUCED";
            if (reason == null && this.state.success(i, "FACE", true, j, elapsedRealtime)) {
                this.state.expectFirstProcessFrame(j);
            }
            return;
        }
        if ("CREDENTIAL_CHECKED".equals(str)) {
            this.state.credentialChecked(i, credentialKind(obj, i2), j);
            return;
        }
        if ("CREDENTIAL_REJECTED".equals(str)) {
            if (i == this.state.user) {
                this.state.clearCredential();
            }
            return;
        }
        if ("MEDIATOR_DONE".equals(str)) {
            FaceIslandCredentialFlow.Token token = obj instanceof FaceIslandCredentialFlow.Token ? (FaceIslandCredentialFlow.Token) obj : null;
            if (token != null && token.cycle == this.state.cycle && token.round == this.state.round
                && token.user == currentUser() && !FaceIslandReflect.boolField(this.mediator, "mGoingToSleep")) {
                this.state.success(token.user, token.source, false, j, elapsedRealtime);
            }
            return;
        }
        if ("HIDE".equals(str)) {
            FaceIslandCredentialFlow.cancel(this.mediator);
            this.state.requestHide();
            return;
        }
        if ("HIDE_ANIMATION_FINISHED".equals(str)) {
            this.state.hideAnimationFinished(j);
            return;
        }
        if ("HIDE_CANCELLED".equals(str)) {
            this.state.cancelHideRequest();
            if (FaceIslandReflect.boolField(this.mediator, "mPendingLock")) {
                FaceIslandCredentialFlow.cancel(this.mediator);
                this.state.cancelExit(this.state.faceSuccess && faceBlockReason(this.state.user) == null
                    && readFlowBool(this.repository, "_isCurrentUserAuthenticated"), j);
            }
        }
    }

    private boolean deferWakeEvent(String event, Object owner, int user, int code, boolean flag, String detail, long at, long boundary, long lockRequest) throws Exception {
        FaceIslandWakeBuffer.Event item = new FaceIslandWakeBuffer.Event(event, owner, user, code, flag, detail, at);
        FaceIslandAuthRequest request = owner instanceof FaceIslandAuthRequest ? (FaceIslandAuthRequest) owner : null;
        if ("AUTH_START".equals(event) && request != null && ((!this.state.active || this.state.round == 0) && !this.closedUntilRelock && request.user == currentUser() && request.repository == this.repository && request.screenBoundary == boundary && request.showRequest >= 0 && request.showRequest == this.lastShowRequest && SystemClock.elapsedRealtime() - request.startedAt <= 2000)) {
            this.wakeEvents.start(request, this.repository, request.user, boundary, request.showRequest, request.startedAt, item);
            return true;
        }
        if (!this.state.active && request != null && ("FACE_PRODUCED".equals(event) || "FACE_ACCEPTED".equals(event) || "AUTH_STOP".equals(event))) {
            boolean saved = this.wakeEvents.append(request, item);
            return saved;
        }
        if (this.state.active) {
            return false;
        }
        if (!"HIDE".equals(event) && !"HIDE_ANIMATION_FINISHED".equals(event)) {
            return false;
        }
        boolean saved2 = this.wakeEvents.appendExit(item, boundary, lockRequest);
        return saved2;
    }

    private void replayWakeEvents(long now, long showRequest) throws Exception {
        for (FaceIslandWakeBuffer.Event item : this.wakeEvents.take(this.repository, this.state.user, this.invalidationSequence.get(), showRequest, now)) {
            handleActiveEvent(item.name, item.owner, item.user, item.code, item.flag, item.detail, item.at);
        }
    }

    private void replayFingerprint(long now) throws Exception {
        FaceIslandFingerprintFlow.Token token = this.pendingFingerprint;
        if (token == null) {
            return;
        }
        if (!FaceIslandFingerprintFlow.valid(token, this.monitor, currentUser(), this.invalidationSequence.get(), this.lastShowRequest, now)) {
            this.pendingFingerprint = null;
            this.pendingFingerprintDirect = false;
            return;
        }
        if (!this.state.active || this.asleep) {
            return;
        }
        boolean direct = this.pendingFingerprintDirect;
        this.pendingFingerprint = null;
        this.pendingFingerprintDirect = false;
        if (FaceIslandFingerprintFlow.consume(token, this.monitor, this.state.user, this.invalidationSequence.get(), this.lastShowRequest, now)) {
            boolean accepted = this.state.success(token.user, "FINGERPRINT", false, now, now);
            if (accepted) {
                this.state.completeSuccessVisual();
                if (FaceIslandPrefs.hideAfterFingerprintUnlock()) {
                    this.closedUntilRelock = true;
                    this.state.end("FINGERPRINT_HIDDEN_BY_SETTING");
                    removePublished();
                    invalidateRenderers();
                    return;
                }
                if (direct) {
                    this.state.goingAway(now);
                }
            }
        }
    }

    private void refresh(long now) throws Exception {
        if (!this.enabled) {
            return;
        }
        syncSystem(now);
        if (this.repository != null && this.state.active && !this.debugPreview) {
            boolean rawAuth = readFlowBool(this.repository, "_isAuthRunning");
            boolean rawDetect = readFlowBool(this.repository, "_isDetectRunning");
            this.cancellation = readFlowBool(this.repository, "cancellationInProgress");
            if (rawAuth != this.observedAuth) {
                this.observedAuth = rawAuth;
                if (!rawAuth) {
                    this.state.authStop(now, -1L);
                }
            }
            if (rawDetect != this.observedDetect) {
                this.observedDetect = rawDetect;
                this.state.detection(rawDetect);
            }
            if (this.retryPending && now - this.retryAtMs > 1500L) {
                this.retryPending = false;
            }
        }
        updatePresentation(now);
    }

    /**
     * 由旧版完整反编译还原，并合入 0.4 的 debug preview / 几何刷新逻辑。
     */
    private void syncSystem(long now) throws Exception {
        String str;
        if (this.mediator == null || this.monitor == null) {
            return;
        }
        applyInvalidation();
        refreshActiveGeometry();
        if (this.debugPreview) {
            return;
        }
        int currentUser = currentUser();
        this.showing = FaceIslandReflect.boolField(this.mediator, "mShowing");
        this.interactive = FaceIslandReflect.boolField(this.mediator, "mDeviceInteractive");
        boolean z = true;
        this.asleep = this.sleepRequested || FaceIslandReflect.boolField(this.mediator, "mGoingToSleep")
            || FaceIslandReflect.boolField(this.mediator, "mDozing")
            || FaceIslandReflect.boolField(this.mediator, "mAodShowing") || !this.interactive;
        this.displaySuppressed = FaceIslandReflect.boolField(this.mediator, "mOccluded");
        Object optionalField = FaceIslandReflect.optionalField(this.mediator, "mLastShowRequest");
        long longValue = optionalField instanceof Number ? ((Number) optionalField).longValue() : -1L;
        boolean lockRequestChanged = this.lastShowRequest >= 0 && longValue >= 0 && this.lastShowRequest != longValue;
        this.lastShowRequest = longValue;
        if (this.asleep || lockRequestChanged || !this.showing) {
            this.closedUntilRelock = false;
        }
        if (this.state.active && (currentUser != this.state.user || this.asleep || lockRequestChanged)) {
            if (currentUser != this.state.user) {
                str = "USER_SWITCH";
            } else {
                str = this.asleep ? "SCREEN_OFF_OR_AOD" : "NEW_LOCK_REQUEST";
            }
            cleanup(str);
        }
        if (!this.wakeEvents.hasAccepted(this.repository, currentUser, this.invalidationSequence.get(), longValue, now)
            && (this.pendingFingerprint == null || !FaceIslandFingerprintFlow.valid(this.pendingFingerprint,
            this.monitor, currentUser, this.invalidationSequence.get(), longValue, now))) {
            z = false;
        }
        if (!this.asleep && !this.closedUntilRelock && currentUser >= 0 && !this.state.active && !this.displaySuppressed
            && ((this.showing && !FaceIslandReflect.boolField(this.mediator, "mHiding")) || z)) {
            cancelSleepExit();
            this.state.begin(currentUser, now, FaceIslandPrefs.desktopExitHoldMs());
            this.cycleStartedMs = now;
            this.observedDetect = false;
            this.observedAuth = false;
            this.retryPending = false;
            replayWakeEvents(now, longValue);
        }
        replayFingerprint(now);
        if (this.state.active && !this.asleep
            && (!this.showing || FaceIslandReflect.boolField(this.mediator, "mHiding"))) {
            this.state.dismissed(now, FaceIslandPrefs.desktopExitHoldMs());
        }
    }

    private void applyInvalidation() {
        long sequence = this.invalidationSequence.get();
        if (sequence == this.appliedInvalidationSequence) {
            return;
        }
        this.appliedInvalidationSequence = sequence;
        this.wakeEvents.clear();
        if (this.pendingFingerprint != null && this.pendingFingerprint.boundary != sequence) {
            this.pendingFingerprint = null;
            this.pendingFingerprintDirect = false;
        }
        if (this.state.active && this.invalidatedAtMs >= this.cycleStartedMs) {
            if (this.debugPreview) {
                stopDebugPreview("SCREEN_OFF_OR_AOD_SIGNAL");
            } else {
                cleanup("SCREEN_OFF_OR_AOD_SIGNAL");
            }
        }
        this.closedUntilRelock = false;
    }

    private void startDebugPreview(int user, long now) {
        cancelSleepExit();
        this.state.begin(user, now, FaceIslandPrefs.desktopExitHoldMs());
        this.cycleStartedMs = now;
        this.debugPreview = true;
        this.appliedWidthPercent = FaceIslandPrefs.widthPercent();
        this.appliedIconEdgeOffsetDp = FaceIslandPrefs.iconEdgeOffsetDp();
        this.retryPending = false;
        this.observedDetect = false;
        this.observedAuth = false;
    }

    private void stopDebugPreview(String reason) {
        if (this.debugPreview) {
            this.debugPreview = false;
            // 作废所有还没执行的预览步骤。
            this.previewToken++;
            if ("REAL_AUTH_EVENT".equals(reason)) {
                this.debugPreviewSuppressedUntilMs = SystemClock.elapsedRealtime() + 1000;
            }
            this.appliedIconEdgeOffsetDp = -1;
            this.appliedWidthPercent = -1;
            // 预览没有真实锁屏状态，结束时要清掉临时的抑制标记。
            this.closedUntilRelock = false;
            this.state.end(reason);
            removePublished();
            invalidateRenderers();
        }
    }

    private void refreshActiveGeometry() {
        int width = FaceIslandPrefs.widthPercent();
        int offset = FaceIslandPrefs.iconEdgeOffsetDp();
        String glyphMode = FaceIslandPrefs.successGlyphMode();
        if (width == this.appliedWidthPercent && offset == this.appliedIconEdgeOffsetDp
            && glyphMode.equals(this.appliedGlyphMode)) {
            return;
        }
        this.appliedWidthPercent = width;
        this.appliedIconEdgeOffsetDp = offset;
        this.appliedGlyphMode = glyphMode;
        if (this.data != null) {
            removePublished();
            this.lastPublishMs = -10000L;
        }
    }

    private static boolean isRealAuthenticationEvent(String event) {
        return "AUTH_START".equals(event) || "AUTH_STOP".equals(event) || "FACE_PRODUCED".equals(event)
            || "FACE_ACCEPTED".equals(event) || "FINGERPRINT_ACCEPTED".equals(event)
            || "FINGERPRINT_DIRECT_ACCEPTED".equals(event) || "CREDENTIAL_CHECKED".equals(event)
            || "CREDENTIAL_REJECTED".equals(event) || "MEDIATOR_DONE".equals(event)
            || "HIDE".equals(event) || "HIDE_ANIMATION_FINISHED".equals(event)
            || "HIDE_CANCELLED".equals(event);
    }

    private int currentUser() throws Exception {
        Object selected = FaceIslandReflect.optionalField(this.mediator, "mSelectedUserInteractor");
        if (selected == null) {
            selected = FaceIslandReflect.optionalField(this.monitor, "mSelectedUserInteractor");
        }
        return FaceIslandReflect.number(FaceIslandReflect.call(selected, "getSelectedUserId", new Object[0]), -1);
    }

    private String faceBlockReason(int user) throws Exception {
        if (this.monitor == null) {
            return "MONITOR_UNBOUND";
        }
        if (currentUser() != user || this.state.user != user) {
            return "USER_MISMATCH";
        }
        if (this.asleep) {
            return "ASLEEP";
        }
        if (FaceIslandReflect.bool(FaceIslandReflect.call(this.monitor, "isSimPinSecure", new Object[0]))) {
            return "SIM_PIN_SECURE";
        }
        Object tracker = FaceIslandReflect.field(this.monitor, "mStrongAuthTracker");
        int bits = FaceIslandReflect.number(FaceIslandReflect.call(tracker, "getStrongAuthForUser", Integer.valueOf(user)), -1);
        if (bits < 0) {
            return "STRONG_AUTH_UNKNOWN";
        }
        if ((bits & 4096) != 0) {
            return "SECURE_LOCK_CONFIRMATION_REQUIRED";
        }
        ClassLoader loader = this.monitor.getClass().getClassLoader();
        Class<?> biometricType = Class.forName("android.hardware.biometrics.BiometricSourceType", false, loader);
        Object face = biometricType.getField("FACE").get(null);
        if (FaceIslandReflect.bool(FaceIslandReflect.call(this.monitor, "isUnlockingWithBiometricAllowed", face))) {
            return null;
        }
        return "FACE_BIOMETRIC_DISALLOWED";
    }

    private static boolean readFlowBool(Object owner, String name) throws Exception {
        return owner != null && FaceIslandReflect.bool(FaceIslandReflect.flow(FaceIslandReflect.field(owner, name)));
    }

    private static String credentialKind(Object owner, int explicit) {
        if (explicit == 1) {
            return "PIN";
        }
        if (explicit == 2) {
            return "PASSWORD";
        }
        if (explicit == 3) {
            return "PATTERN";
        }
        Object mode = FaceIslandReflect.optionalField(owner, "mSecurityMode");
        if (mode == null) {
            mode = FaceIslandReflect.optionalField(owner, "mCurrentSecurityMode");
        }
        if (mode == null) {
            Object outer = FaceIslandReflect.optionalField(owner, "this$0");
            mode = FaceIslandReflect.optionalField(outer, "mCurrentSecurityMode");
        }
        String text = String.valueOf(mode);
        if ("PIN".equalsIgnoreCase(text)) {
            return "PIN";
        }
        if ("Password".equalsIgnoreCase(text)) {
            return "PASSWORD";
        }
        if ("Pattern".equalsIgnoreCase(text)) {
            return "PATTERN";
        }
        return null;
    }

    private void updatePresentation(long now) throws Exception {
        if (this.state.tick(now)) {
            this.closedUntilRelock = true;
            removePublished();
        }
        if (this.state.active && (!this.displaySuppressed || this.debugPreview)) {
            ensurePublished(now);
        } else if (this.data != null) {
            removePublished();
        }
        invalidateRenderers();
        long deadline = this.state.active ? this.state.closeAtMs : -1;
        if (deadline != this.scheduledCloseAt) {
            this.main.removeCallbacks(this.closeDeadline);
            this.scheduledCloseAt = deadline;
            if (deadline >= 0) this.main.postDelayed(this.closeDeadline, Math.max(0, deadline - now));
        }
    }

    private Object content() throws Exception {
        if (this.islandOwner == null) {
            return null;
        }
        Object plugin = FaceIslandReflect.field(this.islandOwner, "dynamicIslandPluginController");
        for (Method method : plugin.getClass().getDeclaredMethods()) {
            if (method.getName().startsWith("getContent$") && method.getParameterTypes().length == 0) {
                return FaceIslandReflect.call(plugin, method.getName(), new Object[0]);
            }
        }
        throw new NoSuchMethodException("DynamicIslandPluginController.getContent$");
    }

    private void ensurePublished(long now) throws Exception {
        if (!FaceIslandHooks.isPluginReady()) {
            FaceIslandNativeFaceVisibility.setSuppressed(false);
            if (this.data != null) removePublished();
            return;
        }
        Object real;
        String str;
        boolean registered;
        boolean pending;
        boolean blocked;
        Object content = content();
        boolean missing = content == null || this.context == null;
        // 80ms 心跳会反复走到这里，只在状态切换时记录，避免刷屏。
        if (missing != this.publishMissingLogged) {
            this.publishMissingLogged = missing;
            XposedLog.d("FaceIslandCtl", missing
                ? "publish skipped: content=" + (content != null) + " context=" + (this.context != null)
                + " active=" + this.state.active + " debug=" + this.debugPreview
                : "publish ready");
        }
        if (content != null && this.context != null) {
            String activeKey = this.debugPreview ? DEBUG_KEY : KEY;
            try {
                real = FaceIslandReflect.call(content, "real", new Object[0]);
            } catch (NoSuchMethodException e) {
                real = content;
            }
            if (real == null) {
                str = KEY;
                registered = false;
                pending = false;
                blocked = false;
            } else {
                Object rawMap = FaceIslandReflect.field(real, "islandData");
                if (!(rawMap instanceof Map)) {
                    throw new IllegalStateException("islandData 不是登记表");
                }
                Map<?, ?> map = (Map) rawMap;
                boolean registeredNow = this.data != null && map.get(activeKey) == this.data;
                Object waiting = FaceIslandReflect.field(real, "pendingData");
                if (!(waiting instanceof Map)) {
                    throw new IllegalStateException("pendingData 不是等待表");
                }
                boolean pendingNow = this.data != null && ((Map) waiting).containsKey(this.data);
                Object windowState = FaceIslandReflect.call(real, "getWindowState", new Object[0]);
                str = KEY;
                blocked = FaceIslandReflect.bool(FaceIslandReflect.flow(
                    FaceIslandReflect.call(windowState, "getMiPlayShow", new Object[0])));
                registered = registeredNow;
                pending = pendingNow;
            }
            boolean samePlugin = content == this.publishedContent && real == this.publishedReal;
            Object real3 = real;
            FaceIslandEntryState.Decision decision = this.entryState.decide(
                this.data != null, samePlugin, this.entryInvalidated, registered, pending, now);
            if (decision.publish && !blocked) {
                XposedLog.d("FaceIslandCtl", "publishing decision=" + decision + " samePlugin=" + samePlugin
                    + " registered=" + registered + " pending=" + pending);
            }
            if (decision.publish && !blocked && now - this.lastPublishMs >= 500) {
                this.lastPublishMs = now;
                if (this.data != null) {
                    removePublished();
                }
                Class<?> type = Class.forName("com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData",
                    true, this.islandOwner.getClass().getClassLoader());
                Object next = type.getDeclaredConstructor(new Class[0]).newInstance(new Object[0]);
                FaceIslandReflect.call(next, "setKey", activeKey);
                FaceIslandReflect.call(next, "setProperties", 0);
                FaceIslandReflect.call(next, "setPriority", 100);
                Bundle extras = new Bundle();
                extras.putBoolean("face_island_managed", true);
                extras.putBoolean(DEBUG_KEY, this.debugPreview);
                extras.putLong("face_island_cycle", this.state.cycle);
                extras.putString("packageName", FaceIslandCompat.SYSTEM_UI_PACKAGE);
                extras.putString("miui.pkg.name", FaceIslandCompat.SYSTEM_UI_PACKAGE);
                extras.putInt("miui.user.id", this.state.user);
                FaceIslandReflect.call(next, "setExtras", extras);
                String business = this.debugPreview ? DEBUG_KEY : str;
                FaceIslandReflect.call(next, "setTickerData", "{\"business\":\"" + business
                    + "\",\"bigIslandArea\":{},\"smallIslandArea\":{},\"islandProperty\":0,\"islandPriority\":100,"
                    + "\"islandTimeout\":0,\"dismissIsland\":false,\"islandOrder\":true,\"expandedTime\":0,"
                    + "\"maxSize\":false,\"clickDirectIntent\":false,\"appContentDescription\":\"解锁状态\"}");
                FaceIslandHostView nextHost = new FaceIslandHostView(this.context, this, this.state.cycle, this.debugPreview);
                FaceIslandReflect.call(next, "setView", nextHost);
                this.data = next;
                this.publishedHost = nextHost;
                this.publishedKey = activeKey;
                this.entryInvalidated = false;
                this.publishedContent = content;
                this.publishedReal = real3;
                this.entryState.published(now);
                FaceIslandReflect.call(content, "addDynamicIslandView", next, false);
                XposedLog.i("FaceIslandCtl", "published key=" + activeKey + " debug=" + this.debugPreview);
                try {
                    this.publishedReal = FaceIslandReflect.call(content, "real", new Object[0]);
                } catch (NoSuchMethodException e2) {
                    this.publishedReal = content;
                }
            }
            return;
        }
        if (this.publishedContent != null) {
            if (this.publishedHost != null) {
                this.publishedHost.retire();
            }
            this.publishedReal = null;
            this.publishedContent = null;
            this.data = null;
            this.publishedHost = null;
            this.publishedKey = null;
            this.entryState.clear();
        }
    }

    private void removePublished() {
        FaceIslandNativeFaceVisibility.setSuppressed(false);
        final Object old = this.publishedContent;
        final String oldKey = this.publishedKey == null ? KEY : this.publishedKey;
        boolean oldDebugPreview = DEBUG_KEY.equals(oldKey);
        if (this.publishedHost != null) {
            this.publishedHost.retire();
        }
        this.publishedHost = null;
        this.publishedReal = null;
        this.publishedContent = null;
        this.data = null;
        this.publishedKey = null;
        this.entryState.clear();
        this.entryInvalidated = false;
        if (old == null) {
            return;
        }
        Runnable removal = new Runnable() { // from class: com.android.systemui.faceisland.FaceIslandController.3
            @Override
            public void run() {
                FaceIslandController.this.removing = true;
                try {
                    FaceIslandReflect.call(old, "removeDynamicIslandView", oldKey);
                } catch (Exception e) {
                } finally {
                    FaceIslandController.this.removing = false;
                }
            }
        };
        if (!oldDebugPreview && this.exitBridgeActive) {
            try {
                if (FaceIslandExitBridge.removeSourceWhenReady(this.exitToken, removal)) {
                    return;
                }
            } catch (Throwable e) {
            }
        }
        removal.run();
    }

    private void cleanup(String reason) {
        if (reason.startsWith("SCREEN_OFF_OR_AOD")) {
            beginSleepExit();
        } else {
            cancelSleepExit();
        }
        this.state.end(reason);
        this.wakeEvents.clear();
        this.pendingFingerprint = null;
        this.pendingFingerprintDirect = false;
        FaceIslandCredentialFlow.clear();
        this.retryPending = false;
        this.observedDetect = false;
        this.observedAuth = false;
        removePublished();
        invalidateRenderers();
    }

    private void beginSleepExit() {
        if (!this.state.active || this.publishedReal == null || this.data == null || this.context == null) {
            return;
        }
        cancelSleepExit();
        final long token = this.nextExitToken + 1;
        this.nextExitToken = token;
        try {
            boolean created = FaceIslandExitBridge.begin(this.context, this.publishedReal, this.data, token, 250L);
            if (!created) {
                return;
            }
            this.exitBridgeActive = true;
            this.exitToken = token;
            this.main.postDelayed(new Runnable() { // from class: com.android.systemui.faceisland.FaceIslandController.4
                @Override
                public void run() {
                    if (FaceIslandController.this.exitToken == token) {
                        FaceIslandController.this.cancelSleepExit();
                    }
                }
            }, 450L);
        } catch (Throwable th) {
        }
    }

    private void cancelSleepExit() {
        boolean active = this.exitBridgeActive;
        long token = this.exitToken;
        this.exitBridgeActive = false;
        this.exitToken = 0L;
        if (!active) {
            return;
        }
        try {
            FaceIslandExitBridge.cancel(token);
        } catch (Throwable th) {
        }
    }

    public FaceIslandSnapshot snapshot() {
        return new FaceIslandSnapshot(this.state, SystemClock.elapsedRealtime(), this.debugPreview);
    }

    /** 编辑器/模板桥判断当前是否在播放设置页预览。 */
    public static boolean isPreviewActive() {
        return INSTANCE.debugPreview;
    }

    /**
     * 热重载清理：停掉心跳与预览时间线，撤销已发布的岛和退场浮层，并释放宿主对象引用。
     */
    public static void shutdown() {
        final FaceIslandController controller = INSTANCE;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                controller.onShutdown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            FutureTask<Void> completion = new FutureTask<>(task, null);
            controller.main.post(completion);
            try { completion.get(2, TimeUnit.SECONDS); }
            catch (Exception error) {
                completion.cancel(false);
                throw new IllegalStateException("Face island main-thread cleanup did not complete", error);
            }
        }
    }

    public static void preferencesChanged() {
        FaceIslandController controller = INSTANCE;
        Runnable update = () -> {
            if (!controller.enabled) return;
            controller.state.updateExitHold(FaceIslandPrefs.desktopExitHoldMs());
            try { controller.refresh(SystemClock.elapsedRealtime()); }
            catch (Throwable error) { XposedLog.w("FaceIslandCtl", "preference refresh failed", error); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run();
        else controller.main.post(update);
    }

    /** 设置开关关闭后的 kill-switch：停掉已发布的岛与退场浮层。 */
    public static void setEnabled(final boolean value) {
        final FaceIslandController controller = INSTANCE;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                controller.enabled = value;
                if (value) {
                    controller.resumeHeartbeat();
                } else {
                    controller.stopRunningState("MODULE_DISABLED");
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            controller.main.post(task);
        }
    }

    private void resumeHeartbeat() {
        if (this.started || this.mediator == null || this.monitor == null) {
            return;
        }
        this.started = true;
        this.main.removeCallbacks(this.heartbeat);
        this.main.post(this.heartbeat);
    }

    private void onShutdown() {
        stopRunningState("HOT_RELOAD");
        this.renderers.clear();
        this.repository = null;
        this.monitor = null;
        this.mediator = null;
        this.islandOwner = null;
        this.context = null;
        this.publishMissingLogged = false;
    }

    private void stopRunningState(String reason) {
        this.main.removeCallbacksAndMessages(null);
        this.started = false;
        this.scheduledCloseAt = -1;
        FaceIslandNativeFaceVisibility.setSuppressed(false);
        this.previewToken++;
        this.debugPreview = false;
        cancelSleepExit();
        try {
            FaceIslandExitBridge.reset();
        } catch (Throwable th) {
        }
        cleanup(reason);
    }

    public boolean isCurrent(long cycle) {
        return this.state.active && this.state.cycle == cycle;
    }

    public long newViewGeneration(long cycle) {
        if (isCurrent(cycle)) {
            return this.state.newView();
        }
        return -1L;
    }

    public void registerRenderer(View renderer) {
        this.renderers.add(new WeakReference<>(renderer));
    }

    private void invalidateRenderers() {
        for (int i = this.renderers.size() - 1; i >= 0; i--) {
            View view = this.renderers.get(i).get();
            if (view == null) {
                this.renderers.remove(i);
            } else {
                view.invalidate();
            }
        }
    }

    public void onRendererVisible(long cycle, long generation) {
        if (isCurrent(cycle) && generation == this.state.viewGen) {
            if (!this.debugPreview && FaceIslandHooks.isPluginReady()) {
                FaceIslandNativeFaceVisibility.setSuppressed(true);
            }
            long now = SystemClock.elapsedRealtime();
            this.state.visible(cycle, generation, now);
        }
    }

    public void onRendererFrame(long cycle, long generation, int sampledPhase) {
        if (isCurrent(cycle) && generation == this.state.viewGen && sampledPhase == 4) {
            this.state.successGlyphVisible(cycle, generation, SystemClock.elapsedRealtime());
        }
    }

    void pluginRemoved(long cycle) {
        if (this.removing || !isCurrent(cycle)) {
            return;
        }
        this.entryInvalidated = true;
        this.lastPublishMs = SystemClock.elapsedRealtime();
    }

    public void retry() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            this.main.post(this::retry);
            return;
        }
        if (!this.enabled || this.debugPreview || !FaceIslandHooks.isPluginReady()) return;
        long now = SystemClock.elapsedRealtime();
        try {
            syncSystem(now);
            this.cancellation = readFlowBool(this.repository, "cancellationInProgress");
            if (!this.state.active || this.state.successLatched || this.state.goingAwayMs >= 0
                || !this.showing || this.displaySuppressed || this.asleep || this.cancellation
                || this.retryPending || FaceIslandReflect.boolField(this.mediator, "mHiding")
                || readFlowBool(this.repository, "_isAuthRunning")
                || readFlowBool(this.repository, "_isDetectRunning")) return;
            Object interactor = FaceIslandReflect.field(this.monitor, "mFaceAuthInteractor");
            Class<?> eventType = Class.forName("com.android.systemui.deviceentry.shared.FaceAuthUiEvent",
                true, this.monitor.getClass().getClassLoader());
            Object event = eventType.getField("FACE_AUTH_USER_CLICK_FACE_ICON").get(null);
            this.retryPending = true;
            this.retryAtMs = now;
            FaceIslandReflect.call(interactor, "runFaceAuth", event, false);
        } catch (Throwable error) {
            this.retryPending = false;
            XposedLog.w("FaceIslandCtl", "face retry failed", error);
        }
    }

    Object prepareCredential(Object container, int user) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper() || this.debugPreview) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        syncSystem(now);
        String mode = credentialKind(container, 0);
        String source = this.state.credentialFinish(user, mode, now);
        if (source == null) {
            return null;
        }
        return new FaceIslandCredentialFlow.Token(this.state.cycle, this.state.round, user, source);
    }

    /**
     * 设置页“预览动画”入口。只在主线程执行，播放 5 秒后自动结束。
     *
     * <p>预览和真机解锁走同一条时间线：扫描入场 → 成功态 → 退场，
     * 只跳过真实认证事件的接收。</p>
     */
    public static boolean beginPreview() {
        final FaceIslandController controller = INSTANCE;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            controller.main.post(new Runnable() {
                @Override
                public void run() {
                    beginPreview();
                }
            });
            return true;
        }
        try {
            if (!FaceIslandHooks.isPluginReady()) return false;
            if (controller.debugPreview) return false;
            if (SystemClock.elapsedRealtime() < controller.debugPreviewSuppressedUntilMs) return false;
            // Do not overwrite a real lock-screen authentication with a settings preview.
            if (FaceIslandReflect.boolField(controller.mediator, "mShowing")
                && controller.state.active) return false;
            if (controller.mediator == null || controller.monitor == null || controller.islandOwner == null) {
                XposedLog.w("FaceIslandCtl", "preview bind missing: mediator=" + (controller.mediator != null)
                    + " monitor=" + (controller.monitor != null)
                    + " island=" + (controller.islandOwner != null));
                return false;
            }
            if (controller.content() == null) {
                // 没有可用的岛内容时直接结束，不能留下 5 秒空白。
                XposedLog.e("FaceIslandCtl", "preview aborted: content unavailable");
                return false;
            }
            if (!FaceIslandCompat.isSupportedDevice() || !FaceIslandPrefs.moduleEnabled()) {
                XposedLog.i("FaceIslandCtl", "preview ignored: module disabled");
                return false;
            }
            XposedLog.i("FaceIslandCtl", "preview start: context=" + (controller.context != null)
                + " active=" + controller.state.active + " debug=" + controller.debugPreview);
            int user = controller.currentUser();
            if (user < 0) {
                user = 0;
            }
            controller.stopDebugPreview("PREVIEW_RESTART");
            long now = SystemClock.elapsedRealtime();
            controller.startDebugPreview(user, now);
            // 与 AUTH_START 一致：先起扫描动画，预览才不是静态轮廓。
            controller.state.authStart(now, now);
            final long token = controller.previewToken + 1;
            controller.previewToken = token;
            controller.main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    controller.previewSuccess(token);
                }
            }, PREVIEW_SCAN_MS);
            controller.main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (controller.previewToken == token) {
                        controller.stopDebugPreview("PREVIEW_TIMEOUT");
                    }
                }
            }, PREVIEW_TIMEOUT_MS);
            controller.refreshActiveGeometry();
            controller.updatePresentation(now);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private void previewSuccess(long token) {
        if (this.previewToken != token || !this.debugPreview || !this.state.active) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        try {
            if (!this.state.success(this.state.user, "PREVIEW", true, now, now)) {
                return;
            }
            updatePresentation(now);
        } catch (Throwable th) {
            XposedLog.w("FaceIslandCtl", "preview success failed: " + th);
            return;
        }
        this.main.postDelayed(new Runnable() {
            @Override
            public void run() {
                previewExit(token);
            }
        }, PREVIEW_SUCCESS_MS);
    }

    private void previewExit(long token) {
        if (this.previewToken != token || !this.debugPreview || !this.state.active) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        try {
            this.state.completeSuccessVisual();
            this.state.goingAway(now);
            updatePresentation(now);
        } catch (Throwable th) {
            XposedLog.w("FaceIslandCtl", "preview exit failed: " + th);
        }
    }
}
