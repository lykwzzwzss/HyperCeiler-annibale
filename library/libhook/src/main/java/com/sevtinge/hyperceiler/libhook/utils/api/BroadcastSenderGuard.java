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
package com.sevtinge.hyperceiler.libhook.utils.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.BroadcastOptions;
import android.os.Process;

/**
 * Entry guard for the exported action receivers that live inside SystemUI and system_server.
 *
 * <p>Maintenance and preview receivers require {@link #PERMISSION}. Ordinary action receivers
 * also serve hooks running in the system launcher, which cannot hold the module's signature
 * permission. Those receivers require a shared sender identity and verify its UID against the
 * module, the receiver host, privileged system callers, or the installed system launcher.
 */
public final class BroadcastSenderGuard {

    /** Signature permission declared in the module manifest; only same-signature apps hold it. */
    public static final String PERMISSION = "com.sevtinge.hyperceiler.permission.SEND_ACTION";

    private static final String MODULE_PACKAGE = "com.sevtinge.hyperceiler";
    private static final int UID_ROOT = 0;
    private static final int UID_SYSTEM = 1000;
    private static final int UID_SHELL = 2000;

    private BroadcastSenderGuard() {
    }

    /**
     * @return true when the broadcast really comes from this module (or from the system/ADB, which
     * already outrank the module and are used by the in-app diagnostics).
     */
    public static boolean isFromModule(BroadcastReceiver receiver, Context context) {
        int uid = sendingUid(receiver);
        // This entry is only used behind PERMISSION. Older senders do not share their identity.
        return uid < 0 || isModuleUid(uid, context);
    }

    /** Ordinary actions may also come from the installed system launcher. Fail closed. */
    public static boolean isTrustedActionSender(BroadcastReceiver receiver, Context context) {
        int uid = sendingUid(receiver);
        return uid >= 0 && (isModuleUid(uid, context) || isSystemLauncher(uid, context));
    }

    private static boolean isModuleUid(int uid, Context context) {
        if (uid == UID_ROOT || uid == UID_SYSTEM || uid == UID_SHELL) {
            return true;
        }
        if (uid == Process.myUid()) {
            return true;
        }
        try {
            int moduleUid = context.getPackageManager().getPackageUid(MODULE_PACKAGE, 0);
            return uid == moduleUid;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSystemLauncher(int uid, Context context) {
        try {
            ApplicationInfo launcher = context.getPackageManager()
                .getApplicationInfo("com.miui.home", 0);
            return launcher.uid == uid && (launcher.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return false;
        }
    }

    /** Include the real sending UID; a Context/package-name extra cannot establish identity. */
    public static void send(Context context, Intent intent, String targetPackage) {
        context.sendBroadcast(intent.setPackage(targetPackage), null,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }

    private static int sendingUid(BroadcastReceiver receiver) {
        if (receiver == null) {
            return -1;
        }
        try {
            return receiver.getSentFromUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
