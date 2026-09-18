package dev.aisentinel.core.scoring.shadow;

import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;
import dev.aisentinel.core.scoring.artifact.LoadedCandidateScorer;

import java.util.Objects;

/**
 * Callable shadow candidate bound to its provenance identity.
 * <p>
 * Binding a scorer here does not grant production decision authority.
 * {@code SHADOW RESULT != PRODUCTION DECISION}
 */
public final class ShadowCandidateBinding {

    private final AnomalyScorer scorer;
    private final CandidateScorerProvenance provenance;

    private ShadowCandidateBinding(AnomalyScorer scorer, CandidateScorerProvenance provenance) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
    }

    public static ShadowCandidateBinding fromReady(LoadedCandidateScorer loaded) {
        Objects.requireNonNull(loaded, "loaded");
        return new ShadowCandidateBinding(loaded.scorer(), loaded.provenance());
    }

    /**
     * Explicit binding for tests or adapters that already hold a READY scorer and
     * matching provenance. Prefer {@link #fromReady(LoadedCandidateScorer)} on the
     * request path.
     */
    public static ShadowCandidateBinding of(AnomalyScorer scorer, CandidateScorerProvenance provenance) {
        return new ShadowCandidateBinding(scorer, provenance);
    }

    public AnomalyScorer scorer() {
        return scorer;
    }

    public CandidateScorerProvenance provenance() {
        return provenance;
    }
}
