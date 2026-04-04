package io.aisentinel.distributed.enforcement;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ClusterAwareEnforcementHandlerTest {

    @Test
    void localQuarantineShortCircuitsWithoutCallingCluster() {
        var delegate = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
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
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
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
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
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
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
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
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
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
            public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
                                 String identityHash, String endpoint) {
                return false;
            }
        };
        var handler = new ClusterAwareEnforcementHandler(delegate, (t, k) -> OptionalLong.empty(), "t1",
            EnforcementScope.IDENTITY_ENDPOINT);
        assertThat(handler.apply(EnforcementAction.ALLOW, mock(HttpServletRequest.class), mock(HttpServletResponse.class),
            "id", "/a")).isFalse();
    }
}
