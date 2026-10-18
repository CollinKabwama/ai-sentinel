package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes durable generated-corpus evaluation report artifacts into an output directory.
 * <p>
 * Produces machine-readable Kit evaluation-result JSON, event-inspection JSON, and a
 * self-contained HTML report. Does not recompute detection metrics; projects existing
 * {@link GeneratedCorpusEvaluationResult} evidence.
 */
public final class GeneratedCorpusEvaluationReportWriter {

    public static final String KIT_RESULT_FILE_NAME = "kit-evaluation-result.json";
    public static final String EVENT_INSPECTION_FILE_NAME = "event-inspection.json";
    public static final String HTML_REPORT_FILE_NAME = "evaluation-report.html";

    public GeneratedCorpusEvaluationReportWriter() {
    }

    public WrittenReports write(GeneratedCorpusEvaluationResult result, Path outputDirectory) throws IOException {
        GeneratedCorpusEvaluationResult safeResult = Objects.requireNonNull(result, "result");
        Path target = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            throw new IllegalArgumentException("outputDirectory must be an existing directory: " + target);
        }

        String kitJson = GeneratedCorpusEvaluationReportJson.kitResult(safeResult) + "\n";
        String eventsJson = GeneratedCorpusEvaluationReportJson.eventInspection(safeResult) + "\n";
        String html = GeneratedCorpusEvaluationReportHtml.render(safeResult) + "\n";

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

    /**
     * Paths and sizes for generated-corpus report artifacts.
     */
    public record WrittenReports(
        Path outputDirectory,
        Path kitResultJson,
        Path eventInspectionJson,
        Path htmlReport,
        int kitResultBytes,
        int eventInspectionBytes,
        int htmlReportBytes
    ) {
        public WrittenReports {
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
