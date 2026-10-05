/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.content.Context
import android.view.WindowManager
import java.lang.ref.WeakReference
import com.sevtinge.hyperceiler.common.log.XposedLog
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldAs

/**
 * WindowState hooks for [HomeDockWindow].
 *
 * <p>Split out of the rule file for size only. It used to be an `inner class`, so it could reach the
 * rule's state implicitly; every such access is now an explicit `owner.` read against the single
 * [owner] reference. The body is otherwise unchanged, which is the whole point of the split: the
 * two processes that drive this hook (the rule's own frame loop and WMS callbacks) must observe
 * exactly the same behaviour as before.
 *
 * <p>Dependencies are passed as one reference rather than 23 constructor parameters - measuring the
 * body showed it touches 14 fields and 9 members of the owner, which no parameter list should carry.
 */
internal class DockWindowHooks(
    private val owner: HomeDockWindow,
    private val windowClass: Class<*>
) {
    private val attrsField = windowClass.getDeclaredField("mAttrs").apply { isAccessible = true }

    fun install() {
        windowClass.getDeclaredMethod("prepareSurfaces").apply { isAccessible = true }
            .createAfterHook { param -> runCatching { prepareWindow(param.thisObject) }.onFailure { owner.notePrepareFailure(it) } }
        windowClass.getDeclaredMethod("removeImmediately").apply { isAccessible = true }
            .createBeforeHook { param -> synchronized(owner.layers) { owner.removeLayer(param.thisObject) } }
        installNativeMotionTransaction()
        installWallpaper()
    }

    private fun installNativeMotionTransaction() {
        runCatching {
            val stub = loadClass("android.view.IWindowManager\$Stub")
            val transact = stub.getDeclaredMethod("onTransact", Integer.TYPE, Parcel::class.java,
                Parcel::class.java, Integer.TYPE).apply { isAccessible = true }
            transact.createBeforeHook { param ->
                    // A hot reload cannot physically remove callbacks already registered in
                    // system_server. Never short-circuit here: restore the Parcel so every live
                    // endpoint can observe the same frame, including the newest hook.
                    if (owner.stopped) return@createBeforeHook
                    val code = param.args[0] as Int
                    val layout = code == HomeLayoutNativeEndpointOS4.TRANSACTION_CODE
                    if (!layout && !DockNativeMotionEndpoint.handles(code)) return@createBeforeHook
                    val data = param.args[1] as Parcel
                    val position = data.dataPosition()
                    if (layout) owner.layoutReply.remove() else owner.nativeMotionReply.remove()
                    runCatching {
                        if (layout) {
                            owner.layoutReply.set(owner.layoutEndpoint.receive(data, param.args[3] as Int))
                        } else {
                            owner.nativeMotionReply.set(owner.nativeMotionEndpoint.receive(
                                code, data, param.args[3] as Int))
                        }
                    }.onFailure {
                        val diagnostic = if (layout) "native-layout-transaction-error"
                            else "native-motion-transaction-error"
                        if (owner.observed.add(diagnostic)) {
                            owner.glassClient.record("native transaction rejected=${it.javaClass.simpleName}")
                        }
                    }.also {
                        data.setDataPosition(position)
                    }
                }
            transact.createAfterHook { param ->
                val code = param.args[0] as Int
                val layout = code == HomeLayoutNativeEndpointOS4.TRANSACTION_CODE
                val state = code == DockNativeMotionEndpoint.STATE_TRANSACTION_CODE
                if (owner.stopped
                    || (!layout && !state && !DockNativeMotionEndpoint.handles(code))) {
                    return@createAfterHook
                }
                val reply = param.args[2] as? Parcel ?: return@createAfterHook
                reply.setDataPosition(0)
                if (state) {
                    // Answered here, not pushed: system_server is the only side that knows
                    // whether the panel is interactive and its own WindowState is visible.
                    // The launcher-native side has no reverse channel to be told. The body is
                    // empty, so there is nothing to
                    // parse in the before hook - it only has to leave the code alone.
                    reply.writeInt(DockNativeMotionEndpoint.STATE_ACK)
                    reply.writeInt(if (owner.desktopDrawable()) 1 else 0)
                } else if (layout) {
                    val result = owner.layoutReply.get()
                    owner.layoutReply.remove()
                    reply.writeInt(result?.acknowledgment() ?: HomeLayoutNativeEndpointOS4.ACK)
                    reply.writeInt(result?.gridEnabled() ?: 0)
                    reply.writeInt(result?.cellX() ?: 0)
                    reply.writeInt(result?.cellY() ?: 0)
                    val knobEnabled = result?.knobEnabled() ?: IntArray(0)
                    val knobDeltas = result?.knobDeltaDp() ?: IntArray(0)
                    reply.writeInt(knobEnabled.size)
                    for (index in knobEnabled.indices) {
                        reply.writeInt(knobEnabled[index])
                        reply.writeInt(if (index < knobDeltas.size) knobDeltas[index] else 0)
                    }
                    // The code-patch features ride along as a fixed run of values; the native
                    // side refuses the whole snapshot when any of them is out of range.
                    val tweaks = result?.tweaks() ?: IntArray(0)
                    for (index in 0 until HomeLayoutNativeEndpointOS4.TWEAK_COUNT) {
                        reply.writeInt(if (index < tweaks.size) tweaks[index] else 0)
                    }
                    // Appended after every pre-existing field: this endpoint runs in
                    // system_server and the reader in the launcher, and the two processes are
                    // injected independently, so a stale server must not shift the fields an
                    // up-to-date reader expects. An old reader just ignores the trailing value.
                    reply.writeInt(result?.titleBlank() ?: 0)
                } else {
                    val acknowledgment = owner.nativeMotionReply.get() ?: DockNativeMotionEndpoint.ACK
                    owner.nativeMotionReply.remove()
                    reply.writeInt(acknowledgment)
                }
                param.result = true
            }
            owner.glassClient.record("native motion IWindowManager endpoint ready")
        }.onFailure {
            owner.glassClient.record("native motion IWindowManager endpoint unavailable=${it.javaClass.simpleName}")
        }
    }

    private fun prepareWindow(window: Any) {
        if (owner.stopped) return
        val attrs = attrsField.get(window) as WindowManager.LayoutParams
        if (attrs.packageName != "com.miui.home") return
        // Only launcher windows reach this point, so this is the cheapest place to pick up a
        // changed parameter. It is what makes an edit survive a desktop restart: the restart
        // rebuilds the WindowState and traverses into here, but it never re-creates the hook.
        // The style query is asynchronous and throttled; its result lands on a later traversal.
        owner.glassClient.refreshRevealStyle()
        owner.glassClient.refreshGeometry()
        owner.refreshSettings()
        val title = attrs.title.toString()
        synchronized(owner.layers) {
            if (owner.stopped) return
            if (owner.settings.enabled && owner.observed.size < 12 && owner.observed.add("${attrs.type}:$title")) {
                XposedLog.i(owner.TAG, HomeDockWindow.LOG_TAG, "Launcher window: type=${attrs.type}, title=${title.take(160)}")
            }
            if (!isLauncher(window, attrs)) return
            owner.service = window.getObjectFieldAs<Any>("mWmService")
            // Unconditional on purpose: this is the *bootstrap* for the provider channel, so
            // gating it on owner.settings.enabled would deadlock. owner.settings.enabled is itself only
            // trustworthy once the provider has answered (see owner.refreshSettings()), and the
            // provider read needs styleContext, which only bindDiagnostics() assigns. Whenever
            // LSPosed's remote push is lost - the exact failure this whole path exists to
            // survive - the snapshot stays frozen at its old value, the gate never opens, and
            // the geometry knobs can never be refreshed again. The context is only used for
            // read-only content queries, so binding it while the dock is disabled costs one
            // object reference and nothing else.
            owner.glassClient.bindDiagnostics(owner.service!!.getObjectFieldAs<Context>("mContext"))
            if (owner.revealStyleWindow.get() !== window) {
                owner.revealStyleWindow = WeakReference(window)
                // system_server survives a launcher restart. Bypass its old 30 s style limit
                // once per new window, asynchronously, never query under WMS/owner.layers locks.
                owner.glassClient.refreshRevealStyle(force = true)
            }
            owner.registerDisplayListener()
            owner.updateLayer(window)
            owner.scheduleNativeBindSweep()
        }
    }

    private fun isLauncher(window: Any, attrs: WindowManager.LayoutParams): Boolean =
        DockWindowPolicy.isLauncherWindow(attrs.packageName, attrs.title.toString(), attrs.type,
            window.callMethod("getDisplayId") as Int)

    private fun installWallpaper() {
        // Read only the exact launcher's scoped command; never alter wallpaper or gesture handling.
        runCatching {
            val endpoint = DockWallpaperEndpoint.resolve(loadClass("com.android.server.wm.WallpaperController"),
                windowClass, loadClass("com.android.server.wm.Session"), IBinder::class.java, Bundle::class.java)
            val command = endpoint.method().apply { isAccessible = true }
            command.createBeforeHook { param ->
                runCatching { wallpaperCommand(endpoint, param, captureOnly = true) }
                    .onFailure { reportMotionError(it) }
            }
            command.createAfterHook { param ->
                runCatching { wallpaperCommand(endpoint, param) }.onFailure { reportMotionError(it) }
            }
            XposedLog.i(owner.TAG, HomeDockWindow.LOG_TAG, "Dock recents motion observer ready")
            owner.glassClient.record("motion observer ready endpoint=${command.toGenericString()}")
        }.onFailure {
            owner.glassClient.record("motion observer unsupported=${it.javaClass.simpleName}: ${it.message?.take(160)}")
            XposedLog.w(owner.TAG, HomeDockWindow.LOG_TAG, "Dock recents motion unsupported; keeping background", it)
        }
    }

    private fun wallpaperCommand(endpoint: DockWallpaperEndpoint.Endpoint, param: HookParam,
        captureOnly: Boolean = false) {
        if (owner.stopped || !owner.settings.enabled) return
        val wm = owner.service ?: return
        // Session callbacks may run after WMS releases its lock. Preserve WM -> layer lock order.
        synchronized(wm.getObjectFieldAs<Any>(HomeDockWindow.WM_LOCK)) {
            synchronized(owner.layers) {
                if (owner.stopped || !owner.settings.enabled) return
                val window = if (endpoint.sessionScoped()) {
                    owner.layers.entries.firstOrNull { (_, layer) ->
                        DockWallpaperEndpoint.ownsWindow(layer.motionSession, layer.motionClient,
                            param.thisObject, param.args[0])
                    }?.key ?: return
                } else param.args[0] ?: return
                val attrs = attrsField.get(window) as WindowManager.LayoutParams
                if (!isLauncher(window, attrs)) return
                val extras = param.args[5] as? Bundle
                if (!captureOnly) recordCommand(param.args[1], extras)
                val action = param.args[1] as? String
                if (action != DockRecentsMotion.WALLPAPER_ACTION) return
                if (extras == null) return
                @Suppress("DEPRECATION")
                val scale = (extras.get("scale_to") as? Number)?.toDouble() ?: return
                val sceneAction = extras.getString("action")
                val command = param.args[1] as String
                val isSetTo = sceneAction == "setTo"
                val overview = DockRecentsMotion.overviewTarget(command, sceneAction, scale) ?: return
                if (captureOnly) {
                    val glass = owner.layers[window]?.glass ?: return
                    if (overview || DockRecentsMotion.homeTarget(command, sceneAction, scale)) {
                        owner.glassClient.releaseDepartureHold(glass)
                        // A plain app switch never rotates the display, so thaw the frozen home
                        // sample here. During a rotation the display is still landscape at this
                        // command and the rotation return owns the thaw instead.
                        if (!owner.layerUpdate.isDisplayRotated()) {
                            owner.glassClient.resumeFrozenCapture(glass, "home-command")
                        }
                        owner.requestTraversal()
                    } else if (DockRecentsMotion.leavingHomeTarget(command, sceneAction, scale)) {
                        // The app-launch zoom fires while the home wallpaper is still the blur
                        // source. Freeze here so the retained frame carries the home sample; the
                        // freeze keeps the panel drawn, so unlike the old undraw pause this costs
                        // nothing on the visible Dock and removes the app's colours from the
                        // returning frame.
                        owner.glassClient.holdForDeparture(glass)
                    }
                    // No departure hold on the generic path: pausing there put the still-visible
                    // Dock into the vendor's undraw state for every app switch. The drawable
                    // freeze above is safe because it keeps compositing the last home sample.
                    return
                }
                if (DockRecentsMotion.homeTarget(command, sceneAction, scale)) {
                    scheduleVisibleGlassRefresh(window)
                }
                updateOverview(window, overview, isSetTo, scale)
            }
        }
    }

    private fun scheduleVisibleGlassRefresh(window: Any) {
        val wm = owner.service ?: return
        wm.getObjectFieldAs<Handler>(HomeDockWindow.WM_HANDLER).postDelayed({
            runCatching {
                synchronized(wm.getObjectFieldAs<Any>(HomeDockWindow.WM_LOCK)) {
                    synchronized(owner.layers) {
                        val layer = owner.layers[window] ?: return@postDelayed
                        if (owner.stopped) return@postDelayed
                        val nativeLatest = owner.nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
                            ?.takeUnless { it.scene() == DockNativeMotion.SCENE_AUTO_AIM }
                        if (layer.overview || nativeLatest != null) return@postDelayed
                        if (!layer.nativeApplied && window.callMethod("isVisible") == true) {
                            layer.glass?.let { owner.glassClient.resume(it) }
                        }
                    }
                }
            }.onFailure { reportMotionError(it) }
        }, 120)
    }

    private fun recordCommand(action: Any?, extras: Bundle?) {
        if (owner.commandSamples++ >= 100) return
        @Suppress("DEPRECATION")
        val value = extras?.get("scale_to")
        owner.glassClient.record("launcher wallpaper command=${(action as? String)?.take(80)} " +
            "action=${extras?.getString("action")?.take(40)} " +
            "scale=${(value as? Number)?.toDouble()} type=${value?.javaClass?.simpleName}")
    }

    private fun updateOverview(window: Any, overview: Boolean, immediate: Boolean, scale: Double) {
        // A controller command can precede the first prepareSurfaces.
        if (owner.layers[window] == null) {
            owner.updateLayer(window)
            owner.requestTraversal() // Commit the new surface's parent/crop/visibility.
        }
        val layer = owner.layers[window] ?: return
        layer.overview = overview
        val now = SystemClock.uptimeMillis()
        layer.motionTime = now
        val wasRunning = layer.motion.isRunning(now)
        val targetChanged = layer.motion.setOverview(overview, now)
        if (immediate) layer.motion.finish()
        val nativeLatest = owner.nativeMotionEndpoint.latest(layer.nativeUid, layer.nativePid)
            ?.takeUnless { it.scene() == DockNativeMotion.SCENE_AUTO_AIM }
        val validationNow = System.nanoTime()
        if (overview && validationNow - layer.lastOverviewValidationNs > 80_000_000L) {
            layer.lastOverviewValidationNs = validationNow
            layer.overviewGeneration++
            val alreadyObserved = owner.nativeMotionEndpoint.sawOverviewSince(
                layer.nativeUid, layer.nativePid, validationNow - 100_000_000L)
            scheduleNativeHookValidation(window, layer, layer.overviewGeneration,
                layer.nativeUid, layer.nativePid, validationNow, alreadyObserved)
        }
        if (nativeLatest != null) {
            if (targetChanged || (immediate && wasRunning)
                || layer.nativeScene == 1
                || (layer.overview && layer.nativeScene == 0)) {
                owner.scheduleAnimationFrame(true)
            }
            return
        }
        if (!targetChanged && !(immediate && wasRunning)) {
            owner.glassClient.record("motion overview command without target change overview=$overview immediate=$immediate scale=$scale")
            if (overview) owner.scheduleAnimationFrame() else owner.requestTraversal()
        } else {
            layer.motionSamples = 0
            layer.motionEndPending = true
            owner.glassClient.record("motion target overview=$overview immediate=$immediate scale=$scale liftDp=${DockRecentsMotion.LIFT_DP} curve=sceneSpring")
            if (owner.directMotionAvailable) owner.scheduleAnimationFrame() else owner.requestTraversal()
        }
    }

    private fun scheduleNativeHookValidation(window: Any, expectedLayer: Layer,
        generation: Long, expectedUid: Int, expectedPid: Int,
        since: Long, alreadyObserved: Boolean) {
        val wm = owner.service ?: return
        wm.getObjectFieldAs<Handler>(HomeDockWindow.WM_HANDLER).postDelayed({
            runCatching {
                synchronized(wm.getObjectFieldAs<Any>(HomeDockWindow.WM_LOCK)) {
                    synchronized(owner.layers) {
                        val layer = owner.layers[window] ?: return@postDelayed
                        if (owner.stopped || layer !== expectedLayer
                            || layer.overviewGeneration != generation
                            || layer.nativeUid != expectedUid || layer.nativePid != expectedPid) {
                            return@postDelayed
                        }
                        if (alreadyObserved || owner.nativeMotionEndpoint.sawOverviewSince(
                                expectedUid, expectedPid, since)) {
                            layer.nativeOverviewGeneration = generation
                            return@postDelayed
                        }
                        owner.nativeMotionEndpoint.requestHookRevalidation(
                            expectedUid, expectedPid)
                        owner.glassClient.record(
                            "native motion missing after overview target; requesting semantic revalidation")
                    }
                }
            }.onFailure { reportMotionError(it) }
        }, 250)
    }

    private fun reportMotionError(error: Throwable) {
        synchronized(owner.layers) {
            if (owner.observed.add("recents-motion-error")) {
                owner.glassClient.record("motion observer error=${error.javaClass.simpleName}: ${error.message?.take(160)}")
                XposedLog.w(owner.TAG, HomeDockWindow.LOG_TAG, "Dock recents signal unavailable; keeping background", error)
            }
        }
    }

}
