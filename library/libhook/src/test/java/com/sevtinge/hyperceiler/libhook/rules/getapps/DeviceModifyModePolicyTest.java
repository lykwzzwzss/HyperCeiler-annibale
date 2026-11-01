package com.sevtinge.hyperceiler.libhook.rules.getapps;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class DeviceModifyModePolicyTest {
    @Test
    public void preservesLegacyPresetModesStoredAsStringsOrIntegers() {
        assertEquals(315, DeviceModifyModePolicy.resolveConfiguredMode(
            Map.of(DeviceModifyModePolicy.LEGACY_MODE_KEY, "315")));
        assertEquals(105, DeviceModifyModePolicy.resolveConfiguredMode(
            Map.of(DeviceModifyModePolicy.LEGACY_MODE_KEY, 105)));
    }

    @Test
    public void preservesLegacyCustomMode() {
        assertEquals(1, DeviceModifyModePolicy.resolveConfiguredMode(
            Map.of(DeviceModifyModePolicy.LEGACY_MODE_KEY, "1")));
    }

    @Test
    public void explicitNewSwitchOverridesLegacyMode() {
        assertEquals(1, DeviceModifyModePolicy.resolveConfiguredMode(Map.of(
            DeviceModifyModePolicy.CUSTOM_SWITCH_KEY, true,
            DeviceModifyModePolicy.LEGACY_MODE_KEY, "315")));
        assertEquals(0, DeviceModifyModePolicy.resolveConfiguredMode(Map.of(
            DeviceModifyModePolicy.CUSTOM_SWITCH_KEY, false,
            DeviceModifyModePolicy.LEGACY_MODE_KEY, 315)));
    }

    @Test
    public void malformedValuesFallBackSafely() {
        assertEquals(315, DeviceModifyModePolicy.resolveConfiguredMode(Map.of(
            DeviceModifyModePolicy.CUSTOM_SWITCH_KEY, "invalid",
            DeviceModifyModePolicy.LEGACY_MODE_KEY, "315")));
        assertEquals(0, DeviceModifyModePolicy.resolveConfiguredMode(
            Map.of(DeviceModifyModePolicy.LEGACY_MODE_KEY, "not-a-mode")));
        assertEquals(0, DeviceModifyModePolicy.resolveConfiguredMode(
            Map.of(DeviceModifyModePolicy.LEGACY_MODE_KEY, true)));
    }
}
