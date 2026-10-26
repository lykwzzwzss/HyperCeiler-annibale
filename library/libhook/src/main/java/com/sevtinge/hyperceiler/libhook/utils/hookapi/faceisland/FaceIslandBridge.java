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
import android.content.pm.ApplicationInfo;
import android.os.Build;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.InMemoryDexClassLoader;

/**
 * 人脸解锁岛补丁桥。
 *
 * <p>补丁以两个 dex 的形式随模块分发。这里在 miui.systemui.plugin 的 ClassLoader 下加载，
 * 让补丁类既能访问插件资源，又不会和 SystemUI 自带的同名混淆类冲突。所有补丁 API 都通过
 * 反射调用，模块本身不直接依赖补丁类。</p>
 */
public final class FaceIslandBridge {
    private static final String TAG = "FaceIslandBridge";

    private static final String HOOKS_CLASS = "com.android.systemui.faceisland.FaceIslandHooks";
    private static final String PLUGIN_BRIDGE_CLASS =
        "miui.systemui.dynamicisland.faceisland.FaceIslandTemplateBridge";

    private static final Object LOCK = new Object();
    private static final Map<String, Method> METHODS = new HashMap<>();

    private static ClassLoader sLoader;
    private static Class<?> sHooks;
    private static Class<?> sPluginBridge;
    private static boolean sFailed;

    private static WeakReference<Object> sMonitor;
    private static WeakReference<Object> sMediator;
    private static WeakReference<Object> sRepository;
    private static WeakReference<Object> sIsland;

    private FaceIslandBridge() {
    }

    public static boolean isReady() {
        synchronized (LOCK) {
            return sHooks != null;
        }
    }

    /**
     * 在插件 ClassLoader 下加载补丁 dex。重复调用只初始化一次。
     */
    public static boolean ensureLoaded(Context context, ClassLoader pluginLoader) {
        synchronized (LOCK) {
            if (sHooks != null) {
                return true;
            }
            if (sFailed) {
                return false;
            }
            try {
                byte[] systemUi = readResource(context, "faceisland/systemui.dex");
                byte[] plugin = readResource(context, "faceisland/plugin.dex");
                if (systemUi == null || plugin == null) {
                    throw new IOException("faceisland dex resource missing");
                }
                ClassLoader parent = pluginLoader != null
                    ? pluginLoader
                    : FaceIslandBridge.class.getClassLoader();
                InMemoryDexClassLoader loader = new InMemoryDexClassLoader(
                    new ByteBuffer[]{ByteBuffer.wrap(systemUi), ByteBuffer.wrap(plugin)},
                    parent
                );
                sHooks = Class.forName(HOOKS_CLASS, true, loader);
                sPluginBridge = Class.forName(PLUGIN_BRIDGE_CLASS, true, loader);
                sLoader = loader;
                XposedLog.i(TAG, "face island dex loaded, parent=" + parent);
                replayBinds();
                return true;
            } catch (Throwable t) {
                sFailed = true;
                XposedLog.e(TAG, "load face island dex failed", t);
                return false;
            }
        }
    }

    public static ClassLoader getLoader() {
        synchronized (LOCK) {
            return sLoader;
        }
    }

    public static Class<?> getPluginBridgeClass() {
        synchronized (LOCK) {
            return sPluginBridge;
        }
    }

