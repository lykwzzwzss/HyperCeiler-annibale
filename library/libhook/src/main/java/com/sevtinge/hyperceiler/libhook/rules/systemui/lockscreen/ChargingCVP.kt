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
package com.sevtinge.hyperceiler.libhook.rules.systemui.lockscreen

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.PowerManager
import android.util.ArrayMap
import android.widget.TextView
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethod
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHooks
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getStaticObjectFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setObjectField
import java.io.BufferedReader
import java.io.FileReader
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

object ChargingCVP : BaseHook() {
    private const val STATE_TEXT_VIEW = "ChargingCVP.textView"
    private const val SHOW_SPACING_KEY = "system_ui_lock_screen_show_spacing"
    private const val SHOW_SPACING_LEGACY_KEY = "system_ui_statusbar_lock_screen_show_spacing"
    private const val DEFAULT_SHOW_SPACING = 6
    private val showSpacingValue by lazy {
        PrefsBridge.getBoolean("system_ui_lock_screen_show_spacing_value")
    }
    // 新键不存在时才回退读取旧键，避免已配置的用户继续使用错误键
    private val showSpacingSeconds by lazy {
        val hasNewKey = runCatching {
            PrefsBridge.getSharedPreferences().contains("prefs_key_$SHOW_SPACING_KEY")
        }.getOrDefault(false)
        if (hasNewKey) {
            PrefsBridge.getInt(SHOW_SPACING_KEY, DEFAULT_SHOW_SPACING)
        } else {
            PrefsBridge.getInt(SHOW_SPACING_LEGACY_KEY, DEFAULT_SHOW_SPACING)
        }
    }
    private val isShowTemp by lazy {
        PrefsBridge.getBoolean("system_ui_show_battery_temperature")
    }
    private val isShowMoreC by lazy {
        PrefsBridge.getBoolean("system_ui_show_charging_c_more")
    }

    @SuppressLint("SetTextI18n")
    override fun init() {
        // 去除单行限制
        val clazzDependency = findClass("com.android.systemui.Dependency")
        val clazzKeyguardIndicationController =
            findClass("com.android.systemui.statusbar.KeyguardIndicationController")

        if (showSpacingValue) {
            getHotReloadRuntimeState(STATE_TEXT_VIEW, TextView::class.java)
                ?.let { setShowSpacing(clazzDependency, clazzKeyguardIndicationController, it) }
        }

        findClassIfExists("com.android.systemui.statusbar.phone.KeyguardIndicationTextView")?.constructors?.toList()?.createHooks {
            after { param ->
                (param.thisObject as TextView).apply {
                    isSingleLine = false
                    textSize = 8.2f
                }
                if (showSpacingValue) {
                    // 是否更改刷新频率
                    setShowSpacing(
                        clazzDependency,
                        clazzKeyguardIndicationController,
                        param.thisObject as TextView
                    )
                }
            }
        }


        // 修改底部文本信息
        findClassIfExists("com.miui.charge.ChargeUtils")?: findClass("com.android.keyguard.charge.ChargeUtils").findMethod { name("getChargingHintText"); paramCount(3) }
            .createHook {
                after { param ->
                    param.result = param.result?.let {
                        "${getTemp()}$it\n${getCVP()}"
                    }
                }
            }

    }

