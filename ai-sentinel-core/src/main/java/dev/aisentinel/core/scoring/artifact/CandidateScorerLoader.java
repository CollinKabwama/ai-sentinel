package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads a supported candidate scorer from a validated descriptor and artifact bytes.
 * <p>
 * Pipeline:
 * <ol>
 *   <li>descriptor validation</li>
 *   <li>bounded artifact acquisition (caller-supplied bytes)</li>
 *   <li>SHA-256 digest verification over those exact bytes</li>
 *   <li>format/type dispatch</li>
 *   <li>safe construction</li>
 *   <li>readiness representation</li>
 * </ol>
 * Verified bytes are defensively copied once and consumed from that copy
 * ({@code VERIFIED BYTES == CONSUMED BYTES}).
 * <p>
 * This is an in-process engineering failure-containment boundary, not OS process
 * isolation. Candidate failures remain candidate failures and must not become
 * attack evidence, policy actions, or fabricated scores.
 * <p>
 * Artifact bytes are supplied by the caller. This type does not perform filesystem
 * or network acquisition. Default AI-Sentinel behavior is unchanged when no
 * candidate is configured ({@link #notConfigured()}).
 */
public final class CandidateScorerLoader {

    private static final Logger log = LoggerFactory.getLogger(CandidateScorerLoader.class);

    private CandidateScorerLoader() {
    }

    /**
     * Operational representation when no candidate is configured.
     */
    public static CandidateScorerLoadResult notConfigured() {
        return CandidateScorerLoadResult.notConfigured();
    }

    /**
     * Validate descriptor metadata, verify artifact integrity, and construct a
     * supported candidate scorer when possible.
     * <p>
     * Never throws for malformed/incompatible candidate artifacts; failures are
     * returned as structured {@link CandidateScorerLoadResult} values. Does not
     * catch {@link Error}.
     *
     * @param descriptor validated or candidate-to-validate descriptor (re-validated here)
     * @param artifactBytes exact bytes to verify and consume; may be {@code null} when unavailable
     */
    public static CandidateScorerLoadResult load(ScorerArtifactDescriptor descriptor, byte[] artifactBytes) {
        if (descriptor == null) {
            return CandidateScorerLoadResult.invalid(
                null,
                null,
                List.of(issue(
                    CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED,
                    "candidate descriptor must not be null"
                ))
            );
        }

        ScorerArtifactValidationResult validation = ScorerArtifactValidator.validate(descriptor);
        if (validation.rejected()) {
            List<CandidateScorerLoadIssue> issues = new ArrayList<>();
            issues.add(issue(
                CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED,
                "candidate descriptor failed validation"
            ));
            for (ScorerArtifactValidationIssue validationIssue : validation.issues()) {
                issues.add(issue(
                    CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED,
                    validationIssue.code().name() + ": " + validationIssue.message()
                ));
            }
            log.info(
                "Candidate scorer load rejected: status={} scorerId={} artifactId={} failure={}",
                CandidateScorerRuntimeStatus.INVALID,
                descriptor.scorerId(),
                descriptor.artifactId(),
                CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED
            );
            return CandidateScorerLoadResult.invalid(
                descriptor,
                provenanceWithoutVerification(descriptor),
                issues
            );
        }

        if (artifactBytes == null) {
            return unavailable(
                descriptor,
                provenanceWithoutVerification(descriptor),
                CandidateScorerLoadFailureCode.ARTIFACT_UNAVAILABLE,
                "candidate artifact bytes were not supplied"
            );
        }

        if (artifactBytes.length == 0) {
            return invalid(
                descriptor,
                provenanceWithoutVerification(descriptor),
                CandidateScorerLoadFailureCode.ARTIFACT_EMPTY,
                "candidate artifact bytes must not be empty for loadable artifact types"
            );
        }

        if (artifactBytes.length > ArtifactByteIntegrity.MAX_CANDIDATE_ARTIFACT_BYTES) {
            return invalid(
                descriptor,
                provenanceWithoutVerification(descriptor),
                CandidateScorerLoadFailureCode.ARTIFACT_TOO_LARGE,
                "candidate artifact exceeds maximum supported size of "
                    + ArtifactByteIntegrity.MAX_CANDIDATE_ARTIFACT_BYTES + " bytes"
            );
        }

        // Copy once before hashing/construction so verified content cannot diverge
        // from later mutation of the caller's array.
        byte[] consumedBytes = ArtifactByteIntegrity.defensiveCopy(artifactBytes);

        ArtifactDigest declaredDigest = descriptor.artifactDigest();
        if (declaredDigest.algorithm() != ArtifactDigestAlgorithm.SHA_256) {
            return invalid(
                descriptor,
                provenanceWithoutVerification(descriptor),
                CandidateScorerLoadFailureCode.UNSUPPORTED_DIGEST_ALGORITHM,
                "candidate artifact byte verification currently supports SHA-256 only"
            );
        }

        String computedDigestHex = ArtifactByteIntegrity.sha256Hex(consumedBytes);
        if (!ArtifactByteIntegrity.digestsMatch(declaredDigest, computedDigestHex)) {
            log.info(
                "Candidate scorer load rejected: status={} scorerId={} artifactId={} failure={}",
                CandidateScorerRuntimeStatus.INVALID,
                descriptor.scorerId(),
                descriptor.artifactId(),
                CandidateScorerLoadFailureCode.DIGEST_MISMATCH
            );
            return CandidateScorerLoadResult.invalid(
                descriptor,
                new CandidateScorerProvenance(descriptor, false, computedDigestHex, ""),
                List.of(issue(
                    CandidateScorerLoadFailureCode.DIGEST_MISMATCH,
                    "artifact bytes do not match the SHA-256 digest declared by the validated descriptor"
                ))
            );
        }

        VerifiedArtifactBytes verified = VerifiedArtifactBytes.ofVerified(
            declaredDigest,
            computedDigestHex,
            consumedBytes
        );

        return constructFromVerified(descriptor, verified, consumedBytes);
    }

    private static CandidateScorerLoadResult constructFromVerified(
        ScorerArtifactDescriptor descriptor,
        VerifiedArtifactBytes verified,
        byte[] consumedBytes
    ) {
        String scorerType = descriptor.scorerType();
        if (ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1.equals(scorerType)) {
            if (!ScorerArtifactDescriptor.FORMAT_AIF1.equals(descriptor.artifactFormat())) {
                return unavailable(
                    descriptor,
                    new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
                    CandidateScorerLoadFailureCode.UNSUPPORTED_ARTIFACT_FORMAT,
                    "descriptor artifact format '" + descriptor.artifactFormat()
                        + "' is not supported for scorer type '" + scorerType + "'"
                );
            }
            return loadIsolationForest(descriptor, verified, consumedBytes);
        }
        if (ScorerArtifactDescriptor.TYPE_STATISTICAL.equals(scorerType)
            || ScorerArtifactDescriptor.TYPE_COMPOSITE.equals(scorerType)
            || ScorerArtifactDescriptor.TYPE_EXTERNAL.equals(scorerType)) {
            return unavailable(
                descriptor,
                new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
                CandidateScorerLoadFailureCode.UNSUPPORTED_RUNTIME_IMPLEMENTATION,
                "descriptor scorer type '" + scorerType
                    + "' has no candidate runtime loader implementation yet"
            );
        }
        return unavailable(
            descriptor,
            new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
            CandidateScorerLoadFailureCode.UNSUPPORTED_RUNTIME_IMPLEMENTATION,
            "no candidate runtime loader is registered for scorer type '" + scorerType + "'"
        );
    }

    private static CandidateScorerLoadResult loadIsolationForest(
        ScorerArtifactDescriptor descriptor,
        VerifiedArtifactBytes verified,
        byte[] consumedBytes
    ) {
        IsolationForestModel model;
        try {
            model = IsolationForestModelCodec.decode(consumedBytes);
        } catch (Exception ex) {
            log.info(
                "Candidate scorer load rejected: status={} scorerId={} artifactId={} failure={}",
                CandidateScorerRuntimeStatus.INVALID,
                descriptor.scorerId(),
                descriptor.artifactId(),
                CandidateScorerLoadFailureCode.ARTIFACT_DECODE_FAILED
            );
            return CandidateScorerLoadResult.invalid(
                descriptor,
                new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
                List.of(issue(
                    CandidateScorerLoadFailureCode.ARTIFACT_DECODE_FAILED,
                    "artifact bytes could not be decoded as an Isolation Forest AIF1 model"
                ))
            );
        }

        if (model.featureDimension() != descriptor.declaredFeatureDimension()) {
            return invalid(
                descriptor,
                new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
                CandidateScorerLoadFailureCode.FEATURE_DIMENSION_MISMATCH,
                "decoded model feature dimension "
                    + model.featureDimension()
                    + " does not match descriptor declaredFeatureDimension "
                    + descriptor.declaredFeatureDimension()
            );
        }

        try {
            CandidateIsolationForestScorer scorer = new CandidateIsolationForestScorer(model);
            CandidateScorerProvenance provenance = new CandidateScorerProvenance(
                descriptor,
                true,
                verified.computedDigestHex(),
                CandidateIsolationForestScorer.RUNTIME_IMPLEMENTATION_ID
            );
            LoadedCandidateScorer loaded = new LoadedCandidateScorer(
                descriptor,
                verified,
                scorer,
                provenance
            );
            log.info(
                "Candidate scorer load ready: status={} scorerId={} scorerVersion={} artifactId={} implementation={}",
                CandidateScorerRuntimeStatus.READY,
                descriptor.scorerId(),
                descriptor.scorerVersion(),
                descriptor.artifactId(),
                CandidateIsolationForestScorer.RUNTIME_IMPLEMENTATION_ID
            );
            return CandidateScorerLoadResult.ready(loaded);
        } catch (RuntimeException ex) {
            log.info(
                "Candidate scorer load rejected: status={} scorerId={} artifactId={} failure={}",
                CandidateScorerRuntimeStatus.INVALID,
                descriptor.scorerId(),
                descriptor.artifactId(),
                CandidateScorerLoadFailureCode.CONSTRUCTION_FAILURE
            );
            return CandidateScorerLoadResult.invalid(
                descriptor,
                new CandidateScorerProvenance(descriptor, true, verified.computedDigestHex(), ""),
                List.of(issue(
                    CandidateScorerLoadFailureCode.CONSTRUCTION_FAILURE,
                    "candidate scorer construction failed after artifact verification"
                ))
            );
        }
    }

    private static CandidateScorerProvenance provenanceWithoutVerification(ScorerArtifactDescriptor descriptor) {
        return new CandidateScorerProvenance(descriptor, false, "", "");
    }

    private static CandidateScorerLoadResult invalid(
        ScorerArtifactDescriptor descriptor,
        CandidateScorerProvenance provenance,
        CandidateScorerLoadFailureCode code,
        String message
    ) {
        log.info(
            "Candidate scorer load rejected: status={} scorerId={} artifactId={} failure={}",
            CandidateScorerRuntimeStatus.INVALID,
            descriptor.scorerId(),
            descriptor.artifactId(),
            code
        );
        return CandidateScorerLoadResult.invalid(descriptor, provenance, List.of(issue(code, message)));
    }

    private static CandidateScorerLoadResult unavailable(
        ScorerArtifactDescriptor descriptor,
        CandidateScorerProvenance provenance,
        CandidateScorerLoadFailureCode code,
        String message
    ) {
        log.info(
            "Candidate scorer load unavailable: status={} scorerId={} artifactId={} failure={}",
            CandidateScorerRuntimeStatus.UNAVAILABLE,
            descriptor.scorerId(),
            descriptor.artifactId(),
            code
        );
        return CandidateScorerLoadResult.unavailable(descriptor, provenance, List.of(issue(code, message)));
    }

    private static CandidateScorerLoadIssue issue(CandidateScorerLoadFailureCode code, String message) {
        return new CandidateScorerLoadIssue(code, message);
    }
}
