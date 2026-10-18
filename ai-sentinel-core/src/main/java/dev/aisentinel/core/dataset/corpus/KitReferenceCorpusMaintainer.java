package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Regenerates and verifies the repository-owned versioned Kit reference evaluation corpus.
 * <p>
 * Maintenance utility for repository evidence — not a product CLI.
 */
final class KitReferenceCorpusMaintainer {

    private KitReferenceCorpusMaintainer() {
    }

    static Path root(Path repositoryRoot) {
        return Objects.requireNonNull(repositoryRoot, "repositoryRoot")
            .resolve(KitReferenceCorpusLayouts.ROOT_RELATIVE);
    }

    static void generate(Path repositoryRoot, Path outputRoot) throws IOException {
        Path sourceRoot = root(repositoryRoot);
        GenerationSpec spec = readGenerationSpec(sourceRoot.resolve("generation-spec.json"));
        Files.createDirectories(outputRoot);
        copyFile(sourceRoot.resolve("generation-spec.json"), outputRoot.resolve("generation-spec.json"));
        Path readme = sourceRoot.resolve("README.md");
        if (Files.isRegularFile(readme)) {
            copyFile(readme, outputRoot.resolve("README.md"));
        }

        Path scenariosOut = outputRoot.resolve(KitReferenceCorpusLayouts.SCENARIOS_DIR);
        Files.createDirectories(scenariosOut);
        List<InventoryEntry> entries = new ArrayList<>();

        for (GenerationEntry entry : spec.entries()) {
            Path scenarioSource = sourceRoot.resolve(entry.scenarioFile());
            if (!Files.isRegularFile(scenarioSource)) {
                throw new CorpusGeneratorException("Missing scenario file: " + scenarioSource);
            }
            String scenarioJson = Files.readString(scenarioSource, StandardCharsets.UTF_8);
            Path scenarioDest = outputRoot.resolve(entry.scenarioFile());
            Files.createDirectories(scenarioDest.getParent());
            Files.writeString(scenarioDest, scenarioJson, StandardCharsets.UTF_8);

            Path corpusDir = outputRoot.resolve(entry.corpusDirectory());
            deleteRecursively(corpusDir);
            GeneratedCorpus generated = CorpusGenerator.generate(
                scenarioJson,
                entry.seed(),
                spec.generatorBuildId(),
                corpusDir
            );

            ScenarioDocument scenario = ScenarioDocumentParser.parse(scenarioJson);
            if (!scenario.scenarioId().equals(entry.expectedScenarioId())
                && entry.expectedScenarioId() != null
                && !entry.expectedScenarioId().isBlank()) {
                throw new CorpusGeneratorException(
                    "Scenario id mismatch for " + entry.scenarioFile()
                        + ": expected " + entry.expectedScenarioId()
                        + " but parsed " + scenario.scenarioId());
            }

            int warmupEvents = countCategory(generated.annotationsPath(), "warmup");
            int evaluationEvents = generated.eventCount() - warmupEvents;
            entries.add(new InventoryEntry(
                scenario.scenarioId(),
                scenario.scenarioVersion(),
                scenario.family(),
                entry.scenarioFile(),
                generated.scenarioSha256(),
                entry.seed(),
                generated.corpusId(),
                entry.corpusDirectory(),
                generated.representationMode(),
                generated.eventCount(),
                warmupEvents,
                evaluationEvents,
                generated.eventsSha256(),
                generated.annotationsSha256(),
                sha256File(generated.corpusManifestPath()),
                sha256File(generated.replayManifestPath())
            ));
        }

        requireUniqueValues(entries, InventoryEntry::scenarioId, "scenarioId");
        requireUniqueValues(entries, InventoryEntry::corpusId, "corpusId");
        entries.sort(Comparator.comparing(InventoryEntry::scenarioId));
        String inventoryJson = writeInventoryJson(spec, entries);
        Files.writeString(
            outputRoot.resolve(KitReferenceCorpusLayouts.INVENTORY_FILE),
            inventoryJson,
            StandardCharsets.UTF_8
        );
        verifyInventoryAgainstDisk(outputRoot, spec, entries);
    }

    static void verify(Path repositoryRoot) throws IOException {
        Path tracked = root(repositoryRoot);
        Path temp = Files.createTempDirectory("kit-reference-verify-");
        try {
            generate(repositoryRoot, temp);
            assertTreesMatch(tracked, temp);
        } finally {
            deleteRecursively(temp);
        }
    }

