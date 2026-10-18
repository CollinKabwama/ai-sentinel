package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.evaluation.DetectionClassificationConfiguration;
import dev.aisentinel.core.evaluation.GeneratedCorpusDetectionEvaluator;
import dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationReportWriter;
import dev.aisentinel.core.evaluation.GeneratedCorpusEvaluationResult;
import dev.aisentinel.core.model.FeatureDefinition;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.replay.ReplayConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused coverage for the fictional Northgate organization-profile synthetic evaluation.
 * Does not extend compilers or invent new evaluation infrastructure.
 */
class NorthgateOrganizationProfileTest {

    private static final String BUILD_ID = "aisentinel-northgate-corpus@1";
    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(0.5);

    private static final List<ScenarioExpectation> EXPECTATIONS = List.of(
        new ScenarioExpectation(
            "northgate.established-normal.v1",
            "established-normal",
            "scenarios/northgate.established-normal.v1.json",
            "corpora/northgate.established-normal",
            "northgate-established-normal-1",
            "northgate-en-",
            Map.of(
                "northgate-en-001", 12,
                "northgate-en-002", 12,
                "northgate-en-003", 12,
                "northgate-en-004", 12
            ),
            Map.of(
                "northgate-en-001", 8,
                "northgate-en-002", 8,
                "northgate-en-003", 8,
                "northgate-en-004", 8
            ),
            Map.of(
                "warmup", "benign",
                "evaluation-normal", "benign"
            )
        ),
        new ScenarioExpectation(
            "northgate.legitimate-burst.v1",
            "legitimate-burst",
            "scenarios/northgate.legitimate-burst.v1.json",
            "corpora/northgate.legitimate-burst",
            "northgate-legitimate-burst-1",
            "northgate-lb-",
            Map.of(
                "northgate-lb-001", 12,
                "northgate-lb-002", 12,
                "northgate-lb-003", 12,
                "northgate-lb-004", 12
            ),
            Map.of(
                "northgate-lb-001", 8,
                "northgate-lb-002", 8,
                "northgate-lb-003", 8,
                "northgate-lb-004", 8
            ),
            Map.of(
                "warmup", "benign",
                "evaluation-normal", "benign",
                "legitimate-burst", "benign"
            )
        ),
        new ScenarioExpectation(
            "northgate.abrupt-burst.v1",
            "abrupt-burst",
            "scenarios/northgate.abrupt-burst.v1.json",
            "corpora/northgate.abrupt-burst",
            "northgate-abrupt-burst-1",
            "northgate-ab-",
            Map.of(
                "northgate-ab-001", 12,
                "northgate-ab-002", 12,
                "northgate-ab-003", 12,
                "northgate-ab-004", 12
            ),
            Map.of(
                "northgate-ab-001", 23,
                "northgate-ab-002", 3,
                "northgate-ab-003", 3,
                "northgate-ab-004", 3
            ),
            Map.of(
                "warmup", "benign",
                "evaluation-normal", "benign",
                "burst", "anomalous"
            )
        ),
        new ScenarioExpectation(
            "northgate.endpoint-shift.v1",
            "endpoint-distribution-change",
            "scenarios/northgate.endpoint-shift.v1.json",
            "corpora/northgate.endpoint-shift",
            "northgate-endpoint-shift-1",
            "northgate-ed-",
            Map.of(
                "northgate-ed-001", 12,
                "northgate-ed-002", 12,
                "northgate-ed-003", 12,
                "northgate-ed-004", 12
            ),
            Map.of(
                "northgate-ed-001", 32,
                "northgate-ed-002", 0,
                "northgate-ed-003", 0,
                "northgate-ed-004", 0
            ),
            Map.of(
                "warmup", "benign",
                "evaluation-normal", "benign",
                "endpoint-distribution-change", "anomalous"
            )
        ),
        new ScenarioExpectation(
            "northgate.session-recovery.v1",
            "recovery",
            "scenarios/northgate.session-recovery.v1.json",
            "corpora/northgate.session-recovery",
            "northgate-session-recovery-1",
            "northgate-rc-",
            Map.of(
                "northgate-rc-001", 12,
                "northgate-rc-002", 12,
                "northgate-rc-003", 12,
                "northgate-rc-004", 12
            ),
            Map.of(
                "northgate-rc-001", 32,
                "northgate-rc-002", 0,
                "northgate-rc-003", 0,
                "northgate-rc-004", 0
            ),
            Map.of(
                "warmup", "benign",
                "evaluation-normal", "benign",
                "burst", "anomalous",
                "recovery", "benign"
            )
        )
    );

    @TempDir
    Path tempDir;

