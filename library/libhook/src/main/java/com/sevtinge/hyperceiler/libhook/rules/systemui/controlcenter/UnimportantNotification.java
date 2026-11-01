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
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

/**
 * 忽略“不重要通知”的折叠逻辑。
 * <p>
 * 原实现依赖 Kotlin lambda 生成类名（{@code FoldCoordinator$shadeExpansionListener$1}）和
 * 编译器合成的 {@code access$shouldIgnoreEntry}，这两个名字会随 ROM/Kotlin 编译器版本变化，
 * 匹配失败时 fallback 也只是形同虚设。现在改为：
 * <ul>
 *   <li>按方法签名在 {@code FoldCoordinator} 上定位 {@code shouldIgnoreEntry(NotificationEntry)}；</li>
 *   <li>按调用关系（{@code FoldCoordinator} 持有的监听器字段 → 其唯一接收
 *       {@code ShadeExpansionChangeEvent} 的方法）定位面板展开监听器；</li>
 *   <li>任何一步找不到都只打日志，不再抛异常影响 SystemUI 启动。</li>
 * </ul>
 */
public class UnimportantNotification extends BaseHook {
    private static final String CLS_FOLD_COORDINATOR = "com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator";
    private static final String CLS_NOTIFICATION_UTIL = "com.android.systemui.statusbar.notification.utils.NotificationUtil";
    private static final String CLS_NOTIFICATION_UTIL_OLD = "com.android.systemui.statusbar.notification.NotificationUtil";
    private static final String CLS_SHADE_EXPANSION_EVENT = "com.android.systemui.shade.ShadeExpansionChangeEvent";

    private static final String METHOD_SHOULD_IGNORE_ENTRY = "shouldIgnoreEntry";
    private static final String FIELD_PENDING_NOTIFICATIONS = "mPendingNotifications";

    /** 已注册 hook 的监听器类型，避免同一方法被重复 hook（FoldCoordinator 通常是单例，但保持幂等）。 */
    private static final Set<Class<?>> hookedListenerTypes = Collections.synchronizedSet(new HashSet<>());

    @Override
    public void init() {
        try {
            Class<?> foldCoordinator = findClassIfExists(CLS_FOLD_COORDINATOR);
            if (foldCoordinator == null) {
                XposedLog.w(TAG, "FoldCoordinator not found, skip unimportant notification hook");
                return;
            }

            boolean shouldIgnoreHooked = hookShouldIgnoreEntry(foldCoordinator);
            boolean expansionHooked = hookExpansionListener(foldCoordinator);

            if (!shouldIgnoreHooked && !expansionHooked) {
                // 旧 ROM 的兜底实现，只在 FoldCoordinator 完全没匹配上时才尝试
                hookNotificationUtilFallback();
            }
        } catch (Throwable e) {
            XposedLog.w(TAG, "init failed, unimportant notification hook disabled: " + e);
        }
    }

