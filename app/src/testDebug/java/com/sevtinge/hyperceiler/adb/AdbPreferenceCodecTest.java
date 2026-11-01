package com.sevtinge.hyperceiler.adb;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class AdbPreferenceCodecTest {
    @Test public void onlyShellRootAndTheAppMayCall() {
        assertTrue(AdbPreferenceCodec.isAllowedUid(0, 10298));
        assertTrue(AdbPreferenceCodec.isAllowedUid(2000, 10298));
        assertTrue(AdbPreferenceCodec.isAllowedUid(10298, 10298));
        assertFalse(AdbPreferenceCodec.isAllowedUid(10123, 10298));
        assertFalse(AdbPreferenceCodec.isAllowedUid(1000, 10298));
    }

    @Test public void normalizesKeysAndProtectsFrameworkState() {
        assertEquals("prefs_key_feature", AdbPreferenceCodec.key("feature"));
        assertEquals("prefs_key_feature", AdbPreferenceCodec.key("prefs_key_feature"));
        for (String key : new String[]{"allow_hook", "framework_api_allow_hook", "framework_check_reason", "../bad"}) {
            assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.writableKey(key));
        }
    }

    @Test public void preservesExistingTypesIncludingSmallLongsAndFloat() {
        Map<String, Object> changes = AdbPreferenceCodec.batch(
            "{\"enabled\":true,\"mode\":\"2\",\"number\":25,\"clock\":5,\"scale\":1.25}",
            Map.of("prefs_key_enabled", false, "prefs_key_mode", "0", "prefs_key_number", 20,
                "prefs_key_clock", 0L, "prefs_key_scale", 1f));
        assertEquals(true, changes.get("prefs_key_enabled"));
        assertEquals("2", changes.get("prefs_key_mode"));
        assertEquals(Integer.class, changes.get("prefs_key_number").getClass());
        assertEquals(Long.class, changes.get("prefs_key_clock").getClass());
        assertEquals(Float.class, changes.get("prefs_key_scale").getClass());
    }

    @Test public void refusesTypeConfusionBeforeReturningAnyBatch() {
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch(
            "{\"first\":true,\"mode\":2}", Map.of("prefs_key_mode", "0")));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("boolean", "true", "false"));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("boolean", "\"true\"", false));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("boolean", "null", false));
    }

    @Test public void validatesNumericBoundsWithoutTruncating() {
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("int", "2147483648", null));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("int", "1.5", null));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.single("float", "1e100", null));
        assertEquals(25, AdbPreferenceCodec.single("int", "25", null));
        assertEquals(25L, AdbPreferenceCodec.single("long", "25", null));
    }

    @Test public void supportsNewKeysUnicodeStringSetsAndRemoval() {
        Map<String, Object> changes = AdbPreferenceCodec.batch(
            "{\"feature\":true,\"text\":\"中文 '$() \\\"quoted\\\"\",\"apps\":[\"a\",\"b\",\"a\"],\"old\":null}", Map.of());
        assertEquals(true, changes.get("prefs_key_feature"));
        assertEquals("中文 '$() \"quoted\"", changes.get("prefs_key_text"));
        assertEquals(Set.of("a", "b"), changes.get("prefs_key_apps"));
        assertTrue(changes.containsKey("prefs_key_old"));
        assertNull(changes.get("prefs_key_old"));
    }

    @Test public void rejectsAmbiguousOrInvalidBatches() {
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch("[]", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch("{\"a\":{}}", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch("{\"a\":[1]}", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch("{\"a\":true,\"prefs_key_a\":false}", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch("{\"a\":1.25}", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> AdbPreferenceCodec.batch(" ".repeat(65_537), Map.of()));
    }
}
