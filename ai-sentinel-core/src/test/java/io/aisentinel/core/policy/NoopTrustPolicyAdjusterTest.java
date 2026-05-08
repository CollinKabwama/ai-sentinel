package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NoopTrustPolicyAdjusterTest {

    @Test
    void noopReturnsBaselineActionUnchanged() {
        TrustPolicyAdjustment adj = NoopTrustPolicyAdjuster.INSTANCE.adjust(
            EnforcementAction.THROTTLE,
            0.9,
            RequestFeatures.builder()
                .identityHash("h")
                .endpoint("/api")
                .timestampMillis(0)
                .requestsPerWindow(1)
                .endpointEntropy(0)
                .tokenAgeSeconds(0)
                .parameterCount(0)
                .payloadSizeBytes(0)
                .headerFingerprintHash(0)
                .ipBucket(0)
                .build(),
            "/api",
            mock(HttpServletRequest.class),
            new RequestContext()
        );
        assertThat(adj.action()).isEqualTo(EnforcementAction.THROTTLE);
        assertThat(adj.trustPolicyDetail()).isEmpty();
        assertThat(adj.changedFrom(EnforcementAction.THROTTLE)).isFalse();
    }
}
