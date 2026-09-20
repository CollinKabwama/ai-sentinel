package dev.aisentinel.core.replay;

import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioAnnotation;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void trackedReferenceDatasetReplaysAndValidates() throws Exception {
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        ReplayDataset dataset = loadTrackedDataset();
        ReplayEngine.ReplayRun run = new ReplayEngine().run(dataset, configuration, tempDir.resolve("run"));
        ReplayOutputValidator.ValidationSummary validation =
            new ReplayOutputValidator().validate(tempDir.resolve("run"), dataset, configuration);

        assertThat(run.resultCount()).isEqualTo(dataset.eventCount());
        assertThat(validation.resultCount()).isEqualTo(dataset.eventCount());
        assertThat(run.results()).extracting(ReplayResult::eventId)
            .containsExactlyElementsOf(dataset.events().stream().map(e -> e.replayInput().eventId()).toList());
        assertThat(run.results()).allSatisfy(result -> assertThat(result.replayStatus()).isEqualTo(ReplayResultStatus.REPLAYED));
        assertThat(run.manifest().resultsSha256()).isEqualTo(validation.resultsSha256());
    }

    @Test
    void replayManifestIdentifiesEffectiveConfiguration() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReplayConfiguration defaults = ReplayConfiguration.referenceDefaults();
        ReplayConfiguration stricterThresholds = new ReplayConfiguration(
            defaults.replayMode(),
            defaults.aiSentinelVersion(),
            defaults.scorer(),
            new ReplayPolicyConfiguration(
                defaults.policy().policyId(),
                defaults.policy().policyVersion(),
                defaults.policy().evaluationMode(),
                0.1,
                defaults.policy().elevatedThreshold(),
                defaults.policy().highThreshold(),
                defaults.policy().criticalThreshold()
            )
        );

        ReplayEngine.ReplayRun run = new ReplayEngine().run(dataset, defaults, tempDir.resolve("config-default"));

        assertThat(defaults.configurationFingerprint()).hasSize(64);
        assertThat(defaults.configurationFingerprint()).isNotEqualTo(stricterThresholds.configurationFingerprint());
        assertThat(run.manifest().configurationFingerprint()).isEqualTo(defaults.configurationFingerprint());
        assertThat(ReplayEngine.deterministicRunId(dataset, defaults))
            .isNotEqualTo(ReplayEngine.deterministicRunId(dataset, stricterThresholds));
        assertThatThrownBy(() -> new ReplayOutputValidator()
            .validate(tempDir.resolve("config-default"), dataset, stricterThresholds))
            .isInstanceOf(ReplayException.class)
            .hasMessageContaining("configuration fingerprint");
    }

    @Test
    void identicalRunsProduceByteIdenticalArtifacts() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");

        new ReplayEngine().run(dataset, configuration, first);
        new ReplayEngine().run(dataset, configuration, second);

        assertThat(Files.readString(first.resolve(ReplaySchemas.RESULTS_FILE_NAME)))
            .isEqualTo(Files.readString(second.resolve(ReplaySchemas.RESULTS_FILE_NAME)));
        assertThat(Files.readString(first.resolve(ReplaySchemas.MANIFEST_FILE_NAME)))
            .isEqualTo(Files.readString(second.resolve(ReplaySchemas.MANIFEST_FILE_NAME)));
    }

    @Test
    void preservesWarmupAndScenarioContinuity() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        Map<String, ReplayResult> results = replayByEventId(dataset, ReplayConfiguration.referenceDefaults(), tempDir.resolve("continuity"));
        ReferenceDatasetScenarioAnnotation rapidBurst = generatedScenario("rapid-request-burst");

        assertThat(results.get("evt-ref-0001").evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_WARMUP);
        assertThat(results.get("evt-ref-0002").evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_WARMUP);
        assertThat(results.get("evt-ref-0003").evaluationStatuses()).doesNotContain(EvaluationStatus.STATISTICAL_WARMUP);

        ReplayResult firstBurst = results.get(rapidBurst.eventIds().get(0));
        assertThat(firstBurst.evaluationStatuses()).doesNotContain(EvaluationStatus.STATISTICAL_WARMUP);
        assertThat(firstBurst.identityKey()).isEqualTo("id:synthetic-001");
    }

    @Test
    void preservesIndependentIdentityStateAcrossInterleaving() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetScenarioAnnotation interleaved = generatedScenario("interleaved-normal-identities");
        List<ReplayResult> results = new ReplayEngine()
            .run(dataset, ReplayConfiguration.referenceDefaults(), tempDir.resolve("interleaved"))
            .results()
            .stream()
            .filter(result -> interleaved.eventIds().contains(result.eventId()))
            .toList();

        Map<String, List<ReplayResult>> byIdentity = results.stream()
            .collect(Collectors.groupingBy(ReplayResult::identityKey, java.util.LinkedHashMap::new, Collectors.toList()));

        assertThat(byIdentity.keySet()).containsExactly("id:synthetic-010", "id:synthetic-011");
        assertThat(byIdentity.get("id:synthetic-010").subList(0, 2))
            .allSatisfy(result -> assertThat(result.evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_WARMUP));
        assertThat(byIdentity.get("id:synthetic-011").subList(0, 2))
            .allSatisfy(result -> assertThat(result.evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_WARMUP));
        assertThat(byIdentity.get("id:synthetic-010").get(2).evaluationStatuses()).doesNotContain(EvaluationStatus.STATISTICAL_WARMUP);
        assertThat(byIdentity.get("id:synthetic-011").get(2).evaluationStatuses()).doesNotContain(EvaluationStatus.STATISTICAL_WARMUP);
    }

    @Test
    void historicalOutputsAreIgnoredAsReplayInputs() throws Exception {
        Path datasetDir = copyTrackedDataset("historical-mutation");
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        String mutated = Files.readString(events, StandardCharsets.UTF_8)
            .replaceFirst("\"scorerId\":\"statistical\"", "\"scorerId\":\"mutated-history\"")
            .replaceFirst("\"anomalyScore\":0\\.4", "\"anomalyScore\":0.99")
            .replaceFirst("\"policyScore\":0\\.4", "\"policyScore\":0.99")
            .replaceFirst("\"action\":\"MONITOR\"", "\"action\":\"THROTTLE\"")
            .replaceFirst("\\[\"STATISTICAL_WARMUP\"]", "[\"COMPLETE\"]")
            .replaceFirst("STATISTICAL_WARMUP", "MUTATED_OUTPUT");
        Files.writeString(events, mutated, StandardCharsets.UTF_8);
        updateManifestChecksum(datasetDir);

        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        ReplayDataset original = loadTrackedDataset();
        ReplayDataset mutatedDataset = new ReplayDatasetLoader().load(
            datasetDir,
            datasetDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)
        );

        Path originalOut = tempDir.resolve("historical-original");
        Path mutatedOut = tempDir.resolve("historical-mutated");
        List<ReplayResult> originalResults = new ReplayEngine().run(original, configuration, originalOut).results();
        List<ReplayResult> mutatedResults = new ReplayEngine().run(mutatedDataset, configuration, mutatedOut).results();

        assertThat(normalizeRunIds(originalResults)).isEqualTo(normalizeRunIds(mutatedResults));
    }

    @Test
    void annotationsAreValidatedButNotUsedForScoring() throws Exception {
        Path datasetDir = copyTrackedDataset("annotation-mutation");
        Path annotations = datasetDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME);
        String mutatedAnnotations = Files.readString(annotations, StandardCharsets.UTF_8)
            .replaceFirst("\"expectedClass\":\"NORMAL\"", "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\"")
            .replaceFirst("\"notes\":\"Stable repeated behavior after baseline formation for one user identity\\.\"",
                "\"notes\":\"mutated-annotation\"")
            .replaceFirst("\"anomalyExpected\":false", "\"anomalyExpected\":true");
        Files.writeString(annotations, mutatedAnnotations, StandardCharsets.UTF_8);

        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        ReplayDataset original = loadTrackedDataset();
        ReplayDataset mutatedDataset = new ReplayDatasetLoader().load(datasetDir, annotations);

        Path originalOut = tempDir.resolve("annotation-original");
        Path mutatedOut = tempDir.resolve("annotation-mutated");
        new ReplayEngine().run(original, configuration, originalOut);
        new ReplayEngine().run(mutatedDataset, configuration, mutatedOut);

        assertThat(Files.readString(originalOut.resolve(ReplaySchemas.RESULTS_FILE_NAME)))
            .isEqualTo(Files.readString(mutatedOut.resolve(ReplaySchemas.RESULTS_FILE_NAME)));
    }

    @Test
    void replayIsSensitiveToInputMutationAndOrdering() throws Exception {
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        ReplayDataset original = loadTrackedDataset();
        Path originalOut = tempDir.resolve("order-original");
        new ReplayEngine().run(original, configuration, originalOut);
        String originalResults = Files.readString(originalOut.resolve(ReplaySchemas.RESULTS_FILE_NAME));

        Path reorderedDir = copyTrackedDataset("reordered");
        reorderFirstTwoEvents(reorderedDir);
        updateManifestChecksum(reorderedDir);
        ReplayDataset reordered = new ReplayDatasetLoader().load(
            reorderedDir,
            reorderedDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)
        );
        Path reorderedOut = tempDir.resolve("order-reordered");
        new ReplayEngine().run(reordered, configuration, reorderedOut);

        Path mutatedFeatureDir = copyTrackedDataset("feature-mutation");
        mutateFirstFeature(mutatedFeatureDir);
        updateManifestChecksum(mutatedFeatureDir);
        ReplayDataset mutated = new ReplayDatasetLoader().load(
            mutatedFeatureDir,
            mutatedFeatureDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)
        );
        Path mutatedOut = tempDir.resolve("feature-mutated");
        new ReplayEngine().run(mutated, configuration, mutatedOut);

        assertThat(Files.readString(reorderedOut.resolve(ReplaySchemas.RESULTS_FILE_NAME))).isNotEqualTo(originalResults);
        assertThat(Files.readString(mutatedOut.resolve(ReplaySchemas.RESULTS_FILE_NAME))).isNotEqualTo(originalResults);
    }

    @Test
    void rejectsChecksumAndSchemaMismatches() throws Exception {
        Path checksumDir = copyTrackedDataset("checksum-mismatch");
        mutateFirstFeature(checksumDir);

        assertThatThrownBy(() -> new ReplayDatasetLoader().load(
            checksumDir, checksumDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isInstanceOf(ReplayException.class)
            .extracting(ex -> ((ReplayException) ex).kind())
            .isEqualTo(ReplayFailureKind.DATASET_VALIDATION_FAILURE);

        Path eventSchemaDir = copyTrackedDataset("event-schema");
        replaceInEvents(eventSchemaDir, text -> text.replaceFirst("\"eventSchemaVersion\":\"1\"", "\"eventSchemaVersion\":\"99\""));
        updateManifestChecksum(eventSchemaDir);
        assertThatThrownBy(() -> new ReplayDatasetLoader().load(
            eventSchemaDir, eventSchemaDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isInstanceOf(ReplayException.class)
            .extracting(ex -> ((ReplayException) ex).kind())
            .isEqualTo(ReplayFailureKind.INPUT_PARSE_FAILURE);

        Path featureSchemaDir = copyTrackedDataset("feature-schema");
        replaceInEvents(featureSchemaDir, text -> text.replaceFirst("\"featureSchemaVersion\":\"1\"", "\"featureSchemaVersion\":\"99\""));
        updateManifestChecksum(featureSchemaDir);
        assertThatThrownBy(() -> new ReplayDatasetLoader().load(
            featureSchemaDir, featureSchemaDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isInstanceOf(ReplayException.class)
            .extracting(ex -> ((ReplayException) ex).kind())
            .isEqualTo(ReplayFailureKind.INPUT_PARSE_FAILURE);

        Path datasetSchemaDir = copyTrackedDataset("dataset-schema");
        Path manifest = datasetSchemaDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest).replace("\"datasetSchemaVersion\":\"1\"", "\"datasetSchemaVersion\":\"99\""));
        assertThatThrownBy(() -> new ReplayDatasetLoader().load(
            datasetSchemaDir, datasetSchemaDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isInstanceOf(ReplayException.class)
            .extracting(ex -> ((ReplayException) ex).kind())
            .isEqualTo(ReplayFailureKind.INPUT_PARSE_FAILURE);

        Path eventsFileDir = copyTrackedDataset("events-file");
        Path eventsFileManifest = eventsFileDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        Files.writeString(eventsFileManifest, Files.readString(eventsFileManifest)
            .replace("\"eventsFile\":\"events.jsonl\"", "\"eventsFile\":\"other.jsonl\""));
        assertThatThrownBy(() -> new ReplayDatasetLoader().load(
            eventsFileDir, eventsFileDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isInstanceOf(ReplayException.class)
            .extracting(ex -> ((ReplayException) ex).kind())
            .isEqualTo(ReplayFailureKind.DATASET_VALIDATION_FAILURE);
    }

    @Test
    void invalidScoresPreserveFailOpenSemantics() throws Exception {
        Path oneEventDataset = copyTrackedDataset("invalid-score");
        trimDatasetToFirstEvent(oneEventDataset);
        updateManifestChecksumAndCount(oneEventDataset, 1);

        ReplayDataset dataset = new ReplayDatasetLoader().load(oneEventDataset);
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        AnomalyScorer invalidScorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return Double.NaN;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        ReplayEngine invalidEngine = new ReplayEngine(cfg -> new ReplayEngine.ReplayRuntime(
            invalidScorer,
            ReplayEngine.newDecisionEngine(cfg, invalidScorer)
        ));

        ReplayResult result = invalidEngine.run(dataset, configuration, tempDir.resolve("invalid-score-out")).results().getFirst();
        assertThat(result.anomalyScore()).isNull();
        assertThat(result.policyScore()).isNull();
        assertThat(result.action().name()).isEqualTo("ALLOW");
        assertThat(result.evaluationStatuses()).contains(EvaluationStatus.INVALID_SCORE);
    }

    @Test
    void replayDoesNotMutateLoadedFeatureSnapshots() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReplayInputRecord first = dataset.events().getFirst().replayInput();

        new ReplayEngine().run(dataset, ReplayConfiguration.referenceDefaults(), tempDir.resolve("immutability"));

        assertThat(dataset.events().getFirst().replayInput().features()).isEqualTo(first.features());
        assertThat(dataset.events().getFirst().replayInput().observedAt()).isEqualTo(first.observedAt());
    }

    private ReplayDataset loadTrackedDataset() throws IOException {
        return new ReplayDatasetLoader().load(
            repoRoot().resolve("evaluation/reference"),
            repoRoot().resolve("evaluation/reference").resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)
        );
    }

    private Map<String, ReplayResult> replayByEventId(ReplayDataset dataset,
                                                      ReplayConfiguration configuration,
                                                      Path outputDir) throws IOException {
        List<ReplayResult> results = new ReplayEngine().run(dataset, configuration, outputDir).results();
        Map<String, ReplayResult> byId = new HashMap<>();
        for (ReplayResult result : results) {
            byId.put(result.eventId(), result);
        }
        return byId;
    }

    private ReferenceDatasetScenarioAnnotation generatedScenario(String scenarioId) throws Exception {
        return new ReferenceDatasetGenerator()
            .generate(tempDir.resolve("generated-scenarios-" + scenarioId))
            .annotations()
            .scenarios()
            .stream()
            .filter(annotation -> annotation.id().equals(scenarioId))
            .findFirst()
            .orElseThrow();
    }

    private Path copyTrackedDataset(String name) throws IOException {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);
        Path source = repoRoot().resolve("evaluation/reference");
        Files.copy(source.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME), dir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME));
        Files.copy(source.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME), dir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME));
        Files.copy(source.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME), dir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME));
        return dir;
    }

    private static void reorderFirstTwoEvents(Path datasetDir) throws IOException {
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        String first = lines.get(0);
        lines.set(0, lines.get(1));
        lines.set(1, first);
        Files.write(events, lines, StandardCharsets.UTF_8);
    }

    private static void mutateFirstFeature(Path datasetDir) throws IOException {
        replaceInEvents(datasetDir, text -> text.replaceFirst("\"requestsPerWindow\":1\\.0", "\"requestsPerWindow\":9.0"));
    }

    private static void replaceInEvents(Path datasetDir, UnaryOperator<String> mutator) throws IOException {
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        Files.writeString(events, mutator.apply(Files.readString(events, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static void trimDatasetToFirstEvent(Path datasetDir) throws IOException {
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        List<String> lines = Files.readAllLines(events, StandardCharsets.UTF_8);
        Files.writeString(events, lines.getFirst() + "\n", StandardCharsets.UTF_8);
    }

    private static void updateManifestChecksum(Path datasetDir) throws IOException {
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        String sha = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(events));
        Path manifest = datasetDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8)
            .replaceAll("\"eventsSha256\":\"[0-9a-f]{64}\"", "\"eventsSha256\":\"" + sha + "\"");
        Files.writeString(manifest, manifestText, StandardCharsets.UTF_8);
    }

    private static void updateManifestChecksumAndCount(Path datasetDir, int recordCount) throws IOException {
        updateManifestChecksum(datasetDir);
        Path manifest = datasetDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8)
            .replaceAll("\"recordCount\":[0-9]+", "\"recordCount\":" + recordCount);
        Files.writeString(manifest, manifestText, StandardCharsets.UTF_8);
    }

    private static List<ReplayResult> normalizeRunIds(List<ReplayResult> results) {
        return results.stream()
            .map(result -> new ReplayResult(
                result.replaySchemaVersion(),
                "normalized-run-id",
                result.replayStatus(),
                result.sequenceNumber(),
                result.eventId(),
                result.correlationId(),
                result.identityKey(),
                result.identityType(),
                result.observedAt(),
                result.endpointKey(),
                result.featureSchemaVersion(),
                result.scorerId(),
                result.scorerVersion(),
                result.anomalyScore(),
                result.policyScore(),
                result.action(),
                result.evaluationStatuses(),
                result.riskFactors(),
                result.policyId(),
                result.policyVersion(),
                result.evaluationMode()
            ))
            .toList();
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
}
