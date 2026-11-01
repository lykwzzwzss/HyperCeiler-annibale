package com.sevtinge.hyperceiler.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sevtinge.hyperceiler.R;

import fan.appcompat.app.Fragment;

public class SettingsPageFragment extends Fragment {

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setThemeRes(R.style.NavigatorContentTheme);
    }

    @Override
    public View onInflateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewInflated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewInflated(view, savedInstanceState);
        getActionBar().setTitle(R.string.navigation_settings_title);
        // 只在首次创建时挂载子 Fragment，重建时交给 FragmentManager 恢复
        if (savedInstanceState == null) {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.prefs_container, new SettingsFragment())
                .commitAllowingStateLoss();
        }
    }
}
