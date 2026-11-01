package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class FaceIslandWakeBuffer<T> {
    public static final long MAX_WAIT_MS = 2000;
    private long boundary;
    private final ArrayList<Event> events = new ArrayList<>();
    private Object repository;
    private T request;
    private long showRequest;
    private long startedAt;
    private int user;

    public static final class Event {
        public final long at;
        public final int code;
        public final String detail;
        public final boolean flag;
        public final String name;
        public final Object owner;
        public final int user;

        public Event(String name, Object owner, int user, int code, boolean flag, String detail, long at) {
            this.name = name;
            this.owner = owner;
            this.user = user;
            this.code = code;
            this.flag = flag;
            this.detail = detail;
            this.at = at;
        }
    }

    public void start(T next, Object repo, int selectedUser, long epoch, long lockRequest, long at, Event event) {
        clear();
        if (next == null || repo == null || selectedUser < 0 || lockRequest < 0) {
            return;
        }
        this.request = next;
        this.repository = repo;
        this.user = selectedUser;
        this.boundary = epoch;
        this.showRequest = lockRequest;
        this.startedAt = at;
        this.events.add(event);
    }

    public boolean append(T expected, Event event) {
        if (this.request == null || expected != this.request || event.at < this.startedAt
            || event.at - this.startedAt > MAX_WAIT_MS || this.events.size() >= 8) {
            return false;
        }
        this.events.add(event);
        return true;
    }

    public boolean appendExit(Event event, long epoch, long lockRequest) {
        return this.boundary == epoch && this.showRequest == lockRequest
            && ("HIDE".equals(event.name) || "HIDE_ANIMATION_FINISHED".equals(event.name))
            && append(this.request, event);
    }

    private boolean valid(Object repo, int selectedUser, long epoch, long lockRequest, long now) {
        boolean result = this.request != null && this.repository == repo && this.user == selectedUser
            && this.boundary == epoch && this.showRequest == lockRequest && now >= this.startedAt
            && now - this.startedAt <= MAX_WAIT_MS;
        if (!result) {
            clear();
        }
        return result;
    }

    public boolean hasAccepted(Object repo, int selectedUser, long epoch, long lockRequest, long now) {
        if (!valid(repo, selectedUser, epoch, lockRequest, now)) {
            return false;
        }
        long producedAt = -1;
        Iterator<Event> it = this.events.iterator();
        while (it.hasNext()) {
            Event event = it.next();
            if (event.user == this.user) {
                if ("FACE_PRODUCED".equals(event.name)) {
                    producedAt = event.at;
                }
                if ("FACE_ACCEPTED".equals(event.name) && producedAt >= 0 && event.at >= producedAt) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Event> take(Object repo, int selectedUser, long epoch, long lockRequest, long now) {
        if (!valid(repo, selectedUser, epoch, lockRequest, now)) {
            return Collections.emptyList();
        }
        List<Event> result = new ArrayList<>(this.events);
        clear();
        return result;
    }

    public void clear() {
        this.request = null;
        this.repository = null;
        this.events.clear();
    }
}
