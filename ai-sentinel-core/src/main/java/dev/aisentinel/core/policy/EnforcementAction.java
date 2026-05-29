package dev.aisentinel.core.policy;

/**
 * Policy action to apply based on risk score.
 */
public enum EnforcementAction {
    ALLOW,
    MONITOR,
    THROTTLE,
    BLOCK,
    QUARANTINE
}
