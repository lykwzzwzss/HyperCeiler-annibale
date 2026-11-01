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
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.tiles

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.common.utils.ShellUtils
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileConfig
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileContext
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileState
import com.sevtinge.hyperceiler.libhook.appbase.systemui.TileUtils
import com.sevtinge.hyperceiler.libhook.utils.api.MathUtils
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.beforeHookMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.hookAllConstructors
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * 手电筒亮度调节磁贴
 *
 * 功能：通过系统亮度滑块调节手电筒亮度
 */
object NewFlashLight : TileUtils() {

    // 手电筒亮度控制文件路径
    private const val TORCH = "/sys/class/leds/yellow:flash-0/brightness"
    private const val FLASH_SWITCH = "/sys/class/leds/led:switch_0/brightness"
    private const val MAX_BRIGHTNESS = "/sys/class/leds/yellow:flash-0/max_brightness"

    // Settings keys
    private const val SETTING_FLASH_ENABLED = "flash_light_enabled"
    private const val SETTING_FLASH_BRIGHTNESS = "flash_light_brightness"
    private const val STATE_CONTEXT = "NewFlashLight.context"
    private const val STATE_CONTROLLER = "NewFlashLight.controller"

    private var mode: Int = 0
    private var lastFlash: Int = -1
    private var isListening: Boolean = false
    @Volatile private var isHook: Boolean = false
    @Volatile private var flashSession: Long = 0
    private var torchStrength: TorchStrengthController? = null
    private var backendChecked = false
    private var legacyAvailable = false
    private var brightnessObserver: ContentObserver? = null
    private val writeExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(1), ThreadPoolExecutor.DiscardOldestPolicy())

    override fun onCreateTileConfig(): TileConfig {
        return TileConfig.Builder()
            .setTileClass(findClassIfExists("com.android.systemui.qs.tiles.MiuiFlashlightTile"))
            .build()
    }

    override fun init() {
        super.init()
        // 读取配置
        mode = PrefsBridge.getStringAsInt("security_flash_light_switch", 0)

        registerHotReloadCleanup {
            isHook = false
            flashSession++
            writeExecutor.shutdownNow()
        }

        // Hook 相关方法
        initBrightnessControllerHook()
        hookBrightnessControl()
        hookBrightnessUtils()

        val restoredContext = getHotReloadRuntimeState(STATE_CONTEXT, Context::class.java)
        val restoredController = getHotReloadRuntimeState(STATE_CONTROLLER, Any::class.java)
        if (restoredContext != null && restoredController != null) {
            setupBrightnessListener(restoredContext, restoredController)
        }
    }

    override fun onUpdateState(ctx: TileContext): TileState? {
        val context = ctx.context
        val flashController = ctx.getField<Any>("flashlightController")
        val isEnabled = flashController?.callMethod("isEnabled") as? Boolean ?: false

        initBackend(context)
        val active = isEnabled && (torchStrength != null || legacyAvailable)
        if (active != isHook) {
            flashSession++
            isHook = active
        }
        // Strength callbacks must not reset the slider or replay saved brightness.
        if (isFlashLightEnabled(context) != active) {
            setFlashLightEnabled(context, if (active) 1 else 0)
        }

        // 返回 null 使用原有状态逻辑
        return null
    }

    /**
     * Hook 亮度控制器构造函数，添加监听
     */
    private fun initBrightnessControllerHook() {
        findClass("com.android.systemui.controlcenter.policy.MiuiBrightnessController")
            .hookAllConstructors {
                after { param ->
                    val context = getObjectField(param.thisObject, "mContext") as? Context
                    if (context != null) {
                        setupBrightnessListener(context, param.thisObject)
                    }
                }
            }
    }

    /**
     * 设置亮度监听器
     */
    private fun setupBrightnessListener(context: Context, controller: Any) {
        initBackend(context)
        if (isListening) {
            XposedLog.d(TAG, "Already listening")
            return
        }

        brightnessObserver = object : ContentObserver(Handler(context.mainLooper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                lastFlash = -1
                val active = isFlashLightEnabled(context) && (torchStrength != null || legacyAvailable)
                if (active != isHook) flashSession++
                isHook = active

                if (isHook) {
                    val brightness = getFlashBrightness(context)

                    if (brightness != null && brightness != "0") {
                        try {
                            val jsonObject = JSONObject(brightness)
                            val flash = jsonObject.getInt("brightness")
                            val slider = jsonObject.getInt("slider")

                            setSliderValue(controller, slider)
                            // 写入亮度值
                            writeFile(flash)
                        } catch (e: JSONException) {
                            XposedLog.e(TAG, "Failed to parse brightness JSON", e)
                        }
                    }
                } else if (torchStrength != null) {
                    // Show the unchanged screen brightness after leaving torch mode.
                    val handler = getObjectField(controller, "mBackgroundHandler") as? Handler
                    val update = getObjectField(controller, "mUpdateSliderRunnable") as? Runnable
                    if (handler != null && update != null) handler.post(update)
                }
            }
        }

        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_FLASH_ENABLED),
            false,
            brightnessObserver!!
        )
        isListening = true
        registerContentObserverHotReloadCleanup(context.contentResolver, brightnessObserver!!)
        putHotReloadRuntimeState(STATE_CONTEXT, context)
        putHotReloadRuntimeState(STATE_CONTROLLER, controller)
        XposedLog.i(TAG, "Brightness listener set up successfully")
    }

    /**
     * Hook 亮度控制相关方法
     */
    private fun hookBrightnessControl() {
        val lambdaClass = findClassIfExists(
            $$$"com.android.systemui.controlcenter.policy.MiuiBrightnessController$$ExternalSyntheticLambda0"
        )
        lambdaClass?.takeIf { cls -> cls.declaredMethods.any { it.name == "run" } }?.let {
            it.beforeHookMethod("run") { param ->
                if (isHook) {
                    param.result = null
                }
            }
        }

        val innerClass = findClassIfExists(
            $$"com.android.systemui.controlcenter.policy.MiuiBrightnessController$2"
        )
        innerClass?.takeIf { cls -> cls.declaredMethods.any { it.name == "run" } }?.let {
            it.beforeHookMethod("run") { param ->
                if (isHook) {
                    param.result = null
                }
            }
        }

        val controllerClass = findClass("com.android.systemui.controlcenter.policy.MiuiBrightnessController")
        controllerClass.beforeHookMethod("onChanged") { param ->
            if (!isHook || torchStrength == null) return@beforeHookMethod
            // K90 OS4 also has HyperOSBrightnessPolicyV1, which bypasses the old runnable.
            // Own the user event before either screen-brightness write path is reached.
            param.result = null
            setBooleanField(param.thisObject, "isUserSliding", param.args[1] as Boolean)
            if (getObjectField(param.thisObject, "mExternalChange") == true) return@beforeHookMethod
            val slider = param.args[3] as Int
            (getObjectField(param.thisObject, "mSliderAnimator") as? android.animation.ValueAnimator)?.cancel()
            getObjectField(param.thisObject, "mToggleSlidersController")
                ?.callMethod("setValue", slider, param.args[0])
            val brightnessUtils = findClass("com.android.systemui.controlcenter.policy.BrightnessUtils")
            lastFlash = calculateBrightness(brightnessUtils, slider, 0f, 1f)
            writeFile(lastFlash)
        }
        controllerClass.beforeHookMethod("onStop", Int::class.java) { param ->
                if (isHook && lastFlash != -1) {
                    val context = getObjectField(param.thisObject, "mContext") as Context
                    // 宿主传入的进度值先夹到 0..100，再作为 JSON 数值写出，避免带出任意对象。
                    val sliderValue = (param.args[0] as Int).coerceIn(0, 100)

                    val jsonObject = JSONObject(mapOf("slider" to sliderValue, "brightness" to lastFlash))
                    setFlashBrightness(context, jsonObject.toString())
                }
                if (isHook && torchStrength != null) {
                    // Do not schedule the screen/slider consistency check in torch mode.
                    setBooleanField(param.thisObject, "isUserSliding", false)
                    param.result = null
                }
            }
    }

    /**
     * Hook BrightnessUtils 亮度转换方法
     */
    private fun hookBrightnessUtils() {
        val brightnessUtils = findClassIfExists(
            "com.android.systemui.controlcenter.policy.BrightnessUtils"
        ) ?: return

        val hookGammaConversion = { paramOrder: Boolean ->
            brightnessUtils.beforeHookMethod(
                "convertGammaToLinearFloat",
                if (paramOrder) Int::class.java else Float::class.java,
                Float::class.java,
                if (paramOrder) Float::class.java else Int::class.java,
            ) { param ->
                if (!isHook || torchStrength != null) return@beforeHookMethod

                var min = param.args[if (paramOrder) 1 else 0] as Float
                var max = param.args[if (paramOrder) 2 else 1] as Float
                val value = param.args[if (paramOrder) 0 else 2] as Int

                // 调整最小值
                if (min < 0.001f) {
                    min = 0.00114514f
                }
                min = (min * 500).roundToInt().toFloat()
                max = (max * 500).roundToInt().toFloat()

                // 计算亮度值
                val brightness = calculateBrightness(brightnessUtils, value, min, max)

                if (brightness > 0) {
                    lastFlash = brightness
                    writeFile(brightness)
                }

                param.result = brightness.toFloat()
            }
        }

        // 尝试两种参数顺序
        runCatching {
            brightnessUtils.getDeclaredMethod(
                "convertGammaToLinearFloat",
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            hookGammaConversion(true)
        }.recoverCatching {
            brightnessUtils.getDeclaredMethod(
                "convertGammaToLinearFloat",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hookGammaConversion(false)
        }.onFailure {
            XposedLog.e(TAG, "convertGammaToLinearFloat method not found")
        }
    }

    /**
     * 计算亮度值
     */
    private fun calculateBrightness(
        brightnessUtils: Class<*>,
        value: Int,
        min: Float,
        max: Float
    ): Int {
        val gammaSpaceMax = getStaticIntField(brightnessUtils, "GAMMA_SPACE_MAX")
        val r = getStaticFloatField(brightnessUtils, "R")
        val a = getStaticFloatField(brightnessUtils, "A")
        val b = getStaticFloatField(brightnessUtils, "B")
        val c = getStaticFloatField(brightnessUtils, "C")

        val norm = MathUtils.norm(0.0f, gammaSpaceMax.toFloat(), value.toFloat())
        val exp = if (norm <= r) {
            MathUtils.sq(norm / r)
        } else {
            MathUtils.exp((norm - c) / a) + b
        }

        val normalized = (MathUtils.constrain(exp, 0.0f, 12.0f) / 12.0f).coerceIn(0f, 1f)
        torchStrength?.let { backend ->
            return (1 + normalized * (backend.maxLevel - 1)).roundToInt().coerceIn(1, backend.maxLevel)
        }
        val finalMin = if (min < 10) 12f else min
        val end = MathUtils.lerpNew(finalMin, max, MathUtils.constrain(exp, 0.0f, 12.0f) / 12.0f)

        var brightness = end.roundToInt()
        val maxBrightness = getMaxBrightness()
        if (maxBrightness != -1 && brightness > maxBrightness) {
            brightness = maxBrightness
        }

        return brightness
    }

    /**
     * 动画设置滑块位置
     */
    private fun setSliderValue(controller: Any, targetValue: Int) {
        runCatching {
            if (getObjectField(controller, "isUserSliding") == true) return
            val sliderController = getObjectField(controller, "mToggleSlidersController") ?: return
            val externalChange = getObjectField(controller, "mExternalChange") == true
            // Programmatic slider updates must not write the screen or torch brightness.
            setBooleanField(controller, "mExternalChange", true)
            try {
                runCatching {
                    // K90 OS4: setValue(int, ToggleSliderBase), null updates every slider.
                    callMethod(sliderController, "setValue", targetValue, null)
                }.recoverCatching {
                    callMethod(sliderController, "setValue", targetValue, false)
                }.recoverCatching {
                    callMethod(sliderController, "setValue", targetValue)
                }.recoverCatching {
                    setObjectField(sliderController, "sliderValue", targetValue)
                    refreshSliders(sliderController, targetValue)
                }.getOrThrow()
                setBooleanField(controller, "mControlValueInitialized", true)
            } finally {
                setBooleanField(controller, "mExternalChange", externalChange)
            }
        }.onFailure {
            XposedLog.e(TAG, "Error in setSliderValue", it)
        }
    }

    private fun refreshSliders(sliderController: Any, value: Int) {
        runCatching {
            val toggleSliders = getObjectField(sliderController, "toggleSliders") ?: return
            val iterator = callMethod(toggleSliders, "iterator") as? Iterator<*> ?: return
            iterator.forEach { slider ->
                runCatching {
                    slider?.let {
                        callMethod(it, "setValue", value)
                    }
                }
            }
        }
    }

    /**
     * 读取最大亮度值
     */
    private fun getMaxBrightness(): Int {
        torchStrength?.let { return it.maxLevel }
        return try {
            val result = ShellUtils.rootExecCmd("cat $MAX_BRIGHTNESS")
            result.trim().toInt()
        } catch (_: Exception) {
            XposedLog.w(TAG, "Max brightness file not found: $MAX_BRIGHTNESS")
            255
        }
    }

    /** 选择系统相机调光接口，旧设备再回退到已有节点。 */
    private fun initBackend(context: Context) {
        if (backendChecked) return
        backendChecked = true
        torchStrength = runCatching { TorchStrengthController.create(context) }
            .onFailure { XposedLog.w(TAG, "Torch strength discovery failed: $it") }.getOrNull()
        legacyAvailable = torchStrength == null && File(TORCH).exists() && File(MAX_BRIGHTNESS).exists()
        XposedLog.i(TAG, "Torch backend: camera levels=${torchStrength?.maxLevel}, legacy=$legacyAvailable")
    }

    private fun writeFile(flash: Int) {
        if (!isHook || writeExecutor.isShutdown) return
        val session = flashSession
        writeExecutor.execute {
            // Discard queued values after the tile switches off or starts a new session.
            if (!isHook || session != flashSession) return@execute
            try {
                val backend = torchStrength
                if (backend != null) {
                    backend.setLevel(flash)
                } else if (legacyAvailable) {
                    val level = flash.coerceIn(1, getMaxBrightness().coerceAtLeast(1))
                    val command = when (mode) {
                        2 -> "echo 0 > $TORCH; echo $level > $TORCH"
                        3 -> "echo $level > $TORCH; echo 1 > $FLASH_SWITCH; echo 0 > $FLASH_SWITCH"
                        else -> "echo $level > $TORCH"
                    }
                    // One ordered command; never broaden sysfs permissions with chmod 777.
                    ShellUtils.rootExecCmd(command)
                }
            } catch (e: Exception) {
                XposedLog.e(TAG, "Torch strength update failed", e)
            }
        }
    }

    /**
     * Settings 辅助方法
     */
    private fun isFlashLightEnabled(context: Context): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, SETTING_FLASH_ENABLED) == 1
        } catch (_: Settings.SettingNotFoundException) {
            setFlashLightEnabled(context, 0)
            false
        }
    }

    private fun setFlashLightEnabled(context: Context, value: Int) {
        Settings.System.putInt(context.contentResolver, SETTING_FLASH_ENABLED, value)
    }

    private fun getFlashBrightness(context: Context): String? {
        return try {
            Settings.System.getString(context.contentResolver, SETTING_FLASH_BRIGHTNESS)
        } catch (_: Throwable) {
            null
        }
    }

    private fun setFlashBrightness(context: Context, value: String) {
        Settings.System.putString(context.contentResolver, SETTING_FLASH_BRIGHTNESS, value)
    }
}
