package dev.aisentinel.distributed.enforcement;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClusterAwareEnforcementHandlerTest {

    @Test
    void localQuarantineShortCircuitsWithoutCallingCluster() {
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return true;
            }
        };
        ClusterQuarantineReader neverCalled = (t, k) -> {
            throw new AssertionError("cluster reader should not run when local quarantine is true");
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, neverCalled, "t1", EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.isQuarantined("id", "/a")).isTrue();
    }

    @Test
    void identityGlobalUsesIdentityOnlyKeyForCluster() {
        long future = System.currentTimeMillis() + 60_000;
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        ClusterQuarantineReader reader = (tenant, key) -> {
            assertThat(tenant).isEqualTo("t1");
            assertThat(key).isEqualTo("id-h");
            return OptionalLong.of(future);
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, reader, "t1", EnforcementScope.IDENTITY_GLOBAL);
        assertThat(handler.isQuarantined("id-h", "/ignored")).isTrue();
        assertThat(handler.isQuarantined("id-h", "/other")).isTrue();
    }

    @Test
    void clusterExpiredUntilIsNotQuarantined() {
        long past = System.currentTimeMillis() - 60_000;
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        ClusterQuarantineReader reader = (t, k) -> OptionalLong.of(past);
        var handler = new ClusterAwareEnforcementHandler(delegate, reader, "t1", EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.isQuarantined("id", "/a")).isFalse();
    }

    @Test
    void clusterQuarantineMergesWhenLocalFalse() {
        long future = System.currentTimeMillis() + 60_000;
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        ClusterQuarantineReader reader = (tenant, key) -> {
            assertThat(tenant).isEqualTo("t1");
            assertThat(key).isEqualTo("id|/a");
            return OptionalLong.of(future);
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, reader, "t1", EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.isQuarantined("id", "/a")).isTrue();
    }

    @Test
    void noopClusterReaderMatchesLocalOnly() {
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, (t, k) -> OptionalLong.empty(), "t1",
            EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.isQuarantined("id", "/a")).isFalse();
    }

    @Test
    void applyDelegates() {
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return false;
            }
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, (t, k) -> OptionalLong.empty(), "t1",
            EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.apply(EnforcementAction.ALLOW, mock(HttpRequestView.class), mock(EnforcementResponse.class),
            "id", "/a")).isFalse();
    }

    @Test
    void releaseDelegatesAndInvalidatesClusterLookupCache() {
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> invalidatedKey = new java.util.concurrent.atomic.AtomicReference<>();
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean releaseQuarantine(String identityHash, String endpoint) {
                released.set(true);
                return true;
            }
        };
        ClusterQuarantineReader reader = new ClusterQuarantineReader() {
            @Override
            public OptionalLong quarantineUntil(String tenantId, String enforcementKey) {
                return OptionalLong.empty();
            }

            @Override
            public void invalidateQuarantineLookup(String tenantId, String enforcementKey) {
                assertThat(tenantId).isEqualTo("t1");
                invalidatedKey.set(enforcementKey);
            }
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, reader, "t1", EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.releaseQuarantine("id", "/a")).isTrue();
        assertThat(released.get()).isTrue();
        assertThat(invalidatedKey.get()).isEqualTo("id|/a");
    }

    @Test
    void releaseSuppressesImmediateStaleClusterReassertion() {
        long future = System.currentTimeMillis() + 60_000;
        var delegate = new EnforcementHandler() {
            boolean quarantined = true;

            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                if (action == EnforcementAction.QUARANTINE) {
                    quarantined = true;
                }
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return quarantined;
            }

            @Override
            public boolean releaseQuarantine(String identityHash, String endpoint) {
                boolean hadLocal = quarantined;
                quarantined = false;
                return hadLocal;
            }
        };
        ClusterQuarantineReader staleReader = (tenant, key) -> OptionalLong.of(future);
        var handler = new ClusterAwareEnforcementHandler(delegate, staleReader, "t1", EnforcementScope.IDENTITY_ENDPOINT);

        assertThat(handler.releaseQuarantine("id", "/a")).isTrue();
        assertThat(handler.isQuarantined("id", "/a")).isFalse();

        handler.apply(EnforcementAction.QUARANTINE, mock(HttpRequestView.class), mock(EnforcementResponse.class),
            "id", "/a");
        assertThat(handler.isQuarantined("id", "/a")).isTrue();
    }

}
