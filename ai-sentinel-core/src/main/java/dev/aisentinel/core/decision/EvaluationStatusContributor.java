package dev.aisentinel.core.decision;

import dev.aisentinel.core.model.RequestFeatures;

/**
 * Optional SPI for scorers and other evaluation components to report operational
 * {@link EvaluationStatus} markers without requiring status collection code to know concrete types.
 * <p>
 * Contributions are observability only: they must not fabricate risk scores, change policy actions,
 * or alter enforcement. A throwing contributor is treated as degraded status collection, not a
 * scoring failure.
 */
public interface EvaluationStatusContributor {

    void contributeEvaluationStatuses(RequestFeatures features, EvaluationStatusContributionContext context);
}
