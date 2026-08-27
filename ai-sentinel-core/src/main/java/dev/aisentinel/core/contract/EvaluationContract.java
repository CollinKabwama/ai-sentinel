package dev.aisentinel.core.contract;

/**
 * Shared bounds and version constants for the platform-neutral evaluation contract (v1).
 */
public final class EvaluationContract {
    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_STRING_LENGTH = 2048;
    public static final int MAX_PATH_LENGTH = 2048;
    public static final int MAX_HEADERS = 64;
    public static final int MAX_PARAMETERS = 128;
    public static final int MAX_ATTRIBUTES = 64;
    public static final int MAX_TRUST_SIGNALS = 16;

    private EvaluationContract() {
    }
}
