package com.sevtinge.hyperceiler.libhook.base;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;

public class HookInitializationBatchTest {
    @Test public void timeoutPreservesIndependentCompletedQueries() {
        FutureTask<String> stalled = new FutureTask<>(() -> "never started");
        var results = HookInitializationBatch.collect(List.of(stalled,
            CompletableFuture.completedFuture("face"), CompletableFuture.completedFuture("volume")),
            0, TimeUnit.MILLISECONDS);
        assertTrue(stalled.isCancelled());
        assertTrue(results.get(0).error() instanceof TimeoutException);
        assertEquals("face", results.get(1).value());
        assertEquals("volume", results.get(2).value());
        assertNull(results.get(2).error());
    }

    @Test public void failureAndCancellationDoNotAbortTheBatch() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("missing interface"));
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        var results = HookInitializationBatch.collect(List.of(failed, cancelled,
            CompletableFuture.completedFuture("working")), 1, TimeUnit.SECONDS);
        assertTrue(results.get(0).error() instanceof IllegalStateException);
        assertNotNull(results.get(1).error());
        assertEquals("working", results.get(2).value());
    }
}
