package dev.aisentinel.core.regression;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency and bounded-state regressions for shared detector state:
 * BaselineStore capacity eviction, per-key bucket-window counting, and CompositeScorer registration.
 */
class ConcurrencyStateSafetyRegressionTest {

    private static final RequestFeatures FEATURES = RequestFeatures.builder()
        .identityHash("id")
        .endpoint("/api")
        .timestampMillis(0)
        .requestsPerWindow(1)
        .endpointEntropy(0)
        .endpointConcentration(1)
        .tokenAgeSeconds(60)
        .parameterCount(0)
        .payloadSizeBytes(0)
        .headerFingerprintHash(0)
        .ipBucket(0)
        .build();

    @Test
    void baselineStore_capacityEviction_staysBoundedAndDeterministic() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 3, now::get);

        for (int i = 0; i < 3; i++) {
            now.addAndGet(1);
            store.incrementAndGet("keep-" + i);
        }
        assertThat(store.size()).isEqualTo(3);

        // Older keys first; newest inserts should force eviction of oldest lastAccess.
        now.addAndGet(10);
        store.incrementAndGet("new-a");
        now.addAndGet(1);
        store.incrementAndGet("new-b");

        assertThat(store.size()).isLessThanOrEqualTo(3);
        assertThat(store.get("new-a")).isPositive();
        assertThat(store.get("new-b")).isPositive();
        // At least two of the original three must have been evicted under maxKeys=3 after two inserts.
        int survivors = 0;
        for (int i = 0; i < 3; i++) {
            if (store.get("keep-" + i) > 0) {
                survivors++;
            }
        }
        assertThat(survivors).isLessThanOrEqualTo(1);
    }

    @Test
    @Timeout(30)
    void baselineStore_concurrentCapacityPressure_neverExceedsMaxKeysMaterially() throws Exception {
        int maxKeys = 50;
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), maxKeys);
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    store.incrementAndGet("t" + threadId + "-k" + i);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // After quiescence, cardinality must respect the configured bound.
        assertThat(store.size()).isLessThanOrEqualTo(maxKeys);
    }

    @Test
    @Timeout(30)
    void bucketChain_concurrentSameKey_preservesExactCount() throws Exception {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 1000, now::get);
        String key = "id|/api/checkout";
        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < perThread; i++) {
                    store.incrementAndGet(key);
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(store.get(key)).isEqualTo(threads * perThread);
    }

    @Test
    void bucketChain_rollingWindow_unchangedUnderSequentialClock() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        Duration ttl = Duration.ofSeconds(30);
        BaselineStore store = new BaselineStore(ttl, 1000, now::get);
        String key = "id|/api";

        assertThat(store.incrementAndGet(key)).isEqualTo(1);
        now.addAndGet(10_000);
        assertThat(store.incrementAndGet(key)).isEqualTo(2);
        now.addAndGet(10_000);
        assertThat(store.incrementAndGet(key)).isEqualTo(3);

        now.addAndGet(ttl.toMillis() + BaselineStore.bucketMs());
        assertThat(store.get(key)).isLessThan(3);
    }

    @Test
    @Timeout(30)
    void compositeScorer_concurrentAddAndScore_noConcurrentModification() throws Exception {
        CompositeScorer composite = new CompositeScorer();
        AtomicInteger invocations = new AtomicInteger();
        AnomalyScorer child = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                invocations.incrementAndGet();
                return 0.25;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        composite.addScorer(child, 1.0);

        int scorers = 4;
        int adders = 2;
        ExecutorService pool = Executors.newFixedThreadPool(scorers + adders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < scorers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int n = 0; n < 2_000; n++) {
                    try {
                        double s = composite.score(FEATURES);
                        assertThat(s).isBetween(0.0, 1.0);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        throw t;
                    }
                }
                return null;
            }));
        }
        for (int i = 0; i < adders; i++) {
            int adderId = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int n = 0; n < 200; n++) {
                    composite.addScorer(new AnomalyScorer() {
                        @Override
                        public double score(RequestFeatures features) {
                            return 0.1 + (adderId * 0.01);
                        }

                        @Override
                        public void update(RequestFeatures features) {
                        }
                    }, 0.1);
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(failure.get()).isNull();
        assertThat(invocations.get()).isPositive();
        // Ordering of initially registered child is preserved in the view prefix.
        assertThat(composite.scorersView().get(0)).isSameAs(child);
        double after = composite.score(FEATURES);
        assertThat(after).isBetween(0.0, 1.0);
    }

    @Test
    void compositeScorer_blendUnchangedForStableRegistration() {
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(constant(0.2), 1.0);
        composite.addScorer(constant(0.8), 1.0);
        assertThat(composite.score(FEATURES)).isEqualTo(0.5);
        assertThat(composite.scorersView()).hasSize(2);
    }

    private static AnomalyScorer constant(double value) {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return value;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }
}
