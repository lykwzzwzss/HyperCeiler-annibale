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
package com.sevtinge.hyperceiler.common.utils;

/**
 * 锁屏人脸解锁岛在设置页与 SystemUI 进程之间共享的契约。
 */
public final class FaceIslandContract {

    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    public static final String SYSTEM_INTERFACE_PACKAGE = "miui.systemui.plugin";

    /** 设置页“预览动画”按钮发送，SystemUI 内的接收器收到后播放一次预览。 */
    public static final String ACTION_PREVIEW =
        "com.sevtinge.hyperceiler.module.action.FaceIslandPreview";

    public static final String KEY_ENABLED = "system_ui_face_island_unlock";
    public static final String KEY_WIDTH_PERCENT = "system_ui_face_island_width_percent";
    public static final String KEY_ICON_EDGE_OFFSET_DP = "system_ui_face_island_icon_edge_offset_dp";
    public static final String KEY_DESKTOP_EXIT_HOLD_MS = "system_ui_face_island_desktop_exit_hold_ms";
    public static final String KEY_FINGERPRINT_HIDDEN = "system_ui_face_island_fingerprint_hidden";
    public static final String KEY_SUCCESS_GLYPH_MODE = "system_ui_face_island_success_glyph_mode";
    public static final String KEY_PREVIEW = "system_ui_face_island_preview";

    private FaceIslandContract() {
    }
}
