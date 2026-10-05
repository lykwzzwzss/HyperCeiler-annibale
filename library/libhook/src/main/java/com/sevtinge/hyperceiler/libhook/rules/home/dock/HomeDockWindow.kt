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
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.SharedPreferences
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.WindowManager
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs
import java.util.IdentityHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HYOS launcher has no ART Activity: its Java module entry and JNI preference setter do not run.
 * WMS still owns its windows. Attach an effect layer below the launcher buffer, above wallpaper,
 * using the existing system scope and remote preferences. No injected input window is necessary.
 */
class HomeDockWindow : BaseHook() {
    /**
     * Shared constants. `internal` rather than `private` because [DockWindowModels]'s `Layer`
     * carries [NO_SCENE] as a default and Kotlin's top-level `private` is file-scoped.
     */
    internal companion object {
        const val LOG_TAG = "system"
        const val METHOD_CLOSE = "close"
        const val METHOD_RELEASE = "release"
        const val WM_HANDLER = "mH"
        const val WM_LOCK = "mGlobalLock"
        const val IS_VALID = "isValid"
        const val SET_POSITION = "setPosition"
        const val SET_LAYER = "setLayer"
        const val SET_CROP = "setWindowCrop"
        const val SET_RADIUS = "setCornerRadius"
        const val TRANSACTION = "android.view.SurfaceControl\$Transaction"
        const val STALE_FRAME_NS = 50_000_000L
        /**
         * Cadence of the motion tick that replaced our private vsync clock. The panel runs at
         * 120Hz, so 8ms keeps the row in step with it without owning a DisplayEventReceiver.
         */
        const val FRAME_INTERVAL_MS = 8L
        /**
         * Title prefix of the launcher's overlay window - the minus-one screen. The launcher
         * names the window itself (the string never appears in services.jar), and WMS owns it,
         * so it is the one launcher state this process can observe.
         */
        const val LAUNCHER_OVERLAY_TITLE = "LauncherOverlayWindow"
        /** Minimum gap between window-map scans while the overlay is not known to be showing. */
        const val OVERLAY_SCAN_MS = 50L
        /** Failures older than this start a fresh count, so a slow boot hiccup self-heals. */
        const val PREPARE_FAILURE_WINDOW_MS = 10_000L
        /** Consecutive failures inside that window before the Dock gives up for this process. */
        const val PREPARE_FAILURE_LIMIT = 3
        const val NATIVE_BIND_SWEEP_MS = 1_000L
        /**
         * Ceiling for the sweep's idle backoff.
         *
         * <p>The sweep's job is to re-assert what the framework does not guarantee on an idle
         * window: the native motion identity and the dock preferences. Neither needs a timer while
         * nothing changes - every traversal, layer change and preference event resets the cadence
         * (see [resetSweepCadence]). The ceiling is deliberately low (8 s, not minutes): if any
         * state is ever wrong, this is also how long the Dock can stay wrong, and 2026-09-14 showed
         * a mis-evaluated overlay flag can hold the Dock hidden until the next traversal.
         */
        const val SWEEP_BACKOFF_MAX_MS = 8_000L
        /**
         * Sweep interval while the panel is asleep (non-interactive).
         *
         * <p>Doze makes everything the sweep maintains moot: the launcher cannot draw, so no
         * native sample can arrive, no geometry can visibly change, and the rotation gate cannot
         * flip. 2026-09-17 measured this loop at a 1-2 s cadence through the night in system_server
         * (each tick also re-reads dock preferences and the display rotation) - exactly the kind of
         * idle cost a battery report blames on the panel. 30 s matches the native health worker's
         * own doze gate; waking the screen restores the fast cadence via the first interactive
         * traversal, which also calls [resetSweepCadence], so the worst case is one heartbeat of
         * sweep absence after wake - invisible, because the traversal paths do the real work.
         */
        const val SWEEP_DOZE_INTERVAL_MS = 30_000L
        /** Reveal (821ms) plus a margin for the keyguard/home wallpaper swap to settle. */
        const val MATERIAL_SETTLE_MS = 1_600L
        /** Slack added to the rotation settle window before asking for the resuming traversal. */
        const val ROTATION_RESUME_MARGIN_MS = 80L
        /** "No scene accepted yet" for [Layer.nativeScene]; never written by the frame loop. */
        const val NO_SCENE = -1
        /**
         * Rate limit for the one scene record the frame loop still writes.
         *
         * <p>A scene transition is the only interesting event in that loop, so it is kept - but a
         * scene/scale pair that oscillates would otherwise write one record per flip at 120 Hz.
         * 150 ms matches the sampling window [DockGlassClient.Journal] uses for its frame noise.
         */
        const val SCENE_LOG_MIN_GAP_MS = 150L
    }




    internal val layers = IdentityHashMap<Any, Layer>()
    // Protected by layers; do not retain retired launcher windows when the dock is disabled.
    internal var revealStyleWindow = WeakReference<Any>(null)
    internal val observed = HashSet<String>()
    internal var stopped = false
    internal var settings = Settings.read()
    internal var service: Any? = null
    internal var blurAvailable = true
    private var revealScalingAvailable = true
    private var revealCropAvailable = true
    // Protected by WM_LOCK, then layers. Covers a Dock created by the unlock traversal itself.
    internal var pendingRevealAt = -1L

    // TODO(twitch-diag): temporary - true for 3s after each unlock so the post-reveal write
    // sequence (which writer moves the dock when) can be traced; remove once the twitch is fixed.
    internal fun revealDebugActive(): Boolean =
        pendingRevealAt >= 0L && SystemClock.uptimeMillis() - pendingRevealAt <= 3000L

    // TODO(twitch-diag): rate limit for the temporary frame-write trace.
    private var lastFrameDbgUptime = 0L

    /**
     * Whether the unlock's wallpaper-swap settle window is active.
     *
     * While the keyguard and home wallpapers differ, the transition swaps them, which flips the
     * glass darkness probe and would tear the material down and rebuild it mid-transition - the
     * flash the user sees as "sampling two different wallpapers". During this window the dock
     * keeps its current material (and the reveal's fade-in hides most of that period anyway);
     * exactly one rebuild is allowed once the window closes.
     */
    internal fun materialSettleActive(): Boolean =
        pendingRevealAt >= 0L && SystemClock.uptimeMillis() - pendingRevealAt <= MATERIAL_SETTLE_MS
    internal var commandSamples = 0
    private val processGuard = DockGlassProcessGuard()
    private val displayListenerRegistered = AtomicBoolean(false)
    private val rotationHookFailureReported = AtomicBoolean(false)
    internal val glassClient = DockGlassClient(processGuard, { requestTraversal() },
        // New hosts retain the settle guard; a retained source can resume as soon as portrait returns.
        rotationSettling = { layerUpdate.isRotationSettling() },
        rotationActive = { layerUpdate.isDisplayRotated() })
    internal val nativeMotionEndpoint = DockNativeMotionEndpoint(
        {
            if (directMotionAvailable) scheduleAnimationFrame(true) else {
                requestTraversal()
                scheduleDirectMotionRecovery()
            }
        },
        {
            // Native's idle packet does not refresh motion state. It only proves transport
            // liveness and exercises our dedicated frame receiver before the next gesture.
            if (directMotionAvailable) scheduleAnimationFrame() else scheduleDirectMotionRecovery()
        },
        glassClient::record)
    internal val nativeMotionReply = ThreadLocal<Int>()
    internal val layoutEndpoint = HomeLayoutNativeEndpointOS4()
    internal val layoutReply = ThreadLocal<HomeLayoutNativeEndpointOS4.Snapshot>()
    private val frameEpoch = AtomicLong(0)
    private val scheduledFrameEpoch = AtomicLong(0)
    private val scheduledFrameStartedNs = AtomicLong(0)
    private val urgentFrameRecoveryScheduled = AtomicBoolean(false)
    private val directRecoveryScheduled = AtomicBoolean(false)
    private val nativeBindSweepScheduled = AtomicBoolean(false)
    internal var animationAvailable = true
    internal var directMotionAvailable = true
    // Owned and used only on WMS's handler thread, and driven by a plain Handler post rather
    // than a Choreographer. A vsync receiver we create inside system_server can be torn down
    // by the platform (null DisplayEventReceiver.mReceiverPtr) while the display looper still
    // holds a dispatched callback; the next getLatestVsyncEventData then dereferences it and
    // kills system_server with a SIGSEGV that no try/catch can survive. That is why this file
    // no longer creates, retains or un-latches a frame clock: a Handler tick has no lifetime
    // the platform can yank out from under us.
    private var motionTransaction: Any? = null
    private var scheduledFrame: ScheduledFrame? = null
    private var directRecoveryDelay = 100L
    // Display thread only. Cached overlay WindowState and its last observed visibility; see
    // launcherOverlayVisible(). The overlay outlives a page change but not a launcher restart.
    private var overlayWindow: Any? = null
    private var overlayScannedAt = 0L
    private var overlayVisible = false
    // Display thread only. Consecutive pose/traversal failures inside one short window; see
    // notePrepareFailure(). Reset as soon as the failures stop being consecutive.
    private var prepareFailures = 0
    private var prepareFailureWindowAt = 0L
    /** Minimum gap between replacing a dead glass host, so a crash loop cannot spin. */
    internal val GLASS_REBUILD_BACKOFF_MS = 2_000L
    /** Current sweep cadence; backs off while idle, resets to the fast interval on any activity. */
    @Volatile private var sweepIntervalMs = NATIVE_BIND_SWEEP_MS

