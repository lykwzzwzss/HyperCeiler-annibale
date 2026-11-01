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

import com.sevtinge.hyperceiler.common.utils.FaceIslandContract;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandController;

/**
 * 人脸解锁岛的设置读取入口。
 *
 * <p>补丁原实现从自己的 {@code super_island_settings} 读取；移植后统一走 HyperCeiler
 * 设置，键名与 {@code system_ui_lock_screen.xml} 保持一致。</p>
 */
public final class FaceIslandPrefs {

    public static final String SUCCESS_GLYPH_MODE_SMILE = "smile";
    public static final String SUCCESS_GLYPH_MODE_CHECK = "check";

    private FaceIslandPrefs() {
    }

    public static boolean moduleEnabled() {
        return booleanPref(FaceIslandContract.KEY_ENABLED, false);
    }

    public static int widthPercent() {
        return clamp(intPref(FaceIslandContract.KEY_WIDTH_PERCENT, 100), 80, 140);
    }

    public static int iconEdgeOffsetDp() {
        return clamp(intPref(FaceIslandContract.KEY_ICON_EDGE_OFFSET_DP, 0), -8, 8);
    }

    public static long desktopExitHoldMs() {
        return clamp(intPref(FaceIslandContract.KEY_DESKTOP_EXIT_HOLD_MS, 800), 0, 5000);
    }

    public static boolean hideAfterFingerprintUnlock() {
        return booleanPref(FaceIslandContract.KEY_FINGERPRINT_HIDDEN, false);
    }

    public static String successGlyphMode() {
        return SUCCESS_GLYPH_MODE_CHECK.equals(
            stringPref(FaceIslandContract.KEY_SUCCESS_GLYPH_MODE, SUCCESS_GLYPH_MODE_SMILE))
            ? SUCCESS_GLYPH_MODE_CHECK
            : SUCCESS_GLYPH_MODE_SMILE;
    }

    public static boolean isCheckGlyphMode() {
        return SUCCESS_GLYPH_MODE_CHECK.equals(successGlyphMode());
    }

    /**
     * 设置页预览正在播放时，模板桥需要按宽度比例缩放调试矩形。
     * 直接读控制器的真实预览状态，避免恒为 false 让缩放变成死代码。
     */
    public static boolean isDebugPreviewActive() {
        return FaceIslandController.isPreviewActive();
    }

    private static int intPref(String key, int fallback) {
        try {
            return PrefsBridge.getInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean booleanPref(String key, boolean fallback) {
        try {
            return PrefsBridge.getBoolean(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String stringPref(String key, String fallback) {
        try {
            String value = PrefsBridge.getString(key, fallback);
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
