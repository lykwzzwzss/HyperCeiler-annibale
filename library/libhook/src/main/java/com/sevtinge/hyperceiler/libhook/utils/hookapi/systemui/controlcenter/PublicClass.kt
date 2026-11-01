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
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter

import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull

object PublicClass {

    /** 美元符号常量（避免字面量被工具链/插值破坏）。 */
    private val D: String = 36.toChar().toString()

    // OS3
    val hyperProgressSeekBar by lazy {
        loadClassOrNull("miuix.miuixbasewidget.widget.HyperProgressSeekBar")
    }
    val clzConstraintSetClass by lazy {
        loadClassOrNull("androidx.constraintlayout.widget.ConstraintSet")
    }
    val miuiIslandMediaControllerImpl by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaControllerImpl") ?:
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController")
    }
    val miuiIslandMediaViewHolder by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder")
    }
    val miuiIslandMediaViewBinderImpl by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinderImpl") ?:
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewBinder")
    }
    val playerIslandConstraintLayout by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediaisland.PlayerIslandConstraintLayout")
    }
    val mediaData by lazy {
        loadClassOrNull("com.android.systemui.media.controls.shared.model.MediaData")
    }
    val miuiMediaNotificationControllerImpl by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl") ?:
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationController")
    }
    val mediaViewHolderNew by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder")
    }
    val miuiMediaViewControllerImpl by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl") ?:
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewController")
    }

    // Android 16
    // 注意：下方类名含美元符号，必须用 36.toChar() 拼接 —— 裸写美元加标识符会被
    // Kotlin 当作字符串插值（曾因此拼出错类名，常量长期为 null，插值静默破坏）。
    val seekBarObserverNew by lazy {
        loadClassOrNull(
            "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl" +
                D + "seekBarObserver" + D + "1"
        ) ?: loadClassOrNull(
            "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaSeekBarProgressOwner" +
                D + "progressObserver" + D + "1"
        )
    }

    /** progressObserver 的宿主类（其 this 引用即宿主，holder 需从宿主取）。 */
    val seekBarProgressOwner by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaSeekBarProgressOwner")
    }

    // Android 15
    val miuiMediaControlPanel by lazy {
        loadClassOrNull("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaControlPanel")
    }

    val seekBarObserver by lazy {
        loadClassOrNull("com.android.systemui.media.controls.ui.binder.SeekBarObserver")
    }

    val playerTwoCircleView by lazy {
        loadClassOrNull("com.miui.systemui.notification.media.PlayerTwoCircleView")
    }
}
