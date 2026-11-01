package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import android.os.Handler
import android.os.Looper
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Field
import java.lang.reflect.Method

object DisableAnonymousSourceWarning : BaseHook() {
    private lateinit var dispatcher: Method
    private lateinit var continuation: Field
    private lateinit var invokeContinuation: Method

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        dispatcher = requiredMember("AnonymousSourceWarningDispatcherV1") {
            it.findMethod {
                matcher {
                    paramTypes(Int::class.javaPrimitiveType)
                    returnType(Void.TYPE)
                    usingStrings("showDialog:", "Did not find app info for DLG_UNKNOWN_SOURCES: ")
                }
            }.single()
        }
        continuation = dispatcher.declaringClass.declaredFields.single {
            it.type.name == "kotlin.jvm.functions.Function0"
        }.apply { isAccessible = true }
        // Resolve against the target's interface, not the module's Kotlin class loader.
        invokeContinuation = continuation.type.getMethod("invoke")
        return true
    }

    override fun init() {
        val main = Handler(Looper.getMainLooper())
        hookMethod(dispatcher, object : IMethodHook {
            override fun before(param: HookParam) {
                if (!InstallerWarningPolicy.skipAnonymousSourceDialog(param.args[0] as Int)) return
                val callback = continuation.get(param.thisObject) ?: return
                // The original positive button posts this same callback to the UI thread.
                // It proceeds to the install confirmation page, never installs the APK.
                if (main.post {
                    try {
                        invokeContinuation.invoke(callback)
                    } catch (t: Throwable) {
                        XposedLog.e(TAG, "com.miui.packageinstaller", "Anonymous-source continuation failed", t)
                    }
                }) param.result = null
            }
        })
        XposedLog.i(TAG, "com.miui.packageinstaller", "Resolved reminder dispatcher: $dispatcher")
    }
}
