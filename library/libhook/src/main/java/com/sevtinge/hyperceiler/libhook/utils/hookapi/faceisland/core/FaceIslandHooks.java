package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.FaceIslandContract;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandPrefs;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public final class FaceIslandHooks {
    private static final Set<Class<?>> HOOKED = Collections.synchronizedSet(new HashSet<>());
    private static final AtomicBoolean ENABLED_WATCH_STARTED = new AtomicBoolean();
    private static volatile boolean pluginReady;
    public static final String BINDING_PREFIX = "FaceIsland.binding.";
    private static final SharedPreferences.OnSharedPreferenceChangeListener ENABLED_LISTENER =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
                if (key != null && !key.startsWith("prefs_key_system_ui_face_island_")) {
                    return;
                }
                // 关开关后旧 controller 还在跑心跳，必须当场停用；重新打开时只要恢复心跳即可。
                FaceIslandController.setEnabled(isEnabled());
                FaceIslandController.preferencesChanged();
            }
        };

    private FaceIslandHooks() {
    }

    /** 运行时总开关：设备受支持且设置页开关仍开启。 */
    public static boolean isEnabled() {
        return FaceIslandCompat.isSupportedDevice() && FaceIslandPrefs.moduleEnabled();
    }

    /** 热重载清理：允许新 generation 重新安装回调 hook。 */
    public static void reset() {
        HOOKED.clear();
        pluginReady = false;
        FaceIslandNativeFaceVisibility.reset();
    }

    public static boolean isPluginReady() {
        return pluginReady && isEnabled();
    }

    public static void setPluginReady(boolean ready) {
        pluginReady = ready;
        if (!ready) FaceIslandNativeFaceVisibility.setSuppressed(false);
    }

    /**
     * 监听开关变化，关掉后立即停用已发布的岛与退场浮层。
     */
    public static void watchEnabledPreference() {
        if (!ENABLED_WATCH_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            SharedPreferences prefs = PrefsBridge.getSharedPreferences();
            if (prefs == null) {
                ENABLED_WATCH_STARTED.set(false);
                return;
            }
            prefs.registerOnSharedPreferenceChangeListener(ENABLED_LISTENER);
            BaseHook.registerHotReloadCleanup(() -> {
                try {
                    prefs.unregisterOnSharedPreferenceChangeListener(ENABLED_LISTENER);
                } catch (Throwable th) {
                    XposedLog.w("FaceIsland", "enabled watcher unregister failed: " + th);
                }
                ENABLED_WATCH_STARTED.set(false);
            });
        } catch (Throwable th) {
            ENABLED_WATCH_STARTED.set(false);
            XposedLog.w("FaceIsland", "enabled watcher unavailable: " + th);
        }
    }

    private static void send(String event, Object owner, int user, int code, boolean flag, String detail) {
        // BIND_* 永远放行，开关重新打开后不需要重启 SystemUI 也能恢复。
        if (!event.startsWith("BIND_") && !isEnabled()) {
            return;
        }
        try {
            FaceIslandController.get().dispatch(event, owner, user, code, flag, detail, SystemClock.elapsedRealtime());
        } catch (Throwable th) {
        }
    }

    public static void bindRepository(Object owner) {
        BaseHook.putHotReloadRuntimeState(BINDING_PREFIX + "repository", owner);
        send("BIND_REPOSITORY", owner, -1, 0, false, "");
    }

    public static void bindMonitor(Object owner) {
        BaseHook.putHotReloadRuntimeState(BINDING_PREFIX + "monitor", owner);
        send("BIND_MONITOR", owner, -1, 0, false, "");
    }

    public static void bindMediator(Object owner) {
        BaseHook.putHotReloadRuntimeState(BINDING_PREFIX + "mediator", owner);
        send("BIND_MEDIATOR", owner, -1, 0, false, "");
    }

    public static void bindIsland(Object owner) {
        BaseHook.putHotReloadRuntimeState(BINDING_PREFIX + "island", owner);
        send("BIND_ISLAND", owner, -1, 0, false, "");
    }

    public static Object wrapFaceCallback(Object callback, int user) {
        try {
            FaceIslandAuthRequest request = FaceIslandAuthRequest.wrap(callback, user);
            installCallbackHooks(callback.getClass());
            send("AUTH_START", request, user, 0, false, "");
            return callback;
        } catch (Throwable th) {
            return callback;
        }
    }

    /**
     * 直接 hook 原回调类的 5 个方法，替代补丁里对 FaceManager.AuthenticationCallback 的继承。
     */
    private static void installCallbackHooks(Class<?> type) {
        synchronized (HOOKED) {
            if (!HOOKED.add(type)) {
                return;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            String name = method.getName();
            int count = method.getParameterCount();
            if ("onAuthenticationAcquired".equals(name) && count == 1) {
                BaseHook.hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        FaceIslandAuthRequest.enter(param.getThisObject());
                        FaceIslandHooks.onDetectionChanged(FaceIslandAuthRequest.currentCallback());
                    }

                    @Override
                    public void after(HookParam param) {
                        FaceIslandAuthRequest.leave();
                    }
                });
            } else if ("onAuthenticationFailed".equals(name) && count == 0) {
                BaseHook.hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        FaceIslandAuthRequest.enter(param.getThisObject());
                        FaceIslandHooks.onDetectionChanged(FaceIslandAuthRequest.currentCallback());
                    }

                    @Override
                    public void after(HookParam param) {
                        FaceIslandAuthRequest.leave();
                    }
                });
            } else if ("onAuthenticationHelp".equals(name) && count == 2) {
                BaseHook.hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        FaceIslandAuthRequest.enter(param.getThisObject());
                        FaceIslandHooks.onDetectionChanged(FaceIslandAuthRequest.currentCallback());
                    }

                    @Override
                    public void after(HookParam param) {
                        FaceIslandAuthRequest.leave();
                    }
                });
            } else if ("onAuthenticationError".equals(name) && count == 2) {
                BaseHook.hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        FaceIslandAuthRequest.enter(param.getThisObject());
                    }

                    @Override
                    public void after(HookParam param) {
                        FaceIslandHooks.onAuthStopped(FaceIslandAuthRequest.currentCallback());
                        FaceIslandAuthRequest.leave();
                    }
                });
            } else if ("onAuthenticationSucceeded".equals(name) && count == 1) {
                BaseHook.hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        FaceIslandAuthRequest.enter(param.getThisObject());
                        FaceIslandHooks.onFaceProduced(param.getArgs()[0]);
                    }

                    @Override
                    public void after(HookParam param) {
                        FaceIslandAuthRequest.leave();
                    }
                });
            }
        }
        XposedLog.d("FaceIsland", "callback hooks installed: " + type.getName());
    }

    public static void onAuthStopped(Object owner) {
        FaceIslandAuthRequest request = FaceIslandAuthRequest.currentCallback();
        send("AUTH_STOP", (request == null || request.repository != owner) ? owner : request, -1, 0, false, "");
    }

    public static void onDetectionChanged(Object owner) {
        send("DETECTION", owner, -1, 0, false, "");
    }

    public static void onFaceProduced(Object result) {
        try {
            FaceIslandAuthRequest request = FaceIslandAuthRequest.currentCallback();
            FaceIslandAuthRequest.remember(result, request);
            int user = FaceIslandReflect.number(FaceIslandReflect.call(result, "getUserId", new Object[0]), -1);
            send("FACE_PRODUCED", request, user, 0, false, "");
        } catch (Throwable th) {
        }
    }

    public static Object enterFaceAcceptance(Object status) {
        try {
            return FaceIslandAuthRequest.enterAcceptance(status);
        } catch (Throwable th) {
            return null;
        }
    }

    public static void leaveFaceAcceptance(Object previous) {
        FaceIslandAuthRequest.leaveAcceptance(previous);
    }

    public static void onFaceAccepted(Object monitor, int user, boolean strong) {
        send("FACE_ACCEPTED", FaceIslandAuthRequest.accepting(), user, 0, strong, "");
    }

    public static Object enterFaceAcceptanceFromCoroutine(Object coroutine) {
        try {
            return enterFaceAcceptance(FaceIslandReflect.optionalField(coroutine, "L$0"));
        } catch (Throwable th) {
            return null;
        }
    }

    public static void onFingerprintProduced(Object monitor, int user, boolean strong) {
        try {
            FaceIslandController controller = FaceIslandController.get();
            try {
                FaceIslandFingerprintFlow.Token token = FaceIslandFingerprintFlow.produced(monitor, user, strong,
                    controller.screenBoundary(), controller.showRequestIdentity(), SystemClock.elapsedRealtime());
                try {
                    send("FINGERPRINT_PRODUCED", token, user, 0, strong, "");
                } catch (Throwable th) {
                }
            } catch (Throwable th2) {
            }
        } catch (Throwable th3) {
        }
    }

    public static Object enterFingerprintPolicy(Object injector, int user, Object source, boolean strong) {
        try {
            FaceIslandController controller = FaceIslandController.get();
            try {
                return FaceIslandFingerprintFlow.enter(
                    FaceIslandReflect.field(injector, "keyguardUpdateMonitor"), user, strong,
                    "FINGERPRINT".equals(String.valueOf(source)), controller.screenBoundary(),
                    controller.showRequestIdentity(), SystemClock.elapsedRealtime());
            } catch (Throwable th) {
                return null;
            }
        } catch (Throwable th2) {
            return null;
        }
    }

    public static boolean isBiometricAuthenticated(Object injector) {
        return FaceIslandReflect.boolField(injector, "biometricAuthenticated");
    }

    public static boolean isFingerprintUnlockContext(Object injector) {
        Object mediator = FaceIslandReflect.optionalField(injector, "keyguardViewMediator");
        return FaceIslandReflect.boolField(mediator, "mShowing")
            || !FaceIslandReflect.boolField(mediator, "mDeviceInteractive")
            || FaceIslandReflect.boolField(mediator, "mPendingLock");
    }

    public static void onFingerprintBiometricAuthenticated(Object injector, int user, Object source, boolean strong,
                                                           boolean wasAuthenticated, boolean unlockContext) {
        if (!"FINGERPRINT".equals(String.valueOf(source)) || wasAuthenticated || !unlockContext) {
            return;
        }
        try {
            if (FaceIslandReflect.boolField(injector, "biometricAuthenticated")) {
                FaceIslandController controller = FaceIslandController.get();
                Object monitor = FaceIslandReflect.field(injector, "keyguardUpdateMonitor");
                FaceIslandFingerprintFlow.Token token = FaceIslandFingerprintFlow.acceptAuthenticated(monitor, user,
                    strong, controller.screenBoundary(), controller.showRequestIdentity(), SystemClock.elapsedRealtime());
                if (token != null) {
                    send("FINGERPRINT_DIRECT_ACCEPTED", token, user, 0, strong, "");
                }
            }
        } catch (Throwable th) {
        }
    }

    /**
     * 由 smali 还原：检查 BiometricUnlockController 的待处理认证是否仍然匹配当前指纹 token，
     * 决定 leave 时是否保留 pending 状态。
     */
    public static void leaveFingerprintPolicy(Object previous, Object injector) {
        boolean pending = false;
        FaceIslandFingerprintFlow.Token token = FaceIslandFingerprintFlow.current();
        if (token != null) {
            try {
                Object controller = FaceIslandReflect.field(injector, "biometricUnlockController");
                Object pendingAuth = FaceIslandReflect.optionalField(
                    FaceIslandReflect.call(controller, "get", new Object[0]), "mPendingAuthenticated");
                if (pendingAuth != null) {
                    int userId = FaceIslandReflect.number(
                        FaceIslandReflect.optionalField(pendingAuth, "userId"), -1);
                    if (userId == token.user
                        && "FINGERPRINT".equals(String.valueOf(
                        FaceIslandReflect.optionalField(pendingAuth, "biometricSourceType")))
                        && FaceIslandReflect.boolField(pendingAuth, "isStrongBiometric") == token.strong) {
                        pending = true;
                    }
                }
            } catch (Throwable th) {
                pending = false;
            }
        }
        if (token != null) {
            send("FINGERPRINT_POLICY_END", token, token.user, 0, token.strong, "");
        }
        FaceIslandFingerprintFlow.leave(previous, pending);
    }

    public static void onFingerprintUnlockBranch(Object biometricController) {
        FaceIslandFingerprintFlow.Token accepted;
        try {
            FaceIslandFingerprintFlow.Token token = FaceIslandFingerprintFlow.current();
            if (token == null) {
                return;
            }
            FaceIslandController controller = FaceIslandController.get();
            Object monitor = FaceIslandReflect.field(biometricController, "mUpdateMonitor");
            Object tracker = FaceIslandReflect.field(monitor, "mStrongAuthTracker");
            int strongAuth = FaceIslandReflect.number(
                FaceIslandReflect.call(tracker, "getStrongAuthForUser", Integer.valueOf(token.user)), -1);
            boolean z = monitor == token.monitor && strongAuth >= 0 && (strongAuth & 4096) == 0
                && !FaceIslandReflect.bool(FaceIslandReflect.call(monitor, "isSimPinSecure", new Object[0]))
                && FaceIslandReflect.bool(FaceIslandReflect.call(monitor, "isUnlockingWithBiometricAllowed",
                Boolean.valueOf(token.strong)));
            boolean allowed = z;
            int mode = FaceIslandReflect.number(FaceIslandReflect.field(biometricController, "mMode"), -1);
            if (allowed) {
                accepted = FaceIslandFingerprintFlow.accept(monitor, mode, controller.screenBoundary(),
                    controller.showRequestIdentity(), SystemClock.elapsedRealtime());
            } else {
                accepted = null;
            }
            send("FINGERPRINT_ACCEPTED", accepted, token.user, mode, token.strong, "");
        } catch (Throwable th) {
        }
    }

    public static void onCredentialChecked(Object controller, int user, int kind) {
        send("CREDENTIAL_CHECKED", controller, user, kind, false, "");
    }

    public static void onCredentialRejected(Object controller, int user) {
        send("CREDENTIAL_REJECTED", controller, user, 0, false, "");
    }

    public static Object beginCredentialFinish(Object container, int user) {
        try {
            return FaceIslandController.get().prepareCredential(container, user);
        } catch (Throwable th) {
            return null;
        }
    }

    public static Object beginCredentialFinishFromCallback(Object callback, int user) {
        return beginCredentialFinish(outerObject(callback), user);
    }

    public static Object enterCredentialRequest(Object token) {
        return FaceIslandCredentialFlow.enterRequest(token);
    }

    public static void leaveCredentialRequest(Object previous) {
        FaceIslandCredentialFlow.leaveRequest(previous);
    }

    public static void onDoneRequest(Object mediator, int user) {
        try {
            FaceIslandCredentialFlow.requested(outerObject(mediator), user);
        } catch (Throwable th) {
        }
    }

    public static void onDoneRequestFromCallback(Object callback, int user) {
        onDoneRequest(outerObject(callback), user);
    }

    public static void onCredentialPending(Object mediator, int user, Object token, Object callback) {
        try {
            FaceIslandCredentialFlow.pending(outerObject(mediator), user, token, callback);
        } catch (Throwable th) {
        }
    }

    public static void onExitAnimationCreated(Object callback, Object mediator, int user) {
        try {
            FaceIslandCredentialFlow.bindCallback(callback, mediator, user);
        } catch (Throwable th) {
        }
    }

    public static Object enterExitCallback(Object callback) {
        return FaceIslandCredentialFlow.enterCallback(callback);
    }

    public static void leaveExitCallback(Object previous) {
        FaceIslandCredentialFlow.leaveCallback(previous);
    }

    public static void onMediatorDone(Object mediator) {
        try {
            send("MEDIATOR_DONE", FaceIslandCredentialFlow.consume(mediator), -1, 0, false, "");
        } catch (Throwable th) {
        }
    }

    public static void onHide(Object mediator) {
        send("HIDE", mediator, -1, 0, false, "");
    }

    public static void onHideAnimationFinished(Object callback) {
        send("HIDE_ANIMATION_FINISHED", callback, -1, 0, false, "");
    }

    public static void onHideCancelled(Object mediator) {
        send("HIDE_CANCELLED", mediator, -1, 0, false, "");
    }

    public static void onSleepStarted(Object mediator) {
        try {
            FaceIslandController.get().recordSleepStarted(SystemClock.elapsedRealtime());
            try {
                send("SLEEP_BEGIN", mediator, -1, 0, false, "");
            } catch (Throwable th) {
            }
        } catch (Throwable th2) {
        }
    }

    public static void onSleepFinished(Object mediator) {
        send("SLEEP_FINISHED", mediator, -1, 0, false, "");
    }

    public static void onWakeStarted(Object mediator) {
        send("WAKE_BEGIN", mediator, -1, 0, false, "");
    }

    public static void onSleepStartedFromService(Object callback) {
        Object mediator = mediatorFromServiceCallback(callback);
        if (mediator != null) {
            onSleepStarted(mediator);
        }
    }

    public static void onSleepFinishedFromService(Object callback) {
        Object mediator = mediatorFromServiceCallback(callback);
        if (mediator != null) {
            onSleepFinished(mediator);
        }
    }

    public static void onWakeStartedFromService(Object callback) {
        Object mediator = mediatorFromServiceCallback(callback);
        if (mediator != null) {
            onWakeStarted(mediator);
        }
    }

    public static void onKeyguardStateFromService(Object callback) {
        Object mediator = mediatorFromServiceCallback(callback);
        if (mediator != null) {
            onKeyguardState(mediator);
        }
    }

    /**
     * 由 smali 还原：锁屏/息屏状态变化时刷新控制器的系统快照。
     */
    public static void onKeyguardState(Object mediator) {
        try {
            boolean asleep = !(FaceIslandReflect.boolField(mediator, "mDeviceInteractive")
                && !FaceIslandReflect.boolField(mediator, "mGoingToSleep")
                && !FaceIslandReflect.boolField(mediator, "mDozing")
                && !FaceIslandReflect.boolField(mediator, "mAodShowing"));
            FaceIslandController.get().recordSystemSnapshot(asleep, SystemClock.elapsedRealtime());
            send(asleep ? "SYSTEM_SLEEP" : "SYSTEM_STATE", mediator, -1, 0, false, "");
        } catch (Throwable th) {
        }
    }

    public static void hideNativeFaceIcon(Object container, Object faceView) {
        FaceIslandNativeFaceVisibility.track(container);
        FaceIslandNativeFaceVisibility.track(faceView);
    }

    private static Object mediatorFromServiceCallback(Object callback) {
        Object service = outerObject(callback);
        return FaceIslandReflect.optionalField(service, "mKeyguardViewMediator");
    }

    private static Object outerObject(Object owner) {
        Object outer = FaceIslandReflect.optionalField(owner, "this$0");
        return outer == null ? owner : outer;
    }
}
