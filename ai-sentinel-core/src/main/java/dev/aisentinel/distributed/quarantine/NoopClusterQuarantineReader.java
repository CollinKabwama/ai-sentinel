package dev.aisentinel.distributed.quarantine;

import java.util.OptionalLong;

/**
 * Default reader when cluster quarantine is off: always empty (local enforcement only).
 * Thread-safe singleton.
 */
public final class NoopClusterQuarantineReader implements ClusterQuarantineReader {

    public static final NoopClusterQuarantineReader INSTANCE = new NoopClusterQuarantineReader();

    private NoopClusterQuarantineReader() {
    }

    @Override
    public OptionalLong quarantineUntil(String tenantId, String enforcementKey) {
        return OptionalLong.empty();
    }
}
