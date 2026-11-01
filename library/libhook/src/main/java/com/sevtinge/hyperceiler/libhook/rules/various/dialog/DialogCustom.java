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
package com.sevtinge.hyperceiler.libhook.rules.various.dialog;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.BlurUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

public class DialogCustom extends BaseHook {
    // A panel may be updated many times for IME/insets/configuration changes.
    // Attach its background listener once, without retaining dismissed dialogs.
    private final WeakHashMap<View, Boolean> initializedPanels = new WeakHashMap<>();
    private int gravity;
    private int horizontalMargin;
    private int bottomMargin;

    @Override
    public void init() {
        Class<?> controller = findClassIfExists(getPackageName().equals("com.miui.home")
            ? "miui.home.lib.dialog.AlertController" : "miuix.appcompat.app.AlertController");
        if (controller == null) return;

        gravity = PrefsBridge.getStringAsInt("various_dialog_gravity", 0);
        horizontalMargin = PrefsBridge.getInt("various_dialog_margin_horizontal", 0);
        bottomMargin = PrefsBridge.getInt("various_dialog_margin_bottom", 0);
        registerHotReloadCleanup(initializedPanels::clear);

        if (PrefsBridge.getBoolean("various_dialog_window_blur")) {
            hookAllConstructors(controller, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object value = getObjectField(param.getThisObject(), "mWindow");
                    if (!(value instanceof Window window)) return;
                    WindowManager.LayoutParams attributes = window.getAttributes();
                    attributes.setBlurBehindRadius(PrefsBridge.getInt("various_dialog_window_blur_radius", 60));
                    window.setAttributes(attributes);
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                }
            });
        }

        // Keep the old callbacks, and cover current miuix including asynchronous
        // inflation and panel replacement. Only hook methods that are declared.
        Set<String> callbacks = Set.of("setupDialogPanel", "updateDialogPanel", "setupView",
            "updateDialogPanelLayoutParams", "updateParentPanelMarginByWindowInsets",
            "onAttachedToWindow");
        Field materialEnabled = null;
        if (PrefsBridge.getBoolean("various_dialog_bg_blur_custom_enable")) {
            try {
                materialEnabled = controller.getDeclaredField("mMaterialEnabled");
                materialEnabled.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                // Older miuix does not apply material effects to this background.
            }
        }
        Field material = materialEnabled;
        IMethodHook update = new IMethodHook() {
            @Override
            public void before(HookParam param) {
                // New miuix posts material styling after setupView and makes the
                // background transparent. Custom background owns this panel.
                if (material != null) {
                    try {
                        material.setBoolean(param.getThisObject(), false);
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }

            @Override
            public void after(HookParam param) {
                applyPanel(param.getThisObject());
            }
        };
        for (Method method : controller.getDeclaredMethods()) {
            if (callbacks.contains(method.getName())) hookMethod(method, update);
        }
    }

    private void applyPanel(Object controller) {
        Object value = getObjectField(controller, "mParentPanel");
        if (!(value instanceof View panel)) return;
        ViewGroup.LayoutParams params = panel.getLayoutParams();
        if (gravity != 0 && params instanceof FrameLayout.LayoutParams layout) {
            int side = DisplayUtils.dp2px(panel.getContext(), horizontalMargin);
            int bottom = gravity == 1 ? 0 : DisplayUtils.dp2px(panel.getContext(), bottomMargin);
            int targetGravity = gravity == 1 ? Gravity.CENTER : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            if (layout.width != ViewGroup.LayoutParams.MATCH_PARENT || layout.gravity != targetGravity
                || layout.getMarginStart() != side || layout.getMarginEnd() != side
                || layout.bottomMargin != bottom) {
                layout.width = ViewGroup.LayoutParams.MATCH_PARENT;
                layout.gravity = targetGravity;
                layout.setMarginStart(side);
                layout.setMarginEnd(side);
                layout.bottomMargin = bottom;
                panel.setLayoutParams(layout);
            }
        }
        if (!initializedPanels.containsKey(panel)) {
            initializedPanels.put(panel, Boolean.TRUE);
            new BlurUtils(panel, "various_dialog_bg_blur");
        }
    }
}
