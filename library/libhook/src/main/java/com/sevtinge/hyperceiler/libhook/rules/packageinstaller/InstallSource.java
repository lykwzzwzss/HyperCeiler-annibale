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
package com.sevtinge.hyperceiler.libhook.rules.packageinstaller;

import android.app.Activity;
import android.text.TextUtils;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Method;

public class InstallSource extends BaseHook {

    private static final String PREF_KEY = "miui_package_installer_install_source";
    private static final String INSTALL_START = "com.miui.packageInstaller.InstallStart";
    private static final String CALLING_PACKAGE_HELPER = "y2.d$a";
    private static final String LEGACY_CALLING_PACKAGE_HELPER = "com.miui.packageInstaller.L";

    String mInstallSourcePackageName;

    @Override
    public void init() {
        // 默认不修改安装来源：只有用户显式填写包名时才安装 Hook
        mInstallSourcePackageName = PrefsBridge.getString(PREF_KEY, "");
        if (TextUtils.isEmpty(mInstallSourcePackageName)
            || TextUtils.isEmpty(mInstallSourcePackageName.trim())) {
            XposedLog.d(TAG, getPackageName(), "Install source is empty, keep system value");
            return;
        }
        mInstallSourcePackageName = mInstallSourcePackageName.trim();

        Class<?> installStart = findClassIfExists(INSTALL_START, getClassLoader());
        if (installStart == null) {
            XposedLog.e(TAG, getPackageName(), "Class not found: " + INSTALL_START);
            return;
        }

        // 只 hook 安装器自己的安装入口，不再全局替换 Activity.getLaunchedFromPackage。
        // 1. 部分版本 InstallStart 自己声明了 getCallingPackage
        Method getCallingPackage = findMethodExactIfExists(installStart, "getCallingPackage");
        if (getCallingPackage != null && getCallingPackage.getDeclaringClass() == installStart) {
            hookMethod(getCallingPackage, returnConstant(mInstallSourcePackageName));
        }

        // 2. 当前版本 InstallStart.onCreate 通过 y2.d$a#b(Activity) 解析调用方
        Class<?> callingPackageHelper = findClassIfExists(CALLING_PACKAGE_HELPER, getClassLoader());
        if (callingPackageHelper != null) {
            findAndHookMethod(callingPackageHelper, "b", Activity.class,
                returnConstant(mInstallSourcePackageName));
        } else {
            XposedLog.w(TAG, getPackageName(), "Class not found: " + CALLING_PACKAGE_HELPER);
        }

        // 3. 低版本走 com.miui.packageInstaller.L#a(InstallStart)
        Class<?> legacyHelper = findClassIfExists(LEGACY_CALLING_PACKAGE_HELPER, getClassLoader());
        if (legacyHelper != null) {
            findAndHookMethod(legacyHelper, "a", installStart,
                returnConstant(mInstallSourcePackageName));
        }
    }
}
