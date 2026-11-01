package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandPrefs;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHostView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class FaceIslandTemplateBridge {
    private static final String DEBUG_KEY = "face_island_debug_preview";
    private static final int FAKE_BIG = 1;
    private static final int FAKE_SMALL = 3;
    private static final String KEY = "face_island_unlock";
    private static final String MANAGED_EXTRA = "face_island_managed";
    private static final int REAL_BIG = 0;
    private static final int REAL_SMALL = 2;
    private static final String[] REAL_AREA_IDS = {"area_left", "area_right", "area_cutout"};
    private static final String[] FAKE_AREA_IDS = {"fake_area_left", "fake_area_right", "fake_area_cutout"};
    private static final String[] REAL_CUTOUT_FALLBACK_IDS = {"area_margin"};
    private static final String[] FAKE_CUTOUT_FALLBACK_IDS = {"area_margin"};
    private static final Map<String, WeakReference<View>[]> ROOTS = new HashMap<>();
    private static final Map<String, WeakReference<Object>[]> HOSTS = new HashMap<>();
    private static final Map<String, Boolean> REMOVAL_NOTIFIED = new HashMap<>();

    private FaceIslandTemplateBridge() {
    }

    public static View create(Context pluginContext, Object data, ViewGroup parent, boolean fake, boolean small) {
        try {
            if (!isManaged(data)) {
                XposedLog.d("FaceIslandBridge", "create skipped: not managed");
                return null;
            }
            try {
                Object hostObject = invoke(data, "getView");
                if (!(hostObject instanceof FaceIslandHostView)) {
                    XposedLog.w("FaceIslandBridge", "create skipped: host=" + hostObject);
                    return null;
                }
                FaceIslandHostView host = (FaceIslandHostView) hostObject;
                XposedLog.d("FaceIslandBridge", "create host=" + host.getClass().getName()
                    + " fake=" + fake + " small=" + small + " parent=" + parent);
                try {
                    // 直接调用：宿主与桥同属模块 classloader，避免混淆后方法名反射失配。
                    View result = host.createTopView(pluginContext, parent, fake, small);
                    if (result == null) {
                        XposedLog.w("FaceIslandBridge", "create skipped: null view");
                        return null;
                    }
                    View root = result;
                    if (parent != null && root.getParent() == null) {
                        parent.addView(root);
                    }
                    if (parent != null && root.getParent() == parent) {
                        int slot = slot(fake, small);
                        String dataKey = islandKey(data);
                        synchronized (ROOTS) {
                            WeakReference<View>[] roots = ROOTS.get(dataKey);
                            WeakReference<Object>[] hosts = HOSTS.get(dataKey);
                            if (roots == null) {
                                roots = new WeakReference[4];
                                ROOTS.put(dataKey, roots);
                            }
                            if (hosts == null) {
                                hosts = new WeakReference[4];
                                HOSTS.put(dataKey, hosts);
                            }
                            roots[slot] = new WeakReference<>(root);
                            hosts[slot] = new WeakReference<>(host);
                            REMOVAL_NOTIFIED.put(dataKey, false);
                        }
                        return root;
                    }
                    return null;
                } catch (Throwable th) {
                    XposedLog.e("FaceIslandBridge", "createTopView failed", th);
                    return null;
                }
            } catch (Throwable th2) {
                XposedLog.e("FaceIslandBridge", "create view failed", th2);
            }
        } catch (Throwable th3) {
            XposedLog.e("FaceIslandBridge", "create outer failed", th3);
        }
        return null;
    }

    public static boolean updateWidth(Object data, int width, boolean fake, int part) {
        ViewGroup.LayoutParams params;
        if (!isManaged(data) || part < 0 || part > 2) {
            return false;
        }
        int scaledWidth = Math.max(0, Math.round((FaceIslandPrefs.widthPercent() * width) / 100.0f));
        boolean handled = false;
        int[] slots = {slot(fake, false), slot(fake, true)};
        for (int rootSlot : slots) {
            View root = getRoot(data, rootSlot);
            if (root != null) {
                handled = true;
                View area = findArea(root, fake, part);
                if (area != null && (params = area.getLayoutParams()) != null) {
                    params.width = scaledWidth;
                    area.setLayoutParams(params);
                }
            }
        }
        return handled;
    }

    public static Object scaleDebugIslandRect(Object value) {
        int scaledWidth;
        if (!(value instanceof Rect) || !FaceIslandPrefs.isDebugPreviewActive()) {
            return value;
        }
        Rect source = (Rect) value;
        int width = source.width();
        if (width <= 0 || (scaledWidth = Math.max(1,
            Math.round((FaceIslandPrefs.widthPercent() * width) / 100.0f))) == width) {
            return value;
        }
        int center = source.centerX();
        int half = scaledWidth / 2;
        Rect scaled = new Rect(source);
        scaled.left = center - half;
        scaled.right = scaled.left + scaledWidth;
        return scaled;
    }

    public static Object scaleManagedIslandWidth(Object owner, Object value) {
        if (!(value instanceof Number) || !isManagedContent(owner)) {
            return value;
        }
        int width = ((Number) value).intValue();
        if (width <= 0) {
            return value;
        }
        int scaledWidth = Math.max(1, Math.round((FaceIslandPrefs.widthPercent() * width) / 100.0f));
        return Integer.valueOf(scaledWidth);
    }

    public static Object scaleManagedIslandX(Object owner, Object value) {
        if (!(value instanceof Number) || !isManagedContent(owner)) {
            return value;
        }
        int baseWidth = readIntField(owner, "bigIslandViewWidth");
        if (baseWidth <= 0) {
            baseWidth = readIntField(owner, "bigIslandViewWidthHasSmallIsland");
        }
        if (baseWidth <= 0) {
            return value;
        }
        int scaledWidth = Math.max(1, Math.round((FaceIslandPrefs.widthPercent() * baseWidth) / 100.0f));
        int shift = (scaledWidth - baseWidth) / 2;
        return Integer.valueOf(((Number) value).intValue() - shift);
    }

    private static boolean isManagedContent(Object content) {
        try {
            return isManaged(invoke(content, "getCurrentIslandData"));
        } catch (Throwable th) {
            return false;
        }
    }

    public static boolean setVisible(Object data, boolean visible, boolean fake) {
        if (!isManaged(data)) {
            return false;
        }
        int[] slots = fake ? new int[]{1, 3} : new int[]{0, 2};
        boolean handled = false;
        for (int slot : slots) {
            View root = getRoot(data, slot);
            if (root != null) {
                root.setVisibility(visible ? 0 : 8);
                handled = true;
            }
        }
        return handled;
    }

    public static boolean remove(String key) {
        if (!isManagedKey(key)) {
            return false;
        }
        notifyRemoved(key, "PLUGIN_REMOVE");
        synchronized (ROOTS) {
            WeakReference<View>[] roots = ROOTS.remove(key);
            HOSTS.remove(key);
            REMOVAL_NOTIFIED.remove(key);
            if (roots != null) {
                for (WeakReference<View> reference : roots) {
                    View root = reference == null ? null : reference.get();
                    if (root != null && (root.getParent() instanceof ViewGroup)) {
                        ((ViewGroup) root.getParent()).removeView(root);
                    }
                }
            }
        }
        return true;
    }

    public static void notifyRemoved(String key, String reason) {
        if (!isManagedKey(key)) {
            return;
        }
        Object host = null;
        synchronized (ROOTS) {
            if (Boolean.TRUE.equals(REMOVAL_NOTIFIED.get(key))) {
                return;
            }
            WeakReference<Object>[] hosts = HOSTS.get(key);
            if (hosts != null) {
                for (WeakReference<Object> reference : hosts) {
                    if (reference != null && reference.get() != null) {
                        host = reference.get();
                        break;
                    }
                }
            }
            if (host == null) {
                return;
            }
            REMOVAL_NOTIFIED.put(key, true);
            try {
                if (host instanceof FaceIslandHostView) {
                    ((FaceIslandHostView) host).onPluginRemoved(reason);
                } else {
                    host.getClass().getMethod("onPluginRemoved", String.class).invoke(host, reason);
                }
            } catch (NoSuchMethodException e) {
            } catch (Throwable th) {
            }
        }
    }

    public static boolean isManaged(Object data) {
        if (data != null) {
            try {
                if (isManagedKey(islandKey(data))) {
                    Object extras = invoke(data, "getExtras");
                    if (extras instanceof Bundle) {
                        return ((Bundle) extras).getBoolean(MANAGED_EXTRA, false);
                    }
                    return false;
                }
            } catch (Throwable th) {
                return false;
            }
        }
        return false;
    }

    public static Object createTemplate(Object data, Object controller) {
        if (!isManaged(data) || controller == null) {
            return null;
        }
        try {
            ClassLoader loader = controller.getClass().getClassLoader();
            Class<?> templateClass = Class.forName("miui.systemui.dynamicisland.model.IslandTemplate", true, loader);
            Class<?>[] parameterTypes = {
                Class.forName("miui.systemui.dynamicisland.model.BigIslandArea", true, loader),
                Class.forName("miui.systemui.dynamicisland.model.SmallIslandArea", true, loader),
                Class.forName("miui.systemui.dynamicisland.model.ShareData", true, loader),
                String.class, Boolean.TYPE, Integer.TYPE, String.class, Integer.class, Integer.class,
                Boolean.TYPE, Boolean.class, Integer.TYPE, Boolean.class, String.class, Boolean.class
            };
            Constructor<?> constructor = templateClass.getConstructor(parameterTypes);
            return constructor.newInstance(null, null, null, islandKey(data), Boolean.FALSE, 0, null,
                0, 100, Boolean.TRUE, Boolean.TRUE, 0, Boolean.FALSE, "解锁状态", Boolean.FALSE);
        } catch (Throwable th) {
            return null;
        }
    }

    public static boolean cancelManagedTimeout(Object windowController, Object data) {
        if (!isManaged(data)) {
            return false;
        }
        try {
            Object safeguards = field(windowController, "dynamicIslandSafeguardsController");
            if (safeguards == null) {
                return false;
            }
            String key = String.valueOf(invoke(data, "getKey"));
            Method method = findMethod(safeguards.getClass(), "cancelDelayDeleted", 1);
            if (method == null) {
                return false;
            }
            method.invoke(safeguards, key);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    public static boolean onIslandClick(Object content) {
        try {
            Object data = invoke(content, "getCurrentIslandData");
            if (!isManaged(data)) {
                return false;
            }
            if (DEBUG_KEY.equals(islandKey(data))) {
                return true;
            }
            Object host = null;
            try {
                host = invoke(data, "getView");
            } catch (Throwable th) {
            }
            if (host == null) {
                return true;
            }
            if (host instanceof FaceIslandHostView) {
                ((FaceIslandHostView) host).retry();
            } else {
                host.getClass().getMethod("retry", new Class[0]).invoke(host, new Object[0]);
            }
            return true;
        } catch (Throwable th2) {
            return false;
        }
    }

    public static Object onInterceptTouchEvent(Object content) {
        try {
            Object data = invoke(content, "getCurrentIslandData");
            if (isManaged(data)) {
                return Boolean.FALSE;
            }
            return null;
        } catch (Throwable th) {
            return null;
        }
    }

    private static View findArea(View root, boolean fake, int part) {
        String[] names = fake ? FAKE_AREA_IDS : REAL_AREA_IDS;
        View area = findByName(root, names[part]);
        if (area != null || part != 2) {
            return area;
        }
        String[] fallback = fake ? FAKE_CUTOUT_FALLBACK_IDS : REAL_CUTOUT_FALLBACK_IDS;
        for (String name : fallback) {
            View area2 = findByName(root, name);
            if (area2 != null) {
                return area2;
            }
        }
        return null;
    }

    private static View findByName(View root, String name) {
        Resources resources = root.getResources();
        String packageName = root.getContext().getPackageName();
        int id = resources.getIdentifier(name, "id", packageName);
        if (id == 0) {
            return null;
        }
        return root.findViewById(id);
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method, new Class[0]).invoke(target, new Object[0]);
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target == null ? null : target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int readIntField(Object target, String name) {
        try {
            Object value = field(target, name);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            return 0;
        } catch (Throwable th) {
            return 0;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName()) && method.getParameterTypes().length == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static int slot(boolean z, boolean z2) {
        return z2 ? z ? 3 : 2 : z ? 1 : 0;
    }

    private static View getRoot(Object data, int slot) {
        Object storedHost;
        View view = null;
        try {
            Object currentHost = invoke(data, "getView");
            String dataKey = islandKey(data);
            if (!isManagedKey(dataKey)) {
                return null;
            }
            synchronized (ROOTS) {
                WeakReference<View>[] roots = ROOTS.get(dataKey);
                WeakReference<Object>[] hosts = HOSTS.get(dataKey);
                if (hosts != null && hosts[slot] != null) {
                    storedHost = hosts[slot].get();
                    if (storedHost != null && storedHost == currentHost) {
                        if (roots != null && roots[slot] != null) {
                            view = roots[slot].get();
                        }
                        return view;
                    }
                    return null;
                }
                storedHost = null;
                if (storedHost != null) {
                    if (roots != null) {
                        view = roots[slot].get();
                    }
                    return view;
                }
                return null;
            }
        } catch (Throwable th) {
            return null;
        }
    }

    private static String islandKey(Object data) {
        try {
            Object value = invoke(data, "getKey");
            if (value == null) {
                return null;
            }
            return String.valueOf(value);
        } catch (Throwable th) {
            return null;
        }
    }

    private static boolean isManagedKey(String key) {
        return KEY.equals(key) || DEBUG_KEY.equals(key);
    }
}
