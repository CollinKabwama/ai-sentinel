package io.aisentinel.core.scoring;

import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import io.aisentinel.model.ModelArtifactMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationForestScorerRegistryInstallTest {

    @Test
    void failedChecksumKeepsPriorModel() throws Exception {
        BoundedTrainingBuffer buf = new BoundedTrainingBuffer(100);
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 2, 5, 3, 1L, 1.0, 0.99);
        IsolationForestScorer scorer = new IsolationForestScorer(buf, cfg);
        buf.add(new double[] {1, 2, 3, 4, 5});
        buf.add(new double[] {2, 2, 2, 2, 2});
        buf.add(new double[] {3, 3, 3, 3, 3});
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        long v0 = scorer.getModelVersion();
        byte[] wrong = new byte[] {9, 9, 9};
        var badMeta = new ModelArtifactMetadata(
            "t",
            "bad",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            200L,
            5,
            5,
            3,
            3,
            TrainingFingerprintHashes.sha256HexBytes(new byte[] {1})
        );
        assertThat(scorer.tryInstallFromRegistry(badMeta, wrong)).isFalse();
        assertThat(scorer.getModelVersion()).isEqualTo(v0);
        assertThat(scorer.isModelLoaded()).isTrue();
        assertThat(scorer.getActiveModelSource()).isEqualTo(IsolationForestScorer.ActiveModelSource.LOCAL_RETRAIN);
    }

    @Test
    void installsValidArtifact() throws Exception {
        BoundedTrainingBuffer buf = new BoundedTrainingBuffer(100);
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 2, 5, 3, 1L, 1.0, 0.99);
        IsolationForestScorer scorer = new IsolationForestScorer(buf, cfg);
        List<double[]> samples = List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        );
        IsolationForestTrainer tr = new IsolationForestTrainer(5, 3, 42L);
        IsolationForestModel m = tr.train(samples);
        byte[] payload = IsolationForestModelCodec.encode(m);
        String hash = TrainingFingerprintHashes.sha256HexBytes(payload);
        var meta = new ModelArtifactMetadata(
            "default",
            "reg-v1",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            500L,
            5,
            5,
            3,
            3,
            hash
        );
        assertThat(scorer.tryInstallFromRegistry(meta, payload)).isTrue();
        assertThat(scorer.getRegistryArtifactVersion()).isEqualTo("reg-v1");
        assertThat(scorer.isModelLoaded()).isTrue();
        assertThat(scorer.getActiveModelSource()).isEqualTo(IsolationForestScorer.ActiveModelSource.REGISTRY);
    }
}
