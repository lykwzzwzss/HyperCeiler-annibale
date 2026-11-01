package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

final class FaceIslandResultRegistry<T> {
    private static final int MAX_RESULTS = 16;
    private final ArrayList<Binding<T>> entries = new ArrayList<>();

    FaceIslandResultRegistry() {
    }

    private static final class Binding<T> {
        final T request;
        final WeakReference<Object> result;

        Binding(Object result, T request) {
            this.result = new WeakReference<>(result);
            this.request = request;
        }
    }

    synchronized void remember(Object result, T request) {
        ArrayList<Binding<T>> arrayList;
        if (result == null || request == null) {
            return;
        }
        int i = this.entries.size() - 1;
        while (true) {
            arrayList = this.entries;
            if (i < 0) {
                break;
            }
            Object stored = arrayList.get(i).result.get();
            if (stored == null || stored == result) {
                this.entries.remove(i);
            }
            i--;
        }
        int i2 = arrayList.size();
        if (i2 >= MAX_RESULTS) {
            this.entries.remove(0);
        }
        this.entries.add(new Binding<>(result, request));
    }

    synchronized T find(Object result) {
        if (result == null) {
            return null;
        }
        for (int i = this.entries.size() - 1; i >= 0; i--) {
            Binding<T> binding = this.entries.get(i);
            Object stored = binding.result.get();
            if (stored == null) {
                this.entries.remove(i);
            } else if (stored == result) {
                return binding.request;
            }
        }
        return null;
    }
}
