/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock

import android.os.SystemClock
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.loadClass

/**
 * Surface plumbing for [HomeDockWindow].
 *
 * <p>Split out of the rule file for size only: both objects are stateless with respect to the
 * hook, so relocating them changes no behaviour. `internal` because a top-level `private` would be
 * file-scoped and the rule file still calls them.
 */

/**
 * Sample-by-time console for a record whose interesting events are transitions.
 *
 * <p>Lives outside the companion because it carries mutable state; a plain object with an
 * `@Volatile` stamp is enough for the frame loop, which is single-threaded under the layer lock.
 */
internal object SCENE_LOG {
    @Volatile private var lastAtMs = 0L

    /** @return true when this event should be recorded, and arms the window when it should. */
    fun sample(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAtMs < HomeDockWindow.SCENE_LOG_MIN_GAP_MS) return false
        lastAtMs = now
        return true
    }
}

/** Builds and tears down the launcher effect/colour layers. */
internal object Surfaces {
    fun buildLayer(name: String, parent: Any, color: Boolean): Any {
        val builder = loadClass("android.view.SurfaceControl\$Builder").getConstructor().newInstance()
        builder.callMethod("setName", name)
        builder.callMethod("setParent", parent)
        builder.callMethod("setHidden", true)
        builder.callMethod(if (color) "setColorLayer" else "setEffectLayer")
        return builder.callMethod("build")!!
    }

    fun destroySurface(surface: Any) {
        val transaction = loadClass(HomeDockWindow.TRANSACTION).getConstructor().newInstance()
        try {
            if (surface.callMethod(HomeDockWindow.IS_VALID) == true) {
                transaction.callMethod("remove", surface)
                transaction.callMethod("apply")
            }
        } finally {
            try { transaction.callMethod(HomeDockWindow.METHOD_CLOSE) }
            finally { surface.callMethod(HomeDockWindow.METHOD_RELEASE) }
        }
    }
}
