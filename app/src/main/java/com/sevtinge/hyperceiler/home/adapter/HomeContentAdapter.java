package com.sevtinge.hyperceiler.home.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class HomeContentAdapter extends FragmentStateAdapter {

    private final List<Supplier<Fragment>> mFragmentFactories = new ArrayList<>();

    public HomeContentAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity.getSupportFragmentManager());
    }

    public void addFragment(@NonNull Supplier<Fragment> factory) {
        mFragmentFactories.add(factory);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // 每次都由工厂新建实例，destroyItem 移除后不能再返回同一个已销毁的 Fragment
        return mFragmentFactories.get(position).get();
    }

    @Override
    public int getItemCount() {
        return mFragmentFactories.size();
    }
}
