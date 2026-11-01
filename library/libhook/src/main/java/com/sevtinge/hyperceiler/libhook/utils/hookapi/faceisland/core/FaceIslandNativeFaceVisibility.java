package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.view.View;

public final class FaceIslandNativeFaceVisibility {
    private static final int BOUNCER_FACE_VIEW_ID = 2131429205;

    private FaceIslandNativeFaceVisibility() {
    }

    public static int resolve(Object owner, int requested, int source) {
        try {
            if (owner instanceof View) {
                if (((View) owner).getId() == BOUNCER_FACE_VIEW_ID && requested == 0) {
                    return 4;
                }
            }
        } catch (Throwable th) {
        }
        return requested;
    }
}
