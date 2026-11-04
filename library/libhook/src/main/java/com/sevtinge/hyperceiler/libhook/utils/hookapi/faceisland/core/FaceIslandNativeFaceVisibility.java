package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

public final class FaceIslandNativeFaceVisibility {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<View, Integer> REQUESTED = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static boolean suppressed;

    private FaceIslandNativeFaceVisibility() {
    }

    public static void record(View view, int requested) {
        if (view == null || APPLYING.get()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> record(view, requested));
            return;
        }
        REQUESTED.put(view, requested);
        apply(view, requested);
    }

    static void track(Object object) {
        if (object instanceof View view && !APPLYING.get()) {
            if (!REQUESTED.containsKey(view) || !suppressed) record(view, view.getVisibility());
            else apply(view, REQUESTED.get(view));
        }
    }

    public static void setSuppressed(boolean value) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> setSuppressed(value));
            return;
        }
        if (suppressed == value) return;
        suppressed = value;
        for (Map.Entry<View, Integer> entry : new ArrayList<>(REQUESTED.entrySet())) {
            if (entry.getKey() != null) apply(entry.getKey(), entry.getValue());
        }
    }

    public static void reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(FaceIslandNativeFaceVisibility::reset);
            return;
        }
        setSuppressed(false);
        REQUESTED.clear();
    }

    private static void apply(View view, int requested) {
        int visibility = suppressed && requested == View.VISIBLE ? View.INVISIBLE : requested;
        if (view.getVisibility() == visibility) return;
        APPLYING.set(true);
        try {
            try {
                FaceIslandReflect.call(view, "setVisibilityDirect", visibility);
            } catch (Exception unavailable) {
                view.setVisibility(visibility);
            }
        } finally {
            APPLYING.remove();
        }
    }
}
