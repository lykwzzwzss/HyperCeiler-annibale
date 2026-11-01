/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter

/**
 * 运营商名称文本变换（纯函数，便于单测）。
 *
 * 参数序依据系统界面 17.03.260226.r 反编译实测签名：
 * `onCarrierTextChanged(ILjava/lang/String;I)V`
 *   args[0] = slotId(Int)、args[1] = carrierText(String?)、args[2] = status(Int)
 */
object CarrierTextTransform {

    /** 从回调参数解析 slotId（实测签名 args[0]）。 */
    fun slotId(args: Array<Any?>): Int = args[0] as Int

    /** 从回调参数解析运营商文本（实测签名 args[1]）。 */
    fun carrierText(args: Array<Any?>): String? = args[1] as? String

    /** 把变换后的文本写回回调参数（实测签名 args[1]）。 */
    fun applyTo(args: Array<Any?>, text: String?) {
        args[1] = text
    }

    /** 对 per-slot 文本数组逐元素做变换（元素下标即 slotId），返回新数组。 */
    fun transformAll(mode: Int, slots: Array<String?>, deviceName: String? = null): Array<String?> =
        Array(slots.size) { i -> transform(mode, arrayOf(i, slots[i]), deviceName) }

    /**
     * 按偏好模式变换运营商文本：
     * 0 = 原样；1 = 去除分隔符；2 = 清空；3 = 卡槽 0 显示设备名、其余清空。
     */
    fun transform(mode: Int, args: Array<Any?>, deviceName: String? = null): String? {
        return when (mode) {
            1 -> carrierText(args)
                ?.replace("  |  ", "")
                ?.replace(" | ", "")

            2 -> ""
            3 -> if (slotId(args) == 0) {
                deviceName
            } else {
                null
            }

            else -> carrierText(args)
        }
    }
}
