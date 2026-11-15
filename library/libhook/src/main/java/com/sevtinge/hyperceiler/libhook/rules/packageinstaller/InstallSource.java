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

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.TextView;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import java.lang.reflect.Method;

public class InstallSource extends BaseHook {

    private static final String PREF_KEY = "miui_package_installer_install_source";
    private static final String APP_INFO = "com.miui.packageInstaller.ui.listcomponets.";

    @Override
    public void init() {
        String packageName = PrefsBridge.getString(PREF_KEY, "").trim();
        if (packageName.isEmpty()) return;

        Class<?> row = findClassIfExists(APP_INFO + "g", getClassLoader());
        Class<?> holder = findClassIfExists(APP_INFO + "AppInfoViewObject$ViewHolder", getClassLoader());
        Method bind = row == null || holder == null ? null : findMethodExactIfExists(row, "I", holder);
        if (bind == null || bind.getDeclaringClass() != row
            || findMethodExactIfExists(holder, "getTvInstallSource") == null) {
            XposedLog.w(TAG, getPackageName(), "Install source display is unsupported on this installer");
            return;
        }

        // HyperOS checks the real calling package and UID again on the progress page.
        hookMethod(bind, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                if (param.getHasThrowable()) return;
                Object viewHolder = param.arg(0);
                if (viewHolder == null) return;
                if (!(callMethod(viewHolder, "getTvInstallSource") instanceof TextView source)) return;
                if (source.getVisibility() != View.VISIBLE || source.getText().length() == 0) return;
                Context context = source.getContext();
                int format = context.getResources().getIdentifier("dialog_install_source", "string",
                    "com.miui.packageinstaller");
                if (format == 0) return;

                PackageManager pm = context.getPackageManager();
                CharSequence label;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
                } catch (PackageManager.NameNotFoundException ignored) {
                    return;
                }
                String previous = source.getText().toString();
                String replacement = context.getString(format, label);
                source.setText(replacement);
                if (getObjectField(viewHolder, "itemView") instanceof View item
                    && item.getContentDescription() != null) {
                    String description = item.getContentDescription().toString();
                    if (description.endsWith(previous)) {
                        item.setContentDescription(
                            description.substring(0, description.length() - previous.length()) + replacement);
                    }
                }
            }
        });
    }
}
