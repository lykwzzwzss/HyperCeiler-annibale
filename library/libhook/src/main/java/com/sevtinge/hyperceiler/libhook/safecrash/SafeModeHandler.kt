/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.safecrash

import android.app.ApplicationErrorReport
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemProperties
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.CrashIntentContract
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.callback.ICrashHandler
import com.sevtinge.hyperceiler.libhook.utils.api.PropUtils.setProp

object SafeModeHandler : ICrashHandler {
    private const val TAG = "SafeModeHandler"

    // 旧键迁移结果缓存；新键出现后仍以新键为准
    @Volatile
    private var sLegacySettingsSafeMode: Boolean? = null

    override fun onCrashDetected(
        context: Context,
        pkgName: String,
        crashInfo: ApplicationErrorReport.CrashInfo?,
        longMsg: String?,
        stackTrace: String?
    ): Boolean {
        if (isInSafeModeByConfig(pkgName) || isInSafeModeByProp(pkgName)) {
            XposedLog.d(TAG, "$pkgName is already in safe mode, skipping UI launch.")
            return false
        }

        val alias = CrashScope.getAlias(pkgName) ?: return false
        updateCrashProp(alias)

        return runCatching {
            val intent = Intent().apply {
                component = ComponentName("com.sevtinge.hyperceiler", "com.sevtinge.hyperceiler.home.safemode.CrashActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(CrashIntentContract.KEY_PKG_ALIAS, alias)

                if (longMsg != null) putExtra(CrashIntentContract.KEY_LONG_MSG, longMsg)
                if (stackTrace != null) putExtra(CrashIntentContract.KEY_STACK_TRACE, stackTrace)

                crashInfo?.let {
                    putExtra(CrashIntentContract.KEY_THROW_CLASS, it.throwClassName)
                    putExtra(CrashIntentContract.KEY_THROW_FILE, it.throwFileName)
                    putExtra(CrashIntentContract.KEY_THROW_LINE, it.throwLineNumber)
                    putExtra(CrashIntentContract.KEY_THROW_METHOD, it.throwMethodName)
                }
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            XposedLog.e(TAG, "Failed to start CrashActivity after safe mode update", it)
            true
        }
    }

    private fun isInSafeModeByConfig(pkgName: String): Boolean {
        val key = CrashScope.getSafeModeConfigKey(pkgName) ?: return false
        val prefs = runCatching { PrefsBridge.getSharedPreferences() }.getOrNull()
        if (prefs != null && prefs.contains(prefKey(key))) {
            return PrefsBridge.getBoolean(key)
        }

        val legacyKey = CrashScope.getLegacySafeModeConfigKey(pkgName) ?: return PrefsBridge.getBoolean(key)
        sLegacySettingsSafeMode?.let { return it }

        // 兼容旧键：新键未设置时读一次旧键并迁移写回新键
        val legacyValue = PrefsBridge.getBoolean(legacyKey)
        PrefsBridge.putBoolean(key, legacyValue)
        sLegacySettingsSafeMode = legacyValue
        return legacyValue
    }

    private fun prefKey(key: String): String =
        if (key.startsWith("prefs_key_")) key else "prefs_key_$key"

    private fun isInSafeModeByProp(pkgName: String): Boolean {
        return CrashScope.isPackageInSafeModeProp(pkgName)
    }

    @JvmStatic
    fun updateCrashProp(alias: String) {
        val uniqueProp = (CrashScope.getCrashingAliases() + alias)
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(",")
        applyCrashProp(uniqueProp)
    }

    @JvmStatic
    fun removeCrashProp(alias: String) {
        val newProp = CrashScope.getCrashingAliases()
            .filter { it != alias && it.isNotEmpty() }
            .sorted()
            .joinToString(",")
        applyCrashProp(newProp)
    }

    private fun applyCrashProp(value: String) {
        try {
            if (setProp(CrashScope.PROP_SAFE_MODE, value)) {
                return
            }
            XposedLog.w(TAG, "setProp returned false, falling back to SystemProperties.set")
        } catch (_: Throwable) {
            XposedLog.w(TAG, "setProp threw, falling back to SystemProperties.set")
        }

        try {
            SystemProperties.set(CrashScope.PROP_SAFE_MODE, value)
        } catch (ex: Exception) {
            XposedLog.e(TAG, "Failed to update safe mode prop", ex)
        }
    }
}
