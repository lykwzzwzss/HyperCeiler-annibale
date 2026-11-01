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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.mipad

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHooks

object RemoveStylusBluetoothRestriction : BaseHook() {

    private const val STYLUS_LISTENER_CLASS =
        "com.miui.server.input.stylus.MiuiStylusDeviceListener"

    /**
     * 真正决定手写笔限制状态的方法。不同 ROM 命名不同，只 Hook 实际存在的那几个，
     * 不再像以前那样替换整个类的所有方法。
     */
    private val restrictionMethodNames = listOf(
        "setTouchModeStylusEnable",
        "setTouchMode",
        "setStylusDeviceEnable",
        "isStylusDeviceEnable",
        "isStylusEnable"
    )

    override fun init() {
        val clazzMiuiStylusDeviceListener = findClass(STYLUS_LISTENER_CLASS)

        // 构造完成后立即放开一次
        clazzMiuiStylusDeviceListener.declaredConstructors.toList().createHooks {
            after {
                setTouchModeStylusEnable()
            }
        }

        var hookedCount = 0
        restrictionMethodNames.forEach { methodName ->
            val methods = clazzMiuiStylusDeviceListener.declaredMethods
                .filter { it.name == methodName }
            if (methods.isEmpty()) {
                XposedLog.w(
                    TAG, packageName,
                    "Method not found: ${clazzMiuiStylusDeviceListener.name}#$methodName"
                )
                return@forEach
            }
            methods.forEach { method ->
                if (method.returnType == java.lang.Boolean.TYPE) {
                    // 查询类方法直接返回“已启用”；非 void 方法不返回 null
                    method.createHook {
                        before {
                            it.result = true
                        }
                    }
                } else {
                    // 其余目标方法保留原逻辑，执行结束后重新写入手写笔触控模式
                    method.createHook {
                        after {
                            setTouchModeStylusEnable()
                        }
                    }
                }
                hookedCount++
            }
        }

        if (hookedCount == 0) {
            XposedLog.w(
                TAG, packageName,
                "No stylus restriction method hooked, only constructor hook applied"
            )
        }
    }

    private fun setTouchModeStylusEnable() {
        try {
            val driverVersion =
                PrefsBridge.getStringAsInt("mipad_input_bluetooth_version", 2)
            val flag: Int = 0x10 or driverVersion
            val instanceITouchFeature =
                callStaticMethod(
                    findClass("miui.util.ITouchFeature"),
                    "getInstance"
                ) ?: return
            callMethod(
                instanceITouchFeature,
                "setTouchMode",
                0, 20, flag
            )
        } catch (t: Throwable) {
            XposedLog.w(TAG, packageName, "Failed to apply stylus touch mode", t)
        }
    }
}
