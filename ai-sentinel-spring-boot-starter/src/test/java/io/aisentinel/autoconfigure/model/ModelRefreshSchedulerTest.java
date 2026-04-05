package io.aisentinel.autoconfigure.model;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.scoring.BoundedTrainingBuffer;
import io.aisentinel.core.scoring.IsolationForestConfig;
import io.aisentinel.core.scoring.IsolationForestModelCodec;
import io.aisentinel.core.scoring.IsolationForestScorer;
import io.aisentinel.core.scoring.IsolationForestTrainer;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import io.aisentinel.model.ModelArtifactMetadata;
import io.aisentinel.model.ModelRegistryReader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRefreshSchedulerTest {

    @Test
    void tickInstallsWhenRegistryAdvances() throws Exception {
        BoundedTrainingBuffer buf = new BoundedTrainingBuffer(50);
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 2, 5, 3, 1L, 1.0, 0.99);
        IsolationForestScorer scorer = new IsolationForestScorer(buf, cfg, SentinelMetrics.NOOP);
        List<double[]> samples = List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        );
        var m = new IsolationForestTrainer(5, 3, 42L).train(samples);
        byte[] payload = IsolationForestModelCodec.encode(m);
        String hash = TrainingFingerprintHashes.sha256HexBytes(payload);
        var meta = new ModelArtifactMetadata(
            "default",
            "v-remote",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            900L,
            5,
            5,
            3,
            3,
            hash
        );
        ModelRegistryReader reader = new ModelRegistryReader() {
            @Override
            public Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId) {
                return Optional.of(meta);
            }

            @Override
            public Optional<byte[]> fetchPayload(String tenantId, String modelVersion) {
                return Optional.of(payload);
            }
        };
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().setTenantId("default");
        AtomicLong refreshOk = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordModelRegistryRefreshSuccess() {
                refreshOk.incrementAndGet();
            }
        };
        ModelRefreshScheduler sched = new ModelRefreshScheduler(scorer, reader, props, metrics);
        sched.tick();
        assertThat(scorer.getRegistryArtifactVersion()).isEqualTo("v-remote");
        assertThat(refreshOk.get()).isEqualTo(1);
    }

    @Test
    void startRunsImmediateRefreshOffThread() throws Exception {
        BoundedTrainingBuffer buf = new BoundedTrainingBuffer(10);
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 2, 5, 3, 1L, 1.0, 0.99);
        IsolationForestScorer scorer = new IsolationForestScorer(buf, cfg, SentinelMetrics.NOOP);
        AtomicLong attempts = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordModelRegistryRefreshAttempt() {
                attempts.incrementAndGet();
            }
        };
        ModelRegistryReader reader = new ModelRegistryReader() {
            @Override
            public Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> fetchPayload(String tenantId, String modelVersion) {
                return Optional.empty();
            }
        };
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().setTenantId("default");
        props.getModelRegistry().setPollInterval(java.time.Duration.ofMinutes(5));
        ModelRefreshScheduler sched = new ModelRefreshScheduler(scorer, reader, props, metrics);
        sched.start();
        try {
            long deadline = System.currentTimeMillis() + 5_000;
            while (attempts.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(attempts.get()).isGreaterThanOrEqualTo(1);
        } finally {
            sched.destroy();
        }
    }
}
