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
package com.sevtinge.hyperceiler.libhook.rules.securitycenter

import android.content.Context
import android.content.DialogInterface
import android.widget.Button
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/**
 * 移除链式启动确认弹窗。
 *
 * 只处理 `com.miui.wakepath.ui.ConfirmStartActivity`：该 Activity 专门显示
 * "%1$s 想要打开 %2$s，是否允许？" 弹窗，positive 按钮文案为 button_text_once_accept。
 * 直接点击该弹窗的 positive 按钮，不再全局 hook TextView.setText，
 * 避免误点安全中心其它 "允许/接受" 按钮。
 */
class RemoveOpenAppConfirmationPopup : BaseHook() {
    override fun init() {
        val activityClass = findClassIfExists(CONFIRM_START_ACTIVITY)
        if (activityClass == null) {
            XposedLog.e(TAG, packageName, "Class not found: $CONFIRM_START_ACTIVITY, skip")
            return
        }

        runCatching {
            activityClass.findMethod {
                name("onDialogCreated")
            }.createHook {
                after {
                    if (it.args.size != 1) return@after
                    val dialog = it.args[0] ?: return@after
                    val positive = runCatching {
                        dialog.callMethod("getButton", DialogInterface.BUTTON_POSITIVE) as? Button
                    }.getOrNull() ?: return@after
                    val acceptText = resolveAcceptText(positive.context) ?: return@after
                    if (positive.text?.toString() != acceptText) return@after
                    positive.performClick()
                }
            }
        }.onFailure {
            XposedLog.e(TAG, packageName,
                "Failed to hook $CONFIRM_START_ACTIVITY#onDialogCreated", it)
        }
    }

    private fun resolveAcceptText(context: Context): String? {
        for (name in ACCEPT_TEXT_RES) {
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            if (id != 0) {
                return context.resources.getString(id)
            }
        }
        return null
    }

    private companion object {
        const val CONFIRM_START_ACTIVITY = "com.miui.wakepath.ui.ConfirmStartActivity"
        val ACCEPT_TEXT_RES = listOf("button_text_once_accept", "button_text_accept")
    }
}
