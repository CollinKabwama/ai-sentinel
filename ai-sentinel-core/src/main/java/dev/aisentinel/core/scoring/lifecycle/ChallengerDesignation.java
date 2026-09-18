package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;

/**
 * Explicit challenger designation. Does not approve or promote.
 * <p>
 * {@code ACCEPTED != CHALLENGER}<br>
 * {@code SHADOW ENABLED != CHALLENGER}
 */
public final class ChallengerDesignation {

    private final ModelLifecycleIdentity challenger;
    private final ModelLifecycleIdentity expectedChampion;
    private final String rationale;
    private final String designator;

    public ChallengerDesignation(
        ModelLifecycleIdentity challenger,
        ModelLifecycleIdentity expectedChampion,
        String rationale,
        String designator
    ) {
        this.challenger = Objects.requireNonNull(challenger, "challenger");
        this.expectedChampion = Objects.requireNonNull(expectedChampion, "expectedChampion");
        if (challenger.matches(expectedChampion)) {
            throw new IllegalArgumentException("challenger must differ from expected champion");
        }
        this.rationale = ModelPromotionDecision.requireGovernanceText(rationale, "rationale");
        this.designator = ModelPromotionDecision.requireGovernanceText(designator, "designator");
    }

    public ModelLifecycleIdentity challenger() {
        return challenger;
    }

    public ModelLifecycleIdentity expectedChampion() {
        return expectedChampion;
    }

    public String rationale() {
        return rationale;
    }

    public String designator() {
        return designator;
    }
}
