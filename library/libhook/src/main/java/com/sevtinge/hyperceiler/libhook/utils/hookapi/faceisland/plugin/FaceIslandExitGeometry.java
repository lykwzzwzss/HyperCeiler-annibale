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

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes5.dex */
public final class FaceIslandExitGeometry {
    private FaceIslandExitGeometry() {
    }

    public static int[] drawableBounds(int left, int top2, int right, int bottom, int stroke, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || stroke < 0 || right <= left || bottom <= top2) {
            return new int[0];
        }
        int l = (int) Math.max(0L, left - stroke);
        int t = (int) Math.max(0L, top2 - stroke);
        int r = (int) Math.min(viewWidth, right + stroke);
        int b = (int) Math.min(viewHeight, bottom + stroke);
        return (r <= l || b <= t) ? new int[0] : new int[]{l, t, r, b};
    }
}
