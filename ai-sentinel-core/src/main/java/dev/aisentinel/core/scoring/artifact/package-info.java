/**
 * Candidate scorer/model artifact contract, validation, and loading/health boundary.
 * <p>
 * Descriptor types describe immutable declared artifact metadata and validate
 * identity, integrity metadata, feature-schema binding, input requirements,
 * output compatibility, and capability claims.
 * <p>
 * Loading types verify actual artifact bytes against declared digests, construct
 * supported candidate scorers safely, represent operational readiness, and
 * contain candidate-specific failures. Descriptor acceptance does not imply
 * runtime availability; runtime readiness does not imply model quality, shadow
 * eligibility, or production authority.
 * <p>
 * {@code DESCRIPTOR VALID != RUNTIME AVAILABLE}<br>
 * {@code DIGEST METADATA VALID != ARTIFACT BYTES VERIFIED}<br>
 * {@code READY != APPROVED}<br>
 * {@code SCORER HEALTH != DETECTION QUALITY}
 */
package dev.aisentinel.core.scoring.artifact;