    /**
     * Return the sweep to its fast cadence.
     *
     * <p>Called from every path that means "something about the launcher may have changed": a
     * traversal, a layer appearing or disappearing, a preference event, a launcher window being
     * (re)added. Backing off is only correct while none of those happen.
     */
    private fun resetSweepCadence() {
        sweepIntervalMs = NATIVE_BIND_SWEEP_MS
    }

    /**
     * Resolved once and kept: the native health worker asks for the panel state on every pass, and
     * resolving the service again each time would add a reflective field read per second for no
     * benefit. system_server's own context outlives every hook installed here.
     */
    @Volatile private var powerManager: PowerManager? = null

    /**
     * Whether the panel is in an interactive state, answered to the launcher's native health worker.
     *
     * <p>With the panel in doze the launcher cannot draw, so no patched call site can run and
     * maintaining those patches once a second is pure cost - it kept the launcher process waking
     * through the whole night for a desktop nobody could see. The launcher's native side cannot
     * read this itself: every path that carries the panel state (backlight, the DRM connector) is
     * root-only on this ROM. system_server is where PowerManagerService lives, so answering here is
     * a local read rather than a cross-process one.
     *
     * <p>Fail-open on purpose: a context or service that cannot be resolved answers "interactive",
     * which keeps the worker running exactly as before instead of silently switching Dock
     * maintenance off.
     */
    private fun screenInteractive(): Boolean = runCatching {
        val manager = powerManager ?: run {
            val context = service?.getObjectFieldAs<Context>("mContext")
            context?.getSystemService(PowerManager::class.java)?.also { powerManager = it }
        }
        if (manager == null) true else manager.isInteractive
    }.getOrDefault(true)

