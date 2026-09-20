package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoadHarnessTest {

    @Test
    void warmupAttemptsAreExcludedFromMeasuredCountersAndLatencies() throws Exception {
        AtomicInteger invocations = new AtomicInteger();

        LoadHarness.RunResult result = LoadHarness.run(
            2,
            3,
            5,
            () -> { },
            () -> { },
            (workerId, opIndex) -> {
                int invocation = invocations.incrementAndGet();
                boolean measured = invocation > 6;
                return new LoadHarness.OperationResult(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    measured ? 2_000_000L : 99_000_000L);
            });

        assertThat(result.attempts()).isEqualTo(10);
        assertThat(result.successes()).isEqualTo(10);
        assertThat(result.successLatenciesNanos()).containsOnly(2_000_000L);
        assertThat(invocations).hasValue(16);
    }

    @Test
    void concurrentWorkersStartFromSharedGate() throws Exception {
        int concurrency = 4;
        CountDownLatch enteredOperation = new CountDownLatch(concurrency);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        LoadHarness.RunResult result = LoadHarness.run(
            concurrency,
            0,
            1,
            () -> { },
            () -> { },
            (workerId, opIndex) -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                enteredOperation.countDown();
                if (!enteredOperation.await(2, TimeUnit.SECONDS)) {
                    failures.add(new AssertionError("workers did not overlap"));
                }
                active.decrementAndGet();
                return new LoadHarness.OperationResult(true, false, false, false, false, false, false, 1_000_000L);
            });

        assertThat(failures).isEmpty();
        assertThat(result.attempts()).isEqualTo(concurrency);
        assertThat(maxActive.get()).isEqualTo(concurrency);
    }
}
