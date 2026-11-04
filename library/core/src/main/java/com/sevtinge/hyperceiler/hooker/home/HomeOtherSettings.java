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
import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreHyperOSVersion;

import androidx.preference.SwitchPreference;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

public class HomeOtherSettings extends DashboardFragment {

    private static final java.util.Set<String> OS4_UNAVAILABLE = java.util.Set.of(
        "prefs_key_home_widget_all_widget_animation",
        "prefs_key_home_widget_show_miui_widget",
        "prefs_key_home_widget_allow_moved_to_minus_one_screen",
        "prefs_key_home_other_freeform_shortcut_menu",
        "prefs_key_home_other_tasks_shortcut_menu"
    );

    @Override
    public boolean isPreferenceAvailableForSearch(String key) {
        return !(isMoreHyperOSVersion(4f) && !isPad() && OS4_UNAVAILABLE.contains(key))
            && super.isPreferenceAvailableForSearch(key);
    }

    SwitchPreference mMoveToMinusOneScreen;
    SwitchPreference mWindowedMode;
    SwitchPreference mShareAPK;
    SwitchPreference mEnableMoreSettings;
    SwitchPreference mHideReportText;
    SwitchPreference mDisablePreLoad;


    @Override
    public int getPreferenceScreenResId() {
        if (isMoreHyperOSVersion(3f)) {
            return R.xml.home_other_new;
        }
        return R.xml.home_other;
    }

    @Override
    public void initPrefs() {
        if (isMoreHyperOSVersion(3f)) {
            mMoveToMinusOneScreen = findPreference("prefs_key_home_widget_allow_moved_to_minus_one_screen");
            if (isPad()) setFuncHint(mMoveToMinusOneScreen, 1);
        }

        mWindowedMode = findPreference("prefs_key_home_other_freeform_shortcut_menu");
        mShareAPK = findPreference("prefs_key_home_other_allow_share_apk");
        mHideReportText = findPreference("prefs_key_home_title_hide_report_text");
        mDisablePreLoad = findPreference("prefs_key_home_other_disable_prestart");

        if (isPad()) {
            setFuncHint(mWindowedMode, 2);
            setFuncHint(mShareAPK, 1);
            setFuncHint(mHideReportText, 1);
            setFuncHint(mDisablePreLoad, 1);
        }

        mEnableMoreSettings = findPreference("prefs_key_home_other_mi_pad_enable_more_setting");
        mEnableMoreSettings.setVisible(isPad() && !isMoreHyperOSVersion(3f));
        if (isMoreHyperOSVersion(4f) && !isPad()) {
            for (String key : OS4_UNAVAILABLE) {
                setFuncHint(findPreference(key), 1);
            }
        }
    }

}
