package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.common.log.XposedLog;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandCompat;
import com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.FaceIslandPrefs;

public final class FaceIslandHostView extends FrameLayout {
    private static final int FAKE_BIG = 1;
    private static final int FAKE_SMALL = 3;
    private static final int REAL_BIG = 0;
    private static final int REAL_SMALL = 2;
    private final boolean checkGlyphMode;
    private final FaceIslandController controller;
    private final long cycle;
    private final boolean debugPreview;
    private final long generation;
    private boolean hostActive;
    private final long[] slotTokens;

    public FaceIslandHostView(Context context, FaceIslandController controller, long cycle) {
        this(context, controller, cycle, false);
    }

    public FaceIslandHostView(Context context, FaceIslandController controller, long cycle, boolean debugPreview) {
        super(context);
        this.slotTokens = new long[4];
        this.hostActive = true;
        this.controller = controller;
        this.cycle = cycle;
        this.generation = controller.newViewGeneration(cycle);
        this.debugPreview = debugPreview;
        this.checkGlyphMode = FaceIslandPrefs.isCheckGlyphMode();
        setLayoutParams(new ViewGroup.LayoutParams(1, 1));
        setContentDescription("解锁状态");
    }

    public View createTopView(Context pluginContext, ViewGroup parent, final boolean fake, final boolean small) {
        View root;
        if (!isHostCurrent()) {
            return null;
        }
        final long treeToken = replaceSlot(fake, small);
        if (small) {
            FrameLayout root2 = new FrameLayout(pluginContext);
            int smallId = resource(pluginContext, fake ? "fake_small_container" : "small_container", "id");
            if (smallId != 0) {
                root2.setId(smallId);
            }
            root2.addView(new FaceIslandRenderer(pluginContext, this.controller, this, 1, fake, true,
                this.checkGlyphMode, this.cycle, this.generation, treeToken),
                new FrameLayout.LayoutParams(dp(pluginContext, 32), dp(pluginContext, 34), 17));
            root2.setClipChildren(false);
            if (!this.debugPreview) {
                root2.setOnClickListener(new View.OnClickListener() { // from class: com.android.systemui.faceisland.FaceIslandHostView.1
                    @Override
                    public void onClick(View view) {
                        FaceIslandHostView.this.retrySlot(fake, small, treeToken);
                    }
                });
            }
            if (parent != null) {
                parent.addView(root2, new ViewGroup.LayoutParams(-1, -1));
            }
            return root2;
        }
        int layoutId = resource(pluginContext,
            fake ? "fake_dynamic_big_island_template" : "dynamic_big_island_template_standard", "layout");
        if (layoutId == 0) {
            XposedLog.e("FaceIslandHost", "big island layout not found, pkg=" + pluginContext.getPackageName());
            throw new IllegalStateException("原版顶部标准模板资源未找到");
        }
        View root3 = LayoutInflater.from(pluginContext).inflate(layoutId, parent, false);
        FrameLayout left = (FrameLayout) root3.findViewById(
            resource(pluginContext, fake ? "fake_area_left" : "area_left", "id"));
        FrameLayout right = (FrameLayout) root3.findViewById(
            resource(pluginContext, fake ? "fake_area_right" : "area_right", "id"));
        if (left == null || right == null) {
            XposedLog.e("FaceIslandHost", "big island areas missing, layoutId=" + layoutId);
            throw new IllegalStateException("原版左右区域未找到");
        }
        XposedLog.d("FaceIslandHost", "big island created, layoutId=" + layoutId
            + " left=" + left.getId() + " right=" + right.getId());
        FaceIslandRenderer lock = new FaceIslandRenderer(pluginContext, this.controller, this, 0, fake, false,
            this.checkGlyphMode, this.cycle, this.generation, treeToken);
        int edgeDistanceDp = Math.max(0, Math.min(16, 8 - FaceIslandPrefs.iconEdgeOffsetDp()));
        int edgeDistancePx = dp(pluginContext, edgeDistanceDp);
        lock.setTranslationX(edgeDistancePx - left.getPaddingLeft());
        left.addView(lock, new FrameLayout.LayoutParams(dp(pluginContext, 32), dp(pluginContext, 34), 8388627));
        FaceIslandRenderer face = new FaceIslandRenderer(pluginContext, this.controller, this, 1, fake, false,
            this.checkGlyphMode, this.cycle, this.generation, treeToken);
        face.setTranslationX(right.getPaddingRight() - edgeDistancePx);
        right.addView(face, new FrameLayout.LayoutParams(dp(pluginContext, 32), dp(pluginContext, 34), 8388629));
        if (!this.debugPreview) {
            View.OnClickListener click = new View.OnClickListener() { // from class: com.android.systemui.faceisland.FaceIslandHostView.2
                @Override
                public void onClick(View view) {
                    FaceIslandHostView.this.retrySlot(fake, small, treeToken);
                }
            };
            root = root3;
            root.setOnClickListener(click);
            left.setOnClickListener(click);
            right.setOnClickListener(click);
        } else {
            root = root3;
        }
        if (parent != null) {
            parent.addView(root);
        }
        return root;
    }

    public void retry() {
        if (this.debugPreview || !isHostCurrent()) {
            return;
        }
        this.controller.retry();
    }

    public void onPluginRemoved(String reason) {
        boolean current = isHostCurrent();
        retire();
        if (current) {
            this.controller.pluginRemoved(this.cycle);
        }
    }

    void retire() {
        this.hostActive = false;
        for (int i = 0; i < this.slotTokens.length; i++) {
            replaceSlot(i);
        }
    }

    boolean isRendererCurrent(boolean fake, boolean small, long expectedCycle, long expectedGeneration,
                              long expectedToken, FaceIslandSnapshot snapshot) {
        if (!this.hostActive || expectedCycle != this.cycle || expectedGeneration != this.generation
            || this.slotTokens[slotIndex(fake, small)] != expectedToken || snapshot == null
            || snapshot.cycle != this.cycle || snapshot.viewGen != this.generation) {
            return false;
        }
        return this.controller.isCurrent(this.cycle);
    }

    private boolean isHostCurrent() {
        if (!this.hostActive || !this.controller.isCurrent(this.cycle)) {
            return false;
        }
        FaceIslandSnapshot snapshot = this.controller.snapshot();
        return snapshot.cycle == this.cycle && snapshot.viewGen == this.generation;
    }

    private long replaceSlot(boolean fake, boolean small) {
        int slot = slotIndex(fake, small);
        replaceSlot(slot);
        return this.slotTokens[slot];
    }

    private void replaceSlot(int slot) {
        long token = this.slotTokens[slot];
        this.slotTokens[slot] = token == Long.MAX_VALUE ? 1L : token + 1;
        if (this.slotTokens[slot] == 0) {
            this.slotTokens[slot] = 1;
        }
    }

    private void retrySlot(boolean fake, boolean small, long token) {
        FaceIslandSnapshot snapshot = this.controller.snapshot();
        if (isRendererCurrent(fake, small, this.cycle, this.generation, token, snapshot)) {
            retry();
        }
    }

    private static int slotIndex(boolean z, boolean z2) {
        return z2 ? z ? 3 : 2 : z ? 1 : 0;
    }

    private static int resource(Context context, String name, String kind) {
        int id = context.getResources().getIdentifier(name, kind, context.getPackageName());
        if (id == 0) {
            id = context.getResources().getIdentifier(name, kind, FaceIslandCompat.SYSTEM_INTERFACE_PACKAGE);
        }
        return id == 0 ? context.getResources().getIdentifier(name, kind, "miui.systemui.dynamicisland") : id;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