    /**
     * 按方法签名定位 {@code shouldIgnoreEntry}（不再依赖 {@code access$shouldIgnoreEntry}）。
     *
     * @return 是否至少 hook 成功一个方法
     */
    private boolean hookShouldIgnoreEntry(Class<?> foldCoordinator) {
        boolean hooked = false;
        for (Method method : foldCoordinator.getDeclaredMethods()) {
            if (!METHOD_SHOULD_IGNORE_ENTRY.equals(method.getName())) continue;
            if (method.getParameterCount() != 1) continue;
            if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) continue;
            try {
                method.setAccessible(true);
                hookMethod(method, new IMethodHook() {
                    @Override
                    public void after(HookParam param) {
                        param.setResult(true);
                    }
                });
                hooked = true;
            } catch (Throwable e) {
                XposedLog.w(TAG, "hook shouldIgnoreEntry failed: " + e);
            }
        }
        if (!hooked) {
            XposedLog.w(TAG, "shouldIgnoreEntry(NotificationEntry) not found on FoldCoordinator");
        }
        return hooked;
    }

    /**
     * 按调用关系定位面板展开监听器：FoldCoordinator 的非静态字段里持有监听器实例，
     * 该实例中唯一接收 {@code ShadeExpansionChangeEvent} 的方法是真正的回调入口。
     * 这样既不依赖 lambda 生成类名，也兼容 Kotlin/Java 匿名类的差异。
     *
     * @return 是否至少 hook 成功一个方法
     */
    private boolean hookExpansionListener(Class<?> foldCoordinator) {
        final boolean[] hooked = {false};
        try {
            chainAllConstructors(foldCoordinator, new io.github.libxposed.api.XposedInterface.Hooker() {
                @Override
                public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    if (hooked[0]) return result;
                    try {
                        hooked[0] = hookListenerFields(chain.getThisObject());
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "scan FoldCoordinator listener fields failed: " + e);
                    }
                    return result;
                }
            });
        } catch (Throwable e) {
            XposedLog.w(TAG, "hook FoldCoordinator constructor failed: " + e);
        }
        if (!hooked[0]) {
            XposedLog.w(TAG, "shade expansion listener not located on FoldCoordinator");
        }
        return hooked[0];
    }

    private boolean hookListenerFields(Object coordinator) {
        if (coordinator == null) return false;
        for (Field field : coordinator.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object listener;
            try {
                field.setAccessible(true);
                listener = field.get(coordinator);
            } catch (Throwable ignore) {
                continue;
            }
            if (listener == null || listener == coordinator) continue;
            Class<?> listenerType = listener.getClass();
            Method expansionMethod = findExpansionMethod(listenerType);
            if (expansionMethod == null) continue;
            if (!hookedListenerTypes.add(listenerType)) return true;

            // FoldCoordinator 是 SystemUI 内的长生命周期单例，用弱引用持有避免意外泄漏
            final WeakReference<Object> coordinatorRef = new WeakReference<>(coordinator);
            try {
                hookMethod(expansionMethod, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        Object owner = resolveCoordinator(param.getThisObject(), coordinatorRef);
                        if (owner == null) return;
                        resetPendingNotifications(owner);
                    }
                });
                return true;
            } catch (Throwable e) {
                hookedListenerTypes.remove(listenerType);
                XposedLog.w(TAG, "hook expansion listener method failed: " + e);
            }
        }
        return false;
    }

    private Method findExpansionMethod(Class<?> type) {
        Method fallback = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 1) continue;
            if (!CLS_SHADE_EXPANSION_EVENT.equals(method.getParameterTypes()[0].getName())) continue;
            try {
                method.setAccessible(true);
            } catch (Throwable ignore) {
                continue;
            }
            // Kotlin lambda 类里同时存在 invoke(...) 和真正的 SAM 方法，
            // 优先选择后者，否则只把它当兜底。
            if (!"invoke".equals(method.getName())) {
                return method;
            }
            if (fallback == null) fallback = method;
        }
        return fallback;
    }

    /** 回调的 this 可能是 lambda / 匿名类，需要通过 this$0 或注册时捕获的引用找回 FoldCoordinator。 */
    private Object resolveCoordinator(Object listener, WeakReference<Object> fallback) {
        if (listener != null && CLS_FOLD_COORDINATOR.equals(listener.getClass().getName())) {
            return listener;
        }
        if (listener != null) {
            try {
                Object outer = getObjectField(listener, "this$0");
                if (outer != null && CLS_FOLD_COORDINATOR.equals(outer.getClass().getName())) {
                    return outer;
                }
            } catch (Throwable ignore) {
                // lambda 不一定有 this$0，落到下面的兜底
            }
        }
        return fallback.get();
    }

    private void resetPendingNotifications(Object coordinator) {
        try {
            Object pending = getObjectField(coordinator, FIELD_PENDING_NOTIFICATIONS);
            if (pending instanceof Collection) {
                // 保持宿主声明的集合类型（可能是 Set），只清空内容
                ((Collection<?>) pending).clear();
                return;
            }
            setObjectField(coordinator, FIELD_PENDING_NOTIFICATIONS, new ArrayList<>());
        } catch (Throwable e) {
            XposedLog.w(TAG, "reset " + FIELD_PENDING_NOTIFICATIONS + " failed: " + e);
        }
    }

    /** 老版本 ROM 的兜底路径；找不到类时只记日志。 */
    private void hookNotificationUtilFallback() {
        Class<?> clazz = findClassIfExists(CLS_NOTIFICATION_UTIL);
        if (clazz == null) {
            clazz = findClassIfExists(CLS_NOTIFICATION_UTIL_OLD);
        }
        if (clazz == null) {
            XposedLog.w(TAG, "NotificationUtil not found, no fallback available");
            return;
        }
        try {
            hookAllMethods(clazz, METHOD_SHOULD_IGNORE_ENTRY, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Throwable e) {
            XposedLog.w(TAG, "fallback hook NotificationUtil.shouldIgnoreEntry failed: " + e);
        }
    }
}
