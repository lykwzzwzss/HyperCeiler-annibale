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
package com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.media3

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.updateMargins
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.rules.systemui.controlcenter.media3.CustomBackground.isIsland
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.Hardware.isDarkMode
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import com.sevtinge.hyperceiler.libhook.utils.api.dp
import com.sevtinge.hyperceiler.libhook.utils.api.dpFloat
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.hyperProgressSeekBar
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.mediaViewHolderNew
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.miuiIslandMediaViewBinderImpl
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.miuiIslandMediaViewHolder
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.miuiMediaViewControllerImpl
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.seekBarObserver
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.PublicClass.seekBarObserverNew
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.media.getMediaViewHolderFieldAs
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.progress.CometSeekBar
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.progress.SquigglySeekBar
import com.sevtinge.hyperceiler.libhook.utils.hookapi.systemui.controlcenter.progress.ThumbStyle
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.afterHookConstructor
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.afterHookMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.beforeHookMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.findFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getAdditionalInstanceField
import com.sevtinge.hyperceiler.libhook.utils.hookapi.tool.getIdByName
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNullAs
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setAdditionalInstanceField
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed.appContext

object MediaSeekBar : BaseHook() {
    private const val KEY_REAL_SEEKBAR = "KEY_REAL_PROGRESS_BAR"

    // ==================== 通知中心配置 ====================
    private val ncOn by lazy {
        PrefsBridge.getBoolean("system_ui_control_center_media_control_progress_on")
    }
    private val ncProgressMode by lazy {
        PrefsBridge.getStringAsInt("system_ui_control_center_media_control_progress_mode", 0)
    }
    private val ncThumbMode by lazy {
        PrefsBridge.getStringAsInt("system_ui_control_center_media_control_progress_thumb_mode", 0)
    }
    private val ncProgressThickness by lazy {
        PrefsBridge.getInt("system_ui_control_center_media_control_progress_thickness", 80)
    }
    private val ncCornerRadius by lazy {
        PrefsBridge.getInt("system_ui_control_center_media_control_progress_corner_radius", 36)
    }
    private val ncProgressComet by lazy {
        PrefsBridge.getBoolean("system_ui_control_center_media_control_progress_comet")
    }
    private val ncProgressRound by lazy {
        PrefsBridge.getBoolean("system_ui_control_center_media_control_progress_round")
    }

    private val ncCustomThumbStyle by lazy {
        when (ncThumbMode) {
            1 -> ThumbStyle.VerticalBar
            2 -> if (ncProgressRound) ThumbStyle.RoundRect else ThumbStyle.Hidden
            else -> ThumbStyle.Circle
        }
    }

    // ==================== 灵动岛配置 ====================
    private val diOn by lazy {
        PrefsBridge.getBoolean("system_ui_island_media_control_progress_on")
    }
    private val diProgressMode by lazy {
        PrefsBridge.getStringAsInt("system_ui_island_media_control_progress_mode", 0)
    }
    private val diThumbMode by lazy {
        PrefsBridge.getStringAsInt("system_ui_island_media_control_progress_thumb_mode", 0)
    }
    private val diProgressThickness by lazy {
        PrefsBridge.getInt("system_ui_island_media_control_progress_thickness", 6)
    }
    private val diCornerRadius by lazy {
        PrefsBridge.getInt("system_ui_island_media_control_progress_corner_radius", 36)
    }
    private val diProgressComet by lazy {
        PrefsBridge.getBoolean("system_ui_island_media_control_progress_comet")
    }
    private val diProgressRound by lazy {
        PrefsBridge.getBoolean("system_ui_island_media_control_progress_round")
    }

    private val diCustomThumbStyle by lazy {
        when (diThumbMode) {
            1 -> ThumbStyle.VerticalBar
            2 -> if (diProgressRound) ThumbStyle.RoundRect else ThumbStyle.Hidden
            else -> ThumbStyle.Circle
        }
    }

    // ==================== 公共字段 ====================
    private val ncBackgroundStyle by lazy {
        PrefsBridge.getStringAsInt("system_ui_control_center_media_control_background_mode", 0)
    }
    private val ncAlwaysDark by lazy {
        PrefsBridge.getBoolean("system_ui_control_center_media_control_always_dark")
    }

    private val clzProgress by lazy {
        findClassIfExists("com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel\$Progress")
    }
    private val clzSeekBarViewModel by lazy {
        findClassIfExists("com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel")
    }

