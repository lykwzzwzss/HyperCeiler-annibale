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
package com.sevtinge.hyperceiler.libhook.rules.systemsettings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.TetheringManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.ArrayMap;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import java.util.Locale;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class UsbModeChoose extends BaseHook {
    ArrayMap<String, Integer> mode = new ArrayMap<>();

    ArrayMap<Integer, String> getMode = new ArrayMap<>();
    int mChoose = PrefsBridge.getStringAsInt("system_settings_usb_mode_choose", 0);

    boolean modes = PrefsBridge.getBoolean("system_settings_usb_mode");
    boolean addAll = false;
    Resources resources;
    Activity activity;
    Locale locale;
    Configuration configuration;
    String[] allMode = {
        "",
        "仅限充电",
        "传输文件",
        "传输照片",
        "MIDI模式",
        "反向充电",
        "USB 网络共享"
    };

    @Override
    public void init() {
        Class<?> usbPreferenceActivity = findClassIfExists(
            "com.android.settings.connecteddevice.usb.UsbPreferenceActivity");
        Class<?> usbStatsFragment = findClassIfExists(
            "com.android.settings.connecteddevice.usb.UsbStatsPreferenceFragement");
        if (usbPreferenceActivity != null && usbStatsFragment != null) {
            initCurrentUsbChooser(usbStatsFragment);
            return;
        }

        Class<?> usbModeChooser = findClassIfExists(
            "com.android.settings.connecteddevice.usb.UsbModeChooserActivity");
        if (usbModeChooser == null) return;
        if (mChoose != 0
            && findMethodExactIfExists(usbModeChooser, "getTitleMiui12", long.class) == null) return;

        if (mChoose != 0 || modes) {
            findAndHookMethod(usbModeChooser,
                "onCreate", Bundle.class, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        activity = (Activity) param.getThisObject();
                    }
                }
            );
        }

        if (mChoose != 0) {
            findAndHookMethod(usbModeChooser,
                "initModesList", long[].class, new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        // XposedLog.e(TAG, "long: " + param.args[0]);
                        long[] jArr = {0, 8, 4, 16, 128};
                        if (addAll || activity == null) return;
                        setLanguage(activity);
                        try {
                            for (long l : jArr) {
                                int getTitle = (int) callStaticMethod(
                                    usbModeChooser, "getTitleMiui12", l);
                                if (getTitle != 0) {
                                    String get = (String) callMethod(
                                        param.getThisObject(), "getString", getTitle
                                    );
                                    mode.put(get, (int) l);
                                    // XposedLog.e(TAG, "get: " + get);
                                }
                            }
                            if (mode.size() == jArr.length) {
                                mode.put("USB 网络共享", -1);
                                addAll = true;
                            }
                        } finally {
                            revertLanguage();
                        }
                    }
                }
            );

            findAndHookMethod(usbModeChooser,
                "initDialog", new IMethodHook() {
                    @SuppressLint("WrongConstant")
                    @Override
                    public void before(HookParam param) {
                        if (activity == null) return;
                        String action = activity.getIntent().getAction();
                        // XposedLog.e(TAG, "ac: " + action);
                        if (getMode.isEmpty())
                            setAllMode();
                        if (action == null) {
                            Integer choose = mode.get(getMode.get(mChoose));
                            if (choose == null) return;
                            // XposedLog.e(TAG, "choose: " + choose);
                            if (choose != -1) {
                                Object mBackend = getObjectField(param.getThisObject(), "mBackend");
                                // XposedLog.e(TAG, "cc: " + getMode.get(mChoose));
                                if (getMode.get(mChoose).equals("反向充电")) {
                                    if ((boolean) callMethod(param.getThisObject(), "isSupportReverseCharging")) {
                                        callMethod(mBackend, "setCurrentFunctions", (long) choose);
                                    } else {
                                        XposedLog.e(TAG, "Your phone can't reverse charging.");
                                    }
                                } else {
                                    callMethod(mBackend, "setCurrentFunctions", (long) choose);
                                    // XposedLog.e(TAG, "set: " + choose + " name: " + getMode.get(mChoose));
                                }
                            } else if (choose == -1) {
                                Object tethering = activity.getSystemService("tethering");
                                int end = (int) callMethod(tethering, "setUsbTethering", true);
                                XposedLog.i(TAG, "tethering: " + end);
                            }
                            if (modes) {
                                param.setResult(null);
                                activity.finish();
                            }
                            // XposedLog.e(TAG, "finish");
                        }
                    }
                }
            );
        } else if (modes) {
            findAndHookMethod(usbModeChooser,
                "initDialog", new IMethodHook() {
                    @Override
                    public void before(HookParam param) {
                        if (activity == null) return;
                        String action = activity.getIntent().getAction();
                        if (action == null) {
                            param.setResult(null);
                            activity.finish();
                        }
                    }
                }
            );
        }
    }


    private void initCurrentUsbChooser(Class<?> usbStatsFragment) {
        findAndHookMethod(usbStatsFragment, "onCreatePreferences", Bundle.class, String.class,
            new IMethodHook() {
                @Override
                public void after(HookParam param) {
                    Object fragment = param.getThisObject();
                    Context context = (Context) callMethod(fragment, "getContext");
                    if (context == null) return;
                    Activity currentActivity = (Activity) getObjectField(fragment, "mActivity");
                    if (currentActivity == null) currentActivity = (Activity) callMethod(fragment, "getActivity");
                    if (currentActivity == null || currentActivity.getIntent() == null
                        || currentActivity.getIntent().getAction() != null) return;

                    boolean waitForReverseCharge = false;
                    if (mChoose != 0) {
                        Object backend = getObjectField(fragment, "mBackend");
                        switch (mChoose) {
                            case 1 -> callMethod(backend, "setCurrentFunctions", 0L);
                            case 2 -> callMethod(backend, "setCurrentFunctions", 4L);
                            case 3 -> callMethod(backend, "setCurrentFunctions", 16L);
                            case 4 -> callMethod(backend, "setCurrentFunctions", 8L);
                            case 5 -> {
                                boolean supported = (boolean) callMethod(fragment, "isSupportReverseCharge");
                                if (supported) {
                                    callMethod(backend, "setPowerRole", 1);
                                    waitForReverseCharge = true;
                                } else {
                                    XposedLog.i(TAG, "The current device does not support USB reverse charging.");
                                }
                            }
                            case 6 -> startUsbTethering(context);
                            default -> { }
                        }
                    }

                    if (waitForReverseCharge) {
                        Object backend = getObjectField(fragment, "mBackend");
                        Handler handler = (Handler) getObjectField(fragment, "mHandler");
                        if (handler != null) {
                            Activity reverseChargeActivity = currentActivity;
                            Runnable reverseChargeCheck = () -> {
                                if (reverseChargeActivity.isFinishing() || reverseChargeActivity.isDestroyed()) return;
                                try {
                                    if ((int) callMethod(backend, "getPowerRole") == 1
                                        && (int) callMethod(backend, "getDataRole") == 2) {
                                        callMethod(backend, "setDataRole", 1);
                                    }
                                    callMethod(fragment, "initWinodws");
                                    if (modes) callMethod(reverseChargeActivity, "dismissDialogAndFinish");
                                } catch (Throwable t) {
                                    XposedLog.e(TAG, "Failed to complete USB reverse charging: " + t);
                                }
                            };
                            handler.postDelayed(reverseChargeCheck, 2000L);
                            registerHotReloadCleanup(() -> handler.removeCallbacks(reverseChargeCheck));
                        }
                    } else if (modes) {
                        Object activity = getObjectField(fragment, "mActivity");
                        if (activity != null) callMethod(activity, "dismissDialogAndFinish");
                    }
                }
            }
        );
    }

    private void startUsbTethering(Context context) {
        TetheringManager tethering = context.getSystemService(TetheringManager.class);
        if (tethering == null) {
            XposedLog.e(TAG, "USB tethering service is unavailable.");
            return;
        }
        // TETHERING_USB is hidden from the API-37 compile SDK surface, but remains type 1
        // in the Android framework contract consumed by the public request builder.
        TetheringManager.TetheringRequest request =
            new TetheringManager.TetheringRequest.Builder(1).build();
        tethering.startTethering(request, context.getMainExecutor(),
            new TetheringManager.StartTetheringCallback() {
                @Override
                public void onTetheringStarted() {
                    XposedLog.i(TAG, "USB tethering started from the USB chooser.");
                }

                @Override
                public void onTetheringFailed(int error) {
                    XposedLog.e(TAG, "USB tethering failed: " + error);
                }
            }
        );
    }

    public void setAllMode() {
        for (int i = 0; i < allMode.length; i++) {
            String name = allMode[i];
            getMode.put(i, name);
        }
    }

    public void setLanguage(Activity activity) {
        resources = activity.getResources();
        configuration = resources.getConfiguration();
        locale = configuration.getLocales().get(0);
        configuration.setLocale(Locale.SIMPLIFIED_CHINESE);
        resources.updateConfiguration(configuration, null);
    }

    public void revertLanguage() {
        if (configuration != null && resources != null) {
            configuration.setLocale(locale);
            resources.updateConfiguration(configuration, null);
        }
    }
}
