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

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: E:\vibecoding\hyperceiler\work\superisland-0.4\classes5.dex */
final class FaceIslandExitHandoff implements ViewTreeObserver.OnPreDrawListener {
    private final View background;
    private boolean committed;
    private final View content;
    private final long cycle;
    private final Object data;
    private final FrozenDrawable frozen;
    private boolean installed;
    private ViewTreeObserver observer;
    private final Drawable originalDrawable;
    private Object overlay;
    private final long token;
    private final ArrayList<Target> targets = new ArrayList<>();
    private final Drawable transparent = new ColorDrawable(0);

    FaceIslandExitHandoff(View background, View content, Object data, long token, long cycle, FaceIslandExitCapture capture) throws Exception {
        this.data = data;
        this.background = background;
        this.content = content;
        this.token = token;
        this.cycle = cycle;
        if (!FaceIslandExitCapture.contains(background, content) || FaceIslandExitCapture.call(content, "getCurrentIslandData") != data) {
            throw new IllegalStateException("handoff_identity_mismatch");
        }
        this.originalDrawable = (Drawable) FaceIslandExitCapture.call(background, "getDrawable");
        this.frozen = new FrozenDrawable(capture);
        this.targets.add(new Target(background, content));
        this.targets.add(new Target(content, content));
        Object fake = FaceIslandExitCapture.optional(content, "getFakeView");
        if ((fake instanceof View) && FaceIslandExitCapture.optional(fake, "getCurrentIslandData") == data) {
            this.targets.add(new Target((View) fake, fake));
        }
    }

    void install() throws Exception {
        this.overlay = FaceIslandExitCapture.call(this.background, "getOverlay");
        FaceIslandExitCapture.call(this.overlay, "add", new Class[]{Drawable.class}, this.frozen);
        this.installed = true;
        this.observer = this.background.getViewTreeObserver();
        if (this.observer.isAlive()) {
            this.observer.addOnPreDrawListener(this);
        }
        enforce();
    }

    void commit() {
        this.committed = true;
        enforce();
        removeOverlay();
    }

    @Override // android.view.ViewTreeObserver.OnPreDrawListener
    public boolean onPreDraw() {
        enforce();
        return true;
    }

    private void enforce() {
        if (this.installed) {
            if (FaceIslandExitCapture.optional(this.content, "getCurrentIslandData") != this.data) {
                removeOverlay();
                restoreOwnedDrawable();
                return;
            }
            try {
                if (!this.committed) {
                    FaceIslandExitCapture.call(this.background, "setDrawable", new Class[]{Drawable.class}, this.transparent);
                }
                Iterator<Target> it = this.targets.iterator();
                while (it.hasNext()) {
                    Target target = it.next();
                    if (FaceIslandExitCapture.optional(target.owner, "getCurrentIslandData") == this.data) {
                        int visibility = (target.view != this.background || this.committed) ? 4 : target.visibility;
                        if (target.view.getVisibility() != visibility) {
                            target.view.setVisibility(visibility);
                        }
                    }
                }
            } catch (Exception e) {
            }
        }
    }

    void release(boolean rollback) {
        if (this.observer != null && this.observer.isAlive()) {
            this.observer.removeOnPreDrawListener(this);
        }
        ViewTreeObserver live = this.background.getViewTreeObserver();
        if (live != this.observer && live.isAlive()) {
            live.removeOnPreDrawListener(this);
        }
        this.observer = null;
        if (this.installed && !rollback) {
            this.committed = true;
            enforce();
        }
        removeOverlay();
        restoreOwnedDrawable();
        if (rollback && this.installed) {
            Iterator<Target> it = this.targets.iterator();
            while (it.hasNext()) {
                Target target = it.next();
                if (FaceIslandExitCapture.optional(target.owner, "getCurrentIslandData") == this.data && target.view.getVisibility() == 4) {
                    target.view.setVisibility(target.visibility);
                }
            }
        }
        this.installed = false;
        this.targets.clear();
    }

    private void restoreOwnedDrawable() {
        try {
            if (FaceIslandExitCapture.optional(this.background, "getDrawable") == this.transparent) {
                FaceIslandExitCapture.call(this.background, "setDrawable", new Class[]{Drawable.class}, this.originalDrawable);
            }
        } catch (Exception e) {
        }
    }

    private void removeOverlay() {
        if (this.overlay == null) {
            return;
        }
        try {
            FaceIslandExitCapture.call(this.overlay, "remove", new Class[]{Drawable.class}, this.frozen);
        } catch (Exception e) {
        }
        this.overlay = null;
    }

    private static final class Target {
        final Object owner;
        final View view;
        final int visibility;

        Target(View view, Object owner) {
            this.view = view;
            this.owner = owner;
            this.visibility = view.getVisibility();
        }
    }

    private static final class FrozenDrawable extends Drawable {
        int alpha = 255;
        final FaceIslandExitCapture capture;
        final FaceIslandExitPainter painter;

        FrozenDrawable(FaceIslandExitCapture capture) {
            this.capture = capture;
            this.painter = new FaceIslandExitPainter(capture);
            setBounds(capture.localRect);
        }

        @Override // android.graphics.drawable.Drawable
        public void draw(Canvas canvas) {
            this.painter.drawFrozen(canvas, this.alpha);
        }

        @Override // android.graphics.drawable.Drawable
        public void setAlpha(int alpha) {
            this.alpha = alpha;
        }

        @Override // android.graphics.drawable.Drawable
        public void setColorFilter(ColorFilter filter) {
        }

        @Override // android.graphics.drawable.Drawable
        public int getOpacity() {
            return -3;
        }
    }
}
