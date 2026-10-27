/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 */
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.content.DialogInterface
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Method

/** Continues only the offline, unregistered-app warning in the K90 installer. */
object DisableOfflineInstallWarning : BaseHook() {
    private lateinit var show: Method

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        // JADX prints the package as p123r2; the actual DEX class is r2.C.
        // Resolve by behavior instead of ever treating a decompiler alias as a runtime name.
        show = requiredMember("OfflineInstallWarningShowV1") {
            it.findClass {
                matcher {
                    usingStrings("offline_unregistered_install_popup", "unregistered_install_popup")
                }
            }.findMethod {
                matcher {
                    paramTypes(DialogInterface.OnClickListener::class.java, DialogInterface.OnClickListener::class.java)
                    returnType(Void.TYPE)
                }
            }.single()
        }
        return true
    }

    override fun init() {
        hookMethod(show, object : IMethodHook {
            override fun before(param: HookParam) {
                val offline = try {
                    param.thisObject.javaClass.declaredFields.any { field ->
                        if (field.type != String::class.java) return@any false
                        field.isAccessible = true
                        InstallerWarningPolicy.isOfflineUnregisteredWarning(field.get(param.thisObject) as? String)
                    }
                } catch (_: Throwable) {
                    // If the dialog's identifying field changes, keep the
                    // installer's original warning path intact.
                    false
                }
                if (!offline) return
                // The first listener is the installer's own "Continue" action.
                // It reveals the existing one-time authorization control.
                val continueInstall = param.args[0] as? DialogInterface.OnClickListener ?: return
                try {
                    continueInstall.onClick(null, DialogInterface.BUTTON_POSITIVE)
                } catch (_: Throwable) {
                    // A changed callback contract must not suppress the warning.
                    return
                }
                param.result = null
            }
        })
        XposedLog.i(TAG, "com.miui.packageinstaller", "Resolved offline reminder display: $show")
    }
}
