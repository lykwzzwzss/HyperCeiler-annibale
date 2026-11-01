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

import android.content.pm.ApplicationInfo
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHooks
import java.lang.reflect.Method

object AllAsSystemApp : BaseHook() {
    private val SYSTEM_APP_METHOD_NAMES = listOf(
        "isSystemApp",
        "isUpdatedSystemApp",
        "isSystemApplication",
        "isUpdatedSystemApplication"
    )

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        if (systemMethod.isEmpty()) {
            XposedLog.e(TAG, packageName, "DexKit anchor not found: isSystemApp(ApplicationInfo)")
        }
        return true
    }

    private val systemMethod by lazy<List<Method>> {
        // 只匹配明确的方法名，避免把分析/埋点等其它 (ApplicationInfo)Z 方法也改成系统应用
        val named = SYSTEM_APP_METHOD_NAMES.flatMap { methodName ->
            optionalMemberList<Method>("AllAsSystemApp_$methodName") {
                it.findMethod {
                    matcher {
                        name = methodName
                        paramTypes = listOf("android.content.pm.ApplicationInfo")
                        returnType = "boolean"
                    }
                }
            }
        }.distinct()
        if (named.isNotEmpty()) {
            return@lazy named
        }

        // R8 混淆后方法名不可依赖：本机 PackageUtil#u(ApplicationInfo)Z 就是
        // isSystemApp 的实现（FLAG_SYSTEM + uid < 10000），只在这一个安装器工具类内兜底
        optionalMemberList<Method>("AllAsSystemAppPackageUtil") {
            it.findMethod {
                matcher {
                    declaredClass = "com.android.packageinstaller.PackageUtil"
                    paramTypes = listOf("android.content.pm.ApplicationInfo")
                    returnType = "boolean"
                }
            }
        }
    }

    override fun init() {
        systemMethod.createHooks {
            before { param ->
                (param.args[0] as ApplicationInfo).flags =
                    (param.args[0] as ApplicationInfo).flags.or(ApplicationInfo.FLAG_SYSTEM)
            }
        }
    }
}
