package dev.aisentinel.distributed.quarantine;

/**
 * Default writer when cluster quarantine publish is off: discards quarantine events.
 * Thread-safe singleton.
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
