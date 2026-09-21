package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class EvaluationComparisonWriter {
    static final String JSON_FILE_NAME = "comparison.json";
    static final String HTML_FILE_NAME = "comparison.html";

    WrittenComparison write(EvaluationComparisonModel comparison, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            Path json = outputDirectory.resolve(JSON_FILE_NAME);
            Path html = outputDirectory.resolve(HTML_FILE_NAME);
            Files.writeString(json, EvaluationComparisonJson.render(comparison) + "\n",
                StandardCharsets.UTF_8);
            Files.writeString(html, EvaluationComparisonHtml.render(comparison) + "\n",
                StandardCharsets.UTF_8);
            return new WrittenComparison(json, html);
        } catch (IOException ex) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                "Unable to write comparison artifacts: " + ex.getMessage(), ex);
        }
    }

    record WrittenComparison(Path json, Path html) {
    }
}
