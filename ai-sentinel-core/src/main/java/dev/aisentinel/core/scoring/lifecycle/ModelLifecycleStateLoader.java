package dev.aisentinel.core.scoring.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort reconstruction of process-local governance state from a prior
 * filesystem publication. Not transactional; not distributed consensus.
 */
final class ModelLifecycleStateLoader {

    private static final Pattern STRING_FIELD = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private ModelLifecycleStateLoader() {
    }

    static LoadedState load(Path root) throws IOException {
        Path championPath = root.resolve(ModelLifecycleSchemas.CHAMPION_FILE);
        if (!Files.isRegularFile(championPath)) {
            return LoadedState.empty();
        }
        String championJson = Files.readString(championPath, StandardCharsets.UTF_8);
        requireSchemaVersion(championJson);
        ModelLifecycleIdentity champion = parseIdentityObject(championJson, "identity");

        ChallengerDesignation challenger = null;
        Path challengerPath = root.resolve(ModelLifecycleSchemas.CHALLENGER_FILE);
        if (Files.isRegularFile(challengerPath)) {
            String json = Files.readString(challengerPath, StandardCharsets.UTF_8);
            requireSchemaVersion(json);
            ModelLifecycleIdentity c = parseNestedIdentity(json, "challenger");
            ModelLifecycleIdentity expected = parseNestedIdentity(json, "expectedChampion");
            String rationale = requiredString(json, "rationale");
            String designator = requiredString(json, "designator");
            challenger = new ChallengerDesignation(c, expected, rationale, designator);
        }

        ModelPromotionDecision decision = null;
        Path decisionPath = root.resolve(ModelLifecycleSchemas.DECISION_FILE);
        if (Files.isRegularFile(decisionPath)) {
            String json = Files.readString(decisionPath, StandardCharsets.UTF_8);
            requireSchemaVersion(json);
            ModelPromotionDecisionStatus status =
                ModelPromotionDecisionStatus.valueOf(requiredString(json, "status"));
            if (status == ModelPromotionDecisionStatus.NOT_DECIDED) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                    "persisted decision must be APPROVED or REJECTED"
                );
            }
            ModelLifecycleIdentity atDecision = parseNestedIdentity(json, "championAtDecision");
            ModelLifecycleIdentity decisionChallenger = parseNestedIdentity(json, "challenger");
            String comparisonSha = requiredString(json, "comparisonSha256Hex");
            String rationale = requiredString(json, "rationale");
            String approver = requiredString(json, "approver");
            decision = status == ModelPromotionDecisionStatus.APPROVED
                ? ModelPromotionDecision.approved(atDecision, decisionChallenger, comparisonSha, rationale, approver)
                : ModelPromotionDecision.rejected(atDecision, decisionChallenger, comparisonSha, rationale, approver);
            String expectedSha = requiredString(json, "decisionSha256Hex");
            if (!expectedSha.equals(decision.decisionSha256Hex())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                    "decision file hash does not match reconstructed decision"
                );
            }
        }

        List<ModelLifecycleHistoryEntry> history = new ArrayList<>();
        long nextSequence = 0L;
        Path historyDir = root.resolve(ModelLifecycleSchemas.HISTORY_DIRECTORY);
        if (Files.isDirectory(historyDir)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "*.json")) {
                for (Path p : stream) {
                    files.add(p);
                }
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path file : files) {
                String name = file.getFileName().toString();
                String json = Files.readString(file, StandardCharsets.UTF_8);
                requireSchemaVersion(json);
                long sequence = parseSequence(name);
                nextSequence = Math.max(nextSequence, sequence + 1L);
                if (name.startsWith("promotion-")) {
                    history.add(ModelLifecycleHistoryEntry.promotion(parsePromotion(json), sequence));
                } else if (name.startsWith("rollback-")) {
                    history.add(ModelLifecycleHistoryEntry.rollback(parseRollback(json), sequence));
                }
            }
        }

        return new LoadedState(champion, challenger, decision, history, nextSequence);
    }

    private static long parseSequence(String fileName) {
        // promotion-000003-abcdef.json
        int first = fileName.indexOf('-');
        int second = fileName.indexOf('-', first + 1);
        if (first < 0 || second < 0) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "malformed lifecycle history file name: " + fileName
            );
        }
        try {
            return Long.parseLong(fileName.substring(first + 1, second));
        } catch (NumberFormatException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "malformed lifecycle history sequence: " + fileName,
                e
            );
        }
    }

    private static ModelPromotionRecord parsePromotion(String json) {
        ModelLifecycleIdentity previous = parseNestedIdentity(json, "previousChampion");
        ModelLifecycleIdentity neu = parseNestedIdentity(json, "newChampion");
        ModelPromotionRecord record = new ModelPromotionRecord(
            previous,
            neu,
            requiredString(json, "decisionSha256Hex"),
            requiredString(json, "comparisonSha256Hex"),
            requiredString(json, "rationale"),
            requiredString(json, "approver")
        );
        if (!record.promotionId().equals(requiredString(json, "promotionId"))
            || !record.promotionSha256Hex().equals(requiredString(json, "promotionSha256Hex"))) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "promotion history hash mismatch"
            );
        }
        return record;
    }

    private static ModelRollbackRecord parseRollback(String json) {
        ModelRollbackRecord record = new ModelRollbackRecord(
            requiredString(json, "rolledBackPromotionId"),
            parseNestedIdentity(json, "championBeforeRollback"),
            parseNestedIdentity(json, "restoredChampion"),
            requiredString(json, "rationale"),
            requiredString(json, "approver")
        );
        if (!record.rollbackId().equals(requiredString(json, "rollbackId"))
            || !record.rollbackSha256Hex().equals(requiredString(json, "rollbackSha256Hex"))) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "rollback history hash mismatch"
            );
        }
        return record;
    }

    private static ModelLifecycleIdentity parseIdentityObject(String json, String fieldName) {
        String object = extractObject(json, fieldName);
        return identityFromObject(object);
    }

    private static ModelLifecycleIdentity parseNestedIdentity(String json, String fieldName) {
        return identityFromObject(extractObject(json, fieldName));
    }

    private static ModelLifecycleIdentity identityFromObject(String object) {
        ModelLifecycleIdentityKind kind =
            ModelLifecycleIdentityKind.valueOf(requiredString(object, "kind"));
        String scorerId = requiredString(object, "scorerId");
        String scorerVersion = requiredString(object, "scorerVersion");
        String artifactId = requiredString(object, "artifactId");
        String digest = requiredString(object, "verifiedDigestHex");
        String fingerprint = requiredString(object, "configurationFingerprintSha256Hex");
        if (kind == ModelLifecycleIdentityKind.DESIGNATED_REFERENCE) {
            ModelLifecycleIdentity ref = ModelLifecycleIdentity.designatedReference(
                scorerId, scorerVersion, fingerprint);
            if (!ref.verifiedDigestHex().equals(digest.toLowerCase(Locale.ROOT))) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                    "designated reference digest mismatch"
                );
            }
            return ref;
        }
        return ModelLifecycleIdentity.fromAccepted(
            new dev.aisentinel.core.scoring.shadow.AcceptedCandidateIdentity(
                scorerId, scorerVersion, artifactId, digest, fingerprint
            )
        );
    }

    private static String extractObject(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "missing object field: " + fieldName
            );
        }
        int brace = json.indexOf('{', idx);
        if (brace < 0) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "object field not an object: " + fieldName
            );
        }
        int depth = 0;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(brace, i + 1);
                }
            }
        }
        throw new ModelLifecycleException(
            ModelLifecycleException.Code.EVIDENCE_MISMATCH,
            "unclosed object field: " + fieldName
        );
    }

    private static String requiredString(String json, String fieldName) {
        Matcher matcher = STRING_FIELD.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(fieldName)) {
                return unescape(matcher.group(2));
            }
        }
        throw new ModelLifecycleException(
            ModelLifecycleException.Code.EVIDENCE_MISMATCH,
            "missing string field: " + fieldName
        );
    }

    private static void requireSchemaVersion(String json) {
        String schemaVersion = requiredString(json, "schemaVersion");
        if (!ModelLifecycleSchemas.SCHEMA_VERSION.equals(schemaVersion)) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                "unsupported model lifecycle schemaVersion: " + schemaVersion
            );
        }
    }

    private static String unescape(String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\\", "\\");
    }

    record LoadedState(
        ModelLifecycleIdentity champion,
        ChallengerDesignation challenger,
        ModelPromotionDecision decision,
        List<ModelLifecycleHistoryEntry> history,
        long nextSequence
    ) {
        static LoadedState empty() {
            return new LoadedState(null, null, null, List.of(), 0L);
        }
    }
}
