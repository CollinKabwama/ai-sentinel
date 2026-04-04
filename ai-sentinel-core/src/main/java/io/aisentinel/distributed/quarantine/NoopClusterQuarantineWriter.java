package io.aisentinel.distributed.quarantine;

/**
 * Default writer: no cross-node replication.
 */
public final class NoopClusterQuarantineWriter implements ClusterQuarantineWriter {

    public static final NoopClusterQuarantineWriter INSTANCE = new NoopClusterQuarantineWriter();

    private NoopClusterQuarantineWriter() {
    }

    @Override
    public void publishQuarantine(String tenantId, String enforcementKey, long untilEpochMillis) {
        // no-op
    }
}
