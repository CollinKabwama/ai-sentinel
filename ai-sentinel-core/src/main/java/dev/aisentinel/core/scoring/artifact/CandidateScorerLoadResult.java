package dev.aisentinel.core.scoring.artifact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured result of candidate scorer loading / readiness evaluation.
 * <p>
 * Distinct from {@link ScorerArtifactValidationResult} (descriptor metadata only)
 * and from request-path evaluation statuses.
 * <p>
 * Operational failure must not be represented as a fabricated anomaly score
 * ({@code UNAVAILABLE SCORE != SYNTHETIC SCORE}).
 */
public final class CandidateScorerLoadResult {

    private final CandidateScorerRuntimeStatus status;
    private final ScorerArtifactDescriptor descriptor;
    private final LoadedCandidateScorer loaded;
    private final CandidateScorerProvenance provenance;
    private final List<CandidateScorerLoadIssue> issues;

    private CandidateScorerLoadResult(
        CandidateScorerRuntimeStatus status,
        ScorerArtifactDescriptor descriptor,
        LoadedCandidateScorer loaded,
        CandidateScorerProvenance provenance,
        List<CandidateScorerLoadIssue> issues
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.descriptor = descriptor;
        this.loaded = loaded;
        this.provenance = provenance;
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (status == CandidateScorerRuntimeStatus.READY) {
            Objects.requireNonNull(loaded, "loaded");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(provenance, "provenance");
            if (!this.issues.isEmpty()) {
                throw new IllegalArgumentException("READY results must not carry failure issues");
            }
            if (!loaded.descriptor().equals(descriptor)) {
                throw new IllegalArgumentException("READY result descriptor must match loaded candidate descriptor");
            }
            if (loaded.provenance() != provenance) {
                throw new IllegalArgumentException("READY result provenance must match loaded candidate provenance");
            }
            if (!provenance.artifactBytesVerified()) {
                throw new IllegalArgumentException("READY results require verified artifact bytes");
            }
        } else if (loaded != null) {
            throw new IllegalArgumentException("non-READY results must not carry a loaded scorer");
        } else if (this.issues.isEmpty()) {
            throw new IllegalArgumentException("non-READY results must carry at least one issue");
        }
    }

    public static CandidateScorerLoadResult notConfigured() {
        return new CandidateScorerLoadResult(
            CandidateScorerRuntimeStatus.NOT_CONFIGURED,
            null,
            null,
            null,
            List.of(new CandidateScorerLoadIssue(
                CandidateScorerLoadFailureCode.NOT_CONFIGURED,
                "no candidate scorer was configured"
            ))
        );
    }

    static CandidateScorerLoadResult invalid(
        ScorerArtifactDescriptor descriptor,
        CandidateScorerProvenance provenance,
        List<CandidateScorerLoadIssue> issues
    ) {
        return new CandidateScorerLoadResult(
            CandidateScorerRuntimeStatus.INVALID,
            descriptor,
            null,
            provenance,
            issues
        );
    }

    static CandidateScorerLoadResult unavailable(
        ScorerArtifactDescriptor descriptor,
        CandidateScorerProvenance provenance,
        List<CandidateScorerLoadIssue> issues
    ) {
        return new CandidateScorerLoadResult(
            CandidateScorerRuntimeStatus.UNAVAILABLE,
            descriptor,
            null,
            provenance,
            issues
        );
    }

    static CandidateScorerLoadResult ready(LoadedCandidateScorer loaded) {
        return new CandidateScorerLoadResult(
            CandidateScorerRuntimeStatus.READY,
            loaded.descriptor(),
            loaded,
            loaded.provenance(),
            List.of()
        );
    }

    public CandidateScorerRuntimeStatus status() {
        return status;
    }

    public boolean ready() {
        return status == CandidateScorerRuntimeStatus.READY;
    }

    public Optional<ScorerArtifactDescriptor> descriptor() {
        return Optional.ofNullable(descriptor);
    }

    public Optional<LoadedCandidateScorer> loaded() {
        return Optional.ofNullable(loaded);
    }

    public Optional<CandidateScorerProvenance> provenance() {
        return Optional.ofNullable(provenance);
    }

    public List<CandidateScorerLoadIssue> issues() {
        return issues;
    }
}
