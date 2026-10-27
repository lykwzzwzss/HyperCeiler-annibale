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
import android.os.Handler;
import android.os.Looper;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/** Adapts the bundled controller to its child class loader without changing the dex. */
final class FaceIslandRuntimeHooks {
    private static Object controller;
    private static XposedInterface.HookHandle exitHandle;
    private static XposedInterface.HookHandle dismissHandle;

    static void install(ClassLoader loader) throws Exception {
        Class<?> type = Class.forName("com.android.systemui.faceisland.FaceIslandController", true, loader);
        Class<?> bridge = Class.forName("miui.systemui.dynamicisland.faceisland.FaceIslandExitBridge", true, loader);
        Method begin = bridge.getMethod("begin", Context.class, Object.class, Object.class, long.class, long.class);
        bridge.getMethod("cancel", long.class);
        Object instance = field(type, "INSTANCE").get(null);
        // Validate the complete adapter contract before installing anything.
        for (String name : new String[]{"state", "publishedReal", "data", "context", "nextExitToken",
                "exitBridge", "exitToken", "main", "heartbeat", "showing", "asleep", "mediator"}) field(type, name);
        field(field(type, "state").get(instance).getClass(), "active");
        method(type, "cleanup", String.class);
        Method cancel = method(type, "cancelSleepExit");
        Method target = method(type, "beginSleepExit");
        Method syncSystem = method(type, "syncSystem", long.class);
        controller = instance;
        exitHandle = BaseHook.hookMethod(target, new IMethodHook() {
            @Override public void before(HookParam param) {
                // Always replace the original lookup, which uses the parent plugin loader.
                param.setResult(null);
                Object self = param.getThisObject();
                try {
                    Object state = get(self, "state");
                    Object real = get(self, "publishedReal");
                    Object data = get(self, "data");
                    Object context = get(self, "context");
                    if (!field(state.getClass(), "active").getBoolean(state)
                            || real == null || data == null || context == null) return;
                    cancel.invoke(self);
                    long token = field(type, "nextExitToken").getLong(self) + 1;
                    field(type, "nextExitToken").setLong(self, token);
                    if (!Boolean.TRUE.equals(begin.invoke(null, context, real, data, token, 250L))) return;
                    field(type, "exitBridge").set(self, bridge);
                    field(type, "exitToken").setLong(self, token);
                    ((Handler) get(self, "main")).postDelayed(() -> {
                        try {
                            if (field(type, "exitToken").getLong(self) == token) cancel.invoke(self);
                        } catch (Throwable t) { XposedLog.w("FaceIslandExit", "cancel failed", t); }
                    }, 450L);
                } catch (Throwable t) { XposedLog.w("FaceIslandExit", "begin failed", t); }
            }
        });
        if (exitHandle == null) throw new IllegalStateException("Exit adapter hook failed");
        dismissHandle = BaseHook.hookMethod(syncSystem, new IMethodHook() {
            @Override public void after(HookParam param) {
                Object self = param.getThisObject();
                try {
                    // mHiding is set only once dismissal is committed, before the desktop
                    // transition. Face acceptance or an unfinished swipe does not set it.
                    // Run before updatePresentation, bypassing the dex's closeAtMs hold.
                    if (field(type, "asleep").getBoolean(self)) return;
                    Object mediator = get(self, "mediator");
                    boolean hiding = mediator != null
                        && field(mediator.getClass(), "mHiding").getBoolean(mediator);
                    if (field(type, "showing").getBoolean(self) && !hiding) return;
                    Object state = get(self, "state");
                    if (field(state.getClass(), "active").getBoolean(state)
                            || get(self, "data") != null) {
                        method(type, "cleanup", String.class).invoke(self, "KEYGUARD_DISMISSED");
                    }
                } catch (Throwable t) {
                    XposedLog.w("FaceIslandExit", "dismiss cleanup failed", t);
                }
            }
        });
        if (dismissHandle == null) throw new IllegalStateException("Dismiss adapter hook failed");
    }

    static void retire() {
        Object old = controller;
        controller = null;
        if (dismissHandle != null) {
            dismissHandle.unhook();
            dismissHandle = null;
        }
        if (exitHandle != null) {
            exitHandle.unhook();
            exitHandle = null;
        }
        if (old == null) return;
        Runnable cleanup = () -> {
            try {
                Handler handler = (Handler) get(old, "main");
                handler.removeCallbacks((Runnable) get(old, "heartbeat"));
                method(old.getClass(), "cleanup", String.class).invoke(old, "PLUGIN_RELOAD");
            } catch (Throwable t) { XposedLog.w("FaceIslandExit", "retire failed", t); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
        else new Handler(Looper.getMainLooper()).post(cleanup);
    }

    private static Object get(Object owner, String name) throws Exception {
        return field(owner.getClass(), name).get(owner);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
}
