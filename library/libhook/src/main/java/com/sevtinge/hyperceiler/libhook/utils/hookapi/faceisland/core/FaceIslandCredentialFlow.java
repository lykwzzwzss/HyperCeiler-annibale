package com.sevtinge.hyperceiler.libhook.utils.hookapi.faceisland.core;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class FaceIslandCredentialFlow {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final WeakHashMap<Object, Token> ACTIVE = new WeakHashMap<>();
    private static final WeakHashMap<Object, Token> CALLBACKS = new WeakHashMap<>();
    private static final ThreadLocal<Token> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Completion> COMPLETION = new ThreadLocal<>();

    static final class Token {
        final long cycle;
        final long id = SEQUENCE.incrementAndGet();
        final long round;
        final String source;
        final int user;

        Token(long cycle, long round, int user, String source) {
            this.cycle = cycle;
            this.round = round;
            this.user = user;
            this.source = source;
        }
    }

    private static final class Completion {
        final Token token;

        Completion(Token token) {
            this.token = token;
        }
    }

    private FaceIslandCredentialFlow() {
    }

    static Object enterRequest(Object token) {
        Token previous = REQUEST.get();
        REQUEST.set(token instanceof Token ? (Token) token : null);
        return previous;
    }

    static void leaveRequest(Object previous) {
        if (previous instanceof Token) {
            REQUEST.set((Token) previous);
        } else {
            REQUEST.remove();
        }
    }

    static void requested(Object mediator, int user) {
        synchronized (FaceIslandCredentialFlow.class) {
            pending(mediator, user, REQUEST.get(), null);
        }
    }

    static void pending(Object mediator, int user, Object candidate, Object callback) {
        synchronized (FaceIslandCredentialFlow.class) {
            Token token = candidate instanceof Token ? (Token) candidate : null;
            if (token == null || token.user != user) {
                ACTIVE.remove(mediator);
                token = null;
            } else {
                ACTIVE.put(mediator, token);
            }
            if (callback != null) {
                CALLBACKS.put(callback, token);
            }
        }
    }

    static void bindCallback(Object callback, Object mediator, int user) {
        synchronized (FaceIslandCredentialFlow.class) {
            Token token = ACTIVE.get(mediator);
            CALLBACKS.put(callback, (token == null || token.user != user) ? null : token);
        }
    }

    static Object enterCallback(Object callback) {
        Completion previous;
        synchronized (FaceIslandCredentialFlow.class) {
            previous = COMPLETION.get();
            COMPLETION.set(new Completion(CALLBACKS.get(callback)));
        }
        return previous;
    }

    static void leaveCallback(Object previous) {
        if (previous instanceof Completion) {
            COMPLETION.set((Completion) previous);
        } else {
            COMPLETION.remove();
        }
    }

    static Token consume(Object mediator) {
        synchronized (FaceIslandCredentialFlow.class) {
            Token active = ACTIVE.get(mediator);
            Completion completion = COMPLETION.get();
            if (completion != null && completion.token != active) {
                return null;
            }
            ACTIVE.remove(mediator);
            return active;
        }
    }

    static Token active(Object mediator) {
        Token token;
        synchronized (FaceIslandCredentialFlow.class) {
            token = ACTIVE.get(mediator);
        }
        return token;
    }

    static void cancel(Object mediator) {
        synchronized (FaceIslandCredentialFlow.class) {
            ACTIVE.remove(mediator);
        }
    }

    static void clear() {
        synchronized (FaceIslandCredentialFlow.class) {
            ACTIVE.clear();
        }
    }
}
