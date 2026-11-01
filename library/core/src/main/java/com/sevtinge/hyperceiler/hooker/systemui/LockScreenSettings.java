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
package com.sevtinge.hyperceiler.hooker.systemui;


import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Miui.isPad;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreSmallVersion;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.common.utils.FaceIslandContract;
import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

import fan.preference.DropDownPreference;

public class LockScreenSettings extends DashboardFragment {
    // 人脸解锁岛只适配 REDMI K90（annibale），其他机型隐藏入口但保留已存配置。
    private static final String FACE_ISLAND_SUPPORTED_DEVICE = "annibale";

    SwitchPreference mHideRightButton; // 隐藏右侧按钮
    SwitchPreference mBlurButton; // 锁屏模糊按钮
    SwitchPreference mChangeCV; // 显示充电动画
    SwitchPreference mAnim; // 联动动画
    DropDownPreference mHideLeftButtonNew; // 左侧按钮自定义

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.system_ui_lock_screen;
    }

    @Override
    public boolean isPreferenceAvailableForSearch(String key) {
        if (isPad() && (key.equals("prefs_key_system_ui_lock_screen_bottom_left_button")
                || key.equals("prefs_key_system_ui_lock_screen_hide_camera")
                || key.equals("prefs_key_system_ui_lock_screen_blur_button"))) {
            return false;
        }
        if (isMoreHyperOSVersion(3f)) {
            if (key.equals("prefs_key_system_ui_lock_screen_bottom_left_button")
                    || key.equals("prefs_key_system_ui_lock_screen_hide_camera")) {
                return false;
            }
            if (!isPad() && (key.equals("prefs_key_system_ui_lock_screen_show_charging_cv")
                    || key.equals("prefs_key_system_ui_show_charging_c_more")
                    || key.equals("prefs_key_system_ui_show_battery_temperature")
                    || key.equals("prefs_key_system_ui_lock_screen_show_spacing_value")
                    || key.equals("prefs_key_system_ui_lock_screen_show_spacing")
                    || key.equals("prefs_key_system_ui_lock_screen_linkage_anim")
                    || key.equals("prefs_key_system_ui_lock_screen_linkage_anim_on")
                    || key.equals("prefs_key_system_ui_lock_screen_linkage_anim_off"))) {
                return false;
            }
        } else if (isMoreSmallVersion(200, 2f)
                && (key.equals("prefs_key_system_ui_lock_screen_bottom_left_button")
                || key.equals("prefs_key_system_ui_lock_screen_hide_camera"))) {
            return false;
        }
        return super.isPreferenceAvailableForSearch(key);
    }

    @Override
    public void initPrefs() {
        mHideRightButton = findPreference("prefs_key_system_ui_lock_screen_hide_camera");
        mHideLeftButtonNew = findPreference("prefs_key_system_ui_lock_screen_bottom_left_button");
        mBlurButton = findPreference("prefs_key_system_ui_lock_screen_blur_button");
        mChangeCV = findPreference("prefs_key_system_ui_lock_screen_show_charging_cv");
        mAnim = findPreference("prefs_key_system_ui_lock_screen_linkage_anim");

        Preference preview = findPreference("prefs_key_" + FaceIslandContract.KEY_PREVIEW);
        if (preview != null) {
            preview.setOnPreferenceClickListener(preference -> {
                Context context = preference.getContext();
                Intent intent = new Intent(FaceIslandContract.ACTION_PREVIEW)
                    .setPackage(FaceIslandContract.SYSTEM_UI_PACKAGE);
                context.sendBroadcast(intent);
                Toast.makeText(context, R.string.system_ui_face_island_preview_desc,
                    Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        if (isPad()) {
            setFuncHint(mHideLeftButtonNew, 1);
            setFuncHint(mHideRightButton, 1);
            setFuncHint(mBlurButton, 1);
        } else if (isMoreHyperOSVersion(3f)) {
            setPreVisible(mHideLeftButtonNew, false);
            setPreVisible(mHideRightButton, false);
            setFuncHint(mChangeCV, 1);
            setFuncHint(mAnim, 1);
        } else if (isMoreSmallVersion(200, 2f)) {
            setFuncHint(mHideLeftButtonNew, 2);
            setFuncHint(mHideRightButton, 2);
        }

        // 设备门控：仅 K90（annibale）支持人脸解锁岛，其他机型隐藏入口。
        if (!FACE_ISLAND_SUPPORTED_DEVICE.equalsIgnoreCase(Build.DEVICE)) {
            hideFaceIslandPreferences();
        }
    }

    /**
     * 非 K90 机型隐藏人脸解锁岛相关入口；只改可见性，不删除任何 key。
     */
    private void hideFaceIslandPreferences() {
        String[] keys = {
            FaceIslandContract.KEY_ENABLED,
            FaceIslandContract.KEY_WIDTH_PERCENT,
            FaceIslandContract.KEY_ICON_EDGE_OFFSET_DP,
            FaceIslandContract.KEY_DESKTOP_EXIT_HOLD_MS,
            FaceIslandContract.KEY_FINGERPRINT_HIDDEN,
            FaceIslandContract.KEY_SUCCESS_GLYPH_MODE,
            FaceIslandContract.KEY_PREVIEW
        };
        for (String key : keys) {
            Preference preference = findPreference("prefs_key_" + key);
            if (preference == null) continue;
            preference.setVisible(false);
            if (FaceIslandContract.KEY_ENABLED.equals(key)) {
                // 入口注明限制条件，隐藏后仍能被搜索索引读到。
                preference.setSummary(R.string.system_ui_face_island_unlock_desc);
            }
        }
    }
}
