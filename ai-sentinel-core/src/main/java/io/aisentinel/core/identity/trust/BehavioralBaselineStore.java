package io.aisentinel.core.identity.trust;

/**
 * Port for behavioral trust baseline state (in-process or optional Redis-backed implementation).
 */
public interface BehavioralBaselineStore {

    /**
     * @return previous entry for this key before this update, or {@code null} if none
     */
    BehavioralBaselineEntry updateAndGetPrevious(String key,
                                                 String endpoint,
                                                 long headerFingerprintHash,
                                                 int ipBucket,
                                                 long nowMillis);
}
