package dev.aisentinel.autoconfigure.distributed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis reconnect recovery for quarantine status flags.
 * <p>
 * This is <strong>not</strong> a live Redis / multi-process reconnect E2E — it proves the
 * status object's fail→success transition that readers/writers use to clear degraded after
 * Lettuce reconnects. Live Redis coverage remains in Testcontainers suites when Docker is available.
 */
class RedisReconnectStatusRecoveryTest {

    @Test
    void readerDegradedClearsOnSubsequentSuccess() {
        DistributedQuarantineStatus status = new DistributedQuarantineStatus();
        status.recordRedisError("timeout", new RuntimeException("boom"));
        assertThat(status.isRedisReaderDegraded()).isTrue();
        assertThat(status.getLastRedisErrorSummary()).contains("timeout");

        status.recordRedisSuccess();
        assertThat(status.isRedisReaderDegraded()).isFalse();
    }

    @Test
    void writerDegradedClearsOnSubsequentSuccess() {
        DistributedQuarantineStatus status = new DistributedQuarantineStatus();
        status.recordWriteError("set-failed", new RuntimeException("boom"));
        assertThat(status.isRedisWriterDegraded()).isTrue();

        status.recordWriteSuccess();
        assertThat(status.isRedisWriterDegraded()).isFalse();
    }

    @Test
    void successAfterFailureDoesNotRequireRestart() {
        DistributedQuarantineStatus status = new DistributedQuarantineStatus();
        status.recordRedisError("conn", new RuntimeException("down"));
        status.recordWriteError("conn", new RuntimeException("down"));
        assertThat(status.isRedisReaderDegraded()).isTrue();
        assertThat(status.isRedisWriterDegraded()).isTrue();

        // Simulate restored Redis path without constructing a new status bean.
        status.recordRedisSuccess();
        status.recordWriteSuccess();
        assertThat(status.isRedisReaderDegraded()).isFalse();
        assertThat(status.isRedisWriterDegraded()).isFalse();
    }
}
