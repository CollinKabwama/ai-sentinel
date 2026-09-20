/**
 * Observational shadow scoring: execute an explicitly enabled, identity-bound
 * candidate beside the authoritative scorer and emit comparison telemetry.
 * <p>
 * {@code SHADOW RESULT != PRODUCTION DECISION}<br>
 * {@code SHADOW SCORE != AUTHORITATIVE SCORE}<br>
 * {@code ACCEPTANCE != AUTOMATIC SHADOW ENABLEMENT}<br>
 * {@code SHADOW ENABLED != CANDIDATE PROMOTED}<br>
 * {@code SHADOW OBSERVATION != TRAINING}<br>
 * {@code DISAGREEMENT != DEFECT}<br>
 * {@code DISAGREEMENT != GROUND TRUTH}
 * <p>
 * Candidate scores never enter policy, enforcement, baseline update, or
 * authoritative {@code RiskDecision} fields. Default configuration is disabled.
 */
package dev.aisentinel.core.scoring.shadow;
