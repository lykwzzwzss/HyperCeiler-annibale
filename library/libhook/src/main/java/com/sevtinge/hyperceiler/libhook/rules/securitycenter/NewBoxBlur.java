/*
  * This file is part of HyperCeiler.

  * HyperCeiler is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as
  * published by the Free Software Foundation, either version 3 of the
  * License.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <https://www.gnu.org/licenses/>.

  * Copyright (C) 2023-2026 HyperCeiler Contributions
*/
package com.sevtinge.hyperceiler.libhook.rules.securitycenter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.BlurUtils;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class NewBoxBlur extends BaseHook {

    Class<?> mNewBoxCls;
    Class<?> mTurboaLayout;
    Class<?> mWindowManager;
    Class<?> mVideoBoxCls;


    Class<?> mTurboLayout;
    Class<?> mDockLayout;

    /** 目标类在新版本里可能被删掉，null 时直接跳过，避免整条 init 抛异常。 */
    private static void hook(Class<?> clazz, String method, Object... args) {
        if (clazz == null) {
            return;
        }
        findAndHookMethod(clazz, method, args);
    }

    private static void hookConstructors(Class<?> clazz, IMethodHook callback) {
        if (clazz == null) {
            return;
        }
        hookAllConstructors(clazz, callback);
    }

    @Override
    public void init() {

        mDockLayout = findClassIfExists("com.miui.gamebooster.windowmanager.newbox.j");
        mTurboLayout = findClassIfExists("com.miui.gamebooster.windowmanager.newbox.TurboLayout");

        mNewBoxCls = findClassIfExists("com.miui.gamebooster.windowmanager.newbox.i");
        mTurboaLayout = findClassIfExists("com.miui.gamebooster.windowmanager.newbox.NewToolBoxTopView");
        mWindowManager = findClassIfExists("com.miui.gamebooster.windowmanager.j");

        mVideoBoxCls = findClassIfExists("com.miui.gamebooster.videobox.adapter.i");


        hookConstructors(mDockLayout, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                /*
                 * 这个类在不同版本里并不一定是 View（本机 newbox.j 直接继承 Object），
                 * 强转会在游戏工具箱进程里必崩，所以先判定再处理。
                 */
                Object host = param.getThisObject();
                if (!(host instanceof ViewGroup view)) {
                    return;
                }
                int paddingVertical = DisplayUtils.dp2px(PrefsBridge.getInt("security_center_newbox_bg_padding_vertical", 10));
                int paddingHorizontal = DisplayUtils.dp2px(PrefsBridge.getInt("security_center_newbox_bg_padding_horizontal", 10));
                view.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
                new BlurUtils(view, "security_center_newbox_bg_custom");
            }
        });

        //
        hookConstructors(mTurboLayout, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object dock = callMethod(param.getThisObject(), "getDockLayout");
                if (!(dock instanceof ViewGroup view)) {
                    return;
                }
                new BlurUtils(view, "security_center_newbox_bg_custom");
            }
        });

        /*findAndHookConstructor(mNewBoxCls, Context.class, boolean.class, String.class, mWindowManager, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                View view = (View) param.getThisObject();
                new BlurUtils(view, "various_new_box_blur");

            }
        });*/


        hook(mTurboaLayout, "a", boolean.class, boolean.class, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object field = getObjectField(param.getThisObject(), "j");
                if (!(field instanceof ImageView view)) {
                    return;
                }
                GradientDrawable shapeDrawable = new GradientDrawable();
                shapeDrawable.setColor(Color.TRANSPARENT);
                view.setImageDrawable(shapeDrawable);
            }
        });

        hook(mTurboaLayout, "onAttachedToWindow", new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object host = param.getThisObject();
                if (!(host instanceof View view)) {
                    return;
                }
                new BlurUtils(view, "security_center_newbox_bg_custom");
            }
        });

        hook(mVideoBoxCls, "a", Context.class, boolean.class, new IMethodHook() {
            @Override
            public void after(HookParam param) {
                Object field = getObjectField(param.getThisObject(), "b");
                if (!(field instanceof ViewGroup viewGroup)) {
                    return;
                }
                new BlurUtils(viewGroup, "security_center_newbox_bg_custom");
            }
        });

    }
}
