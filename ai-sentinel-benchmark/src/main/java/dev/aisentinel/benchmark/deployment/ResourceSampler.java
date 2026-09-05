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
    private final AtomicLong sampleCount = new AtomicLong();
    private final AtomicLong redisSampleCount = new AtomicLong();
    private final AtomicLong redisCpuSampleCount = new AtomicLong();
    private final AtomicLong redisCpuSamplesScaled = new AtomicLong();
    private final AtomicLong sampleFailures = new AtomicLong();
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
                sampleOnce();
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    Long peakHeapBytes() {
        return sampleCount.get() == 0L ? null : peakHeapBytes.get();
    }

    Long peakRssBytes() {
        long value = peakRssBytes.get();
        return value < 0 ? null : value;
    }

    Double averageRedisCpuPercent() {
        long samples = redisCpuSampleCount.get();
        if (samples == 0L) {
            return null;
        }
        return redisCpuSamplesScaled.get() / 1000.0 / samples;
    }

    Long latestRedisMemoryBytes() {
        long value = latestRedisMemoryBytes.get();
        return value < 0 ? null : value;
    }

    long sampleCount() {
        return sampleCount.get();
    }

    long redisSampleCount() {
        return redisSampleCount.get();
    }

    long sampleFailures() {
        return sampleFailures.get();
    }

    @Override
    public void close() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(1000L);
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sampleOnce() {
        try {
            peakHeapBytes.accumulateAndGet(ResourceSupport.heapUsedBytes(), Math::max);
            sampleCount.incrementAndGet();
            Long rss = ResourceSupport.processRssBytes();
            if (rss != null) {
                peakRssBytes.accumulateAndGet(rss, Math::max);
            }
            if (redisContainerId != null) {
                ResourceSupport.DockerStats stats = ResourceSupport.dockerStats(redisContainerId);
                if (stats != null) {
                    if (stats.memoryBytes() != null) {
                        latestRedisMemoryBytes.set(stats.memoryBytes());
                    }
                    if (stats.cpuPercent() != null) {
                        redisCpuSamplesScaled.addAndGet(Math.round(stats.cpuPercent() * 1000.0));
                        redisCpuSampleCount.incrementAndGet();
                    }
                    redisSampleCount.incrementAndGet();
                }
            }
        } catch (RuntimeException ignored) {
            sampleFailures.incrementAndGet();
        }
    }
}
