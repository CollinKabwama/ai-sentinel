package dev.aisentinel.core.replay;

import dev.aisentinel.core.dataset.EvaluationDatasetManifest;

import java.util.List;
import java.util.Objects;

/**
 * Typed replay dataset plus optional annotation provenance.
 */
public record ReplayDataset(
    EvaluationDatasetManifest manifest,
    String eventsSha256,
    List<ReplaySourceEvent> events,
    AnnotationMetadata annotations
) {
    public ReplayDataset {
        manifest = Objects.requireNonNull(manifest, "manifest");
        if (eventsSha256 == null || !eventsSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("eventsSha256 must be 64 lowercase hex characters");
        }
        events = events == null ? List.of() : List.copyOf(events);
        annotations = annotations == null ? AnnotationMetadata.absent(manifest.datasetId()) : annotations;
    }

    public int eventCount() {
        return events.size();
    }

    public record ReplaySourceEvent(
        ReplayInputRecord replayInput,
        HistoricalReferenceOutput historicalOutput
    ) {
        public ReplaySourceEvent {
            replayInput = Objects.requireNonNull(replayInput, "replayInput");
            historicalOutput = Objects.requireNonNull(historicalOutput, "historicalOutput");
        }
    }

    public record AnnotationMetadata(
        String schemaVersion,
        String datasetId,
        int scenarioCount
    ) {
        public AnnotationMetadata {
            schemaVersion = schemaVersion == null ? "" : schemaVersion;
            datasetId = datasetId == null ? "" : datasetId;
            if (scenarioCount < 0) {
                throw new IllegalArgumentException("scenarioCount must be >= 0");
            }
        }

        static AnnotationMetadata absent(String datasetId) {
            return new AnnotationMetadata("", datasetId == null ? "" : datasetId, 0);
        }
    }
}
