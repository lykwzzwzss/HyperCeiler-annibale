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
import com.sevtinge.hyperceiler.common.utils.api.ProjectApi.isDebug
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getIntField
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectField
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook

object NoAccessDeviceLogsRequest : BaseHook() {
    private lateinit var mActivityManagerInternal: Any
    private lateinit var mLogcatManagerService: Any

    override fun init() {
        loadClass("com.android.server.logcat.LogcatManagerService", systemParam.classLoader).apply {
            findMethod {
                name("onStart")
            }.createAfterHook {
                try {
                    mLogcatManagerService = it.thisObject
                    mActivityManagerInternal =
                        mLogcatManagerService.getObjectField("mActivityManagerInternal")!!
                } catch (t: Throwable) {
                    XposedLog.e(TAG, packageName, "NoAccessDeviceLogsRequest -> onStart", t)
                }
            }

            findMethod {
                name("processNewLogAccessRequest")
            }.createBeforeHook {
                try {
                    val client = it.args[0]
                    if (client == null || !::mActivityManagerInternal.isInitialized) return@createBeforeHook
                    val uid = client.getIntField("mUid")
                    val packageName =
                        client.getObjectField("mPackageName") as String
                    /*
                     * "关闭访问设备日志确认" 只应替系统自己/ADB 省掉弹窗。以前的实现对所有请求都
                     * 调 onAccessApprovedForClient，等于让任意应用静默读 logcat；这里改为只批准
                     * root/system/shell，其余请求走拒绝路径。
                     */
                    if (uid <= 1000 || uid == 2000) {
                        mLogcatManagerService.callMethod("onAccessApprovedForClient", client)
                    } else {
                        mLogcatManagerService.callMethod("declineRequest", client)
                    }

                    // debug 用，取消禁用详细日志输出可进行调试
                    if (isDebug()) {
                        XposedLog.d(
                            TAG,
                            this@NoAccessDeviceLogsRequest.packageName,
                            "NoAccessDeviceLogsRequest bypass for package=$packageName uid=$uid"
                        )
                    }
                    it.result = null
                } catch (t: Throwable) {
                    // 输出异常日志
                    XposedLog.e(TAG, this@NoAccessDeviceLogsRequest.packageName, "processNewLogAccessRequest failed", t)
                }
            }
        }

        // 米客原来的取消方法，未知情况封堵失败
        /*try {
            findClass("com.android.server.logcat.LogcatManagerService").findAllMethods { filter { name == "onLogAccessRequested" } }.createHooks {
                before { param ->
                    XposedHelpers.callMethod(param.thisObject, "declineRequest", param.args[0])
                    param.result = null
                }
            }
        } catch (t: Throwable) {
            logE(TAG, packageName, t)
        }*/
    }
}
