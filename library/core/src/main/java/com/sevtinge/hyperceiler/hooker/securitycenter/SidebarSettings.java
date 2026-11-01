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
package com.sevtinge.hyperceiler.hooker.securitycenter;

import static com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Hardware.getDeviceName;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.dashboard.DashboardFragment;

import androidx.preference.Preference;

public class SidebarSettings extends DashboardFragment {
    private static final String KEY_DISABLE_SUGGEST = "prefs_key_disable_security_center_sidebar_show_suggest";
    @Override
    public int getPreferenceScreenResId() {
        return R.xml.security_center_sidebar;
    }

    @Override
    public void initPrefs() {
        Preference disableSuggest = findPreference(KEY_DISABLE_SUGGEST);
        if (isK90Device()) disableSuggest.setVisible(false);
    }

    @Override
    public boolean isPreferenceAvailableForSearch(String key) {
        return !KEY_DISABLE_SUGGEST.equals(key) || !isK90Device();
    }

    private static boolean isK90Device() {
        String device = getDeviceName();
        return "annibale".equals(device) || "myron".equals(device);
    }
}
