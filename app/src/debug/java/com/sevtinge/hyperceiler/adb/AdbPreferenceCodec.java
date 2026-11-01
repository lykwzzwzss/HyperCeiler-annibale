package com.sevtinge.hyperceiler.adb;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Transport-independent validation, shared by single writes and JSON batches. */
final class AdbPreferenceCodec {
    static final int MAX_JSON_CHARS = 65_536;
    static final int MAX_CHANGES = 512;

    private AdbPreferenceCodec() {}

    static boolean isAllowedUid(int caller, int appUid) {
        return caller == 0 || caller == 2000 || caller == appUid;
    }

    static String key(String raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Missing key");
        String key = raw.startsWith("prefs_key_") ? raw : "prefs_key_" + raw;
        if (key.length() > 240 || !key.matches("prefs_key_[A-Za-z0-9_.$:-]+")) {
            throw new IllegalArgumentException("Invalid preference key");
        }
        return key;
    }

    static String writableKey(String raw) {
        String key = key(raw);
        if (key.equals("prefs_key_allow_hook") || key.equals("prefs_key_framework_api_allow_hook")
            || key.startsWith("prefs_key_framework_check_")) {
            throw new IllegalArgumentException("Framework runtime state is read-only: " + key);
        }
        return key;
    }

    static String type(Object value) {
        if (value == null) return "absent";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof String) return "string";
        if (value instanceof Set<?>) return "string_set";
        throw new IllegalArgumentException("Unsupported stored type");
    }

    static Map<String, Object> batch(String json, Map<String, ?> current) {
        if (json == null || json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Missing JSON or JSON exceeds 65536 characters");
        }
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || root.getAsJsonObject().size() > MAX_CHANGES) {
            throw new IllegalArgumentException("Expected an object with at most 512 preference keys");
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
            String key = writableKey(entry.getKey());
            if (changes.containsKey(key)) throw new IllegalArgumentException("Duplicate key: " + key);
            JsonElement value = entry.getValue();
            String expected = type(current.get(key));
            if (expected.equals("absent")) expected = inferType(value);
            changes.put(key, decode(value, expected));
        }
        return changes;
    }

    static Object single(String requestedType, String value, Object current) {
        String expected = type(current);
        String selected = requestedType == null ? expected : requestedType;
        if (selected.equals("absent")) throw new IllegalArgumentException("New keys require type");
        if (!expected.equals("absent") && !expected.equals(selected)) {
            throw new IllegalArgumentException("Stored type is " + expected + "; remove the key before changing its type");
        }
        if (value == null) throw new IllegalArgumentException("Missing value");
        if (value.length() > MAX_JSON_CHARS) throw new IllegalArgumentException("Value too long");
        JsonElement parsed = selected.equals("string") ? new JsonPrimitive(value) : JsonParser.parseString(value);
        if (parsed.isJsonNull()) throw new IllegalArgumentException("Use remove to delete a key");
        return decode(parsed, selected);
    }

    private static String inferType(JsonElement value) {
        if (value.isJsonNull()) return "absent";
        if (value.isJsonArray()) return "string_set";
        if (value.isJsonObject()) throw new IllegalArgumentException("Objects are not preference values");
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return "boolean";
        if (primitive.isString()) return "string";
        BigDecimal number = primitive.getAsBigDecimal();
        try {
            long integer = number.longValueExact();
            return integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE ? "int" : "long";
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("New fractional values require an explicit float type");
        }
    }

    private static Object decode(JsonElement value, String type) {
        if (value.isJsonNull()) return null;
        if (type.equals("string_set") && value.isJsonArray()) {
            Set<String> result = new LinkedHashSet<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("String sets accept only strings");
                }
                result.add(item.getAsString());
            }
            return result;
        }
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Value must have type " + type);
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (type.equals("boolean") && primitive.isBoolean()) return primitive.getAsBoolean();
        if (type.equals("string") && primitive.isString()) return primitive.getAsString();
        if (primitive.isNumber()) {
            try {
                return switch (type) {
                    case "int" -> primitive.getAsBigDecimal().intValueExact();
                    case "long" -> primitive.getAsBigDecimal().longValueExact();
                    case "float" -> {
                        float number = primitive.getAsFloat();
                        if (!Float.isFinite(number)) throw new IllegalArgumentException("Float must be finite");
                        yield number;
                    }
                    default -> throw new IllegalArgumentException("Expected " + type);
                };
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Number is not a valid " + type);
            }
        }
        throw new IllegalArgumentException("Value must have type " + type);
    }
}
