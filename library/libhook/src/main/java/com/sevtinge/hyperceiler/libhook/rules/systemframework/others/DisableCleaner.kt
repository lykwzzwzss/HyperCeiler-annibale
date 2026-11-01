/*
  * This file is part of HyperCeiler.
  *
  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.
  */
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook

object DisableCleaner : BaseHook() {
    override fun init() {
        hookVoidMethods("com.android.server.am.ActivityManagerService", "checkExcessivePowerUsage")
        hookIntMethods("com.android.server.am.ActivityManagerShellCommand", "runKillAll") {
            it.result = 0
        }
        hookBooleanMethods("com.android.server.am.psc.OomAdjuster", "shouldKillExcessiveProcesses")
        hookBooleanMethods("com.android.server.am.OomAdjuster", "shouldKillExcessiveProcesses")
        hookUpdateAndTrim("com.android.server.am.psc.OomAdjuster")
        hookUpdateAndTrim("com.android.server.am.OomAdjuster")
        hookVoidMethods("com.android.server.am.PhantomProcessList", "trimPhantomProcessesIfNecessary")
        hookVoidMethods("com.android.server.am.ProcessMemoryCleaner", "checkBackgroundProcCompact")
        hookVoidMethods("com.android.server.am.ProcessPowerCleaner", "handleAutoLockOff")
        hookVoidMethods("com.android.server.am.SystemPressureControllerNative", "nStartPressureMonitor")
        hookVoidMethods("com.android.server.am.SystemPressureController", "nStartPressureMonitor")
        hookVoidMethods("com.android.server.wm.RecentTasks", "trimInactiveRecentTasks")
        hookVoidMethods("com.android.server.am.CameraBooster", "boostCameraIfNeeded")
        hookVoidMethods("com.miui.cameraopt.adapter.ProcessManagerAdapter", "killApplication")
    }

    private fun hookVoidMethods(className: String, methodName: String) = hookMethods(
        className, methodName, Void.TYPE
    ) { it.result = null }

    private fun hookBooleanMethods(className: String, methodName: String) = hookMethods(
        className, methodName, java.lang.Boolean.TYPE
    ) { it.result = false }

    private fun hookIntMethods(className: String, methodName: String, callback: (HookParam) -> Unit) =
        hookMethods(className, methodName, Integer.TYPE, callback)

    private fun hookMethods(
        className: String,
        methodName: String,
        returnType: Class<*>,
        callback: (HookParam) -> Unit
    ) {
        val targetClass = findClassIfExists(className) ?: return
        targetClass.declaredMethods
            .filter { it.name == methodName && it.returnType == returnType }
            .forEach { method ->
                hookMethod(method, object : IMethodHook {
                    override fun before(param: HookParam) {
                        callback(param)
                    }
                })
            }
    }

    private fun hookUpdateAndTrim(className: String) {
        val targetClass = findClassIfExists(className) ?: return
        targetClass.declaredMethods
            .filter {
                it.name == "updateAndTrimProcessLSP" && it.returnType == Void.TYPE
                    && it.parameterTypes.size >= 3
                    && (it.parameterTypes[2] == java.lang.Long.TYPE
                        || it.parameterTypes[2] == Integer.TYPE)
            }
            .forEach { method ->
                hookMethod(method, object : IMethodHook {
                    override fun before(param: HookParam) {
                        val args = param.args
                        if (args.size < 3) return
                        // The third parameter is the last-trim cutoff on both known signatures.
                        args[2] = when (args[2]) {
                            is Long -> 0L
                            is Int -> 0
                            else -> return
                        }
                    }
                })
            }
    }
}
