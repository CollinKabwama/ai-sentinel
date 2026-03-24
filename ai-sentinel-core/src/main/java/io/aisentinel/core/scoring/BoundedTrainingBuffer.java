package io.aisentinel.core.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe bounded ring buffer for feature vectors used to train Isolation Forest.
 * When full, oldest entries are overwritten. No unbounded growth.
 */
public final class BoundedTrainingBuffer {

    private final double[][] ring;
    private final int maxSize;
    private final AtomicInteger size = new AtomicInteger(0);
    private int writeIndex;
    private final ReentrantLock writeLock = new ReentrantLock();

    public BoundedTrainingBuffer(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        this.ring = new double[this.maxSize][];
        this.writeIndex = 0;
    }

    /**
     * Appends a copy of the feature vector. If buffer is full, overwrites oldest.
     * Thread-safe.
     */
    public void add(double[] features) {
        if (features == null || features.length == 0) return;
        double[] copy = Arrays.copyOf(features, features.length);
        writeLock.lock();
        try {
            ring[writeIndex] = copy;
            writeIndex = (writeIndex + 1) % maxSize;
            int s = size.get();
            if (s < maxSize) size.set(s + 1);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Current number of samples (capped at maxSize).
     */
    public int size() {
        return size.get();
    }

    /**
     * Returns a snapshot of all stored vectors for training. Order is unspecified.
     * Caller must not modify the inner arrays. Safe to call while other threads add.
     */
    public List<double[]> getSnapshotForTraining() {
        writeLock.lock();
        try {
            int n = size.get();
            List<double[]> out = new ArrayList<>(n);
            if (n == maxSize) {
                for (int i = 0; i < maxSize; i++) {
                    int idx = (writeIndex + i) % maxSize;
                    if (ring[idx] != null) out.add(ring[idx]);
                }
            } else {
                for (int i = 0; i < writeIndex; i++) {
                    if (ring[i] != null) out.add(ring[i]);
                }
            }
            return out;
        } finally {
            writeLock.unlock();
        }
    }
}
