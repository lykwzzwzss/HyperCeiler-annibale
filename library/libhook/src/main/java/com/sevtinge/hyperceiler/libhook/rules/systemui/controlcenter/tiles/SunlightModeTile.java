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
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.tiles;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.libhook.R;
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileConfig;
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileContext;
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileState;
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileUtils;
import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.common.utils.ShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Objects;

public class SunlightModeTile extends TileUtils {

    // ==================== 常量定义 ====================

    private static final String TILE_NAME = "custom_SUN";

    // 亮度文件路径
    //
    // brightness_clone 只是 mi_display 的影子节点：写入会成功、值也会变，但不会驱动背光，
    // 面板亮度由 backlight 节点决定。曾经把它当主路径用，结果就是“shell 写亮度没反应”。
    private static final String BRIGHTNESS_FILE_CLONE = "/sys/class/mi_display/disp-DSI-0/brightness_clone";
    private static final String BRIGHTNESS_FILE_BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String BRIGHTNESS_FILE_BACKLIGHT_MAX = "/sys/class/backlight/panel0-backlight/max_brightness";
    private static final String BRIGHTNESS_FILE_SECONDARY = "/sys/devices/platform/soc/9800000.qcom,mdss_mdp/backlight/panel0-backlight/brightness";

    // 兜底亮度上限，读不到 max_brightness 时使用
    private static final int FALLBACK_MAX_BRIGHTNESS = 16383;
    // writeBrightness 的返回值：节点对写入毫无反应
    private static final int WRITE_REJECTED = -1;

    // 系统设置键
    private static final String SETTING_SCREEN_BRIGHTNESS = "screen_brightness";
    private static final String SETTING_SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode";
    private static final String SETTING_SUNLIGHT_MODE = "sunlight_mode";

    // 附加字段键
    private static final String FIELD_BROADCAST_RECEIVER = "sunlight_broadcast_receiver";
    private static final String FIELD_CONTENT_OBSERVER = "sunlight_content_observer";
    private static final String FIELD_TILE_INSTANCE = "sunlight_tile_instance";

    // ==================== 运行模式枚举 ====================

    /**
     * 运行模式
     */
    private enum Mode {
        /** 系统阳光模式 */
        SYSTEM_SUNLIGHT,
        /** 强制最高亮度（直接写文件） */
        FORCE_MAX_BRIGHTNESS,
        /** 自定义亮度（Shell 命令） */
        CUSTOM_BRIGHTNESS_SHELL,
        /** 系统 API 最高亮度 */
        SYSTEM_API_MAX_BRIGHTNESS
    }

    // ==================== 状态字段 ====================

    private Mode mMode = Mode.SYSTEM_SUNLIGHT;
    private String mBrightnessPath = null;
    private boolean mUseSystemApi = false;
    private int mCustomBrightness = 2048;
    private int mMaxBrightness = FALLBACK_MAX_BRIGHTNESS;
    /** 开启高亮前的自动亮度状态，关闭时原样恢复 */
    private int mSavedAutoBrightnessMode = -1;

    // 运行时状态（使用 volatile 保证多线程可见性）
    private volatile int mLastBrightness = 0;
    private volatile int mTargetBrightness = 0;
    private volatile boolean mIsListeningBroadcast = false;
    private volatile boolean mIsSunlightActive = false;

    // 磁贴实例引用（用于从广播接收器刷新状态）
    private WeakReference<Object> mTileInstanceRef;

    // ==================== 初始化 ====================

    @Override
    public void init() {
        initMode();
        initBrightnessPath();
        super.init();

        // 官方磁贴已从控制中心移除，且 HyperOS 4 的编辑列表不再读磁贴资源串，
        // 这里主动补一次用户磁贴列表，避免依赖 SystemUIApplication.onCreate 的时序。
        ensureTileInPanelAsync();
    }

