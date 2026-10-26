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
package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland;

import android.content.Context;
import android.view.View;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Method;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 动态岛插件侧补丁接入点。补丁 dex 的 TemplateBridge 负责把“人脸解锁岛”的 View
 * 嵌入 MIUI 模板工厂；这里只需在模板工厂的四个入口上做委托。
 */
public final class FaceIslandPluginHooks {
    private static final String TAG = "FaceIslandPluginHooks";
    private static boolean sInstalled;

    private FaceIslandPluginHooks() {
    }

    public static synchronized void install(ClassLoader loader) {
        if (sInstalled || loader == null) {
            return;
        }
        try {
            Class<?> factory = Class.forName(
                "miui.systemui.dynamicisland.template.IslandTemplateFactory", true, loader);
            hook(factory, "createBigIslandTemplate", 7, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object view = FaceIslandBridge.createTemplateView(
                        (Context) param.getArgs()[0], param.getArgs()[1], param.getArgs()[3],
                        (Boolean) param.getArgs()[4], false);
                    android.util.Log.i(TAG, "create big view=" + view);
                    android.util.Log.i(TAG, "create small view=" + view);
                    if (view != null) {
                        param.setResult(view);
                    }
                }
            });
            hook(factory, "createSmallIslandTemplate", 7, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object view = FaceIslandBridge.createTemplateView(
                        (Context) param.getArgs()[0], param.getArgs()[1], param.getArgs()[3],
                        (Boolean) param.getArgs()[4], true);
                    if (view != null) {
                        param.setResult(view);
                    }
                }
            });
            hook(factory, "hideView", 2, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (FaceIslandBridge.setVisible(param.getArgs()[0], false, (Boolean) param.getArgs()[1])) {
                        param.setResult(null);
                    }
                }
            });
            hook(factory, "showView", 2, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (FaceIslandBridge.setVisible(param.getArgs()[0], true, (Boolean) param.getArgs()[1])) {
                        param.setResult(null);
                    }
                }
            });
            hook(factory, "removeTemplate", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (FaceIslandBridge.remove((String) param.getArgs()[0])) {
                        param.setResult(null);
                    }
                }
            });
            hook(factory, "updateCutoutWidth", 3, widthHook(2));
            hook(factory, "updateLeftWidth", 3, widthHook(0));
            hook(factory, "updateRightWidth", 3, widthHook(1));

            Class<?> window = Class.forName(
                "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController", true, loader);
            hook(window, "setCancelTimeout", 2, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (FaceIslandBridge.isManaged(param.getArgs()[0])) {
                        param.setResult(null);
                    }
                }
            });
            hook(window, "getIslandTemplate", 1, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    Object template = FaceIslandBridge.createTemplate(param.getArgs()[0], param.getThisObject());
                    if (template != null) {
                        param.setResult(template);
                    }
                }
            });
            hook(window, "removeDynamicIslandView", 2, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    FaceIslandBridge.notifyRemoved((String) param.getArgs()[0], "PLUGIN_REMOVE");
                }
            });

            Class<?> content = Class.forName(
                "miui.systemui.dynamicisland.window.content.DynamicIslandContentView", true, loader);
            hook(content, "onIslandClick", 0, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (FaceIslandBridge.onIslandClick(param.getThisObject())) {
                        param.setResult(null);
                    }
                }
            });
            sInstalled = true;
            android.util.Log.i(TAG, "plugin hooks installed");
            XposedLog.i(TAG, "plugin hooks installed");
        } catch (Throwable t) {
            android.util.Log.e(TAG, "install plugin hooks failed", t);
            XposedLog.e(TAG, "install plugin hooks failed", t);
        }
    }

    private static IMethodHook widthHook(int slot) {
        return new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if (FaceIslandBridge.updateWidth(
                    param.getArgs()[0], (Integer) param.getArgs()[1], (Boolean) param.getArgs()[2], slot)) {
                    param.setResult(null);
                }
            }
        };
    }

    private static void hook(Class<?> clazz, String name, int count, IMethodHook callback) {
        if (callback == null) {
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count) {
                BaseHook.hookMethod(method, callback);
                return;
            }
        }
        XposedLog.w(TAG, "method not found: " + clazz.getName() + "#" + name + "/" + count);
    }
}
