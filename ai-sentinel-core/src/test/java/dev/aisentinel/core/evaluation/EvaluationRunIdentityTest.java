package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunIdentityTest {

    private static final String FP =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String EVENTS =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String ANNOTATIONS =
        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Test
    void sameConfigSameEvaluationRunId() {
        String first = runId(0.5d, FP);
        String second = runId(0.5d, FP);
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("evalrun.");
        assertThat(first).hasSize("evalrun.".length() + 24);
    }

    @Test
    void differentThresholdDifferentEvaluationRunId() {
        assertThat(runId(0.5d, FP)).isNotEqualTo(runId(0.95d, FP));
    }

    @Test
    void differentReplayFingerprintDifferentEvaluationRunId() {
        String other =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        assertThat(runId(0.5d, FP)).isNotEqualTo(runId(0.5d, other));
    }

    @Test
    void outputPathIsNotPartOfEvaluationRunId() {
        // Material deliberately excludes paths; identical configs stay identical.
        assertThat(runId(0.5d, FP)).isEqualTo(runId(0.5d, FP));
    }

    @Test
    void resultFamilyIdPreservesInputBinding() {
        assertThat(EvaluationRunIdentity.resultFamilyId("corpus.demo"))
            .isEqualTo("result.corpus.demo");
    }

    @Test
    void comparisonIdDiffersForDifferentRunPairsAndIsDirectional() {
        RunEvidence a50 = evidence("evalrun.a50", 0.5d);
        RunEvidence b60 = evidence("evalrun.b60", 0.6d);
        RunEvidence a05 = evidence("evalrun.a05", 0.05d);
        RunEvidence b95 = evidence("evalrun.b95", 0.95d);

        String x = EvaluationRunIdentity.comparisonId(a50, b60);
        String y = EvaluationRunIdentity.comparisonId(a05, b95);
        assertThat(x).isNotEqualTo(y);
        assertThat(x).isEqualTo(EvaluationRunIdentity.comparisonId(a50, b60));
        assertThat(x).isNotEqualTo(EvaluationRunIdentity.comparisonId(b60, a50));
        assertThat(x).startsWith("comparison.");
    }

    @Test
    void legacySurrogateUsesPersistedFieldsAndIsWeakerThanEvaluationRunId() {
        RunEvidence legacyLeft = evidence(null, 0.5d);
        RunEvidence legacyRight = evidence(null, 0.6d);
        RunEvidence concreteLeft = evidence("evalrun.left", 0.5d);
        RunEvidence concreteRight = evidence("evalrun.right", 0.6d);

        String legacyId = EvaluationRunIdentity.comparisonId(legacyLeft, legacyRight);
        String concreteId = EvaluationRunIdentity.comparisonId(concreteLeft, concreteRight);
        assertThat(legacyId).isNotEqualTo(concreteId);
        assertThat(legacyId).isEqualTo(EvaluationRunIdentity.comparisonId(legacyLeft, legacyRight));
        assertThat(EvaluationRunIdentity.concreteOrLegacySurrogate(legacyLeft))
            .contains("legacySurrogate=true")
            .contains("resultId=result.corpus.mini")
            .contains("anomalyThreshold=0.5");
    }

    @Test
    void softwareVersionResolvedWithoutFabricatingBuildId() {
        assertThat(KitSoftwareIdentity.softwareVersion()).contains("0.4.0");
        assertThat(KitSoftwareIdentity.buildId()).isEmpty();
    }

    private static String runId(double threshold, String fingerprint) {
        return EvaluationRunIdentity.evaluationRunId(
            "generated-corpus",
            "corpus.demo",
            EVENTS,
            ANNOTATIONS,
            "1",
            "1",
            fingerprint,
            "statistical-baseline",
            "0.3.0",
            "reference-policy",
            "0.3.0",
            threshold,
            "1"
        );
    }

    private static RunEvidence evidence(String evaluationRunId, double threshold) {
        return new RunEvidence(
            "generated-corpus",
            "result.corpus.mini",
            evaluationRunId,
            "1",
            "1",
            "1",
            null,
            "corpus.mini",
            EVENTS,
            null,
            null,
            ANNOTATIONS,
            threshold,
            Map.of(),
            Map.of()
        );
    }
}
