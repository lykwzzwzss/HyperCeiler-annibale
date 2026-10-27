package com.sevtinge.hyperceiler.adb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sevtinge.hyperceiler.BuildConfig;
import com.sevtinge.hyperceiler.common.utils.AppSettingsStore;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** ADB content-call endpoint. Never grants preference access to ordinary applications. */
public final class AdbSettingsProvider extends ContentProvider {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @Override
    public boolean onCreate() {
        return true; // Application.attachBaseContext already initialized PrefsBridge.
    }

    @NonNull
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        // ContentProvider.call does not automatically enforce the manifest read/write permission.
        if (!AdbPreferenceCodec.isAllowedUid(Binder.getCallingUid(), Process.myUid())) {
            throw new SecurityException("Only ADB shell/root may access HyperCeiler ADB settings");
        }
        synchronized (AdbSettingsProvider.class) {
            long identity = Binder.clearCallingIdentity();
            try {
                Bundle reply = execute(method, arg, extras == null ? Bundle.EMPTY : extras);
                reply.putBoolean("remote_bound", PrefsBridge.isRemoteBound());
                reply.putBoolean("remote_synced", PrefsBridge.areRemotePrefsSynced());
                return reply;
            } catch (IllegalArgumentException | IllegalStateException | JsonParseException e) {
                Bundle reply = new Bundle();
                reply.putBoolean("ok", false);
                reply.putString("error", e.getMessage());
                return reply;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private Bundle execute(String method, String arg, Bundle extras) {
        Map<String, ?> current = PrefsBridge.getAll();
        Map<String, Object> data = new LinkedHashMap<>();
        boolean write = false;
        switch (method) {
            case "status" -> {
                data.put("api", 1);
                data.put("version_code", BuildConfig.VERSION_CODE);
                data.put("version_name", BuildConfig.VERSION_NAME);
                data.put("key_count", current.size());
            }
            case "get" -> {
                String key = AdbPreferenceCodec.key(arg);
                data.put("key", key);
                data.put("exists", current.containsKey(key));
                data.put("type", AdbPreferenceCodec.type(current.get(key)));
                data.put("value", current.get(key));
            }
            case "list" -> {
                String prefix = arg == null || arg.isEmpty() ? "" : AdbPreferenceCodec.key(arg);
                Map<String, Object> entries = new TreeMap<>();
                Map<String, String> types = new TreeMap<>();
                current.forEach((key, value) -> {
                    if (key.startsWith(prefix)) {
                        entries.put(key, value);
                        types.put(key, AdbPreferenceCodec.type(value));
                    }
                });
                data.put("entries", entries);
                data.put("types", types);
            }
            case "set", "remove", "apply" -> {
                Map<String, Object> changes;
                if (method.equals("apply")) {
                    String payload = extras.getString("payload");
                    if (payload == null || payload.length() > 90_000) {
                        throw new IllegalArgumentException("Missing or oversized Base64 payload");
                    }
                    changes = AdbPreferenceCodec.batch(new String(Base64.decode(payload, Base64.DEFAULT),
                        StandardCharsets.UTF_8), current);
                } else {
                    String key = AdbPreferenceCodec.writableKey(arg);
                    changes = new LinkedHashMap<>();
                    String value = extras.getString("value");
                    if (extras.containsKey("value64")) {
                        String encoded = extras.getString("value64");
                        if (encoded == null || encoded.length() > 90_000) {
                            throw new IllegalArgumentException("Missing or oversized value64");
                        }
                        value = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    }
                    changes.put(key, method.equals("remove") ? null : AdbPreferenceCodec.single(
                        extras.getString("type"), value, current.get(key)));
                }
                if (!PrefsBridge.applyByApp(changes)) {
                    throw new IllegalStateException("Local preference commit failed");
                }
                AppSettingsStore.syncGlobalAfterChanges(getContext(), changes);
                data.put("changed_keys", changes.keySet());
                data.put("local_saved", true);
                // Hook initialization is generally conditional and cached until process reload.
                data.put("restart_required", !changes.isEmpty());
                write = true;
            }
            default -> throw new IllegalArgumentException("Methods: status, get, list, set, remove, apply");
        }
        boolean bound = PrefsBridge.isRemoteBound();
        boolean synced = PrefsBridge.areRemotePrefsSynced();
        data.put("remote_bound", bound);
        data.put("remote_synced", synced);
        if (write && !synced) {
            data.put("note", bound ? "Saved locally; remote sync failed. Retry after reopening HyperCeiler."
                : "Saved locally; pending Xposed service connection. Open HyperCeiler, then check status.");
        }
        Bundle reply = new Bundle();
        reply.putBoolean("ok", !write || !bound || synced);
        reply.putString("data", Base64.encodeToString(JSON.toJson(data).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        return reply;
    }

    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
        @Nullable String selection, @Nullable String[] args, @Nullable String sort) {
        throw new UnsupportedOperationException("Use content call");
    }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Use content call");
    }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] args) {
        throw new UnsupportedOperationException("Use content call");
    }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
        @Nullable String selection, @Nullable String[] args) {
        throw new UnsupportedOperationException("Use content call");
    }
}
