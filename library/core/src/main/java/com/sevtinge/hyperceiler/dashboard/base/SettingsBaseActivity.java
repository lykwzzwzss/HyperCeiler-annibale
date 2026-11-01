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
package com.sevtinge.hyperceiler.dashboard.base;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceFragmentCompat;

import com.sevtinge.hyperceiler.common.log.AndroidLog;
import com.sevtinge.hyperceiler.common.utils.AppLanguageHelper;
import com.sevtinge.hyperceiler.core.R;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import fan.appcompat.app.AppCompatActivity;

public class SettingsBaseActivity extends AppCompatActivity implements ActivityCallback {

    private static final String TAG = "SettingsBaseActivity";
    private static final String ALLOWED_FRAGMENT_PREFIX = "com.sevtinge.hyperceiler.";
    private static final String EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args";
    private static final int MAX_FRAGMENT_ARG_KEYS = 64;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguageHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //PageDecorator.applyGrayScaleFilter(this);
        //registerObserver(getApplicationContext());
        createUiFromIntent(savedInstanceState, getIntent());
    }

    protected void createUiFromIntent(Bundle savedInstanceState, Intent intent) {
        setContentView(R.layout.settings_sub);
        if (intent == null) {
            AndroidLog.e(TAG, "Missing intent, finishing");
            finish();
            return;
        }

        sanitizeShowFragmentArgs(intent);

        String initialFragmentName;
        try {
            initialFragmentName = getInitialFragmentName(intent);
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Rejected malformed fragment name", e);
            finish();
            return;
        }
        if (TextUtils.isEmpty(initialFragmentName)) {
            return;
        }

        Fragment targetFragment = instantiateAllowedFragment(initialFragmentName);
        if (targetFragment == null) {
            finish();
            return;
        }

        try {
            targetFragment.setArguments(getArguments(intent));
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Rejected malformed fragment arguments", e);
            intent.removeExtra(EXTRA_SHOW_FRAGMENT_ARGS);
            targetFragment.setArguments(null);
        }
        setFragment(targetFragment);
    }

    /**
     * 只允许本模块包内、非抽象、且继承自 PreferenceFragmentCompat 的 Fragment。
     */
    @Nullable
    private Fragment instantiateAllowedFragment(String fragmentName) {
        if (TextUtils.isEmpty(fragmentName) || !fragmentName.startsWith(ALLOWED_FRAGMENT_PREFIX)) {
            AndroidLog.e(TAG, "Rejected fragment outside module package: " + fragmentName);
            return null;
        }
        try {
            Class<?> clazz = Class.forName(fragmentName, false, getClassLoader());
            if (!PreferenceFragmentCompat.class.isAssignableFrom(clazz)) {
                AndroidLog.e(TAG, "Rejected non-preference fragment: " + fragmentName);
                return null;
            }
            if (Modifier.isAbstract(clazz.getModifiers()) || clazz.isInterface()) {
                AndroidLog.e(TAG, "Rejected abstract fragment: " + fragmentName);
                return null;
            }
            // 与 Fragment.instantiate 一致：要求 public 无参构造。
            return (Fragment) clazz.getConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            AndroidLog.e(TAG, "Fragment class not found: " + fragmentName, e);
        } catch (InstantiationException | IllegalAccessException e) {
            AndroidLog.e(TAG, "Fragment cannot be instantiated: " + fragmentName, e);
        } catch (NoSuchMethodException | InvocationTargetException e) {
            AndroidLog.e(TAG, "Fragment has no usable no-arg constructor: " + fragmentName, e);
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Fragment instantiation failed: " + fragmentName, e);
        }
        return null;
    }

    /**
     * 忽略伪造的 show_fragment_args：必须是 Bundle，且 key 数量不超过 64。
     */
    private void sanitizeShowFragmentArgs(Intent intent) {
        Bundle args;
        try {
            args = intent.getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGS);
        } catch (RuntimeException e) {
            AndroidLog.e(TAG, "Rejected non-Bundle fragment args", e);
            intent.removeExtra(EXTRA_SHOW_FRAGMENT_ARGS);
            return;
        }
        if (args == null) {
            return;
        }
        if (args.keySet().size() > MAX_FRAGMENT_ARG_KEYS) {
            AndroidLog.e(TAG, "Rejected fragment args with too many keys: " + args.keySet().size());
            intent.removeExtra(EXTRA_SHOW_FRAGMENT_ARGS);
        }
    }


    public void setFragment(Fragment fragment) {
        getSupportFragmentManager()
            .beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.frame_content, fragment)
            .commitNow();
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        return super.onOptionsItemSelected(item);
    }
}
