/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.home.gesture

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.appbase.systemframework.GlobalActionBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import io.github.lingqiqi5211.ezhooktool.core.findMethod
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import java.util.WeakHashMap
import kotlin.math.abs

/** Gesture fallback for HyperOS 4's dex-less Flutter launcher. */
object HomeGestureOS4 : BaseHook() {
    private const val LAUNCHER_ACTIVITY = "com.miui.home.launcher.Launcher"
    private const val DOUBLE_TAP_TIMEOUT = 500L

    private val states = WeakHashMap<Activity, GestureState>()

    override fun init() {
        registerHotReloadCleanup { states.clear() }

        // HyperOS 4's launcher is a Flutter surface. It consumes the touch inside
        // Window.superDispatchTouchEvent, so Activity.onTouchEvent is never reached for the
        // gestures the engine claims. Activity.dispatchTouchEvent is the Window.Callback entry
        // and runs before that consumption; onTouchEvent stays as the fallback for builds whose
        // root view leaves the event unconsumed.
        runCatching {
            Activity::class.java.findMethod {
                name("dispatchTouchEvent")
                parameterTypes(MotionEvent::class.java)
            }.createBeforeHook { param ->
                runCatching {
                    if (param.thisObject is Activity) {
                        val activity = param.thisObject as Activity
                        val event = param.args[0] as MotionEvent
                        if (dispatchGesture(activity, event)) param.result = true
                    }
                }.onFailure {
                    XposedLog.w(TAG, "system",
                        "HomeGestureOS4 dispatch hook body failed; Flutter 消费触摸时可能不触发", it)
                }
            }
        }.onFailure {
            XposedLog.w(TAG, "system", "HomeGestureOS4 dispatchTouchEvent hook unavailable; " +
                "Flutter 消费触摸时可能不触发, falling back to onTouchEvent", it)
        }

        runCatching {
            Activity::class.java.findMethod {
                name("onTouchEvent")
                parameterTypes(MotionEvent::class.java)
            }.createBeforeHook { param ->
                runCatching {
                    val activity = param.thisObject as Activity
                    if (activity.componentName.className != LAUNCHER_ACTIVITY) return@runCatching
                    val event = param.args[0] as MotionEvent
                    val state = states.getOrPut(activity) {
                        GestureState(ViewConfiguration.get(activity).scaledTouchSlop * 2)
                    }
                    // The dispatch hook above already processed this exact event. Running the
                    // state machine twice would turn one tap into a fake double tap.
                    if (state.isDispatchDuplicate(event)) return@runCatching
                    if (state.handle(activity, event)) param.result = true
                }.onFailure {
                    XposedLog.w(TAG, "system", "HomeGestureOS4 touch fallback failed", it)
                }
            }
        }.onFailure {
            XposedLog.w(TAG, "system", "HomeGestureOS4 onTouchEvent hook unavailable", it)
        }
    }

    private fun dispatchGesture(activity: Activity, event: MotionEvent): Boolean {
        if (activity.componentName.className != LAUNCHER_ACTIVITY) return false
        val state = states.getOrPut(activity) {
            GestureState(ViewConfiguration.get(activity).scaledTouchSlop * 2)
        }
        state.markDispatch(event)
        return state.handle(activity, event)
    }

    private class GestureState(private val touchSlop: Int) {
        private var downX = 0f
        private var downY = 0f
        private var maxPointerCount = 1
        private var firstTapX = 0f
        private var firstTapY = 0f
        private var firstTapTime = 0L
        /** Identity and time of the event the dispatch hook already fed into [handle]. */
        private var dispatchedEvent: MotionEvent? = null
        private var dispatchedEventTime = 0L

        fun markDispatch(event: MotionEvent) {
            dispatchedEvent = event
            dispatchedEventTime = event.eventTime
        }

        // The time is compared as a value: MotionEvent instances are pooled and the same object
        // can come back for a later event while the identity check alone would still match.
        fun isDispatchDuplicate(event: MotionEvent): Boolean =
            event === dispatchedEvent && event.eventTime == dispatchedEventTime

        fun handle(activity: Activity, event: MotionEvent): Boolean {
            maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    maxPointerCount = 1
                    return false
                }

                MotionEvent.ACTION_CANCEL -> {
                    maxPointerCount = 1
                    return false
                }

                MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - downX
                    val deltaY = event.rawY - downY
                    val swipeThreshold = maxOf(dp2px(64), touchSlop * 3)

                    if (abs(deltaY) >= swipeThreshold && abs(deltaY) > abs(deltaX) * 1.5f) {
                        val suffix = if (maxPointerCount >= 2) "2" else ""
                        val direction = if (deltaY < 0) "up" else "down"
                        maxPointerCount = 1
                        firstTapTime = 0L
                        return GlobalActionBridge.handleAction(
                            activity,
                            "home_gesture_${direction}_swipe$suffix"
                        )
                    }

                    if (maxPointerCount == 1 && abs(deltaX) <= touchSlop && abs(deltaY) <= touchSlop) {
                        val now = SystemClock.elapsedRealtime()
                        val isDoubleTap = now - firstTapTime <= DOUBLE_TAP_TIMEOUT &&
                            abs(event.rawX - firstTapX) <= touchSlop &&
                            abs(event.rawY - firstTapY) <= touchSlop
                        if (isDoubleTap) {
                            firstTapTime = 0L
                            return GlobalActionBridge.handleAction(activity, "home_gesture_double_tap")
                        }
                        firstTapX = event.rawX
                        firstTapY = event.rawY
                        firstTapTime = now
                    }
                    maxPointerCount = 1
                }
            }
            return false
        }
    }
}
