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
package com.sevtinge.hyperceiler.hooker.home;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Miui.isPad;
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Hardware.getDeviceName;

import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

import fan.preference.DropDownPreference;
import fan.preference.SeekBarPreferenceCompat;

public class HomeFolderSettings extends DashboardFragment {

    DropDownPreference mFolderShade;
    SeekBarPreferenceCompat mFolderShadeLevel;

    SwitchPreference mUnlockFolderBlurSupport;
    SwitchPreference mRecommendAppsSwitch;
    SwitchPreference mSmallFolderIconBackgroundCustom1;
    SwitchPreference mSmallFolderIconBackgroundCustom2;
    SwitchPreference mSmallFolderIconBackgroundCustom3;

    @Override
    public int getPreferenceScreenResId() {
        return R.xml.home_folder;
    }

    @Override
    public void initPrefs() {
        mFolderShade = findPreference("prefs_key_home_folder_shade");
        mFolderShadeLevel = findPreference("prefs_key_home_folder_shade_level");
        mUnlockFolderBlurSupport = findPreference("prefs_key_home_folder_unlock_blur_supported");
        mRecommendAppsSwitch = findPreference("prefs_key_home_folder_recommend_apps_switch");

        if (isPad()) {
            mSmallFolderIconBackgroundCustom1 = findPreference("prefs_key_home_big_folder_icon_bg_2x1");
            mSmallFolderIconBackgroundCustom2 = findPreference("prefs_key_home_big_folder_icon_bg_1x2");
            mSmallFolderIconBackgroundCustom3 = findPreference("prefs_key_home_big_folder_icon_bg");

            mSmallFolderIconBackgroundCustom1.setTitle(R.string.home_big_folder_icon_bg_2x1_n);
            mSmallFolderIconBackgroundCustom2.setTitle(R.string.home_big_folder_icon_bg_1x2_n);
            mSmallFolderIconBackgroundCustom3.setTitle(R.string.home_big_folder_icon_bg_n);
        }
        setFolderShadeLevelEnable(getFolderShadeMode());
        if (isK90Device()) {
            mFolderShade.setVisible(false);
            mFolderShadeLevel.setVisible(false);
            mRecommendAppsSwitch.setVisible(false);
        }

        mFolderShade.setOnPreferenceChangeListener((preference, o) -> {
            setFolderShadeLevelEnable(Integer.parseInt((String) o));
            return true;
        });
    }

    @Override
    public boolean isPreferenceAvailableForSearch(String key) {
        if (isK90Device() && ("prefs_key_home_folder_shade".equals(key)
            || "prefs_key_home_folder_shade_level".equals(key)
            || "prefs_key_home_folder_recommend_apps_switch".equals(key))) {
            return false;
        }
        if ("prefs_key_home_folder_shade_level".equals(key)) {
            return getFolderShadeMode() != 0;
        }
        return true;
    }

    private static int getFolderShadeMode() {
        Object value = PrefsBridge.getAll().get("prefs_key_home_folder_shade");
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean isK90Device() {
        String device = getDeviceName();
        return "annibale".equals(device) || "myron".equals(device);
    }

    private void setFolderShadeLevelEnable(int i) {
        boolean isEnable = i != 0 && !isK90Device();
        mFolderShadeLevel.setVisible(isEnable);
        mFolderShadeLevel.setEnabled(isEnable);
    }
}
