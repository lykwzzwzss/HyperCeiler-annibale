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
package com.sevtinge.hyperceiler.libhook.rules.systemui.superisland;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHooks;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 人脸认证链路：认证回调包装、接受事件、检测状态和取消。
 */
public class FaceIslandFaceAuthRule extends FaceIslandRuleBase {

    private static final ThreadLocal<ArrayList<Object>> FACE_ACCEPT =
        ThreadLocal.withInitial(ArrayList::new);
    /** 已安装 onAuthenticationSucceeded hook 的方法，防止重复注册导致事件下发两次。 */
    private static final Set<Method> SUCCEEDED_HOOKS = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        hookFaceAuthenticated();
        hookRepository();
        hookFaceManager();
    }

    private void hookFaceAuthenticated() {
        Class<?> monitor = find("com.android.keyguard.KeyguardUpdateMonitor");
        if (monitor == null) {
            return;
        }
        hook(monitor, "onFaceAuthenticated", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = argsOf(param);
                int user = number(args, 0, -1);
                try {
                    Object tracker = getObjectField(param.getThisObject(), "mStrongAuthTracker");
                    int strong = (Integer) callMethod(tracker, "getStrongAuthForUser", user);
                    if ((strong & 4096) != 0) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
                FaceIslandHooks.onFaceAccepted(param.getThisObject(), user, bool(args, 1, false));
            }
        });
    }

    private void hookRepository() {
        Class<?> repository = find(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl");
        if (repository != null) {
            hook(repository, "cancel", 0, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onDetectionChanged(param.getThisObject());
                    FaceIslandHooks.onAuthStopped(param.getThisObject());
                }
            });
            hook(repository, "access$onFaceAuthRequestCompleted", 1, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onAuthStopped(param.getArgs()[0]);
                }
            });
        }

        Class<?> detect = find(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$detect$2");
        if (detect != null) {
            hook(detect, "invokeSuspend", 1, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    if (owner != null) {
                        FaceIslandHooks.onDetectionChanged(owner);
                    }
                }
            });
        }

        Class<?> detection = find(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$detectionCallback$1");
        if (detection != null) {
            hook(detection, "onFaceDetected", 3, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    if (owner != null) {
                        FaceIslandHooks.onDetectionChanged(owner);
                    }
                }
            });
        }

        Class<?> faceCallback = find(
            "com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl$faceAuthCallback$1");
        if (faceCallback != null) {
            for (Method method : faceCallback.getDeclaredMethods()) {
                if (!"onAuthenticationSucceeded".equals(method.getName())
                    || method.getParameterCount() != 1) {
                    continue;
                }
                // 同名同参方法会被逐个 hook，重复注册同一方法要跳过，避免重复下发 FACE_PRODUCED。
                if (!SUCCEEDED_HOOKS.add(method)) {
                    continue;
                }
                hookMethod(method, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        try {
                            Object repo = getObjectField(param.getThisObject(), "this$0");
                            Object running = callMethod(getObjectField(repo, "_isAuthRunning"), "getValue");
                            if (Boolean.FALSE.equals(running)) {
                                return;
                            }
                        } catch (Throwable ignored) {
                        }
                        FaceIslandHooks.onFaceProduced(param.getArgs()[0]);
                    }
                });
            }
        }

        Class<?> acceptance = find(
            "com.android.systemui.deviceentry.domain.interactor.SystemUIDeviceEntryFaceAuthInteractor$observeFaceAuthStateUpdates$1");
        if (acceptance != null) {
            hook(acceptance, "invokeSuspend", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    push(FACE_ACCEPT,
                        FaceIslandHooks.enterFaceAcceptanceFromCoroutine(param.getThisObject()));
                }

                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.leaveFaceAcceptance(pop(FACE_ACCEPT));
                }
            });
        }
    }

    private void hookFaceManager() {
        Class<?> faceManager = find("android.hardware.face.MiuiFaceManager");
        if (faceManager == null) {
            return;
        }
        for (Method method : faceManager.getDeclaredMethods()) {
            if (!"authenticate".equals(method.getName())) {
                continue;
            }
            hookMethod(method, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object[] args = argsOf(param);
                    int user = -1;
                    for (int index = args.length - 1; index >= 0; index--) {
                        if (args[index] instanceof Integer) {
                            user = (Integer) args[index];
                            break;
                        }
                    }
                    for (int index = 0; index < args.length; index++) {
                        Object callback = args[index];
                        if (callback == null) {
                            continue;
                        }
                        String name = callback.getClass().getName();
                        if (!name.contains("faceAuthCallback") || name.contains("faceisland")) {
                            continue;
                        }
                        Object wrapped = FaceIslandHooks.wrapFaceCallback(callback, user);
                        if (wrapped != null) {
                            args[index] = wrapped;
                            XposedLog.d(TAG, "wrapped face callback " + name + " user=" + user);
                        }
                    }
                }
            });
        }
    }
}
