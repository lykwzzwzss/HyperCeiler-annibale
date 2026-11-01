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
package com.sevtinge.hyperceiler.libhook.rules.systemui.lockscreen;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class DisableUnlockByBleToast extends BaseHook {
    private static final long BLE_TOAST_WINDOW_MS = 3000L;
    private static final String BLE_TOAST_RES_NAME =
        "com.android.systemui:string/miui_keyguard_ble_unlock_succeed_msg";

    private static final AtomicBoolean sToastHooksInstalled = new AtomicBoolean(false);
    private static final AtomicLong sSuppressUntil = new AtomicLong(0L);
    private static volatile boolean sBleToastPending = false;

    @Override
    public void init() {
        // Toast 钩子只安装一次，避免每次蓝牙解锁都重复注册
        if (sToastHooksInstalled.compareAndSet(false, true)) {
            try {
                installToastHooks();
            } catch (Throwable t) {
                sToastHooksInstalled.set(false);
                XposedLog.e(TAG, "Failed to install toast hooks", t);
            }
        }

        hookBleUnlockTriggers();
    }

    private void hookBleUnlockTriggers() {
        findAndHookMethod("com.android.keyguard.KeyguardSecurityContainerController$3", "dismiss", boolean.class, int.class, boolean.class, "com.android.keyguard.KeyguardSecurityModel$SecurityMode", new IMethodHook() {
            @Override
            public void before(HookParam param) {
                armSuppressWindow();
            }
        });
        findAndHookMethod("com.android.keyguard.MiuiBleUnlockHelper", "tryUnlockByBle", new IMethodHook() {
            @Override
            public void before(HookParam param) {
                armSuppressWindow();
            }
        });
    }

    private static void armSuppressWindow() {
        sSuppressUntil.set(SystemClock.elapsedRealtime() + BLE_TOAST_WINDOW_MS);
    }

    private static boolean isInSuppressWindow(long now) {
        long until = sSuppressUntil.get();
        return until > 0L && now <= until;
    }

    private void installToastHooks() {
        findAndHookMethod(Toast.class, "makeText", Context.class, int.class, int.class, new IMethodHook() {
            @Override
            public void before(HookParam param) {
                try {
                    Context context = (Context) param.getArgs()[0];
                    int resId = (int) param.getArgs()[1];
                    if (context == null) return;
                    String resName = context.getResources().getResourceName(resId);
                    if (BLE_TOAST_RES_NAME.equals(resName)
                        && isInSuppressWindow(SystemClock.elapsedRealtime())) {
                        // 只登记本次蓝牙解锁提示，show 时吞掉后立即清除
                        sBleToastPending = true;
                    }
                } catch (Throwable t) {
                    XposedLog.w(TAG, "Failed to inspect toast resource", t);
                }
            }
        });
        findAndHookMethod(Toast.class, "show", new IMethodHook() {
            @Override
            public void before(HookParam param) {
                long now = SystemClock.elapsedRealtime();
                if (sBleToastPending && isInSuppressWindow(now)) {
                    sBleToastPending = false;
                    sSuppressUntil.set(0L);
                    param.setResult(null);
                }
            }
        });
    }

}