    private val fldProgressSeekBarMinHeight by lazy {
        hyperProgressSeekBar?.findFieldOrNull("mProgressSeekBarMinHeight")
    }
    private val fldProgressHeight by lazy {
        hyperProgressSeekBar?.findFieldOrNull("mProgressHeight")
    }
    private val fldListening by lazy { clzProgress?.findFieldOrNull("listening") }
    private val fldSeekAvailable by lazy { clzProgress?.findFieldOrNull("seekAvailable") }
    private val fldPlaying by lazy { clzProgress?.findFieldOrNull("playing") }
    private val fldScrubbing by lazy { clzProgress?.findFieldOrNull("scrubbing") }
    private val fldEnabled by lazy { clzProgress?.findFieldOrNull("enabled") }
    private val fldDuration by lazy { clzProgress?.findFieldOrNull("duration") }
    private val fldElapsedTime by lazy { clzProgress?.findFieldOrNull("elapsedTime") }

    private val fldFalsingManager by lazy {
        clzSeekBarViewModel?.findFieldOrNull("falsingManager")
    }
    private val ctorSeekBarChangeListener by lazy {
        findClassIfExists($$"com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel$SeekBarChangeListener")
            ?.constructors?.firstOrNull { it.parameterCount == 2 }
    }

    private val mediaBgViewId by lazy {
        appContext.getIdByName("media_progress_bar")
    }

    override fun init() {
        if (ncOn) {
            initNotificationCenter()
        }
        if (isIsland && diOn) {
            initDynamicIsland()
        }
    }

    // ==================== 通知中心 ====================

    private fun initNotificationCenter() {
        if (ncProgressMode == 0) {
            initNcDefaultProgressWidth()
            return
        }
        initNcCustomSeekBar()
    }

    private fun initNcDefaultProgressWidth() {
        if (hyperProgressSeekBar == null) return
        mediaViewHolderNew?.afterHookConstructor {
            val seekBar = it.thisObject.getObjectFieldOrNullAs<SeekBar>("seekBar") ?: return@afterHookConstructor
            if (hyperProgressSeekBar?.isInstance(seekBar) != true) return@afterHookConstructor
            applyProgressHeight(seekBar, ncProgressThickness)
        }
    }

    private fun initNcCustomSeekBar() {
        mediaViewHolderNew?.afterHookConstructor {
            getOrCreateRealSeekBar(it.thisObject, false)
        }

        if (isMoreAndroidVersion(36)) {
            seekBarObserverNew?.beforeHookMethod("onChanged") {
                val progressOwner =
                    it.thisObject.getObjectFieldOrNull("this" + 36.toChar() + "0") ?: return@beforeHookMethod
                // 新架构：progressObserver 宿主是 MiuiMediaSeekBarProgressOwner，无 holder 字段；
                // holder 由 attach 时缓存到其附加字段（见下方 controllerClass.attach 分支）。
                // 双路取 holder：优先用 attach 时缓存；失败则从 consumers 反查
                // （consumers 里是 MiuiMediaViewControllerImpl 等 RenderStateConsumer，其有 holder 字段）
                val holder = progressOwner.getAdditionalInstanceField("hc_holder")
                    ?: resolveHolderFromConsumers(progressOwner)
                val vmProgress = it.args[0] ?: return@beforeHookMethod
                if (holder == null) {
                    it.result = null
                    return@beforeHookMethod
                }
                onProgressChanged(holder, vmProgress, false)
                it.result = null
            }
        } else {
            seekBarObserver?.beforeHookMethod("onChanged") {
                val holder =
                    it.thisObject.getObjectFieldOrNull("holder") ?: return@beforeHookMethod
                val vmProgress = it.args[0] ?: return@beforeHookMethod
                onProgressChanged(holder, vmProgress, false)
                it.result = null
            }
        }

        val controllerClass = miuiMediaViewControllerImpl ?: return

        val fldEnableFullAod = findClassIfExists(
            "com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController"
        )?.findFieldOrNull("mEnableFullAod")
        val metLazyGet = findClassIfExists("dagger.Lazy")
            ?.declaredMethods?.firstOrNull { it.name == "get" }

        controllerClass.apply {
            afterHookMethod("detach") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                getOrCreateRealSeekBar(holder, false)?.setOnSeekBarChangeListener(null)
            }

            afterHookMethod("attach") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                // seekBarViewModel 挂在 seekBarProgressOwner 上，不在 MiuiMediaViewControllerImpl 上！
                // 旧代码从 thisObject 取 → 恒 null → 整段静默 return →
                // ① listener 从未绑定（拖动无效）② hc_holder 也从未缓存（holderCached=false）。
                val progressOwner = param.thisObject.getObjectFieldOrNull("seekBarProgressOwner")
                val seekBarViewModel = progressOwner?.getObjectFieldOrNull("seekBarViewModel")
                    ?: param.thisObject.getObjectFieldOrNull("seekBarViewModel")
                if (seekBarViewModel == null) return@afterHookMethod
                runCatching {
                    progressOwner?.setAdditionalInstanceField("hc_holder", holder)
                    // 反向缓存 owner 到 holder：拖动自建 listener 需要 owner.currentController 才能 seekTo
                    progressOwner?.let { holder.setAdditionalInstanceField("hc_owner", it) }
                }.onFailure { XposedLog.e(TAG, "cache holder to progressOwner failed: ${it.message}") }
                bindSeekBarListener(holder, seekBarViewModel, false)
            }

