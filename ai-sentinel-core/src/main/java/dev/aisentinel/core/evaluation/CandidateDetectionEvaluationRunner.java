package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoadIssue;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoadResult;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoader;
import dev.aisentinel.core.scoring.artifact.LoadedCandidateScorer;
import dev.aisentinel.core.scoring.artifact.ScorerArtifactDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Takes a safely loaded candidate scorer through the existing deterministic
 * reference replay and detection-evaluation framework.
 * <p>
 * This produces candidate-specific evaluation evidence and, when an explicit
 * acceptance policy is supplied, an engineering acceptance assessment. It does
 * not approve production use, enable shadow scoring, select a champion, or
 * mutate the Official Detection Reference Baseline.
 * <p>
 * {@code EVALUATED CANDIDATE != ACCEPTED CANDIDATE}<br>
 * {@code ACCEPTED CANDIDATE != PRODUCTION MODEL}
 */
public final class CandidateDetectionEvaluationRunner {

    static final List<String> COMPLETED_LIMITATIONS = List.of(
        "EVALUATED CANDIDATE != APPROVED CANDIDATE",
        "EVALUATED CANDIDATE != ACCEPTED CANDIDATE",
        "EVALUATED CANDIDATE != SHADOW CANDIDATE",
        "EVALUATED CANDIDATE != CHAMPION",
        "EVALUATED CANDIDATE != PRODUCTION CANDIDATE",
        "EVALUATION COMPLETED != ACCEPTED",
        "FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE",
        "ACCEPTANCE POLICY != PRODUCTION POLICY",
        "ACCEPTANCE THRESHOLD != PRODUCTION THRESHOLD",
        "ACCEPTED CANDIDATE != CHAMPION",
        "ACCEPTED CANDIDATE != PROMOTED MODEL",
        "ACCEPTED CANDIDATE != PRODUCTION MODEL",
        "ACCEPTANCE != AUTOMATIC PROMOTION",
        "ACCEPTANCE != AUTOMATIC DEPLOYMENT",
        "METRIC IMPROVEMENT != AUTOMATIC ACCEPTANCE",
        "METRIC IMPROVEMENT != AUTOMATIC PROMOTION",
        "CANDIDATE EVALUATION != BASELINE PROMOTION",
        "REFERENCE THRESHOLD != PRODUCTION THRESHOLD",
        "REFERENCE THRESHOLD != ENFORCEMENT THRESHOLD",
        "REFERENCE THRESHOLD != OPTIMAL THRESHOLD",
        "CONFIGURATION FINGERPRINT != ARTIFACT DIGEST"
    );

    static final List<String> NOT_READY_LIMITATIONS = List.of(
        "CANDIDATE LOAD FAILURE != DETECTOR PREDICTION",
        "CANDIDATE LOAD FAILURE != ATTACK",
        "CANDIDATE LOAD FAILURE != ACCEPTANCE REJECTED",
        "EVALUATION FAILED != ACCEPTANCE REJECTED",
        "UNAVAILABLE EVALUATION != NEGATIVE PREDICTION",
        "UNAVAILABLE EVALUATION != POSITIVE PREDICTION",
        "Replay and detection evaluation were not executed."
    );

    private final CandidateDetectionEvaluationEvidenceWriter evidenceWriter =
        new CandidateDetectionEvaluationEvidenceWriter();

    /**
     * Load through {@link CandidateScorerLoader}, then evaluate only a READY candidate.
     * Non-ready loading produces structured evidence without fabricated predictions.
     */
    public CandidateDetectionEvaluationResult evaluate(
        ScorerArtifactDescriptor descriptor,
        byte[] artifactBytes,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        Path outputDirectory
    ) throws IOException {
        return evaluate(descriptor, artifactBytes, datasetDirectory, annotationsFile, classification, null, outputDirectory, false);
    }

    /**
     * Load through {@link CandidateScorerLoader}, evaluate only a READY candidate,
     * then assess the produced metrics against an explicit acceptance policy.
     */
    public CandidateDetectionEvaluationResult evaluate(
        ScorerArtifactDescriptor descriptor,
        byte[] artifactBytes,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy,
        Path outputDirectory
    ) throws IOException {
        return evaluate(
            descriptor,
            artifactBytes,
            datasetDirectory,
            annotationsFile,
            classification,
            Objects.requireNonNull(acceptancePolicy, "acceptancePolicy"),
            outputDirectory,
            true
        );
    }

