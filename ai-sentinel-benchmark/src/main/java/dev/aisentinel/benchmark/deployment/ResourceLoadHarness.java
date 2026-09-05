package dev.aisentinel.benchmark.deployment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

final class ResourceLoadHarness {

    @FunctionalInterface
    interface Operation {
        boolean invoke(int workerId, long sequence) throws Exception;
    }

    record RunResult(long attempts, long successes, long failures, long measuredWallNanos) {
    }

    private ResourceLoadHarness() {
    }

    static RunResult run(int concurrency,
                         Duration warmupDuration,
                         Duration measurementDuration,
                         Operation operation) throws Exception {
        warmup(concurrency, warmupDuration, operation);
        return measure(concurrency, measurementDuration, operation);
    }

    static void warmup(int concurrency, Duration duration, Operation operation) throws Exception {
        runPhase(concurrency, duration, operation);
    }

    static RunResult measure(int concurrency, Duration duration, Operation operation) throws Exception {
        return runPhase(concurrency, duration, operation);
    }

    private static RunResult runPhase(int concurrency, Duration duration, Operation operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicBoolean stop = new AtomicBoolean(false);
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            long deadline = System.nanoTime() + duration.toNanos();
            long start = System.nanoTime();
            for (int worker = 0; worker < concurrency; worker++) {
                int workerId = worker;
                futures.add(pool.submit(task(workerId, deadline, stop, operation)));
            }
            long attempts = 0L;
            long successes = 0L;
            long failures = 0L;
            for (Future<long[]> future : futures) {
                long[] counts = future.get();
                attempts += counts[0];
                successes += counts[1];
                failures += counts[2];
            }
            return new RunResult(attempts, successes, failures, System.nanoTime() - start);
        } catch (ExecutionException e) {
            stop.set(true);
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("Resource workload failed", cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<long[]> task(int workerId,
                                         long deadlineNanos,
                                         AtomicBoolean stop,
                                         Operation operation) {
        return () -> {
            long sequence = 0L;
            long attempts = 0L;
            long successes = 0L;
            long failures = 0L;
            while (!stop.get() && System.nanoTime() < deadlineNanos) {
                boolean success = operation.invoke(workerId, sequence++);
                attempts++;
                if (success) {
                    successes++;
                } else {
                    failures++;
                }
            }
            return new long[] {attempts, successes, failures};
        };
    }
}
