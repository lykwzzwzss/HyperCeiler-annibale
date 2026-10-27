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

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.ContextUtils
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import java.util.WeakHashMap

/**
 * 把铃声选择里的「本地音乐」换成可选的目录式选择器。
 *
 * 默认的「本地音乐」入口是 `com.android.fileexplorer.activity.PickAudioActivity`,它是
 * `FileCategoryActivity` 的 activity-alias,落地后按 `MediaStore.Audio` 查询,呈现为一张
 * 全库扁平的音频清单,没有目录结构,文件一多就极难定位。
 *
 * 本规则接管 `ThirdPartyPickersFragment.onPreferenceTreeClick` 里那一条的组件,提供三种模式:
 *
 * - `0` 关闭:不改写,保持系统原样;
 * - `1` 文件管理器:`com.android.fileexplorer.activity.FileActivity` 自身声明支持
 *   `RINGTONE_PICKER`(见其 `isPickMode`),`FileOperationManager.getIntentByRingtonePicker`
 *   会回填标准的 `android.intent.extra.ringtone.PICKED_URI`,只换 component 即可,协议天然兼容;
 * - `2` 系统文件选择器:`com.google.android.documentsui`。它不声明 `RINGTONE_PICKER`,
 *   必须改成 `ACTION_GET_CONTENT` 加音频类型过滤,结果也只放在 `data` 里,所以额外在
 *   `onActivityResult` 里把 `data` 补写成 `PICKED_URI`,否则调用方拿到空结果等于没选。
 *
 * 落点说明:实际的启动发生在 `ThirdPartyPickersFragment.onPreferenceTreeClick`,
 * 它把每个条目的 Intent(存在 Preference 的 Intent 字段里)交给 `startActivityForResult`。
 * 注意同名但少一个 `s` 的 `ThirdPartyPickerFragment` 是另一个未被实例化的类,不要挂错。
 *
 * 判据取 component 类名而非标题文本:标题会随语言变化,类名稳定;同时只命中
 * `PickAudioActivity`,不影响同列表里的「录音机」与文件管理器条目。
 */
object RingtonePickerUseFileBrowser : BaseHook() {

    private const val PICKER_FRAGMENT = "com.android.thememanager.activity.ThirdPartyPickersFragment"

    private const val TARGET_PICKER = "com.android.fileexplorer.activity.PickAudioActivity"
    private const val FILE_BROWSER = "com.android.fileexplorer.activity.FileActivity"
    private const val FILE_EXPLORER_PKG = "com.android.fileexplorer"

    private const val DOCUMENTS_UI_PKG = "com.google.android.documentsui"

    private const val PREF_KEY = "prefs_key_theme_manager_ringtone_picker_mode"
    private const val LEGACY_KEY = "prefs_key_theme_manager_ringtone_use_file_browser"
    private const val MODE_OFF = 0
    private const val MODE_FILE_BROWSER = 1
    private const val MODE_DOCUMENTS_UI = 2

    private const val EXTRA_PICKED_URI = "android.intent.extra.ringtone.PICKED_URI"

    /**
     * 条目原始 Intent 的快照。
     *
     * Preference 持有的 Intent 会被反复点击复用,改写后无法再靠 component 认出「本地音乐」,
     * 因此首次见到时留底,之后每次点击都从快照重建:既保证重复点击幂等,也保证运行中
     * 切换模式不会残留上一次的 action/package。
     */
    private val sOriginals = WeakHashMap<Intent, Original>()

    /** 是否刚把「本地音乐」交给文档界面;只有这种情况才需要补写结果。 */
    @Volatile
    private var sDocumentsUiLaunched = false

    private data class Original(
        val action: String?,
        val component: ComponentName?,
        val type: String?,
        val packageName: String?
    )

    fun isEnabled(): Boolean = mode() != MODE_OFF

