package com.sevtinge.hyperceiler.libhook.rules.securitycenter

import android.content.Context
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHook
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

object NewPrivacyThumbnailBlur : BaseHook() {
    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        if (thumbnailBlur.isEmpty()) {
            XposedLog.e(TAG, packageName, "DexKit anchor not found: privacy thumbnail blur")
        }
        return true
    }

    private val thumbnailBlur by lazy<List<Method>> {
        // 主锚点：包含资源名的类里，匹配 (Context, String, boolean) 的写入方法。
        val byStringClass = optionalMemberList<Method>("newPtb") {
            it.findClass {
                matcher {
                    addUsingString("miui_recents_privacy_thumbnail_blur", StringMatchType.Contains)
                }
            }.findMethod {
                matcher {
                    paramTypes(Context::class.java, String::class.java, Boolean::class.java)
                }
            }
        }
        if (byStringClass.isNotEmpty()) {
            return@lazy byStringClass
        }

        // 回退 1：类名未混淆时直接按类名 + 方法形状定位。
        val byClassName = optionalMemberList<Method>("newPtbByClass") {
            it.findMethod {
                matcher {
                    declaredClass = "com.miui.permcenter.privacyblur.ThumbnailBlurUtils"
                    paramTypes(Context::class.java, String::class.java, Boolean::class.java)
                    returnType = "void"
                }
            }
        }
        if (byClassName.isNotEmpty()) {
            return@lazy byClassName
        }

        // 回退 2：不依赖类匹配，直接按字符串 + 方法形状定位。
        optionalMemberList<Method>("newPtbByShape") {
            it.findMethod {
                matcher {
                    addUsingString("miui_recents_privacy_thumbnail_blur", StringMatchType.Contains)
                    paramTypes(Context::class.java, String::class.java, Boolean::class.java)
                }
            }
        }
    }

    override fun init() {
        thumbnailBlur.forEach { method ->
            method.createBeforeHook { param ->
                if (Thread.currentThread().stackTrace.none {
                    it.className == "PrivacyThumbnailBlurSettings"
                }) {
                    param.result = null
                }
            }
        }
    }
}
