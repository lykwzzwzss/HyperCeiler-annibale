/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.os.IBinder
import com.sevtinge.hyperceiler.common.utils.PrefsBridge

/**
 * Data carriers for [HomeDockWindow].
 *
 * <p>Split out of the rule file purely for size: every type here is a plain value holder with no
 * dependency on the owning hook, so moving it changes no behaviour. Kept `internal` (rather than
 * file-private as before) because Kotlin's `private` at top level is file-scoped and the rule file
 * still has to name them.
 */

/**
 * One resolved dock appearance configuration.
 *
 * <p>Read once per refresh and swapped in whole (`settings = resolved`), so a reader either sees a
 * fully consistent snapshot or the previous one - never a half-applied mix.
 */
internal data class Settings(
    val enabled: Boolean, val mode: Int, val color: Int, val height: Int,
    val margin: Int, val bottom: Int, val radius: Int, val nightMode: Int,
    val revealStyle: DockUnlockReveal.Style
) {
    companion object {
        fun read() = Settings(
            PrefsBridge.getBoolean("home_dock_bg_custom_enable"),
            DockWindowPolicy.normalizeBackgroundMode(PrefsBridge.getStringAsInt("home_dock_add_blur", 1)),
            PrefsBridge.getInt("home_dock_bg_color", 0),
            PrefsBridge.getInt("home_dock_bg_height", 150),
            PrefsBridge.getInt("home_dock_bg_margin_horizontal", 25),
            PrefsBridge.getInt("home_dock_bg_margin_bottom", 15),
            PrefsBridge.getInt("home_dock_bg_radius", 30),
            PrefsBridge.getStringAsInt("home_other_home_mode", 0),
            DockUnlockReveal.Style.of(PrefsBridge.getString("home_dock_unlock_style", "daybreak"))
        )
    }

    val blur get() = mode == 1 || mode == DockGlassPreset.MODE
    val glass get() = mode == DockGlassPreset.MODE
}

/** Everything that decides whether the current layer's material has to be rebuilt. */
internal data class Appearance(val config: Settings, val bounds: DockWindowPolicy.Bounds,
    val dark: Boolean, val visible: Boolean, val glass: DockGlassClient.Ticket?) {
    val surface = glass?.lease
    // A capture frozen with the drawable freeze still composites its last home frame, so the
    // panel stays native glass. Only the vendor's undraw fallback has no visible source and
    // needs the compositor fallback to carry it. Readiness is part of the key, so this flip
    // re-runs applyAppearance.
    val ready = glass?.ready == true && !glass.dead && surface != null && !glass.captureUndrawn
    val key = "$config/$bounds/$dark/$visible/$surface/$ready/${glass?.dead}/" +
        "${glass?.capturePaused}/${glass?.captureUndrawn}"
}

/**
 * One launcher window's effect layer and every piece of state that follows it.
 *
 * <p>Holds both the live surfaces and the last accepted native sample, so it is deliberately
 * mutable; the three `reset*` helpers below are the only state machines it owns.
 */
internal data class Layer(val parent: Any, val effect: Any, val tint: Any,
    var appearance: String = "", var glass: DockGlassClient.Ticket? = null,
    var glassRotationEpoch: Int = 0,
    val motion: DockRecentsMotion = DockRecentsMotion(),
    val nativeMotion: DockNativeMotion = DockNativeMotion(),
    var nativeUid: Int = -1, var nativePid: Int = -1,
    var nativeApplied: Boolean = false, var overview: Boolean = false,
    /**
     * Last scene this layer reacted to, or [HomeDockWindow.NO_SCENE] when there is none.
     *
     * <p>Deliberately *not* the same thing as "not initialised". Every place that used to
     * write `-1` here is a place that either dropped the identity or lost the sample, and
     * writing the same sentinel from the frame loop's invisible branch made the value flip
     * between `-1` and a real scene on alternating frames - one `native motion scene=` record
     * per flip. Clearing the identity clears this along with it, so the sentinel keeps its
     * intended meaning: no sample has been accepted for this layout.
     */
    var nativeScene: Int = HomeDockWindow.NO_SCENE,
    var overviewGeneration: Long = 0, var nativeOverviewGeneration: Long = 0,
    var lastOverviewValidationNs: Long = 0,
    var lastVisible: Boolean? = null,
    /** True while the launcher's overlay (minus-one screen) is what keeps this layer hidden. */
    var overlayHidden: Boolean = false,
    var lastGlassReady: Boolean? = null,
    var motionSession: Any? = null, var motionClient: IBinder? = null,
    var motionSamples: Int = 0, var motionEndPending: Boolean = false,
    var baseY: Int = 0, var density: Float = 0f, var motionTime: Long = 0,
    var nativeSampleDeadlineNs: Long = 0,
    var autoAimSequence: Long = 0, var autoAimDeadlineNs: Long = 0,
    var autoAimLive: Boolean = false, var autoAimSamples: Int = 0,
    var x: Float = Float.NaN, var y: Float = Float.NaN,
    /** Launcher frame size, the coordinate space [x]/[baseY] live in. Drives the AUTO_AIM pivot. */
    var parentWidth: Int = 0, var parentHeight: Int = 0,
    val reveal: DockUnlockReveal = DockUnlockReveal(),
    var width: Int = 0, var height: Int = 0, var radius: Float = 0f,
    var revealScaleX: Float = 1f, var revealScaleY: Float = 1f,
    var revealCropWidth: Float = 1f, var revealCropHeight: Float = 1f,
    var revealCornerProgress: Float = 1f,
    var revealAlpha: Float = 1f, var revealSlide: Float = 0f,
    /** When the current glass host died, so its replacement is rate limited. */
    var glassDroppedAt: Long = 0L) {
    /** Drop every cached native identity/sample after the launcher Session changed. */
    fun resetNativeMotion() {
        nativeMotion.reset()
        nativeApplied = false
        nativeScene = HomeDockWindow.NO_SCENE
        nativeSampleDeadlineNs = 0
        motionSamples = 0
        motionEndPending = false
        nativeUid = -1
        nativePid = -1
        resetAutoAim()
    }

    /**
     * Drop everything that authenticates a native sample, keeping the layout state.
     *
     * <p>This is the half of [resetNativeMotion] that a layer which merely stopped being
     * visible needs: the sample it was following is gone, so the next accepted one must not be
     * compared against a scene from the previous session - but the window is still the same
     * window, so re-resolving its Session on every hidden frame would be pure churn.
     */
    fun clearNativeSample() {
        nativeMotion.reset()
        nativeApplied = false
        nativeScene = HomeDockWindow.NO_SCENE
        nativeSampleDeadlineNs = 0
        resetAutoAim()
    }

    /** Drop the live unlock footprint without disturbing the independent recents state. */
    fun resetAutoAim() {
        autoAimSequence = 0
        autoAimDeadlineNs = 0
        autoAimLive = false
        autoAimSamples = 0
    }
}

/** One pending frame callback and the epoch it belongs to. */
internal data class ScheduledFrame(val epoch: Long, val tick: Runnable)

/** One background transform for one frame. The pose is identity unless the reveal is live. */
internal data class FrameUpdate(val layer: Layer, val y: Float,
    val pose: DockUnlockReveal.ContainerPose, val alpha: Float, val slide: Float,
    var appliedScaleX: Float = 1f, var appliedScaleY: Float = 1f)

/** Last container pose pushed to the transaction, used to skip redundant writes. */
internal data class AppliedContainer(val scaleX: Float, val scaleY: Float)