    override fun init() {
        runCatching {
            val pickers = loadClass(PICKER_FRAGMENT)
            pickers.findMethod {
                name("onPreferenceTreeClick")
                paramCount(1)
            }.createHook {
                before { param ->
                    val preference = param.args[0] ?: return@before
                    val intent = findIntentField(preference) ?: return@before
                    onEntryClicked(intent)
                }
            }
            // 文档界面的结果放在 data 里,而调用方只读 PICKED_URI。这段必须在原方法把
            // 结果交给 setResult 之前跑完:finish() 会把 Intent 就地发出,事后补写无效。
            pickers.findMethod {
                name("onActivityResult")
                paramCount(3)
            }.createHook {
                before { param ->
                    if (!sDocumentsUiLaunched) return@before
                    sDocumentsUiLaunched = false
                    val resultCode = param.args[1] as? Int
                    val result = param.args[2] as? Intent
                    if (resultCode != Activity.RESULT_OK || result == null) return@before
                    if (result.hasExtra(EXTRA_PICKED_URI)) return@before
                    result.putExtra(EXTRA_PICKED_URI, result.data ?: return@before)
                }
            }
        }.onFailure {
            XposedLog.w(TAG, "hook $PICKER_FRAGMENT failed: ${it.message}")
        }
    }

    private fun onEntryClicked(intent: Intent) {
        val original = originalOf(intent)
        val component = original.component
        if (component == null || component.packageName != FILE_EXPLORER_PKG) {
            sDocumentsUiLaunched = false
            return
        }
        if (component.className != TARGET_PICKER) {
            sDocumentsUiLaunched = false
            return
        }

        // 先还原成条目本来的样子,再按当前模式改写,避免多次点击互相叠加。
        intent.action = original.action
        intent.component = component
        intent.type = original.type
        intent.setPackage(original.packageName)

        when (mode()) {
            MODE_FILE_BROWSER -> intent.component = ComponentName(FILE_EXPLORER_PKG, FILE_BROWSER)
            MODE_DOCUMENTS_UI -> applyDocumentsUi(intent)
            else -> Unit
        }
    }

    private fun applyDocumentsUi(intent: Intent) {
        if (!documentsUiAvailable()) {
            // 没装文档界面时退回文件管理器,总比点了弹「无法打开」好。
            XposedLog.w(TAG, "$DOCUMENTS_UI_PKG is unavailable, fall back to $FILE_BROWSER")
            intent.component = ComponentName(FILE_EXPLORER_PKG, FILE_BROWSER)
            return
        }
        // 原来的 RINGTONE_PICKER 不是文档界面声明的 action,必须换成 GET_CONTENT;
        // 保留 setPackage 让系统只在这一个应用内解析,不弹「打开方式」。
        intent.component = null
        intent.action = Intent.ACTION_GET_CONTENT
        intent.type = "audio/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setPackage(DOCUMENTS_UI_PKG)
        sDocumentsUiLaunched = true
    }

    private fun documentsUiAvailable(): Boolean {
        val context = ContextUtils.getContextNoError(ContextUtils.FLAG_CURRENT_APP) ?: return true
        val probe = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            setPackage(DOCUMENTS_UI_PKG)
        }
        return runCatching {
            context.packageManager.resolveActivity(probe, 0) != null
        }.getOrDefault(true)
    }

    private fun originalOf(intent: Intent): Original = sOriginals.getOrPut(intent) {
        Original(intent.action, intent.component, intent.type, intent.`package`)
    }

    /**
     * 读取当前模式。
     *
     * 这个功能早期是布尔开关,存在旧 key 下。旧值不迁移到新 key:类型不同的值留在同一个
     * key 里会让 ListPreference 读取时抛 ClassCastException,直接把设置页带崩。这里只做
     * 只读兼容——旧 key 为 true 时按「文件管理器」处理,不让已经启用过的用户静默失效。
     */
    private fun mode(): Int {
        val current = PrefsBridge.getInt(PREF_KEY, -1)
        if (current >= 0) return current
        val legacy = runCatching { PrefsBridge.getBoolean(LEGACY_KEY, false) }.getOrDefault(false)
        return if (legacy) MODE_FILE_BROWSER else MODE_OFF
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
