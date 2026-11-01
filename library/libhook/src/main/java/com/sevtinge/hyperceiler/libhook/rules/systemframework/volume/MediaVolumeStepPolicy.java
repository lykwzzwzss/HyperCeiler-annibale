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
package com.sevtinge.hyperceiler.libhook.rules.systemframework.volume;

/** A grid in native visible volume indices; never changes the stream's native maximum. */
public final class MediaVolumeStepPolicy {
    private MediaVolumeStepPolicy() {}

    public static boolean applies(int stream, int direction, int flags,
                                  boolean explicitDevice, String callingPackage) {
        return stream == 3 && (direction == 1 || direction == -1)
            && (flags & 0x40) == 0 // FLAG_BLUETOOTH_ABS_VOLUME: incoming remote synchronization.
            && !explicitDevice && !"com.android.bluetooth".equals(callingPackage);
    }

    public static int distance(int current, int min, int max, int steps, int direction) {
        long range = (long) max - min;
        if (range <= 0 || steps <= 0 || (direction != 1 && direction != -1)) return 0;
        int divisions = (int) Math.min((long) steps, range);
        long bounded = Math.max((long) min, Math.min((long) max, current));
        int low = 0, high = divisions;
        // Find the nearest rounded grid point, then move one point in the requested
        // direction. Snapping from an arbitrary slider value must not create a tiny
        // first adjustment; Bluetooth's one-index rounding must not skip a grid point.
        while (low <= high) {
            int middle = (low + high) >>> 1;
            long point = min + (range * middle + divisions / 2) / divisions;
            if (point < bounded) low = middle + 1;
            else high = middle - 1;
        }
        int upper = Math.min(divisions, low);
        int lower = Math.max(0, upper - 1);
        long upperPoint = min + (range * upper + divisions / 2) / divisions;
        long lowerPoint = min + (range * lower + divisions / 2) / divisions;
        int nearest = bounded - lowerPoint <= upperPoint - bounded ? lower : upper;
        int slot = Math.max(0, Math.min(divisions, nearest + direction));
        long point = min + (range * slot + divisions / 2) / divisions;
        return (int) Math.min(Integer.MAX_VALUE, Math.abs(point - bounded));
    }
}
