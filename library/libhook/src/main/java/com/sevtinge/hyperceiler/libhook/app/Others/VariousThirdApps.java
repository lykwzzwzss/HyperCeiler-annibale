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
package com.sevtinge.hyperceiler.libhook.app.Others;

import static com.sevtinge.hyperceiler.libhook.base.BaseHook.findClass;
import static com.sevtinge.hyperceiler.libhook.base.BaseHook.setStaticObjectField;

import android.content.Context;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.hchen.database.HookBase;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.appbase.input.InputMethodClassLoaderDispatcher;
import com.sevtinge.hyperceiler.libhook.appbase.input.InputMethodConfig;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import com.sevtinge.hyperceiler.libhook.rules.various.MusicHooks;
import com.sevtinge.hyperceiler.libhook.rules.various.clipboard.BaiduClipboard;
import com.sevtinge.hyperceiler.libhook.rules.various.clipboard.SoGouClipboard;
import com.sevtinge.hyperceiler.libhook.rules.various.clipboard.UnlockIme;
import com.sevtinge.hyperceiler.libhook.rules.various.input.MiAospIme;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.AppsTool;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

@HookBase(targetPackage = "VariousThirdApps")
public class VariousThirdApps extends BaseLoad {
    private static final String XIAOMI_SOGOU_PACKAGE = "com.sohu.inputmethod.sogou.xiaomi";
    private static final String SOGOU_PACKAGE = "com.sohu.inputmethod.sogou";
    private static final String XIAOMI_BAIDU_PACKAGE = "com.baidu.input_mi";
    private static final String BAIDU_PACKAGE = "com.baidu.input";

    private static Set<String> sEnabledInputMethodPackages = Collections.emptySet();

    private String mPackageName;


    @Override
    public void onPackageLoaded() {
        mPackageName = getPackageName();
        boolean isInputMethod = isEnabledInputMethodPackage(mPackageName);

        if (isInputMethod) {
            initInputMethodHooks();
            initClipboardHooks();
            return;
        }

        if ("com.google.android.googlequicksearchbox".equals(mPackageName)) {
            try {
                spoofGoogleBuildIdentity();
                XposedLog.d("GoogleQuickSearchBox", "Spoofed device info to Pixel 9 Pro success");
            } catch (Throwable e) {
                XposedLog.e("GoogleQuickSearchBox", "Failed to spoof device info", e);
            }
            return;
        }

        initMusicHooks();
    }

    private void initInputMethodHooks() {
        boolean needMiuiImeUnlock = InputMethodConfig.shouldHookMiuiIme(mPackageName);
        boolean needAospIme = InputMethodConfig.shouldHookAospIme(mPackageName);

        initHook(new InputMethodClassLoaderDispatcher());
        initHook(new UnlockIme(), needMiuiImeUnlock);
        initHook(new MiAospIme(), needAospIme);
    }

    private void initClipboardHooks() {
        boolean enableClipboardHook = PrefsBridge.getBoolean("sogou_xiaomi_clipboard");

        initHook(new SoGouClipboard(), enableClipboardHook && isSogouPackage(mPackageName));
        initHook(new BaiduClipboard(), enableClipboardHook && isBaiduPackage(mPackageName));
    }

    private void initMusicHooks() {
        initHook(MusicHooks.INSTANCE, PrefsBridge.getBoolean("system_ui_statusbar_music_switch") && PrefsBridge.getBoolean("system_ui_statusbar_music_show_app"));
    }

    private static void spoofGoogleBuildIdentity() throws Throwable {
        Class<?> buildClass = findClass("android.os.Build", BaseLoad.getClassLoader());
        String[] names = {"MANUFACTURER", "BRAND", "MODEL", "DEVICE"};
        String[] values = {"Google", "google", "Pixel 9 Pro", "caiman"};
        BuildFieldTarget[] targets = new BuildFieldTarget[names.length];

        // Resolve and validate every field before touching any of them, so fallback rollback has a full snapshot.
        for (int i = 0; i < names.length; i++) {
            Field field = buildClass.getDeclaredField(names[i]);
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                throw new IllegalStateException("Unexpected Build field: " + field);
            }
            Object originalValue = field.get(null);
            if (!(originalValue instanceof String)) {
                throw new IllegalStateException("Build field is not initialized as String: " + field);
            }
            targets[i] = new BuildFieldTarget(field, originalValue, values[i]);
        }

