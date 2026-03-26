package io.aisentinel.core.enforcement;

/**
 * How throttle and quarantine state keys are derived from identity and endpoint.
 */
public enum EnforcementScope {

    /** Throttle and quarantine keys use identity only (shared across endpoints). */
    IDENTITY_GLOBAL,

    /** Throttle and quarantine keys use identity and endpoint together. */
    IDENTITY_ENDPOINT
}
