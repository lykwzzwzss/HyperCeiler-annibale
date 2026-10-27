package com.sevtinge.hyperceiler.libhook.rules.packageinstaller;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallerWarningPolicyTest {
    @Test public void skipsOnlyTheInformationalAnonymousSourceDialog() {
        assertTrue(InstallerWarningPolicy.skipAnonymousSourceDialog(10));
        for (int dialog : new int[]{-1, 0, 6, 9, 11, 12, 13}) {
            assertFalse("Must preserve restriction/authorization dialog " + dialog,
                InstallerWarningPolicy.skipAnonymousSourceDialog(dialog));
        }
    }

    @Test public void acceptsOnlyTheExactOfflineReminderEvent() {
        assertTrue(InstallerWarningPolicy.isOfflineUnregisteredWarning("offline_unregistered_install_popup"));
        for (String event : new String[]{null, "", "unregistered_install_popup",
            "offline_unregistered_install_popup_continue_btn", "risk_popup"}) {
            assertFalse(InstallerWarningPolicy.isOfflineUnregisteredWarning(event));
        }
    }
}