        UnsafeAccess unsafeAccess = null;
        Throwable unsafePreparationFailure = null;
        try {
            UnsafeAccess preparedUnsafeAccess = UnsafeAccess.create();
            for (BuildFieldTarget target : targets) {
                target.prepare(preparedUnsafeAccess);
            }
            unsafeAccess = preparedUnsafeAccess;
        } catch (Throwable e) {
            unsafePreparationFailure = e;
        }

        try {
            for (BuildFieldTarget target : targets) {
                setStaticObjectField(buildClass, target.field.getName(), target.value);
                target.verifyReflected(target.value);
            }
            return;
        } catch (Throwable setterFailure) {
            XposedLog.w("GoogleQuickSearchBox", "EzHookTool static field setter failed; trying Unsafe",
                setterFailure);
            if (unsafeAccess == null) {
                if (unsafePreparationFailure != null) setterFailure.addSuppressed(unsafePreparationFailure);
                for (BuildFieldTarget target : targets) {
                    try {
                        setStaticObjectField(buildClass, target.field.getName(), target.originalValue);
                    } catch (Throwable rollbackError) {
                        setterFailure.addSuppressed(rollbackError);
                    }
                }
                throw setterFailure;
            }
        }

        try {
            for (BuildFieldTarget target : targets) {
                unsafeAccess.put(target, target.value);
                target.verify(target.value, unsafeAccess);
            }
        } catch (Throwable fallbackFailure) {
            Throwable rollbackFailure = null;
            for (BuildFieldTarget target : targets) {
                try {
                    unsafeAccess.put(target, target.originalValue);
                    target.verify(target.originalValue, unsafeAccess);
                } catch (Throwable error) {
                    if (rollbackFailure == null) rollbackFailure = error;
                    else rollbackFailure.addSuppressed(error);
                }
            }
            if (rollbackFailure != null) fallbackFailure.addSuppressed(rollbackFailure);
            throw fallbackFailure;
        }
    }

    private static final class BuildFieldTarget {
        private final Field field;
        private final Object originalValue;
        private final String value;
        private Object staticBase;
        private long offset;

        private BuildFieldTarget(Field field, Object originalValue, String value) {
            this.field = field;
            this.originalValue = originalValue;
            this.value = value;
        }

        private void prepare(UnsafeAccess unsafeAccess) throws Throwable {
            staticBase = unsafeAccess.base(field);
            offset = unsafeAccess.offset(field);
            if (staticBase == null || offset < 0) {
                throw new IllegalStateException("Invalid Unsafe address for Build field: " + field.getName());
            }
            Object rawValue = unsafeAccess.get(this);
            if (!originalValue.equals(rawValue)) {
                throw new IllegalStateException("Unsafe address does not match Build field: " + field.getName());
            }
        }

        private void verifyReflected(Object expected) throws Throwable {
            if (!expected.equals(field.get(null))) {
                throw new IllegalStateException("Build field readback failed: " + field.getName());
            }
        }

        private void verify(Object expected, UnsafeAccess unsafeAccess) throws Throwable {
            Object rawValue = unsafeAccess.get(this);
            if (!expected.equals(rawValue)) {
                throw new IllegalStateException("Build field readback failed: " + field.getName());
            }
            verifyReflected(expected);
        }
    }

    private static final class UnsafeAccess {
        private final Object unsafe;
        private final Method staticFieldBase;
        private final Method staticFieldOffset;
        private final Method getObjectVolatile;
        private final Method putObjectVolatile;

        private UnsafeAccess(Object unsafe, Method staticFieldBase, Method staticFieldOffset,
                             Method getObjectVolatile, Method putObjectVolatile) {
            this.unsafe = unsafe;
            this.staticFieldBase = staticFieldBase;
            this.staticFieldOffset = staticFieldOffset;
            this.getObjectVolatile = getObjectVolatile;
            this.putObjectVolatile = putObjectVolatile;
        }

        private static UnsafeAccess create() throws Throwable {
            Throwable internalFailure;
            try {
                return createInternalUnsafe();
            } catch (Throwable e) {
                internalFailure = e;
            }

            try {
                return createLegacyUnsafe();
            } catch (Throwable legacyFailure) {
                legacyFailure.addSuppressed(internalFailure);
                throw legacyFailure;
            }
        }

        private static UnsafeAccess createInternalUnsafe() throws Throwable {
            HiddenApiBypass.addHiddenApiExemptions("Ljdk/internal/misc/Unsafe;");
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Field singletonField = unsafeClass.getDeclaredField("THE_ONE");
            singletonField.setAccessible(true);
            Object unsafe = singletonField.get(null);
            Method staticFieldBase = unsafeClass.getDeclaredMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);
            Method getReferenceVolatile = unsafeClass.getDeclaredMethod("getReferenceVolatile", Object.class, long.class);
            Method putReferenceVolatile = unsafeClass.getDeclaredMethod(
                "putReferenceVolatile", Object.class, long.class, Object.class);
            makeAccessible(staticFieldBase, staticFieldOffset, getReferenceVolatile, putReferenceVolatile);
            return new UnsafeAccess(unsafe, staticFieldBase, staticFieldOffset,
                getReferenceVolatile, putReferenceVolatile);
        }

        private static UnsafeAccess createLegacyUnsafe() throws Throwable {
            HiddenApiBypass.addHiddenApiExemptions("Lsun/misc/Unsafe;");
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field singletonField = unsafeClass.getDeclaredField("theUnsafe");
            singletonField.setAccessible(true);
            Object unsafe = singletonField.get(null);
            Method staticFieldBase = unsafeClass.getDeclaredMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);
            Method getObjectVolatile = unsafeClass.getDeclaredMethod("getObjectVolatile", Object.class, long.class);
            Method putObjectVolatile = unsafeClass.getDeclaredMethod(
                "putObjectVolatile", Object.class, long.class, Object.class);
            makeAccessible(staticFieldBase, staticFieldOffset, getObjectVolatile, putObjectVolatile);
            return new UnsafeAccess(unsafe, staticFieldBase, staticFieldOffset,
                getObjectVolatile, putObjectVolatile);
        }

        private static void makeAccessible(Method... methods) {
            for (Method method : methods) {
                method.setAccessible(true);
            }
        }

        private Object base(Field field) throws Throwable {
            return staticFieldBase.invoke(unsafe, field);
        }

        private long offset(Field field) throws Throwable {
            return ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
        }

        private Object get(BuildFieldTarget target) throws Throwable {
            return getObjectVolatile.invoke(unsafe, target.staticBase, target.offset);
        }

        private void put(BuildFieldTarget target, Object value) throws Throwable {
            putObjectVolatile.invoke(unsafe, target.staticBase, target.offset, value);
        }
    }

    private Set<String> getEnabledInputMethodPackages(Context context) {
        try {
            if (context == null) {
                XposedLog.e("getEnabledInputMethodPackages", "context is null");
                return Collections.emptySet();
            }

            InputMethodManager inputMethodManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (inputMethodManager == null) {
                XposedLog.e("getEnabledInputMethodPackages", "inputMethodManager is null");
                return Collections.emptySet();
            }

            List<InputMethodInfo> enabledInputMethods = inputMethodManager.getEnabledInputMethodList();
            LinkedHashSet<String> packages = new LinkedHashSet<>();
            for (InputMethodInfo inputMethodInfo : enabledInputMethods) {
                if (inputMethodInfo.getServiceInfo() != null) {
                    packages.add(inputMethodInfo.getServiceInfo().packageName);
                }
            }
            return packages;
        } catch (Throwable e) {
            XposedLog.e("getEnabledInputMethodPackages", "have e: " + e + ", message: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    private boolean isEnabledInputMethodPackage(String packageName) {
        if (sEnabledInputMethodPackages.isEmpty()) {
            sEnabledInputMethodPackages = getEnabledInputMethodPackages(
                AppsTool.findContext(AppsTool.FlAG_ONLY_ANDROID));
        }
        return sEnabledInputMethodPackages.contains(packageName);
    }

    private boolean isSogouPackage(String packageName) {
        return XIAOMI_SOGOU_PACKAGE.equals(packageName) || SOGOU_PACKAGE.equals(packageName);
    }

    private boolean isBaiduPackage(String packageName) {
        return BAIDU_PACKAGE.equals(packageName) || XIAOMI_BAIDU_PACKAGE.equals(packageName);
    }
}
