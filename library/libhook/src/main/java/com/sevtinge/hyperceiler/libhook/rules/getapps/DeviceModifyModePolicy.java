package com.sevtinge.hyperceiler.libhook.rules.getapps;

import java.util.Map;

/** Pure preference resolution for the GetApps device-modification setting. */
public final class DeviceModifyModePolicy {
    public static final String CUSTOM_SWITCH_KEY = "prefs_key_market_device_modify_new1";
    public static final String LEGACY_MODE_KEY = "prefs_key_market_device_modify_new";

    private DeviceModifyModePolicy() {}

    public static int resolveConfiguredMode(Map<String, ?> preferences) {
        Object customSwitch = preferences.get(CUSTOM_SWITCH_KEY);
        Boolean enabled = parseBoolean(customSwitch);
        if (enabled != null) return enabled ? 1 : 0;

        Object legacyMode = preferences.get(LEGACY_MODE_KEY);
        if (legacyMode instanceof Number number) return number.intValue();
        if (legacyMode instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static Boolean parseBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim())) return false;
        }
        return null;
    }
}
