package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.core.scoring.IsolationForestModel;
import io.aisentinel.core.scoring.IsolationForestModelCodec;
import io.aisentinel.core.scoring.IsolationForestTrainer;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import io.aisentinel.model.ModelArtifactMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Consumes JSON training candidates, maintains a bounded FIFO buffer, runs scheduled train cycles, publishes
 * artifacts to the configured filesystem registry. Not on the API request path.
 */
@Service
@Slf4j
public class TrainerOrchestrator {

    private final TrainerProperties props;
    private final TrainingCandidateMessageParser parser;
    private final BoundedIfSampleBuffer buffer;
    private final FilesystemArtifactPublisher publisher;
    private final TrainerMetrics metrics;
    private final ObjectMapper objectMapper;
    private final BoundedEventIdDeduper eventIdDeduper;

    public TrainerOrchestrator(TrainerProperties props,
                               ObjectMapper objectMapper,
                               TrainerMetrics metrics) {
        this.props = props;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.parser = new TrainingCandidateMessageParser(this.objectMapper);
        int cap = props.getBuffer().getMaxSamples() > 0 ? props.getBuffer().getMaxSamples() : 50_000;
        this.buffer = new BoundedIfSampleBuffer(cap);
        this.publisher = new FilesystemArtifactPublisher(this.objectMapper);
        this.metrics = metrics;
        int dedupCap = props.getDedup().getMaxRecentEventIds();
        this.eventIdDeduper = new BoundedEventIdDeduper(dedupCap);
    }

    public void handleKafkaMessage(String json) {
        metrics.received();
        try {
            TrainingCandidateRecord r = parser.parse(json);
            if (!props.getTenantId().equals(r.tenantId())) {
                metrics.wrongTenant();
                return;
            }
            if (!eventIdDeduper.firstTime(r.eventId())) {
                metrics.duplicateEventId();
                return;
            }
            if (r.compositeScore() < props.getAdmission().getMinCompositeScore()) {
                metrics.admissionRejected();
                return;
            }
            Double ifs = r.isolationForestScore();
            if (ifs != null && ifs > props.getAdmission().getMaxIsolationForestScore()) {
                metrics.admissionRejected();
                return;
            }
            buffer.offer(r.isolationForestFeatures());
        } catch (Exception e) {
            log.debug("Trainer dropped malformed candidate: {}", e.toString());
            metrics.malformed();
        }
    }

    @Scheduled(fixedDelayString = "${aisentinel.trainer.train.interval-millis:300000}")
    public void scheduledTrain() {
        runTrainCycle();
    }

    public void runTrainCycle() {
        int min = props.getTrain().getMinSamples() > 0 ? props.getTrain().getMinSamples() : 100;
        if (buffer.size() < min) {
            return;
        }
        List<double[]> snap = buffer.drainAll();
        long t0 = System.nanoTime();
        try {
            var ifp = props.getIfModel();
            IsolationForestTrainer trainer = new IsolationForestTrainer(
                ifp.getNumTrees(), ifp.getMaxDepth(), ifp.getRandomSeed());
            IsolationForestModel model = trainer.train(snap);
            if (model == null) {
                restoreSamples(snap);
                metrics.trainFailed(System.nanoTime() - t0);
                return;
            }
            byte[] payload = IsolationForestModelCodec.encode(model);
            String version = FilesystemArtifactPublisher.newVersionId();
            ModelArtifactMetadata meta = FilesystemArtifactPublisher.buildMetadata(
                props.getTenantId(),
                version,
                TrainingCandidateRecord.CURRENT_SCHEMA_VERSION,
                System.currentTimeMillis(),
                model.featureDimension(),
                ifp.getNumTrees(),
                ifp.getMaxDepth(),
                snap.size(),
                payload
            );
            Path root = Path.of(props.getRegistry().getFilesystemRoot());
            publisher.publish(props.getTenantId(), meta, payload, root);
            metrics.trainOk(System.nanoTime() - t0);
            metrics.published();
            log.info("Published IF artifact version={} samples={} to {}", version, snap.size(), root);
        } catch (Exception e) {
            restoreSamples(snap);
            log.warn("Trainer cycle failed: {}", e.toString());
            metrics.trainFailed(System.nanoTime() - t0);
        }
    }

    private void restoreSamples(List<double[]> snap) {
        for (double[] row : snap) {
            buffer.offer(row);
        }
    }
}
