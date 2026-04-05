package io.aisentinel.trainer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-capacity FIFO of IF feature rows; drops oldest on overflow.
 */
public final class BoundedIfSampleBuffer {

    private final int maxSamples;
    private final ArrayDeque<double[]> deque = new ArrayDeque<>();

    public BoundedIfSampleBuffer(int maxSamples) {
        this.maxSamples = Math.max(1, maxSamples);
    }

    public synchronized void offer(double[] row) {
        if (row == null) {
            return;
        }
        while (deque.size() >= maxSamples) {
            deque.pollFirst();
        }
        deque.addLast(row.clone());
    }

    public synchronized int size() {
        return deque.size();
    }

    /**
     * Snapshot for training; buffer is unchanged.
     */
    public synchronized List<double[]> snapshot() {
        return new ArrayList<>(deque);
    }

    public synchronized void clear() {
        deque.clear();
    }

    /**
     * Removes and returns all samples in FIFO order. Concurrent {@link #offer} calls may refill the buffer
     * while the caller trains on the returned list.
     */
    public synchronized List<double[]> drainAll() {
        List<double[]> out = new ArrayList<>(deque.size());
        while (!deque.isEmpty()) {
            out.add(deque.pollFirst());
        }
        return out;
    }
}
