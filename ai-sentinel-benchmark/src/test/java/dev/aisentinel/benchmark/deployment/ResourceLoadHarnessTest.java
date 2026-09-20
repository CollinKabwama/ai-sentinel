package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceLoadHarnessTest {

    @Test
    void measuredWorkersStartFromSharedGate() throws Exception {
        int concurrency = 4;
        AtomicBoolean firstRound = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(concurrency);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        ResourceLoadHarness.RunResult result = ResourceLoadHarness.measure(
            concurrency,
            Duration.ofMillis(50),
            (workerId, sequence) -> {
                if (firstRound.get()) {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    entered.countDown();
                    if (!entered.await(2, TimeUnit.SECONDS)) {
                        failures.add(new AssertionError("workers did not overlap"));
                    }
                    active.decrementAndGet();
                    if (entered.getCount() == 0L) {
                        firstRound.set(false);
                    }
                }
                return true;
            });

        assertThat(failures).isEmpty();
        assertThat(maxActive.get()).isEqualTo(concurrency);
        assertThat(result.attempts()).isGreaterThanOrEqualTo(concurrency);
        assertThat(result.failures()).isZero();
    }
}