            afterHookMethod("onFullAodStateChanged") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                val toFullAod = param.args[0] as Boolean
                getOrCreateRealSeekBar(holder, false)?.visibility = if (toFullAod) View.GONE else View.VISIBLE
            }

            afterHookMethod("updateForegroundColors") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                val controller = param.thisObject.getObjectFieldOrNull("fullAodController")
                    ?.let { metLazyGet?.invoke(it) }
                val enableFullAod = fldEnableFullAod?.get(controller) == true
                val isDark = enableFullAod || isDarkMode() || (ncBackgroundStyle == 0 && ncAlwaysDark)
                val seekBar = getOrCreateRealSeekBar(holder, false) ?: return@afterHookMethod
                seekBar.progressTintList = ColorStateList.valueOf(if (isDark) Color.WHITE else Color.BLACK)
                XposedLog.d(TAG, lpparam.packageName, "updateForegroundColors: isDark=$isDark (fullAod=$enableFullAod, alwaysDark=$ncAlwaysDark)")
            }
        }
    }

    // ==================== 灵动岛 ====================

    private fun initDynamicIsland() {
        if (diProgressMode == 0) {
            initDiDefaultProgressWidth()
            return
        }
        initDiCustomSeekBar()
    }

    private fun initDiDefaultProgressWidth() {
        if (hyperProgressSeekBar == null) return
        miuiIslandMediaViewHolder?.afterHookConstructor {
            val seekBar = it.thisObject.getMediaViewHolderFieldAs<SeekBar>("seekBar", true) ?: return@afterHookConstructor
            if (hyperProgressSeekBar?.isInstance(seekBar) != true) return@afterHookConstructor
            applyProgressHeight(seekBar, diProgressThickness)
        }
    }

    private fun initDiCustomSeekBar() {
        miuiIslandMediaViewHolder?.afterHookConstructor {
            getOrCreateRealSeekBar(it.thisObject, true)
        }

        miuiIslandMediaViewBinderImpl?.let { binderClass ->
            binderClass.declaredMethods.firstOrNull {
                it.name.contains("seekBarChanged") && java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 3
            }?.let { method ->
                binderClass.beforeHookMethod(method.name, *method.parameterTypes) { param ->
                    val holder = param.args[2] ?: return@beforeHookMethod
                    val vmProgress = param.args[1] ?: return@beforeHookMethod
                    onProgressChanged(holder, vmProgress, true)
                    param.result = null
                }
            }

            binderClass.afterHookMethod("detach") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                val dummyHolder = param.thisObject.getObjectFieldOrNull("dummyHolder") ?: return@afterHookMethod
                getOrCreateRealSeekBar(holder, true)?.setOnSeekBarChangeListener(null)
                getOrCreateRealSeekBar(dummyHolder, true)?.setOnSeekBarChangeListener(null)
            }

            binderClass.afterHookMethod("attach") { param ->
                val holder = param.thisObject.getObjectFieldOrNull("holder") ?: return@afterHookMethod
                val dummyHolder = param.thisObject.getObjectFieldOrNull("dummyHolder") ?: return@afterHookMethod
                val seekBarViewModel = param.thisObject.getObjectFieldOrNull("seekBarViewModel") ?: return@afterHookMethod
                bindSeekBarListener(holder, seekBarViewModel, true)
                bindSeekBarListener(dummyHolder, seekBarViewModel, true)
            }
        }
    }

    // ==================== 公共方法 ====================

    private fun applyProgressHeight(seekBar: SeekBar, thickness: Int) {
        val context = seekBar.context
        var height = dp2px(context, thickness.toFloat())
        if (height % 2 != 0) height -= 1
        fldProgressSeekBarMinHeight?.apply { isAccessible = true }?.set(seekBar, height)
        fldProgressHeight?.apply { isAccessible = true }?.set(seekBar, height)
    }

    /** 从 ProgressOwner 的 consumers 集合反查 holder（新架构无 holder 字段的兜底路径）。 */
    private fun resolveHolderFromConsumers(owner: Any): Any? = runCatching {
        val consumers = owner.getObjectFieldOrNull("consumers") as? Iterable<*> ?: return null
        for (c in consumers) {
            if (c == null) continue
            c.getObjectFieldOrNull("holder")?.let { return it }
        }
        null
    }.getOrNull()

    private fun bindSeekBarListener(holder: Any, seekBarViewModel: Any, isDynamicIsland: Boolean) {
        val falsingManager = fldFalsingManager?.get(seekBarViewModel)
        val rawListener = ctorSeekBarChangeListener?.newInstance(seekBarViewModel, falsingManager)
        // 系统 listener 类在新版已成「死类」：只有 3 个回调方法、无 <init>、全库无人实例化
        //（实测 ctor=false raw=false）。新系统改用 miuix HyperProgressSeekBar 的
        // OnRangeChangedListener 机制，而本模块已把它替换为标准 SeekBar，该机制随之丢失
        // → 拖动时滑块会动，但不通知 ViewModel，歌曲进度不变。
        // 因此取不到系统 listener 时回退为自建 listener：松手后直接 MediaController.seekTo。
        val listener = rawListener as? SeekBar.OnSeekBarChangeListener
            ?: createSelfSeekListener(holder)
        val seekBar = getOrCreateRealSeekBar(holder, isDynamicIsland)
        seekBar?.setOnSeekBarChangeListener(listener)
    }

    /**
     * 自建 seek listener —— 系统 OnSeekBarChangeListener 实现类失效时的兜底。
     * 通过 holder 附加字段 hc_owner 找到 MiuiMediaSeekBarProgressOwner，取其 currentController 执行 seekTo。
     */
    private fun createSelfSeekListener(holder: Any): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                // 拖动期间禁止 onProgressChanged 用播放位置回写 progress，否则滑块会被"弹回"
                holder.setAdditionalInstanceField("hc_scrubbing", true)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                holder.setAdditionalInstanceField("hc_scrubbing", false)
                // SeekBar 的 max/progress 单位与 MediaController.seekTo 一致（毫秒），可直接传
                val progress = sb?.progress ?: return
                val owner = holder.getAdditionalInstanceField("hc_owner")
                val controller = owner?.getObjectFieldOrNull("currentController")
                if (controller != null) {
                    // seekTo 不在 MediaController 上，而在 MediaController.TransportControls 上
                    // （产物实证：MediaController;->getTransportControls()）。
                    // 统一用反射，避免编译期依赖；用 methods 搜索而非 Class.forName 拼 $ 内部类名。
                    runCatching {
                        val tc = controller.javaClass.methods
                            .firstOrNull { it.name == "getTransportControls" && it.parameterTypes.isEmpty() }
                            ?.invoke(controller)
                        val seekTo = tc?.javaClass?.methods
                            ?.firstOrNull { it.name == "seekTo" && it.parameterTypes.size == 1 }
                        if (tc == null || seekTo == null) {
                            XposedLog.e(TAG, lpparam.packageName, "selfSeek: tc=" + (tc != null)
                                + " seekTo=" + (seekTo != null))
                        } else {
                            seekTo.invoke(tc, progress.toLong())
                        }
                    }.onFailure {
                        XposedLog.e(TAG, lpparam.packageName, "selfSeek: seekTo failed: "
                            + it.javaClass.simpleName + "/" + it.message)
                    }
                }
            }
        }

    @SuppressLint("SetTextI18n")
    private fun onProgressChanged(holder: Any, vmProgress: Any, isDynamicIsland: Boolean) {
        val seekBar = getOrCreateRealSeekBar(holder, isDynamicIsland) ?: return
        val elapsedTimeView = holder.getMediaViewHolderFieldAs<TextView>("elapsedTimeView", isDynamicIsland)
        val totalTimeView = holder.getMediaViewHolderFieldAs<TextView>("totalTimeView", isDynamicIsland)

        val listening = fldListening?.get(vmProgress) == true
        val seekAvailable = fldSeekAvailable?.get(vmProgress) == true
        val playing = fldPlaying?.get(vmProgress) == true
        val scrubbing = fldScrubbing?.get(vmProgress) == true
        val enabled = fldEnabled?.get(vmProgress) == true
        val duration = fldDuration?.get(vmProgress) as? Int ?: 0
        val elapsedTime = fldElapsedTime?.get(vmProgress) as? Int

        if (enabled) {
            totalTimeView?.text = DateUtils.formatElapsedTime(duration / 1000L)
            seekBar.isEnabled = seekAvailable
            seekBar.max = duration
            elapsedTime?.let {
                elapsedTimeView?.text = DateUtils.formatElapsedTime(it / 1000L)
                // 拖动中（含自建 listener 的 hc_scrubbing）不回写 progress，避免滑块被弹回
                val selfScrubbing = holder.getAdditionalInstanceField("hc_scrubbing") == true
                if (!scrubbing && !selfScrubbing) seekBar.progress = it
            }
            if (seekBar is SquigglySeekBar) {
                seekBar.animate = playing && !scrubbing && listening
                seekBar.transitionEnabled = !seekAvailable
            }
        } else {
            seekBar.isEnabled = false
            seekBar.progress = 0
            seekBar.contentDescription = ""
            elapsedTimeView?.text = "00:00"
            totalTimeView?.text = "00:00"
            if (seekBar is SquigglySeekBar) {
                seekBar.animate = false
            }
        }
    }

    private fun getOrCreateRealSeekBar(holder: Any, isDynamicIsland: Boolean): SeekBar? {
        val existing = holder.getAdditionalInstanceField(KEY_REAL_SEEKBAR) as? SeekBar
        if (existing != null) return existing

        val mode = if (isDynamicIsland) diProgressMode else ncProgressMode
        val thickness = if (isDynamicIsland) diProgressThickness * 8 else ncProgressThickness
        val comet = if (isDynamicIsland) diProgressComet else ncProgressComet
        val thumb = if (isDynamicIsland) diCustomThumbStyle else ncCustomThumbStyle

        val origSeekBar = if (isDynamicIsland) {
            holder.getMediaViewHolderFieldAs<SeekBar>("seekBar", true)
        } else {
            holder.getObjectFieldOrNullAs<SeekBar>("seekBar")
        }
        val parent = origSeekBar?.parent as? ViewGroup
        if (origSeekBar == null || parent == null) return null
        val context = origSeekBar.context
        val index = (parent.indexOfChild(origSeekBar) + 1).coerceIn(0, parent.childCount)

        val realSeekBar: SeekBar = when (mode) {
            1 -> {
                // SquigglySeekBar（波浪线进度条）
                SquigglySeekBar(context).apply {
                    id = mediaBgViewId
                    layoutParams = origSeekBar.layoutParams?.apply {
                        (this as? ViewGroup.MarginLayoutParams)?.updateMargins(top = 0, bottom = 0)
                    }
                    thumbStyle = thumb
                    waveLength = 20.dpFloat(context)
                    lineAmplitude = 1.5.dpFloat(context)
                    phaseSpeed = 8.dpFloat(context)
                    strokeWidth = 2.dpFloat(context)
                }
            }
            2 -> {
                // CometSeekBar（圆滑自定义进度条）
                val cornerRadius = if (isDynamicIsland) diCornerRadius else ncCornerRadius
                CometSeekBar(context).apply {
                    id = mediaBgViewId
                    layoutParams = origSeekBar.layoutParams?.apply {
                        (this as? ViewGroup.MarginLayoutParams)?.updateMargins(top = 0, bottom = 0)
                    }
                    progressHeight = thickness.dp
                    progressCornerRadius = cornerRadius.dp.toFloat()
                    cometEffect = comet
                    thumbStyle = thumb
                }
            }
            else -> return null
        }

        parent.addView(realSeekBar, index)
        parent.removeView(origSeekBar)
        holder.setAdditionalInstanceField(KEY_REAL_SEEKBAR, realSeekBar)
        XposedLog.d(TAG, lpparam.packageName, "getOrCreateRealSeekBar: created mode=$mode isDI=$isDynamicIsland")
        return realSeekBar
    }
}
