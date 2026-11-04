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
package com.sevtinge.hyperceiler.libhook.rules.systemui.superisland;

import android.content.Context;
import android.view.ViewGroup;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHooks;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin.FaceIslandTemplateBridge;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedInterface;
import java.util.WeakHashMap;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 动态岛插件侧：把超级岛模板插进 MIUI 的模板工厂、窗口控制器和内容视图。
 * 由 {@code NewPluginHelperKt} 在 dynamic island 插件加载后调用 {@link #install(ClassLoader)}。
 */
public class FaceIslandPluginRule extends FaceIslandRuleBase {

    private static final int PART_LEFT = 0;
    private static final int PART_RIGHT = 1;
    private static final int PART_CUTOUT = 2;
    public static final String LOADER_STATE_KEY = "FaceIsland.pluginLoader";
    private static final Map<ClassLoader, List<XposedInterface.HookHandle>> INSTALLED =
        new WeakHashMap<>();
    private static final ThreadLocal<List<XposedInterface.HookHandle>> INSTALLING = new ThreadLocal<>();

    @Override
    public void init() {}

    public static synchronized void reset() {
        FaceIslandHooks.setPluginReady(false);
        for (List<XposedInterface.HookHandle> handles : INSTALLED.values()) unhook(handles);
        INSTALLED.clear();
    }

    /** Validate the complete contract before changing any plugin behavior. */
    public static synchronized boolean install(ClassLoader loader) {
        if (loader == null || !FaceIslandHooks.isEnabled()) return false;
        if (INSTALLED.containsKey(loader)) {
            FaceIslandHooks.setPluginReady(true);
            return true;
        }
        FaceIslandHooks.setPluginReady(false);
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            validate(loader, "miui.systemui.dynamicisland.template.IslandTemplateFactory",
                "createBigIslandTemplate/7", "createSmallIslandTemplate/7", "showView/2", "hideView/2",
                "updateLeftWidth/3", "updateRightWidth/3", "updateCutoutWidth/3", "removeTemplate/1");
            validate(loader, "miui.systemui.dynamicisland.window.DynamicIslandWindowViewController",
                "removeDynamicIslandView/2", "getBigIslandRect/2", "getExpandedIslandRect/1",
                "getIslandTemplate/1", "setCancelTimeout/2");
            validate(loader, "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView",
                "getBigIslandViewWidth/0", "getBigIslandViewWidthHasSmallIsland/0",
                "getCurrentBigIslandWidth/1", "getBigIslandX/0", "getBigIslandXHasSmallIsland/0",
                "getCurrentBigIslandX/1");
            validate(loader, "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
                "onIslandClick/0", "onInterceptTouchEvent/1");
            INSTALLING.set(handles);
            hookTemplateFactory(loader);
            hookWindowController(loader);
            hookBaseContent(loader);
            hookContent(loader);
            for (List<XposedInterface.HookHandle> old : INSTALLED.values()) unhook(old);
            INSTALLED.clear();
            INSTALLED.put(loader, handles);
            putHotReloadRuntimeState(LOADER_STATE_KEY, loader);
            FaceIslandHooks.setPluginReady(true);
            XposedLog.i("FaceIslandPlugin", "plugin hooks installed: " + handles.size());
            return true;
        } catch (Throwable error) {
            unhook(handles);
            FaceIslandHooks.setPluginReady(false);
            XposedLog.e("FaceIslandPlugin", "plugin unavailable; native face display retained", error);
            return false;
        } finally {
            INSTALLING.remove();
        }
    }

    private static void unhook(List<XposedInterface.HookHandle> handles) {
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); }
            catch (Throwable error) { XposedLog.w("FaceIslandPlugin", "unhook failed", error); }
        }
    }

    private static Method requireMethod(Class<?> type, String name, int count) {
        if (type == null) throw new IllegalStateException("Missing plugin class for " + name);
        Method found = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count
                    && !method.isBridge() && !method.isSynthetic()) {
                if (found != null) throw new IllegalStateException("Ambiguous plugin method: " + name);
                found = method;
            }
        }
        if (found == null) throw new IllegalStateException(type.getName() + "#" + name + "/" + count);
        return found;
    }

    private static void validate(ClassLoader loader, String className, String... signatures) {
        Class<?> type = find(className, loader);
        for (String signature : signatures) {
            int split = signature.lastIndexOf('/');
            requireMethod(type, signature.substring(0, split), Integer.parseInt(signature.substring(split + 1)));
        }
    }

    static Method hook(Class<?> type, String name, int count, IMethodHook callback) {
        Method method = requireMethod(type, name, count);
        XposedInterface.HookHandle handle = hookMethod(method, callback);
        if (handle == null) throw new IllegalStateException("Hook installation failed: " + method);
        INSTALLING.get().add(handle);
        return method;
    }

    private static void hookTemplateFactory(ClassLoader loader) {
        Class<?> factory = find("miui.systemui.dynamicisland.template.IslandTemplateFactory", loader);
        if (factory == null) throw new IllegalStateException("Template factory missing");
        hook(factory, "createBigIslandTemplate", 7, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object view = createTemplateView(param, false);
                if (view != null) {
                    param.setResult(view);
                }
            }
        });
        hook(factory, "createSmallIslandTemplate", 7, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object view = createTemplateView(param, true);
                if (view != null) {
                    param.setResult(view);
                }
            }
        });
        hook(factory, "showView", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = argsOf(param);
                FaceIslandTemplateBridge.setVisible(args[0], true, bool(args, 1, false));
            }
        });
        hook(factory, "hideView", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = argsOf(param);
                FaceIslandTemplateBridge.setVisible(args[0], false, bool(args, 1, false));
            }
        });
        hook(factory, "updateLeftWidth", 3, widthHook(PART_LEFT));
        hook(factory, "updateRightWidth", 3, widthHook(PART_RIGHT));
        hook(factory, "updateCutoutWidth", 3, widthHook(PART_CUTOUT));
        hook(factory, "removeTemplate", 1, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                FaceIslandTemplateBridge.remove(String.valueOf(param.getArgs()[0]));
            }
        });
    }

    private static void hookWindowController(ClassLoader loader) {
        Class<?> window = find("miui.systemui.dynamicisland.window.DynamicIslandWindowViewController", loader);
        if (window == null) throw new IllegalStateException("Window controller missing");
        hook(window, "removeDynamicIslandView", 2, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                FaceIslandTemplateBridge.notifyRemoved(String.valueOf(param.getArgs()[0]), "PLUGIN_REMOVE");
            }
        });
        hook(window, "getBigIslandRect", 2, scaleResultHook(true));
        hook(window, "getExpandedIslandRect", 1, scaleResultHook(true));
        hook(window, "getIslandTemplate", 1, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object template = FaceIslandTemplateBridge.createTemplate(
                    param.getArgs()[0], param.getThisObject());
                if (template != null) {
                    param.setResult(template);
                }
            }
        });
        hook(window, "setCancelTimeout", 2, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if (FaceIslandTemplateBridge.cancelManagedTimeout(
                    param.getThisObject(), param.getArgs()[0])) {
                    param.setResult(null);
                }
            }
        });
    }

    private static void hookBaseContent(ClassLoader loader) {
        Class<?> content = find(
            "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView", loader);
        if (content == null) throw new IllegalStateException("Plugin content missing");
        hook(content, "getBigIslandViewWidth", 0, scaleResultHook(false));
        hook(content, "getBigIslandViewWidthHasSmallIsland", 0, scaleResultHook(false));
        hook(content, "getCurrentBigIslandWidth", 1, scaleResultHook(false));
        hook(content, "getBigIslandX", 0, scaleXHook());
        hook(content, "getBigIslandXHasSmallIsland", 0, scaleXHook());
        hook(content, "getCurrentBigIslandX", 1, scaleXHook());
    }

    private static void hookContent(ClassLoader loader) {
        Class<?> content = find("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", loader);
        if (content == null) throw new IllegalStateException("Plugin content missing");
        hook(content, "onIslandClick", 0, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                if (FaceIslandTemplateBridge.onIslandClick(param.getThisObject())) {
                    param.setResult(null);
                }
            }
        });
        hook(content, "onInterceptTouchEvent", 1, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                Object result = FaceIslandTemplateBridge.onInterceptTouchEvent(param.getThisObject());
                if (result != null) {
                    param.setResult(result);
                }
            }
        });
    }

    private static Object createTemplateView(HookParam param, boolean small) {
        try {
            Object[] args = argsOf(param);
            if (args.length < 5 || !(args[0] instanceof Context) || !(args[3] instanceof ViewGroup)) {
                return null;
            }
            return FaceIslandTemplateBridge.create(
                (Context) args[0], args[1], (ViewGroup) args[3], bool(args, 4, false), small);
        } catch (Throwable error) {
            return null;
        }
    }

    private static IMethodHook widthHook(final int part) {
        return new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object[] args = argsOf(param);
                FaceIslandTemplateBridge.updateWidth(args[0], number(args, 1, 0),
                    bool(args, 2, false), part);
            }
        };
    }

    private static IMethodHook scaleResultHook(final boolean rect) {
        return new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object scaled = rect
                    ? FaceIslandTemplateBridge.scaleDebugIslandRect(param.getResult())
                    : FaceIslandTemplateBridge.scaleManagedIslandWidth(
                    param.getThisObject(), param.getResult());
                if (scaled != param.getResult()) {
                    param.setResult(scaled);
                }
            }
        };
    }

    private static IMethodHook scaleXHook() {
        return new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object scaled = FaceIslandTemplateBridge.scaleManagedIslandX(
                    param.getThisObject(), param.getResult());
                if (scaled != param.getResult()) {
                    param.setResult(scaled);
                }
            }
        };
    }
}
