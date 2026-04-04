package io.aisentinel.distributed.quarantine;

import java.util.OptionalLong;

/**
 * Default reader: no shared quarantine (single-node behavior).
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
