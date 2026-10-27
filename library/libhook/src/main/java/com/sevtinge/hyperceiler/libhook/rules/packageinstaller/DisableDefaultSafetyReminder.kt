package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Method

/** Removes the generic "not reviewed / use the store" row, not virus-specific rows. */
object DisableDefaultSafetyReminder : BaseHook() {
    private lateinit var factory: Method

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        factory = requiredMember("DefaultSafetyReminderFactoryV1") {
            it.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "com.miui.packageInstaller.ui.listcomponets.FullSafeInstallHintViewObject"
                    addUsingField("Lcom/miui/packageInstaller/model/CloudParams;->cooperationExemptRiskTip:Ljava/lang/Integer;")
                }
            }.single()
        }
        return true
    }

    override fun init() {
        hookMethod(factory, object : IMethodHook {
            override fun before(param: HookParam) { param.result = null }
        })
        XposedLog.i(TAG, "com.miui.packageinstaller", "Resolved default reminder factory: $factory")
    }
}
