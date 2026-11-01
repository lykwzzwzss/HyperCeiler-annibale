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

import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * PIN / 密码 / 图案校验，以及解锁退场回调。
 */
public class FaceIslandCredentialRule extends FaceIslandRuleBase {

    private static final ThreadLocal<ArrayList<Object>> CREDENTIAL_FINISH =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<CredentialRequest>> CREDENTIAL_REQUEST =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<ArrayList<Object>> EXIT_CALLBACK =
        ThreadLocal.withInitial(ArrayList::new);

    private static final class CredentialRequest {
        boolean active;
        Object entered;
    }

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        hookPassword();
        hookPattern();
        hookSecurityContainer();
        hookMediatorCallback();
        hookExitCallback();
    }

    private void hookPassword() {
        hook(find("com.android.keyguard.KeyguardAbsKeyInputViewController"),
            "onPasswordCheckedByMiui", 6, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object[] args = argsOf(param);
                    int user = number(args, 0, -1);
                    if (bool(args, 1, false)) {
                        FaceIslandHooks.onCredentialChecked(param.getThisObject(), user, 0);
                    } else {
                        FaceIslandHooks.onCredentialRejected(param.getThisObject(), user);
                    }
                }
            });
    }

    private void hookPattern() {
        Class<?> pattern = find("com.android.keyguard.KeyguardPatternViewController");
        if (pattern == null) {
            return;
        }
        for (Method method : pattern.getDeclaredMethods()) {
            if (!method.getName().contains("onPatternCheckedByMiui") || method.getParameterCount() != 7) {
                continue;
            }
            hookMethod(method, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object[] args = argsOf(param);
                    Object owner = args.length > 0 ? args[0] : null;
                    int user = number(args, 1, -1);
                    if (bool(args, 2, false)) {
                        FaceIslandHooks.onCredentialChecked(owner, user, 3);
                    } else {
                        FaceIslandHooks.onCredentialRejected(owner, user);
                    }
                }
            });
            break;
        }
    }

    private void hookSecurityContainer() {
        hook(find("com.android.keyguard.KeyguardSecurityContainerController$3"), "finish", 1,
            new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object token = FaceIslandHooks.beginCredentialFinishFromCallback(
                        param.getThisObject(), number(argsOf(param), 0, -1));
                    push(CREDENTIAL_FINISH, token);
                    CredentialRequest state = new CredentialRequest();
                    if (token != null) {
                        state.active = true;
                        state.entered = FaceIslandHooks.enterCredentialRequest(token);
                    }
                    push(CREDENTIAL_REQUEST, state);
                }

                @Override
                public void after(HookParam param) {
                    CredentialRequest state = pop(CREDENTIAL_REQUEST);
                    if (state != null && state.active) {
                        FaceIslandHooks.leaveCredentialRequest(state.entered);
                    }
                    pop(CREDENTIAL_FINISH);
                }
            });
    }

    private void hookMediatorCallback() {
        hook(find("com.android.systemui.keyguard.KeyguardViewMediator$5"), "keyguardDone", 1,
            new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandHooks.onDoneRequestFromCallback(param.getThisObject(),
                        number(argsOf(param), 0, -1));
                }
            });
    }

    private void hookExitCallback() {
        Class<?> exit = find("com.android.systemui.keyguard.KeyguardViewMediator$OnHideAnimationFinished");
        if (exit == null) {
            return;
        }
        hookLargestConstructor(exit, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = argsOf(param);
                if (args.length < 2) {
                    return;
                }
                int user = args[1] instanceof Integer ? (Integer) args[1] : -1;
                FaceIslandHooks.onExitAnimationCreated(param.getThisObject(), args[0], user);
                Object token = peek(CREDENTIAL_FINISH);
                if (token != null) {
                    FaceIslandHooks.onCredentialPending(args[0], user, token, param.getThisObject());
                }
            }
        });
        hook(exit, "run", 0, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                push(EXIT_CALLBACK, FaceIslandHooks.enterExitCallback(param.getThisObject()));
            }

            @Override
            public void after(HookParam param) {
                FaceIslandHooks.onHideAnimationFinished(param.getThisObject());
                FaceIslandHooks.leaveExitCallback(pop(EXIT_CALLBACK));
            }
        });
    }
}
