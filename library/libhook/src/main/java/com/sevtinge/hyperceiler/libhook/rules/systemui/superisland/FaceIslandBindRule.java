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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.FaceIslandContract;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandArtwork;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandController;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHooks;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin.FaceIslandExitBridge;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 绑定控制器需要的宿主对象，并挂设置页“预览动画”的广播入口。
 */
public class FaceIslandBindRule extends FaceIslandRuleBase {

    private static BroadcastReceiver sReceiver;
    private static final AtomicBoolean sReceiverRegistering = new AtomicBoolean();

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        registerHotReloadCleanups();
        FaceIslandHooks.watchEnabledPreference();
        hookLargestConstructor(find("com.android.systemui.deviceentry.data.repository.DeviceEntryFaceAuthRepositoryImpl"),
            new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.bindRepository(param.getThisObject());
                }
            });
        hookLargestConstructor(find("com.android.keyguard.KeyguardUpdateMonitor"), new IMethodHook() {
            @Override
            public void after(HookParam param) {
                FaceIslandHooks.bindMonitor(param.getThisObject());
            }
        });
        hookLargestConstructor(find("com.android.systemui.keyguard.KeyguardViewMediator"), new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object mediator = param.getThisObject();
                FaceIslandHooks.bindMediator(mediator);
                // mediator 的 mContext 取不到时退回进程级 Application，注册失败留给下一次绑定重试。
                registerPreviewReceiver(previewContext(mediator));
            }
        });
        hookLargestConstructor(find("com.android.systemui.statusbar.notification.DynamicIslandController"),
            new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    FaceIslandHooks.bindIsland(param.getThisObject());
                }
            });
    }

    /**
     * 热重载时旧 generation 的控制器、退场浮层与静态缓存都必须复位。
     */
    private static void registerHotReloadCleanups() {
        registerHotReloadCleanup(FaceIslandController::shutdown);
        registerHotReloadCleanup(FaceIslandHooks::reset);
        registerHotReloadCleanup(FaceIslandExitBridge::reset);
        registerHotReloadCleanup(FaceIslandArtwork::release);
        registerHotReloadCleanup(FaceIslandPluginRule::reset);
    }

    private static Context previewContext(Object mediator) {
        try {
            Object context = getObjectField(mediator, "mContext");
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable th) {
            XposedLog.w("FaceIsland", "mediator context unavailable: " + th);
        }
        try {
            Class<?> activityThread = findClassIfExists("android.app.ActivityThread");
            Object application = activityThread == null ? null
                : callStaticMethod(activityThread, "currentApplication");
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable th) {
            XposedLog.w("FaceIsland", "application context unavailable: " + th);
        }
        return null;
    }

    private static void registerPreviewReceiver(Context context) {
        if (context == null) {
            XposedLog.w("FaceIsland", "preview receiver skipped: context missing");
            return;
        }
        if (sReceiver != null || !sReceiverRegistering.compareAndSet(false, true)) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (!FaceIslandContract.ACTION_PREVIEW.equals(intent.getAction())) {
                        return;
                    }
                    boolean started = FaceIslandController.beginPreview();
                    XposedLog.i("FaceIsland", "face island preview requested: " + started);
                }
            };
            IntentFilter filter = new IntentFilter(FaceIslandContract.ACTION_PREVIEW);
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            registerReceiverHotReloadCleanup(context, receiver);
            registerHotReloadCleanup(() -> {
                sReceiver = null;
                sReceiverRegistering.set(false);
            });
            sReceiver = receiver;
        } catch (Throwable th) {
            // 注册失败不缓存任何状态，下一次 mediator 绑定会重试。
            XposedLog.e("FaceIsland", "preview receiver register failed: ", th);
        } finally {
            sReceiverRegistering.set(false);
        }
    }
}
