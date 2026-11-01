package com.sevtinge.hyperceiler.home.adapter;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager.widget.PagerAdapter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 仿 ViewPager2 风格的 ViewPager1 适配器
 * 优点：滑动极速，API 现代，不报废弃警告
 */
public abstract class FragmentStateAdapter extends PagerAdapter {

    private final FragmentManager mFragmentManager;
    private FragmentTransaction mCurTransaction = null;
    private Fragment mCurrentPrimaryItem = null;
    // 本批次被 destroyItem 移除的实例，事务提交前不得再被 instantiateItem 复用
    private final Set<Fragment> mPendingRemovals = Collections.newSetFromMap(new IdentityHashMap<>());

    public FragmentStateAdapter(@NonNull FragmentManager fm) {
        mFragmentManager = fm;
    }



    public abstract int getItemCount();
    public abstract Fragment createFragment(int position);

    public String getFragmentTag(int position) {
        return buildFragmentTag(position);
    }

    protected static String buildFragmentTag(int position) {
        // 统一暴露 tag 规则，供外部在语言切换等场景下精确清理旧 fragment。
        return "f" + position;
    }

    @Override
    public int getCount() {
        return getItemCount();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return ((Fragment) object).getView() == view;
    }

    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }

        // 唯一标识符，确保 Fragment 正确挂载
        String name = getFragmentTag(position);
        Fragment fragment = mFragmentManager.findFragmentByTag(name);
        if (fragment != null && (fragment.isRemoving() || mPendingRemovals.contains(fragment))) {
            // 已在 destroyItem 中移除的实例不能再复用，否则会拿到已销毁的 Fragment
            fragment = null;
        }

        if (fragment != null) {
            mCurTransaction.attach(fragment);
        } else {
            fragment = createFragment(position);
            mCurTransaction.add(container.getId(), fragment, name);
        }

        if (fragment != mCurrentPrimaryItem) {
            fragment.setMenuVisibility(false);
            fragment.setUserVisibleHint(false);
        }

        return fragment;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        if (mCurTransaction == null) {
            mCurTransaction = mFragmentManager.beginTransaction();
        }
        Fragment fragment = (Fragment) object;
        // 记录待移除实例并同步从适配器缓存移除，避免下次再取到已销毁的 Fragment
        mPendingRemovals.add(fragment);
        // 销毁视图并移除 Fragment，达到 FragmentStateAdapter 节省内存的效果
        mCurTransaction.remove(fragment);
    }

    @Override
    public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        Fragment fragment = (Fragment) object;
        if (fragment != mCurrentPrimaryItem) {
            if (mCurrentPrimaryItem != null) {
                mCurrentPrimaryItem.setMenuVisibility(false);
                mCurrentPrimaryItem.setUserVisibleHint(false);
            }
            fragment.setMenuVisibility(true);
            fragment.setUserVisibleHint(true);
            mCurrentPrimaryItem = fragment;
        }
    }

    @Override
    public void finishUpdate(@NonNull ViewGroup container) {
        if (mCurTransaction != null) {
            mCurTransaction.commitNowAllowingStateLoss();
            mCurTransaction = null;
        }
        // 事务已执行，被移除的 Fragment 不会再被 findFragmentByTag 命中
        mPendingRemovals.clear();
    }
}
