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
import android.content.pm.PackageManager;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * Entry guard for the exported action receivers that live inside SystemUI and system_server.
 *
 * <p>Those receivers are registered by the module, not declared in the host manifest, so they
 * cannot rely on a manifest permission. Two layers are used instead:
 * <ol>
 *   <li>registration requires {@link #PERMISSION}, a {@code signature} permission declared by the
 *       module package - an unrelated application does not hold it and its broadcast is dropped by
 *       the system before our code runs;</li>
 *   <li>the receiver re-checks the sending uid against the module signature, which also covers
 *       ROMs that ignore the registration permission.</li>
 * </ol>
 */
public final class BroadcastSenderGuard {

    /** Signature permission declared in the module manifest; only same-signature apps hold it. */
    public static final String PERMISSION = "com.sevtinge.hyperceiler.permission.SEND_ACTION";

    private static final String MODULE_PACKAGE = "com.sevtinge.hyperceiler";
    private static final int UID_ROOT = 0;
    private static final int UID_SYSTEM = 1000;
    private static final int UID_SHELL = 2000;

    private static volatile Method getSendingUid;

    private BroadcastSenderGuard() {
    }

    /**
     * @return true when the broadcast really comes from this module (or from the system/ADB, which
     * already outrank the module and are used by the in-app diagnostics).
     */
    public static boolean isFromModule(BroadcastReceiver receiver, Context context) {
        int uid = sendingUid(receiver);
        if (uid < 0) {
            // Older platforms cannot report the sender; the registration permission still gates
            // the broadcast, so there is nothing stronger to check here.
            return true;
        }
        if (uid == UID_ROOT || uid == UID_SYSTEM || uid == UID_SHELL) {
            return true;
        }
        if (uid == Process.myUid()) {
            return true;
        }
        try {
            int moduleUid = context.getPackageManager().getPackageUid(MODULE_PACKAGE, 0);
            return context.getPackageManager().checkSignatures(uid, moduleUid)
                == PackageManager.SIGNATURE_MATCH;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int sendingUid(BroadcastReceiver receiver) {
        if (receiver == null) {
            return -1;
        }
        try {
            Method method = getSendingUid;
            if (method == null) {
                method = BroadcastReceiver.class.getMethod("getSendingUid");
                getSendingUid = method;
            }
            return (Integer) method.invoke(receiver);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
