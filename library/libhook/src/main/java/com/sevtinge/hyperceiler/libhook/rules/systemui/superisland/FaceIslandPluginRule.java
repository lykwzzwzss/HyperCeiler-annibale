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
    private static final int STEP_TEMPLATE_FACTORY = 0;
    private static final int STEP_WINDOW_CONTROLLER = 1;
    private static final int STEP_BASE_CONTENT = 2;
    private static final int STEP_CONTENT = 3;
    private static final int STEP_COUNT = 4;
    private static final Map<ClassLoader, InstallState> INSTALLED =
        Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void init() {
        // 由 NewPluginHelperKt 在动态岛插件加载后调用 install(loader) 安装。
    }

    /** 热重载清理：允许新 generation 重新安装插件侧 hook。 */
    public static void reset() {
        INSTALLED.clear();
    }

    /**
     * 分批安装，已成功的部分不会重装；中途失败后下一次调用只补没装完的部分。
     */
    public static synchronized boolean install(ClassLoader loader) {
        if (loader == null || !FaceIslandHooks.isEnabled()) {
            return false;
        }
        InstallState state = INSTALLED.get(loader);
        if (state == null) {
            state = new InstallState();
            INSTALLED.put(loader, state);
        }
        if (state.done) {
            return true;
        }
        boolean success = installStep(state, loader, STEP_TEMPLATE_FACTORY, "template_factory",
            FaceIslandPluginRule::hookTemplateFactory);
        success &= installStep(state, loader, STEP_WINDOW_CONTROLLER, "window_controller",
            FaceIslandPluginRule::hookWindowController);
        success &= installStep(state, loader, STEP_BASE_CONTENT, "base_content",
            FaceIslandPluginRule::hookBaseContent);
        success &= installStep(state, loader, STEP_CONTENT, "content",
            FaceIslandPluginRule::hookContent);
        if (success && !state.done) {
            state.done = true;
            XposedLog.i("FaceIslandPlugin", "plugin hooks installed");
        }
        return success;
    }

    private static boolean installStep(InstallState state, ClassLoader loader, int step, String name,
                                       HookStep action) {
        if (state.installed[step]) {
            return true;
        }
        try {
            action.install(loader);
            state.installed[step] = true;
            return true;
        } catch (Throwable error) {
            XposedLog.w("FaceIslandPlugin", "plugin hooks failed at " + name + ": " + error);
            return false;
        }
    }

    private interface HookStep {
        void install(ClassLoader loader) throws Throwable;
    }

    private static final class InstallState {
        final boolean[] installed = new boolean[STEP_COUNT];
        boolean done;
    }

    private static void hookTemplateFactory(ClassLoader loader) {
        Class<?> factory = find("miui.systemui.dynamicisland.template.IslandTemplateFactory", loader);
        if (factory == null) {
            return;
        }
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
        if (window == null) {
            return;
        }
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
        if (content == null) {
            return;
        }
        hook(content, "getBigIslandViewWidth", 0, scaleResultHook(false));
        hook(content, "getBigIslandViewWidthHasSmallIsland", 0, scaleResultHook(false));
        hook(content, "getCurrentBigIslandWidth", 1, scaleResultHook(false));
        hook(content, "getBigIslandX", 0, scaleXHook());
        hook(content, "getBigIslandXHasSmallIsland", 0, scaleXHook());
        hook(content, "getCurrentBigIslandX", 1, scaleXHook());
    }

    private static void hookContent(ClassLoader loader) {
        Class<?> content = find("miui.systemui.dynamicisland.window.content.DynamicIslandContentView", loader);
        if (content == null) {
            return;
        }
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
