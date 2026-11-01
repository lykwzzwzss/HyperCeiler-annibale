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

import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHooks;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 锁屏显示/隐藏与休眠唤醒状态。
 */
public class FaceIslandKeyguardPowerRule extends FaceIslandRuleBase {

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        Class<?> mediator = find("com.android.systemui.keyguard.KeyguardViewMediator");
        if (mediator != null) {
            hook(mediator, "handleHide", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandHooks.onHide(param.getThisObject());
                }

                @Override
                public void after(HookParam param) {
                    // handleHide 提交 mHiding 后立即刷新，不必等心跳或桌面转场结束。
                    FaceIslandHooks.onKeyguardState(param.getThisObject());
                }
            });
            hook(mediator, "handleKeyguardDone", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    try {
                        if (!getBooleanField(param.getThisObject(), "mGoingToSleep")) {
                            FaceIslandHooks.onMediatorDone(param.getThisObject());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            hook(mediator, "setShowingLocked", 3, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onKeyguardState(param.getThisObject());
                }
            });
            hook(mediator, "handleCancelKeyguardExitAnimation", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandHooks.onHideCancelled(param.getThisObject());
                }
            });
        }

        Class<?> service = find("com.android.systemui.keyguard.KeyguardService$2");
        if (service != null) {
            hook(service, "onStartedGoingToSleep", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandHooks.onSleepStartedFromService(param.getThisObject());
                }

                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onKeyguardStateFromService(param.getThisObject());
                }
            });
            hook(service, "onFinishedGoingToSleep", 2, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onSleepFinishedFromService(param.getThisObject());
                    FaceIslandHooks.onKeyguardStateFromService(param.getThisObject());
                }
            });
            hook(service, "onStartedWakingUp", 2, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onWakeStartedFromService(param.getThisObject());
                    FaceIslandHooks.onKeyguardStateFromService(param.getThisObject());
                }
            });
        }

        Class<?> surfaces = find("com.android.systemui.statusbar.phone.CentralSurfacesImpl$16");
        if (surfaces != null) {
            hook(surfaces, "onDozingChanged", 1, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object owner = getObjectField(param.getThisObject(), "this$0");
                    Object mediator2 = owner != null
                        ? getObjectField(owner, "mKeyguardViewMediator") : null;
                    if (mediator2 != null) {
                        FaceIslandHooks.onKeyguardState(mediator2);
                    }
                }
            });
        }
    }
}
