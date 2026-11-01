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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.BuildConfig;
import com.sevtinge.hyperceiler.R;
import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.utils.LanguageHelper;

import java.util.Date;

import fan.appcompat.app.AppCompatActivity;

public class ExceptionCrashActivity extends AppCompatActivity implements View.OnLongClickListener {
    private static final String TAG = "Crash";
    private static final int MAX_EXTRA_LENGTH = 8 * 1024;
    private static final String EXTRA_CRASH_STACK = "crash_stack";
    private static final String EXTRA_CRASH_INFO = "crashInfo";
    private static final String[] STRING_EXTRA_KEYS = {
        "crash_message", "crash_type", EXTRA_CRASH_STACK,
        "crash_file", "crash_class", "crash_method", EXTRA_CRASH_INFO
    };

    private String buildMsg;
    private String fullMsg;
    private String stackMsg;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LanguageHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);
        getAppCompatActionBar().setTitle(getString(R.string.error));

        Intent intent = getIntent();
        if (!hasValidStringExtras(intent)) {
            AndroidLog.e(TAG, "Invalid crash intent, finish without clearing crash store");
            finish();
            return;
        }

        AppCrashStore.clear(this);

        Bundle extras = intent.getExtras();
        String message = valueOrFallback(getStringExtra(extras, "crash_message"), "Unknown");
        String exceptionType = valueOrFallback(getStringExtra(extras, "crash_type"), "Unknown");
        String fileName = valueOrFallback(getStringExtra(extras, "crash_file"), "Unknown");
        String className = valueOrFallback(getStringExtra(extras, "crash_class"), "Unknown");
        String methodName = valueOrFallback(getStringExtra(extras, "crash_method"), "Unknown");
        int lineNumber = getIntExtra(extras, "crash_line", -1);
        long crashTime = getLongExtra(extras, "crash_time", System.currentTimeMillis());
        String fullDetail = getStringExtra(extras, EXTRA_CRASH_STACK);
        if (fullDetail == null) {
            fullDetail = getStringExtra(extras, EXTRA_CRASH_INFO);
        }
        if (fullDetail == null) {
            fullDetail = "No stack trace";
        }

        Date timestamp = new Date(crashTime);

        buildMsg = getString(com.sevtinge.hyperceiler.core.R.string.error_version_name) + ": "  + BuildConfig.VERSION_NAME +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.error_git_hash) + ": "  + BuildConfig.GIT_HASH +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.error_build_time) + ": "  + BuildConfig.BUILD_TIME;

        fullMsg = getString(com.sevtinge.hyperceiler.core.R.string.error_message) + ": "  + message +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.error_type) + ": "  + exceptionType +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.error_file_name) + ": "  + fileName +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.safe_mode_recorder_class) + ": "  + className +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.safe_mode_recorder_method) + ": "  + methodName +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.safe_mode_recorder_line) + ": "  + lineNumber +
            "\n" + getString(com.sevtinge.hyperceiler.core.R.string.error_time) + ": " + timestamp;

        AndroidLog.e(TAG, "App Crash, log:\n" + fullMsg);

        CrashInfoItem versionNameView = findViewById(R.id.version_name);
        versionNameView.setValue(BuildConfig.VERSION_NAME);
        CrashInfoItem gitHashView = findViewById(R.id.git_hash);
        gitHashView.setValue(BuildConfig.GIT_HASH);
        CrashInfoItem buildTimeView = findViewById(R.id.build_time);
        buildTimeView.setValue(BuildConfig.BUILD_TIME);

        CrashInfoItem msgView = findViewById(R.id.message);
        msgView.setValue(message);

        CrashInfoItem typeView = findViewById(R.id.e_type);
        typeView.setValue(exceptionType);
        CrashInfoItem filenameView = findViewById(R.id.filename);
        filenameView.setValue(fileName);
        CrashInfoItem classnameView = findViewById(R.id.classname);
        classnameView.setValue(className);
        CrashInfoItem methodnameView = findViewById(R.id.methodname);
        methodnameView.setValue(methodName);
        CrashInfoItem lineView = findViewById(R.id.e_line);
        lineView.setValue(String.valueOf(lineNumber));
        CrashInfoItem timeView = findViewById(R.id.e_time);
        timeView.setValue(timestamp.toString());

        TextView stackView = findViewById(R.id.stack);
        stackMsg = fullDetail;
        stackView.setText(stackMsg);

        LinearLayout buildViews = findViewById(R.id.build_views);
        LinearLayout messageViews = findViewById(R.id.message_views);

        buildViews.setOnLongClickListener(this);
        messageViews.setOnLongClickListener(this);
        stackView.setOnLongClickListener(this);
    }

    @Override
    public boolean onLongClick(View v) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("text", buildMsg + "\n----------\n" + fullMsg + "\n----------\n" + stackMsg);
        cm.setPrimaryClip(clip);
        Toast.makeText(v.getContext(), getString(R.string.copy_ok), Toast.LENGTH_SHORT).show();

        return true;
    }

    /**
     * 只接受字符串类型的 crash extra。任何非 String 的同名 extra（包括旧版序列化
     * Throwable 的 crashInfo）都会让本次界面直接拒绝启动，避免外部可控反序列化。
     */
    private boolean hasValidStringExtras(@Nullable Intent intent) {
        if (intent == null) return false;
        Bundle extras;
        try {
            extras = intent.getExtras();
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Unreadable crash intent extras", e);
            return false;
        }
        if (extras == null) return true;
        try {
            for (String key : STRING_EXTRA_KEYS) {
                Object value = extras.get(key);
                if (value != null && !(value instanceof String)) {
                    AndroidLog.e(TAG, "Rejected non-string crash extra: " + key);
                    return false;
                }
            }
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Unreadable crash intent extras", e);
            return false;
        }
        return true;
    }

    @Nullable
    private static String getStringExtra(@Nullable Bundle extras, String key) {
        if (extras == null) return null;
        try {
            Object value = extras.get(key);
            return value instanceof String string ? truncate(string) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int getIntExtra(@Nullable Bundle extras, String key, int fallback) {
        if (extras == null) return fallback;
        try {
            Object value = extras.get(key);
            return value instanceof Integer intValue ? intValue : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long getLongExtra(@Nullable Bundle extras, String key, long fallback) {
        if (extras == null) return fallback;
        try {
            Object value = extras.get(key);
            return value instanceof Long longValue ? longValue : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Nullable
    private static String truncate(@Nullable String value) {
        if (value == null) return null;
        return value.length() > MAX_EXTRA_LENGTH ? value.substring(0, MAX_EXTRA_LENGTH) : value;
    }

    private String valueOrFallback(String value, String fallback) {
        String truncated = truncate(value);
        return truncated == null || truncated.isEmpty() ? fallback : truncated;
    }
}