    /**
     * 初始化运行模式
     */
    private void initMode() {
        // 优先检查高级模式设置
        int highMode = PrefsBridge.getStringAsInt("system_control_center_sunshine_new_mode_high", 0);
        if (highMode > 0) {
            switch (highMode) {
                case 1 -> mMode = Mode.SYSTEM_SUNLIGHT;
                case 2 -> mMode = Mode.FORCE_MAX_BRIGHTNESS;
                case 3 -> mMode = Mode.CUSTOM_BRIGHTNESS_SHELL;
            }
            mCustomBrightness = PrefsBridge.getInt("system_control_center_sunshine_mode_brightness", 2048);
        } else {
            // 普通模式设置
            int normalMode = PrefsBridge.getStringAsInt("system_control_center_sunshine_new_mode", 0);
            switch (normalMode) {
                case 1 -> mMode = Mode.SYSTEM_SUNLIGHT;
                case 2 -> mMode = Mode.FORCE_MAX_BRIGHTNESS;
            }
        }

        XposedLog.d(TAG, "SunlightMode initialized with mode: " + mMode);
    }

    /**
     * 初始化亮度文件路径
     *
     * <p>只接受真正驱动背光的节点。`brightness_clone` 写入不会改变面板亮度，
     * 因此仅在没有其它可写节点时作为最后兜底，并且会被标记为「可能无效」。</p>
     */
    private void initBrightnessPath() {
        mMaxBrightness = readIntFile(BRIGHTNESS_FILE_BACKLIGHT_MAX, FALLBACK_MAX_BRIGHTNESS);

        // 根据设置选择路径优先级
        int writeMode = PrefsBridge.getStringAsInt("system_control_center_sunshine_new_mode_write", 1);
        String[] candidates = writeMode == 1
            ? new String[]{BRIGHTNESS_FILE_BACKLIGHT, BRIGHTNESS_FILE_SECONDARY, BRIGHTNESS_FILE_CLONE}
            : new String[]{BRIGHTNESS_FILE_SECONDARY, BRIGHTNESS_FILE_BACKLIGHT, BRIGHTNESS_FILE_CLONE};

        for (String candidate : candidates) {
            if (new File(candidate).exists()) {
                mBrightnessPath = candidate;
                break;
            }
        }

        if (mBrightnessPath == null) {
            mUseSystemApi = true;
            XposedLog.w(TAG, "Brightness node not found, falling back to system API");
            return;
        }

        // 提升节点可写性（root 下多半已是 rw-rw-r--，失败不影响后续写入）
        ShellUtils.rootExecCmd("chmod 666 " + mBrightnessPath);

        if (BRIGHTNESS_FILE_CLONE.equals(mBrightnessPath)) {
            XposedLog.w(TAG, "Only brightness_clone is available; it does not drive the panel, custom brightness may be a no-op");
        }
        XposedLog.d(TAG, "Using brightness path: " + mBrightnessPath + ", max=" + mMaxBrightness);
    }

