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

package com.sevtinge.hyperceiler.libhook.rules.securitycenter;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.IDexKit;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.base.BaseData;

import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class BypassAdbInstallVerify extends BaseHook {
    private static final String VERIFY_ACTIVITY = "com.miui.permcenter.install.AdbInstallVerifyActivity";
    private static final String VERIFY_TASK = VERIFY_ACTIVITY + "$b";
    private static final String VERIFY_URL = "https://srv.sec.miui.com/data/adb";

    private Method mAdbInstallNetworkVerifyMethod;
    private Method mAdbInstallCallerMethod;

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        // 网络检查入口在不同版本被 R8 拆分/内联：先按“类中使用 URL + 方法中使用 connectivity”定位，
        // 再退回类名 + connectivity，单条查询失败不影响另一条 Hook。
        mAdbInstallNetworkVerifyMethod = optionalMember("AdbInstallNetworkVerify", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(ClassMatcher.create()
                                        .usingStrings(VERIFY_URL))
                                .usingStrings("connectivity")
                        )).singleOrNull();
            }
        });
        if (mAdbInstallNetworkVerifyMethod == null) {
            mAdbInstallNetworkVerifyMethod = optionalMember("AdbInstallNetworkVerifyByClass", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .className(VERIFY_ACTIVITY))
                                    .usingStrings("connectivity")
                                    .returnType(void.class)
                            )).singleOrNull();
                }
            });
        }
        if (mAdbInstallNetworkVerifyMethod == null) {
            mAdbInstallNetworkVerifyMethod = optionalMember("AdbInstallNetworkVerifyByActivity", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .className(VERIFY_ACTIVITY))
                                    .usingStrings("connectivity")
                            )).singleOrNull();
                }
            });
        }
        if (mAdbInstallNetworkVerifyMethod == null) {
            XposedLog.e(TAG, getPackageName(),
                    "DexKit anchor not found: AdbInstallNetworkVerify, skip network check bypass");
        }

        mAdbInstallCallerMethod = optionalMember("AdbInstallCaller", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("AdbInstallActivity", "start request for adb install!")
                        )).singleOrNull();
            }
        });
        if (mAdbInstallCallerMethod == null) {
            mAdbInstallCallerMethod = optionalMember("AdbInstallCallerByTask", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .className(VERIFY_TASK))
                                    .usingStrings("start request for adb install!")
                                    .returnType(String.class)
                            )).singleOrNull();
                }
            });
        }
        if (mAdbInstallCallerMethod == null) {
            mAdbInstallCallerMethod = optionalMember("AdbInstallCallerByTaskShape", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(ClassMatcher.create()
                                            .className(VERIFY_TASK))
                                    .returnType(String.class)
                                    .paramCount(1)
                            )).singleOrNull();
                }
            });
        }
        if (mAdbInstallCallerMethod == null) {
            XposedLog.e(TAG, getPackageName(),
                    "DexKit anchor not found: AdbInstallCaller, skip caller bypass");
        }
        return true;
    }

    @Override
    public void init() {
        if (mAdbInstallNetworkVerifyMethod != null) {
            hookMethod(mAdbInstallNetworkVerifyMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    try {
                        Object instance = param.getThisObject();
                        Class<?> taskClass = findClassIfExists(VERIFY_TASK, getClassLoader());
                        if (taskClass == null) {
                            XposedLog.e(TAG, getPackageName(), "Class not found: " + VERIFY_TASK);
                            return;
                        }
                        Object adbInstallVerifyActivityInstance = newInstance(taskClass, instance);
                        setObjectField(instance, "d", adbInstallVerifyActivityInstance);
                        Object threadPoolExecutor = getStaticObjectField(findClass("android.os.AsyncTask", getClassLoader()), "THREAD_POOL_EXECUTOR");
                        callMethod(adbInstallVerifyActivityInstance, "executeOnExecutor", threadPoolExecutor, new Void[]{});
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedLog.e(TAG, getPackageName(), "Failed to bypass adb install network verify", t);
                    }
                }
            });
        }
        if (mAdbInstallCallerMethod != null) {
            hookMethod(mAdbInstallCallerMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    param.setResult(null);
                }
            });
        }
    }
}
