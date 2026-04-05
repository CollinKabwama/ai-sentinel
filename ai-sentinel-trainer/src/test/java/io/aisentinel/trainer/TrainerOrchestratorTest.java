package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrainerOrchestratorTest {

    @Test
    void endToEndTrainAndPublish(@TempDir Path root) throws Exception {
        TrainerProperties props = new TrainerProperties();
        props.setTenantId("default");
        props.getRegistry().setFilesystemRoot(root.toString());
        props.getTrain().setMinSamples(3);
        props.getBuffer().setMaxSamples(100);
        props.getIfModel().setNumTrees(5);
        props.getIfModel().setMaxDepth(3);
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TrainerMetrics metrics = new TrainerMetrics(reg);
        TrainerOrchestrator orch = new TrainerOrchestrator(props, new ObjectMapper(), metrics);
        String template = """
            {"schemaVersion":2,"eventId":"%s","tenantId":"default","nodeId":"n","identityHash":"h",\
            "endpointSha256Hex":"%s","enforcementKeySha256Hex":"%s","observedAtEpochMillis":1,\
            "isolationForestFeatures":[1,2,3,4,5],"statisticalFeatures":[1,2,3,4,5,6,7],\
            "compositeScore":0.8,"policyAction":"MONITOR","sentinelMode":"ENFORCE","requestProceeded":true,\
            "startupGraceActive":false}\
            """;
        String ep = "a".repeat(64);
        String ek = "b".repeat(64);
        orch.handleKafkaMessage(template.formatted("e1", ep, ek));
        orch.handleKafkaMessage(template.formatted("e2", ep, ek));
        orch.handleKafkaMessage(template.formatted("e3", ep, ek));
        orch.runTrainCycle();
        Path active = root.resolve("default").resolve("active.json");
        assertThat(Files.isRegularFile(active)).isTrue();
        assertThat(reg.counter("aisentinel.trainer.artifact.published").count()).isEqualTo(1);
    }

    @Test
    void wrongTenantIncrementsMetric() {
        TrainerProperties props = new TrainerProperties();
        props.setTenantId("tenant-a");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TrainerMetrics metrics = new TrainerMetrics(reg);
        TrainerOrchestrator orch = new TrainerOrchestrator(props, new ObjectMapper(), metrics);
        String json = """
            {"schemaVersion":2,"eventId":"x","tenantId":"tenant-b","nodeId":"n","identityHash":"h",\
            "endpointSha256Hex":"%s","enforcementKeySha256Hex":"%s","observedAtEpochMillis":1,\
            "isolationForestFeatures":[1,2,3,4,5],"statisticalFeatures":[1,2,3,4,5,6,7],\
            "compositeScore":0.8,"policyAction":"MONITOR","sentinelMode":"ENFORCE","requestProceeded":true,\
            "startupGraceActive":false}\
            """.formatted("c".repeat(64), "d".repeat(64));
        orch.handleKafkaMessage(json);
        assertThat(reg.counter("aisentinel.trainer.candidates.wrong_tenant").count()).isEqualTo(1);
    }

    @Test
    void duplicateEventIdDroppedWithMetric() {
        TrainerProperties props = new TrainerProperties();
        props.setTenantId("default");
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        TrainerMetrics metrics = new TrainerMetrics(reg);
        TrainerOrchestrator orch = new TrainerOrchestrator(props, new ObjectMapper(), metrics);
        String json = """
            {"schemaVersion":2,"eventId":"same","tenantId":"default","nodeId":"n","identityHash":"h",\
            "endpointSha256Hex":"%s","enforcementKeySha256Hex":"%s","observedAtEpochMillis":1,\
            "isolationForestFeatures":[1,2,3,4,5],"statisticalFeatures":[1,2,3,4,5,6,7],\
            "compositeScore":0.8,"policyAction":"MONITOR","sentinelMode":"ENFORCE","requestProceeded":true,\
            "startupGraceActive":false}\
            """.formatted("a".repeat(64), "b".repeat(64));
        orch.handleKafkaMessage(json);
        orch.handleKafkaMessage(json);
        assertThat(reg.counter("aisentinel.trainer.candidates.duplicate_event_id").count()).isEqualTo(1);
    }
}
