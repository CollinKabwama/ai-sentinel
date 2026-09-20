package dev.aisentinel.core.contract;

/**
 * Supported evaluation-event schema versions.
 */
public final class EvaluationEventSchemas {

    public static final String VERSION_1 = "1";
    public static final String CURRENT_VERSION = VERSION_1;

    private EvaluationEventSchemas() {
    }

    public static boolean supports(String version) {
        return CURRENT_VERSION.equals(version);
    }

    public static String requireSupported(String version) {
        if (!supports(version)) {
            throw new EvaluationContractException("Unsupported evaluation event schema version: " + version);
        }
        return CURRENT_VERSION;
    }
}
