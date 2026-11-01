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

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class QuickScreenshot extends BaseHook {
    @Override
    public void init() {
        IMethodHook removeDelay = new IMethodHook() {
            @Override
            public void before(HookParam param) {
                param.setResult(0L);
            }
        };

        // Android 17 moved screenshot chord handling out of PhoneWindowManager.
        // Prefer the active controller, and retain the old path for earlier releases.
        if (tryHook("com.android.server.input.KeyGestureController", removeDelay)) {
            XposedLog.i(TAG, "Screenshot chord delay disabled through KeyGestureController");
        } else if (tryHook("com.android.server.policy.PhoneWindowManager", removeDelay)) {
            XposedLog.i(TAG, "Screenshot chord delay disabled through PhoneWindowManager");
        } else {
            XposedLog.w(TAG, "Screenshot chord delay hook unavailable on this ROM");
        }
    }

    private boolean tryHook(String className, IMethodHook hook) {
        Class<?> targetClass = findClassIfExists(className);
        if (targetClass == null) return false;

        for (java.lang.reflect.Method method : targetClass.getDeclaredMethods()) {
            if (!method.getName().equals("getScreenshotChordLongPressDelay")
                || method.getParameterCount() != 0
                || method.getReturnType() != Long.TYPE) {
                continue;
            }
            try {
                return hookMethod(method, hook) != null;
            } catch (Throwable t) {
                XposedLog.w(TAG, "Unable to hook " + className + ".getScreenshotChordLongPressDelay(): " + t);
                return false;
            }
        }
        return false;
    }
}
