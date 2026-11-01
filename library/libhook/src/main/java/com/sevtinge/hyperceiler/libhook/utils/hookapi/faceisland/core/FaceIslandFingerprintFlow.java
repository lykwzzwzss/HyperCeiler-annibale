package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

public final class FaceIslandFingerprintFlow {
    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    public static final long MAX_WAIT_MS = 2000;
    private static Token candidate;
    private static long serial;

    public static final class Token {
        public long acceptedAt;
        public final long at;
        public final long boundary;
        private boolean consumed;
        public final long id;
        public final Object monitor;
        public final long showRequest;
        public final boolean strong;
        public final int user;

        private Token(Object owner, int resultUser, boolean isStrong, long epoch, long lock, long now) {
            this.acceptedAt = -1L;
            long j = serial + 1;
            serial = j;
            this.id = j;
            this.monitor = owner;
            this.user = resultUser;
            this.strong = isStrong;
            this.boundary = epoch;
            this.showRequest = lock;
            this.at = now;
        }
    }

    private FaceIslandFingerprintFlow() {
    }

    public static Token current() {
        return CURRENT.get();
    }

    public static synchronized Token produced(Object monitor, int user, boolean strong, long boundary, long showRequest, long now) {
        Token token;
        Token token2;
        synchronized (FaceIslandFingerprintFlow.class) {
            if (monitor == null || user < 0 || showRequest < 0) {
                token = null;
            } else {
                token = new Token(monitor, user, strong, boundary, showRequest, now);
            }
            candidate = token;
            token2 = candidate;
        }
        return token2;
    }

    public static synchronized Object enter(Object monitor, int user, boolean strong, boolean fingerprint, long boundary, long showRequest, long now) {
        Token previous;
        synchronized (FaceIslandFingerprintFlow.class) {
            previous = CURRENT.get();
            Token next = candidate;
            CURRENT.set((fingerprint && valid(next, monitor, user, boundary, showRequest, now)
                && next.strong == strong && next.acceptedAt < 0) ? next : null);
        }
        return previous;
    }

    public static synchronized void leave(Object previous, boolean pending) {
        synchronized (FaceIslandFingerprintFlow.class) {
            Token current = CURRENT.get();
            if (!pending && current != null && candidate == current) {
                candidate = null;
            }
            if (previous instanceof Token) {
                CURRENT.set((Token) previous);
            } else {
                CURRENT.remove();
            }
        }
    }

    public static synchronized Token accept(Object monitor, int mode, long boundary, long showRequest, long now) {
        synchronized (FaceIslandFingerprintFlow.class) {
            Token token = CURRENT.get();
            if (token != null && token == candidate) {
                if (token.acceptedAt < 0) {
                    if (unlockMode(mode) && valid(token, monitor, token.user, boundary, showRequest, now)) {
                        token.acceptedAt = now;
                        candidate = null;
                        return token;
                    }
                    candidate = null;
                    CURRENT.remove();
                    return null;
                }
            }
            return null;
        }
    }

    public static synchronized Token acceptAuthenticated(Object monitor, int user, boolean strong, long boundary, long showRequest, long now) {
        synchronized (FaceIslandFingerprintFlow.class) {
            Token existing = CURRENT.get();
            if (existing == null) {
                existing = candidate;
            }
            if (existing != null && existing.acceptedAt >= 0) {
                // 幂等：同一次认证只下发一次 FINGERPRINT_DIRECT_ACCEPTED。
                return null;
            }
            Token token = existing;
            if (token == null || token.strong != strong
                || !valid(token, monitor, user, boundary, showRequest, now)) {
                if (CURRENT.get() == existing) {
                    CURRENT.remove();
                }
                if (candidate == existing) {
                    candidate = null;
                }
                token = (monitor == null || user < 0)
                    ? null : new Token(monitor, user, strong, boundary, showRequest, now);
            }
            if (token == null) {
                return null;
            }
            // 直接接受路径必须把 token 交回调用方，否则 FINGERPRINT_DIRECT_ACCEPTED 永远发不出去。
            token.acceptedAt = now;
            candidate = token;
            return token;
        }
    }

    public static synchronized boolean valid(Token token, Object monitor, int user, long boundary, long showRequest, long now) {
        boolean z = false;
        synchronized (FaceIslandFingerprintFlow.class) {
            if (token != null) {
                if (!token.consumed && token.monitor == monitor && token.user == user && token.boundary == boundary
                    && token.showRequest == showRequest && now >= token.at) {
                    z = now - token.at <= MAX_WAIT_MS;
                }
            }
        }
        return z;
    }

    public static synchronized boolean consume(Token token, Object monitor, int user, long boundary, long showRequest, long now) {
        synchronized (FaceIslandFingerprintFlow.class) {
            if (token != null) {
                if (token.acceptedAt >= 0 && valid(token, monitor, user, boundary, showRequest, now)) {
                    token.consumed = true;
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean unlockMode(int mode) {
        return mode == 1 || mode == 2 || mode == 5 || mode == 6 || mode == 7;
    }
}
