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
package com.sevtinge.hyperceiler.libhook.rules.systemui.plugin.systemui;

import static com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookConstructor;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class QSColor extends BaseHook {
    /**
     * 资源 id / 尺寸的“未初始化”哨兵值。
     * 统一使用 0（而非 -1），因为 {@code Resources.getIdentifier()} 找不到资源时返回的就是 0，
     * 用 -1 会产生两套哨兵语义并在比较时漏判。
     */
    private static final int UNINITIALIZED = 0;
    /**
     * 颜色偏好的“未设置”默认值。这里保留 -1（不透明白）而不是 {@link #UNINITIALIZED}，
     * 因为它只在用户未选色时作为实际颜色使用，从不参与等值判断，改成 0 会让图标变成透明。
     */
    private static final int COLOR_UNSET = -1;

    private static boolean small = false;
    private static int bgColor = COLOR_UNSET;
    private static int color = COLOR_UNSET;
    private static boolean big = false;

    private static int bigBgColor = COLOR_UNSET;
    private static int bigColor = COLOR_UNSET;

    public static void pluginHook(ClassLoader classLoader) {
        String TAG = "QSColor";
        load();
        if (small) {
            try {
                findAndHookConstructor("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", classLoader, Context.class, Context.class, AttributeSet.class, new IMethodHook() {
                    @Override
                    public void after(HookParam param) {
                        setObjectReplacement("miui.systemui.plugin", "color", "qs_icon_enabled_color", color);
                    }
                });
            } catch (Exception | Error ignore) {
                findAndHookConstructor("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", classLoader, Context.class, Context.class, boolean.class, new IMethodHook() {
                    @Override
                    public void after(HookParam param) {
                        setObjectReplacement("miui.systemui.plugin", "color", "qs_icon_enabled_color", color);
                    }
                });
            }

            // from YunZiA
            com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", classLoader, "getActiveBackgroundDrawable", "com.android.systemui.plugins.qs.QSTile$State", new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    // 先判空/判类型再强转，避免宿主返回其它类型时在回调里抛 CCE
                    Object result = param.getResult();
                    if (result instanceof GradientDrawable) {
                        GradientDrawable drawable = (GradientDrawable) result;
                        drawable.setColor(bgColor);
                        param.setResult(drawable);
                    }
                }
            });

            // @deprecated 16.0.4.83.0
            try {
                com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", classLoader, "updateIcon", "com.android.systemui.plugins.qs.QSTile$State", boolean.class, boolean.class, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(param.getThisObject(), "iconColor", color);
                    }
                });
            } catch (Throwable ignore) {}
        }

        if (big) {
            findAndHookConstructor("miui.systemui.controlcenter.qs.tileview.QSCardItemIconView", classLoader, Context.class, Context.class, AttributeSet.class, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    setObjectReplacement("miui.systemui.plugin", "color", "qs_icon_enabled_color", bigColor);
                }
            });

            findAndHookConstructor("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader, Context.class, AttributeSet.class, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    try {
                        LinearLayout linearLayout = (LinearLayout) param.getThisObject();
                        if (linearLayout == null) return;
                        Context context = linearLayout.getContext();
                        if (context == null) return;
                        // getIdentifier 找不到资源时返回 0，直接交给 getDimensionPixelSize/getDrawable 会抛
                        // Resources$NotFoundException，因此先判 0 再使用。
                        int cornerRadius = context.getResources().getIdentifier("control_center_universal_corner_radius", "dimen", "miui.systemui.plugin");
                        cornerRadiusF = cornerRadius != UNINITIALIZED
                            ? context.getResources().getDimensionPixelSize(cornerRadius)
                            : UNINITIALIZED;
                        id = context.getResources().getIdentifier("qs_card_wifi_background_enabled", "drawable", "miui.systemui.plugin");
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "QSCardItemView ctor init res failed: " + e);
                    }
                }
            });

            com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader, "updateBackground", new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    try {
                        Object state = com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(param.getThisObject(), "state");
                        // String spec = (String) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(state, "spec");
                        if (state == null) return; // 系统界面组件会先 null 几次才会获取到值，应该是官方写法有问题
                        int i = com.sevtinge.hyperceiler.libhook.base.BaseHook.getIntField(state, "state");
                        LinearLayout linearLayout = (LinearLayout) param.getThisObject();
                        if (linearLayout == null) return;
                        if (i == 2) {
                            // 0 表示资源解析失败（未初始化哨兵值），此时不能用于 getDrawable。
                            if (id == UNINITIALIZED) {
                                XposedLog.w(TAG, "QSCardItemView background res id unresolved, skip");
                                return;
                            }
                            Drawable drawable = linearLayout.getContext().getTheme().getResources().getDrawable(id, linearLayout.getContext().getTheme());
                            if (drawable == null) return;
                            drawable.setTint(bigBgColor);
                            linearLayout.setBackground(drawable);
                        }
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "QSCardItemView updateBackground failed: " + e);
                    }
                }
            });

            com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader, "setCornerRadius", float.class, new IMethodHook() {
                @Override
                public void before(HookParam param) {
                    if (cornerRadiusF != UNINITIALIZED) {
                        param.getArgs()[0] = cornerRadiusF;
                    }
                }
            });

            com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSCardItemView", classLoader, "updateState", "com.android.systemui.plugins.qs.QSTile$State", boolean.class, boolean.class, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object state = param.getArgs() != null && param.getArgs().length > 0 ? param.getArgs()[0] : null;
                    if (state == null) return;
                    try {
                        int i = com.sevtinge.hyperceiler.libhook.base.BaseHook.getIntField(state, "state");
                        if (i != 2) return;
                        Object thisObject = param.getThisObject();
                        if (!(thisObject instanceof LinearLayout)) return;
                        Context context = ((LinearLayout) thisObject).getContext();
                        if (context == null) return;

                        TextView textView = null;
                        TextView textView1 = null;
                        // getIdentifier 返回 0 时不能传给 _$_findCachedViewById，避免 Resources$NotFoundException。
                        int title = context.getResources().getIdentifier("title", "id", "miui.systemui.plugin");
                        int status = context.getResources().getIdentifier("status", "id", "miui.systemui.plugin");
                        if (title != UNINITIALIZED && status != UNINITIALIZED) {
                            try {
                                textView = (TextView) com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(thisObject, "_$_findCachedViewById", title);
                                textView1 = (TextView) com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(thisObject, "_$_findCachedViewById", status);
                            } catch (Throwable ignore) {
                                textView = null;
                                textView1 = null;
                            }
                        }
                        if (textView == null || textView1 == null) {
                            // 17.0.1.19.4 变更
                            Object binding = com.sevtinge.hyperceiler.libhook.base.BaseHook.callMethod(thisObject, "getBinding");
                            if (binding == null) return;
                            if (textView == null) {
                                textView = (TextView) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(binding, "title");
                            }
                            if (textView1 == null) {
                                textView1 = (TextView) com.sevtinge.hyperceiler.libhook.base.BaseHook.getObjectField(binding, "status");
                            }
                        }
                        if (textView != null) textView.setTextColor(bigColor);
                        if (textView1 != null) textView1.setTextColor(bigColor);
                    } catch (Throwable e) {
                        XposedLog.w(TAG, "QSCardItemView updateState failed: " + e);
                    }
                }
            });

            // @deprecated unknown version
            try {
                com.sevtinge.hyperceiler.libhook.base.BaseHook.findAndHookMethod("miui.systemui.controlcenter.qs.tileview.QSCardItemIconView", classLoader, "setIcon", "com.android.systemui.plugins.qs.QSTile$State", boolean.class, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(param.getThisObject(), "iconColor", bigColor);
                    }
                });
            } catch (Throwable ignore) {}
        }
    }

    // 0 为“未初始化/解析失败”哨兵值，与 Resources.getIdentifier() 的失败返回值保持一致
    public static int id = UNINITIALIZED;
    public static float cornerRadiusF = UNINITIALIZED;

    public static void load() {
        small = PrefsBridge.getBoolean("system_ui_control_center_qs_open_color");
        big = PrefsBridge.getBoolean("system_ui_control_center_qs_big_open_color");
        // 颜色偏好默认值见 COLOR_UNSET 注释（非哨兵，故仍为 -1）
        bgColor = PrefsBridge.getInt("system_ui_control_center_qs_bg_color", COLOR_UNSET);
        color = PrefsBridge.getInt("system_ui_control_center_qs_color", COLOR_UNSET);
        bigBgColor = PrefsBridge.getInt("system_ui_control_center_qs_big_bg_color", COLOR_UNSET);
        bigColor = PrefsBridge.getInt("system_ui_control_center_qs_big_color", COLOR_UNSET);
    }

    @Override
    public void init() {
        load();
        if (small) {
            findAndHookConstructor("com.android.systemui.qs.tileimpl.MiuiQSIconViewImpl", Context.class, new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    com.sevtinge.hyperceiler.libhook.base.BaseHook.setObjectField(param.getThisObject(), "mIconColorEnabled", color);
                    setObjectReplacement("com.android.systemui", "color", "qs_tile_icon_enabled_color", color);
                }
            });
        }
    }
}