    @Test
    void allFiveScenarioDocumentsParseWithExpectedFamilies() throws Exception {
        Path root = northgateRoot();
        for (ScenarioExpectation expectation : EXPECTATIONS) {
            String json = Files.readString(root.resolve(expectation.scenarioFile()), StandardCharsets.UTF_8);
            ScenarioDocument document = ScenarioDocumentParser.parse(json);
            assertThat(document.scenarioId()).isEqualTo(expectation.scenarioId());
            assertThat(document.family()).isEqualTo(expectation.family());
            assertThat(document.identityCount()).isEqualTo(4);
            assertThat(document.warmupDurationSeconds()).isEqualTo(48);
            assertThat(document.evaluationDurationSeconds()).isEqualTo(32);
            assertThat(document.identityKeyPrefix()).isEqualTo(expectation.identityPrefix());
            assertThat(document.endpointKeys()).isNotEmpty();
        }
    }

    @Test
    void generationIsDeterministicAndMatchesCheckedInCorpora() throws Exception {
        Path root = northgateRoot();
        for (ScenarioExpectation expectation : EXPECTATIONS) {
            Path scenarioPath = root.resolve(expectation.scenarioFile());
            Path firstDir = tempDir.resolve(expectation.scenarioId() + "-a");
            Path secondDir = tempDir.resolve(expectation.scenarioId() + "-b");

            GeneratedCorpus first = CorpusGenerator.generate(
                scenarioPath, expectation.seed(), BUILD_ID, firstDir);
            GeneratedCorpus second = CorpusGenerator.generate(
                scenarioPath, expectation.seed(), BUILD_ID, secondDir);

            assertThat(Files.readAllBytes(second.eventsPath())).isEqualTo(Files.readAllBytes(first.eventsPath()));
            assertThat(Files.readAllBytes(second.annotationsPath()))
                .isEqualTo(Files.readAllBytes(first.annotationsPath()));
            assertThat(Files.readAllBytes(second.corpusManifestPath()))
                .isEqualTo(Files.readAllBytes(first.corpusManifestPath()));

            Path checkedIn = root.resolve(expectation.corpusDirectory());
            assertThat(Files.readAllBytes(checkedIn.resolve("events.jsonl")))
                .isEqualTo(Files.readAllBytes(first.eventsPath()));
            assertThat(Files.readAllBytes(checkedIn.resolve("annotations.json")))
                .isEqualTo(Files.readAllBytes(first.annotationsPath()));
            assertThat(Files.readAllBytes(checkedIn.resolve("corpus-manifest.json")))
                .isEqualTo(Files.readAllBytes(first.corpusManifestPath()));
            assertThat(first.eventCount()).isEqualTo(80);
            assertThat(first.generatorBuildId()).isEqualTo(BUILD_ID);
        }
    }

    @Test
    void eventIdsAnnotationsFeaturesAndBaselineDepthHold() throws Exception {
        Path root = northgateRoot();
        for (ScenarioExpectation expectation : EXPECTATIONS) {
            Path corpusDir = root.resolve(expectation.corpusDirectory());
            List<EvaluationEvent> events = readEvents(corpusDir);
            Map<String, AnnotationRow> annotations = readAnnotations(corpusDir);

            assertThat(events).hasSize(80);
            assertThat(annotations).hasSize(80);

            Set<String> eventIds = new HashSet<>();
            Map<String, Integer> warmupByIdentity = new HashMap<>();
            Map<String, Integer> evaluationByIdentity = new HashMap<>();

            for (EvaluationEvent event : events) {
                assertThat(eventIds.add(event.eventId())).isTrue();
                assertThat(annotations).containsKey(event.eventId());

                AnnotationRow annotation = annotations.get(event.eventId());
                assertThat(expectation.categoryClass()).containsKey(annotation.category());
                assertThat(annotation.expectedClass())
                    .isEqualTo(expectation.categoryClass().get(annotation.category()));

                FeatureSnapshot features = event.features();
                assertThat(features).isNotNull();
                for (FeatureDefinition definition : FeatureSchema.CANONICAL_FEATURES) {
                    assertThat(Double.isFinite(features.numericValue(definition.name()))).isTrue();
                }
                assertThat(FeatureSchema.CANONICAL_FEATURES).hasSize(8);
                assertThat(features.parameterCount()).isGreaterThanOrEqualTo(0);
                assertThat(features.payloadSizeBytes()).isGreaterThanOrEqualTo(0L);
                assertThat(features.ipBucket()).isGreaterThanOrEqualTo(0);

                String line = EvaluationEventJson.write(event);
                assertThat(line).doesNotContain("expectedClass");
                assertThat(line).doesNotContain("evaluationExpectations");
                assertThat(line).doesNotContain("groundTruth");

                if ("warmup".equals(annotation.category())) {
                    warmupByIdentity.merge(event.identityKey(), 1, Integer::sum);
                } else {
                    evaluationByIdentity.merge(event.identityKey(), 1, Integer::sum);
                }
            }

            assertThat(warmupByIdentity).isEqualTo(expectation.warmupByIdentity());
            for (Map.Entry<String, Integer> entry : expectation.evaluationByIdentity().entrySet()) {
                assertThat(evaluationByIdentity.getOrDefault(entry.getKey(), 0))
                    .as("%s evaluation count", entry.getKey())
                    .isEqualTo(entry.getValue());
            }

            String eventsText = Files.readString(corpusDir.resolve("events.jsonl"), StandardCharsets.UTF_8);
            assertThat(eventsText.toLowerCase(Locale.ROOT)).doesNotContain("malicious");
        }
    }

