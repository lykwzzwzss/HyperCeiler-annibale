/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.securitycenter;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.IDexKit;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.base.BaseData;

import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class InstallIntercept extends BaseHook {
    private static final String INTERCEPT_ENABLED_KEY = "permcenter_install_intercept_enabled";

    private Method mInstallMethod;

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        mInstallMethod = optionalMember("install", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) {
                return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings(INTERCEPT_ENABLED_KEY)
                                .returnType(boolean.class)
                        )).singleOrNull();
            }
        });
        if (mInstallMethod == null) {
            mInstallMethod = optionalMember("installByClass", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .usingStrings(INTERCEPT_ENABLED_KEY))
                                    .returnType(boolean.class)
                            )).singleOrNull();
                }
            });
        }
        if (mInstallMethod == null) {
            mInstallMethod = optionalMember("installByShape", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings(INTERCEPT_ENABLED_KEY)
                                    .paramCount(0)
                                    .returnType(boolean.class)
                            )).singleOrNull();
                }
            });
        }
        if (mInstallMethod == null) {
            XposedLog.e(TAG, getPackageName(),
                    "DexKit anchor not found: install intercept enabled, skip hook");
        }
        return true;
    }

    @Override
    public void init() {
        if (mInstallMethod == null) {
            return;
        }
        hookMethod(mInstallMethod,
                new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(false);
                    }
                }
        );
    }
}
