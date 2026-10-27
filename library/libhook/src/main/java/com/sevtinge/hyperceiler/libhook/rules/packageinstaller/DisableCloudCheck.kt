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
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.content.Context
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import java.lang.reflect.Method

object DisableCloudCheck : BaseHook() {
    override fun useDexKit() = true

    private lateinit var fetch: Method

    override fun initDexKit(): Boolean {
        // AppMarket.syncSearch (C0644e.c) on installer 5.5.5.0.0-20260821.
        // The old NormalInstallBtn string was removed; requiring it disabled
        // this entire hook before the cloud request could be intercepted.
        fetch = requiredMember("CloudCheckFetchV2") {
            it.findMethod {
                matcher {
                    paramCount = 6
                    paramTypes(
                        Context::class.java,
                        findClass("com.miui.packageInstaller.CallingPackage"),
                        Int::class.javaPrimitiveType,
                        findClass("com.miui.packageInstaller.model.ApkInfo"),
                        HashMap::class.java,
                        null
                    )
                    returnType(Object::class.java)
                    usingStrings("sourceAppName", "localVersionCode")
                }
            }.single()
        }
        return true
    }

    override fun init() {
        val params = findClass("com.miui.packageInstaller.model.CloudParams")
        val success = findClass("com.miui.packageInstaller.model.CloudResult\$Success")
        val paramsConstructor = params.getDeclaredConstructor().apply { isAccessible = true }
        val successConstructor = success.getDeclaredConstructor(params).apply { isAccessible = true }
        hookMethod(fetch, object : IMethodHook {
            override fun before(param: HookParam) {
                // Preserve the installer's successful result type and let its
                // normal downstream state machine handle this empty response.
                try {
                    val emptyParams = paramsConstructor.newInstance()
                    // CloudParams defaults both flags to true; retain neither
                    // pre-install nor post-install ads when the cloud payload is skipped.
                    params.getField("showAdsBefore").set(emptyParams, false)
                    params.getField("showAdsAfter").set(emptyParams, false)
                    param.result = successConstructor.newInstance(emptyParams)
                } catch (t: Throwable) {
                    // Leave the original cloud request in place if the response
                    // model changes on a future installer build.
                    XposedLog.e(
                        TAG,
                        "com.miui.packageinstaller",
                        "Could not create an empty CloudResult.Success",
                        t
                    )
                }
            }
        })
    }
}
