package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateEvaluationAcceptancePolicyTest {

    @Test
    void emptyPolicyIsRejected() {
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder().build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one criterion");
    }

    @Test
    void invalidNumericConstraintsAreRejected() {
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(-1L)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumEvaluableObservations");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .maximumExcludedObservations(-1L)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumExcludedObservations");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumPrecision(1.1)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumPrecision");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumRecall(Double.NEGATIVE_INFINITY)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumRecall");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumF1(Double.NaN)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumF1");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .maximumFalsePositiveRate(-0.01)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumFalsePositiveRate");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .maximumFalseNegativeRate(2.0)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumFalseNegativeRate");
    }

    @Test
    void ratioBoundariesZeroAndOneAreAcceptedForEveryRatioCriterion() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumPrecision(0.0)
            .minimumRecall(0.0)
            .minimumF1(0.0)
            .maximumFalsePositiveRate(1.0)
            .maximumFalseNegativeRate(1.0)
            .build();

        assertThat(policy.minimumPrecision()).contains(0.0);
        assertThat(policy.minimumRecall()).contains(0.0);
        assertThat(policy.minimumF1()).contains(0.0);
        assertThat(policy.maximumFalsePositiveRate()).contains(1.0);
        assertThat(policy.maximumFalseNegativeRate()).contains(1.0);
    }

    @Test
    void nonFiniteRatioValuesAreRejectedForEveryRatioCriterion() {
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumPrecision(Double.NaN)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumPrecision");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumRecall(Double.POSITIVE_INFINITY)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumRecall");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .minimumF1(Double.NEGATIVE_INFINITY)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimumF1");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .maximumFalsePositiveRate(Double.NaN)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumFalsePositiveRate");
        assertThatThrownBy(() -> CandidateEvaluationAcceptancePolicy.builder()
            .maximumFalseNegativeRate(Double.POSITIVE_INFINITY)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximumFalseNegativeRate");
    }

    @Test
    void absentPolicyIsNotAssessed() {
        CandidateEvaluationAcceptanceAssessment assessment =
            CandidateEvaluationAcceptanceAssessor.assess(null, mixedMetrics());
        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.NOT_ASSESSED);
        assertThat(assessment.configuredPolicy()).isEmpty();
        assertThat(assessment.issues()).isEmpty();
    }

    @Test
    void missingMetricsAreNotAssessedEvenWhenPolicyIsPresent() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(1L)
            .build();
        CandidateEvaluationAcceptanceAssessment assessment =
            CandidateEvaluationAcceptanceAssessor.assess(policy, null);
        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.NOT_ASSESSED);
        assertThat(assessment.configuredPolicy()).contains(policy);
        assertThat(assessment.issues()).isEmpty();
    }

    @Test
    void satisfiedCriteriaAreAccepted() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(4L)
            .maximumExcludedObservations(2L)
            .minimumPrecision(0.5)
            .minimumRecall(0.5)
            .minimumF1(0.5)
            .maximumFalsePositiveRate(0.5)
            .maximumFalseNegativeRate(0.5)
            .build();
        CandidateEvaluationAcceptanceAssessment assessment = policy.assess(mixedMetrics());
        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.ACCEPTED);
        assertThat(assessment.issues()).isEmpty();
    }

    @Test
    void everyFailedCriterionIsReportedInDeterministicOrder() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(100L)
            .maximumExcludedObservations(0L)
            .minimumPrecision(1.0)
            .minimumRecall(1.0)
            .minimumF1(1.0)
            .maximumFalsePositiveRate(0.0)
            .maximumFalseNegativeRate(0.0)
            .build();
        CandidateEvaluationAcceptanceAssessment assessment = policy.assess(mixedMetrics());
        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.REJECTED);
        assertThat(assessment.issues())
            .extracting(CandidateEvaluationAcceptanceIssue::code)
            .containsExactly(
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_EVALUABLE_OBSERVATIONS_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MAXIMUM_EXCLUDED_OBSERVATIONS_EXCEEDED,
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_PRECISION_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_RECALL_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_F1_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_POSITIVE_RATE_EXCEEDED,
                CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_NEGATIVE_RATE_EXCEEDED
            );
        assertThat(assessment.issues().get(0).actual()).isEqualTo("4");
        assertThat(assessment.issues().get(0).required()).isEqualTo("100");
    }

    @Test
    void undefinedMetricsDoNotSatisfyConfiguredRatioCriteria() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumPrecision(0.0)
            .minimumRecall(0.0)
            .minimumF1(0.0)
            .maximumFalseNegativeRate(1.0)
            .build();
        CandidateEvaluationAcceptanceAssessment assessment = policy.assess(undefinedPositiveMetrics());
        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.REJECTED);
        assertThat(assessment.issues())
            .extracting(CandidateEvaluationAcceptanceIssue::code)
            .containsExactly(
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_PRECISION_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_RECALL_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MINIMUM_F1_NOT_MET,
                CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_NEGATIVE_RATE_EXCEEDED
            );
        assertThat(assessment.issues())
            .allSatisfy(issue -> assertThat(issue.actual()).isEqualTo("undefined"));
    }

    @Test
    void undefinedFalsePositiveRateDoesNotSatisfyConfiguredCriterion() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .maximumFalsePositiveRate(1.0)
            .build();
        CandidateEvaluationAcceptanceAssessment assessment = policy.assess(undefinedNegativeMetrics());

        assertThat(assessment.status()).isEqualTo(CandidateEvaluationAcceptanceStatus.REJECTED);
        assertThat(assessment.issues())
            .extracting(CandidateEvaluationAcceptanceIssue::code)
            .containsExactly(CandidateEvaluationAcceptanceIssueCode.MAXIMUM_FALSE_POSITIVE_RATE_EXCEEDED);
        assertThat(assessment.issues().get(0).actual()).isEqualTo("undefined");
    }

    @Test
    void assessmentRejectsImpossibleStatesAndCopiesIssueList() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(1L)
            .build();
        CandidateEvaluationAcceptanceIssue issue = new CandidateEvaluationAcceptanceIssue(
            CandidateEvaluationAcceptanceIssueCode.MINIMUM_EVALUABLE_OBSERVATIONS_NOT_MET,
            "0",
            "1",
            "evaluable observations 0 are below required minimum 1"
        );
        assertThatThrownBy(() -> new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.NOT_ASSESSED,
            policy,
            List.of(issue)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("NOT_ASSESSED");
        assertThatThrownBy(() -> new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.ACCEPTED,
            null,
            List.of()
        )).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("policy");
        assertThatThrownBy(() -> new CandidateEvaluationAcceptanceAssessment(
            CandidateEvaluationAcceptanceStatus.REJECTED,
            policy,
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires at least one issue");

        List<CandidateEvaluationAcceptanceIssue> mutableIssues = new ArrayList<>();
        mutableIssues.add(issue);
        CandidateEvaluationAcceptanceAssessment assessment =
            new CandidateEvaluationAcceptanceAssessment(CandidateEvaluationAcceptanceStatus.REJECTED, policy, mutableIssues);
        mutableIssues.clear();
        assertThat(assessment.issues()).containsExactly(issue);
    }

    @Test
    void exactBoundaryValuesPass() {
        DetectionEvaluationMetrics metrics = mixedMetrics();
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumPrecision(metrics.metrics().precision().value())
            .minimumRecall(metrics.metrics().recall().value())
            .minimumF1(metrics.metrics().f1().value())
            .maximumFalsePositiveRate(metrics.metrics().falsePositiveRate().value())
            .maximumFalseNegativeRate(metrics.metrics().falseNegativeRate().value())
            .build();
        assertThat(policy.assess(metrics).status()).isEqualTo(CandidateEvaluationAcceptanceStatus.ACCEPTED);
    }

    @Test
    void assessDoesNotInspectLabels() {
        CandidateEvaluationAcceptancePolicy policy = CandidateEvaluationAcceptancePolicy.builder()
            .minimumEvaluableObservations(1L)
            .build();
        DetectionEvaluationMetrics metrics = mixedMetrics();
        assertThat(policy.assess(metrics).status()).isEqualTo(CandidateEvaluationAcceptanceStatus.ACCEPTED);
        assertThat(metrics.confusionMatrix().truePositives() + metrics.confusionMatrix().falseNegatives())
            .isEqualTo(metrics.confusionMatrix().actualPositiveCount());
    }

    private static DetectionEvaluationMetrics mixedMetrics() {
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(1, 1, 1, 1);
        return metrics(confusion, 2L);
    }

    private static DetectionEvaluationMetrics undefinedPositiveMetrics() {
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(0, 4, 0, 0);
        return metrics(confusion, 0L);
    }

    private static DetectionEvaluationMetrics undefinedNegativeMetrics() {
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(1, 0, 0, 0);
        return metrics(confusion, 0L);
    }

    private static DetectionEvaluationMetrics metrics(DetectionConfusionMatrix confusion, long excluded) {
        long evaluable = confusion.totalCount();
        long total = evaluable + excluded;
        DetectionMetrics detectionMetrics = DetectionMetrics.from(confusion);
        return new DetectionEvaluationMetrics(
            "dataset-1",
            "replay-1",
            new DetectionClassificationConfiguration(0.5),
            total,
            evaluable,
            excluded,
            confusion,
            detectionMetrics,
            List.of(new ScenarioDetectionMetrics(
                "scenario-1",
                ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                total,
                evaluable,
                excluded,
                confusion,
                detectionMetrics
            ))
        );
    }
}
