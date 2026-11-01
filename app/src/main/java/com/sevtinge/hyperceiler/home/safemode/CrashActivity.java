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
package com.sevtinge.hyperceiler.home.safemode;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.common.utils.CrashIntentContract;
import com.sevtinge.hyperceiler.common.utils.shell.ShellInit;
import com.sevtinge.hyperceiler.core.R;
import com.sevtinge.hyperceiler.libhook.safecrash.CrashScope;
import com.sevtinge.hyperceiler.utils.DialogHelper;
import com.sevtinge.hyperceiler.utils.LanguageHelper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fan.appcompat.app.AppCompatActivity;

public class CrashActivity extends AppCompatActivity {

    private static final String TAG = "CrashActivity";
    private static final long SHELL_INIT_TIMEOUT_MS = 3000L;
    private static final ExecutorService SHELL_INIT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hyperceiler-crash-shell-init");
        thread.setDaemon(true);
        return thread;
    });

    private String longMsg;
    private String stackTrace;
    private String throwClassName;
    private String throwFileName;
    private int throwLineNumber;
    private String throwMethodName;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageHelper.wrapContext(newBase));
    }

    @SuppressLint({"SetTextI18n", "StringFormatInvalid"})
    @Override
    public void onCreate(@Nullable Bundle bundle) {
        super.onCreate(bundle);
        initShellAsync();
        setContentView(R.layout.activity_crash_dialog);

        Intent intent = getIntent();
        if (intent == null) {
            finish();
            return;
        }
        CrashRecordStore.CrashRecord record =
            CrashRecordStore.read(this, intent.getStringExtra(CrashRecordStore.EXTRA_RECORD_ID));
        String alias = record != null ? record.packageAlias : intent.getStringExtra(CrashIntentContract.KEY_PKG_ALIAS);

        longMsg = record != null ? record.message : intent.getStringExtra(CrashIntentContract.KEY_LONG_MSG);
        stackTrace = record != null ? record.stackTrace : intent.getStringExtra(CrashIntentContract.KEY_STACK_TRACE);
        throwClassName = record != null ? record.className : intent.getStringExtra(CrashIntentContract.KEY_THROW_CLASS);
        throwFileName = record != null ? record.fileName : intent.getStringExtra(CrashIntentContract.KEY_THROW_FILE);
        throwLineNumber = record != null ? record.lineNumber : intent.getIntExtra(CrashIntentContract.KEY_THROW_LINE, -1);
        throwMethodName = record != null ? record.methodName : intent.getStringExtra(CrashIntentContract.KEY_THROW_METHOD);

        String realPkgName = record != null && record.packageName != null
            ? record.packageName
            : CrashScope.getPackageName(alias);
        if (realPkgName == null) realPkgName = alias != null ? alias : "unknown";

        String appName = getAppName(realPkgName);
        String msg = formatSafeModeDesc(appName, realPkgName);

        View view = LayoutInflater.from(this).inflate(R.layout.crash_report_dialog, null);
        TextView mMessageTv = view.findViewById(R.id.tv_message);
        mMessageTv.setText(msg);

        TextView mCrashRecordTv = view.findViewById(R.id.tv_record);
        Paint paint = mCrashRecordTv.getPaint();
        paint.setFlags(Paint.UNDERLINE_TEXT_FLAG | Paint.ANTI_ALIAS_FLAG);
        paint.setAntiAlias(true);

        mCrashRecordTv.setOnClickListener(v -> DialogHelper.showCrashMsgDialog(
            this, throwClassName, throwFileName, throwLineNumber, throwMethodName, longMsg, stackTrace
        ));

        DialogHelper.showCrashReportDialog(this, view);
    }

    /**
     * ShellInit 是进程级全局单例，且没有引用计数；这里只在后台触发初始化，
     * 不阻塞崩溃提示界面，超过 3 秒未完成则中断该次尝试并静默放弃。
     */
    private static void initShellAsync() {
        try {
            SHELL_INIT_EXECUTOR.execute(() -> {
                Thread worker = new Thread(ShellInit::init, "hyperceiler-shell-init");
                worker.setDaemon(true);
                worker.start();
                try {
                    worker.join(SHELL_INIT_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (worker.isAlive()) {
                    worker.interrupt();
                    AndroidLog.w(TAG, "ShellInit.init timed out after " + SHELL_INIT_TIMEOUT_MS + "ms");
                }
            });
        } catch (RuntimeException e) {
            AndroidLog.w(TAG, "Unable to run ShellInit.init in background", e);
        }
    }

    private String getAppName(String pkg) {
        if ("com.android.systemui".equals(pkg)) return getString(com.sevtinge.hyperceiler.core.R.string.system_ui);
        if ("com.android.settings".equals(pkg)) return getString(com.sevtinge.hyperceiler.core.R.string.system_settings);
        if ("com.miui.home".equals(pkg)) return getString(com.sevtinge.hyperceiler.core.R.string.mihome);
        if ("com.hchen.demo".equals(pkg)) return getString(com.sevtinge.hyperceiler.R.string.demo);
        if ("com.miui.securitycenter".equals(pkg)) return getString(com.sevtinge.hyperceiler.core.R.string.security_center_hyperos);
        return pkg;
    }

    private String formatSafeModeDesc(String appName, String pkg) {
        String msg = getString(com.sevtinge.hyperceiler.R.string.safe_mode_desc, " " + appName + " (" + pkg + ") ");
        return msg.replace("  ", " ")
            .replace("， ", "，")
            .replace("、 ", "、")
            .trim();
    }
}
