package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次人脸认证请求的上下文。
 *
 * <p>补丁原本继承 {@code FaceManager.AuthenticationCallback} 做包装；该回调是 @hide 抽象类，
 * 模块无法编译。这里改为记录原回调对象，并在 {@link FaceIslandHooks#wrapFaceCallback} 里
 * 直接 hook 原回调类的 5 个方法，语义等价且不需要隐藏类。</p>
 */
final class FaceIslandAuthRequest {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ThreadLocal<FaceIslandAuthRequest> CALLBACK = new ThreadLocal<>();
    private static final ThreadLocal<FaceIslandAuthRequest> ACCEPTING = new ThreadLocal<>();
    private static final ThreadLocal<java.util.ArrayList<FaceIslandAuthRequest>> STACK =
        ThreadLocal.withInitial(java.util.ArrayList::new);
    private static final FaceIslandResultRegistry<FaceIslandAuthRequest> RESULTS = new FaceIslandResultRegistry<>();
    private static final Map<Object, FaceIslandAuthRequest> REGISTRY =
        Collections.synchronizedMap(new WeakHashMap<>());

    final Object repository;
    final int user;
    final long id = SEQUENCE.incrementAndGet();
    final long startedAt = SystemClock.elapsedRealtime();
    final long screenBoundary = FaceIslandController.get().screenBoundary();
    final long showRequest = FaceIslandController.get().showRequestIdentity();
    private final WeakReference<Object> callback;

    private FaceIslandAuthRequest(Object callback, int user) throws Exception {
        this.callback = new WeakReference<>(callback);
        this.repository = FaceIslandReflect.field(callback, "this$0");
        this.user = user;
    }

    static FaceIslandAuthRequest wrap(Object callback, int user) throws Exception {
        FaceIslandAuthRequest request = new FaceIslandAuthRequest(callback, user);
        REGISTRY.put(callback, request);
        return request;
    }

    static FaceIslandAuthRequest forCallback(Object callback) {
        return callback == null ? null : REGISTRY.get(callback);
    }

    static FaceIslandAuthRequest currentCallback() {
        return CALLBACK.get();
    }

    static FaceIslandAuthRequest accepting() {
        return ACCEPTING.get();
    }

    static void enter(Object callback) {
        FaceIslandAuthRequest request = forCallback(callback);
        STACK.get().add(CALLBACK.get());
        if (request == null) {
            CALLBACK.remove();
        } else {
            CALLBACK.set(request);
        }
    }

    static void leave() {
        java.util.ArrayList<FaceIslandAuthRequest> stack = STACK.get();
        FaceIslandAuthRequest previous = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
        if (previous == null) {
            CALLBACK.remove();
        } else {
            CALLBACK.set(previous);
        }
    }

    static void remember(Object result, FaceIslandAuthRequest request) {
        synchronized (FaceIslandAuthRequest.class) {
            RESULTS.remember(result, request);
        }
    }

    static Object enterAcceptance(Object status) throws Exception {
        FaceIslandAuthRequest previous;
        synchronized (FaceIslandAuthRequest.class) {
            previous = ACCEPTING.get();
            Object result = FaceIslandReflect.field(status, "successResult");
            FaceIslandAuthRequest request = RESULTS.find(result);
            ACCEPTING.set(request);
        }
        return previous;
    }

    static void leaveAcceptance(Object previous) {
        if (!(previous instanceof FaceIslandAuthRequest)) {
            ACCEPTING.remove();
        } else {
            ACCEPTING.set((FaceIslandAuthRequest) previous);
        }
    }
}
