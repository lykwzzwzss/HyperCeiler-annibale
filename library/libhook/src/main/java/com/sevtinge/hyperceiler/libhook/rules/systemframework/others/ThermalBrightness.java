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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion;
import static com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookConstructor;

import java.lang.reflect.Field;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class ThermalBrightness extends BaseHook {
    public final String displayPowerControllerImpl = "com.android.server.display.DisplayPowerControllerImpl";
    public final String automaticBrightnessControllerImpl = "com.android.server.display.AutomaticBrightnessControllerImpl";
    public final String thermalBrightnessController = "com.android.server.display.ThermalBrightnessController";
    public final String temperatureController = "com.android.server.display.TemperatureController";
    public final String thermalHelper = "com.android.server.audio.MultimediaMiPerception.utils.ThermalHelper";
    public final String thermalObserver = "com.android.server.display.ThermalObserver";


    @Override
    public void init() {
        try {
            findClass(displayPowerControllerImpl);
            findAndHookConstructor(displayPowerControllerImpl,
                new IMethodHook() {
                    @Override
                    public void after(HookParam param) {
                        setThermalFlag(param, "SUPPORT_TEMEPERATURE_CONTROL");
                        setThermalFlag(param, "mThermalBrightnessControlAvailable");
                        setThermalFlag(param, "mApplyThermalBrightnessRate");
                    }
                }
            );
        } catch (Throwable e) {
            XposedLog.e(TAG, getPackageName(), "No found class: " + e);
        }

        try {
            findClass(automaticBrightnessControllerImpl);
            findAndHookConstructor(automaticBrightnessControllerImpl,
                new IMethodHook() {
                    @Override
                    public void after(HookParam param) {
                        setThermalFlag(param, "SUPPORT_TEMEPERATURE_CONTROL");
                    }
                }
            );
        } catch (Throwable e) {
            XposedLog.e(TAG, getPackageName(), "No found class: " + e);
        }

        if (isMoreAndroidVersion(36)) {
            try {
                findClass(thermalHelper).getDeclaredMethod("updateTemperature");
                findAndHookMethod(thermalHelper,
                    "updateTemperature", new IMethodHook() {
                        @Override
                        public void before(HookParam param) {
                            param.setResult(null);
                        }
                    }
                );
            } catch (NoSuchMethodException e) {
                XposedLog.e(TAG, getPackageName(), "Don't Have updateTemperature: " + e);
            } catch (Throwable ne) {
                XposedLog.e(TAG, getPackageName(), "Class not found: " + ne);
            }
        } else {
            try {
                findClass(temperatureController).getDeclaredMethod("updateTemperature");
                findAndHookMethod(temperatureController,
                    "updateTemperature", new IMethodHook() {
                        @Override
                        public void before(HookParam param) {
                            param.setResult(null);
                        }
                    }
                );
            } catch (NoSuchMethodException e) {
                XposedLog.e(TAG, getPackageName(), "Don't Have updateTemperature: " + e);
            } catch (Throwable ne) {
                XposedLog.e(TAG, getPackageName(), "Class not found: " + ne);
            }
        }

        try {
            findClass(thermalObserver).getDeclaredMethod("updateTemperature");
            findAndHookMethod(thermalObserver,
                "updateTemperature", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(null);
                    }
                }
            );
        } catch (NoSuchMethodException e) {
            XposedLog.e(TAG, getPackageName(), "Don't Have updateTemperature: " + e);
        } catch (Throwable ne) {
            XposedLog.e(TAG, getPackageName(), "Class not found: " + ne);
        }

        hookAllMethods("com.android.server.display.DisplayPowerControllerImpl",
            "adjustBrightnessByThermal", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    param.setResult(param.getArgs()[0]);
                }
            }
        );

        try {
            findClassIfExists(displayPowerControllerImpl).getDeclaredMethod("updateThermalBrightness", float.class);
            findAndHookMethod(displayPowerControllerImpl,
                "updateThermalBrightness", float.class, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(null);
                    }
                }
            );
        } catch (NoSuchMethodException e) {
            XposedLog.e(TAG, getPackageName(), "Don't Have updateThermalBrightness: " + e);
        }

        try {
            findClassIfExists(thermalBrightnessController).getDeclaredMethod("updateThermalBrightnessIfNeeded");
            findAndHookMethod(thermalBrightnessController,
                "updateThermalBrightnessIfNeeded", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        param.setResult(false);
                    }
                }
            );
        } catch (NoSuchMethodException e) {
            XposedLog.e(TAG, getPackageName(), "Don't Have updateThermalBrightnessIfNeeded: " + e);
        }

        try {
            findClassIfExists(thermalBrightnessController).getDeclaredMethod("updateConditionState", int.class);
            findAndHookMethod(thermalBrightnessController, "updateConditionState", int.class, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    param.setResult(null);
                }
            });
        } catch (NoSuchMethodException e) {
            XposedLog.e(TAG, getPackageName(), "Don't Have updateConditionState: " + e);
        }
    }

    /**
     * 把热控亮度开关写进构造函数刚刚创建出来的宿主对象。
     * 原写法直接把 HookParam 当作宿主对象传给 setBooleanField，字段永远写不到目标实例上，
     * 热控降亮度 Hook 必然失效。
     * 字段在目标 ROM 上不存在（被改名、被移除或为编译期内联常量）时只记录日志，不抛出。
     */
    private void setThermalFlag(HookParam param, String fieldName) {
        Object target = param == null ? null : param.getThisObject();
        if (target == null) {
            XposedLog.w(TAG, getPackageName(), "Skip null instance, field: " + fieldName);
            return;
        }
        Class<?> targetClass = target.getClass();
        Field field = findBooleanField(targetClass, fieldName);
        if (field == null) {
            XposedLog.w(TAG, getPackageName(),
                "Field not found: " + targetClass.getName() + "#" + fieldName);
            return;
        }
        try {
            field.setBoolean(target, false);
        } catch (Throwable t) {
            XposedLog.w(TAG, getPackageName(),
                "Failed to set field: " + targetClass.getName() + "#" + fieldName, t);
        }
    }

    private static Field findBooleanField(Class<?> clazz, String fieldName) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                Class<?> type = field.getType();
                if (type != boolean.class && type != Boolean.class) {
                    return null;
                }
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
