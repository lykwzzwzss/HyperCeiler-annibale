package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.Context;
import android.content.res.Resources;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.BuildConfig;

import java.io.InputStream;

/**
 * 读取随模块打包的超级岛素材。素材从补丁的内嵌 Base64 抽出，放到 assets/faceisland 下。
 */
final class FaceIslandAssets {

    private FaceIslandAssets() {
    }

    static InputStream open(Context context, String name) throws Exception {
        ClassLoader loader = FaceIslandAssets.class.getClassLoader();
        if (loader != null) {
            InputStream stream = loader.getResourceAsStream("assets/" + name);
            if (stream == null) {
                stream = loader.getResourceAsStream(name);
            }
            if (stream != null) {
                XposedLog.d("FaceIslandAssets", "classloader hit: " + name);
                return stream;
            }
        }
        if (context != null) {
            try {
                Resources resources = context.getPackageManager()
                    .getResourcesForApplication(BuildConfig.APP_MODULE_ID);
                InputStream stream = resources.getAssets().open(name);
                XposedLog.d("FaceIslandAssets", "module assets hit: " + name);
                return stream;
            } catch (Throwable error) {
                XposedLog.e("FaceIslandAssets", "module assets miss: " + name, error);
                throw error;
            }
        }
        throw new IllegalStateException("模块上下文不可用: " + name);
    }
}
