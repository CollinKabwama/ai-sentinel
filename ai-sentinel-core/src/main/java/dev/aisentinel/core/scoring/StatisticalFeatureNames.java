package dev.aisentinel.core.scoring;

/**
 * Stable names for {@link RequestFeatures#toStatisticalArray()} dimensions (index-aligned).
 * Used for operator-facing statistical explanation only — not IF attribution.
 */
public final class StatisticalFeatureNames {

    public static final String[] NAMES = {
        "requestsPerWindow",
        "endpointEntropy",
        "endpointConcentration",
        "tokenAgeSeconds",
        "parameterCount",
        "payloadSizeBytes"
    };

    private StatisticalFeatureNames() {
    }

    public static String nameAt(int index) {
        if (index < 0 || index >= NAMES.length) {
            return "feature[" + index + "]";
        }
        return NAMES[index];
    }
}
