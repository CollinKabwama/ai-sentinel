package dev.aisentinel.core.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two existing Evaluation Kit evidence directories without rerunning detection.
 */
public final class EvaluationComparisonEngine {

    public EvaluationComparisonResult compare(
        Path baselineRunDir,
        Path candidateRunDir,
        Path outputDirectory
    ) {
        Objects.requireNonNull(baselineRunDir, "baselineRunDir");
        Objects.requireNonNull(candidateRunDir, "candidateRunDir");
        Path output = Objects.requireNonNull(outputDirectory, "outputDirectory")
            .toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                "Comparison output directory already exists: " + output);
        }

        EvaluationComparisonLoader loader = new EvaluationComparisonLoader();
        RunEvidence baseline = loader.load(baselineRunDir, "baseline");
        RunEvidence candidate = loader.load(candidateRunDir, "candidate");
        validateCompatibility(baseline, candidate);
        EvaluationComparisonModel comparison = buildComparison(baseline, candidate);
        EvaluationComparisonWriter.WrittenComparison written =
            new EvaluationComparisonWriter().write(comparison, output);
        return new EvaluationComparisonResult(
            true,
            comparison.thresholdEqual(),
            written.json(),
            written.html(),
            comparison.eventsCompared(),
            comparison.eventChanges().size(),
            comparison.newFalsePositives(),
            comparison.newFalseNegatives()
        );
    }

    private static void validateCompatibility(RunEvidence baseline, RunEvidence candidate) {
        requireEqual("datasetSource", baseline.datasetSource(), candidate.datasetSource());
        requireEqual("resultSchemaVersion", baseline.resultSchemaVersion(), candidate.resultSchemaVersion());
        requireEqual("featureSchemaVersion", baseline.featureSchemaVersion(), candidate.featureSchemaVersion());
        requireEqual("evaluationEventSchemaVersion",
            baseline.evaluationEventSchemaVersion(), candidate.evaluationEventSchemaVersion());
        if (baseline.representationMode() != null || candidate.representationMode() != null) {
            requireEqual("representationMode", baseline.representationMode(), candidate.representationMode());
        }
        if (baseline.labeled() != candidate.labeled()) {
            incompatible("labeled and unlabeled evaluation evidence cannot be compared");
        }
        if ("generated-corpus".equals(baseline.datasetSource())) {
            requirePresent("corpusId", baseline.corpusId(), candidate.corpusId());
            requirePresent("corpusEventsSha256",
                baseline.corpusEventsSha256(), candidate.corpusEventsSha256());
            requireEqual("corpusId", baseline.corpusId(), candidate.corpusId());
            requireEqual("corpusEventsSha256",
                baseline.corpusEventsSha256(), candidate.corpusEventsSha256());
        } else {
            requirePresent("datasetId", baseline.datasetId(), candidate.datasetId());
            requirePresent("eventsSha256", baseline.eventsSha256(), candidate.eventsSha256());
            requireEqual("datasetId", baseline.datasetId(), candidate.datasetId());
            requireEqual("eventsSha256", baseline.eventsSha256(), candidate.eventsSha256());
        }
        if (baseline.labeled()) {
            requirePresent("annotationsSha256",
                baseline.annotationsSha256(), candidate.annotationsSha256());
        }
        requireEqual("annotationsSha256", baseline.annotationsSha256(), candidate.annotationsSha256());
        if (!baseline.events().keySet().equals(candidate.events().keySet())) {
            Set<String> baselineOnly = new TreeSet<>(baseline.events().keySet());
            baselineOnly.removeAll(candidate.events().keySet());
            Set<String> candidateOnly = new TreeSet<>(candidate.events().keySet());
            candidateOnly.removeAll(baseline.events().keySet());
            incompatible("eventId sets differ; baseline-only=" + baselineOnly
                + ", candidate-only=" + candidateOnly);
        }
        for (String eventId : baseline.events().keySet()) {
            EventEvidence left = baseline.events().get(eventId);
            EventEvidence right = candidate.events().get(eventId);
            if (!Objects.equals(left.expectedClass(), right.expectedClass())) {
                incompatible("expectedClass differs for eventId " + eventId);
            }
        }
    }

    private static EvaluationComparisonModel buildComparison(
        RunEvidence baseline, RunEvidence candidate
    ) {
        List<MetricChange> metrics = metricChanges(baseline, candidate);
        List<CountChange> counts = countChanges(metrics);
        Map<String, Integer> transitions = transitionMap();
        List<EventChange> events = new ArrayList<>();
        int newFalsePositives = 0;
        int newFalseNegatives = 0;
        List<EventEvidence> ordered = baseline.events().values().stream()
            .sorted(Comparator.comparingLong(EventEvidence::sequenceNumber)
                .thenComparing(EventEvidence::eventId))
            .toList();
        for (EventEvidence left : ordered) {
            EventEvidence right = candidate.events().get(left.eventId());
            List<String> changes = new ArrayList<>();
            if (!Objects.equals(left.anomalyScore(), right.anomalyScore())) {
                changes.add("score-changed");
            }
            if (!Objects.equals(left.predictedAnomalous(), right.predictedAnomalous())) {
                changes.add("prediction-changed");
            }
            if (!Objects.equals(left.action(), right.action())) {
                changes.add("action-changed");
            }
            if (!new LinkedHashSet<>(left.evaluationStatuses())
                .equals(new LinkedHashSet<>(right.evaluationStatuses()))) {
                changes.add("status-changed");
            }
            if (!Objects.equals(left.outcome(), right.outcome())) {
                changes.add("outcome-changed");
            }
            String leftClass = correctness(left);
            String rightClass = correctness(right);
            String transition = null;
            if (leftClass != null && rightClass != null) {
                transition = leftClass + "_TO_" + rightClass;
                if (transitions.containsKey(transition)) {
                    transitions.put(transition, transitions.get(transition) + 1);
                    if (!leftClass.equals(rightClass)) {
                        changes.add("correctness-transition");
                    } else {
                        transition = null;
                    }
                    if ("TN_TO_FP".equals(transition)) {
                        newFalsePositives++;
                    } else if ("TP_TO_FN".equals(transition)) {
                        newFalseNegatives++;
                    }
                } else {
                    transition = null;
                }
            }
            if (!changes.isEmpty()) {
                Double scoreDelta = left.anomalyScore() == null || right.anomalyScore() == null
                    ? null : right.anomalyScore() - left.anomalyScore();
                events.add(new EventChange(left.eventId(), left.sequenceNumber(), List.copyOf(changes),
                    left, right, scoreDelta, transition));
            }
        }
        boolean thresholdEqual =
            Double.compare(baseline.anomalyThreshold(), candidate.anomalyThreshold()) == 0;
        List<String> limitations = new ArrayList<>();
        if (!baseline.labeled()) {
            limitations.add("Labeled detection metrics and correctness transitions are unavailable.");
        }
        return new EvaluationComparisonModel(
            comparisonId(baseline.resultId(), candidate.resultId()),
            limitations.isEmpty() ? "completed" : "completed_with_limitations",
            List.copyOf(limitations),
            baseline,
            candidate,
            thresholdEqual,
            metrics,
            counts,
            Map.copyOf(transitions),
            List.copyOf(events),
            baseline.events().size(),
            newFalsePositives,
            newFalseNegatives
        );
    }

    private static List<MetricChange> metricChanges(
        RunEvidence baseline, RunEvidence candidate
    ) {
        List<MetricChange> changes = new ArrayList<>();
        for (String familyName : List.of("structural", "detectionLabeled", "temporal")) {
            MetricFamily left = baseline.metricFamilies().get(familyName);
            MetricFamily right = candidate.metricFamilies().get(familyName);
            if (left == null && right == null) {
                continue;
            }
            String leftAvailability = left == null ? "unavailable" : left.availability();
            String rightAvailability = right == null ? "unavailable" : right.availability();
            Set<String> names = new TreeSet<>();
            if (left != null) {
                names.addAll(left.values().keySet());
            }
            if (right != null) {
                names.addAll(right.values().keySet());
            }
            for (String name : names) {
                Double leftValue = left == null ? null : left.values().get(name);
                Double rightValue = right == null ? null : right.values().get(name);
                changes.add(new MetricChange(
                    familyName, name, leftAvailability, rightAvailability,
                    availabilityChange(leftAvailability, rightAvailability),
                    leftValue, rightValue,
                    leftValue == null || rightValue == null ? null : rightValue - leftValue
                ));
            }
        }
        return List.copyOf(changes);
    }

    private static List<CountChange> countChanges(List<MetricChange> metrics) {
        List<CountChange> changes = new ArrayList<>();
        for (MetricChange metric : metrics) {
            if (metric.baselineValue() != null && metric.candidateValue() != null
                && isIntegral(metric.baselineValue()) && isIntegral(metric.candidateValue())
                && isCountName(metric.metric())) {
                long left = metric.baselineValue().longValue();
                long right = metric.candidateValue().longValue();
                changes.add(new CountChange(metric.metric(), left, right, right - left));
            }
        }
        return List.copyOf(changes);
    }

    private static boolean isCountName(String name) {
        return name.endsWith("Count") || name.endsWith("Events") || name.startsWith("true")
            || name.startsWith("false") || name.equals("eventCount");
    }

    private static boolean isIntegral(double value) {
        return Math.rint(value) == value && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
    }

    private static String availabilityChange(String left, String right) {
        boolean leftAvailable = "available".equals(left);
        boolean rightAvailable = "available".equals(right);
        if (!leftAvailable && rightAvailable) {
            return "became-available";
        }
        if (leftAvailable && !rightAvailable) {
            return "became-unavailable";
        }
        return "unchanged";
    }

    private static String correctness(EventEvidence event) {
        if (!event.binaryMetricParticipant() || event.predictedAnomalous() == null) {
            return null;
        }
        return switch (event.expectedClass()) {
            case "anomalous" -> event.predictedAnomalous() ? "TP" : "FN";
            case "benign" -> event.predictedAnomalous() ? "FP" : "TN";
            default -> null;
        };
    }

    private static Map<String, Integer> transitionMap() {
        Map<String, Integer> transitions = new LinkedHashMap<>();
        for (String name : List.of("TP_TO_FN", "FN_TO_TP", "TN_TO_FP", "FP_TO_TN",
            "TP_TO_TP", "TN_TO_TN", "FP_TO_FP", "FN_TO_FN")) {
            transitions.put(name, 0);
        }
        return transitions;
    }

    private static String comparisonId(String baselineId, String candidateId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                (baselineId + "\n" + candidateId).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("comparison.");
            for (int i = 0; i < 12; i++) {
                value.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requirePresent(String field, String... values) {
        if (Arrays.stream(values).anyMatch(value -> value == null || value.isBlank())) {
            incompatible(field + " is required for comparison");
        }
    }

    private static void requireEqual(String field, Object baseline, Object candidate) {
        if (!Objects.equals(baseline, candidate)) {
            incompatible(field + " differs between baseline and candidate");
        }
    }

    private static void incompatible(String message) {
        throw new GeneratedCorpusEvaluationException(
            GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
            "Incompatible evaluation runs: " + message);
    }
}
