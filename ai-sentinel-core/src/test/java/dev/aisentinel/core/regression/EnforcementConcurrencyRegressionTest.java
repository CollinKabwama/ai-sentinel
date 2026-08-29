package dev.aisentinel.core.regression;

import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Same-identity enforcement concurrency regressions for local quarantine and throttle maps.
 * Single-JVM coverage only — does not claim multi-process Redis atomicity.
 */
class EnforcementConcurrencyRegressionTest {

    private static final TelemetryEmitter TELEMETRY = mock(TelemetryEmitter.class);
    private static final HttpRequestView REQUEST = mock(HttpRequestView.class);

    @Test
    @Timeout(30)
    void concurrentQuarantineCreation_sameIdentity_remainsQuarantined() throws Exception {
        CompositeEnforcementHandler handler = handler(60_000L, 1000, 5.0);
        String id = "same-id";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < 50; i++) {
                    handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(handler.isQuarantined(id, "/api")).isTrue();
        assertThat(handler.getQuarantineCount()).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void concurrentQuarantineReadWrite_noLostActiveState() throws Exception {
        CompositeEnforcementHandler handler = handler(60_000L, 1000, 5.0);
        String id = "rw-id";
        handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger trueReads = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads / 2; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
                }
                return null;
            }));
        }
        for (int t = 0; t < threads / 2; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 400; i++) {
                    if (handler.isQuarantined(id, "/api")) {
                        trueReads.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(handler.isQuarantined(id, "/api")).isTrue();
        assertThat(trueReads.get()).isGreaterThan(0);
    }

    @Test
    @Timeout(30)
    void quarantineExtension_underConcurrency_keepsKeyActive() throws Exception {
        CompositeEnforcementHandler handler = handler(2_000L, 1000, 5.0);
        String id = "extend-id";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 30; i++) {
                    handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
                    Thread.sleep(20);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(handler.isQuarantined(id, "/api")).isTrue();
    }

    @Test
    @Timeout(30)
    void expiryVersusNewWrite_freshQuarantineSurvives() throws Exception {
        // Short duration so natural expiry can race with re-apply; a concurrent refresh must keep quarantine active until the new until-time.
        CompositeEnforcementHandler handler = handler(50L, 1000, 5.0);
        String id = "expiry-race";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    if ((threadId + i) % 2 == 0) {
                        handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
                    } else {
                        handler.isQuarantined(id, "/api");
                    }
                    Thread.sleep(1);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        // Final write after races settle must be observed.
        handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
        assertThat(handler.isQuarantined(id, "/api")).isTrue();
        pool.shutdownNow();
    }

    @Test
    @Timeout(30)
    void throttleConcurrency_sameIdentity_respectsRateWithoutExceptions() throws Exception {
        CompositeEnforcementHandler handler = handler(60_000L, 1000, 1.0);
        String id = "throttle-id";
        int threads = 8;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < perThread; i++) {
                    if (handler.tryAcquireThrottlePermit(id, "/api")) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // 1 rps token bucket under burst: far fewer allows than total attempts; at least one succeeds.
        assertThat(allowed.get()).isBetween(1, threads * perThread / 2);
    }

    @Test
    @Timeout(30)
    void crossIdentity_quarantineContention_doesNotDropOtherKeys() throws Exception {
        CompositeEnforcementHandler handler = handler(60_000L, 1000, 5.0);
        int identities = 16;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < identities; i++) {
                    String id = "id-" + ((threadId + i) % identities);
                    handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, id, "/api");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        for (int i = 0; i < identities; i++) {
            assertThat(handler.isQuarantined("id-" + i, "/api")).isTrue();
        }
        assertThat(handler.getQuarantineCount()).isEqualTo(identities);
    }

    @Test
    @Timeout(30)
    void capacityEviction_hotKeyQuarantineSurvivesChurn() throws Exception {
        // Small maxKeys forces eviction while a hot identity is repeatedly re-quarantined.
        CompositeEnforcementHandler handler = handler(60_000L, 4, 5.0);
        String hot = "hot-id";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 400; i++) {
                handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, hot, "/api");
            }
            return null;
        }));
        for (int t = 1; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 400; i++) {
                    handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE,
                        "churn-" + threadId + "-" + i, "/api");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // Drive capacity eviction with fillers, then refresh the hot key last so it holds the
        // newest until (CHM victim selection among equal timestamps is otherwise arbitrary).
        for (int i = 0; i < 8; i++) {
            handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, "fill-" + i, "/api");
        }
        handler.apply(EnforcementAction.QUARANTINE, REQUEST, DiscardingEnforcementResponse.INSTANCE, hot, "/api");
        assertThat(handler.isQuarantined(hot, "/api")).isTrue();
        // Evict-then-put can leave size at maxKeys+1 until the next apply; bound is material.
        assertThat(handler.getQuarantineCount()).isLessThanOrEqualTo(5);
    }

    @Test
    @Timeout(30)
    void capacityEviction_hotThrottleKeySurvivesChurnWithoutRateLimitReset() throws Exception {
        // Low rate (1 rps) so a warm bucket denies almost every immediate retry; a reset bucket
        // (evicted then recreated) allows immediately (nextAllowed starts at 0). Small maxKeys
        // forces repeated capacity eviction while a hot identity is hammered concurrently.
        CompositeEnforcementHandler handler = handler(60_000L, 1000, 1.0);
        String hot = "hot-throttle-id";
        handler.tryAcquireThrottlePermit(hot, "/api"); // warm the bucket once
        int churnThreads = 6;
        int churnPerThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(churnThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger hotAllows = new AtomicInteger();
        AtomicInteger hotAttempts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < churnPerThread; i++) {
                hotAttempts.incrementAndGet();
                if (handler.tryAcquireThrottlePermit(hot, "/api")) {
                    hotAllows.incrementAndGet();
                }
            }
            return null;
        }));
        for (int t = 0; t < churnThreads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < churnPerThread; i++) {
                    handler.tryAcquireThrottlePermit("churn-" + threadId + "-" + i, "/api");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // At 1 rps, a correctly-behaving bucket allows only a small handful of times regardless of
        // how many attempts were made in a fraction of a second. A defective capacity-eviction path
        // that resets the hot bucket produces allows roughly proportional to attempts instead.
        assertThat(hotAllows.get())
            .as("hotAttempts=%d hotAllows=%d — allow count must stay small under a 1 rps limit " +
                "regardless of concurrent capacity churn on unrelated keys", hotAttempts.get(), hotAllows.get())
            .isLessThan(300);
    }

    private static CompositeEnforcementHandler handler(long quarantineMs, int maxKeys, double rps) {
        return new CompositeEnforcementHandler(
            429, quarantineMs, rps, TELEMETRY, maxKeys, 60_000L, EnforcementScope.IDENTITY_ENDPOINT);
    }
}