    /**
     * 读取 sysfs 中的整数，失败返回默认值
     */
    private static int readIntFile(String path, int def) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            return line != null ? Integer.parseInt(line.trim()) : def;
        } catch (IOException | NumberFormatException e) {
            return def;
        }
    }

    // ==================== TileUtils 实现 ====================

    @NonNull
    @Override
    protected TileConfig onCreateTileConfig() {
        return new TileConfig.Builder()
            .setTileClass(findClassIfExists("com.android.systemui.qs.tiles.PowerSaverTile"))
            .setTileName(TILE_NAME)
            .setTileProvider("powerSaverTileProvider")
            .setLabelResId(R.string.tiles_sunshine_mode)
            .setIcons(R.drawable.baseline_wb_sunny_24, R.drawable.baseline_wb_sunny_24)
            // 官方磁贴已移除，编辑列表里不存在这一项，只能注册时直接补进面板
            .setAutoAddToPanel(true)
            .build();
    }

    @Override
    protected boolean onCheckAvailable(TileContext ctx) {
        return true;
    }

    @Override
    protected void onTileClick(TileContext ctx) {
        Context context = ctx.getContext();

        // 保存磁贴实例引用
        saveTileInstance(ctx);

        try {
            switch (mMode) {
                case SYSTEM_SUNLIGHT -> toggleSystemSunlightMode(context);
                case FORCE_MAX_BRIGHTNESS -> toggleForceBrightness(ctx, context, 16383);
                case CUSTOM_BRIGHTNESS_SHELL -> toggleForceBrightness(ctx, context, mCustomBrightness);
                case SYSTEM_API_MAX_BRIGHTNESS -> toggleSystemApiBrightness(ctx, context);
            }
        } catch (Exception e) {
            XposedLog.e(TAG, "Error handling tile click", e);
        }

        ctx.refreshState();
    }

    @Nullable
    @Override
    protected Intent onGetLongClickIntent(TileContext ctx) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$DisplaySettingsActivity"));
        return intent;
    }

    @Override
    protected void onListeningChanged(TileContext ctx, boolean listening) {
        Context context = ctx.getContext();

        saveTileInstance(ctx);

        if (listening) {
            registerContentObserver(ctx, context);
        } else {
            unregisterContentObserver(ctx, context);
        }
    }

    @Nullable
    @Override
    protected TileState onUpdateState(TileContext ctx) {
        saveTileInstance(ctx);

        boolean isEnabled = checkIsEnabled(ctx.getContext());


        if (!isEnabled && mIsSunlightActive) {
            cleanupOnExternalDisable(ctx);
        }

        mIsSunlightActive = isEnabled;

        return new TileState(isEnabled);
    }

    // ==================== 磁贴实例管理 ====================

    /**
     * 保存磁贴实例引用
     */
    private void saveTileInstance(TileContext ctx) {
        mTileInstanceRef = new WeakReference<>(ctx.getTileInstance());
    }

    /**
     * 刷新磁贴状态（从非 TileContext 环境调用）
     */
    private void refreshTileState() {
        if (mTileInstanceRef != null) {
            Object tile = mTileInstanceRef.get();
            if (tile != null) {
                try {
                    tile.getClass().getMethod("refreshState").invoke(tile);
                } catch (Exception e) {
                    XposedLog.e(TAG, "Failed to refresh tile state", e);
                }
            }
        }
    }

    // ==================== 模式切换逻辑 ====================

    /**
     * 切换系统阳光模式
     */
    private void toggleSystemSunlightMode(Context context) {
        try {
            int currentMode = Settings.System.getInt(context.getContentResolver(), SETTING_SUNLIGHT_MODE);
            int newMode = (currentMode == 1) ? 0 : 1;
            Settings.System.putInt(context.getContentResolver(), SETTING_SUNLIGHT_MODE, newMode);
            mIsSunlightActive = (newMode == 1);
        } catch (Settings.SettingNotFoundException e) {
            XposedLog.e(TAG, "sunlight_mode setting not found", e);
        }
    }

    /**
     * 切换强制亮度模式
     */
    private void toggleForceBrightness(TileContext ctx, Context context, int targetBrightness) {
        if (mUseSystemApi) {
            toggleSystemApiBrightness(ctx, context);
            return;
        }

        int currentBrightness = readBrightness();

        if (!mIsSunlightActive) {
            // 开启高亮模式
            enableSunlightMode(ctx, context, currentBrightness, targetBrightness);
        } else {
            // 关闭高亮模式
            disableSunlightMode(ctx, context);
        }
    }

    /**
     * 启用阳光模式
     */
    private void enableSunlightMode(TileContext ctx, Context context, int currentBrightness, int targetBrightness) {
        mLastBrightness = currentBrightness;
        handleAutoBrightnessOnEnable(context);

        int applied = writeBrightness(targetBrightness);
        if (applied == WRITE_REJECTED) {
            // 节点完全不接受写入（典型是被当成控制节点的 brightness_clone），退回系统设置方案
            XposedLog.w(TAG, "Custom brightness rejected by node, falling back to system brightness");
            handleAutoBrightnessOnDisable(context);
            mUseSystemApi = true;
            toggleSystemApiBrightness(ctx, context);
            return;
        }

        registerBroadcastReceiver(ctx, context);
        // 记录实际生效值：万一被框架截断，状态判断也不会一直显示未开启
        mTargetBrightness = applied;
        mIsSunlightActive = true;

        XposedLog.d(TAG, "Sunlight mode enabled, last=" + mLastBrightness
            + ", requested=" + targetBrightness + ", applied=" + mTargetBrightness);
    }

    /**
     * 禁用阳光模式
     */
    private void disableSunlightMode(TileContext ctx, Context context) {
        unregisterBroadcastReceiver(ctx, context);

        if (mLastBrightness > 0) {
            writeBrightness(mLastBrightness);
        }
        // 先还原亮度再交还自动亮度，否则自动亮度会立刻覆盖恢复值
        handleAutoBrightnessOnDisable(context);

        resetState();

        XposedLog.d(TAG, "Sunlight mode disabled");
    }

    /**
     * 切换系统 API 最高亮度模式
     */
    private void toggleSystemApiBrightness(TileContext ctx, Context context) {
        try {
            if (!mIsSunlightActive) {
                // 开启
                handleAutoBrightnessOnEnable(context);

                mLastBrightness = Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS);
                mTargetBrightness = 255;
                Settings.System.putInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS, mTargetBrightness);
                mIsSunlightActive = true;
            } else {
                // 关闭
                Settings.System.putInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS, mLastBrightness);
                handleAutoBrightnessOnDisable(context);

                resetState();
            }
        } catch (Settings.SettingNotFoundException e) {
            XposedLog.e(TAG, "Brightness setting not found", e);
        }
    }

    /**
     * 重置内部状态
     */
    private void resetState() {
        mLastBrightness = 0;
        mTargetBrightness = 0;
        mIsSunlightActive = false;
    }

    /**
     * 外部关闭时清理状态
     */
    private void cleanupOnExternalDisable(TileContext ctx) {
        XposedLog.d(TAG, "Sunlight mode externally disabled, cleaning up");

        if (mIsListeningBroadcast) {
            unregisterBroadcastReceiver(ctx, ctx.getContext());
        }

        resetState();
    }

    // ==================== 状态检查 ====================

    /**
     * 检查当前是否启用
     */
    private boolean checkIsEnabled(Context context) {
        try {
            switch (mMode) {
                case SYSTEM_SUNLIGHT -> {
                    return Settings.System.getInt(context.getContentResolver(), SETTING_SUNLIGHT_MODE) == 1;
                }
                case FORCE_MAX_BRIGHTNESS, CUSTOM_BRIGHTNESS_SHELL -> {
                    if (mUseSystemApi) {
                        return Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS_MODE) == 0
                            && Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS) == mTargetBrightness;
                    }
                    if (mTargetBrightness == 0) {
                        return false;
                    }
                    return readBrightness() == mTargetBrightness;
                }
                case SYSTEM_API_MAX_BRIGHTNESS -> {
                    return Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS_MODE) == 0
                        && Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS) == mTargetBrightness;
                }
            }
        } catch (Settings.SettingNotFoundException e) {
            XposedLog.e(TAG, "Setting not found while checking state", e);
        }
        return false;
    }

    // ==================== 亮度文件操作 ====================

    /**
     * 读取当前亮度
     *
     * <p>始终读写入的那个节点，避免「写 A 读 B」导致状态判断永远对不上。</p>
     */
    private int readBrightness() {
        if (mBrightnessPath == null) return 0;
        return readIntFile(mBrightnessPath, 0);
    }

    /**
     * 写入亮度值
     *
     * <p>需要区分两种“没写到位”：</p>
     * <ul>
     *   <li><b>节点没反应</b>：写入前后值完全不变，典型是被当成控制节点的 {@code brightness_clone}，
     *       这种节点不能用作亮度控制，返回 {@link #WRITE_REJECTED} 让调用方降级；</li>
     *   <li><b>被截断</b>：值变了但没到目标，说明节点有效，只是框架把当前亮度上限压住了
     *       （{@code mAppliedMaxOprBrightness} 一类），这不算失败，按实际生效值继续。</li>
     * </ul>
     *
     * @return 实际生效的亮度；节点无响应时返回 {@link #WRITE_REJECTED}
     */
    private int writeBrightness(int brightness) {
        if (mBrightnessPath == null) return WRITE_REJECTED;

        int target = Math.max(0, Math.min(brightness, mMaxBrightness));
        int before = readBrightness();

        ShellUtils.rootExecCmd("echo " + target + " > " + mBrightnessPath);

        int after = readBrightness();
        if (after == before && target != before) {
            XposedLog.w(TAG, "Brightness node ignored the write: target=" + target
                + ", value=" + after + ", node=" + mBrightnessPath);
            return WRITE_REJECTED;
        }
        if (after != target) {
            XposedLog.w(TAG, "Brightness clamped by system: target=" + target + ", applied=" + after);
        }
        return after;
    }

    // ==================== 自动亮度处理 ====================

    /**
     * 启用高亮时处理自动亮度
     *
     * <p>自动亮度会持续把背光写回传感器算出来的值，所以必须切到手动模式再改亮度。
     * 曾经用的 `screen_brightness_custom_mode` 在 HyperOS 4 上并不存在，等于什么都没做。</p>
     */
    private void handleAutoBrightnessOnEnable(Context context) {
        if (mSavedAutoBrightnessMode != -1) return;
        mSavedAutoBrightnessMode = getBrightnessMode(context);
        if (mSavedAutoBrightnessMode == 1) {
            setBrightnessMode(context, 0);
        }
    }

    /**
     * 禁用高亮时恢复自动亮度
     */
    private void handleAutoBrightnessOnDisable(Context context) {
        if (mSavedAutoBrightnessMode == 1) {
            setBrightnessMode(context, 1);
        }
        mSavedAutoBrightnessMode = -1;
    }

    private int getBrightnessMode(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS_MODE);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    private void setBrightnessMode(Context context, int value) {
        Settings.System.putInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS_MODE, value);
    }

    // ==================== 广播接收器 ====================

    /**
     * 注册息屏广播接收器
     */
    private void registerBroadcastReceiver(TileContext ctx, Context context) {
        if (mIsListeningBroadcast) return;

        ScreenOffReceiver receiver = new ScreenOffReceiver(context, this);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        registerReceiverHotReloadCleanup(context, receiver);

        ctx.setAdditionalField(FIELD_BROADCAST_RECEIVER, receiver);
        mIsListeningBroadcast = true;

        XposedLog.d(TAG, "Registered screen off receiver");
    }

    /**
     * 注销息屏广播接收器
     */
    private void unregisterBroadcastReceiver(TileContext ctx, Context context) {
        if (!mIsListeningBroadcast) return;

        BroadcastReceiver receiver = ctx.getAdditionalField(FIELD_BROADCAST_RECEIVER);
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // 已经注销
            }
            ctx.removeAdditionalField(FIELD_BROADCAST_RECEIVER);
        }
        mIsListeningBroadcast = false;

        XposedLog.d(TAG, "Unregistered screen off receiver");
    }

    /**
     * 息屏时恢复亮度并刷新磁贴状态
     */
    private void onScreenOff(Context context) {
        if (mLastBrightness == 0) return;

        XposedLog.d(TAG, "Screen off, restoring brightness and refreshing tile");

        // 恢复亮度
        if (!mUseSystemApi && mBrightnessPath != null) {
            writeBrightness(mLastBrightness);
        } else {
            Settings.System.putInt(context.getContentResolver(), SETTING_SCREEN_BRIGHTNESS, mLastBrightness);
        }

        // 恢复自动亮度
        handleAutoBrightnessOnDisable(context);

        // 重置状态
        resetState();

        // 刷新磁贴状态
        refreshTileState();
    }

    /**
     * 息屏广播接收器（静态内部类避免内存泄漏）
     */
    private static class ScreenOffReceiver extends BroadcastReceiver {
        private final WeakReference<Context> contextRef;
        private final WeakReference<SunlightModeTile> tileRef;

        ScreenOffReceiver(Context context, SunlightModeTile tile) {
            this.contextRef = new WeakReference<>(context);
            this.tileRef = new WeakReference<>(tile);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Objects.equals(intent.getAction(), Intent.ACTION_SCREEN_OFF)) return;

            SunlightModeTile tile = tileRef.get();
            Context ctx = contextRef.get();

            if (tile != null && ctx != null) {
                tile.onScreenOff(ctx);
            }
        }
    }

    // ==================== 内容观察者 ====================

    /**
     * 注册内容观察者
     */
    private void registerContentObserver(TileContext ctx, Context context) {
        ContentObserver observer = new ContentObserver(new Handler(context.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                super.onChange(selfChange, uri);
                // 系统侧改了亮度/阳光模式，刷新磁贴显示
                ctx.refreshState();
            }
        };

        context.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(SETTING_SCREEN_BRIGHTNESS), false, observer);

        // 对于系统阳光模式，也监听 sunlight_mode
        if (mMode == Mode.SYSTEM_SUNLIGHT) {
            context.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(SETTING_SUNLIGHT_MODE), false, observer);
        }

        registerContentObserverHotReloadCleanup(context.getContentResolver(), observer);
        ctx.setAdditionalField(FIELD_CONTENT_OBSERVER, observer);
    }

    /**
     * 注销内容观察者
     */
    private void unregisterContentObserver(TileContext ctx, Context context) {
        ContentObserver observer = ctx.getAdditionalField(FIELD_CONTENT_OBSERVER);
        if (observer != null) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
            } catch (IllegalArgumentException ignored) {
                // 已在热重载清理阶段注销。
            }
            ctx.removeAdditionalField(FIELD_CONTENT_OBSERVER);
        }
    }
}
