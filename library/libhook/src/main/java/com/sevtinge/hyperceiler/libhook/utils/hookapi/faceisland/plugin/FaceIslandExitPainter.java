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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes5.dex */
final class FaceIslandExitPainter {
    private final FaceIslandExitCapture capture;
    private final Paint paint = new Paint(3);
    private final Path clip = new Path();

    FaceIslandExitPainter(FaceIslandExitCapture capture) {
        this.capture = capture;
    }

    void drawFrozen(Canvas canvas, int alpha) {
        int save = canvas.save();
        canvas.concat(this.capture.screenToLocal);
        drawAt(canvas, this.capture.screenRect.left, this.capture.screenRect.top, 0.0f, alpha);
        canvas.restoreToCount(save);
    }

    void drawWindow(Canvas canvas, float progress, int alpha) {
        drawAt(canvas, 0.0f, 0.0f, progress, alpha);
    }

    private void drawAt(Canvas canvas, float left, float top2, float progress, int alpha) {
        Bitmap bitmap = this.capture.bitmap;
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        this.paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        float width = bitmap.getWidth();
        float height = bitmap.getHeight();
        if (progress <= 0.0f) {
            canvas.drawBitmap(bitmap, left, top2, this.paint);
            return;
        }
        float collapse = Math.min(1.0f, progress / 0.72f);
        float clipWidth = ((Math.min(width, height) - width) * collapse) + width;
        float clipLeft = ((width - clipWidth) / 2.0f) + left;
        float radius = this.capture.cornerRadius + (((Math.min(height, clipWidth) / 2.0f) - this.capture.cornerRadius) * collapse);
        this.clip.reset();
        this.clip.addRoundRect(new RectF(clipLeft, top2, clipLeft + clipWidth, top2 + height), Math.max(0.0f, radius), Math.max(0.0f, radius), Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(this.clip);
        canvas.drawBitmap(bitmap, left, top2, this.paint);
        canvas.restoreToCount(save);
    }
}
