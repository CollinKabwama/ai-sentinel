package io.aisentinel.core.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTrainingBufferTest {

    @Test
    void bufferBoundedWhenOverMaxSize() {
        var buffer = new BoundedTrainingBuffer(3);
        buffer.add(new double[]{1, 2, 3});
        buffer.add(new double[]{4, 5, 6});
        buffer.add(new double[]{7, 8, 9});
        assertThat(buffer.size()).isEqualTo(3);
        buffer.add(new double[]{10, 11, 12});
        buffer.add(new double[]{13, 14, 15});
        assertThat(buffer.size()).isEqualTo(3);
        List<double[]> snap = buffer.getSnapshotForTraining();
        assertThat(snap).hasSize(3);
    }

    @Test
    void snapshotContainsStoredVectors() {
        var buffer = new BoundedTrainingBuffer(10);
        buffer.add(new double[]{1.0, 2.0});
        buffer.add(new double[]{3.0, 4.0});
        List<double[]> snap = buffer.getSnapshotForTraining();
        assertThat(snap).hasSize(2);
        assertThat(snap.get(0)).containsExactly(1.0, 2.0);
        assertThat(snap.get(1)).containsExactly(3.0, 4.0);
    }

    @Test
    void concurrentAddsDoNotExceedMaxSize() throws InterruptedException {
        var buffer = new BoundedTrainingBuffer(100);
        int threads = 4;
        int addsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < addsPerThread; i++) {
                        buffer.add(new double[]{i, i * 2});
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertThat(buffer.size()).isLessThanOrEqualTo(100);
        assertThat(buffer.getSnapshotForTraining()).hasSize(buffer.size());
    }

    @Test
    void addIgnoresNullOrEmpty() {
        var buffer = new BoundedTrainingBuffer(5);
        buffer.add(null);
        buffer.add(new double[0]);
        assertThat(buffer.size()).isEqualTo(0);
    }
}
