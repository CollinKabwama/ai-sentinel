package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;

/**
 * Stable names for {@link RequestFeatures#toStatisticalArray()} dimensions (index-aligned).
 * Used for operator-facing statistical explanation only — not IF attribution.
 * Canonical contract: {@link FeatureSchema#STATISTICAL_FEATURE_NAMES}.
 */
public final class StatisticalFeatureNames {

    public static final String[] NAMES = FeatureSchema.STATISTICAL_FEATURE_NAMES.toArray(String[]::new);

    private StatisticalFeatureNames() {
    }

    public static String nameAt(int index) {
        if (index < 0 || index >= NAMES.length) {
            return "feature[" + index + "]";
        }
        return NAMES[index];
    }
}