    private CandidateDetectionEvaluationResult evaluate(
        ScorerArtifactDescriptor descriptor,
        byte[] artifactBytes,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy,
        Path outputDirectory,
        boolean policySupplied
    ) throws IOException {
        DetectionClassificationConfiguration safeClassification =
            Objects.requireNonNull(classification, "classification");
        Path safeOutput = requireOutputDirectory(outputDirectory);
        CandidateScorerLoadResult loadResult = CandidateScorerLoader.load(descriptor, artifactBytes);
        if (!loadResult.ready()) {
            CandidateDetectionEvaluationEvidence evidence =
                notReadyEvidence(loadResult, safeClassification, policySupplied ? acceptancePolicy : null);
            return new CandidateDetectionEvaluationResult(
                CandidateDetectionEvaluationStatus.CANDIDATE_NOT_READY,
                evidence,
                evidenceWriter.write(safeOutput, evidence),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            );
        }
        return evaluateReady(
            loadResult.loaded().orElseThrow(),
            datasetDirectory,
            annotationsFile,
            safeClassification,
            policySupplied ? acceptancePolicy : null,
            safeOutput,
            policySupplied
        );
    }

    /**
     * Evaluate an already loaded READY candidate. {@link LoadedCandidateScorer} can
     * only be constructed by the loader after successful verification.
     */
    public CandidateDetectionEvaluationResult evaluateReady(
        LoadedCandidateScorer loadedCandidate,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        Path outputDirectory
    ) throws IOException {
        return evaluateReady(loadedCandidate, datasetDirectory, annotationsFile, classification, null, outputDirectory, false);
    }

    /**
     * Evaluate an already loaded READY candidate and assess the produced metrics
     * against an explicit acceptance policy.
     */
    public CandidateDetectionEvaluationResult evaluateReady(
        LoadedCandidateScorer loadedCandidate,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy,
        Path outputDirectory
    ) throws IOException {
        return evaluateReady(
            loadedCandidate,
            datasetDirectory,
            annotationsFile,
            classification,
            Objects.requireNonNull(acceptancePolicy, "acceptancePolicy"),
            outputDirectory,
            true
        );
    }

