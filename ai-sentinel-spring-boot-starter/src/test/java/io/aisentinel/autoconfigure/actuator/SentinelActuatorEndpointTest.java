package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.scoring.BoundedTrainingBuffer;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.scoring.IsolationForestConfig;
import io.aisentinel.core.scoring.IsolationForestScorer;
import io.aisentinel.core.scoring.StatisticalScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SentinelActuatorEndpointTest {

    private static <T> ObjectProvider<T> nullProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() throws BeansException {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getObject(Object... args) throws BeansException {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() throws BeansException {
                return null;
            }

            @Override
            public T getIfAvailable(Supplier<T> supplier) throws BeansException {
                return supplier.get();
            }

            @Override
            public T getIfUnique() throws BeansException {
                return null;
            }

            @Override
            public Iterator<T> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<T> orderedStream() {
                return Stream.empty();
            }
        };
    }

    private static CompositeEnforcementHandler compositeHandler() {
        return new CompositeEnforcementHandler(429, 60_000L, 5.0, mock(TelemetryEmitter.class));
    }

    @Test
    void infoReturnsExpectedStructure() {
        SentinelProperties props = new SentinelProperties();
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            null, null, null, null, null, nullProvider(), nullProvider());

        Map<String, Object> info = endpoint.info();

        assertThat(info).containsKeys("enabled", "mode", "isolationForestEnabled", "quarantineCount",
            "startupGraceActive", "enforcementScope", "activeThrottleCount", "activeQuarantineCount",
            "acceptedTrainingSampleCount", "rejectedTrainingSampleCount", "lastScoreComponents",
            "distributedEnabled", "distributedClusterQuarantineReadEnabled", "distributedClusterQuarantineWriteEnabled",
            "distributedClusterThrottleEnabled", "distributedRedisEnabled", "distributedRedisKeyPrefix",
            "distributedTrainingPublishEnabled", "distributedTrainingKafkaEnabled", "distributedTrainingCandidatesTopic");
        assertThat(info.get("enabled")).isEqualTo(true);
        assertThat(info.get("mode")).isEqualTo("ENFORCE");
        assertThat(info.get("isolationForestEnabled")).isEqualTo(false);
        assertThat(info.get("quarantineCount")).isEqualTo(0);
    }

    @Test
    void infoReflectsCustomProperties() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(false);
        props.setMode(SentinelProperties.Mode.MONITOR);
        props.getIsolationForest().setEnabled(true);
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            null, null, null, null, null, nullProvider(), nullProvider());

        Map<String, Object> info = endpoint.info();

        assertThat(info.get("enabled")).isEqualTo(false);
        assertThat(info.get("mode")).isEqualTo("MONITOR");
        assertThat(info.get("isolationForestEnabled")).isEqualTo(true);
        assertThat(info.get("quarantineCount")).isEqualTo(0);
    }

    @Test
    void infoIncludesIsolationForestMetadataWhenEnabledAndScorerProvided() {
        SentinelProperties props = new SentinelProperties();
        props.getIsolationForest().setEnabled(true);
        var buffer = new BoundedTrainingBuffer(100);
        var config = new IsolationForestConfig(0.5, 10, 5, 5, 42L, 0.1);
        IsolationForestScorer ifScorer = new IsolationForestScorer(buffer, config);
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), ifScorer, StartupGrace.NEVER, null, null,
            null, null, null, null, null, nullProvider(), nullProvider());

        Map<String, Object> info = endpoint.info();

        assertThat(info).containsKeys("isolationForestModelLoaded", "isolationForestBufferedSampleCount",
            "isolationForestModelVersion", "isolationForestLastRetrainTimeMillis",
            "isolationForestModelAgeMillis", "isolationForestRetrainFailureCount",
            "isolationForestLastRetrainFailureTimeMillis");
        assertThat(info.get("isolationForestModelLoaded")).isEqualTo(false);
        assertThat(info.get("isolationForestBufferedSampleCount")).isEqualTo(0);
        assertThat(info.get("isolationForestModelAgeMillis")).isEqualTo(-1L);
        assertThat(info.get("isolationForestRetrainFailureCount")).isEqualTo(0L);
    }

    @Test
    void infoIncludesLastScoreComponentsAfterCompositeScorerRuns() {
        SentinelProperties props = new SentinelProperties();
        var features = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
        var composite = new CompositeScorer();
        composite.addScorer(new StatisticalScorer(100, 60_000L, 999, 0.55), 1.0);
        composite.score(features);

        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, composite,
            null, null, null, null, null, nullProvider(), nullProvider());

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) endpoint.info().get("lastScoreComponents");

        assertThat(components).containsKeys("statistical", "composite", "evaluatedAtMillis");
        assertThat(components.get("statistical")).isEqualTo(0.55);
        assertThat(components.get("composite")).isEqualTo(0.55);
    }
}
