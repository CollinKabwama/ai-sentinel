package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedCorpusEvaluationReportWriterTest {

    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(0.5);

    @TempDir
    Path tempDir;

    @Test
    void abruptBurstWritesSchemaShapedKitResultEventInspectionAndSelfContainedHtml() throws Exception {
        Path out = tempDir.resolve("abrupt-burst-reports");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.abrupt-burst"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );

        Path kitPath = out.resolve(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME);
        Path eventsPath = out.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME);
        Path htmlPath = out.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME);

        assertThat(Files.isRegularFile(kitPath)).isTrue();
        assertThat(Files.isRegularFile(eventsPath)).isTrue();
        assertThat(Files.isRegularFile(htmlPath)).isTrue();
        assertThat(Files.isRegularFile(out.resolve(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME))).isTrue();

        String kit = Files.readString(kitPath, StandardCharsets.UTF_8);
        assertThat(kit).contains("\"resultSchemaVersion\":\"1\"");
        assertThat(kit).contains("\"resultId\":\"result." + result.provenance().corpusId() + "\"");
        assertThat(kit).contains("\"evaluationRunId\":\"evalrun.");
        assertThat(kit).contains("\"softwareVersion\":\"0.4.0\"");
        assertThat(kit).contains("\"aiSentinelVersion\":\"0.3.0\"");
        assertThat(kit).doesNotContain("\"aiSentinelBuildId\"");
        assertThat(kit).contains("\"status\":\"completed_with_limitations\"");
        assertThat(kit).contains("\"datasetSource\":\"generated-corpus\"");
        assertThat(kit).contains("\"corpusId\":\"" + result.provenance().corpusId() + "\"");
        assertThat(kit).contains("\"scenarioId\":\"" + result.provenance().scenarioId() + "\"");
        assertThat(kit).contains("\"seed\":\"" + result.provenance().seed() + "\"");
        assertThat(kit).contains("\"structural\":{\"availability\":\"available\"");
        assertThat(kit).contains("\"eventCount\":10");
        assertThat(kit).contains("\"warmupEvents\":4");
        assertThat(kit).contains("\"detectionLabeled\":{\"availability\":\"available\"");
        assertThat(kit).contains("\"detectionEvidenceRef\":\"evaluation.json\"");
        assertThat(kit).contains("\"eventInspectionRef\":\"event-inspection.json\"");
        assertThat(kit).contains("\"htmlReportRef\":\"evaluation-report.html\"");
        assertThat(kit).contains("not production validation");
        assertThat(kit).doesNotContain("\"evaluationConfiguration\"");
        assertThat(kit).doesNotContain("\"phaseCounts\"");

        String events = Files.readString(eventsPath, StandardCharsets.UTF_8);
        assertThat(events).contains("\"inspectionSchemaVersion\":\"1\"");
        assertThat(events).contains("\"eventCount\":10");
        assertThat(events).contains("\"participation\":\"warmup\"");
        assertThat(events).contains("\"outcome\":\"excluded-warmup\"");
        assertThat(events).contains("\"participation\":\"binary-labeled\"");
        assertThat(result.eventInspections()).hasSize(10);
        assertThat(result.eventInspections().stream()
            .filter(row -> GeneratedCorpusEventInspection.PARTICIPATION_WARMUP.equals(row.participation()))
            .count()).isEqualTo(4);
        assertThat(result.eventInspections().stream()
            .filter(row -> GeneratedCorpusEventInspection.PARTICIPATION_BINARY_LABELED.equals(row.participation()))
            .count()).isEqualTo(6);

        String html = Files.readString(htmlPath, StandardCharsets.UTF_8);
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains(result.provenance().corpusId());
        assertThat(html).contains("Run summary");
        assertThat(html).contains("evaluationRunId");
        assertThat(html).contains("Corpus provenance");
        assertThat(html).contains("Evaluation configuration");
        assertThat(html).contains("Phase counts");
        assertThat(html).contains("Detection metrics");
        assertThat(html).contains("Scenario timeline");
        assertThat(html).contains("Event inspection");
        assertThat(html).contains("Sibling artifacts");
        assertThat(html).contains("Limitations");
        assertThat(html).contains("softwareVersion (packaging)");
        assertThat(html).contains("aiSentinelVersion (reference configuration)");
        assertThat(html).contains("not production validation");
        assertThat(html).contains("Ground truth is evaluation-layer metadata");
        assertThat(html).contains("Evaluated binary-labeled events");
        assertThat(html).doesNotContain("<script");
        int summaryAt = html.indexOf("id=\"summary\"");
        int provenanceAt = html.indexOf("id=\"provenance\"");
        assertThat(summaryAt).isGreaterThanOrEqualTo(0);
        assertThat(provenanceAt).isGreaterThan(summaryAt);
        assertNoInternalMetadata(html);
        assertNoInternalMetadata(kit);
        assertNoInternalMetadata(events);
    }

    @Test
    void establishedNormalMarksPositiveClassRatiosUnavailableWithoutFabricatingZeros() throws Exception {
        Path out = tempDir.resolve("established-normal-reports");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.established-normal"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );

        String kit = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME),
            StandardCharsets.UTF_8
        );
        assertThat(kit).contains("\"detectionLabeled\":{\"availability\":\"available\"");
        assertThat(kit).contains("\"precision\":null");
        assertThat(kit).contains("\"recall\":null");
        assertThat(kit).contains("\"truePositives\":0");
        assertThat(kit).contains("\"falsePositives\":0");

        String html = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME),
            StandardCharsets.UTF_8
        );
        assertThat(html).contains("precision");
        assertThat(html).contains("unavailable");
        assertThat(html).contains("Unavailable is not zero");
        assertThat(result.eventInspections()).hasSize(result.phaseCounts().totalEvents());
    }

    @Test
    void unknownUnlabeledEventsAppearInInspectionButNotAsBinaryOutcomes() throws Exception {
        Path out = tempDir.resolve("invalid-score-reports");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.invalid-score-degradation"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );

        assertThat(result.phaseCounts().unknownOrUnlabeledEvents()).isEqualTo(4);
        List<GeneratedCorpusEventInspection> unknown = result.eventInspections().stream()
            .filter(row -> GeneratedCorpusEventInspection.PARTICIPATION_UNKNOWN_UNLABELED.equals(row.participation()))
            .toList();
        assertThat(unknown).hasSize(4);
        assertThat(unknown).allSatisfy(row -> {
            assertThat(row.binaryMetricParticipant()).isFalse();
            assertThat(row.outcome()).isEqualTo("excluded-unknown-unlabeled");
        });

        String events = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME),
            StandardCharsets.UTF_8
        );
        assertThat(events).contains("\"outcome\":\"excluded-unknown-unlabeled\"");
        assertThat(countOccurrences(events, "\"outcome\":\"excluded-unknown-unlabeled\"")).isEqualTo(4);
    }

    @Test
    void reportProjectionIsDeterministicForSameCorpusAndThreshold() throws Exception {
        Path out1 = tempDir.resolve("det-report-1");
        Path out2 = tempDir.resolve("det-report-2");
        new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.legitimate-burst"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out1
        );
        new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.legitimate-burst"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out2
        );

        assertThat(Files.readString(out1.resolve(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME)))
            .isEqualTo(Files.readString(out2.resolve(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME)));
        assertThat(Files.readString(out1.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME)))
            .isEqualTo(Files.readString(out2.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME)));
        assertThat(Files.readString(out1.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME)))
            .isEqualTo(Files.readString(out2.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME)));
    }

    @Test
    void reportWriterProjectsExistingResultWithoutRequiringReEvaluation() throws Exception {
        Path evalOut = tempDir.resolve("source-eval");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.recovery"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            evalOut
        );

        Path reportOnly = Files.createDirectories(tempDir.resolve("report-only"));
        GeneratedCorpusEvaluationReportWriter.WrittenReports written =
            new GeneratedCorpusEvaluationReportWriter().write(result, reportOnly);

        assertThat(written.kitResultBytes()).isPositive();
        assertThat(written.eventInspectionBytes()).isPositive();
        assertThat(written.htmlReportBytes()).isPositive();
        assertThat(Files.readString(written.kitResultJson())).contains(result.provenance().corpusId());
    }

    @Test
    void hostileProvenanceValuesAreHtmlEscapedNotExecutedOrStructural() throws Exception {
        Path shuffledCorpus = tempDir.resolve("hostile-corpus");
        Files.createDirectories(shuffledCorpus);
        Path original = corpus("kit.established-normal");
        for (String file : List.of("events.jsonl", "annotations.json", "corpus-manifest.json", "manifest.json")) {
            Files.copy(original.resolve(file), shuffledCorpus.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        String payload = "x\\\"><script>alert(1)</script><img src=x onerror=alert(2)>&<b>bold</b>";
        Path manifest = shuffledCorpus.resolve("corpus-manifest.json");
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        String seedReplacement = Matcher.quoteReplacement("\"seed\":\"" + payload + "\"");
        String buildIdReplacement = Matcher.quoteReplacement("\"generatorBuildId\":\"" + payload + "\"");
        String injected = manifestText
            .replaceFirst("\"seed\":\"[^\"]*\"", seedReplacement)
            .replaceFirst("\"generatorBuildId\":\"[^\"]*\"", buildIdReplacement);
        Files.writeString(manifest, injected, StandardCharsets.UTF_8);

        Path out = tempDir.resolve("hostile-out");
        new GeneratedCorpusDetectionEvaluator().evaluate(
            shuffledCorpus, ReplayConfiguration.referenceDefaults(), THRESHOLD, out);

        String html = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME), StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<script>alert");
        assertThat(html).doesNotContain("onerror=alert(2)>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;img src=x onerror=alert(2)&gt;");
        assertThat(html).contains("&amp;&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void recoveryCategorySurvivesAsAuthoredKitCategoryNotHistoricalCompatibilityEnum() throws Exception {
        Path out = tempDir.resolve("recovery-category");
        new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus("kit.recovery"), ReplayConfiguration.referenceDefaults(), THRESHOLD, out);

        String events = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME), StandardCharsets.UTF_8);
        assertThat(events).contains("\"category\":\"recovery\"");
        assertThat(events).doesNotContain("ESTABLISHED_NORMAL_BASELINE");

        String html = Files.readString(
            out.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME), StandardCharsets.UTF_8);
        assertThat(html).contains(">recovery<");
        // The historical compatibility-mapped grouping label is allowed only in the clearly
        // labeled "compatibilityCategory" scenario-timeline column, never presented as the
        // corpus's own authored category.
        assertThat(html).contains("compatibilityCategory");
        assertThat(html).contains("historical metrics-grouping label, not the corpus's");
    }

    @Test
    void reorderingGroundTruthSidecarDoesNotReorderEventInspection() throws Exception {
        Path original = corpus("kit.abrupt-burst");
        Path out1 = tempDir.resolve("order-original");
        GeneratedCorpusEvaluationResult originalResult = new GeneratedCorpusDetectionEvaluator().evaluate(
            original, ReplayConfiguration.referenceDefaults(), THRESHOLD, out1);
        List<String> originalOrder = originalResult.eventInspections().stream()
            .map(GeneratedCorpusEventInspection::eventId)
            .toList();

        Path shuffledCorpus = tempDir.resolve("shuffled-corpus");
        Files.createDirectories(shuffledCorpus);
        for (String file : List.of("events.jsonl", "annotations.json", "corpus-manifest.json", "manifest.json")) {
            Files.copy(original.resolve(file), shuffledCorpus.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        String annotationsText = Files.readString(shuffledCorpus.resolve("annotations.json"), StandardCharsets.UTF_8);
        String reversedAnnotationsText = reverseAnnotationsArrayOrder(annotationsText);
        Files.writeString(shuffledCorpus.resolve("annotations.json"), reversedAnnotationsText, StandardCharsets.UTF_8);
        rewriteAnnotationsChecksum(shuffledCorpus);

        Path out2 = tempDir.resolve("order-shuffled");
        GeneratedCorpusEvaluationResult shuffledResult = new GeneratedCorpusDetectionEvaluator().evaluate(
            shuffledCorpus, ReplayConfiguration.referenceDefaults(), THRESHOLD, out2);
        List<String> shuffledOrder = shuffledResult.eventInspections().stream()
            .map(GeneratedCorpusEventInspection::eventId)
            .toList();

        assertThat(shuffledOrder).isEqualTo(originalOrder);
        assertThat(Files.readString(out2.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME)))
            .isEqualTo(Files.readString(out1.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME)));
    }

    private static String reverseAnnotationsArrayOrder(String annotationsJson) {
        Matcher matcher = Pattern.compile("\"annotations\":\\[(.*)]", Pattern.DOTALL).matcher(annotationsJson);
        if (!matcher.find()) {
            throw new IllegalStateException("Unable to locate annotations array");
        }
        String body = matcher.group(1);
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    objects.add(body.substring(start, i + 1));
                }
            }
        }
        Collections.reverse(objects);
        String rebuilt = "\"annotations\":[" + String.join(",", objects) + "]";
        return annotationsJson.substring(0, matcher.start()) + rebuilt
            + annotationsJson.substring(matcher.end());
    }

    private static void rewriteAnnotationsChecksum(Path corpusDir) throws Exception {
        Path annotations = corpusDir.resolve("annotations.json");
        String sha = sha256Hex(Files.readAllBytes(annotations));
        Path manifest = corpusDir.resolve("corpus-manifest.json");
        String text = Files.readString(manifest);
        String updated = text.replaceFirst(
            "(\"annotationsArtifact\":\\{[^}]*\"sha256\":\")[0-9a-f]{64}",
            "$1" + sha
        );
        if (updated.equals(text)) {
            throw new IllegalStateException("Unable to rewrite annotationsArtifact checksum");
        }
        Files.writeString(manifest, updated);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static Path corpus(String name) {
        return repoRoot().resolve("evaluation/kit-reference/corpora").resolve(name);
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path candidate = cwd.resolve("evaluation/kit-reference");
        if (Files.isDirectory(candidate)) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("evaluation/kit-reference"))) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate repository root from " + cwd);
    }

    private static void assertNoInternalMetadata(String text) {
        List<String> patterns = List.of(
            "ENG" + "-",
            "FIND" + "-",
            "TASK" + "-",
            "WAVE" + "-",
            "REVIEW" + "-",
            "REMEDI" + "ATION",
            "roadmap" + " task",
            "review" + " finding",
            "review" + " gate",
            "internal" + " task",
            "internal" + " tracker",
            "current" + " task",
            "next" + " task"
        );
        for (String pattern : patterns) {
            assertThat(text).doesNotContain(pattern);
        }
    }
}
