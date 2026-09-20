package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.scoring.AnomalyScorer;

import java.util.Objects;

/**
 * A candidate scorer that has passed integrity verification and supported
 * construction. Bound to the exact validated descriptor and verified bytes.
 * <p>
 * {@code READY != ACCURATE}<br>
 * {@code MODEL AVAILABLE != MODEL APPROVED}<br>
 * {@code MODEL AVAILABLE != SHADOW ENABLED}<br>
 * {@code MODEL CANDIDATE != CHAMPION}
 */
public final class LoadedCandidateScorer {

    private final ScorerArtifactDescriptor descriptor;
    private final VerifiedArtifactBytes verifiedArtifact;
    private final AnomalyScorer scorer;
    private final CandidateScorerProvenance provenance;

    LoadedCandidateScorer(
        ScorerArtifactDescriptor descriptor,
        VerifiedArtifactBytes verifiedArtifact,
        AnomalyScorer scorer,
        CandidateScorerProvenance provenance
    ) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.verifiedArtifact = Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    public ScorerArtifactDescriptor descriptor() {
        return descriptor;
    }

    public VerifiedArtifactBytes verifiedArtifact() {
        return verifiedArtifact;
    }

    /**
     * Callable candidate scorer. Invoking it does not grant production decision
     * authority. Observational shadow scoring may bind this scorer only through
     * explicit {@code ShadowScoringExecutor} configuration
     * ({@code SHADOW RESULT != PRODUCTION DECISION}).
     */
    public AnomalyScorer scorer() {
        return scorer;
    }

    public CandidateScorerProvenance provenance() {
        return provenance;
    }
}
