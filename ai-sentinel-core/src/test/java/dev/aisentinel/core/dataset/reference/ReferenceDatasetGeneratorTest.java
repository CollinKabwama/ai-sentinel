package dev.aisentinel.core.dataset.reference;

import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.model.FeatureSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceDatasetGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generationIsDeterministicAndPrivacySafe() throws Exception {
        ReferenceDatasetGenerator generator = new ReferenceDatasetGenerator();
        Path firstDir = tempDir.resolve("first");
        Path secondDir = tempDir.resolve("second");

        ReferenceDatasetGenerator.GeneratedReferenceDataset first = generator.generate(firstDir);
        ReferenceDatasetGenerator.GeneratedReferenceDataset second = generator.generate(secondDir);

        assertThat(first.events()).hasSize(136);
        assertThat(first.scenarioCount()).isEqualTo(11);
        assertThat(first.identityCount()).isEqualTo(11);
        assertThat(first.eventsSha256()).isEqualTo(second.eventsSha256());
        assertThat(Files.readString(firstDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME)))
            .isEqualTo(Files.readString(secondDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME)));
        assertThat(Files.readString(firstDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME)))
            .isEqualTo(Files.readString(secondDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME)));
        assertThat(Files.readString(firstDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)))
            .isEqualTo(Files.readString(secondDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)));

        String events = Files.readString(firstDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME));
        assertThat(events)
            .doesNotContain("normal-established-baseline")
            .doesNotContain("SYNTHETIC_ANOMALOUS")
            .doesNotContain("LEGITIMATE_ANOMALOUS")
            .doesNotContain("Bearer ")
            .doesNotContain("\"authorization\"")
            .doesNotContain("\"cookie\"")
            .doesNotContain("@example.com")
            .doesNotContain("?token=");
    }

    @Test
    void trackedDatasetMatchesDeterministicRegeneration() throws Exception {
        Path tracked = repoRoot().resolve("evaluation/reference");
        ReferenceDatasetValidator.ValidationSummary summary = new ReferenceDatasetValidator().validate(tracked);

        assertThat(summary.eventCount()).isEqualTo(136);
        assertThat(summary.scenarioCount()).isEqualTo(11);
        assertThat(summary.identityCount()).isEqualTo(11);
        assertThat(summary.eventsSha256()).hasSize(64);
    }

    @Test
    void requiredInitialScenariosExist() throws Exception {
        ReferenceDatasetGenerator.GeneratedReferenceDataset dataset =
            new ReferenceDatasetGenerator().generate(tempDir.resolve("scenario-check"));

        assertThat(dataset.annotations().scenarios())
            .extracting(ReferenceDatasetScenarioAnnotation::id)
            .containsExactly(
                "normal-established-baseline",
                "warmup-new-identity",
                "rapid-request-burst",
                "endpoint-behavior-change",
                "payload-size-deviation",
                "parameter-count-deviation",
                "token-age-change",
                "low-variance-baseline-deviation",
                "gradual-behavior-change",
                "legitimate-bulk-operation",
                "interleaved-normal-identities"
            );

        assertThat(dataset.annotations().scenarios())
            .extracting(ReferenceDatasetScenarioAnnotation::expectedClass)
            .contains(ReferenceDatasetExpectedClass.NORMAL,
                ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
                ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS);
    }

    @Test
    void scenarioAnnotationsMatchGeneratedBehavior() throws Exception {
        ReferenceDatasetGenerator.GeneratedReferenceDataset dataset =
            new ReferenceDatasetGenerator().generate(tempDir.resolve("semantic-check"));
        Map<String, dev.aisentinel.core.contract.EvaluationEvent> events = dataset.events().stream()
            .collect(Collectors.toMap(dev.aisentinel.core.contract.EvaluationEvent::eventId, Function.identity()));
        Map<String, ReferenceDatasetScenarioAnnotation> scenarios = dataset.annotations().scenarios().stream()
            .collect(Collectors.toMap(ReferenceDatasetScenarioAnnotation::id, Function.identity()));

        assertScenarioFeatureChanges(scenarios.get("rapid-request-burst"), events, "requestsPerWindow");
        assertScenarioFeatureChanges(scenarios.get("endpoint-behavior-change"), events, "endpointEntropy");
        assertScenarioFeatureChanges(scenarios.get("endpoint-behavior-change"), events, "endpointConcentration");
        assertScenarioFeatureChanges(scenarios.get("payload-size-deviation"), events, "payloadSizeBytes");
        assertScenarioFeatureChanges(scenarios.get("parameter-count-deviation"), events, "parameterCount");
        assertScenarioFeatureChanges(scenarios.get("token-age-change"), events, "tokenAgeSeconds");
        assertScenarioFeatureChanges(scenarios.get("low-variance-baseline-deviation"), events, "payloadSizeBytes");
        assertScenarioFeatureChanges(scenarios.get("gradual-behavior-change"), events, "payloadSizeBytes");

        ReferenceDatasetScenarioAnnotation warmup = scenarios.get("warmup-new-identity");
        assertThat(warmup.expectedClass()).isEqualTo(ReferenceDatasetExpectedClass.NORMAL);
        assertThat(warmup.anomalyExpected()).isFalse();
        assertThat(warmup.maliciousnessAsserted()).isFalse();
        assertThat(warmup.eventIds()).allSatisfy(id ->
            assertThat(events.get(id).evaluationStatuses()).extracting(Enum::name).contains("STATISTICAL_WARMUP"));

        ReferenceDatasetScenarioAnnotation legitimate = scenarios.get("legitimate-bulk-operation");
        assertThat(legitimate.expectedClass()).isEqualTo(ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS);
        assertThat(legitimate.anomalyExpected()).isTrue();
        assertThat(legitimate.maliciousnessAsserted()).isFalse();

        ReferenceDatasetScenarioAnnotation interleaved = scenarios.get("interleaved-normal-identities");
        assertThat(interleaved.eventIds().stream().map(id -> events.get(id).identityKey()).toList())
            .containsExactly(
                "id:synthetic-010", "id:synthetic-011",
                "id:synthetic-010", "id:synthetic-011",
                "id:synthetic-010", "id:synthetic-011",
                "id:synthetic-010", "id:synthetic-011",
                "id:synthetic-010", "id:synthetic-011",
                "id:synthetic-010", "id:synthetic-011"
            );
        assertThat(interleaved.eventIds().stream()
            .map(id -> events.get(id).features().requestsPerWindow())
            .toList())
            .containsExactly(1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0, 5.0, 6.0, 6.0);
    }

    @Test
    void validatorRejectsCorruptedManifestRecordCount() throws Exception {
        Path datasetDir = tempDir.resolve("corrupt-manifest");
        new ReferenceDatasetGenerator().generate(datasetDir);
        Path manifest = datasetDir.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest).replace("\"recordCount\":136", "\"recordCount\":135"));

        assertThatThrownBy(() -> new ReferenceDatasetValidator().validate(datasetDir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recordCount");
    }

    @Test
    void validatorRejectsCorruptedAnnotationReference() throws Exception {
        Path datasetDir = tempDir.resolve("corrupt-annotations");
        new ReferenceDatasetGenerator().generate(datasetDir);
        Path annotations = datasetDir.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME);
        Files.writeString(annotations, Files.readString(annotations).replace("evt-ref-0136", "evt-ref-9999"));

        assertThatThrownBy(() -> new ReferenceDatasetValidator().validate(datasetDir))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validatorRejectsLabelLeakIntoEvents() throws Exception {
        Path datasetDir = tempDir.resolve("label-leak");
        new ReferenceDatasetGenerator().generate(datasetDir);
        Path events = datasetDir.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        Files.writeString(events, Files.readString(events).replace("evt-ref-0001", "SYNTHETIC_ANOMALOUS"));

        assertThatThrownBy(() -> new ReferenceDatasetValidator().validate(datasetDir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("label leaked");
    }

    private static void assertScenarioFeatureChanges(ReferenceDatasetScenarioAnnotation scenario,
                                                     Map<String, dev.aisentinel.core.contract.EvaluationEvent> events,
                                                     String feature) {
        List<Double> baseline = scenario.baselineEventIds().stream()
            .map(id -> featureValue(events.get(id).features(), feature))
            .toList();
        List<Double> evaluation = scenario.evaluationEventIds().stream()
            .map(id -> featureValue(events.get(id).features(), feature))
            .toList();
        assertThat(baseline).as(scenario.id() + " baseline " + feature).isNotEmpty();
        assertThat(evaluation).as(scenario.id() + " evaluation " + feature).isNotEmpty();
        assertThat(evaluation).as(scenario.id() + " changes " + feature)
            .isNotEqualTo(baseline.subList(Math.max(0, baseline.size() - Math.min(baseline.size(), evaluation.size())),
                baseline.size()));
    }

    private static double featureValue(FeatureSnapshot features, String feature) {
        return features.numericValue(feature);
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
