package io.aisentinel.trainer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedIfSampleBufferTest {

    @Test
    void drainAllEmptiesBufferSoNewArrivalsArePreservedAfterTrain() {
        BoundedIfSampleBuffer b = new BoundedIfSampleBuffer(10);
        b.offer(new double[] {1, 2, 3});
        b.offer(new double[] {4, 5, 6});
        List<double[]> snap = b.drainAll();
        assertThat(snap).hasSize(2);
        assertThat(b.size()).isZero();
        b.offer(new double[] {7, 8, 9});
        assertThat(b.size()).isEqualTo(1);
    }
}
