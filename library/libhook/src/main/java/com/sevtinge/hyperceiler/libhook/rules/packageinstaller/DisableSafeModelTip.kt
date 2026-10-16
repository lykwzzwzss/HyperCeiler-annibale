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

import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object DisableSafeModelTip : BaseHook() {
    override fun useDexKit() = true
    private lateinit var personalizedAdGetter: Method
    private lateinit var visibilityGetter: Method
    private lateinit var tipClass: Class<*>

    override fun initDexKit(): Boolean {
        // Several methods now mention MiuiSettings$Ad. Select only its boolean getter,
        // not setters or unrelated reflection bridges.
        personalizedAdGetter = requiredMember("PersonalizedAdGetterV2") {
            it.findMethod {
                matcher {
                    usingEqStrings($$"android.provider.MiuiSettings$Ad", "isPersonalizedAdEnabled")
                    paramCount = 0
                    returnType = "boolean"
                }
            }.single()
        }
        tipClass = findClass("com.miui.packageInstaller.ui.listcomponets.SafeModeTipViewObject")
        visibilityGetter = requiredMember("TipVisibilityGetterV2") {
            it.findClass {
                matcher { className = tipClass.superclass!!.name }
            }.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "boolean"
                    modifiers(AccessFlagsMatcher.create(Modifier.PUBLIC))
                    addUsingField { type = "boolean" }
                }
            }.single()
        }
        return true
    }

    override fun init() {
        hookMethod(personalizedAdGetter, object : IMethodHook {
            override fun before(param: HookParam) { param.result = false }
        })
        // The shared base now owns multiple boolean fields. Override its visibility
        // getter for this tip only; never change every installer row's state.
        hookMethod(visibilityGetter, object : IMethodHook {
            override fun before(param: HookParam) {
                if (tipClass.isInstance(param.thisObject)) param.result = false
            }
        })
    }
}
