/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.systemui.superisland;

import android.content.res.Resources;
import android.view.View;

import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandHooks;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core.FaceIslandNativeFaceVisibility;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

/**
 * 隐藏 MIUI 原生人脸图标，避免和超级岛里的脸重复。
 */
public class FaceIslandNativeFaceRule extends FaceIslandRuleBase {

    /** 旧版反编译里的 bouncer 人脸图标 id，资源名解析失败时回退到它。 */
    private static final int BOUNCER_FACE_VIEW_ID = 2131429205;
    /** 锁屏人脸图标的资源名候选，按顺序解析。 */
    private static final String[] FACE_VIEW_ID_NAMES = {
        "miui_keyguard_face_unlock_view", "keyguard_face_unlock_view", "keyguard_face_view"
    };

    @Override
    public void init() {
        if (!FaceIslandCompat.isSupportedDevice()) {
            return;
        }
        Class<?> binder = find("com.android.keyguard.interactor.KeyguardFaceViewBinder");
        if (binder != null) {
            hook(binder, "syncToFaceView", 0, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object container = getObjectField(param.getThisObject(), "faceContainerView");
                    Object face = getObjectField(param.getThisObject(), "faceView");
                    if (container != null && face != null) {
                        FaceIslandHooks.hideNativeFaceIcon(container, face);
                    }
                }
            });
        }

        Class<?> faceView = find("com.miui.keyguard.biometrics.faceunlock.MiuiKeyguardFaceUnlockView");
        if (faceView == null) {
            return;
        }
        IMethodHook visibility = new IMethodHook() {
            @Override
            public void after(HookParam param) {
                View view = (View) param.getThisObject();
                if (!isFaceView(view)) {
                    return;
                }
                Object[] args = argsOf(param);
                int requested = number(args, 0, -1);
                FaceIslandNativeFaceVisibility.record(view, requested);
            }
        };
        hook(faceView, "setVisibility", 1, visibility);
        hook(faceView, "setVisibilityDirect", 1, visibility);
    }

    private static boolean isFaceView(View view) {
        int id = view.getId();
        if (id <= 0) {
            return false;
        }
        return id == resolveFaceViewId(view) || id == BOUNCER_FACE_VIEW_ID;
    }

    private static int resolveFaceViewId(View view) {
        Resources resources = view.getResources();
        String packageName = view.getContext().getPackageName();
        for (String name : FACE_VIEW_ID_NAMES) {
            int id = resources.getIdentifier(name, "id", packageName);
            if (id != 0) {
                return id;
            }
        }
        return 0;
    }
}
