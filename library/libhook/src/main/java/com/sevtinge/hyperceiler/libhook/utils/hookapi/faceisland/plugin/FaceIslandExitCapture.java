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
package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.plugin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.lang.reflect.Method;

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes5.dex */
final class FaceIslandExitCapture {
    final float alpha;
    final Bitmap bitmap;
    final Rect containerRect;
    final float cornerRadius;
    final Rect localRect;
    final Rect screenRect;
    final Matrix screenToLocal;

    private FaceIslandExitCapture(Bitmap bitmap, Rect screen, Rect container, Rect local, Matrix inverse, float alpha, float cornerRadius) {
        this.bitmap = bitmap;
        this.screenRect = screen;
        this.containerRect = container;
        this.localRect = local;
        this.screenToLocal = inverse;
        this.alpha = alpha;
        this.cornerRadius = cornerRadius;
    }

    /*
     * 重建说明：原反编译结果把 try/finally 展开成了若干 catch 分支，并引用了未声明的
     * error 局部变量（无法编译）。这里按语义还原为「失败时回收 Bitmap、成功时移交所有权」，
     * 绘制顺序与判定条件与原反编译逐行一致。
     */
    static FaceIslandExitCapture take(View background, View content, View big) throws Exception {
        if (!background.isShown() || !content.isShown() || !big.isShown() || !contains(background, content) || !contains(background, big)) {
            throw new IllegalStateException("background_tree_not_visible");
        }
        OutlineData outline = readOutline(content);
        int[] bounds = FaceIslandExitGeometry.drawableBounds(number(background, "getActualLeft"), number(background, "getActualTop"), number(background, "getActualWidth"), number(background, "getActualHeight"), number(background, "getStokeWidth"), background.getWidth(), background.getHeight());
        if (bounds.length != 4) {
            throw new IllegalStateException("invalid_drawable_bounds");
        }
        Rect local = new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
        Matrix backgroundToScreen = globalMatrix(background);
        Matrix inverse = new Matrix();
        if (!backgroundToScreen.invert(inverse)) {
            throw new IllegalStateException("non_invertible_background_matrix");
        }
        RectF mapped = new RectF(local);
        backgroundToScreen.mapRect(mapped);
        if (!finite(mapped.left) || !finite(mapped.top) || !finite(mapped.right) || !finite(mapped.bottom)) {
            throw new IllegalStateException("non_finite_screen_bounds");
        }
        Rect screen = new Rect();
        mapped.roundOut(screen);
        Rect visible = new Rect();
        if (!screenVisibleRect(background, visible) || !screen.intersect(visible) || screen.isEmpty()) {
            throw new IllegalStateException("capsule_outside_visible_area");
        }
        if (screen.width() > background.getResources().getDisplayMetrics().widthPixels || screen.height() > background.getResources().getDisplayMetrics().heightPixels) {
            throw new IllegalStateException("snapshot_exceeds_display");
        }
        Bitmap bitmap = Bitmap.createBitmap(screen.width(), screen.height(), Bitmap.Config.ARGB_8888);
        boolean success = false;
        try {
            bitmap.setDensity(0);
            Canvas canvas = new Canvas(bitmap);
            float alpha = visibleAlpha(background);
            if (alpha <= 0.0f) {
                throw new IllegalStateException("background_alpha_zero");
            }
            int save = canvas.save();
            canvas.translate(-screen.left, -screen.top);
            Drawable drawable = (Drawable) call(background, "getDrawable");
            if (drawable == null) {
                throw new IllegalStateException("background_drawable_missing");
            }
            Rect oldBounds = new Rect(drawable.getBounds());
            int oldAlpha = drawableAlpha(drawable);
            try {
                drawable.setBounds(local);
                canvas.save();
                canvas.concat(backgroundToScreen);
                drawable.draw(canvas);
                canvas.restore();
                if (oldAlpha >= 0) {
                    drawable.setAlpha(oldAlpha);
                }
                Matrix contentToScreen = globalMatrix(content);
                int contentSave = canvas.save();
                canvas.concat(contentToScreen);
                int contentLayer = canvas.saveLayerAlpha(outline.rect, Math.round(relativeAlpha(content, background) * 255.0f), 31);
                Path path = new Path();
                path.addRoundRect(outline.rect, outline.radius, outline.radius, Path.Direction.CW);
                canvas.clipPath(path);
                content.draw(canvas);
                canvas.restoreToCount(contentLayer);
                canvas.restoreToCount(contentSave);
                canvas.restoreToCount(save);
                FaceIslandExitCapture capture = new FaceIslandExitCapture(bitmap, screen, visible, local, inverse, alpha, outline.radius);
                success = true;
                return capture;
            } finally {
                drawable.setBounds(oldBounds);
                if (oldAlpha >= 0) {
                    drawable.setAlpha(oldAlpha);
                }
            }
        } finally {
            if (!success) {
                bitmap.recycle();
            }
        }
    }

