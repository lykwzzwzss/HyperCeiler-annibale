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
package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.icon.all;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;

import java.util.ArrayList;
import java.util.List;

public class StatusBarIcon extends BaseHook {

    private static final String CLS_ICON_MANAGER_UTILS = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils";
    private static final String FIELD_RIGHT_BLOCK_LIST = "RIGHT_BLOCK_LIST";
    private static final String FIELD_CONTROL_CENTER_BLOCK_LIST = "CONTROL_CENTER_BLOCK_LIST";

    @Override
    public void init() {
        // from XiaomiHelper with GPL3
        Class<?> miuiIconManagerUtils = findClassIfExists(CLS_ICON_MANAGER_UTILS);
        if (miuiIconManagerUtils == null) {
            XposedLog.w(TAG, "MiuiIconManagerUtils not found, skip status bar icon rule");
            return;
        }

        List<String> hostStatusBarList;
        List<String> hostCtrlCenterList;
        try {
            hostStatusBarList = asStringList(getStaticObjectField(miuiIconManagerUtils, FIELD_RIGHT_BLOCK_LIST));
            hostCtrlCenterList = asStringList(getStaticObjectField(miuiIconManagerUtils, FIELD_CONTROL_CENTER_BLOCK_LIST));
        } catch (Throwable e) {
            XposedLog.w(TAG, "read host block lists failed: " + e);
            return;
        }
        if (hostStatusBarList == null || hostCtrlCenterList == null) {
            XposedLog.w(TAG, "host block lists missing, skip status bar icon rule");
            return;
        }

        // 复制成可变列表后再修改：所有增删都在副本上完成，
        // 中途不会把宿主的 static final 列表置于“改到一半”的状态。
        List<String> statusBarList = new ArrayList<>(hostStatusBarList);
        List<String> ctrlCenterList = new ArrayList<>(hostCtrlCenterList);

        hyperIconShowManager(statusBarList, ctrlCenterList);

        // 规则要生效必须让宿主看到最终结果：static final 引用无法重新指向，
        // 所以优先尝试整体赋值，失败再退回到“清空 + 填充”原 List 实例。
        applyBlockList(miuiIconManagerUtils, FIELD_RIGHT_BLOCK_LIST, hostStatusBarList, statusBarList);
        applyBlockList(miuiIconManagerUtils, FIELD_CONTROL_CENTER_BLOCK_LIST, hostCtrlCenterList, ctrlCenterList);
    }

    /** 只读取宿主列表内容做防御性拷贝，避免直接持有/增删宿主自己的 List 实例。 */
    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?>)) return null;
        List<?> source = (List<?>) value;
        List<String> copy = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof String) {
                copy.add((String) item);
            }
        }
        return copy;
    }

    private void applyBlockList(Class<?> holder, String fieldName, List<String> host, List<String> desired) {
        if (host.equals(desired)) return;
        try {
            // OS4 上该字段是 public static final，反射赋值大概率抛 IllegalAccessException
            setStaticObjectField(holder, fieldName, new ArrayList<>(desired));
            return;
        } catch (Throwable ignored) {
            // 落到下面的原地更新
        }
        try {
            host.clear();
            host.addAll(desired);
        } catch (Throwable e) {
            // 宿主用的是不可变集合时只能放弃，至少不要抛出到 SystemUI
            XposedLog.w(TAG, "apply " + fieldName + " failed (host list immutable?): " + e);
        }
    }

    private void hyperIconShowManager(List<String> statusBarList, List<String> ctrlCenterList) {
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_vpn", 0), "vpn", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_alarm_clock", 0), "alarm_clock", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_nfc", 0), "nfc", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_zen", 0), "zen", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_volume", 0), "volume", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_wifi", 0), "wifi", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_wifi", 0), "demo_wifi", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_airplane", 0), "airplane", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_location", 0), "location", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_mic", 0), "speakerphone", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_hotspot", 0), "hotspot", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_headset", 0), "headset", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_bluetooth", 0), "bluetooth", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_bluetooth_battery", 0), "bluetooth_handsfree_battery", statusBarList, ctrlCenterList);
        // setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_wifi_network_indicator", 0), "network_speed", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_mobile_network_signal_no_card", 0), "no_sim", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_new_hd", 0), "hd", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_car", 0), "car", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_pad", 0), "pad", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_pc", 0), "pc", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_phone", 0), "phone", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_soundbox", 0), "sound_box", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_soundbox_screen", 0), "sound_box_screen", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_soundbox_group", 0), "sound_box_group", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_soundbox_group_stereo", 0), "stereo", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_tv", 0), "tv", statusBarList, ctrlCenterList);
        setIcon(PrefsBridge.getStringAsInt("system_ui_status_bar_icon_wireless_headset", 0), "wireless_headset", statusBarList, ctrlCenterList);
    }

    private void setIcon(int value, String name, List<String> statusBarList, List<String> controlList) {
        // from XiaomiHelper with GPL3
        // statusBarList / controlList 已经是可变副本，这里可以安全地增删
        switch (value) {
            case 1 -> {
                statusBarList.remove(name);
                controlList.remove(name);
            }
            case 12 -> {
                statusBarList.remove(name);
                if (!controlList.contains(name)) controlList.add(name);
            }
            case 11 -> {
                if (!statusBarList.contains(name)) statusBarList.add(name);
                controlList.remove(name);
            }
            case 2 -> {
                if (!statusBarList.contains(name)) statusBarList.add(name);
                if (!controlList.contains(name)) controlList.add(name);
            }
            default -> {
            }
        }
    }

}
