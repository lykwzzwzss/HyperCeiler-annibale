/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.lockscreen;

import android.view.View;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 锁屏人脸解锁岛（K90 / HyperOS 4）。
 *
 * <p>把 dex 补丁里对 SystemUI 的注入点逐项改成 Xposed Hook。真正的动画、状态机和
 * 绘制都在随模块加载的补丁 dex 中，本类只负责把系统事件转交给它。</p>
 */
public class FaceIslandUnlock extends BaseHook {
    private static final String TAG = "FaceIslandUnlock";

    private static final int BOUNCER_FACE_VIEW_ID = 2131429205;

    private static final ThreadLocal<ArrayList<Object>> CREDENTIAL_FINISH =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<CredentialRequest>> CREDENTIAL_REQUEST =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<Object>> FINGERPRINT_POLICY =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<Object>> FACE_ACCEPT =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<Object>> EXIT_CALLBACK =
        ThreadLocal.withInitial(ArrayList::new);

    @Override
    public void init() {
        android.util.Log.i("FaceIsland", "init device=" + android.os.Build.DEVICE);
        if (!FaceIslandBridge.isTargetDevice()) {
            XposedLog.w(TAG, "unsupported device: " + android.os.Build.DEVICE);
            return;
        }
        hookKeyguardUpdateMonitor();
        hookKeyguardViewMediator();
        hookKeyguardService();
        hookBiometricUnlock();
        hookDeviceEntryFaceAuth();
        hookCredential();
        hookFaceView();
        hookIsland();
        android.util.Log.i("FaceIsland", "hooks installed");
    }

    // ------------------------------------------------------------------
    // KeyguardUpdateMonitor
    // ------------------------------------------------------------------

