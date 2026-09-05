package dev.aisentinel.benchmark.deployment;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ResourceSampler implements AutoCloseable {

    private final Duration interval;
    private final String redisContainerId;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong peakHeapBytes = new AtomicLong();
    private final AtomicLong peakRssBytes = new AtomicLong(-1L);
    private final AtomicLong latestRedisMemoryBytes = new AtomicLong(-1L);
    private final AtomicLong redisSampleCount = new AtomicLong();
    private final AtomicLong redisCpuSamplesScaled = new AtomicLong();
    private Thread thread;

    ResourceSampler(Duration interval, String redisContainerId) {
        this.interval = interval;
        this.redisContainerId = redisContainerId;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual().name("resource-sampler").start(() -> {
            while (running.get()) {
                peakHeapBytes.accumulateAndGet(ResourceSupport.heapUsedBytes(), Math::max);
                Long rss = ResourceSupport.processRssBytes();
                if (rss != null) {
                    peakRssBytes.accumulateAndGet(rss, Math::max);
                }
                if (redisContainerId != null) {
                    ResourceSupport.DockerStats stats = ResourceSupport.dockerStats(redisContainerId);
                    if (stats != null) {
                        latestRedisMemoryBytes.set(stats.memoryBytes());
                        redisCpuSamplesScaled.addAndGet(Math.round(stats.cpuPercent() * 1000.0));
                        redisSampleCount.incrementAndGet();
                    }
                }
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    long peakHeapBytes() {
        return peakHeapBytes.get();
    }

    Long peakRssBytes() {
        long value = peakRssBytes.get();
        return value < 0 ? null : value;
    }

    Double averageRedisCpuPercent() {
        long samples = redisSampleCount.get();
        if (samples == 0L) {
            return null;
        }
        return redisCpuSamplesScaled.get() / 1000.0 / samples;
    }

    Long latestRedisMemoryBytes() {
        long value = latestRedisMemoryBytes.get();
        return value < 0 ? null : value;
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