    private CandidateDetectionEvaluationResult evaluateReady(
        LoadedCandidateScorer loadedCandidate,
        Path datasetDirectory,
        Path annotationsFile,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy,
        Path outputDirectory,
        boolean policySupplied
    ) throws IOException {
        LoadedCandidateScorer loaded = Objects.requireNonNull(loadedCandidate, "loadedCandidate");
        DetectionClassificationConfiguration safeClassification =
            Objects.requireNonNull(classification, "classification");
        Path safeOutput = requireOutputDirectory(outputDirectory);
        Path innerParent = Files.createTempDirectory("candidate-detection-evaluation-");
        Path innerOutput = innerParent.resolve("inner");
        try {
            DetectionEvaluationRunner runner =
                new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(loaded.scorer()));
            DetectionEvaluationRunner.DetectionEvaluationRun run = runner.evaluate(
                datasetDirectory,
                annotationsFile,
                CandidateReplayBindings.configuration(loaded.provenance()),
                safeClassification,
                innerOutput
            );
            CandidateDetectionEvaluationEvidence evidence = completedEvidence(
                loaded,
                run,
                safeClassification,
                policySupplied ? acceptancePolicy : null
            );
            return new CandidateDetectionEvaluationResult(
                CandidateDetectionEvaluationStatus.COMPLETED,
                evidence,
                evidenceWriter.write(safeOutput, evidence),
                Optional.of(run.alignment()),
                Optional.of(run.metrics()),
                Optional.of(run.temporal())
            );
        } finally {
            deleteRecursively(innerParent);
        }
    }

    private static CandidateDetectionEvaluationEvidence completedEvidence(
        LoadedCandidateScorer loaded,
        DetectionEvaluationRunner.DetectionEvaluationRun run,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy
    ) {
        return new CandidateDetectionEvaluationEvidence(
            CandidateDetectionEvaluationEvidenceValidator.EVIDENCE_SCHEMA_VERSION,
            CandidateDetectionEvaluationEvidenceValidator.REPORT_KIND,
            CandidateDetectionEvaluationStatus.COMPLETED,
            CandidateEvaluationProvenance.from(loaded.provenance()),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                classification.anomalyThreshold(),
                DetectionEvaluationEvidenceGenerator.THRESHOLD_BOUNDARY
            ),
            List.of(),
            run.evidence(),
            CandidateEvaluationAcceptanceAssessor.assess(acceptancePolicy, run.metrics()),
            COMPLETED_LIMITATIONS
        );
    }

    private static CandidateDetectionEvaluationEvidence notReadyEvidence(
        CandidateScorerLoadResult loadResult,
        DetectionClassificationConfiguration classification,
        CandidateEvaluationAcceptancePolicy acceptancePolicy
    ) {
        CandidateEvaluationProvenance provenance = loadResult.provenance()
            .map(CandidateEvaluationProvenance::from)
            .orElseGet(CandidateEvaluationProvenance::absent);
        List<CandidateLoadIssueRecord> issues = new ArrayList<>();
        for (CandidateScorerLoadIssue issue : loadResult.issues()) {
            issues.add(CandidateLoadIssueRecord.from(issue));
        }
        return new CandidateDetectionEvaluationEvidence(
            CandidateDetectionEvaluationEvidenceValidator.EVIDENCE_SCHEMA_VERSION,
            CandidateDetectionEvaluationEvidenceValidator.REPORT_KIND,
            CandidateDetectionEvaluationStatus.CANDIDATE_NOT_READY,
            provenance,
            new DetectionEvaluationEvidence.ClassificationProvenance(
                classification.anomalyThreshold(),
                DetectionEvaluationEvidenceGenerator.THRESHOLD_BOUNDARY
            ),
            issues,
            null,
            CandidateEvaluationAcceptanceAssessor.assess(acceptancePolicy, null),
            NOT_READY_LIMITATIONS
        );
    }

    static Path requireOutputDirectory(Path outputDirectory) {
        Path safe = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        rejectOfficialBaselineDirectory(safe);
        return safe;
    }

    static void rejectOfficialBaselineDirectory(Path outputDirectory) {
        String asString = outputDirectory.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (asString.endsWith("/evaluation/detection-reference-baseline")
            || asString.contains("/evaluation/detection-reference-baseline/")) {
            throw new IllegalArgumentException(
                "candidate evaluation must not write to the Official Detection Reference Baseline directory");
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    /**
     * Result of one candidate replay/evaluation attempt.
     */
    public record CandidateDetectionEvaluationResult(
        CandidateDetectionEvaluationStatus status,
        CandidateDetectionEvaluationEvidence evidence,
        CandidateDetectionEvaluationEvidenceWriter.WrittenEvidence writtenEvidence,
        Optional<ReferenceEvaluationAlignment> alignment,
        Optional<DetectionEvaluationMetrics> metrics,
        Optional<TemporalDetectionEvaluation> temporal
    ) {
        public CandidateDetectionEvaluationResult {
            status = Objects.requireNonNull(status, "status");
            evidence = Objects.requireNonNull(evidence, "evidence");
            writtenEvidence = Objects.requireNonNull(writtenEvidence, "writtenEvidence");
            alignment = Objects.requireNonNull(alignment, "alignment");
            metrics = Objects.requireNonNull(metrics, "metrics");
            temporal = Objects.requireNonNull(temporal, "temporal");
            if (status != evidence.status()) {
                throw new IllegalArgumentException("result status must match evidence status");
            }
            if (status == CandidateDetectionEvaluationStatus.COMPLETED) {
                if (alignment.isEmpty() || metrics.isEmpty() || temporal.isEmpty() || evidence.evaluation().isEmpty()) {
                    throw new IllegalArgumentException("COMPLETED results require nested evaluation evidence");
                }
            } else if (alignment.isPresent() || metrics.isPresent() || temporal.isPresent() || evidence.evaluation().isPresent()) {
                throw new IllegalArgumentException("non-COMPLETED results must not carry evaluation predictions");
            }
        }
    }
}
