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

import java.util.ArrayList;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 指纹认证链路。只有在锁屏可见/未交互时才把指纹结果交给岛控制器。
 */
public class FaceIslandFingerprintRule extends FaceIslandRuleBase {

    private static final ThreadLocal<ArrayList<Object>> POLICY =
        ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<boolean[]> STATE =
        ThreadLocal.withInitial(() -> new boolean[2]);

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        hook(find("com.android.keyguard.KeyguardUpdateMonitor"), "onFingerprintAuthenticated", 2,
            new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object[] args = argsOf(param);
                    FaceIslandHooks.onFingerprintProduced(param.getThisObject(),
                        number(args, 0, -1), bool(args, 1, false));
                }
            });

        Class<?> injector = find("com.android.keyguard.injector.BiometricUnlockControllerInjector");
        if (injector != null) {
            hook(injector, "onBiometricAuthenticated$1", 3, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    boolean[] state = STATE.get();
                    state[0] = FaceIslandHooks.isBiometricAuthenticated(param.getThisObject());
                    state[1] = FaceIslandHooks.isFingerprintUnlockContext(param.getThisObject());
                    Object[] args = argsOf(param);
                    push(POLICY, FaceIslandHooks.enterFingerprintPolicy(param.getThisObject(),
                        number(args, 0, -1), args.length > 1 ? args[1] : null,
                        bool(args, 2, false)));
                }

                @Override
                public void after(HookParam param) {
                    boolean[] state = STATE.get();
                    Object[] args = argsOf(param);
                    FaceIslandHooks.onFingerprintBiometricAuthenticated(param.getThisObject(),
                        number(args, 0, -1), args.length > 1 ? args[1] : null,
                        bool(args, 2, false), state[0], state[1]);
                    FaceIslandHooks.leaveFingerprintPolicy(pop(POLICY), param.getThisObject());
                }
            });
        }

        hook(find("com.android.systemui.statusbar.phone.BiometricUnlockController"),
            "startWakeAndUnlock", 2, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.onFingerprintUnlockBranch(param.getThisObject());
                }
            });
    }
}
