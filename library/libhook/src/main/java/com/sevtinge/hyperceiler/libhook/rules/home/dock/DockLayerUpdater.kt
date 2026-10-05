/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.content.Context
import android.graphics.Color
import android.view.Display
import android.view.SurfaceControl
import com.sevtinge.hyperceiler.common.log.XposedLog
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-window layer upkeep for [HomeDockWindow].
 *
 * <p>Split out of the rule file for size only. It used to be an `inner class` reaching the rule's
 * state implicitly; those reads are now explicit `owner.` accesses against a single reference (the
 * body touches 9 fields and 14 members, which is more than any constructor list should carry).
 * Behaviour is unchanged - this class still owns the same rotation gate and rebuilds the same
 * material on the same triggers.
 */
internal class DockLayerUpdater(private val owner: HomeDockWindow) {
    /**
     * Suspends the glass while the display is rotated. The launcher window frame cannot see
     * that state - it stays portrait while the display rotates around it - so this reads the
     * display itself; see [DockRotationPolicy].
     */
    private val rotation = DockRotationPolicy()
    /** Display thread only. Resolved once; the default display never changes here. */
    private var displayRef: Display? = null
    /** Reported once, so a silent rotation reader cannot hide behind a quiet log. */
    private var rotationReadReported = false
    private var rotationReadFailed = false

    fun isRotationSettling(): Boolean = rotation.isRotated() || rotation.isSettling(SystemClock.uptimeMillis())
    fun isDisplayRotated(): Boolean = rotation.isRotated()

