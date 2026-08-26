package dev.aisentinel.autoconfigure.actuator;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
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
            "lastDecision", "lastDecisionScope",
            "distributedEnabled", "distributedClusterQuarantineReadEnabled", "distributedClusterQuarantineWriteEnabled",
            "distributedClusterThrottleEnabled", "distributedRedisEnabled", "distributedRedisKeyPrefix",
            "distributedTrainingPublishEnabled", "distributedTrainingKafkaEnabled", "distributedTrainingCandidatesTopic");
        assertThat(info.get("lastDecisionScope")).isEqualTo("lastCompletedDecisionOnThisJvm");
        assertThat(info.get("lastDecision")).isEqualTo(Map.of());
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
            "isolationForestLastRetrainFailureTimeMillis",
            "modelRegistryArtifactVersion", "modelRegistryLastInstallTimeMillis", "modelRegistryInstallFailureCount");
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

        assertThat(components).containsKeys("statistical", "composite", "evaluatedAtMillis",
            "isolationForestIncludedInBlend");
        assertThat(components.get("statistical")).isEqualTo(0.55);
        assertThat(components.get("composite")).isEqualTo(0.55);
        assertThat(components.get("isolationForestIncludedInBlend")).isEqualTo(false);
    }

    @Test
    void lastDecisionExplainsActionScoresAndStatisticalDominantWithoutSensitiveFields() {
        SentinelProperties props = new SentinelProperties();
        var holder = new LastDecisionExplanation();
        holder.record(new LastDecisionExplanation.Snapshot(
            "BLOCK",
            0.91,
            0.91,
            false,
            java.util.List.of("COMPLETE", "STATISTICAL_LIVE"),
            java.util.List.of("LIVE"),
            "FALLBACK_NO_MODEL",
            0.91,
            0.5,
            false,
            new StatisticalScoreSnapshot(0.91, false, "requestsPerWindow", 100.0, 10.0, 1.0, 90.0, 20.0),
            false,
            1_700_000_000_000L
        ));

        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            holder, null, null, null, null, null, nullProvider(), nullProvider());

        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) endpoint.info().get("lastDecision");

        assertThat(last.get("action")).isEqualTo("BLOCK");
        assertThat(last.get("policyBand")).isEqualTo("BLOCK");
        assertThat(last.get("anomalyScore")).isEqualTo(0.91);
        assertThat(last.get("isolationForestScoreMode")).isEqualTo("FALLBACK_NO_MODEL");
        assertThat(last.get("isolationForestIncludedInBlend")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> se = (Map<String, Object>) last.get("statisticalExplanation");
        assertThat(se.get("dominantFeature")).isEqualTo("requestsPerWindow");
        assertThat(last.keySet()).doesNotContain("identityHash", "endpoint", "ipBucket", "headerFingerprintHash",
            "context", "features", "token");
        String encoded = last.toString();
        assertThat(encoded).doesNotContain("identityHash").doesNotContain("/api/");
    }

    @Test
    void lastDecisionReportsModelFallbackAndDegradedPhases() {
        SentinelProperties props = new SentinelProperties();
        var holder = new LastDecisionExplanation();
        holder.record(new LastDecisionExplanation.Snapshot(
            "MONITOR",
            0.4,
            0.4,
            false,
            java.util.List.of("DEGRADED", "MODEL_FALLBACK_USED", "MODEL_UNAVAILABLE", "STATISTICAL_WARMUP"),
            java.util.List.of("DEGRADED", "MODEL_FALLBACK", "WARMUP"),
            "FALLBACK_NO_MODEL",
            0.4,
            0.5,
            false,
            StatisticalScoreSnapshot.warmup(0.4),
            false,
            99L
        ));
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            holder, null, null, null, null, null, nullProvider(), nullProvider());

        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) endpoint.info().get("lastDecision");
        assertThat(last.get("operatorPhases")).asList().contains("MODEL_FALLBACK", "DEGRADED", "WARMUP");
        assertThat(last.get("isolationForestIncludedInBlend")).isEqualTo(false);
    }

    @Test
    void lastDecisionNullsNonFiniteScoresForInvalidScorePresentation() throws Exception {
        SentinelProperties props = new SentinelProperties();
        var holder = new LastDecisionExplanation();
        holder.record(new LastDecisionExplanation.Snapshot(
            "ALLOW",
            Double.NaN,
            Double.NaN,
            false,
            java.util.List.of("INVALID_SCORE"),
            java.util.List.of("LIVE"),
            null,
            null,
            Double.POSITIVE_INFINITY,
            false,
            null,
            false,
            42L
        ));
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            holder, null, null, null, null, null, nullProvider(), nullProvider());

        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) endpoint.info().get("lastDecision");
        assertThat(last.get("action")).isEqualTo("ALLOW");
        assertThat(last.get("anomalyScore")).isNull();
        assertThat(last.get("policyScore")).isNull();
        assertThat(last.get("isolationForestScore")).isNull();
        assertThat(last.get("evaluationStatuses")).asList().contains("INVALID_SCORE");
        // Must not look like legitimate 0.0 or 1.0 risk, and must not emit a NaN token.
        assertThat(last.get("anomalyScore")).isNotEqualTo(0.0);
        assertThat(last.get("anomalyScore")).isNotEqualTo(1.0);
        String encoded = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(last);
        assertThat(encoded).doesNotContain("NaN");
        assertThat(encoded).doesNotContain("Infinity");
        assertThat(encoded).contains("\"anomalyScore\":null");
        assertThat(encoded).contains("INVALID_SCORE");
    }

    @Test
    void finiteScoreOrNullMapsOnlyNonFinite() {
        assertThat(SentinelActuatorEndpoint.finiteScoreOrNull(0.0)).isEqualTo(0.0);
        assertThat(SentinelActuatorEndpoint.finiteScoreOrNull(1.0)).isEqualTo(1.0);
        assertThat(SentinelActuatorEndpoint.finiteScoreOrNull(Double.NaN)).isNull();
        assertThat(SentinelActuatorEndpoint.finiteScoreOrNull(Double.POSITIVE_INFINITY)).isNull();
        assertThat(SentinelActuatorEndpoint.finiteScoreOrNull(Double.NEGATIVE_INFINITY)).isNull();
    }

    @Test
    void isolationForestModeFieldsReportSensibleDefaultBeforeAnyScoreAndUpdateAfter() {
        SentinelProperties props = new SentinelProperties();
        props.getIsolationForest().setEnabled(true);
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0);
        IsolationForestScorer ifScorer = new IsolationForestScorer(buffer, config);
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), ifScorer, StartupGrace.NEVER, null, null,
            null, null, null, null, null, nullProvider(), nullProvider());

        // Before any request has been scored: no model loaded yet, but the field is present and
        // reports a meaningful (not null) fallback-mode default rather than an ambiguous absence.
        Map<String, Object> initial = endpoint.info();
        assertThat(initial.get("isolationForestLastScoreMode")).isEqualTo("FALLBACK_NO_MODEL");
        assertThat(initial.get("isolationForestActiveModelSource")).isEqualTo("NONE");

        RequestFeatures f = RequestFeatures.builder()
            .identityHash("id").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0)
            .build();

        // Still no model: scoring keeps reporting the fallback mode, not a stale/absent value.
        ifScorer.score(f);
        assertThat(endpoint.info().get("isolationForestLastScoreMode")).isEqualTo("FALLBACK_NO_MODEL");

        // Recovery: once a model is trained and installed, the next score reports MODEL — no
        // stale FALLBACK_NO_MODEL survives after the dependency becomes healthy.
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[] {i % 10, 0.5, 60, 2, 100 + i});
        }
        ifScorer.retrain();
        assertThat(ifScorer.isModelLoaded()).isTrue();
        ifScorer.score(f);

        Map<String, Object> recovered = endpoint.info();
        assertThat(recovered.get("isolationForestLastScoreMode")).isEqualTo("MODEL");
        assertThat(recovered.get("isolationForestActiveModelSource")).isEqualTo("LOCAL_RETRAIN");
    }

    @Test
    void evaluationStatusModelIsAlwaysPresentAndBounded() {
        SentinelProperties props = new SentinelProperties();
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null, StartupGrace.NEVER, null, null,
            null, null, null, null, null, nullProvider(), nullProvider());

        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) endpoint.info().get("evaluationStatusModel");

        assertThat(model).containsKeys("WARMUP", "LIVE", "MODEL_FALLBACK", "DEGRADED", "FAIL_OPEN");
    }
}
