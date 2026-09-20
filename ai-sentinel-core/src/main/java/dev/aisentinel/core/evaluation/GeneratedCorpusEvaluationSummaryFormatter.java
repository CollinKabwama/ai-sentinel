package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic human-readable terminal summary for generated-corpus evaluation results.
 */
final class GeneratedCorpusEvaluationSummaryFormatter {

    private GeneratedCorpusEvaluationSummaryFormatter() {
    }

    static String format(
        GeneratedCorpusEvaluationResult result,
        Path evidenceDirectory,
        boolean temporaryEvidenceDirectory
    ) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");

        GeneratedCorpusProvenance provenance = result.provenance();
        GeneratedCorpusPhaseCounts phases = result.phaseCounts();
        DetectionEvaluationMetrics metrics = result.detectionRun().metrics();
        DetectionConfusionMatrix confusion = metrics.confusionMatrix();
        DetectionMetrics ratios = metrics.metrics();

        StringBuilder sb = new StringBuilder();
        sb.append("Generated corpus evaluation\n");
        sb.append("===========================\n");
        sb.append("Status: SUCCESS\n");
        sb.append('\n');

        sb.append("Corpus\n");
        sb.append("  corpusId: ").append(provenance.corpusId()).append('\n');
        sb.append("  scenarioId: ").append(provenance.scenarioId()).append('\n');
        sb.append("  scenarioVersion: ").append(provenance.scenarioVersion()).append('\n');
        sb.append("  representationMode: ").append(provenance.representationMode()).append('\n');
        sb.append("  eventCount: ").append(phases.totalEvents()).append('\n');
        sb.append('\n');

        sb.append("Phases\n");
        sb.append("  warmupEvents: ").append(phases.warmupEvents())
            .append(" (replayed for state-building; excluded from binary metrics)\n");
        sb.append("  labeledBenignEvents: ").append(phases.labeledBenignEvents()).append('\n');
        sb.append("  labeledAnomalousEvents: ").append(phases.labeledAnomalousEvents()).append('\n');
        sb.append("  unknownOrUnlabeledEvents: ").append(phases.unknownOrUnlabeledEvents())
            .append(" (replayed; excluded from binary metrics)\n");
        sb.append("  labeledEvaluationEvents: ").append(phases.labeledEvaluationEvents()).append('\n');
        sb.append('\n');

        sb.append("Detection observations (binary-labeled evaluation events only)\n");
        sb.append("  evaluablePredictions: ").append(metrics.evaluablePredictionCount()).append('\n');
        sb.append("  truePositives: ").append(confusion.truePositives()).append('\n');
        sb.append("  trueNegatives: ").append(confusion.trueNegatives()).append('\n');
        sb.append("  falsePositives: ").append(confusion.falsePositives()).append('\n');
        sb.append("  falseNegatives: ").append(confusion.falseNegatives()).append('\n');
        sb.append("  precision: ").append(formatMetric(ratios.precision())).append('\n');
        sb.append("  recall: ").append(formatMetric(ratios.recall())).append('\n');
        sb.append("  f1: ").append(formatMetric(ratios.f1())).append('\n');
        sb.append("  falsePositiveRate: ").append(formatMetric(ratios.falsePositiveRate())).append('\n');
        sb.append("  falseNegativeRate: ").append(formatMetric(ratios.falseNegativeRate())).append('\n');
        sb.append('\n');

        sb.append("Provenance (corpus)\n");
        sb.append("  seed: ").append(provenance.seed()).append('\n');
        sb.append("  generatorContractVersion: ").append(provenance.generatorContractVersion()).append('\n');
        sb.append("  generatorBuildId: ").append(provenance.generatorBuildId()).append('\n');
        sb.append("  featureSchemaVersion: ").append(provenance.featureSchemaVersion()).append('\n');
        sb.append("  evaluationEventSchemaVersion: ").append(provenance.evaluationEventSchemaVersion()).append('\n');
        sb.append('\n');

        // Not corpus provenance: caller-supplied (or defaulted) evaluation configuration for this
        // run only. Keeping it in a separate section avoids implying it is an authored corpus fact.
        sb.append("Evaluation configuration (this run only, not corpus provenance)\n");
        sb.append("  anomalyThreshold: ")
            .append(String.format(Locale.ROOT, "%.6f", metrics.classification().anomalyThreshold()))
            .append('\n');
        sb.append('\n');

        sb.append("Evidence\n");
        if (temporaryEvidenceDirectory) {
            sb.append("  directory: temporary (omitted from summary for path stability)\n");
        } else {
            sb.append("  directory: ").append(evidenceDirectory.toAbsolutePath().normalize()).append('\n');
        }
        sb.append('\n');

        sb.append("Limitations\n");
        for (String limitation : result.limitations()) {
            sb.append("  - ").append(limitation).append('\n');
        }
        sb.append("  - Controlled synthetic reference-evaluation observations only; not production efficacy.\n");
        return sb.toString();
    }

    private static String formatMetric(DetectionMetricValue value) {
        return GeneratedCorpusEvaluationCli.formatRatio(value);
    }
}
