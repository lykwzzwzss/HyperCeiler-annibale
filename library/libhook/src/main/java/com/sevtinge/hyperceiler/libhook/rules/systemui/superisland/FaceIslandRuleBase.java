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

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 超级岛规则共用的小工具：按名字+参数个数安装 hook、维护 ThreadLocal 状态栈。
 */
abstract class FaceIslandRuleBase extends BaseHook {

    static Class<?> find(String name) {
        return findClassIfExists(name);
    }

    static Class<?> find(String name, ClassLoader loader) {
        return findClassIfExists(name, loader);
    }

    /**
     * 按名字+参数个数安装 hook，覆盖所有同名同参方法（等价于 Xposed 的 hookAllMethods）。
     */
    static Method hook(Class<?> clazz, String name, int count, IMethodHook callback) {
        if (clazz == null) {
            return null;
        }
        Method first = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == count) {
                hookMethod(method, callback);
                if (first == null) {
                    first = method;
                }
            }
        }
        if (first == null) {
            XposedLog.w("FaceIsland", "method not found: " + clazz.getName() + "#" + name + "/" + count);
        }
        return first;
    }

    static Method hookAny(Class<?> clazz, String namePart, int count, IMethodHook callback) {
        if (clazz == null) {
            return null;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().contains(namePart) && method.getParameterCount() == count) {
                hookMethod(method, callback);
                return method;
            }
        }
        return null;
    }

    static Constructor<?> hookLargestConstructor(Class<?> clazz, IMethodHook callback) {
        if (clazz == null) {
            return null;
        }
        Constructor<?> target = null;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            if (target == null || constructor.getParameterCount() > target.getParameterCount()) {
                target = constructor;
            }
        }
        if (target == null) {
            return null;
        }
        Object[] args = new Object[target.getParameterCount() + 1];
        System.arraycopy(target.getParameterTypes(), 0, args, 0, target.getParameterCount());
        args[target.getParameterCount()] = callback;
        findAndHookConstructor(clazz, args);
        return target;
    }

    static int number(Object[] args, int index, int fallback) {
        if (index < 0 || index >= args.length || !(args[index] instanceof Number)) {
            return fallback;
        }
        return ((Number) args[index]).intValue();
    }

    static boolean bool(Object[] args, int index, boolean fallback) {
        if (index < 0 || index >= args.length || !(args[index] instanceof Boolean)) {
            return fallback;
        }
        return (Boolean) args[index];
    }

    static <T> void push(ThreadLocal<ArrayList<T>> stack, T value) {
        stack.get().add(value);
    }

    static <T> T pop(ThreadLocal<ArrayList<T>> stack) {
        ArrayList<T> list = stack.get();
        return list.isEmpty() ? null : list.remove(list.size() - 1);
    }

    static <T> T peek(ThreadLocal<ArrayList<T>> stack) {
        ArrayList<T> list = stack.get();
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    static Object[] argsOf(HookParam param) {
        return param.getArgs();
    }
}