    /**
     * The panel can remain interactive for hours while another app covers the launcher. In that
     * state its native health/layout workers have no visible code to maintain, yet their old
     * panel-only state reply kept the most expensive health pass running once per second.
     *
     * WMS already owns the tracked launcher WindowState. Read its current visibility under the
     * same WM -> layers lock order as the sweep; do not rely on lastVisible, which can lag a
     * traversal. Before the first Dock layer exists (or if reflection fails), fail open so startup
     * and unsupported builds retain their original maintenance behavior.
     */
    private fun launcherWindowVisible(): Boolean = runCatching {
        val wm = service ?: return@runCatching true
        synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
            synchronized(layers) {
                layers.isEmpty() || layers.keys.any { it.callMethod("isVisible") == true }
            }
        }
    }.getOrDefault(true)

    internal fun desktopDrawable(): Boolean = screenInteractive() && launcherWindowVisible()

    override fun init() {
        refreshSettings()
        glassClient.record("hook init diagnosticVersion=37 enabled=${settings.enabled} mode=${settings.mode}")
        glassClient.record("glass capture warmup=positive-alpha-v1 idleWait=retain-producer-v1")
        glassClient.record("glass rotation return=retained-capture-v3 pause=before-wallpaper-zoom resume=pose-committed")
        runCatching { processGuard.install() }
            .onFailure { glassClient.record("renderer guard unavailable=${it.javaClass.simpleName}") }
        val prefs = PrefsBridge.getSharedPreferences()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.contains("home_dock_") || key.endsWith("home_other_home_mode")) {
                // Advisory only. The callback now tells us when it is worth redrawing, it is no
                // longer what makes a value visible: the sweep and every traversal read the
                // preferences themselves. Refresh first so the requested traversal cannot see the
                // previous value, and never fail the hook closed over a read.
                resetSweepCadence()
                refreshSettings()
                requestTraversal()
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        registerHotReloadCleanup {
            stopped = true
            prefs?.unregisterOnSharedPreferenceChangeListener(listener)
            synchronized(layers) { layers.keys.toList().forEach { removeLayer(it) } }
            glassClient.close()
            processGuard.close()
            service?.getObjectFieldAs<Handler>(WM_HANDLER)?.post {
                cancelScheduledFrame()
                runCatching { motionTransaction?.callMethod("close") }
                motionTransaction = null
                directRecoveryScheduled.set(false)
                nativeBindSweepScheduled.set(false)
            }
        }
        DockWindowHooks(this, loadClass("com.android.server.wm.WindowState")).install()
        installRotationHook()
        installUnlockReveal()
        XposedLog.i(TAG, LOG_TAG, "WMS dock hook ready: enabled=${settings.enabled}, blur=${settings.blur}")
    }

    /**
     * Re-read every dock preference and publish the new values to [settings].
     *
     * The launcher is drawn by WMS, so restarting the desktop does not re-create this hook - it
     * only re-creates the launcher's WindowState. The snapshot taken at hook init is therefore
     * *not* refreshed by a desktop restart, and the remote preference map only advances when the
     * framework pushes an update into this process. Whenever that push is lost - LSPosed shipped a
     * dedicated fix for exactly this on system_server - the geometry stayed pinned to whatever was
     * current when system_server loaded the module. That is the reported "I changed the height,
     * restarted the desktop, nothing happened", while a local test that happened to keep the
     * update path alive appeared to work.
     *
     * Reading here is cheap (a handful of in-memory lookups on the remote map) and every caller is
     * already doing far more expensive work - one is a full layout pass. A failed read keeps the
     * last known-good values rather than failing the hook closed: a stale dock is strictly better
     * than no dock.
     *
     * @return true when at least one value actually changed.
     */
    internal fun refreshSettings(): Boolean {
        val latest = runCatching { Settings.read() }.getOrNull() ?: return false
        // The provider reads the file the settings UI wrote, so it is the source of truth. The
        // remote snapshot system_server sees can be stale for a whole process lifetime when the
        // daemon's push is lost, and nothing below the daemon - not a launcher restart, not a
        // desktop reload - ever refreshes it. Both the style and the geometry are therefore
        // overlaid from the provider; PrefsBridge remains the fallback for an unset key and for
        // the window before the first successful query.
        val liveStyle = glassClient.liveRevealStyle()
        val liveGeometry = glassClient.liveGeometry()
        var resolved = if (liveStyle == null) latest
            else latest.copy(revealStyle = DockUnlockReveal.Style.of(liveStyle))
        if (liveGeometry != null) {
            resolved = resolved.copy(
                enabled = liveGeometry.customEnable ?: resolved.enabled,
                mode = liveGeometry.addBlur
                    ?.let { DockWindowPolicy.normalizeBackgroundMode(it) } ?: resolved.mode,
                color = liveGeometry.bgColor ?: resolved.color,
                height = liveGeometry.bgHeight ?: resolved.height,
                margin = liveGeometry.marginHorizontal ?: resolved.margin,
                bottom = liveGeometry.marginBottom ?: resolved.bottom,
                radius = liveGeometry.bgRadius ?: resolved.radius,
                nightMode = liveGeometry.homeMode ?: resolved.nightMode
            )
        }
        if (resolved == settings) return false
        val previousStyle = settings.revealStyle
        settings = resolved
        // A style change must reach already-built layers; new layers read it at creation.
        synchronized(layers) {
            layers.values.forEach {
                it.reveal.setStyle(resolved.revealStyle)
                if (previousStyle != resolved.revealStyle) it.resetAutoAim()
            }
        }
        glassClient.record("prefs applied enabled=${resolved.enabled} mode=${resolved.mode} " +
            "height=${resolved.height} margin=${resolved.margin} bottom=${resolved.bottom} " +
            "radius=${resolved.radius} reveal=${resolved.revealStyle.name.lowercase()}")
        return true
    }

    /** Keeps optional scene-hook failures separate from the background's lifecycle. */


    internal val layerUpdate = DockLayerUpdater(this)
    internal fun updateLayer(window: Any) {
        // A traversal means the launcher is being laid out, so the sweep's idle backoff is over.
        resetSweepCadence()
        layerUpdate.update(window)
    }

    /**
     * Carry the rotation gate on the commit point of every rotation this ROM performs.
     *
     * <p>Nothing else observes the display while an app owns a rotated screen: WMS neither
     * traverses the hidden launcher window (so the traversal poll is out) nor keeps our sweep
     * alive (it dies with the last layer), and the display listener verified silent for
     * app-requested rotations on this device. `DisplayRotation.setRotation` is the method the
     * framework calls with the rotation it is about to use, so it fires exactly when the gate
     * has to close - and the gate only acts on a real state change, so repeats cost nothing.
     *
     * <p>A rotation that is proposed but never committed can move the gate here, so the
     * traversal poll re-reads the display itself and corrects the state on the next frame.
     */
    private fun installRotationHook() {
        runCatching {
            loadClass("com.android.server.wm.DisplayRotation")
                .getDeclaredMethod("setRotation", Integer.TYPE).apply { isAccessible = true }
                .createAfterHook { param ->
                    runCatching { if (layerUpdate.refreshRotationGate()) requestTraversal() }
                        .onFailure {
                            if (rotationHookFailureReported.compareAndSet(false, true)) {
                                glassClient.record("rotation hook body failed=" +
                                    "${it.javaClass.simpleName}: ${it.message?.take(160)}")
                            }
                        }
                }
            glassClient.record("rotation hook ready DisplayRotation#setRotation")
        }.onFailure {
            glassClient.record("rotation hook unavailable=${it.javaClass.simpleName}: " +
                "${it.message?.take(160)}")
        }
    }

    /**
     * Observe display changes as a supplementary signal. This ROM does not deliver these
     * callbacks for every app-requested rotation, so the rotation hook remains necessary even
     * when listener registration succeeds.
     *
     * <p>Registered once, from the first launcher window. The callback runs on WMS's own handler
     * and only re-reads the rotation, so a burst of display changes costs one integer comparison.
     */
    internal fun registerDisplayListener() {
        if (!displayListenerRegistered.compareAndSet(false, true)) return
        val context = runCatching { service?.getObjectFieldAs<Context>("mContext") }.getOrNull() ?: return
        runCatching {
            val manager = context.getSystemService(DisplayManager::class.java)
                ?: error("DisplayManager unavailable")
            val handler = service?.getObjectFieldAs<Handler>(WM_HANDLER)
            manager.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = onDisplayChanged(displayId)
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY && layerUpdate.refreshRotationGate()) {
                        requestTraversal()
                    }
                }
            }, handler)
            glassClient.record("display listener registered")
        }.onFailure {
            glassClient.record("display listener unavailable=${it.javaClass.simpleName}")
        }
    }

    internal fun removeLayer(window: Any) {
        val layer = layers.remove(window) ?: return
        resetSweepCadence()
        layer.glass?.let { glassClient.release(it) }
        // Only release surfaces created by this hook. Never release the host's parent handle.
        runCatching { Surfaces.destroySurface(layer.tint) }
        runCatching { Surfaces.destroySurface(layer.effect) }
    }

    /**
     * Start the dock reveal when the platform begins the unlock transition.
     *
     * `keyguardGoingAway` precedes Flutter _showPresent by roughly 20 ms in the reference trace.
     * This aligns the local reveal's epoch, not each icon's independently staggered 3D transform.
     * The existing native recents channel does not sample that Flutter unlock transform.
     *
     * Every failure is reported instead of thrown, because the dock must keep working without the
     * reveal. A failed install only costs the animation.
     */
    private fun installUnlockReveal() {
        // services.jar confirms keyguardGoingAway(int flags) on both classes. This is the
        // transition START. setKeyguardShown(false) arrives ~315 ms after Flutter _showPresent
        // on the reference device; it must not be used as an animation-start fallback.
        val targets = listOf(
            "com.android.server.wm.KeyguardController" to "keyguardGoingAway",
            "com.android.server.wm.ActivityTaskManagerService" to "keyguardGoingAway"
        )
        for ((className, methodName) in targets) {
            val type = runCatching { loadClass(className) }.getOrNull()
            if (type == null) {
                glassClient.record("unlock reveal class missing $className")
                continue
            }
            val named = hierarchyDeclaredMethods(type).filter { it.name == methodName }
            val method = named.firstOrNull { candidate ->
                candidate.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            if (method == null) {
                glassClient.record("unlock reveal no int-flags signature $className#$methodName found=" +
                    named.joinToString("|") { it.toGenericString().substringAfter("$className.") }.take(180))
                continue
            }
            val hooked = runCatching {
                method.isAccessible = true
                method.createBeforeHook {
                    if (stopped) return@createBeforeHook
                    // No screen-state guard here, deliberately: `keyguardGoingAway` is only called
                    // by KeyguardViewMediator once the unlock is authenticated (traced 14:12:
                    // exactly one call per unlock, flags 8/18, never during AOD/doze transitions -
                    // those go through setKeyguardShown instead). An isInteractive check would
                    // REJECT fingerprint unlocks that go straight from doze to keyguard-gone,
                    // killing the animation on the most common unlock path.
                    onKeyguardGone()
                }
            }.isSuccess
            if (hooked) {
                glassClient.record(
                    "unlock reveal trigger ready ${method.toGenericString()} " +
                        "phase=going-away pose=single-clock-v2 style=depth-flip-v1")
                return
            }
            glassClient.record("unlock reveal hook failed $className#$methodName")
        }
        glassClient.record("unlock reveal trigger unavailable")
    }

    /** Include inherited declarations: some ROMs keep these methods one level up. */
    private fun hierarchyDeclaredMethods(type: Class<*>): List<java.lang.reflect.Method> {
        val out = ArrayList<java.lang.reflect.Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            out.addAll(current.declaredMethods)
            current = current.superclass
        }
        return out
    }

    private fun onKeyguardGone() {
        if (stopped || !settings.enabled) return
        val wm = service ?: return
        // This is a BEFORE hook: posting the arm to mH lets keyguard removal expose the resting
        // Dock before the runnable executes. Prepare and commit the hidden pose before returning.
        // Use the same lock order as traversal; WMS's global monitor is reentrant when already held.
        runCatching {
            var armed = 0
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    val now = SystemClock.uptimeMillis()
                    if (!DockUnlockReveal.acceptsNewEvent(pendingRevealAt, now)) return
                    pendingRevealAt = now
                    layers.values.forEach {
                        // A previous launcher's final scene-3 packet may still be retained by the
                        // endpoint. The epoch check below rejects it; clearing local bookkeeping
                        // also guarantees this unlock cannot inherit a scaled container cache.
                        it.resetAutoAim()
                        if (it.reveal.arm(now)) {
                            // Keep the clock aligned with transition start even if WMS still
                            // reports the launcher hidden for the first few animation frames.
                            it.reveal.startIfArmed(now)
                            armed++
                        }
                    }
                    // Keep arm and prime atomic with respect to traversal and motion callbacks.
                    if (armed > 0) primeReveal()
                }
            }
            // Even with no existing layer, retain the event for obtainLayer and run the safety net.
            glassClient.record("unlock reveal transition-start layers=$armed uptimeMs=$pendingRevealAt " +
                "durationMs=${DockUnlockReveal.DURATION_MS} riseDp=${DockUnlockReveal.RISE_DP} " +
                "reveal=${settings.revealStyle.name.lowercase()}")
            // Hand the epoch to the glass view: the tilt is a real perspective projection but
            // it has to happen inside our own process, so it needs the absolute start time.
            synchronized(layers) {
                layers.values.forEach { layer ->
                    layer.glass?.let {
                        // A paused capture is not drawn at all, and the keyguard keeps it paused
                        // through the unlock. Bring it back before the first animation frame, or
                        // the fly-in animates an empty slot.
                        glassClient.resumeForReveal(it)
                        glassClient.notifyUnlock(it, pendingRevealAt, settings.revealStyle)
                    }
                }
            }
            // Pose the layers now, so the frame where the dock first becomes visible is already
            // the animation's first frame instead of one flash at the resting size.
            if (directMotionAvailable) scheduleAnimationFrame(true) else {
                requestTraversal()
                scheduleDirectMotionRecovery()
            }
            // A reveal that never receives another frame must not leave the dock scaled,
            // translucent - or, after priming, invisible. Restore the resting transform
            // unconditionally once the whole window has elapsed.
            wm.getObjectFieldAs<Handler>(WM_HANDLER)
                .postDelayed({ resetStuckReveal() }, DockUnlockReveal.SETTLE_MS)
            // Close the wallpaper-swap settle window: this traversal applies the home
            // wallpaper's darkness in exactly one material rebuild, and re-probes the
            // producer with fallbacks enabled in case it went unhealthy mid-transition.
            wm.getObjectFieldAs<Handler>(WM_HANDLER)
                .postDelayed({ requestTraversal() }, MATERIAL_SETTLE_MS + 100L)
        }.onFailure {
            glassClient.record("unlock reveal prepare failed=${it.javaClass.simpleName}")
        }
    }

    /**
     * Apply the visual container morph without changing the Dock's measured or laid-out bounds.
     *
     * Perspective fold and capsule fission use a centred SurfaceControl crop, so the underlying
     * glass texture keeps its full-size sampling geometry while only the visible shape opens.
     * Corner radius is interpolated against that crop. The small terminal overshoot and the
     * live AUTO_AIM footprint use the existing four-float matrix, with pivot compensation returned
     * to the caller. A ROM without the Rect crop overload falls back to folding the crop fraction
     * into the matrix; layout is never requested from an animation frame.
     */

    private fun applyRevealContainer(transaction: Any, layer: Layer,
        pose: DockUnlockReveal.ContainerPose, force: Boolean = false): AppliedContainer {
        var cropApplied = revealCropAvailable
        if (cropApplied && (force || pose.cropWidth != layer.revealCropWidth ||
                pose.cropHeight != layer.revealCropHeight)) {
            runCatching {
                val cropWidth = (layer.width * pose.cropWidth).toInt().coerceIn(1, layer.width)
                val cropHeight = (layer.height * pose.cropHeight).toInt().coerceIn(1, layer.height)
                val left = (layer.width - cropWidth) / 2
                val top = (layer.height - cropHeight) / 2
                (transaction as SurfaceControl.Transaction).setCrop(
                    layer.effect as SurfaceControl,
                    Rect(left, top, left + cropWidth, top + cropHeight))
            }.onFailure {
                revealCropAvailable = false
                cropApplied = false
                // Rect cropping may have succeeded on an earlier frame before a transient API
                // failure. Clear it through the long-established width/height overload before
                // switching to the matrix fallback, so no centred sliver can be stranded.
                transaction.callMethod(SET_CROP, layer.effect, layer.width, layer.height)
                glassClient.record("unlock reveal centred crop unavailable ${it.javaClass.simpleName}" +
                    " msg=${it.message?.take(120)}; using matrix fallback")
            }
        }
        // The native Hotseat scale is the clock for recents. Compose it on the common
        // background parent, not on the glass/tint children, so neither can drift inside it.
        val recentsScale = motionScale(layer)
        val scaleX = pose.scaleX * recentsScale * (if (cropApplied) 1f else pose.cropWidth)
        val scaleY = pose.scaleY * recentsScale * (if (cropApplied) 1f else pose.cropHeight)
        if (revealScalingAvailable && (force || scaleX != layer.revealScaleX ||
                scaleY != layer.revealScaleY)) {
            runCatching {
                transaction.callMethod("setMatrix", layer.effect, scaleX, 0f, 0f, scaleY)
            }.onFailure {
                revealScalingAvailable = false
                glassClient.record("unlock reveal scaling unavailable ${it.javaClass.simpleName}" +
                    " msg=${it.message?.take(120)}; keeping crop and fade only")
            }
        }
        if (force || pose.cornerProgress != layer.revealCornerProgress ||
            pose.cropHeight != layer.revealCropHeight) {
            transaction.callMethod(SET_RADIUS, layer.effect,
                pose.cornerRadius(layer.radius, layer.height.toFloat()))
        }
        return AppliedContainer(
            if (revealScalingAvailable) scaleX else 1f,
            if (revealScalingAvailable) scaleY else 1f)
    }

    private fun rememberContainer(layer: Layer, pose: DockUnlockReveal.ContainerPose,
        applied: AppliedContainer) {
        layer.revealScaleX = applied.scaleX
        layer.revealScaleY = applied.scaleY
        layer.revealCropWidth = pose.cropWidth
        layer.revealCropHeight = pose.cropHeight
        layer.revealCornerProgress = pose.cornerProgress
    }

    internal fun hasRestingContainer(layer: Layer): Boolean =
        layer.revealScaleX == 1f && layer.revealScaleY == 1f &&
            layer.revealCropWidth == 1f && layer.revealCropHeight == 1f &&
            layer.revealCornerProgress == 1f

    private fun containerChanged(layer: Layer, pose: DockUnlockReveal.ContainerPose): Boolean =
        pose.scaleX * motionScale(layer) * (if (revealCropAvailable) 1f else pose.cropWidth) != layer.revealScaleX ||
            pose.scaleY * motionScale(layer) * (if (revealCropAvailable) 1f else pose.cropHeight) != layer.revealScaleY ||
            pose.cropWidth != layer.revealCropWidth || pose.cropHeight != layer.revealCropHeight ||
            pose.cornerProgress != layer.revealCornerProgress

    /** The fallback continues from native progress, so the matrix has no hand-off jump. */
    private fun motionScale(layer: Layer): Float = if (layer.nativeApplied)
        layer.nativeMotion.scale() else 1f - .05f * layer.motion.progress(layer.motionTime)

    /**
     * Absolute position shift for one axis of the reveal.
     *
     * <p>Everything that reaches this method is a {@code setMatrix} scale about the layer's own
     * top-left corner, and in both cases the move the Dock has to copy belongs to the launcher,
     * not to the layer:
     *
     * <ul>
     *   <li><b>Unlock fly-in</b> ({@code pose.scale*}). On this ROM the launcher's unlock animation
     *       is a uniform scale about its own {@code pivotPoint}; the Dock inherits that transform
     *       and only has to travel the residual ray from the pivot to its resting edge. Most styles
     *       animate a container of their own, but the travel they need is the same ray - the pivot
     *       is the launcher's, not the style's.</li>
     *   <li><b>Follow scale</b> ({@link #motionScale}). It mirrors the Hotseat icons shrinking as
     *       the user drags up. The Hotseat row is bottom-anchored, so a shrunken icon moves
     *       <em>up</em> - which is the same direction the launcher's unlock pivot sends the Dock.
     *       Compensating the follow scale about the layer's own centre (the old fallback) moved it
     *       <em>down</em> instead, and that is the reversal seen on every style except AUTO_AIM:
     *       only AUTO_AIM reached the pivot branch, so only it moved with the icons.</li>
     * </ul>
     *
     * <p>The pivot term therefore applies to every style, and {@code rest*} must be the resting
     * edge ({@code layer.x} / {@code layer.baseY}), never the current already-lifted position:
     * feeding the follow lift into its own compensation is what overshot on the earlier attempt.
     */
    internal fun revealShiftX(layer: Layer, scaleX: Float, restX: Float): Float {
        val pivot = launcherPivotX(layer)
        if (pivot != null) return (pivot - restX) * (1f - scaleX)
        return DockNativeMotion.centerShift(layer.width.toFloat(), scaleX)
    }

    internal fun revealShiftY(layer: Layer, scaleY: Float, restY: Float): Float {
        val pivot = launcherPivotY(layer)
        if (pivot != null) return (pivot - restY) * (1f - scaleY)
        return DockNativeMotion.centerShift(layer.height.toFloat(), scaleY)
    }

    /** Launcher unlock pivot on X, or null while the parent frame is still unknown. */
    private fun launcherPivotX(layer: Layer): Float? =
        if (layer.parentWidth > 0) layer.parentWidth * DockUnlockReveal.REVEAL_PIVOT_X_FRACTION
        else null

    /** Launcher unlock pivot on Y, or null while the parent frame is still unknown. */
    private fun launcherPivotY(layer: Layer): Float? =
        if (layer.parentHeight > 0) layer.parentHeight * DockUnlockReveal.REVEAL_PIVOT_Y_FRACTION
        else null

    /**
     * The launcher's projected first footprint for this Dock.
     *
     * <p>The Dock sits a fixed radius away from the unlock pivot, so the launcher's own depth rule
     * and camera distance give the exact footprint it would draw the Hotseat row at on the first
     * frame. Falls back to the resting footprint when the geometry is not known yet, which leaves
     * the style showing its old behaviour rather than guessing.
     */
    private fun autoAimStartScale(layer: Layer): Float {
        if (layer.parentWidth <= 0 || layer.parentHeight <= 0 ||
            layer.baseY <= 0 || !layer.x.isFinite()) {
            return 1f
        }
        val pivotX = layer.parentWidth * DockUnlockReveal.REVEAL_PIVOT_X_FRACTION
        val pivotY = layer.parentHeight * DockUnlockReveal.REVEAL_PIVOT_Y_FRACTION
        val centerX = layer.x + layer.width * 0.5f
        val centerY = layer.baseY + layer.height * 0.5f
        val radius = kotlin.math.hypot(
            (centerX - pivotX).toDouble(), (centerY - pivotY).toDouble())
        return DockUnlockReveal.projectedStartScale(radius)
    }

    /** Undo every trace of a reveal so a cancelled or superseded one cannot leave a residue. */
    internal fun restoreRestingTransform(transaction: Any, layer: Layer) {
        val now = SystemClock.uptimeMillis()
        layer.motionTime = now
        motionOffset(layer, now)
        // setWindowCrop is the established full-bounds path and also clears any prior Rect crop.
        transaction.callMethod(SET_CROP, layer.effect, layer.width, layer.height)
        val identity = DockUnlockReveal.ContainerPose.identity()
        val applied = applyRevealContainer(transaction, layer, identity, true)
        rememberContainer(layer, identity, applied)
        transaction.callMethod("setAlpha", layer.effect, 1f)
        layer.revealAlpha = 1f
        layer.revealSlide = 0f
        layer.resetAutoAim()
        if (layer.baseY > 0 && layer.x.isFinite()) {
            val restY = layer.baseY + motionOffset(layer, SystemClock.uptimeMillis())
            // Same pivot compensation as every other write: the follow scale survives the
            // restore, so its pivot term has to survive with it or the Dock drops on release.
            transaction.callMethod(SET_POSITION, layer.effect,
                layer.x + revealShiftX(layer, applied.scaleX, layer.x),
                restY + revealShiftY(layer, applied.scaleY, layer.baseY.toFloat()))
            layer.y = restY
            // TODO(twitch-diag): every restore is suspicious while investigating the twitch.
            glassClient.record("reveal dbg restore yOff=${restY - layer.baseY}")
        }
    }

    /**
     * Resolve the container pose from its sole owner.
     *
     * AUTO_AIM consumes the latest authenticated Hotseat projection setter sample. The mapping is
     * one-to-one: a projected icon footprint becomes the same Dock surface footprint. This ROM
     * never publishes that sample per frame, so a missing sample falls back to the measured icon
     * fly-in envelope instead of leaving the Dock at rest.
     */
    private fun revealContainerPose(layer: Layer, now: Long,
        nowNs: Long = System.nanoTime()): DockUnlockReveal.ContainerPose {
        if (layer.reveal.getStyle() != DockUnlockReveal.Style.AUTO_AIM ||
            !layer.reveal.isRunning()) {
            if (layer.autoAimLive || layer.autoAimDeadlineNs != 0L) layer.resetAutoAim()
            return layer.reveal.containerPose(now)
        }
        val sample = nativeMotionEndpoint.latestAutoAim(layer.nativeUid, layer.nativePid)
        val projected = DockNativeMotion.autoAimScale(
            sample, layer.reveal.eventEpochMillis(), nowNs)
        if (projected == null) {
            if (layer.autoAimLive) {
                layer.autoAimLive = false
                layer.autoAimDeadlineNs = 0L
                glassClient.record("auto aim native projection lost; using timed fallback")
            }
            // This ROM never publishes the per-frame launcher projection through a hookable setter,
            // so a missing live sample is the normal case. Keep the style on the icons' trajectory
            // by scaling the Dock about the launcher's unlock pivot from the footprint that pivot
            // projects for the Dock's own radius.
            return layer.reveal.autoAimProjectedPose(now, autoAimStartScale(layer))
        }
        layer.autoAimDeadlineNs = sample!!.uptimeNanos() + DockNativeMotion.AUTO_AIM_MAX_AGE_NS
        if (sample.sequence() != layer.autoAimSequence) {
            layer.autoAimSequence = sample.sequence()
            if (layer.autoAimSamples++ < 8 || kotlin.math.abs(projected - 1.0) < 0.000001) {
                glassClient.record("auto aim live scale=$projected sequence=${sample.sequence()}")
            }
        }
        layer.autoAimLive = true
        return DockUnlockReveal.ContainerPose.fromProjectedScale(projected)
    }

    /**
     * Write the reveal pose for [now] and remember it, so the frame loop does not repeat a write
     * that is already on the surface.
     *
     * Shared by the prime at arm time and by the first visible appearance. The latter is what
     * guarantees the dock is never drawn at its resting size: the pose travels in the same
     * transaction that shows the layer.
     */
    internal fun applyRevealPose(transaction: Any, layer: Layer, now: Long, force: Boolean = false) {
        layer.motionTime = now
        val motionY = motionOffset(layer, now)
        val pose = revealContainerPose(layer, now)
        val alpha = layer.reveal.alpha(now)
        val applied = applyRevealContainer(transaction, layer, pose, force)
        transaction.callMethod("setAlpha", layer.effect, alpha)
        if (layer.baseY > 0 && layer.x.isFinite()) {
            val restY = layer.baseY + motionY + layer.reveal.risePx(layer.density, now)
            // Compensate about the resting origin, never the current lifted edge.
            transaction.callMethod(SET_POSITION, layer.effect,
                layer.x + revealShiftX(layer, applied.scaleX, layer.x),
                restY + revealShiftY(layer, applied.scaleY, layer.baseY.toFloat()))
            layer.y = restY
        }
        rememberContainer(layer, pose, applied)
        layer.revealAlpha = alpha
        // TODO(twitch-diag): called by prime and by applyAppearance (visibility flips) - rare.
        glassClient.record("reveal dbg pose alpha=$alpha risePx=${layer.reveal.risePx(layer.density, now)}")
    }

    /**
     * Put every layer into the reveal's first pose as soon as the reveal is armed.
     *
     * Most unlocks find the dock layer already built, but simply hidden behind the keyguard. Posing
     * it here means that even if a traversal shows it before the frame loop's first pass, what
     * becomes visible is already the first frame of the animation. Layers created later are caught
     * by {@code applyAppearance}, which poses them in the transaction that shows them.
     */
    private fun primeReveal() {
        val wm = service ?: return
        if (stopped) return
        runCatching {
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    if (layers.isEmpty()) return
                    val transaction = motionTransaction ?: loadClass(TRANSACTION)
                        .getConstructor().newInstance().also { motionTransaction = it }
                    val now = SystemClock.uptimeMillis()
                    layers.values.forEach { applyRevealPose(transaction, it, now) }
                    transaction.callMethod("apply")
                    glassClient.record("unlock reveal primed layers=${layers.size}")
                }
            }
        }.onFailure {
            glassClient.record("unlock reveal prime failed=${it.javaClass.simpleName}")
        }
    }

    /**
     * Safety net for a reveal whose frame source went away mid-flight.
     *
     * It runs after {@link DockUnlockReveal#SETTLE_MS}, but a timer scheduled by an earlier unlock
     * can still land while a later one is animating, so each layer is asked whether its own reveal
     * has stalled. Whatever is stalled and still holds a non-resting transform therefore lost its
     * frames - including the case where the dock was primed and then never animated, which would
     * otherwise leave it invisible forever.
     */
    private fun resetStuckReveal() {
        val wm = service ?: return
        if (stopped) return
        runCatching {
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    val now = SystemClock.uptimeMillis()
                    val stale = layers.values.filter {
                        it.reveal.isStalled(now) && (it.reveal.hasPendingPose() ||
                            !hasRestingContainer(it) || it.revealAlpha != 1f || it.revealSlide != 0f)
                    }
                    if (stale.isEmpty()) return
                    val transaction = motionTransaction ?: loadClass(TRANSACTION)
                        .getConstructor().newInstance().also { motionTransaction = it }
                    stale.forEach {
                        it.reveal.cancel()
                        restoreRestingTransform(transaction, it)
                    }
                    transaction.callMethod("apply")
                    stale.forEach { it.reveal.onPoseCommitted(now) }
                    glassClient.record("unlock reveal force reset layers=${stale.size}")
                }
            }
        }.onFailure {
            glassClient.record("unlock reveal reset failed=${it.javaClass.simpleName}")
        }
    }

    internal fun requestTraversal() {
        val wm = service ?: return
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        handler.post {
            if (!stopped) runCatching {
                synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                    wm.getObjectFieldAs<Any>("mWindowPlacerLocked").callMethod("requestTraversal")
                }
            }.onFailure { notePrepareFailure(it) }
        }
    }

    internal fun recordMotion(layer: Layer, y: Float, now: Long, visible: Boolean, source: String) {
        val running = if (layer.nativeApplied) {
            val progress = layer.nativeMotion.progress()
            progress > 0.001f && kotlin.math.abs(progress - 1f) > 0.001f
        } else layer.motion.isRunning(now)
        if (layer.motionEndPending && (layer.motionSamples < 6 || !running)) {
            glassClient.record("motion position offsetY=${y - layer.baseY} running=$running visible=$visible source=${if (layer.nativeApplied) "native-$source" else source}")
            layer.motionSamples++
            if (!running) layer.motionEndPending = false
        }
    }

    private fun updateMotionFrame(frameTimeNanos: Long) {
        val wm = service ?: return
        runCatching {
            synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                synchronized(layers) {
                    if (stopped || !settings.enabled) return
                    var needsFrame = false
                    val frames = ArrayList<FrameUpdate>()
                    val monotonicNow = System.nanoTime()
                    for ((window, layer) in layers) {
                        // Read the clock before the visibility test: an armed reveal still has to
                        // expire while the launcher is briefly hidden behind a going-away keyguard.
                        val now = DockRecentsMotion.frameTimeMillis(frameTimeNanos, layer.motionTime)
                        layer.motionTime = now
                        if (window.callMethod("isVisible") != true || layer.effect.callMethod(IS_VALID) != true) {
                            layer.motion.finish()
                            layer.clearNativeSample()
                            if (layer.reveal.needsFrame(now)) needsFrame = true
                            continue
                        }
                        if (layer.reveal.startIfArmed(now)) {
                            glassClient.record("unlock reveal started uptimeMs=$now")
                        }
                        if (layer.reveal.needsFrame(now)) needsFrame = true
                        val y = layer.baseY + motionOffset(layer, now) +
                            layer.reveal.risePx(layer.density, now)
                        if (layer.nativeSampleDeadlineNs > monotonicNow
                            || layer.autoAimDeadlineNs > monotonicNow
                            || (!layer.nativeApplied && layer.motion.isRunning(now))) needsFrame = true
                        val pose = revealContainerPose(layer, now, monotonicNow)
                        val alpha = layer.reveal.alpha(now)
                        // The horizontal slide is part of the same gesture as the lift, so it
                        // must keep the frame loop dirty even once opacity and scale settle.
                        val slide = layer.reveal.slidePx(layer.width.toFloat(), now)
                        if (layer.x.isFinite() && layer.baseY > 0 &&
                            (y != layer.y || containerChanged(layer, pose) ||
                                alpha != layer.revealAlpha || slide != layer.revealSlide ||
                                (layer.reveal.hasPendingPose() && !layer.reveal.isRunning()))) {
                            frames.add(FrameUpdate(layer, y, pose, alpha, slide))
                        }
                    }
                    if (frames.isNotEmpty()) {
                        val transaction = motionTransaction ?: loadClass(TRANSACTION)
                            .getConstructor().newInstance().also { motionTransaction = it }
                        for (frame in frames) {
                            val (layer, y, pose, alpha, slide) = frame
                            // Scale and alpha are written only when they change, so the reveal can
                            // share one transaction with the real-time follow. The position always
                            // carries the pivot compensation, which is zero once scale reaches 1.
                            val applied = if (containerChanged(layer, pose)) {
                                applyRevealContainer(transaction, layer, pose)
                            } else AppliedContainer(layer.revealScaleX, layer.revealScaleY)
                            frame.appliedScaleX = applied.scaleX
                            frame.appliedScaleY = applied.scaleY
                            if (alpha != layer.revealAlpha) transaction.callMethod("setAlpha", layer.effect, alpha)
                            // The position always carries the pivot compensation, which is zero
                            // once scale reaches 1. Compensate about the resting origin so a
                            // lift is not folded back into its own compensation.
                            val shiftX = revealShiftX(layer, applied.scaleX, layer.x)
                            val shiftY = revealShiftY(layer, applied.scaleY, layer.baseY.toFloat())
                            transaction.callMethod(SET_POSITION, layer.effect,
                                layer.x + shiftX + slide,
                                y + shiftY)
                            // TODO(twitch-diag): trace frame-loop writes right after an unlock.
                            if (revealDebugActive() && (alpha != layer.revealAlpha ||
                                SystemClock.uptimeMillis() - lastFrameDbgUptime > 40L)) {
                                lastFrameDbgUptime = SystemClock.uptimeMillis()
                                glassClient.record("reveal dbg frame yOff=${y - layer.baseY} " +
                                    "slide=$slide alpha=$alpha nativeApplied=${layer.nativeApplied}")
                            }
                        }
                        transaction.callMethod("setAnimationTransaction")
                        // Deliberately no frame-timeline tag here. The vsync id came from the
                        // frame clock we no longer own, and a stale id is worse than none: the
                        // static and traversal submissions above apply without one and are just
                        // as correct.
                        transaction.callMethod("apply")
                        // Publish cached positions only after a successful submission.
                        for (frame in frames) {
                            val (layer, y, pose, alpha, slide) = frame
                            layer.y = y
                            rememberContainer(layer, pose,
                                AppliedContainer(frame.appliedScaleX, frame.appliedScaleY))
                            layer.revealAlpha = alpha
                            layer.revealSlide = slide
                            layer.reveal.onPoseCommitted(layer.motionTime)
                            recordMotion(layer, y, layer.motionTime, true, "vsync")
                        }
                    }
                    directRecoveryDelay = 100L
                    if (needsFrame) scheduleAnimationFrame()
                }
            }
        }.onFailure {
            // Wallpaper/display replacement and suspend can invalidate the cached transaction
            // temporarily. Keep the static background, but rebuild the direct path; permanently
            // disabling it makes all later gestures lose real-time following. Nothing is torn
            // down here: the tick is a plain Handler message, so dropping it cannot race a
            // queued callback into a system_server SIGSEGV.
            directMotionAvailable = false
            runCatching { motionTransaction?.callMethod("close") }
            motionTransaction = null
            glassClient.record("motion direct frame unavailable=${it.javaClass.simpleName}")
            requestTraversal()
            scheduleDirectMotionRecovery()
        }
    }

    internal fun bindNativeMotion(window: Any, layer: Layer) {
        // Bind the Binder identity eagerly so early samples are never lost.
        // The native transport starts sending as soon as hooks are installed,
        // which may precede the first prepareSurfaces where the window is visible.
        // Re-read the window's Session on every update: HYOS builds a fresh Session for
        // each launcher process, and a cached one would pin this layer to a dead uid/pid,
        // so every sample is authenticated away until the desktop is restarted.
        val current = runCatching { window.getObjectFieldAs<Any>("mSession") }.getOrNull()
        if (current != null && current !== layer.motionSession) {
            layer.motionSession = current
            layer.resetNativeMotion()
        }
        val session = layer.motionSession ?: return
        runCatching {
            val uid = session.getObjectFieldAs<Int>("mUid")
            val pid = session.getObjectFieldAs<Int>("mPid")
            if (uid < 10000 || pid <= 0) return@runCatching
            if (uid != layer.nativeUid || pid != layer.nativePid) {
                layer.nativeMotion.reset()
                layer.nativeApplied = false
                layer.nativeScene = NO_SCENE
                layer.nativeSampleDeadlineNs = 0
                layer.resetAutoAim()
                layer.nativeUid = uid
                layer.nativePid = pid
                glassClient.record("native motion Binder identity uid=$uid pid=$pid")
            }
            // Re-assert on every traversal, not only when the uid/pid changes. The receiver
            // retains (and refuses to apply) every sample until this exact identity is bound,
            // and a module hot reload installs a fresh receiver whose identity store starts
            // empty. A layer that already holds the right uid/pid would then never bind it, so
            // real-time following looks dead until the window is recreated - leave and re-enter
            // the launcher - even though the native side keeps publishing samples.
            // bindIdentity also promotes an already-retained sample, so a frame that arrived
            // during the race is recovered in this same traversal instead of being dropped.
            nativeMotionEndpoint.bindIdentity(uid, pid)
        }.onFailure {
            if (observed.add("native-motion-identity")) {
                glassClient.record("native motion identity unavailable=${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * Safety net for the receiver identity.
     *
     * [bindNativeMotion] re-asserts the exact uid/pid on every launcher traversal, but a
     * traversal is not guaranteed after the process is replaced: WMS can place the surface
     * once and then leave the window alone while the device idles. Because the receiver
     * retains - and refuses to apply - every sample until that identity is bound, a stale
     * identity would silently kill real-time following until the window happened to be
     * recreated, which is exactly the "leave and re-enter the launcher fixes it" symptom.
     * One field read per layer per second is cheap insurance against that.
     *
     * The same tick also bounds how stale the dock preferences can be, and applies them directly.
     * Neither a change callback nor a WMS traversal is guaranteed: the framework can drop the
     * remote update push, and WMS has no reason to traverse an idle launcher window at all. Since
     * the loop already exists and only runs while a dock layer is on screen, re-reading here turns
     * "edited the height, nothing happened until the window was recreated" into at worst one
     * second of delay.
     */
    internal fun scheduleNativeBindSweep() {
        val wm = service ?: return
        if (stopped || !nativeBindSweepScheduled.compareAndSet(false, true)) return
        val handler = wm.getObjectFieldAs<Handler>(WM_HANDLER)
        handler.postDelayed({
            nativeBindSweepScheduled.set(false)
            if (stopped) return@postDelayed
            var keepGoing = false
            runCatching {
                // A covered launcher is as idle as a sleeping panel: neither the native identity
                // nor the glass geometry can affect a visible frame. WMS traversal refreshes the
                // layer immediately when home returns; this timer is only a background backstop.
                if (!desktopDrawable()) {
                    sweepIntervalMs = SWEEP_DOZE_INTERVAL_MS
                    synchronized(layers) { keepGoing = layers.isNotEmpty() }
                    return@runCatching
                }
                val changed = refreshSettings()
                // The provider read is what actually advances the geometry: the values
                // refreshSettings() reads through PrefsBridge can be frozen for the whole
                // process lifetime. It is posted, so its result lands on the next sweep - the
                // cadence is the knob's own latency, not this tick's.
                //
                // This is the backstop, not the fast path: the geometry observers ask for the
                // same read the moment the settings file changes. This tick is what covers a
                // build whose provider notification never arrives.
                glassClient.refreshGeometry()
                // The rotation gate has to be polled here: a rotated app hides the launcher window,
                // WMS stops traversing it, and nothing else observes the display while that lasts.
                val rotationChanged = layerUpdate.refreshRotationGate()
                // Back off while nothing changes: an idle home screen must not wake the display
                // thread every second. Any traversal, layer change or preference event calls
                // resetSweepCadence(), so the fast cadence only survives while things move.
                if (changed || rotationChanged) {
                    sweepIntervalMs = NATIVE_BIND_SWEEP_MS
                } else if (sweepIntervalMs < SWEEP_BACKOFF_MAX_MS) {
                    val previous = sweepIntervalMs
                    sweepIntervalMs = (previous * 2).coerceAtMost(SWEEP_BACKOFF_MAX_MS)
                    glassClient.record("sweep cadence backoff ${previous}→$sweepIntervalMs ms")
                }
                // Preserve the WM -> layer lock order used by the wallpaper command path.
                synchronized(wm.getObjectFieldAs<Any>(WM_LOCK)) {
                    synchronized(layers) {
                        if (stopped) return@postDelayed
                        keepGoing = layers.isNotEmpty()
                        layers.entries.toList().forEach { (window, layer) ->
                            bindNativeMotion(window, layer)
                        }
                        if (changed) {
                            // Re-apply the geometry ourselves instead of hoping that the traversal
                            // requested below reaches prepareSurfaces for an idle window. Same
                            // order the wallpaper command path uses: update, then commit.
                            layers.keys.toList().forEach { window ->
                                runCatching { updateLayer(window) }.onFailure {
                                    glassClient.record("prefs apply failed=${it.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                }
                if (changed || rotationChanged) requestTraversal()
            }.onFailure {
                if (observed.add("native-bind-sweep")) {
                    glassClient.record("native motion bind sweep failed=${it.javaClass.simpleName}")
                }
            }
            if (keepGoing) scheduleNativeBindSweep()
        }, sweepIntervalMs)
    }

    // Called only under the layer lock. The authenticated Binder receiver publishes an
    // immutable latest sample; intermediate queued values never become a second animation.
    // The effect is a child of the launcher window and already inherits its upward motion.
    // The earlier extra -20dp lift visibly moved the background above the icons in a phone
    // recording. Only the measured progress-linked centre correction remains, shared with fallback.
    internal fun motionOffset(layer: Layer, now: Long): Float {
        val sample = nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
        if (sample?.scene() == DockNativeMotion.SCENE_AUTO_AIM) {
            // Scene 3 is a size truth signal, not the recents hotseat scale. Keeping it out of
            // DockNativeMotion is what prevents unlock from inheriting or resuming a vertical lift.
            if (layer.nativeApplied) {
                layer.nativeMotion.reset()
                layer.nativeApplied = false
                layer.nativeScene = NO_SCENE
                layer.nativeSampleDeadlineNs = 0
                layer.motion.finish()
            }
            return DockNativeMotion.relativeOffsetY(layer.density, layer.motion.progress(now))
        }
        if (sample != null) {
            layer.nativeMotion.accept(sample, layer.overview)
            layer.nativeApplied = true
            layer.nativeSampleDeadlineNs = sample.uptimeNanos() + DockNativeMotion.MAX_AGE_NS
            if (sample.scene() == 1) {
                layer.nativeOverviewGeneration = layer.overviewGeneration
            }
            if (sample.scene() != layer.nativeScene) {
                layer.nativeScene = sample.scene()
                layer.motionSamples = 0
                layer.motionEndPending = true
                // Sampled, not verbatim: this is the one diagnostic in the frame loop that is not
                // already in noisyPrefixes-form, and it still fires on every scene transition.
                if (SCENE_LOG.sample()) {
                    glassClient.record("native motion scene=${sample.scene()} scale=${sample.scale()}")
                }
            }
            return layer.nativeMotion.relativeOffsetY(layer.density)
        }
        if (layer.nativeApplied) {
            // A held recents gesture legitimately produces no changing scale samples.
            // Once the verified overview target has cleared, resume the local return
            // curve from the exact native position instead of freezing or snapping.
            if (layer.overview) return layer.nativeMotion.relativeOffsetY(layer.density)
            layer.motion.resumeFrom(layer.nativeMotion.progress(), false, now)
            layer.nativeApplied = false
            layer.nativeMotion.reset()
            layer.nativeScene = NO_SCENE
            layer.nativeSampleDeadlineNs = 0
            layer.motionSamples = 0
            layer.motionEndPending = true
            scheduleAnimationFrame()
            glassClient.record("native motion idle after overview exit; resuming return")
        }
        return DockNativeMotion.relativeOffsetY(layer.density, layer.motion.progress(now))
    }


    /**
     * Whether the launcher's overlay window - the minus-one screen - is currently visible.
     *
     * <p>Read straight from WMS's window map, on the display thread and under the same global
     * lock as every other traversal write. This is deliberately the only launcher state we read:
     * OS4's launcher is a Flutter application with no dex at all, so edit mode and the pages
     * themselves live in Dart and no Java hook can reach them, while the overlay is a real
     * WindowState that WMS owns.
     *
     * <p>The visible case short-circuits on the cached state. Otherwise the map is re-scanned at
     * most every [OVERLAY_SCAN_MS]: the overlay survives page changes, but a launcher restart
     * replaces the WindowState and the stale one would never report visible again.
     */
    internal fun launcherOverlayVisible(wm: Any, now: Long): Boolean {
        val cached = overlayWindow
        if (cached != null) {
            val visible = runCatching { cached.callMethod("isVisible") as Boolean }.getOrNull()
            if (visible == true) {
                overlayVisible = true
                return true
            }
        }
        if (now - overlayScannedAt < OVERLAY_SCAN_MS) return overlayVisible
        overlayScannedAt = now
        val found = runCatching { findLauncherOverlay(wm) }.onFailure {
            if (observed.add("launcher-overlay-scan")) {
                glassClient.record("launcher overlay scan unavailable=${it.javaClass.simpleName}: " +
                    "${it.message?.take(120)}")
            }
        }.getOrNull()
        overlayWindow = found
        overlayVisible = found != null &&
            runCatching { found.callMethod("isVisible") as Boolean }.getOrDefault(false)
        return overlayVisible
    }

    private fun findLauncherOverlay(wm: Any): Any? {
        for (state in wm.getObjectFieldAs<Map<*, *>>("mWindowMap").values) {
            val name = state?.let {
                runCatching { it.callMethod("getName") as String }.getOrNull()
            } ?: continue
            if (name.startsWith(LAUNCHER_OVERLAY_TITLE)) return state
        }
        return null
    }

    private fun hasPendingMotionFrame(): Boolean {
        val now = SystemClock.uptimeMillis()
        val nowNs = System.nanoTime()
        return synchronized(layers) {
            layers.values.any { layer ->
                // Clock expiry does not submit the resting pose. Keep the terminal frame
                // pending across repeated lost callbacks, including a rise-only residue.
                val revealNeedsFrame = layer.reveal.needsFrame(now)
                val latest = nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
                val latestAutoAim = nativeMotionEndpoint.latestAutoAim(
                    layer.nativeUid, layer.nativePid)
                val nativeNeedsFrame = latest != null ||
                    (layer.reveal.getStyle() == DockUnlockReveal.Style.AUTO_AIM &&
                        DockNativeMotion.autoAimScale(latestAutoAim,
                            layer.reveal.eventEpochMillis(), nowNs) != null)
                revealNeedsFrame || (layer.lastVisible == true &&
                    (layer.reveal.hasPendingPose() ||
                        layer.revealAlpha != 1f || !hasRestingContainer(layer) ||
                        layer.revealSlide != 0f))
                    || nativeNeedsFrame
                    || (layer.nativeApplied && !layer.overview)
                    || (!layer.nativeApplied && layer.motion.isRunning(now))
            }
        }
    }

    private fun wmHandler(): Handler? = runCatching {
        service?.getObjectFieldAs<Handler>(WM_HANDLER)
    }.getOrNull()

    internal fun scheduleAnimationFrame(urgent: Boolean = false) {
        if (stopped || !directMotionAvailable) return
        val handler = wmHandler() ?: return
        val epoch = frameEpoch.incrementAndGet()
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        if (!scheduledFrameEpoch.compareAndSet(0, epoch)) {
            val pendingEpoch = scheduledFrameEpoch.get()
            val pendingSince = scheduledFrameStartedNs.get()
            if (urgent && pendingEpoch != 0L && pendingSince != 0L
                && requestedAt - pendingSince >= STALE_FRAME_NS
                && urgentFrameRecoveryScheduled.compareAndSet(false, true)) {
                handler.post {
                    try {
                        if (scheduledFrameEpoch.get() == pendingEpoch) {
                            glassClient.record(
                                "motion progress replaced stale frame epoch=$pendingEpoch")
                            recoverStalledFrame(pendingEpoch)
                        }
                    } finally {
                        urgentFrameRecoveryScheduled.set(false)
                    }
                }
            }
            return
        }
        scheduledFrameStartedNs.set(requestedAt)
        // One Handler tick per displayed frame, re-armed by updateMotionFrame() while the gesture
        // still needs a frame. System.nanoTime() is the same monotonic base the old frame callback
        // handed us, so every consumer downstream is unchanged.
        val tick = Runnable {
            // A tick whose epoch was superseded (a replacement armed, or the loop stopped) is
            // dropped: the epoch is the single source of truth for "this frame is live".
            if (clearScheduledFrame(epoch) && !stopped && directMotionAvailable) {
                runCatching {
                    updateMotionFrame(System.nanoTime())
                }.onFailure {
                    // Never let an optional animation callback throw on a system handler thread.
                    // Nothing is disposed here: the recovery probe simply re-arms the tick later.
                    animationAvailable = false
                    glassClient.record("motion scheduling failed=${it.javaClass.simpleName}: ${it.message?.take(160)}")
                    XposedLog.w(TAG, LOG_TAG, "Dock animation tick failed; retrying on a later frame", it)
                    requestTraversal()
                    scheduleDirectMotionRecovery()
                }
            }
        }
        scheduledFrame = ScheduledFrame(epoch, tick)
        handler.postDelayed(tick, FRAME_INTERVAL_MS)
        // Handler time stops in deep sleep, so this runs shortly after resume even when the
        // pre-suspend tick never ran. The epoch makes a late old tick harmless afterwards.
        handler.postDelayed({ recoverStalledFrame(epoch) }, 100)
    }

    private fun clearScheduledFrame(epoch: Long): Boolean {
        if (!scheduledFrameEpoch.compareAndSet(epoch, 0)) return false
        scheduledFrameStartedNs.set(0)
        if (scheduledFrame?.epoch == epoch) scheduledFrame = null
        return true
    }

    private fun cancelScheduledFrame() {
        val scheduled = scheduledFrame
        if (scheduled != null) {
            wmHandler()?.removeCallbacks(scheduled.tick)
            scheduledFrame = null
        }
        scheduledFrameEpoch.set(0)
        scheduledFrameStartedNs.set(0)
    }

    private fun recoverStalledFrame(epoch: Long) {
        if (scheduledFrameEpoch.get() != epoch) return
        scheduledFrame?.takeIf { it.epoch == epoch }?.let { wmHandler()?.removeCallbacks(it.tick) }
        if (!clearScheduledFrame(epoch)) return
        // Screen-off legitimately pauses the loop, and an idle keepalive must not start a
        // 10 Hz recovery loop. Re-arm only while something still needs a frame.
        if (!hasPendingMotionFrame()) return
        glassClient.record("motion frame tick stalled epoch=$epoch")
        scheduleAnimationFrame()
    }

    private fun scheduleDirectMotionRecovery() {
        if (stopped) return
        val handler = wmHandler() ?: return
        if (!directRecoveryScheduled.compareAndSet(false, true)) return
        val delay = directRecoveryDelay
        directRecoveryDelay = (directRecoveryDelay * 2).coerceAtMost(5_000L)
        handler.postDelayed({
            directRecoveryScheduled.set(false)
            if (stopped) return@postDelayed
            cancelScheduledFrame()
            animationAvailable = true
            directMotionAvailable = true
            glassClient.record("motion direct frame retry after lifecycle interruption delayMs=$delay")
            scheduleAnimationFrame()
        }, delay)
    }

    /**
     * A single failure while posing the Dock must not cost the background for the rest of this
     * system_server's life.
     *
     * <p>Both callers run from the very first traversal after boot, before the launcher, the
     * wallpaper or even our own provider is necessarily reachable, so a transient failure there is
     * expected rather than a defect. [failClosed] is permanent - it latches `stopped` and releases
     * every layer until system_server restarts - which is exactly what a boot-time hiccup must
     * never trigger: the dock simply disappears and no later traversal can bring it back. Give the
     * Dock a few chances inside a short window instead, and keep the failure visible in logcat so
     * the next report names the exception.
     */
    internal fun notePrepareFailure(error: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (prepareFailureWindowAt == 0L || now - prepareFailureWindowAt > PREPARE_FAILURE_WINDOW_MS) {
            prepareFailureWindowAt = now
            prepareFailures = 0
        }
        prepareFailures++
        // Error level on purpose: release builds drop v/i/w, and this line is the only record that
        // survives the journal once it has been flushed.
        XposedLog.e(TAG, LOG_TAG, "dock pose failed (#$prepareFailures of $PREPARE_FAILURE_LIMIT): "
            + "${error.javaClass.simpleName}; retrying on the next traversal", error)
        glassClient.record("dock pose failed count=$prepareFailures " +
            "${error.javaClass.simpleName}: ${error.message?.take(160)}")
        if (prepareFailures >= PREPARE_FAILURE_LIMIT) failClosed(error)
    }

    private fun failClosed(error: Throwable) {
        synchronized(layers) {
            if (stopped) return
            stopped = true
            glassClient.record("hook disabled error=${error.javaClass.simpleName}: ${error.message?.take(160)}")
            layers.keys.toList().forEach { removeLayer(it) }
            glassClient.close()
            processGuard.close()
            XposedLog.e(TAG, LOG_TAG, "WMS dock disabled after an error; system windows left unchanged", error)
        }
    }
}
