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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.util.ArrayDeque;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Media key steps for K90 / HyperOS 4. Absolute volume and the slider keep native units. */
public class VolumeMediaSteps extends BaseHook {
    private final ThreadLocal<ArrayDeque<Integer>> adjustments = new ThreadLocal<>();
    private boolean rangeFailureLogged;

    @Override
    public void init() {
        final int steps = PrefsBridge.getInt("system_framework_volume_media_steps", 15);
        if (steps == 15) return; // Default means the original vendor behavior, including off-grid values.
        if (steps < 15 || steps > 45) {
            XposedLog.w(TAG, "Unsupported media key step count: " + steps);
            return;
        }
        Class<?> service = findClass("com.android.server.audio.AudioService");
        Class<?> deviceAttributes = findClass("android.media.AudioDeviceAttributes");
        // Check both conversion methods before installing anything. An unsupported ROM
        // must not be left with a partially active key-step override.
        try {
            service.getDeclaredMethod("rescaleStep", int.class, int.class, int.class);
            service.getDeclaredMethod("rescaleStepBySuperVolume", int.class, int.class, int.class);
        } catch (ReflectiveOperationException e) {
            XposedLog.w(TAG, "Media key step conversion unavailable: " + e);
            return;
        }
        findAndHookMethod(service, "adjustStreamVolume", int.class, int.class, int.class,
            deviceAttributes, String.class, String.class, int.class, int.class,
            String.class, boolean.class, int.class, new IMethodHook() {
                @Override public void before(HookParam param) {
                    Object[] args = param.getArgs();
                    ArrayDeque<Integer> stack = adjustments.get();
                    if (stack == null) {
                        stack = new ArrayDeque<>();
                        adjustments.set(stack);
                    }
                    boolean eligible = MediaVolumeStepPolicy.applies((int) args[0], (int) args[1],
                        (int) args[2], args[3] != null, (String) args[4]);
                    // Push even ineligible nested calls so they cannot inherit an outer media request.
                    stack.push(eligible ? (int) args[1] : 0);
                }
                @Override public void after(HookParam param) {
                    ArrayDeque<Integer> stack = adjustments.get();
                    if (stack == null) return;
                    if (!stack.isEmpty()) stack.pop();
                    if (stack.isEmpty()) adjustments.remove();
                }
            });
        IMethodHook conversion = new IMethodHook() {
            @Override public void after(HookParam param) {
                ArrayDeque<Integer> stack = adjustments.get();
                if (param.getHasThrowable() || stack == null || stack.isEmpty() || stack.peek() == 0) return;
                Object[] args = param.getArgs();
                if ((int) args[1] != 3 || (int) args[2] != 3) return;
                try {
                    Object audioService = param.getThisObject();
                    Object state = callMethod(audioService, "getVssForStreamOrDefault", 3);
                    int device = (int) callMethod(audioService, "getDeviceForStream", 3);
                    int current = (int) callMethod(state, "getIndex", device);
                    int min = (int) callMethod(state, "getMinIndex");
                    int max = (int) callMethod(state, "getMaxIndex");
                    // AudioService uses index * 10 internally. Grid points must remain
                    // whole visible indices, which are also the Bluetooth sync unit.
                    int visibleMin = (min + 5) / 10;
                    int visibleMax = (max + 5) / 10;
                    if (visibleMax - visibleMin < steps) return;
                    int visibleCurrent = (current + 5) / 10;
                    int direction = stack.peek();
                    int distance = MediaVolumeStepPolicy.distance(
                        visibleCurrent, visibleMin, visibleMax, steps, direction);
                    int target = Math.max(min, Math.min(max,
                        (visibleCurrent + direction * distance) * 10));
                    param.setResult(Math.abs(target - current));
                } catch (Throwable e) {
                    if (!rangeFailureLogged) {
                        rangeFailureLogged = true;
                        XposedLog.w(TAG, "Native media range unavailable; retaining vendor step: " + e);
                    }
                }
            }
        };
        findAndHookMethod(service, "rescaleStep", int.class, int.class, int.class, conversion);
        findAndHookMethod(service, "rescaleStepBySuperVolume", int.class, int.class, int.class, conversion);
        XposedLog.i(TAG, "Media keys use " + steps + " grid intervals; native slider/absolute-volume range preserved");
    }
}
