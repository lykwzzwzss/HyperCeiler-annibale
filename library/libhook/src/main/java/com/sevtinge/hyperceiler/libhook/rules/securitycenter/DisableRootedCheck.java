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
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.IDexKit;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.dexkit.IDexKitList;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.BaseDataList;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.base.BaseData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class DisableRootedCheck extends BaseHook {
    private static final String SAFETY_DETECT_SERVICE = "com.xiaomi.security.xsof.MiSafetyDetectService";

    private Method mReturnEnvironmentMethod;
    private Class<?> mClientApiRequestClass;
    private Method mEnvironmentPutMethod;
    private Class<?> mRiskAppClass;
    private Field mRiskAppField;
    private Method mUnsetEnvironmentMethod;
    private List<Method> mSetEnvironmentMethods = Collections.emptyList();
    private List<Method> mCheckRootMethods = Collections.emptyList();

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        // 每个 DexKit 查询都独立容错：任一锚点失效只跳过对应子功能，不能中断整个 init。
        mReturnEnvironmentMethod = optionalMember("ReturnEnvironment", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                return bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                        .usingStrings("Invalid Task , reason:  [canceled, ")
                    )).singleOrNull();
            }
        });
        if (mReturnEnvironmentMethod == null) {
            mReturnEnvironmentMethod = optionalMember("ReturnEnvironmentContains", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .usingStrings(Collections.singletonList("Invalid Task , reason"), StringMatchType.Contains)
                            .returnType(void.class)
                        )).singleOrNull();
                }
            });
        }
        if (mReturnEnvironmentMethod == null) {
            mReturnEnvironmentMethod = optionalMember("ReturnEnvironmentByService", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(ClassMatcher.create().className(SAFETY_DETECT_SERVICE))
                            .paramTypes(Object.class)
                            .returnType(void.class)
                        )).singleOrNull();
                }
            });
        }
        if (mReturnEnvironmentMethod == null) {
            XposedLog.e(TAG, getPackageName(),
                "DexKit anchor not found: ReturnEnvironment, skip RiskApp environment bypass");
        }

        mClientApiRequestClass = optionalMember("ClientApiRequest", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassData classData = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingStrings("ClientApiRequest")
                    )).singleOrNull();
                return classData;
            }
        });
        if (mClientApiRequestClass == null) {
            mClientApiRequestClass = optionalMember("ClientApiRequestContains", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassData classData = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .className("ClientApiRequest", StringMatchType.Contains)
                        )).singleOrNull();
                    return classData;
                }
            });
        }
        if (mClientApiRequestClass == null) {
            XposedLog.e(TAG, getPackageName(),
                "DexKit anchor not found: ClientApiRequest class");
        }

        if (mClientApiRequestClass != null) {
            mEnvironmentPutMethod = optionalMember("EnvironmentPut", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    return bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                            .declaredClass(mClientApiRequestClass)
                            .returnType(boolean.class)
                        )).singleOrNull();
                }
            });
            if (mEnvironmentPutMethod == null) {
                mEnvironmentPutMethod = optionalMember("EnvironmentPutNoArg", new IDexKit() {
                    @Override
                    public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                        return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                .declaredClass(mClientApiRequestClass)
                                .returnType(boolean.class)
                                .paramCount(0)
                            )).singleOrNull();
                    }
                });
            }
            if (mEnvironmentPutMethod == null) {
                XposedLog.e(TAG, getPackageName(),
                    "DexKit anchor not found: EnvironmentPut");
            }
        }

        mRiskAppClass = optionalMember("RiskAppClass", new IDexKit() {
            @Override
            public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                ClassData classData = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                        .usingStrings("RiskAppManager")
                    )).singleOrNull();
                return classData;
            }
        });
        if (mRiskAppClass == null) {
            mRiskAppClass = optionalMember("RiskAppClassContains", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    ClassData classData = bridge.findClass(FindClass.create()
                        .matcher(ClassMatcher.create()
                            .className("RiskAppManager", StringMatchType.Contains)
                        )).singleOrNull();
                    return classData;
                }
            });
        }
        if (mRiskAppClass == null) {
            XposedLog.e(TAG, getPackageName(),
                "DexKit anchor not found: RiskAppManager class");
        }

        if (mRiskAppClass != null) {
            mRiskAppField = optionalMember("RiskApp", new IDexKit() {
                @Override
                public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    FieldData fieldData = bridge.findField(FindField.create()
                        .matcher(FieldMatcher.create()
                            .type(mRiskAppClass)
                            .modifiers(Modifier.PRIVATE)
                        )).singleOrNull();
                    return fieldData;
                }
            });
            if (mRiskAppField == null) {
                mRiskAppField = optionalMember("RiskAppFieldInService", new IDexKit() {
                    @Override
                    public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                        FieldData fieldData = bridge.findField(FindField.create()
                            .matcher(FieldMatcher.create()
                                .type(mRiskAppClass)
                                .modifiers(Modifier.PRIVATE)
                                .declaredClass(ClassMatcher.create().className(SAFETY_DETECT_SERVICE))
                            )).singleOrNull();
                        return fieldData;
                    }
                });
            }
            if (mRiskAppField == null) {
                XposedLog.e(TAG, getPackageName(), "DexKit anchor not found: RiskApp field");
            }

            if (mClientApiRequestClass != null) {
                mSetEnvironmentMethods = optionalMemberList("SetEnvironment", new IDexKitList() {
                    @Override
                    public BaseDataList<MethodData> dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                        MethodDataList methodData = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                .returnType(void.class)
                                .paramTypes(mClientApiRequestClass)
                                .declaredClass(mRiskAppClass)
                            )
                        );
                        return methodData;
                    }
                });
                mUnsetEnvironmentMethod = optionalMember("UnsetEnvironment", new IDexKit() {
                    @Override
                    public BaseData dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                        return bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                .usingStrings("RiskAppManager")
                            )).singleOrNull();
                    }
                });
            }
            if (mSetEnvironmentMethods.isEmpty() || mUnsetEnvironmentMethod == null) {
                XposedLog.e(TAG, getPackageName(),
                    "DexKit anchors incomplete: SetEnvironment/UnsetEnvironment");
            }
        }

        // OS4 的 root 检查按检测目录分组，优先用 /system/bin/，失效时依次尝试其它目录字符串。
        mCheckRootMethods = optionalMemberList("CheckRoot", new IDexKitList() {
            @Override
            public BaseDataList<MethodData> dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                MethodDataList methodData = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("/system/bin/")
                                .returnType(boolean.class)
                        )
                );
                return methodData;
            }
        });
        if (mCheckRootMethods.isEmpty()) {
            mCheckRootMethods = optionalMemberList("CheckRootXbin", new IDexKitList() {
                @Override
                public BaseDataList<MethodData> dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    MethodDataList methodData = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("/system/xbin/")
                                    .returnType(boolean.class)
                            )
                    );
                    return methodData;
                }
            });
        }
        if (mCheckRootMethods.isEmpty()) {
            mCheckRootMethods = optionalMemberList("CheckRootSbin", new IDexKitList() {
                @Override
                public BaseDataList<MethodData> dexkit(DexKitBridge bridge) throws ReflectiveOperationException {
                    MethodDataList methodData = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("/system/sbin/")
                                    .returnType(boolean.class)
                            )
                    );
                    return methodData;
                }
            });
        }
        if (mCheckRootMethods.isEmpty()) {
            XposedLog.e(TAG, getPackageName(),
                "DexKit anchor not found: root check methods, skip root check bypass");
        }
        return !mCheckRootMethods.isEmpty() || (mReturnEnvironmentMethod != null && mClientApiRequestClass != null && mEnvironmentPutMethod != null && mRiskAppField != null && mUnsetEnvironmentMethod != null && !mSetEnvironmentMethods.isEmpty());
    }

    @Override
    public void init() {
        if (mReturnEnvironmentMethod != null && mClientApiRequestClass != null
            && mEnvironmentPutMethod != null && mRiskAppField != null
            && mUnsetEnvironmentMethod != null && !mSetEnvironmentMethods.isEmpty()) {
            hookMethod(mReturnEnvironmentMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    try {
                        Object obj = param.getArgs()[0];
                        if (!mClientApiRequestClass.isInstance(obj)) {
                            return;
                        }
                        callMethod(obj, mEnvironmentPutMethod.getName());
                        Object thisObj = param.getThisObject();
                        Object cField = getObjectField(thisObj, mRiskAppField.getName());
                        if (cField == null) {
                            return;
                        }
                        for (Method method : mSetEnvironmentMethods) {
                            // DexKit 每次查询返回的是不同的 Method 实例，必须用 equals 比较
                            if (!method.equals(mUnsetEnvironmentMethod)) {
                                callMethod(cField, method.getName(), obj);
                            }
                        }
                    } catch (Throwable t) {
                        XposedLog.e(TAG, getPackageName(), "Failed to bypass RiskApp environment check", t);
                    }
                }
            });
        } else {
            XposedLog.e(TAG, getPackageName(),
                "Skip RiskApp environment bypass: DexKit anchors incomplete");
        }
        for (Method method : mCheckRootMethods) {
            XposedLog.d(TAG, getPackageName(), "Current hooking method is " + method);
            hookMethod(method, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    param.setResult(false);
                }
            });
        }
    }
}
