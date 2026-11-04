package com.sevtinge.hyperceiler.libhook.base;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One batch budget; a slow query must not discard already completed independent results. */
final class HookInitializationBatch {
    record Result<T>(T value, Throwable error) {}

    private HookInitializationBatch() {}

    static <T> List<Result<T>> collect(List<? extends Future<T>> futures, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Result<T>> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            try {
                T value = future.isDone() ? future.get()
                    : future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                results.add(new Result<>(value, null));
            } catch (TimeoutException | CancellationException error) {
                future.cancel(true);
                results.add(new Result<>(null, error));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                results.add(new Result<>(null, error));
            } catch (ExecutionException error) {
                results.add(new Result<>(null, error.getCause() == null ? error : error.getCause()));
            }
        }
        return results;
    }
}
