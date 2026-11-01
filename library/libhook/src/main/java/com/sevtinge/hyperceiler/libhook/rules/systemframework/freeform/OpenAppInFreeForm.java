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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.freeform;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import java.util.ArrayList;
import java.util.List;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class OpenAppInFreeForm extends BaseHook {

    Class<?> mActivityStarter;

    @Override
    public void init() {
        if (PrefsBridge.getBoolean("system_framework_freeform_jump")) {
            mActivityStarter = findClassIfExists("com.android.server.wm.ActivityStarter");
            Class<?> safeActivityOptions = findClassIfExists("com.android.server.wm.SafeActivityOptions");
            Class<?> requestClass = findClassIfExists("com.android.server.wm.ActivityStarter$Request");
            if (mActivityStarter == null || safeActivityOptions == null
                || requestClass == null
                || findMethodExactIfExists(mActivityStarter, "executeRequest", requestClass) == null) {
                return;
            }
            hookAllMethods(mActivityStarter, "executeRequest", new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object request = param.getArgs()[0];
                    Intent intent = (Intent) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(request, "intent");
                    Object safeOptions = com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(request, "activityOptions");
                    ActivityOptions callerOptions = safeOptions == null ? null
                        : (ActivityOptions) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(
                            safeOptions, "mOriginalOptions");
                    if (callerOptions != null
                        && com.sevtinge.hyperceiler.libhook.base.BaseHook.getIntField(
                            callerOptions, "mLaunchWindowingMode") == 5) {
                        return;
                    }
                    String callingPackage = (String) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(request, "callingPackage");
                    ActivityInfo resolvedActivity = (ActivityInfo) com.sevtinge.hyperceiler.libhook.base.BaseHook
                        .getObjectField(request, "activityInfo");
                    String targetPackage = intent != null && intent.getComponent() != null
                        ? intent.getComponent().getPackageName()
                        : resolvedActivity == null ? null : resolvedActivity.packageName;
                    String targetClass = intent != null && intent.getComponent() != null
                        ? intent.getComponent().getClassName()
                        : resolvedActivity == null ? null : resolvedActivity.name;
                    boolean openInFw = shouldOpenInFreeForm(intent, callingPackage, targetPackage, targetClass);

//                Bundle ao = safeOptions != null ? (Bundle) com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(safeOptions, "getActivityOptionsBundle") : null;
//                String reason = (String) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(request, "reason");
//                Helpers.log("startAct: " + callingPackage
//                    + " reason| " + reason
//                    + " intent| " + intent
//                    + " openInFw| " + openInFw
//                    + " activityOptions| " + Helpers.stringifyBundle(ao)
//                    + " intentExtra| " + Helpers.stringifyBundle(intent.getExtras())
//                );

                    if (openInFw) {
                        Context mContext = (Context) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(param.getThisObject(), "mService"), "mContext");
                        Class<?> multiWindowUtils = findClassIfExists("android.util.MiuiMultiWindowUtils");
                        String packageName = targetPackage;
                        ActivityOptions existingOptions = safeOptions == null ? null
                            : (ActivityOptions) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(
                                safeOptions, "mOriginalOptions");
                        ActivityOptions options = existingOptions == null
                            ? (ActivityOptions) com.sevtinge.hyperceiler.libhook.base.BaseHook.callStaticMethod(
                                multiWindowUtils, "getActivityOptions", mContext, packageName, true, false)
                            : existingOptions;
                        options = StickyFloatingWindows.patchActivityOptions(
                            mContext, options, packageName, multiWindowUtils);
                        if (safeOptions != null) {
                            com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(
                                safeOptions, "mOriginalOptions", options);
                        } else {
                            Object replacementSafeOptions = com.sevtinge.hyperceiler.libhook.base.BaseHook.newInstance(
                                safeActivityOptions,
                                options,
                                com.sevtinge.hyperceiler.libhook.base.BaseHook.getIntField(request, "callingPid"),
                                com.sevtinge.hyperceiler.libhook.base.BaseHook.getIntField(request, "callingUid")
                            );
                            com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(
                                request, "activityOptions", replacementSafeOptions);
                        }
                    }
                }
            });
        }
    }

    private boolean shouldOpenInFreeForm(
        Intent intent, String callingPackage, String pkgName, String className) {
        if (intent == null || pkgName == null) {
            return false;
        }
        final List<String> fwBlackList = new ArrayList<>();
        fwBlackList.add("com.miui.home");
        fwBlackList.add("com.android.camera");
        fwBlackList.add("com.android.systemui");
        if (fwBlackList.contains(pkgName)) {
            return false;
        }
        boolean openInFw = false;
        final boolean openFwWhenShare = PrefsBridge.getBoolean("system_framework_freeform_app_share");
        if (openFwWhenShare) {
            if ("com.miui.screenshot".equals(callingPackage)) {
                return false;
            }
            /*if (PrefsBridge.getStringSet("system_fw_forcein_actionsend_apps").contains(pkgName)) return false;*/
            if ("com.miui.packageinstaller".equals(pkgName) && className != null && className.contains("com.miui.packageInstaller.NewPackageInstallerActivity")) {
                return true;
            }
            if (Intent.ACTION_SEND.equals(intent.getAction()) && !pkgName.equals(callingPackage)) {
                openInFw = true;
            } else if ("com.tencent.mm".equals(pkgName) && className != null && className.contains(".plugin.base.stub.WXEntryActivity")) {
                openInFw = true;
            }
        }
        return openInFw;
    }
}
