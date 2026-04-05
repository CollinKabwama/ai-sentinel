package io.aisentinel.distributed.training;

import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;

/**
 * Inputs for training candidate export, captured on the request thread after policy and enforcement.
 * The publisher must copy feature arrays before handing off to another thread.
 */
public final class TrainingCandidatePublishRequest {

    private final RequestFeatures features;
    private final EnforcementAction policyAction;
    private final double compositeScore;
    private final Double statisticalScore;
    private final Double isolationForestScore;
    private final EnforcementScope enforcementScope;
    private final String tenantId;
    private final String nodeId;
    private final String sentinelMode;
    private final boolean requestProceeded;
    private final boolean startupGraceActive;

    public TrainingCandidatePublishRequest(
        RequestFeatures features,
        EnforcementAction policyAction,
        double compositeScore,
        Double statisticalScore,
        Double isolationForestScore,
        EnforcementScope enforcementScope,
        String tenantId,
        String nodeId,
        String sentinelMode,
        boolean requestProceeded,
        boolean startupGraceActive
    ) {
        this.features = features;
        this.policyAction = policyAction;
        this.compositeScore = compositeScore;
        this.statisticalScore = statisticalScore;
        this.isolationForestScore = isolationForestScore;
        this.enforcementScope = enforcementScope != null ? enforcementScope : EnforcementScope.IDENTITY_ENDPOINT;
        this.tenantId = tenantId;
        this.nodeId = nodeId;
        this.sentinelMode = sentinelMode != null ? sentinelMode : "ENFORCE";
        this.requestProceeded = requestProceeded;
        this.startupGraceActive = startupGraceActive;
    }

    public RequestFeatures features() {
        return features;
    }

    public EnforcementAction policyAction() {
        return policyAction;
    }

    public double compositeScore() {
        return compositeScore;
    }

    public Double statisticalScore() {
        return statisticalScore;
    }

    public Double isolationForestScore() {
        return isolationForestScore;
    }

    public EnforcementScope enforcementScope() {
        return enforcementScope;
    }

    public String tenantId() {
        return tenantId;
    }

    public String nodeId() {
        return nodeId;
    }

    public String sentinelMode() {
        return sentinelMode;
    }

    public boolean requestProceeded() {
        return requestProceeded;
    }

    public boolean startupGraceActive() {
        return startupGraceActive;
    }
}
