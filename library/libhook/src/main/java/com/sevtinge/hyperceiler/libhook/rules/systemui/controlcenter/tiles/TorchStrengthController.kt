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

package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.tiles

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Uses the camera HAL's torch current limits instead of model-specific sysfs nodes. */
internal class TorchStrengthController private constructor(
    private val manager: CameraManager,
    private val cameraId: String,
    val maxLevel: Int
) {
    fun setLevel(level: Int) {
        manager.turnOnTorchWithStrengthLevel(cameraId, level.coerceIn(1, maxLevel))
    }

    companion object {
        fun create(context: Context): TorchStrengthController? {
            val manager = context.getSystemService(CameraManager::class.java) ?: return null
            for (id in manager.cameraIdList) {
                // A vendor-only camera may reject characteristics; keep searching the other IDs.
                val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK ||
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                val max = characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                if (max > 1) return TorchStrengthController(manager, id, max)
            }
            return null
        }
    }
}
