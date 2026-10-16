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
package com.sevtinge.hyperceiler.libhook.rules.thememanager

import android.content.ComponentName
import android.content.Intent
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook

/**
 * 将铃声选择里的「本地音乐」改为文件管理器的目录浏览。
 *
 * 默认的「本地音乐」入口是 `com.android.fileexplorer.activity.PickAudioActivity`,它是
 * `FileCategoryActivity` 的 activity-alias,落地后按 `MediaStore.Audio` 查询,呈现为一张
 * 全库扁平的音频清单,没有目录结构,文件一多就极难定位。
 *
 * `com.android.fileexplorer.activity.FileActivity` 自身已声明支持
 * `android.intent.action.RINGTONE_PICKER`(见其 `isPickMode`),并且
 * `FileOperationManager.getIntentByRingtonePicker` 会回填标准的
 * `android.intent.extra.ringtone.PICKED_URI`,所以只需把「本地音乐」这一个条目
 * 的 component 换掉,即可得到目录浏览式的选择器,且返回协议保持兼容。
 *
 * 落点说明:实际的启动发生在 `ThirdPartyPickersFragment.onPreferenceTreeClick`,
 * 它把每个条目的 Intent(存在 Preference 的 Intent 字段里)交给
 * `startActivityForResult`。注意同名但少一个 `s` 的 `ThirdPartyPickerFragment`
 * 是另一个未被实例化的类,不要挂错。
 *
 * 判据取 component 类名而非标题文本:标题会随语言变化,类名稳定;同时只命中
 * `PickAudioActivity`,不影响同列表里的「录音机」与文件管理器条目。
 */
object RingtonePickerUseFileBrowser : BaseHook() {

    private const val PICKER_FRAGMENT = "com.android.thememanager.activity.ThirdPartyPickersFragment"

    private const val TARGET_PICKER = "com.android.fileexplorer.activity.PickAudioActivity"
    private const val FILE_BROWSER = "com.android.fileexplorer.activity.FileActivity"
    private const val FILE_EXPLORER_PKG = "com.android.fileexplorer"

    override fun init() {
        runCatching {
            loadClass(PICKER_FRAGMENT).findMethod {
                name("onPreferenceTreeClick")
                paramCount(1)
            }.createHook {
                before { param ->
                    val preference = param.args[0] ?: return@before
                    val intent = findIntentField(preference) ?: return@before
                    val component = intent.component ?: return@before
                    if (component.packageName != FILE_EXPLORER_PKG) return@before
                    if (component.className != TARGET_PICKER) return@before

                    // 保留调用方原有的 action/extras 与 flag,只替换落地组件。
                    intent.component = ComponentName(FILE_EXPLORER_PKG, FILE_BROWSER)
                }
            }
        }.onFailure {
            XposedLog.w(TAG, "hook $PICKER_FRAGMENT failed: ${it.message}")
        }
    }

    /**
     * 读取 Preference 持有的 Intent。
     *
     * 这里刻意用字段反射而不是 `Preference.getIntent()`:目标 APK 经过 R8 处理,
     * androidx 的 getter 已改名为 `a9()`,按原名调用会抛 `NoSuchMethodError`。
     * 沿类层次找第一个 Intent 类型字段即可,无需依赖任何混淆后的名字。
     */
    private fun findIntentField(preference: Any): Intent? {
        var clazz: Class<*>? = preference.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (field.type != Intent::class.java) continue
                runCatching {
                    field.isAccessible = true
                    return field.get(preference) as? Intent
                }.onFailure {
                    XposedLog.w(TAG, "read intent field ${field.name} failed: ${it.message}")
                }
            }
            clazz = clazz.superclass
        }
        return null
    }
}