    private static byte[] readResource(Context context, String name) {
        ClassLoader cl = FaceIslandBridge.class.getClassLoader();
        if (cl != null) {
            try (InputStream in = cl.getResourceAsStream(name)) {
                if (in != null) {
                    return readAll(in);
                }
            } catch (Throwable ignored) {
            }
        }
        // 模块资源被 R8/打包规则裁剪时，退回模块 APK 的 assets。
        try {
            android.content.res.Resources resources = context.getPackageManager()
                .getResourcesForApplication(BuildConfig.APP_MODULE_ID);
            try (InputStream in = resources.getAssets().open(name)) {
                return readAll(in);
            }
        } catch (Throwable ignored) {
        }
        try {
            ApplicationInfo info = context.getPackageManager()
                .getApplicationInfo(BuildConfig.APP_MODULE_ID, 0);
            File apk = new File(info.sourceDir);
            try (ZipFile zip = new ZipFile(apk)) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    entry = zip.getEntry("assets/" + name);
                }
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        return readAll(in);
                    }
                }
            }
        } catch (Throwable t) {
            XposedLog.w(TAG, "read module dex fallback failed: " + name, t);
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512 * 1024);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    private static void replayBinds() {
        Object monitor = get(sMonitor);
        Object mediator = get(sMediator);
        Object repository = get(sRepository);
        Object island = get(sIsland);
        if (monitor != null) {
            callHooks("bindMonitor", monitor);
        }
        if (mediator != null) {
            callHooks("bindMediator", mediator);
        }
        if (repository != null) {
            callHooks("bindRepository", repository);
        }
        if (island != null) {
            callHooks("bindIsland", island);
        }
    }

    private static Object get(WeakReference<Object> ref) {
        return ref == null ? null : ref.get();
    }

    // ---------------------------------------------------------------------
    // SystemUI 事件
    // ---------------------------------------------------------------------

    public static void bindMonitor(Object obj) {
        sMonitor = new WeakReference<>(obj);
        callHooks("bindMonitor", obj);
    }

    public static void bindMediator(Object obj) {
        sMediator = new WeakReference<>(obj);
        callHooks("bindMediator", obj);
    }

    public static void bindRepository(Object obj) {
        sRepository = new WeakReference<>(obj);
        callHooks("bindRepository", obj);
    }

    public static void bindIsland(Object obj) {
        sIsland = new WeakReference<>(obj);
        callHooks("bindIsland", obj);
    }

    public static Object wrapFaceCallback(Object callback, int userId) {
        return callHooks("wrapFaceCallback", callback, userId);
    }

    public static void onAuthStopped(Object repository) {
        callHooks("onAuthStopped", repository);
    }

    public static void onDetectionChanged(Object repository) {
        callHooks("onDetectionChanged", repository);
    }

    public static void onFaceProduced(Object result) {
        callHooks("onFaceProduced", result);
    }

    public static Object enterFaceAcceptance(Object status) {
        return callHooks("enterFaceAcceptance", status);
    }

    public static void leaveFaceAcceptance(Object token) {
        callHooks("leaveFaceAcceptance", token);
    }

    public static void onFaceAccepted(Object monitor, int user, boolean strong) {
        callHooks("onFaceAccepted", monitor, user, strong);
    }

    public static void onFingerprintProduced(Object monitor, int user, boolean strong) {
        callHooks("onFingerprintProduced", monitor, user, strong);
    }

    public static Object enterFingerprintPolicy(Object injector, int user, Object source, boolean strong) {
        return callHooks("enterFingerprintPolicy", injector, user, source, strong);
    }

    public static void leaveFingerprintPolicy(Object token, Object injector) {
        callHooks("leaveFingerprintPolicy", token, injector);
    }

    public static void onFingerprintUnlockBranch(Object controller) {
        callHooks("onFingerprintUnlockBranch", controller);
    }

    public static void onCredentialChecked(Object controller, int user, int type) {
        callHooks("onCredentialChecked", controller, user, type);
    }

    public static void onCredentialRejected(Object controller, int user) {
        callHooks("onCredentialRejected", controller, user);
    }

    public static Object beginCredentialFinish(Object container, int user) {
        return callHooks("beginCredentialFinish", container, user);
    }

    public static Object enterCredentialRequest(Object token) {
        return callHooks("enterCredentialRequest", token);
    }

    public static void leaveCredentialRequest(Object token) {
        callHooks("leaveCredentialRequest", token);
    }

    public static void onDoneRequest(Object mediator, int user) {
        callHooks("onDoneRequest", mediator, user);
    }

    public static void onCredentialPending(Object mediator, int user, Object token, Object callback) {
        callHooks("onCredentialPending", mediator, user, token, callback);
    }

    public static void onExitAnimationCreated(Object callback, Object mediator, int user) {
        callHooks("onExitAnimationCreated", callback, mediator, user);
    }

    public static Object enterExitCallback(Object callback) {
        return callHooks("enterExitCallback", callback);
    }

    public static void leaveExitCallback(Object token) {
        callHooks("leaveExitCallback", token);
    }

    public static void onMediatorDone(Object mediator) {
        callHooks("onMediatorDone", mediator);
    }

    public static void onHide(Object mediator) {
        callHooks("onHide", mediator);
    }

    public static void onHideCancelled(Object mediator) {
        callHooks("onHideCancelled", mediator);
    }

    public static void onSleepStarted(Object mediator) {
        callHooks("onSleepStarted", mediator);
    }

    public static void onSleepFinished(Object mediator) {
        callHooks("onSleepFinished", mediator);
    }

    public static void onWakeStarted(Object mediator) {
        callHooks("onWakeStarted", mediator);
    }

    public static void onKeyguardState(Object mediator) {
        callHooks("onKeyguardState", mediator);
    }

    public static void hideNativeFaceIcon(Object view, Object faceView) {
        callHooks("hideNativeFaceIcon", view, faceView);
    }

    // ---------------------------------------------------------------------
    // 插件模板桥
    // ---------------------------------------------------------------------

    public static Object createTemplateView(Context context, Object data, Object parent,
                                             boolean big, boolean fake) {
        return callPlugin("create",
            new Class<?>[]{Context.class, Object.class, Object.class, boolean.class, boolean.class},
            context, data, parent, big, fake);
    }

    public static boolean updateWidth(Object data, int width, boolean fake, int slot) {
        Object result = callPlugin("updateWidth",
            new Class<?>[]{Object.class, int.class, boolean.class, int.class},
            data, width, fake, slot);
        return Boolean.TRUE.equals(result);
    }

    public static boolean setVisible(Object data, boolean visible, boolean fake) {
        Object result = callPlugin("setVisible",
            new Class<?>[]{Object.class, boolean.class, boolean.class},
            data, visible, fake);
        return Boolean.TRUE.equals(result);
    }

    public static boolean remove(String key) {
        Object result = callPlugin("remove", new Class<?>[]{String.class}, key);
        return Boolean.TRUE.equals(result);
    }

    public static void notifyRemoved(String key, String reason) {
        callPlugin("notifyRemoved", new Class<?>[]{String.class, String.class}, key, reason);
    }

    public static Object createTemplate(Object data, Object controller) {
        return callPlugin("createTemplate",
            new Class<?>[]{Object.class, Object.class}, data, controller);
    }

    public static boolean isManaged(Object data) {
        Object result = callPlugin("isManaged", new Class<?>[]{Object.class}, data);
        return Boolean.TRUE.equals(result);
    }

    public static boolean onIslandClick(Object content) {
        Object result = callPlugin("onIslandClick", new Class<?>[]{Object.class}, content);
        return Boolean.TRUE.equals(result);
    }

    // ---------------------------------------------------------------------
    // 反射
    // ---------------------------------------------------------------------

    private static Object callHooks(String name, Object... args) {
        synchronized (LOCK) {
            if (sHooks == null) {
                return null;
            }
            try {
                Method method = findMethod(sHooks, name, args);
                if (method == null) {
                    XposedLog.w(TAG, "FaceIslandHooks." + name + " not found");
                    return null;
                }
                return method.invoke(null, args);
            } catch (Throwable t) {
                XposedLog.w(TAG, "FaceIslandHooks." + name + " failed", t);
                return null;
            }
        }
    }

    private static Object callPlugin(String name, Class<?>[] types, Object... args) {
        synchronized (LOCK) {
            if (sPluginBridge == null) {
                return null;
            }
            try {
                Method method = sPluginBridge.getMethod(name, types);
                return method.invoke(null, args);
            } catch (Throwable t) {
                XposedLog.w(TAG, "FaceIslandTemplateBridge." + name + " failed", t);
                return null;
            }
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Object[] args) {
        StringBuilder key = new StringBuilder(name);
        for (Object arg : args) {
            key.append(',').append(arg == null ? "null" : arg.getClass().getName());
        }
        Method cached = METHODS.get(key.toString());
        if (cached != null) {
            return cached;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < types.length; i++) {
                Object arg = args[i];
                if (arg == null) {
                    if (types[i].isPrimitive()) {
                        match = false;
                        break;
                    }
                } else if (!wrap(types[i]).isInstance(arg)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                method.setAccessible(true);
                METHODS.put(key.toString(), method);
                return method;
            }
        }
        return null;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public static boolean isTargetDevice() {
        return "annibale".equals(Build.DEVICE);
    }
}
