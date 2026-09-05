package dev.aisentinel.benchmark.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class LoadHarness {

    @FunctionalInterface
    interface Operation {
        OperationResult invoke(int workerId, int opIndex) throws Exception;
    }

    record OperationResult(
        boolean success,
        boolean timeout,
        boolean fallback,
        boolean failOpen,
        boolean unexpectedEnforcement,
        boolean transportFailure,
        boolean malformedResponse,
        long latencyNanos
    ) {
    }

    record RunResult(
        long attempts,
        long successes,
        long failures,
        long timeouts,
        long fallbacks,
        long failOpenCount,
        long unexpectedEnforcementCount,
        long transportFailures,
        long malformedResponses,
        List<Long> successLatenciesNanos,
        List<Long> failureLatenciesNanos,
        long measuredElapsedNanos
    ) {
    }

    private LoadHarness() {
    }

    static RunResult run(int concurrency,
                         int warmupAttemptsPerThread,
                         int measuredAttemptsPerThread,
                         Runnable beforeWarmup,
                         Runnable beforeMeasured,
                         Operation operation) throws Exception {
        Objects.requireNonNull(beforeWarmup, "beforeWarmup");
        Objects.requireNonNull(beforeMeasured, "beforeMeasured");
        Objects.requireNonNull(operation, "operation");
        beforeWarmup.run();
        execute(concurrency, warmupAttemptsPerThread, operation);
        beforeMeasured.run();
        return execute(concurrency, measuredAttemptsPerThread, operation);
    }

    private static RunResult execute(int concurrency, int attemptsPerThread, Operation operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<RunResult>> futures = new ArrayList<>();
            CountDownLatch ready = new CountDownLatch(concurrency);
            CountDownLatch startGate = new CountDownLatch(1);
            for (int worker = 0; worker < concurrency; worker++) {
                final int workerId = worker;
                futures.add(pool.submit(task(workerId, attemptsPerThread, operation, ready, startGate)));
            }
            ready.await();
            long start = System.nanoTime();
            startGate.countDown();
            long attempts = 0L;
            long successes = 0L;
            long failures = 0L;
            long timeouts = 0L;
            long fallbacks = 0L;
            long failOpenCount = 0L;
            long unexpectedEnforcementCount = 0L;
            long transportFailures = 0L;
            long malformedResponses = 0L;
            List<Long> successLatencies = new ArrayList<>(concurrency * attemptsPerThread);
            List<Long> failureLatencies = new ArrayList<>();
            for (Future<RunResult> future : futures) {
                RunResult partial = future.get();
                attempts += partial.attempts;
                successes += partial.successes;
                failures += partial.failures;
                timeouts += partial.timeouts;
                fallbacks += partial.fallbacks;
                failOpenCount += partial.failOpenCount;
                unexpectedEnforcementCount += partial.unexpectedEnforcementCount;
                transportFailures += partial.transportFailures;
                malformedResponses += partial.malformedResponses;
                successLatencies.addAll(partial.successLatenciesNanos);
                failureLatencies.addAll(partial.failureLatenciesNanos);
            }
            long elapsed = System.nanoTime() - start;
            return new RunResult(
                attempts,
                successes,
                failures,
                timeouts,
                fallbacks,
                failOpenCount,
                unexpectedEnforcementCount,
                transportFailures,
                malformedResponses,
                successLatencies,
                failureLatencies,
                elapsed);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new IllegalStateException("Benchmark worker failed", cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<RunResult> task(int workerId, int attemptsPerThread, Operation operation,
                                            CountDownLatch ready, CountDownLatch startGate) {
        return () -> {
            ready.countDown();
            startGate.await();
            long attempts = 0L;
            long successes = 0L;
            long failures = 0L;
            long timeouts = 0L;
            long fallbacks = 0L;
            long failOpenCount = 0L;
            long unexpectedEnforcementCount = 0L;
            long transportFailures = 0L;
            long malformedResponses = 0L;
            List<Long> successLatencies = new ArrayList<>(attemptsPerThread);
            List<Long> failureLatencies = new ArrayList<>();
            for (int op = 0; op < attemptsPerThread; op++) {
                OperationResult result = operation.invoke(workerId, op);
                attempts++;
                if (result.success) {
                    successes++;
                    successLatencies.add(result.latencyNanos);
                } else {
                    failures++;
                    failureLatencies.add(result.latencyNanos);
                }
                if (result.timeout) {
                    timeouts++;
                }
                if (result.fallback) {
                    fallbacks++;
                }
                if (result.failOpen) {
                    failOpenCount++;
                }
                if (result.unexpectedEnforcement) {
                    unexpectedEnforcementCount++;
                }
                if (result.transportFailure) {
                    transportFailures++;
                }
                if (result.malformedResponse) {
                    malformedResponses++;
                }
            }
            return new RunResult(
                attempts,
                successes,
                failures,
                timeouts,
                fallbacks,
                failOpenCount,
                unexpectedEnforcementCount,
                transportFailures,
                malformedResponses,
                successLatencies,
                failureLatencies,
                0L);
        };
    }
}