    private void hookKeyguardUpdateMonitor() {
        Class<?> clazz = findClassIfExists("com.android.keyguard.KeyguardUpdateMonitor");
        if (clazz == null) {
            return;
        }
        hookLargestConstructor(clazz, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                FaceIslandBridge.bindMonitor(param.getThisObject());
            }
        });
        hook(clazz, "onFaceAuthenticated", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                android.util.Log.i("FaceIsland", "onFaceAuthenticated user="
                    + param.getArgs()[0] + " strong=" + param.getArgs()[1]);
                FaceIslandBridge.onFaceAccepted(param.getThisObject(),
                    (Integer) param.getArgs()[0], (Boolean) param.getArgs()[1]);
            }
        });
        hook(clazz, "onFingerprintAuthenticated", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                android.util.Log.i("FaceIsland", "onFingerprintAuthenticated user="
                    + param.getArgs()[0] + " strong=" + param.getArgs()[1]);
                FaceIslandBridge.onFingerprintProduced(param.getThisObject(),
                    (Integer) param.getArgs()[0], (Boolean) param.getArgs()[1]);
            }
        });
    }

    // ------------------------------------------------------------------
    // KeyguardViewMediator 及内部类
    // ------------------------------------------------------------------

    private void hookKeyguardViewMediator() {
        Class<?> mediator = findClassIfExists("com.android.systemui.keyguard.KeyguardViewMediator");
        if (mediator != null) {
            hookLargestConstructor(mediator, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.bindMediator(param.getThisObject());
                }
            });
            hook(mediator, "handleHide", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandBridge.onHide(param.getThisObject());
                }
            });
            hook(mediator, "handleCancelKeyguardExitAnimation", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandBridge.onHideCancelled(param.getThisObject());
                }
            });
            hook(mediator, "handleKeyguardDone", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    try {
                        if (!getBooleanField(param.getThisObject(), "mGoingToSleep")) {
                            FaceIslandBridge.onMediatorDone(param.getThisObject());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            hook(mediator, "setShowingLocked", 3, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.onKeyguardState(param.getThisObject());
                }
            });
        }

        Class<?> callback = findClassIfExists("com.android.systemui.keyguard.KeyguardViewMediator$5");
        if (callback != null) {
            hook(callback, "keyguardDone", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    if (owner != null) {
                        FaceIslandBridge.onDoneRequest(owner, (Integer) param.getArgs()[0]);
                    }
                    Object token = peek(CREDENTIAL_FINISH);
                    CredentialRequest state = new CredentialRequest();
                    if (token != null) {
                        state.active = true;
                        state.entered = FaceIslandBridge.enterCredentialRequest(token);
                    }
                    push(CREDENTIAL_REQUEST, state);
                }

                @Override
                public void after(HookParam param) {
                    CredentialRequest state = pop(CREDENTIAL_REQUEST);
                    if (state != null && state.active) {
                        FaceIslandBridge.leaveCredentialRequest(state.entered);
                    }
                }
            });
        }

        Class<?> finished = findClassIfExists(
            "com.android.systemui.keyguard.KeyguardViewMediator$OnHideAnimationFinished");
        if (finished != null) {
            hook(finished, "run", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object token = FaceIslandBridge.enterExitCallback(param.getThisObject());
                    push(EXIT_CALLBACK, token);
                }

                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.leaveExitCallback(pop(EXIT_CALLBACK));
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // KeyguardService
    // ------------------------------------------------------------------

    private void hookKeyguardService() {
        Class<?> service = findClassIfExists("com.android.systemui.keyguard.KeyguardService$2");
        if (service == null) {
            return;
        }
        hook(service, "onStartedGoingToSleep", 1, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object mediator = mediatorOf(param.getThisObject());
                if (mediator != null) {
                    FaceIslandBridge.onSleepStarted(mediator);
                    FaceIslandBridge.onKeyguardState(mediator);
                }
            }
        });
        hook(service, "onFinishedGoingToSleep", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object mediator = mediatorOf(param.getThisObject());
                if (mediator != null) {
                    FaceIslandBridge.onSleepFinished(mediator);
                }
            }
        });
        hook(service, "onStartedWakingUp", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object mediator = mediatorOf(param.getThisObject());
                if (mediator != null) {
                    FaceIslandBridge.onWakeStarted(mediator);
                    FaceIslandBridge.onKeyguardState(mediator);
                }
            }
        });
    }

    private static Object mediatorOf(Object service) {
        Object owner = getObjectField(service, "this$0");
        return owner != null ? getObjectField(owner, "mKeyguardViewMediator") : null;
    }

    // ------------------------------------------------------------------
    // 指纹解锁
    // ------------------------------------------------------------------

    private void hookBiometricUnlock() {
        Class<?> injector = findClassIfExists(
            "com.android.keyguard.injector.BiometricUnlockControllerInjector");
        if (injector != null) {
            hook(injector, "onBiometricAuthenticated$1", 3, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object token = FaceIslandBridge.enterFingerprintPolicy(
                        param.getThisObject(), (Integer) param.getArgs()[0],
                        param.getArgs()[1], (Boolean) param.getArgs()[2]);
                    push(FINGERPRINT_POLICY, token);
                }

                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.leaveFingerprintPolicy(
                        pop(FINGERPRINT_POLICY), param.getThisObject());
                }
            });
        }

        Class<?> controller = findClassIfExists(
            "com.android.systemui.statusbar.phone.BiometricUnlockController");
        if (controller != null) {
            hook(controller, "startWakeAndUnlock", 2, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.onFingerprintUnlockBranch(param.getThisObject());
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 人脸认证仓库
    // ------------------------------------------------------------------

    private void hookDeviceEntryFaceAuth() {
        Class<?> repo = findClassIfExists(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl");
        if (repo != null) {
            hookLargestConstructor(repo, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.bindRepository(param.getThisObject());
                }
            });
            hook(repo, "cancel", 0, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.onDetectionChanged(param.getThisObject());
                    FaceIslandBridge.onAuthStopped(param.getThisObject());
                }
            });
            hook(repo, "access$onFaceAuthRequestCompleted", 1, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.onAuthStopped(param.getArgs()[0]);
                }
            });
        }

        Class<?> detect = findClassIfExists(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$detect$2");
        if (detect != null) {
            hook(detect, "invokeSuspend", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    if (owner != null) {
                        FaceIslandBridge.onDetectionChanged(owner);
                    }
                }
            });
        }

        Class<?> detection = findClassIfExists(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$detectionCallback$1");
        if (detection != null) {
            hook(detection, "onFaceDetected", 3, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    if (owner != null) {
                        FaceIslandBridge.onDetectionChanged(owner);
                    }
                }
            });
        }

        Class<?> faceCallback = findClassIfExists(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$faceAuthCallback$1");
        if (faceCallback != null) {
            hook(faceCallback, "onAuthenticationSucceeded", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    android.util.Log.i("FaceIsland", "face produced " + param.getArgs()[0]);
                    FaceIslandBridge.onFaceProduced(param.getArgs()[0]);
                }
            });
        }

        Class<?> acceptance = findClassIfExists(
            "com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor$observeFaceAuthStateUpdates$1");
        if (acceptance != null) {
            hook(acceptance, "invokeSuspend", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object status = getObjectField(param.getThisObject(), "L$0");
                    push(FACE_ACCEPT, FaceIslandBridge.enterFaceAcceptance(status));
                }

                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.leaveFaceAcceptance(pop(FACE_ACCEPT));
                }
            });
        }

        Class<?> faceManager = findClassIfExists("android.hardware.face.MiuiFaceManager");
        if (faceManager != null) {
            hook(faceManager, "authenticate", 6, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object callback = param.getArgs()[3];
                    if (callback == null
                        || !callback.getClass().getName().endsWith("$faceAuthCallback$1")
                        || callback.getClass().getName().contains("faceisland")) {
                        return;
                    }
                    int user = (Integer) param.getArgs()[5];
                    android.util.Log.i("FaceIsland", "wrap face callback "
                        + callback.getClass().getName() + " user=" + user);
                    Object wrapped = FaceIslandBridge.wrapFaceCallback(callback, user);
                    if (wrapped != null) {
                        param.getArgs()[3] = wrapped;
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 凭据（PIN / 密码 / 图案）
    // ------------------------------------------------------------------

    private void hookCredential() {
        Class<?> password = findClassIfExists("com.android.keyguard.KeyguardAbsKeyInputViewController");
        if (password != null) {
            hook(password, "onPasswordCheckedByMiui", 6, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    int user = (Integer) param.getArgs()[0];
                    if ((Boolean) param.getArgs()[1]) {
                        FaceIslandBridge.onCredentialChecked(param.getThisObject(), user, 0);
                    } else {
                        FaceIslandBridge.onCredentialRejected(param.getThisObject(), user);
                    }
                }
            });
        }

        Class<?> pattern = findClassIfExists("com.android.keyguard.KeyguardPatternViewController");
        if (pattern != null) {
            for (Method method : pattern.getDeclaredMethods()) {
                if (method.getName().contains("onPatternCheckedByMiui")
                    && method.getParameterCount() == 7) {
                    hookMethod(method, new IMethodHook() {
                        @Override
                        public void after(HookParam param) {
                            Object controller = param.getArgs()[0];
                            int user = (Integer) param.getArgs()[1];
                            if ((Boolean) param.getArgs()[2]) {
                                FaceIslandBridge.onCredentialChecked(controller, user, 3);
                            } else {
                                FaceIslandBridge.onCredentialRejected(controller, user);
                            }
                        }
                    });
                    break;
                }
            }
        }

        Class<?> container = findClassIfExists(
            "com.android.keyguard.KeyguardSecurityContainerController$3");
        if (container != null) {
            hook(container, "finish", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    Object token = FaceIslandBridge.beginCredentialFinish(
                        owner, (Integer) param.getArgs()[0]);
                    push(CREDENTIAL_FINISH, token);
                }

                @Override
                public void after(HookParam param) {
                    pop(CREDENTIAL_FINISH);
                }
            });
        }

        Class<?> preHide = findClassIfExists(
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
        if (preHide != null) {
            hook(preHide, "startPreHideAnimation", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object token = peek(CREDENTIAL_FINISH);
                    Object callback = param.getArgs()[0];
                    if (token == null || callback == null) {
                        return;
                    }
                    Object mediator = getObjectField(callback, "this$0");
                    int user = getIntField(callback, "mUserId");
                    FaceIslandBridge.onCredentialPending(mediator, user, token, callback);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 原生人脸图标
    // ------------------------------------------------------------------

    private void hookFaceView() {
        Class<?> binder = findClassIfExists("com.android.keyguard.interactor.KeyguardFaceViewBinder");
        if (binder != null) {
            hook(binder, "syncToFaceView", 0, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object container = getObjectField(param.getThisObject(), "faceContainerView");
                    Object face = getObjectField(param.getThisObject(), "faceView");
                    if (container != null) {
                        FaceIslandBridge.hideNativeFaceIcon(container, face);
                    }
                }
            });
        }

        Class<?> faceView = findClassIfExists(
            "com.miui.keyguard.biometrics.faceunlock.MiuiKeyguardFaceUnlockView");
        if (faceView != null) {
            hook(faceView, "onFinishInflate", 0, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    View view = (View) param.getThisObject();
                    if (view.getId() == BOUNCER_FACE_VIEW_ID) {
                        FaceIslandBridge.hideNativeFaceIcon(view, view);
                    }
                }
            });
            hook(faceView, "setVisibility", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    View view = (View) param.getThisObject();
                    if (view.getId() == BOUNCER_FACE_VIEW_ID && ((Integer) param.getArgs()[0]) == 0) {
                        param.getArgs()[0] = 4;
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 动态岛 & 息屏
    // ------------------------------------------------------------------

    private void hookIsland() {
        Class<?> island = findClassIfExists(
            "com.android.systemui.statusbar.notification.DynamicIslandController");
        if (island != null) {
            hookLargestConstructor(island, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandBridge.bindIsland(param.getThisObject());
                }
            });
        }

        Class<?> surfaces = findClassIfExists(
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$16");
        if (surfaces != null) {
            hook(surfaces, "onDozingChanged", 1, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    Object mediator = owner != null
                        ? getObjectField(owner, "mKeyguardViewMediator") : null;
                    if (mediator != null) {
                        FaceIslandBridge.onKeyguardState(mediator);
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void hookLargestConstructor(Class<?> clazz, IMethodHook callback) {
        Constructor<?> target = null;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (target == null || constructor.getParameterCount() > target.getParameterCount()) {
                target = constructor;
            }
        }
        if (target == null) {
            return;
        }
        Object[] args = new Object[target.getParameterCount() + 1];
        System.arraycopy(target.getParameterTypes(), 0, args, 0, target.getParameterCount());
        args[target.getParameterCount()] = callback;
        findAndHookConstructor(clazz, args);
    }

    private static void hook(Class<?> clazz, String name, int count, IMethodHook callback) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count) {
                hookMethod(method, callback);
                return;
            }
        }
        XposedLog.w(TAG, "method not found: " + clazz.getName() + "#" + name + "/" + count);
    }

    private static final class CredentialRequest {
        boolean active;
        Object entered;
    }

    private static <T> void push(ThreadLocal<ArrayList<T>> stack, T value) {
        stack.get().add(value);
    }

    private static <T> T pop(ThreadLocal<ArrayList<T>> stack) {
        ArrayList<T> list = stack.get();
        return list.isEmpty() ? null : list.remove(list.size() - 1);
    }

    private static Object peek(ThreadLocal<ArrayList<Object>> stack) {
        ArrayList<Object> list = stack.get();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