    private static OutlineData readOutline(View content) throws Exception {
        Object clip = optional(content, "getClipToOutline");
        Object providerObject = optional(content, "getOutlineProvider");
        if (!(clip instanceof Boolean) || !((Boolean) clip).booleanValue() || !(providerObject instanceof ViewOutlineProvider)) {
            throw new IllegalStateException("content_outline_unavailable");
        }
        Outline outline = new Outline();
        ((ViewOutlineProvider) providerObject).getOutline(content, outline);
        Rect rect = new Rect();
        if (!outline.getRect(rect) || rect.isEmpty()) {
            throw new IllegalStateException("content_outline_empty");
        }
        float radius = outline.getRadius();
        if (!finite(radius) || radius < 0.0f) {
            throw new IllegalStateException("content_outline_radius_invalid");
        }
        return new OutlineData(new RectF(rect), radius);
    }

    private static Matrix globalMatrix(View view) throws Exception {
        Matrix matrix = new Matrix();
        call(view, "transformMatrixToGlobal", new Class[]{Matrix.class}, matrix);
        return matrix;
    }

    static boolean contains(View ancestor, View child) {
        View current = child;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static boolean finite(float value) {
        return (Float.isNaN(value) || Float.isInfinite(value)) ? false : true;
    }

    private static float visibleAlpha(View view) {
        float alpha = 1.0f;
        View current = view;
        while (current != null) {
            alpha *= current.getAlpha();
            Object transition = optional(current, "getTransitionAlpha");
            if (transition instanceof Number) {
                alpha *= ((Number) transition).floatValue();
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    private static float relativeAlpha(View child, View ancestor) {
        float alpha = 1.0f;
        View current = child;
        while (current != null && current != ancestor) {
            alpha *= current.getAlpha();
            Object transition = optional(current, "getTransitionAlpha");
            if (transition instanceof Number) {
                alpha *= ((Number) transition).floatValue();
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        if (current != ancestor) {
            throw new IllegalStateException("content_not_under_background");
        }
        return Math.max(0.0f, Math.min(1.0f, alpha));
    }

    private static int drawableAlpha(Drawable drawable) {
        Object value = optional(drawable, "getAlpha");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return -1;
    }

    private static boolean screenVisibleRect(View view, Rect rect) {
        if (!view.getGlobalVisibleRect(rect)) {
            return false;
        }
        int[] rootLocation = new int[2];
        view.getRootView().getLocationOnScreen(rootLocation);
        rect.offset(rootLocation[0], rootLocation[1]);
        return true;
    }

    private static int number(Object target, String name) throws Exception {
        Object value = call(target, name);
        if (!(value instanceof Number)) {
            throw new IllegalStateException(name + "_missing");
        }
        return ((Number) value).intValue();
    }

    static Object optional(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            return call(target, name);
        } catch (Exception e) {
            return null;
        }
    }

    static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class[0], new Object[0]);
    }

    static Object call(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(target, arguments);
            } catch (NoSuchMethodException e) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static final class OutlineData {
        final float radius;
        final RectF rect;

        OutlineData(RectF rect, float radius) {
            this.rect = rect;
            this.radius = radius;
        }
    }
}
