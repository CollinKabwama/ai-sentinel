package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes durable evaluator-provided (BYO) evaluation report artifacts into an output directory.
 */
final class EvaluatorProvidedEvaluationReportWriter {

    static final String KIT_RESULT_FILE_NAME = "kit-evaluation-result.json";
    static final String EVENT_INSPECTION_FILE_NAME = "event-inspection.json";
    static final String HTML_REPORT_FILE_NAME = "evaluation-report.html";

    EvaluatorProvidedEvaluationReportWriter() {
    }

    WrittenReports write(EvaluatorProvidedDatasetEvaluationResult result, Path outputDirectory)
        throws IOException {
        EvaluatorProvidedDatasetEvaluationResult safeResult = Objects.requireNonNull(result, "result");
        Path target = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("outputDirectory must be an existing directory: " + target);
        }

        String kitJson = EvaluatorProvidedEvaluationReportJson.kitResult(safeResult) + "\n";
        String eventsJson = EvaluatorProvidedEvaluationReportJson.eventInspection(safeResult) + "\n";
        String html = EvaluatorProvidedEvaluationReportHtml.render(safeResult) + "\n";

        Path kitPath = target.resolve(KIT_RESULT_FILE_NAME);
        Path eventsPath = target.resolve(EVENT_INSPECTION_FILE_NAME);
        Path htmlPath = target.resolve(HTML_REPORT_FILE_NAME);

        Files.writeString(kitPath, kitJson, StandardCharsets.UTF_8);
        Files.writeString(eventsPath, eventsJson, StandardCharsets.UTF_8);
        Files.writeString(htmlPath, html, StandardCharsets.UTF_8);

        return new WrittenReports(
            target,
            kitPath,
            eventsPath,
            htmlPath,
            kitJson.getBytes(StandardCharsets.UTF_8).length,
            eventsJson.getBytes(StandardCharsets.UTF_8).length,
            html.getBytes(StandardCharsets.UTF_8).length
        );
    }

    record WrittenReports(
        Path outputDirectory,
        Path kitResultJson,
        Path eventInspectionJson,
        Path htmlReport,
        int kitResultBytes,
        int eventInspectionBytes,
        int htmlReportBytes
    ) {
        WrittenReports {
            outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            kitResultJson = Objects.requireNonNull(kitResultJson, "kitResultJson");
            eventInspectionJson = Objects.requireNonNull(eventInspectionJson, "eventInspectionJson");
            htmlReport = Objects.requireNonNull(htmlReport, "htmlReport");
            if (kitResultBytes < 0 || eventInspectionBytes < 0 || htmlReportBytes < 0) {
                throw new IllegalArgumentException("byte counts must be non-negative");
            }
        }
    }
}
