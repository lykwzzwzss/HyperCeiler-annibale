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

package com.sevtinge.hyperceiler.libhook.rules.guardprovider;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.api.RootCheckResult;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.io.File;
import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 阻止 guardprovider 将设备判定为已 root。
 *
 * DexKit 通过两个日志字符串定位 root 管理应用检测方法，不绑定混淆名称。
 * K90 / OS4 / guardprovider 3.1.4-20260902.0 的 oe2.C 返回 boolean，
 * 作者此前观察到的 oe2.x 返回包名 String。分别返回 false / null，
 * 避免把 null 作为 boolean 方法的结果。未知返回类型不安装此 hook。
 *
 * su 文件兜底仅在 guardprovider 进程内处理两个明确路径。
 * 设置项：guard_provider_disable_root_check。
 */
public class DisableRootedCheck extends BaseHook {

    /** su 文件检测依次探测的路径。 */
    private static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su"};

    /** 定位到的 root 管理应用检测方法。 */
    private Method mRootManagerCheckMethod;

    @Override
    protected boolean useDexKit() {
        return true;
    }

    @Override
    protected boolean initDexKit() {
        try {
            // 按两个日志字符串共同定位，兼容混淆名称及返回类型变化。
            mRootManagerCheckMethod = requiredMember("RootManagerCheck", bridge -> bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                    .usingStrings("root manager found: ", "getApplicationInfo failed: ")
                )).singleOrNull());
        } catch (Throwable t) {
            // 定位失败不应中断下面的兜底方案，故吞掉异常继续。
            XposedLog.w(TAG, getPackageName(), "root manager check not located, fallback only: " + t);
        }
        return true;
    }

    @Override
    public void init() {
        // 1) root 管理应用检测：让它认为一个都没找到（本机真正的判定来源）
        if (mRootManagerCheckMethod != null && RootCheckResult.supports(mRootManagerCheckMethod.getReturnType())) {
            XposedLog.d(TAG, getPackageName(), "hooking root manager check: " + mRootManagerCheckMethod);
            hookMethod(mRootManagerCheckMethod, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    // K90 3.1.4 返回 boolean，旧版返回包名 String。
                    param.setResult(RootCheckResult.notRooted(mRootManagerCheckMethod.getReturnType()));
                }
            });
        } else if (mRootManagerCheckMethod != null) {
            XposedLog.w(TAG, getPackageName(), "unsupported root check return type: " + mRootManagerCheckMethod.getReturnType());
        }

        // 2) su 文件检测：对两个 su 路径一律报告「不存在」
        findAndHookMethod(File.class, "exists", new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object self = param.getThisObject();
                if (!(self instanceof File)) return;
                // 用 getPath() 而非 getAbsolutePath()：检测方构造的就是绝对路径字面量
                String path = ((File) self).getPath();
                for (String su : SU_PATHS) {
                    if (su.equals(path)) {
                        param.setResult(false);
                        return;
                    }
                }
            }
        });

        XposedLog.d(TAG, getPackageName(), "disable root check installed");
    }
}