    private fun setShowSpacing(
        clazzDependency: Class<*>,
        clazzKeyguardIndicationController: Class<*>,
        textView: TextView
    ) {
        val screenOnOffReceiver = object : BroadcastReceiver() {
            val keyguardIndicationController = runCatching {
                clazzDependency.callStaticMethod("get", clazzKeyguardIndicationController)!!
            }.getOrElse {
                val clazzMiuiStub = findClass("miui.stub.MiuiStub")
                val instanceMiuiStub =
                    clazzMiuiStub.getStaticObjectFieldOrNull("INSTANCE")!!
                val mSysUIProvider =
                    instanceMiuiStub.getObjectFieldOrNull("mSysUIProvider")!!
                val mKeyguardIndicationController =
                    mSysUIProvider.getObjectFieldOrNull("mKeyguardIndicationController")!!
                mKeyguardIndicationController.callMethod("get")!!
            }
            val handler = Handler(textView.context.mainLooper)
            val runnable = object : Runnable {
                val clazzMiuiDependency =
                    findClass("com.miui.systemui.MiuiDependency")
                val clazzMiuiChargeController =
                    findClass("com.miui.charge.MiuiChargeController")
                val sDependency =
                    clazzMiuiDependency.getStaticObjectFieldOrNull("sDependency")!!
                val mProviders =
                    sDependency.getObjectFieldOrNull("mProviders") as ArrayMap<*, *>
                val mMiuiChargeControllerProvider = mProviders[clazzMiuiChargeController]!!
                val instanceMiuiChargeController = mMiuiChargeControllerProvider
                        .getObjectFieldOrNull("f$0")!!
                        .callMethod("get")!!

                override fun run() {
                    try {
                        doUpdateForHyperOS()
                        handler.postDelayed(
                            this,
                            showSpacingSeconds / 2 * 1000L
                        )
                    } catch (t: Throwable) {
                        // 反射/字段形状异常时停止轮询，避免在主线程反复抛异常
                        XposedLog.e(TAG, "ChargingCVP refresh failed, stop posting", t)
                    }
                }

                fun doUpdateForHyperOS() {
                    val mBatteryStatus =
                        instanceMiuiChargeController.getObjectFieldOrNull("mBatteryStatus")!!
                    val level = mBatteryStatus.getObjectFieldOrNull("level")
                    val plugged = mBatteryStatus.getObjectFieldOrNull("plugged") as Int
                    val isPluggedIn =
                        mBatteryStatus.callMethod("isPluggedIn", plugged)
                    val mContext =
                        instanceMiuiChargeController.getObjectFieldOrNull("mContext")
                    val clazzChargeUtils =
                        findClass("com.miui.charge.ChargeUtils", lpparam.classLoader)
                    val chargingHintText =
                        callStaticMethod(
                            clazzChargeUtils,
                            "getChargingHintText",
                            level,
                            isPluggedIn,
                            mContext
                        )
                    keyguardIndicationController.setObjectField("mComputePowerIndication", chargingHintText)
                    keyguardIndicationController.callMethod(
                        "updateDeviceEntryIndication",
                        null,
                        false
                    )
                }
            }

            init {
                if ((textView.context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
                    handler.post(runnable)
                }
            }

            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        handler.post(runnable)
                    }

                    Intent.ACTION_SCREEN_OFF -> {
                        handler.removeCallbacks(runnable)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val context = textView.context
        context.registerReceiver(
            screenOnOffReceiver, filter, Context.RECEIVER_EXPORTED
        )
        registerReceiverHotReloadCleanup(context, screenOnOffReceiver)
        registerHotReloadCleanup {
            screenOnOffReceiver.handler.removeCallbacks(screenOnOffReceiver.runnable)
        }
        putHotReloadRuntimeState(STATE_TEXT_VIEW, textView)
    }

    private fun getTemp(): String {
        var temp = 0.0

        try {
            BufferedReader(FileReader("/sys/class/power_supply/battery/temp")).use { reader ->
                temp = BigDecimal(reader.readLine().toDouble() / 10.0)
                    .setScale(1, RoundingMode.HALF_UP)
                    .toDouble()
            }
        } catch (_: Exception) {
            temp = 0.0
        }

        return if (isShowTemp) "$temp ℃ · " else ""
    }

    @SuppressLint("DefaultLocale")
    private fun getCVP(): String {
        runCatching {
            var current = 0.0
            var voltage = 0.0
            val watt: Double by lazy {
                current * voltage
            }

            current = FileReader("/sys/class/power_supply/battery/current_now").use { fileReader ->
                BufferedReader(fileReader).use { bufferedReader ->
                    -1.0 * bufferedReader.readLine().toDouble() / 1000000.0
                }
            }
            voltage = FileReader("/sys/class/power_supply/battery/voltage_now").use { fileReader ->
                BufferedReader(fileReader).use { bufferedReader ->
                    bufferedReader.readLine().toDouble() / 1000000.0
                }
            }

            // 计算功率信息
            val power = String.format("%.2f", watt)

            // 电流展示逻辑设置
            val mCurrent = if (isShowMoreC) {
                "${(current * 1000).toInt()} mA"
            } else {
                "${String.format("%.1f", abs(current))} A"
            }
            val mVoltage = "${String.format("%.1f", voltage)} V"

            // 判断充满信息是否归零
            val showBattery = if (current == 0.0) {
                ""
            } else {
                "$mCurrent · $mVoltage · $power W"
            }

            // 输出展示信息
            return showBattery
        }
        return ""
    }
}
