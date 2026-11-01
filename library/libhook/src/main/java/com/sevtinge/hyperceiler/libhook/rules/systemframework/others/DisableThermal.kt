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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook

object DisableThermal : BaseHook() {
    private val eventListenerParameters = listOf(
        "android.os.Temperature",
        "android.os.IThermalEventListener",
        "java.lang.Integer"
    )

    override fun init() {
        // K90 / Android 17 moved the callback into the thermal package and renamed it.
        // This suppresses only IThermalEventListener notifications. Temperature updates,
        // shutdown handling, status listeners, headroom listeners and the HAL stay active.
        if (hookEventDispatch(
                "com.android.server.power.thermal.ThermalManagerService",
                "postEventListenerLocked"
            )) {
            XposedLog.i(TAG, "Thermal event listener callbacks disabled on Android 17")
        } else if (hookEventDispatch(
                "com.android.server.power.ThermalManagerService",
                "postEventListener"
            )) {
            XposedLog.i(TAG, "Thermal event listener callbacks disabled through legacy service")
        } else {
            XposedLog.w(TAG, "Thermal event listener hook unavailable on this ROM")
        }
    }

    private fun hookEventDispatch(className: String, methodName: String): Boolean {
        val targetClass = findClassIfExists(className) ?: return false
        val methods = targetClass.declaredMethods.filter {
            it.name == methodName
                && it.returnType == Void.TYPE
                && it.parameterTypes.map { parameter -> parameter.name } == eventListenerParameters
        }
        if (methods.isEmpty()) return false

        methods.forEach { method ->
            hookMethod(method, object : IMethodHook {
                override fun before(param: HookParam) {
                    param.result = null
                }
            })
        }
        return true
    }
}
