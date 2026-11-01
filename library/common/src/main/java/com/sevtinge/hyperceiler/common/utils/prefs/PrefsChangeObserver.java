package com.sevtinge.hyperceiler.common.utils.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.api.ProjectApi;

import java.util.Map;
import java.util.Set;

public class PrefsChangeObserver extends ContentObserver implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String SHARED_PREFS_AUTHORITY = ProjectApi.mAppModulePkg + ".provider.sharedprefs";

    private final Context context;
    private final boolean autoApplyChange;
    private final PrefType prefType;
    private final Object def;
    private final String name;
    private final Handler handler;
    private final boolean remoteListenerRegistered;
    private SharedPreferences remotePrefs;
    private volatile boolean closed;

    public PrefsChangeObserver(Context context, Handler handler) {
        this(context, handler, false, PrefType.Any, null, null);
    }

    public PrefsChangeObserver(Context context, Handler handler, boolean autoApplyChange, String name) {
        this(context, handler, autoApplyChange, PrefType.StringSet, name, null);
    }

    public PrefsChangeObserver(Context context, Handler handler, PrefType type, String name, Object def) {
        this(context, handler, false, type, name, def);
    }

    public PrefsChangeObserver(Context context, Handler handler, boolean autoApplyChange, PrefType type, String name, Object def) {
        super(handler);
        this.context = context;
        this.handler = handler;
        this.def = def;
        // 统一成物理/远端存储中的真实 key，兼容仍然传裸 key 的调用点
        this.name = PrefToUri.normalizeName(name);
        prefType = type;
        this.autoApplyChange = autoApplyChange;
        remoteListenerRegistered = registerRemoteListenerIfAvailable();

        Uri uri = resolveObserverUri(type, this.name);
        if (uri != null) {
            // Keep the old ContentObserver registration so the platform owns this observer's lifecycle.
            // Hook-side refresh still comes from remote prefs listener when available.
            context.getContentResolver().registerContentObserver(uri, type == PrefType.Any, this);
        }
    }

    private Uri resolveObserverUri(PrefType type, String name) {
        Uri uri = null;
        switch (type) {
            case Any -> uri = PrefToUri.anyPrefToUri();
            case String -> uri = PrefToUri.stringPrefToUri(name);
            case StringSet -> uri = PrefToUri.stringSetPrefToUri(name);
            case Integer -> uri = PrefToUri.intPrefToUri(name);
            case Boolean -> uri = PrefToUri.boolPrefToUri(name);
            case Long -> uri = PrefToUri.longPrefToUri(name);
            case Float -> uri = PrefToUri.floatPrefToUri(name);
        }
        return uri;
    }

    private boolean registerRemoteListenerIfAvailable() {
        remotePrefs = getRemotePrefsForHook();
        if (remotePrefs == null) {
            return false;
        }
        remotePrefs.registerOnSharedPreferenceChangeListener(this);
        return true;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        if (closed || selfChange || remoteListenerRegistered) return;
        if (prefType == PrefType.Any) {
            if (uri == null || uri.getPathSegments().size() < 3) return;
            dispatchChange(switch (uri.getPathSegments().get(1)) {
                case "string" -> PrefType.String;
                case "stringset" -> PrefType.StringSet;
                case "integer" -> PrefType.Integer;
                case "boolean" -> PrefType.Boolean;
                case "long" -> PrefType.Long;
                case "float" -> PrefType.Float;
                default -> PrefType.Any;
            }, uri.getPathSegments().get(2), uri);
            return;
        }
        dispatchChange(prefType, name, uri);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (closed || key == null) {
            return;
        }
        if (prefType != PrefType.Any && !PrefToUri.normalizeName(key).equals(name)) {
            return;
        }
        dispatchChange(resolveRemotePrefType(sharedPreferences, key), key, null);
    }

    /**
     * 注销 ContentObserver 与远端 SharedPreferences 监听，重复调用安全。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            context.getContentResolver().unregisterContentObserver(this);
        } catch (Throwable ignored) {
            // 宿主已注销或进程正在退出时忽略
        }
        SharedPreferences prefs = remotePrefs;
        if (prefs != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(this);
            } catch (Throwable ignored) {
                // 远端句柄失效时忽略
            }
            remotePrefs = null;
        }
    }

    public void onChange(PrefType type, Uri uri, String name, Object def) {
    }

    private void dispatchChange(PrefType type, String changedName, Uri uri) {
        if (closed) {
            return;
        }
        Runnable callback = () -> {
            if (closed) {
                return;
            }
            if (autoApplyChange && prefType != PrefType.Any) {
                applyChange();
            }
            onChange(type, uri, changedName, def);
        };
        if (handler != null) {
            handler.post(callback);
        } else {
            callback.run();
        }
    }

    private void applyChange() {
        PrefsBridge.removeHookCache(name);
        PrefsBridge.putHookCache(name, switch (prefType) {
            case String -> PrefsBridge.getString(name, (String) def);
            case StringSet -> PrefsBridge.getStringSet(name);
            case Integer -> PrefsBridge.getInt(name, (Integer) def);
            case Boolean -> PrefsBridge.getBoolean(name, (boolean) def);
            case Long -> PrefsBridge.getLong(name, def instanceof Long longDef ? longDef : 0L);
            case Float -> PrefsBridge.getFloat(name, def instanceof Float floatDef ? floatDef : 0f);
            // Any 无法确定单个 key 的类型，交由 ContentObserver 通知，这里不刷新缓存。
            default -> null;
        });
    }

    private SharedPreferences getRemotePrefsForHook() {
        if (!PrefsBridge.isHookProcess()) {
            return null;
        }
        try {
            return PrefsBridge.getSharedPreferences();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private PrefType resolveRemotePrefType(SharedPreferences sharedPreferences, String key) {
        if (prefType != PrefType.Any) {
            return prefType;
        }
        Map<String, ?> all = sharedPreferences.getAll();
        Object value = all.get(key);
        if (value instanceof String) return PrefType.String;
        if (value instanceof Set<?>) return PrefType.StringSet;
        if (value instanceof Integer) return PrefType.Integer;
        if (value instanceof Long) return PrefType.Long;
        if (value instanceof Float) return PrefType.Float;
        if (value instanceof Boolean) return PrefType.Boolean;
        return PrefType.Any;
    }

    public static class PrefToUri {
        private static final String PREF_KEY_PREFIX = "prefs_key_";

        private static String normalizeName(String name) {
            if (name == null || name.isEmpty() || name.startsWith(PREF_KEY_PREFIX)) {
                return name;
            }
            return PREF_KEY_PREFIX + name;
        }

        public static Uri stringPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/string/" + normalizeName(name));
        }

        public static Uri stringSetPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/stringset/" + normalizeName(name));
        }

        public static Uri intPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/integer/" + normalizeName(name));
        }

        public static Uri boolPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/boolean/" + normalizeName(name));
        }

        public static Uri longPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/long/" + normalizeName(name));
        }

        public static Uri floatPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/float/" + normalizeName(name));
        }

        public static Uri shortcutIconPrefToUri(String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/shortcut_icon/" + normalizeName(name));
        }

        public static Uri anyPrefToUri() {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/pref");
        }

        public static Uri anyPrefToUri(PrefType type, String name) {
            return Uri.parse("content://" + SHARED_PREFS_AUTHORITY + "/pref/" + typeToPath(type) + "/" + normalizeName(name));
        }

        public static Uri prefToUri(PrefType type, String name) {
            return switch (type) {
                case String -> stringPrefToUri(name);
                case StringSet -> stringSetPrefToUri(name);
                case Integer -> intPrefToUri(name);
                case Boolean -> boolPrefToUri(name);
                case Long -> longPrefToUri(name);
                case Float -> floatPrefToUri(name);
                // Any 没有具体类型 Uri，回退到根 Uri，注册时按整棵子树监听。
                default -> anyPrefToUri();
            };
        }

        private static String typeToPath(PrefType type) {
            return switch (type) {
                case String -> "string";
                case StringSet -> "stringset";
                case Integer -> "integer";
                case Boolean -> "boolean";
                case Long -> "long";
                case Float -> "float";
                // Any 在 URI 路径中没有独立类型段，统一落到 pref。
                default -> "pref";
            };
        }
    }
}
