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
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.island

import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.LazyClass.NewStrongToast
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook


object HideStrongToast : BaseHook() {
    override fun init() {
        val strongToastClass = NewStrongToast
        if (strongToastClass == null) {
            XposedLog.w(TAG, "MIUIStrongToast not found, skip HideStrongToast")
            return
        }
        runCatching {
            strongToastClass.findMethod { name("onAttachedToWindow") }.createAfterHook {
                val strongToastLayout = it.thisObject as? FrameLayout ?: return@createAfterHook
                // 真正的隐藏靠 visibility，而不是永久取消绘制
                strongToastLayout.visibility = View.GONE
                // 一次性 preDraw：只拦掉当前这一帧，触发后立刻注销监听，
                // 避免把永不返回的 listener 永久留在 ViewTreeObserver 上造成泄漏。
                val observer = strongToastLayout.viewTreeObserver
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (observer.isAlive) {
                            observer.removeOnPreDrawListener(this)
                        }
                        return false
                    }
                }
                observer.addOnPreDrawListener(listener)
            }
        }.onFailure {
            XposedLog.w(TAG, "hide strong toast failed: ${it.message}")
        }
    }
}