    static GenerationSpec readGenerationSpec(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        String version = requireString(json, "generationSpecVersion");
        if (!"1".equals(version)) {
            throw new CorpusGeneratorException("Unsupported generationSpecVersion: " + version);
        }
        String inventoryId = requireString(json, "inventoryId");
        String inventoryVersion = requireString(json, "inventoryVersion");
        String generatorContractVersion = requireString(json, "generatorContractVersion");
        String generatorBuildId = requireString(json, "generatorBuildId");
        String representationMode = requireString(json, "representationMode");
        String entriesBody = requireArrayBody(json, "entries");
        List<String> objects = splitTopLevelObjects(entriesBody);
        if (objects.isEmpty()) {
            throw new CorpusGeneratorException("generation-spec entries must not be empty");
        }
        List<GenerationEntry> entries = new ArrayList<>();
        for (String object : objects) {
            entries.add(new GenerationEntry(
                requireString(object, "scenarioFile"),
                requireString(object, "corpusDirectory"),
                requireString(object, "seed"),
                optionalString(object, "scenarioId")
            ));
        }
        return new GenerationSpec(
            inventoryId,
            inventoryVersion,
            generatorContractVersion,
            generatorBuildId,
            representationMode,
            List.copyOf(entries)
        );
    }

    private static void requireUniqueValues(
        List<InventoryEntry> entries,
        java.util.function.Function<InventoryEntry, String> extractor,
        String fieldName
    ) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (InventoryEntry entry : entries) {
            String value = extractor.apply(entry);
            Integer previous = seen.putIfAbsent(value, 1);
            if (previous != null) {
                throw new CorpusGeneratorException("Duplicate " + fieldName + " across generation-spec entries: " + value);
            }
        }
    }

    private static void verifyInventoryAgainstDisk(
        Path root,
        GenerationSpec spec,
        List<InventoryEntry> entries
    ) throws IOException {
        Path inventoryPath = root.resolve(KitReferenceCorpusLayouts.INVENTORY_FILE);
        if (!Files.isRegularFile(inventoryPath)) {
            throw new CorpusGeneratorException("Missing inventory.json");
        }
        Map<String, Path> corpusDirs = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(root.resolve(KitReferenceCorpusLayouts.CORPORA_DIR))) {
            stream.filter(Files::isDirectory)
                .sorted()
                .forEach(path -> corpusDirs.put(
                    KitReferenceCorpusLayouts.CORPORA_DIR + "/" + path.getFileName(),
                    path));
        }
        if (corpusDirs.size() != entries.size()) {
            throw new CorpusGeneratorException(
                "Inventory/corpus directory count mismatch: inventory="
                    + entries.size() + " directories=" + corpusDirs.size());
        }
        for (InventoryEntry entry : entries) {
            if (!corpusDirs.containsKey(entry.corpusDirectory())) {
                throw new CorpusGeneratorException(
                    "Inventory references missing corpus directory: " + entry.corpusDirectory());
            }
            Path corpusDir = root.resolve(entry.corpusDirectory());
            GeneratedCorpus hint = new GeneratedCorpus(
                entry.corpusId(),
                entry.scenarioId(),
                entry.scenarioVersion(),
                entry.seed(),
                spec.generatorContractVersion(),
                spec.generatorBuildId(),
                entry.representationMode(),
                corpusDir,
                corpusDir.resolve(CorpusGeneratorSchemas.EVENTS_FILE_NAME),
                corpusDir.resolve(CorpusGeneratorSchemas.CORPUS_MANIFEST_FILE_NAME),
                corpusDir.resolve(CorpusGeneratorSchemas.ANNOTATIONS_FILE_NAME),
                corpusDir.resolve("manifest.json"),
                entry.eventsSha256(),
                entry.annotationsSha256(),
                entry.scenarioSha256(),
                entry.eventCount()
            );
            CorpusGenerator.verifyChecksums(hint);
            if (!entry.eventsSha256().equals(sha256File(hint.eventsPath()))) {
                throw new CorpusGeneratorException("Events checksum drift for " + entry.corpusId());
            }
            if (Files.lines(hint.eventsPath(), StandardCharsets.UTF_8).filter(l -> !l.isBlank()).count()
                != entry.eventCount()) {
                throw new CorpusGeneratorException("Event count mismatch for " + entry.corpusId());
            }
        }
        for (String dir : corpusDirs.keySet()) {
            boolean listed = entries.stream().anyMatch(e -> e.corpusDirectory().equals(dir));
            if (!listed) {
                throw new CorpusGeneratorException("Phantom corpus directory not in inventory: " + dir);
            }
        }
    }

    private static void assertTreesMatch(Path expectedRoot, Path actualRoot) throws IOException {
        Map<String, byte[]> expected = readTree(expectedRoot);
        Map<String, byte[]> actual = readTree(actualRoot);
        if (!expected.keySet().equals(actual.keySet())) {
            List<String> missing = expected.keySet().stream()
                .filter(key -> !actual.containsKey(key))
                .sorted()
                .toList();
            List<String> extra = actual.keySet().stream()
                .filter(key -> !expected.containsKey(key))
                .sorted()
                .toList();
            throw new CorpusGeneratorException(
                "Reference corpus tree mismatch. missing=" + missing + " extra=" + extra);
        }
        for (String relative : expected.keySet().stream().sorted().toList()) {
            if (!java.util.Arrays.equals(expected.get(relative), actual.get(relative))) {
                throw new CorpusGeneratorException(
                    "Reference corpus drift detected for file: " + relative);
            }
        }
    }

    private static Map<String, byte[]> readTree(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.put(relative, Files.readAllBytes(file));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String writeInventoryJson(GenerationSpec spec, List<InventoryEntry> entries) {
        StringBuilder json = new StringBuilder(2048);
        json.append('{');
        appendString(json, "inventorySchemaVersion", KitReferenceCorpusLayouts.INVENTORY_SCHEMA_VERSION, true);
        appendString(json, "inventoryId", spec.inventoryId(), false);
        appendString(json, "inventoryVersion", spec.inventoryVersion(), false);
        appendString(json, "generatorContractVersion", spec.generatorContractVersion(), false);
        appendString(json, "generatorBuildId", spec.generatorBuildId(), false);
        appendString(json, "representationMode", spec.representationMode(), false);
        json.append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            InventoryEntry entry = entries.get(i);
            json.append('{');
            appendString(json, "scenarioId", entry.scenarioId(), true);
            appendString(json, "scenarioVersion", entry.scenarioVersion(), false);
            appendString(json, "family", entry.family(), false);
            appendString(json, "scenarioPath", entry.scenarioPath(), false);
            appendString(json, "scenarioSha256", entry.scenarioSha256(), false);
            appendString(json, "seed", entry.seed(), false);
            appendString(json, "corpusId", entry.corpusId(), false);
            appendString(json, "corpusPath", entry.corpusDirectory(), false);
            appendString(json, "representationMode", entry.representationMode(), false);
            appendNumber(json, "eventCount", entry.eventCount(), false);
            appendNumber(json, "warmupEventCount", entry.warmupEventCount(), false);
            appendNumber(json, "evaluationEventCount", entry.evaluationEventCount(), false);
            appendString(json, "eventsSha256", entry.eventsSha256(), false);
            appendString(json, "annotationsSha256", entry.annotationsSha256(), false);
            appendString(json, "corpusManifestSha256", entry.corpusManifestSha256(), false);
            appendString(json, "replayManifestSha256", entry.replayManifestSha256(), false);
            json.append('}');
        }
        json.append(']');
        appendString(json, "notes",
            "Derived inventory of repository-owned Kit reference corpora. Synthetic evaluation evidence only.",
            false);
        json.append('}');
        json.append('\n');
        return json.toString();
    }

    private static int countCategory(Path annotationsPath, String category) throws IOException {
        String text = Files.readString(annotationsPath, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\"category\":\"" + Pattern.quote(category) + "\"").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String sha256File(Path path) throws IOException {
        return TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(path));
    }

    private static void copyFile(Path source, Path dest) throws IOException {
        Files.createDirectories(dest.getParent());
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing string field: " + field);
        }
        return matcher.group(1);
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String requireArrayBody(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing array field: " + field);
        }
        int open = matcher.end() - 1;
        String array = extractBalanced(json, open, '[', ']');
        return array.substring(1, array.length() - 1);
    }

    private static List<String> splitTopLevelObjects(String arrayBody) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayBody.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String extractBalanced(String json, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(openIndex, i + 1);
                }
            }
        }
        throw new CorpusGeneratorException("Unbalanced JSON structure");
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    record GenerationSpec(
        String inventoryId,
        String inventoryVersion,
        String generatorContractVersion,
        String generatorBuildId,
        String representationMode,
        List<GenerationEntry> entries
    ) {
    }

    record GenerationEntry(
        String scenarioFile,
        String corpusDirectory,
        String seed,
        String expectedScenarioId
    ) {
    }

    record InventoryEntry(
        String scenarioId,
        String scenarioVersion,
        String family,
        String scenarioPath,
        String scenarioSha256,
        String seed,
        String corpusId,
        String corpusDirectory,
        String representationMode,
        int eventCount,
        int warmupEventCount,
        int evaluationEventCount,
        String eventsSha256,
        String annotationsSha256,
        String corpusManifestSha256,
        String replayManifestSha256
    ) {
    }
}
