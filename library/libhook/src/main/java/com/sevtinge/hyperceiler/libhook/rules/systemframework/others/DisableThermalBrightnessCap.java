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

package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Android 17 / HyperOS 4 display-only thermal caps. Temperature sensing stays active. */
public final class DisableThermalBrightnessCap extends BaseHook {
    @Override
    public void init() {
        // Android 17 moved the old BrightnessThrottler into a dedicated state modifier.
        // Do not touch the controller's aggregated cap: it also contains power/other policies.
        String modifier = "com.android.server.display.brightness.clamper.BrightnessThermalModifier";
        skipThermalModifier(modifier, "apply");
        skipThermalModifier(modifier, "applyStateChange");

        String miui = "com.android.server.display.DisplayPowerControllerImpl";
        try {
            // NaN is this implementation's 'no thermal limit' input. Let the original method
            // still compute its separate low-battery policy and notify the display controller.
            if (findAndHookMethod(miui, "updateThermalBrightness", float.class, new IMethodHook() {
                @Override public void before(HookParam param) {
                    param.getArgs()[0] = Float.NaN;
                }
            }) != null) XposedLog.i(TAG, "MIUI display thermal-nit cap disabled");
        } catch (Throwable e) {
            XposedLog.w(TAG, "MIUI thermal-nit hook unavailable: " + e);
        }
        try {
            // Battery thermal level has its own display cap, separate from low battery.
            // Keep the level, sensors and update notifications intact; only neutralize the cap.
            if (findAndHookMethod(miui, "getBclLimitBrightness", int.class, new IMethodHook() {
                @Override public void before(HookParam param) {
                    param.setResult(1.0f);
                }
            }) != null) XposedLog.i(TAG, "MIUI battery-thermal display cap disabled");
        } catch (Throwable e) {
            XposedLog.w(TAG, "MIUI battery-thermal display hook unavailable: " + e);
        }
    }

    private void skipThermalModifier(String className, String method) {
        try {
            if (!hookAllMethods(className, method, new IMethodHook() {
                @Override public void before(HookParam param) {
                    param.setResult(null); // Both observed modifier entry points return void.
                }
            }).isEmpty()) XposedLog.i(TAG, "Android display thermal modifier disabled: " + method);
        } catch (Throwable e) {
            XposedLog.w(TAG, "Android display thermal hook unavailable: " + method + ": " + e);
        }
    }
}
