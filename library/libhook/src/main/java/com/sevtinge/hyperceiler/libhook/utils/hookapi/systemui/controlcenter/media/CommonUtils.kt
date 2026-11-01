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
package com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.media

import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.clzConstraintSetClass
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.mediaViewHolderNew
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.miuiIslandMediaViewHolder
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

object ConstraintSetHelper {
    val clear by lazy {
        clzConstraintSetClass!!.findMethod { name("clear"); parameterTypes(Int::class.java, Int::class.java) }
    }
    val setVisibility by lazy {
        clzConstraintSetClass!!.findMethod { name("setVisibility"); parameterTypes(Int::class.java, Int::class.java) }
    }
    val connect by lazy {
        clzConstraintSetClass!!.findMethod { name("connect"); parameterTypes(Int::class.java, Int::class.java, Int::class.java, Int::class.java) }
    }
    val setMargin by lazy {
        clzConstraintSetClass!!.findMethod { name("setMargin"); parameterTypes(Int::class.java, Int::class.java, Int::class.java) }
    }
    val setGoneMargin by lazy {
        clzConstraintSetClass!!.findMethod { name("setGoneMargin"); parameterTypes(Int::class.java, Int::class.java, Int::class.java) }
    }
    val applyTo by lazy {
        // 限定为单参 applyTo(ConstraintLayout)。
        // 注意：不能用 ConstraintLayout::class.java —— 该引用类型来自宿主 APK 的私有库，
        // 在 hook 进程 ClassLoader 中身份不同/不可加载，会导致匹配失败（曾报 Method not found）。
        // primitive 类（如 Int::class.java）是 JVM 全局单例，不受此限。
        val all = clzConstraintSetClass!!.declaredMethods.filter { it.name == "applyTo" }
        (all.firstOrNull { it.parameterTypes.size == 1 } ?: all.firstOrNull())!!
    }
    val clone by lazy {
        // clone 有 4 个重载（(Context,int)/(ConstraintLayout)/(ConstraintSet)/(Constraints)）。
        // 不限定参数时会命中声明序靠前的 clone(Context,int)，调用时报
        // Wrong number of arguments: expected 2, got 1（氛围光 View 布局失败的根因）。
        // 用「参数类型名字含 ConstraintLayout」匹配，以兼容 "androidx...ConstraintLayout" 与
        // 描述符 "Landroidx/.../ConstraintLayout;" 两种命名，且完全不引用宿主类。
        val all = clzConstraintSetClass!!.declaredMethods.filter { it.name == "clone" }
        val oneParam = all.filter { it.parameterTypes.size == 1 }
        (oneParam.firstOrNull { it.parameterTypes[0].name.contains("ConstraintLayout") }
            ?: oneParam.firstOrNull())!!
    }
}

// 向后兼容的顶层访问器，委托到 ConstraintSetHelper
val clear get() = ConstraintSetHelper.clear
val setVisibility get() = ConstraintSetHelper.setVisibility
val connect get() = ConstraintSetHelper.connect
val setMargin get() = ConstraintSetHelper.setMargin
val setGoneMargin get() = ConstraintSetHelper.setGoneMargin
val applyTo get() = ConstraintSetHelper.applyTo
val clone get() = ConstraintSetHelper.clone

private val ncMediaVHFieldCache = ConcurrentHashMap<String, Field>()
private val diMediaVHFieldCache = ConcurrentHashMap<String, Field>()

@Suppress("UNCHECKED_CAST")
fun <T> Any.getMediaViewHolderFieldAs(fieldName: String, isDynamicIsland: Boolean): T? {
    val cache = if (isDynamicIsland) diMediaVHFieldCache else ncMediaVHFieldCache
    val holderClass = if (isDynamicIsland) miuiIslandMediaViewHolder else mediaViewHolderNew
    val field = cache.getOrPut(fieldName) {
        generateSequence(holderClass) { it.superclass }
            .mapNotNull { currentClass ->
                runCatching {
                    currentClass.getDeclaredField(fieldName).apply { isAccessible = true }
                }.getOrNull()
            }
            .firstOrNull() ?: error("Field $fieldName not found in ${holderClass?.name}")
    }
    return field?.get(this) as? T
}
