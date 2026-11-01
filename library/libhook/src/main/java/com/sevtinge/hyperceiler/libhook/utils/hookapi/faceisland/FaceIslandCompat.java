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
package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland;

import android.os.Build;

import com.sevtinge.hyperceiler.common.utils.FaceIslandContract;

import java.util.Locale;

/**
 * 人脸解锁岛的宿主常量与设备判断。
 */
public final class FaceIslandCompat {

    public static final String SYSTEM_UI_PACKAGE = FaceIslandContract.SYSTEM_UI_PACKAGE;
    public static final String SYSTEM_INTERFACE_PACKAGE = FaceIslandContract.SYSTEM_INTERFACE_PACKAGE;

    /**
     * 补丁适配的 REDMI K90 系列机型代号。
     * <ul>
     *   <li>{@code annibale} — REDMI K90（2510DRK44C），补丁原始目标机</li>
     * </ul>
     */
    private static final String[] SUPPORTED_DEVICES = {"annibale"};

    /** 未列出具体代号时按前缀匹配的 K90 系列（如 k90 / k90pro）。 */
    private static final String[] SUPPORTED_DEVICE_PREFIXES = {"k90"};

    private FaceIslandCompat() {
    }

    /** 不在支持列表的机型直接不注册功能。 */
    public static boolean isSupportedDevice() {
        String device = Build.DEVICE == null ? "" : Build.DEVICE.toLowerCase(Locale.ROOT);
        if (device.isEmpty()) {
            return false;
        }
        for (String supported : SUPPORTED_DEVICES) {
            if (supported.equals(device)) {
                return true;
            }
        }
        for (String prefix : SUPPORTED_DEVICE_PREFIXES) {
            if (device.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