    fun update(window: Any) {
        // Already re-read by prepareWindow (every launcher traversal) and by the 1 Hz sweep,
        // so this is the live configuration rather than the value captured at hook init.
        // Nothing below caches it across traversals: changing a parameter and re-laying the
        // window always recomputes the bounds from the current values.
        val config = owner.settings
        if (!config.enabled) { owner.removeLayer(window); return }
        val parent = window.callMethod("getSurfaceControl") ?: return
        if (parent.callMethod(HomeDockWindow.IS_VALID) != true) { owner.removeLayer(window); return }
        val frame = window.callMethod("getFrame") as Rect
        val configuration = window.callMethod("getConfiguration") as Configuration
        val bounds = DockWindowPolicy.layout(frame.width(), frame.height(), configuration.densityDpi / 160f,
            config.height, config.margin, config.bottom, config.radius)
        if (bounds == null) { owner.removeLayer(window); return }
        val layer = obtainLayer(window, parent, frame, bounds, config)
        // The reveal scales about the layer's own centre, so the bounds have to be current.
        layer.width = bounds.width()
        layer.height = bounds.height()
        layer.radius = bounds.radius()
        // AUTO_AIM scales about the launcher's unlock pivot, which is expressed as a fraction
        // of the launcher frame; the layer bounds share that space.
        layer.parentWidth = frame.width()
        layer.parentHeight = frame.height()
        val dark = when (config.nightMode) {
            1 -> false
            2 -> true
            else -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val windowVisible = window.callMethod("isVisible") == true
        // The launcher hides its own dock while the minus-one screen is showing, and our
        // panel would then be the only thing left floating over it. Everything downstream
        // already keys off `visible` - the appearance transaction hides the effect layer,
        // the glass refresh is paused and the frame loop stops - so folding the overlay into
        // this one flag is what makes the hide (and the restore) automatic.
        val overlayShowing = owner.launcherOverlayVisible(owner.service!!, SystemClock.uptimeMillis())
        if (overlayShowing != layer.overlayHidden) {
            layer.overlayHidden = overlayShowing
            owner.glassClient.record(
                if (overlayShowing) "dock glass hidden for launcher overlay (minus-one)"
                else "dock glass restored after launcher overlay")
        }
        // A retained portrait texture can accompany the launcher through its rotated return.
        // Keep sampling paused until the portrait pose commits; an unpreserved source remains gated.
        // The launcher frame stays portrait, so only display rotation is authoritative here.
        refreshRotationGate()
        // The rotation no longer gates visibility or rebuilds the host. Both were stopgaps
        // from before the host could report its own sampling geometry: hiding the panel here
        // left the launcher's dock icons without any background for the whole rotated return,
        // and the epoch rebuild added a warm-up fallback after it. The host now refuses
        // samples whose blur geometry still carries the rotated transform (geometryValid), so
        // the compositor fallback covers exactly those frames and the glass returns the
        // moment the geometry catches up - no hide, no rebuild, no timer.
        refreshRotationGate()
        val visible = windowVisible && !overlayShowing
        val wasVisible = layer.lastVisible == true
        owner.bindNativeMotion(window, layer)
        if (!visible) {
            layer.motion.finish()
            layer.clearNativeSample()
            layer.resetAutoAim()
        } else if (!owner.animationAvailable && !layer.nativeApplied) {
            layer.motion.finish()
        }
        val glass = updateGlass(layer, config, bounds, dark, visible)
        val resumeGlass = visible && glass != null && (layer.lastVisible == false || glass.retainedCapture)
        if (visible && layer.lastVisible == false && glass != null) owner.glassClient.releaseDepartureHold(glass)
        if (!visible && layer.lastVisible == true && glass != null) {
            // A display rotation hides the launcher behind the foreground app. Freeze the
            // capture while the Dock is invisible so the returning frame keeps the last home
            // sample; otherwise the pass-blur texture is updated with the app's content and the
            // first returning frame paints the wrong colours. Pausing is safe here precisely
            // because the panel is not on screen (a paused capture is not drawn at all).
            if (isDisplayRotated()) owner.glassClient.holdForDeparture(glass)
            else owner.glassClient.pauseRefresh(glass)
        }
        layer.lastVisible = visible
        val appearance = Appearance(config, bounds, dark, visible, glass)
        val now = SystemClock.uptimeMillis()

        val x = bounds.x().toFloat()
        val density = configuration.densityDpi / 160f
        val geometryChanged = layer.x != x || layer.baseY != bounds.y() || layer.density != density
        layer.baseY = bounds.y()
        layer.density = density
        layer.motionTime = now
        val offset = owner.motionOffset(layer, now)
        val running = !layer.nativeApplied && layer.motion.isRunning(now)
        // In direct mode, do not queue old animation positions in a later WMS traversal.
        // WMS still initializes/repositions our layer when the actual layout changes.
        val movingDirectly = owner.directMotionAvailable && visible && (running ||
            layer.reveal.isRunning() || layer.reveal.hasPendingPose() ||
            layer.revealAlpha != 1f || !owner.hasRestingContainer(layer))
        val keepDirectPosition = movingDirectly && wasVisible && !geometryChanged
        // A WMS sync transaction can be applied after a newer direct vsync transaction.
        // Even the same curve sampled at different times then moves the surface backwards.
        // Once visible, only the frame clock owns animation pose; traversal still owns
        // first-show initialization, real geometry changes and the non-direct fallback.
        val y = if (keepDirectPosition) layer.y
            else bounds.y() + offset + layer.reveal.risePx(layer.density, now)
        if (visible && (movingDirectly || running || layer.reveal.isRunning())) owner.scheduleAnimationFrame()
        val moved = layer.x != x || layer.y != y
        if (!moved && layer.appearance == appearance.key && !resumeGlass) return
        val transaction = window.callMethod("getSyncTransaction")!!
        if (moved) {
            // Move the common parent: glass, tint and fallback blur stay aligned. The size/key
            // remains unchanged, so a frame of motion never recreates the glass host or texture.
            // The reveal's pivot compensation rides along, otherwise a traversal landing
            // mid-reveal would drop it and visibly shift the dock. The rest edge is the
            // resting origin, not the lifted y: the compensation must not compensate itself.
            val shiftX = owner.revealShiftX(layer, layer.revealScaleX, x)
            val shiftY = owner.revealShiftY(layer, layer.revealScaleY, layer.baseY.toFloat())
            // The slide is added at write time and never cached, so layer.x stays the
            // resting position and the frame loop below cannot apply it twice.
            transaction.callMethod(HomeDockWindow.SET_POSITION, layer.effect,
                x + shiftX + layer.reveal.slidePx(layer.width.toFloat(), now),
                y + shiftY)
            layer.x = x
            layer.y = y
            // TODO(twitch-diag): trace traversal writes right after an unlock.
            if (owner.revealDebugActive()) {
                owner.glassClient.record("reveal dbg layout yOff=${y - layer.baseY} " +
                    "rise=${layer.reveal.risePx(layer.density, now)} " +
                    "revealScale=${layer.revealScaleX}x${layer.revealScaleY}")
            }
            owner.recordMotion(layer, y, now, visible, "layout")
        }
        if (layer.appearance != appearance.key) {
            applyAppearance(transaction, layer, appearance, keepDirectPosition)
            layer.appearance = appearance.key
        }
        if (resumeGlass) {
            owner.glassClient.resume(glass!!, allowFallback = !owner.materialSettleActive(),
                afterCommit = transaction as SurfaceControl.Transaction)
        }
    }

    /**
     * Read the display rotation and fold it into the rotation gate.
     *
     * @return true when the suspended state changed, so the caller must ask for a traversal.
     * The gate cannot rely on launcher traversals alone: while an app owns a rotated display
     * the launcher window is hidden behind it, WMS destroys its surface and never traverses
     * it, so the one-second sweep is what keeps this state current at all.
     */
    fun refreshRotationGate(): Boolean {
        val displayRotation = readDisplayRotation()
        val rotated = if (displayRotation != null) displayRotation != Surface.ROTATION_0
        else rotation.isRotated()
        val now = SystemClock.uptimeMillis()
        if (!rotation.update(rotated, now)) return false
        owner.glassClient.record(
            if (rotated) "dock glass suspended while the display is rotated"
            else "dock glass returning after rotation epoch=${rotation.getEpoch()} " +
                "settleMs=${rotation.settleRemainingMs(now)}")
        if (!rotated) {
            // Immediately resync glass on rotation return, don't wait for the next traversal.
            // The traversal-based resume (via resumeGlass) only fires when lastVisible flips,
            // which doesn't happen when the dock stays visible through the rotation. The host
            // recomputes its blur geometry inside this resume, so the first returning frame is
            // mapped through the portrait transform instead of the rotated one.
            synchronized(owner.layers) {
                for ((_, layer) in owner.layers) {
                    val glass = layer.glass ?: continue
                    owner.glassClient.resumeAfterRotation(glass)
                }
            }
            // Start capture now, then revisit presentation once portrait has settled.
            owner.service?.getObjectFieldAs<Handler>(HomeDockWindow.WM_HANDLER)?.postDelayed(
                { owner.requestTraversal() },
                rotation.settleRemainingMs(SystemClock.uptimeMillis()) + HomeDockWindow.ROTATION_RESUME_MARGIN_MS)
        } else {
            // Freeze the capture as soon as the display rotates, while the launcher is still the
            // visible window. Waiting for the Dock to hide means the compositor has already
            // captured the foreground app, and that stale frame is exactly what the returning
            // panel would paint. The freeze keeps the panel drawn, so this has no visual cost.
            synchronized(owner.layers) {
                for ((_, layer) in owner.layers) {
                    val glass = layer.glass ?: continue
                    owner.glassClient.holdForDeparture(glass)
                }
            }
        }
        return true
    }

    /**
     * The default display's current rotation, in [Surface.ROTATION_*] terms, or null when it
     * cannot be resolved.
     *
     * <p>Neither substitute works on this ROM: the launcher window keeps its portrait frame
     * while the display rotates around it, and `Configuration.orientation` flips back and
     * forth while the display is still portrait. A null result makes the caller keep its last
     * decision rather than resume sampling on a rotated screen.
     */
    private fun readDisplayRotation(): Int? {
        if (rotationReadFailed) return null
        val value = catchingRecoverableOr<Int?>(null) {
            val display = displayRef ?: run {
                val context = owner.service?.getObjectFieldAs<Context>("mContext")
                    ?: error("WMS context unavailable")
                val manager = context.getSystemService(DisplayManager::class.java)
                    ?: error("DisplayManager unavailable")
                val resolved = manager.getDisplay(Display.DEFAULT_DISPLAY)
                    ?: error("default display unavailable")
                displayRef = resolved
                resolved
            }
            display.rotation
        }
        if (value == null) {
            rotationReadFailed = true
            owner.glassClient.record("display rotation unavailable; keeping the last gate state")
            return null
        }
        if (!rotationReadReported) {
            rotationReadReported = true
            owner.glassClient.record("display rotation read=$value")
        }
        return value
    }

    private fun updateGlass(layer: Layer, config: Settings, bounds: DockWindowPolicy.Bounds,
        dark: Boolean, visible: Boolean): DockGlassClient.Ticket? {
        // Inside the wallpaper-swap settle window keep the existing ticket: releasing and
        // rebuilding the glass while the keyguard and home wallpapers are swapping is the
        // "samples two different wallpapers" flash. One rebuild happens after the window.
        val settledDark = if (owner.materialSettleActive() && layer.glass != null) layer.glass!!.dark else dark
        val glassKey = "${bounds.width()}/${bounds.height()}/${bounds.radius()}/$settledDark"
        // A rotation no longer forces a rebuild: the launcher frame stays portrait through the
        // whole transition, so the host, its bounds and its material token are unchanged, and
        // the vendor's stale blur geometry is covered by the host's geometryValid gate (the
        // compositor fallback carries exactly the mis-mapped frames). Rebuilding here instead
        // traded one wrong-looking state for two: a hidden panel during the rotated return and
        // a warm-up fallback after it.
        // A dead host has to be replaced by us: nothing else clears the reference, so without
        // this the dock stays on the compositor fallback for the rest of the window's life
        // after the renderer process dies (it is the module's own process, so a swipe-away or
        // an out-of-memory kill is enough). The backoff keeps a crash-looping renderer from
        // being restarted on every traversal.
        val dead = layer.glass?.dead == true
        if (dead && layer.glassDroppedAt == 0L) layer.glassDroppedAt = SystemClock.uptimeMillis()
        if (dead && SystemClock.uptimeMillis() - layer.glassDroppedAt < owner.GLASS_REBUILD_BACKOFF_MS) {
            return layer.glass
        }
        if (!config.glass || dead || layer.glass?.key?.let { it != glassKey } == true) {
            layer.glass?.let { owner.glassClient.release(it) }
            layer.glass = null
            layer.glassDroppedAt = 0L
        }
        if (layer.glass != null && layer.glassRotationEpoch != rotation.getEpoch()) {
            // The host survives the rotation; its sampling geometry is validated per probe.
            layer.glassRotationEpoch = rotation.getEpoch()
        }
        // Cold starts and invalid sources still use the guarded warmup path. A paused,
        // ready host survives rotation and supplies glass from the first returning frame.
        if (config.glass && visible && layer.glass == null) {
            val context = owner.service!!.getObjectFieldAs<Context>("mContext")
            layer.glass = owner.glassClient.create(context, glassKey, bounds, settledDark)
            layer.glassRotationEpoch = rotation.getEpoch()
        }
        return layer.glass
    }

    private fun obtainLayer(window: Any, parent: Any, frame: Rect, bounds: DockWindowPolicy.Bounds, config: Settings): Layer {
        var layer = owner.layers[window]
        if (layer != null && (layer.parent !== parent || layer.effect.callMethod(HomeDockWindow.IS_VALID) != true)) {
            owner.removeLayer(window)
            layer = null
        }
        if (layer == null) {
            val effect = Surfaces.buildLayer("HyperCeiler Dock blur", parent, false)
            var tint: Any? = null
            try { tint = Surfaces.buildLayer("HyperCeiler Dock tint", effect, true) }
            finally { if (tint == null) Surfaces.destroySurface(effect) }
            layer = Layer(parent, effect, tint)
            layer.reveal.setStyle(owner.settings.revealStyle)
            val now = SystemClock.uptimeMillis()
            if (DockUnlockReveal.acceptsPending(owner.pendingRevealAt, now)) {
                layer.reveal.arm(owner.pendingRevealAt)
                layer.reveal.startIfArmed(owner.pendingRevealAt)
            }
            owner.layers[window] = layer
            val motionLayer = layer
            // Resolve the two identities independently. They used to share one
            // runCatching, so a failure while reading mClient.asBinder() also threw
            // away a perfectly good mSession, leaving this window with nativeUid/Pid
            // = -1 forever: no native sample, no real-time follow, and only a desktop
            // restart (which builds a new WindowState and layer) could recover.
            runCatching { motionLayer.motionSession = window.getObjectFieldAs<Any>("mSession") }
                .onFailure {
                    motionLayer.motionSession = null
                    owner.glassClient.record("motion window identity unavailable=${it.javaClass.simpleName}")
                }
            if (motionLayer.motionSession != null) {
                runCatching {
                    motionLayer.motionClient = window.getObjectFieldAs<Any>("mClient")
                        .callMethod("asBinder") as IBinder
                    owner.glassClient.record("motion window identity bound")
                }.onFailure {
                    // The client binder is only an optional Session fallback; the native
                    // binding needs mSession's uid/pid alone. Never discard the session.
                    motionLayer.motionClient = null
                    owner.glassClient.record(
                        "motion window identity bound session-only=${it.javaClass.simpleName}")
                }
            }
            XposedLog.i(owner.TAG, HomeDockWindow.LOG_TAG, "Dock surface created: frame=$frame, bounds=$bounds, blur=${config.blur}")
            if (!config.blur && Color.alpha(config.color) == 0) {
                XposedLog.w(owner.TAG, HomeDockWindow.LOG_TAG, "Dock color is transparent; select a visible color or enable blur")
            }
        }
        return layer
    }

    private fun applyAppearance(transaction: Any, layer: Layer, appearance: Appearance,
        keepDirectPosition: Boolean) {
        val (config, bounds, dark, visible, glass) = appearance
        val glassSurface = appearance.surface
        val glassReady = appearance.ready
        transaction.callMethod(HomeDockWindow.SET_LAYER, layer.effect, -1)
        transaction.callMethod(HomeDockWindow.SET_CROP, layer.effect, bounds.width(), bounds.height())
        transaction.callMethod(HomeDockWindow.SET_RADIUS, layer.effect, bounds.radius())
        transaction.callMethod(HomeDockWindow.SET_LAYER, layer.tint, 1)
        transaction.callMethod(HomeDockWindow.SET_CROP, layer.tint, bounds.width(), bounds.height())
        transaction.callMethod(HomeDockWindow.SET_RADIUS, layer.tint, bounds.radius())
        if (glassSurface != null && glass?.dead == false) {
            // Remote root attachment and retirement share one serial worker.
            // WMS still controls the owned parent's visibility and motion.
            owner.glassClient.attach(glass, layer.effect, glassReady)
            // Readiness controls opacity only. The capture root stays composited while
            // warming up, and layer.effect still owns launcher/rotation visibility.
            owner.glassClient.setReady(glass, glassReady)
        }
        if (owner.blurAvailable) {
            runCatching { transaction.callMethod("setBackgroundBlurRadius", layer.effect,
                if (config.blur && !(glassReady && glass?.dead == false)) 120 else 0) }
                .onFailure {
                    owner.blurAvailable = false
                    XposedLog.w(owner.TAG, HomeDockWindow.LOG_TAG, "Compositor blur unavailable; retaining color fallback", it)
                }
        }
        val color = when {
            config.glass -> DockGlassPreset.fallbackColor(dark)
            config.blur -> if (dark) 0x66505050 else 0x66FFFFFF
            else -> config.color
        }
        transaction.callMethod("setColor", layer.tint,
            floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f))
        transaction.callMethod("setAlpha", layer.tint,
            if (glassReady && glass?.dead == false) 0f else Color.alpha(color) / 255f)
        transaction.callMethod("show", layer.tint)
        transaction.callMethod(if (visible) "show" else "hide", layer.effect)
        // The layer can become visible before the reveal's first animation frame runs. Pose it
        // inside this very transaction, otherwise the dock is drawn once at its resting size
        // and only then flies in - the "shows first, animates after" artefact.
        // Material changes must not enqueue an old animation pose behind a newer frame.
        if (!keepDirectPosition) {
            if (layer.reveal.isRunning()) {
                // The base crop/radius above were just rewritten, so re-submit the current
                // morph even when its normalized values match the cached previous frame.
                owner.applyRevealPose(transaction, layer, SystemClock.uptimeMillis(), true)
            } else if (layer.reveal.hasPendingPose() ||
                layer.revealAlpha != 1f || !owner.hasRestingContainer(layer)) {
                owner.restoreRestingTransform(transaction, layer)
            }
        }
        val appliedGlass = glassReady && glass?.dead == false
        if (config.glass && layer.lastGlassReady != appliedGlass) {
            owner.glassClient.record("glass applied native=$appliedGlass fallbackBlur=${if (appliedGlass) 0 else 120} visible=$visible")
            layer.lastGlassReady = appliedGlass
        }
        // Use WMS's transaction, so visibility/position changes commit with the parent window.
    }

}
