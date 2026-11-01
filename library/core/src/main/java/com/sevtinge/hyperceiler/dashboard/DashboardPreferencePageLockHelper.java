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
package com.sevtinge.hyperceiler.dashboard;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.prefs.LayoutPreference;

final class DashboardPreferencePageLockHelper {
    private final DashboardFragment mHost;
    private final String mWarningBannerKey;

    private boolean mPageLocked = false;
    private LayoutPreference mDynamicWarningBanner;

    DashboardPreferencePageLockHelper(@NonNull DashboardFragment host, @NonNull String warningBannerKey) {
        mHost = host;
        mWarningBannerKey = warningBannerKey;
    }

    /**
     * 版本不可查（目标包未安装 / 无法读取版本号）时使用。
     * 只置灰整页并显示“未安装 / 无法检测”提示，保留用户已存的配置。
     */
    void greyOutWithoutClearing() {
        String warningKey = ensureWarningPreference(null, R.string.app_version_unavailable_warning_banner);
        if (mPageLocked) return;

        PreferenceScreen screen = mHost.getPreferenceScreen();
        if (screen == null) return;

        greyOutGroup(screen, warningKey);
        mPageLocked = true;
    }

    /**
     * 已确认版本但超出支持区间（或模块被修改）时使用：整页置灰并清理该组配置。
     */
    void lock(@Nullable Preference warningPreference, @Nullable Integer warningTitleResId) {
        String warningKey = ensureWarningPreference(warningPreference, warningTitleResId);
        if (mPageLocked) return;

        PreferenceScreen screen = mHost.getPreferenceScreen();
        if (screen == null) return;

        disableGroup(screen, warningKey);
        mPageLocked = true;
    }

    private String ensureWarningPreference(@Nullable Preference warningPreference, @Nullable Integer warningTitleResId) {
        if (warningPreference != null) {
            warningPreference.setVisible(true);
            warningPreference.setEnabled(false);
            if (warningTitleResId != null) {
                warningPreference.setTitle(warningTitleResId);
            }
            return warningPreference.getKey();
        }

        PreferenceScreen screen = mHost.getPreferenceScreen();
        if (screen == null) return null;

        if (mDynamicWarningBanner == null) {
            mDynamicWarningBanner = new LayoutPreference(mHost.requireContext(), R.layout.headtip_warn);
            mDynamicWarningBanner.setKey(mWarningBannerKey);
            mDynamicWarningBanner.setOrder(Integer.MIN_VALUE);
            mDynamicWarningBanner.setEnabled(false);
        }

        mDynamicWarningBanner.setVisible(true);
        if (warningTitleResId != null) {
            mDynamicWarningBanner.setTitle(warningTitleResId);
        }

        if (mHost.findPreference(mDynamicWarningBanner.getKey()) == null) {
            screen.addPreference(mDynamicWarningBanner);
        }
        return mDynamicWarningBanner.getKey();
    }

    private void disableGroup(@NonNull PreferenceGroup group, @Nullable String warningKey) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference == null) continue;

            if (preference instanceof PreferenceGroup childGroup) {
                disableGroup(childGroup, warningKey);
            }

            String key = preference.getKey();
            boolean isWarningBanner = !TextUtils.isEmpty(warningKey) && TextUtils.equals(warningKey, key);
            if (isWarningBanner) continue;

            if (!TextUtils.isEmpty(key)) {
                mHost.cleanKey(key);
            }
            preference.setEnabled(false);
        }
    }

    /**
     * 版本不可查时整页置灰：与 {@link #disableGroup(PreferenceGroup, String)} 的区别是不清理 key，
     * 用户换回受支持的版本后原配置仍然可用。
     */
    private void greyOutGroup(@NonNull PreferenceGroup group, @Nullable String warningKey) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference == null) continue;

            if (preference instanceof PreferenceGroup childGroup) {
                greyOutGroup(childGroup, warningKey);
            }

            String key = preference.getKey();
            boolean isWarningBanner = !TextUtils.isEmpty(warningKey) && TextUtils.equals(warningKey, key);
            if (isWarningBanner) continue;

            preference.setEnabled(false);
        }
    }
}
