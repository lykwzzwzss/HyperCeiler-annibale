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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

import android.content.Context;
import android.os.Handler;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefType;
import com.sevtinge.hyperceiler.common.utils.prefs.PrefsChangeObserver;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import com.sevtinge.hyperceiler.libhook.base.BaseLoad;
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook;

import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam;

public class VolumeDefaultStream extends BaseHook {

    private static final String PREF_KEY = "prefs_key_system_framework_default_volume_stream";

    /** Handler 与 PrefsChangeObserver 全局只保留一份，懒初始化一次。 */
    private static final PrefsObserverHolder OBSERVER_HOLDER = new PrefsObserverHolder();

    Class<?> mAudioService;

    @Override
    public void init() {
        mAudioService = findClassIfExists("com.android.server.audio.AudioService");

        // 热重载时注销观察者，避免旧代理回调残留
        registerHotReloadCleanup(OBSERVER_HOLDER::close);

        findAndHookMethod(mAudioService, "getActiveStreamType", int.class, new IMethodHook() {
            @Override
            public void before(HookParam param) {

                Context mContext = (Context) getObjectField(param.getThisObject(), "mContext");
                OBSERVER_HOLDER.ensure(mContext);

                int mDefaultVolumeStream = PrefsBridge.getStringAsInt("system_framework_default_volume_stream", 0);

                param.setResult(mDefaultVolumeStream);

            }
        });
    }

    private static final class PrefsObserverHolder {

        private Handler handler;
        private PrefsChangeObserver observer;

        synchronized void ensure(Context context) {
            if (observer != null || context == null) return;
            try {
                handler = new Handler(context.getMainLooper());
                observer = new PrefsChangeObserver(context, handler, true, PrefType.String, PREF_KEY, "0");
            } catch (Throwable t) {
                handler = null;
                observer = null;
                XposedLog.w(VolumeDefaultStream.class.getSimpleName(), BaseLoad.getPackageName(),
                    "Failed to register prefs observer", t);
            }
        }

        synchronized void close() {
            PrefsChangeObserver currentObserver = observer;
            observer = null;
            if (currentObserver != null) {
                try {
                    currentObserver.close();
                } catch (Throwable t) {
                    XposedLog.w(VolumeDefaultStream.class.getSimpleName(), BaseLoad.getPackageName(),
                        "Failed to close prefs observer", t);
                }
            }
            Handler currentHandler = handler;
            handler = null;
            if (currentHandler != null) {
                try {
                    currentHandler.removeCallbacksAndMessages(null);
                } catch (Throwable ignored) {
                    // 宿主 looper 已退出时无需处理
                }
            }
        }
    }
}
