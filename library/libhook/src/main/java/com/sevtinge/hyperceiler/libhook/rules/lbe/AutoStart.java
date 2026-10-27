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
package com.sevtinge.hyperceiler.libhook.rules.lbe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class AutoStart extends BaseHook {
    @Override
    public void init() {
        Class<?> legacy = findClassIfExists("com.miui.privacy.autostart.AutoRevokePermissionManager");
        if (legacy != null) {
            try {
                Method target = legacy.getDeclaredMethod("lambda$startScheduleASCheck$1", Context.class, boolean.class);
                if (target.getReturnType() != void.class) throw new NoSuchMethodException("Legacy return type");
                target.setAccessible(true);
                if (hookMethod(target, new IMethodHook() {
                    @Override public void before(HookParam param) { param.setResult(null); }
                }) == null) throw new IllegalStateException("Legacy auto-start guard was not installed");
                XposedLog.i(TAG, "Resolved legacy scheduled auto-start guard");
                return;
            } catch (NoSuchMethodException ignored) {
                // Some transitional builds retain the old class but use OnePush instead.
            }
        }
        installOnePushGuard();
    }

    private void installOnePushGuard() {
        final Method revoke;
        final Method onePush;
        final Method userChanged;
        final long autoStartPermission;
        try {
            revoke = instanceMethod(requiredClass("com.hyperos.security.service.OnePushJobService$GetCloudDataTask"),
                "revokeAutoStart", void.class, Context.class, PackageInfo.class, List.class);
            onePush = instanceMethod(requiredClass("com.hyperos.security.service.provider.PermissionManagerProvider"),
                "dealOnePush", Bundle.class, Bundle.class);
            userChanged = instanceMethod(requiredClass("com.hyperos.permissiondatabase.PackagePermissionRepository"),
                "isUserChangePerm", boolean.class, String.class, int.class, long.class);
            Field id = requiredClass("com.miui.permission.PermissionManager").getDeclaredField("PERM_ID_AUTOSTART");
            if (id.getType() != long.class || !Modifier.isStatic(id.getModifiers())) {
                throw new NoSuchFieldException("Unexpected auto-start permission ID");
            }
            id.setAccessible(true);
            autoStartPermission = id.getLong(null);
            if (autoStartPermission <= 0) throw new IllegalStateException("Invalid auto-start permission ID");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Automatic auto-start revocation interfaces unavailable", error);
        }

        AtomicBoolean active = new AtomicBoolean(false);
        ThreadLocal<Integer> onePushDepth = ThreadLocal.withInitial(() -> 0);
        ArrayList<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            // Skip only the scheduled per-package revoke leaf. Cloud restoration and
            // the rest of the job continue unchanged, including permission bookkeeping.
            handles.add(requireHandle(hookMethod(revoke, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (active.get()) param.setResult(null);
                }
            })));
            handles.add(requireHandle(hookMethod(onePush, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (active.get()) onePushDepth.set(onePushDepth.get() + 1);
                }
                @Override public void after(HookParam param) {
                    int depth = onePushDepth.get();
                    if (depth <= 1) onePushDepth.remove();
                    else onePushDepth.set(depth - 1);
                }
            })));
            // The original OnePush registration path already exempts user-managed
            // permissions. Use that branch for auto-start only, within this call.
            // This changes no persistent user-change flags. Deregistration still
            // executes the original restore branch; manual settings remain untouched.
            handles.add(requireHandle(hookMethod(userChanged, new IMethodHook() {
                @Override public void before(HookParam param) {
                    if (active.get() && onePushDepth.get() > 0
                            && ((Long) param.getArgs()[2]) == autoStartPermission) {
                        param.setResult(true);
                    }
                }
            })));
            active.set(true);
            XposedLog.i(TAG, "Resolved OnePush scheduled and registration auto-start guards");
        } catch (RuntimeException error) {
            active.set(false);
            for (XposedInterface.HookHandle handle : handles) {
                try { handle.unhook(); } catch (RuntimeException rollback) { error.addSuppressed(rollback); }
            }
            throw error;
        }
    }

    private Class<?> requiredClass(String name) {
        Class<?> type = findClassIfExists(name);
        if (type == null) throw new IllegalStateException("Missing auto-start class: " + name);
        return type;
    }

    private static Method instanceMethod(Class<?> type, String name, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("Unexpected auto-start method: " + method);
        }
        method.setAccessible(true);
        return method;
    }

    private static XposedInterface.HookHandle requireHandle(XposedInterface.HookHandle handle) {
        if (handle == null) throw new IllegalStateException("OnePush auto-start guard was not installed");
        return handle;
    }
}
