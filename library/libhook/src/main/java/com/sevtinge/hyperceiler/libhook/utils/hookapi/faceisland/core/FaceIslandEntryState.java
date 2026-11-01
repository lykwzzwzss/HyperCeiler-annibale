package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

final class FaceIslandEntryState {
    private static final long FIRST_REGISTRATION_MS = 2000;
    private static final long MISSING_GRACE_MS = 1200;
    private boolean acknowledged;
    private long publishedAt = -1;
    private long missingSince = -1;

    FaceIslandEntryState() {
    }

    enum Decision {
        INITIAL(true),
        PLUGIN_CHANGED(true),
        TEMPLATE_REMOVED(true),
        REGISTRATION_LOST(true),
        FIRST_REGISTRATION_TIMEOUT(true),
        REGISTERED(false),
        PENDING(false),
        WAIT_FIRST_REGISTRATION(false),
        WAIT_MISSING(false);

        final boolean publish;

        Decision(boolean publish) {
            this.publish = publish;
        }
    }

    Decision decide(boolean hasPublished, boolean samePlugin, boolean invalidated, boolean registered, boolean pending, long now) {
        if (!hasPublished) {
            return Decision.INITIAL;
        }
        if (!samePlugin) {
            return Decision.PLUGIN_CHANGED;
        }
        if (invalidated) {
            return Decision.TEMPLATE_REMOVED;
        }
        if (registered || pending) {
            this.acknowledged = true;
            this.missingSince = -1L;
            return registered ? Decision.REGISTERED : Decision.PENDING;
        }
        if (!this.acknowledged) {
            return (this.publishedAt < 0 || now - this.publishedAt < FIRST_REGISTRATION_MS)
                ? Decision.WAIT_FIRST_REGISTRATION : Decision.FIRST_REGISTRATION_TIMEOUT;
        }
        if (this.missingSince < 0) {
            this.missingSince = now;
        }
        return now - this.missingSince >= MISSING_GRACE_MS ? Decision.REGISTRATION_LOST : Decision.WAIT_MISSING;
    }

    void published(long now) {
        this.publishedAt = now;
        this.missingSince = -1L;
        this.acknowledged = false;
    }

    void clear() {
        this.missingSince = -1L;
        this.publishedAt = -1L;
        this.acknowledged = false;
    }
}
