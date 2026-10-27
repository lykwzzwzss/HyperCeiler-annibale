package com.sevtinge.hyperceiler.libhook.rules.packageinstaller

import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam
import io.github.lingqiqi5211.ezhooktool.xposed.java.IMethodHook
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Makes cancel follow the installer's own finish path instead of its store promotion page. */
object DisableInstallCancelRecommendation : BaseHook() {
    private lateinit var configGetter: Method
    private lateinit var constructor: Constructor<*>
    private lateinit var setEnabled: Method

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        configGetter = requiredMember("CancelInstallRecommendationConfigV1") {
            it.findMethod {
                matcher {
                    paramCount = 0
                    returnType = "com.miui.packageInstaller.model.ExitPageText"
                    usingStrings("exit_page_text", "[exitPageText] getExitPageText: raw=")
                }
            }.single()
        }
        constructor = configGetter.returnType.getDeclaredConstructor()
        setEnabled = configGetter.returnType.getMethod("setEnableCancelInstall", Boolean::class.javaPrimitiveType)
        return true
    }

    override fun init() {
        hookMethod(configGetter, object : IMethodHook {
            override fun after(param: HookParam) {
                // A missing cloud config otherwise means "enabled" in the cancel handler.
                val config = param.result ?: constructor.newInstance()
                setEnabled.invoke(config, false)
                param.result = config
            }
        })
        XposedLog.i(TAG, "com.miui.packageinstaller", "Resolved cancel recommendation config: $configGetter")
    }
}
