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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.others;

import static com.sevtinge.hyperceiler.libhook.base.BaseHook.deoptimize;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import java.lang.reflect.Method;

public class UseAndroidPackageInstaller extends BaseHook {
    private final ThreadLocal<Boolean> installerCall = new ThreadLocal<>();

    @Override
    public void init() {
        Class<?> PackageManagerServiceImpl = findClassIfExists("com.android.server.pm.PackageManagerServiceImpl");
        if (PackageManagerServiceImpl == null) {
            XposedLog.w(TAG, "find class E com.android.server.pm.PackageManagerServiceImpl");
            return;
        }

        Method isCts = findMethodExactIfExists(PackageManagerServiceImpl, "isCTS");
        if (isCts == null || isCts.getReturnType() != boolean.class) return;
        if (chain(isCts, call -> Boolean.TRUE.equals(installerCall.get()) ? true : call.proceed()) == null) {
            return;
        }

        Method[] methods = PackageManagerServiceImpl.getDeclaredMethods();
        for (Method method : methods) {
            String name = method.getName();
            if ("hookChooseBestActivity".equals(name) || "updateDefaultPkgInstallerLocked".equals(name) || "assertValidApkAndInstaller".equals(name)) {
                chain(method, call -> {
                    boolean nested = Boolean.TRUE.equals(installerCall.get());
                    installerCall.set(true);
                    try {
                        return call.proceed();
                    } finally {
                        if (!nested) installerCall.remove();
                    }
                });

                try {
                    deoptimize(method);
                } catch (Throwable t) {
                    XposedLog.e("UseAndroidPackageInstaller", getPackageName(), t);
                }
            }
        }
    }
}