    @Test
    void checkedInCorporaEvaluateThroughExistingEvaluationKit() throws Exception {
        Path root = northgateRoot();
        GeneratedCorpusDetectionEvaluator evaluator = new GeneratedCorpusDetectionEvaluator();
        for (ScenarioExpectation expectation : EXPECTATIONS) {
            Path corpusDir = root.resolve(expectation.corpusDirectory());
            Path out = tempDir.resolve(expectation.scenarioId() + "-eval");
            GeneratedCorpusEvaluationResult result = evaluator.evaluate(
                corpusDir,
                ReplayConfiguration.referenceDefaults(),
                THRESHOLD,
                out
            );
            assertThat(result.provenance().scenarioId()).isEqualTo(expectation.scenarioId());
            assertThat(result.provenance().generatorBuildId()).isEqualTo(BUILD_ID);
            assertThat(result.phaseCounts().totalEvents()).isEqualTo(80);
            assertThat(Files.isRegularFile(
                out.resolve(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME))).isTrue();
            assertThat(Files.isRegularFile(
                out.resolve(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME))).isTrue();
            assertThat(Files.isRegularFile(
                out.resolve(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME))).isTrue();
            assertThat(Files.isRegularFile(out.resolve("evaluation.json"))).isTrue();
            assertThat(Files.isRegularFile(out.resolve("evaluation.md"))).isTrue();
        }
    }

    @Test
    void generationSpecListsExactlyFiveNorthgateEntries() throws Exception {
        Path root = northgateRoot();
        String spec = Files.readString(root.resolve("generation-spec.json"), StandardCharsets.UTF_8);
        assertThat(spec).contains("\"inventoryId\": \"northgate-organization-profile\"");
        assertThat(spec).contains("\"generatorBuildId\": \"" + BUILD_ID + "\"");
        assertThat(spec).contains("fictional-organization-profile-synthetic");
        try (var stream = Files.list(root.resolve("corpora"))) {
            assertThat(stream.filter(Files::isDirectory).count()).isEqualTo(5);
        }
        assertThat(EXPECTATIONS).hasSize(5);
        for (ScenarioExpectation expectation : EXPECTATIONS) {
            assertThat(spec).contains(expectation.scenarioId());
            assertThat(Files.isDirectory(root.resolve(expectation.corpusDirectory()))).isTrue();
            assertThat(Files.isRegularFile(root.resolve(expectation.scenarioFile()))).isTrue();
        }
    }

    private static List<EvaluationEvent> readEvents(Path corpusDir) throws Exception {
        List<String> lines = Files.readAllLines(corpusDir.resolve("events.jsonl"), StandardCharsets.UTF_8);
        List<EvaluationEvent> events = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                events.add(EvaluationEventJson.parse(line));
            }
        }
        return events;
    }

    private static Map<String, AnnotationRow> readAnnotations(Path corpusDir) throws Exception {
        String json = Files.readString(corpusDir.resolve("annotations.json"), StandardCharsets.UTF_8);
        Map<String, AnnotationRow> rows = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile(
            "\\{\"eventId\":\"([^\"]+)\",\"scenarioId\":\"([^\"]+)\",\"expectedClass\":\"([^\"]+)\",\"category\":\"([^\"]+)\"\\}"
        ).matcher(json);
        while (matcher.find()) {
            rows.put(
                matcher.group(1),
                new AnnotationRow(matcher.group(1), matcher.group(3), matcher.group(4))
            );
        }
        return rows;
    }

    private static Path northgateRoot() {
        return repoRoot().resolve("evaluation/organization-profile/northgate");
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }

    private record ScenarioExpectation(
        String scenarioId,
        String family,
        String scenarioFile,
        String corpusDirectory,
        String seed,
        String identityPrefix,
        Map<String, Integer> warmupByIdentity,
        Map<String, Integer> evaluationByIdentity,
        Map<String, String> categoryClass
    ) {
    }

    private record AnnotationRow(String eventId, String expectedClass, String category) {
    }
}
