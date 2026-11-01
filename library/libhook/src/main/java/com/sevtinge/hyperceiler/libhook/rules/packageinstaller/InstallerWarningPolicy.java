package com.sevtinge.hyperceiler.libhook.rules.packageinstaller;

/** Boundaries for optional installer reminders; actual install/source permissions are unchanged. */
public final class InstallerWarningPolicy {
    private InstallerWarningPolicy() {}

    public static boolean skipAnonymousSourceDialog(int dialogId) {
        // InstallSourceManager: 9 is source authorization; 6/11/12 are restrictions.
        // Only 10 is the informational anonymous-source acknowledgement.
        return dialogId == 10;
    }

    public static boolean isOfflineUnregisteredWarning(String event) {
        return "offline_unregistered_install_popup".equals(event);
    }
}
