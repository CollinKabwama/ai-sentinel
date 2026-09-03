package dev.aisentinel.trainer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes JVM-local training eventId deduplication under concurrency and capacity pressure.
 */
class TrainerEventIdDedupTest {

    @Test
    void duplicateEventIdsWithinJvmAreCollapsed() {
        BoundedEventIdDeduper deduper = new BoundedEventIdDeduper(100);
        assertThat(deduper.firstTime("e1")).isTrue();
        assertThat(deduper.firstTime("e1")).isFalse();
        assertThat(deduper.firstTime("e2")).isTrue();
        System.out.println("intra-JVM duplicate collapsed");
    }

    @Test
    void concurrentSameEventIdYieldsExactlyOneFirstTime() throws Exception {
        BoundedEventIdDeduper deduper = new BoundedEventIdDeduper(1_000);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firsts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (deduper.firstTime("same")) {
                    firsts.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        System.out.printf(Locale.ROOT, "concurrent firsts=%d (expect 1)%n", firsts.get());
        assertThat(firsts.get()).isEqualTo(1);
    }

    @Test
    void capacityEvictionAllowsReappearanceAfterOverflow() {
        BoundedEventIdDeduper deduper = new BoundedEventIdDeduper(3);
        assertThat(deduper.firstTime("a")).isTrue();
        assertThat(deduper.firstTime("b")).isTrue();
        assertThat(deduper.firstTime("c")).isTrue();
        assertThat(deduper.firstTime("d")).isTrue(); // evicts eldest
        // After overflow, earliest may be forgotten — reappearance treated as firstTime again
        boolean aAgain = deduper.firstTime("a");
        System.out.printf(Locale.ROOT, "afterCapOverflow aAgainFirst=%s%n", aAgain);
        assertThat(aAgain).isTrue();
    }

    @Test
    void capacityZeroDisablesDeduplication() {
        BoundedEventIdDeduper deduper = new BoundedEventIdDeduper(0);
        assertThat(deduper.firstTime("x")).isTrue();
        assertThat(deduper.firstTime("x")).isTrue();
    }
}
