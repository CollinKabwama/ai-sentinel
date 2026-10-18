package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.policy.EnforcementAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical JSON codec for {@link EvaluationEvent}.
 * <p>
 * Dataset export and replay loaders must share this representation so generation → export →
 * replay → evaluation consume one detector-facing evidence semantics. Kit run identity
 * ({@code scenarioId}, {@code corpusId}, {@code resultId}, generator/seed bindings) stays in
 * sidecar / run-manifest metadata and must not appear on the event.
 * <p>
 * This is a contract-specific encoder/decoder for the EvaluationEvent JSONL shape used by
 * dataset export and replay. It is not a general-purpose JSON parser.
 */
public final class EvaluationEventJson {

    private static final Set<String> FORBIDDEN_DETECTOR_FACING_FIELDS = Set.of(
        "expectedClass",
        "groundTruth",
        "ground_truth",
        "annotation",
        "annotations",
        "labels",
        "label",
        "anomalyExpected",
        "maliciousnessAsserted",
        "evaluationExpectations",
        "desiredAnomalyScore",
        "expectedAction",
        "scenarioId",
        "corpusId",
        "resultId",
        "seed",
        "generatorContractVersion",
        "generatorBuildId"
    );

    private EvaluationEventJson() {
    }

    /**
     * Serializes one event to a single-line JSON object (JSONL-compatible).
     */
    public static String write(EvaluationEvent event) {
        Objects.requireNonNull(event, "event");
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "eventSchemaVersion", event.eventSchemaVersion(), true);
        appendString(json, "eventId", event.eventId(), false);
        appendString(json, "observedAt", event.observedAt().toString(), false);
        appendOptionalString(json, "correlationId", event.correlationId());
        appendString(json, "identityKey", event.identityKey(), false);
        appendOptionalString(json, "identityType", event.identityType());
        appendString(json, "endpointKey", event.endpointKey(), false);
        appendString(json, "featureSchemaVersion", event.featureSchemaVersion(), false);
        json.append(",\"features\":{");
        appendNumber(json, "requestsPerWindow", event.features().requestsPerWindow(), true);
        appendNumber(json, "endpointEntropy", event.features().endpointEntropy(), false);
        appendNumber(json, "endpointConcentration", event.features().endpointConcentration(), false);
        appendNumber(json, "tokenAgeSeconds", event.features().tokenAgeSeconds(), false);
        appendNumber(json, "parameterCount", event.features().parameterCount(), false);
        appendNumber(json, "payloadSizeBytes", event.features().payloadSizeBytes(), false);
        appendNumber(json, "headerFingerprintHash", event.features().headerFingerprintHash(), false);
        appendNumber(json, "ipBucket", event.features().ipBucket(), false);
        json.append('}');
        appendString(json, "scorerId", event.scorerId(), false);
        appendOptionalString(json, "scorerVersion", event.scorerVersion());
        appendOptionalNumber(json, "anomalyScore", event.anomalyScore());
        appendOptionalNumber(json, "policyScore", event.policyScore());
        appendString(json, "action", event.action().name(), false);
        appendStringArray(json, "evaluationStatuses", event.evaluationStatuses().stream().map(Enum::name).toList(), false);
        json.append(",\"riskFactors\":[");
        appendRiskFactors(json, event.riskFactors());
        json.append(']');
        appendOptionalString(json, "policyId", event.policyId());
        appendOptionalString(json, "policyVersion", event.policyVersion());
        appendOptionalString(json, "evaluationMode", event.evaluationMode());
        json.append('}');
        return json.toString();
    }

    /**
     * Parses one JSON object into a validated {@link EvaluationEvent}.
     * Rejects ground-truth / Kit-run identity fields on the detector-facing envelope.
     */
    public static EvaluationEvent parse(String jsonObject) {
        if (jsonObject == null || jsonObject.isBlank()) {
            throw new EvaluationContractException("EvaluationEvent JSON is required");
        }
        rejectForbiddenFields(jsonObject);
        try {
            String eventSchemaVersion = requireString(jsonObject, "eventSchemaVersion");
            FeatureSnapshot features = new FeatureSnapshot(
                requireDouble(jsonObject, "requestsPerWindow"),
                requireDouble(jsonObject, "endpointEntropy"),
                requireDouble(jsonObject, "endpointConcentration"),
                requireDouble(jsonObject, "tokenAgeSeconds"),
                (int) requireLong(jsonObject, "parameterCount"),
                requireLong(jsonObject, "payloadSizeBytes"),
                requireLong(jsonObject, "headerFingerprintHash"),
                (int) requireLong(jsonObject, "ipBucket")
            );
            return new EvaluationEvent(
                eventSchemaVersion,
                requireString(jsonObject, "eventId"),
                Instant.parse(requireString(jsonObject, "observedAt")),
                optionalString(jsonObject, "correlationId"),
                requireString(jsonObject, "identityKey"),
                optionalString(jsonObject, "identityType"),
                requireString(jsonObject, "endpointKey"),
                requireString(jsonObject, "featureSchemaVersion"),
                features,
                requireString(jsonObject, "scorerId"),
                optionalString(jsonObject, "scorerVersion"),
                optionalDouble(jsonObject, "anomalyScore"),
                optionalDouble(jsonObject, "policyScore"),
                EnforcementAction.valueOf(requireString(jsonObject, "action")),
                parseStatuses(arrayBody(jsonObject, "evaluationStatuses")),
                parseRiskFactors(jsonObject),
                optionalString(jsonObject, "policyId"),
                optionalString(jsonObject, "policyVersion"),
                optionalString(jsonObject, "evaluationMode")
            );
        } catch (EvaluationContractException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EvaluationContractException("Failed to parse EvaluationEvent JSON: " + e.getMessage());
        }
    }

    static void rejectForbiddenFields(String jsonObject) {
        for (String field : FORBIDDEN_DETECTOR_FACING_FIELDS) {
            if (hasTopLevelField(jsonObject, field)) {
                throw new EvaluationContractException(
                    "Detector-facing EvaluationEvent must not include field: " + field);
            }
        }
    }

    private static boolean hasTopLevelField(String json, String field) {
        // Match "field": at object level. Nested feature keys do not collide with forbidden names.
        return Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:").matcher(json).find();
    }

    /**
     * Matches a JSON string's raw (still-escaped) content: any run of characters that are
     * neither an unescaped quote nor a backslash, or a backslash followed by any one character
     * (the escaped-pair case, including {@code \"} and {@code \\}). Without the backslash
     * alternative, a value containing an escaped quote (for example {@code he said \"hi\"})
     * would be truncated at the first escaped quote instead of the real closing quote.
     */
    private static final String STRING_VALUE_GROUP = "((?:[^\"\\\\]|\\\\.)*)";

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"" + STRING_VALUE_GROUP + "\"").matcher(json);
        if (!matcher.find()) {
            throw new EvaluationContractException("Missing field: " + field);
        }
        return unescape(matcher.group(1));
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"" + STRING_VALUE_GROUP + "\"").matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private static double requireDouble(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!matcher.find()) {
            throw new EvaluationContractException("Missing numeric field: " + field);
        }
        return Double.parseDouble(matcher.group(1));
    }

    private static long requireLong(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new EvaluationContractException("Missing integer field: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static Double optionalDouble(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private static String arrayBody(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (!matcher.find()) {
            throw new EvaluationContractException("Missing array field: " + field);
        }
        return matcher.group(1);
    }

    private static List<EvaluationStatus> parseStatuses(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(body);
        List<EvaluationStatus> out = new ArrayList<>();
        while (matcher.find()) {
            out.add(EvaluationStatus.valueOf(matcher.group(1)));
        }
        return List.copyOf(out);
    }

    private static List<ContractRiskFactor> parseRiskFactors(String json) {
        Matcher arrayMatcher = Pattern.compile("\"riskFactors\":\\[(.*?)](?:,|})", Pattern.DOTALL).matcher(json);
        if (!arrayMatcher.find()) {
            throw new EvaluationContractException("Missing riskFactors");
        }
        String body = arrayMatcher.group(1);
        if (body.isBlank()) {
            return List.of();
        }
        Matcher factorMatcher = Pattern.compile(
            "\\{\"code\":\"" + STRING_VALUE_GROUP + "\",\"category\":\"" + STRING_VALUE_GROUP
                + "\",\"severity\":\"" + STRING_VALUE_GROUP + "\","
                + "\"contribution\":(-?[0-9]+(?:\\.[0-9]+)?),\"confidence\":(-?[0-9]+(?:\\.[0-9]+)?)"
                + "(?:,\"evidenceRef\":\"" + STRING_VALUE_GROUP + "\")?"
                + "(?:,\"explanation\":\"" + STRING_VALUE_GROUP + "\")?"
                + "(?:,\"source\":\"" + STRING_VALUE_GROUP + "\")?\\}")
            .matcher(body);
        List<ContractRiskFactor> out = new ArrayList<>();
        while (factorMatcher.find()) {
            out.add(new ContractRiskFactor(
                unescape(factorMatcher.group(1)),
                unescape(factorMatcher.group(2)),
                unescape(factorMatcher.group(3)),
                Double.parseDouble(factorMatcher.group(4)),
                Double.parseDouble(factorMatcher.group(5)),
                factorMatcher.group(6) == null ? "" : unescape(factorMatcher.group(6)),
                factorMatcher.group(7) == null ? "" : unescape(factorMatcher.group(7)),
                factorMatcher.group(8) == null ? "" : unescape(factorMatcher.group(8))
            ));
        }
        return List.copyOf(out);
    }

    private static void appendRiskFactors(StringBuilder json, List<ContractRiskFactor> factors) {
        for (int i = 0; i < factors.size(); i++) {
            ContractRiskFactor factor = factors.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            appendString(json, "code", factor.code(), true);
            appendString(json, "category", factor.category(), false);
            appendString(json, "severity", factor.severity(), false);
            appendNumber(json, "contribution", factor.contribution(), false);
            appendNumber(json, "confidence", factor.confidence(), false);
            appendOptionalString(json, "evidenceRef", factor.evidenceRef());
            appendOptionalString(json, "explanation", factor.explanation());
            appendOptionalString(json, "source", factor.source());
            json.append('}');
        }
    }

    private static void appendStringArray(StringBuilder json, String field, List<String> values, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    private static void appendOptionalString(StringBuilder json, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        json.append(",\"").append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendOptionalNumber(StringBuilder json, String field, Double value) {
        if (value == null) {
            return;
        }
        json.append(",\"").append(escape(field)).append("\":").append(Double.toString(value));
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(Double.toString(value));
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(Long.toString(value));
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
                        out.append(unicodeEscape(c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * Inverse of {@link #escape(String)}: decodes a raw (still-escaped) JSON string body captured
     * by {@link #STRING_VALUE_GROUP} back into its real character content. Without this, a value
     * containing any escaped character (quote, backslash, newline, tab, control char) would be
     * returned to the caller still literally containing the two-character escape sequence instead
     * of the original character, breaking round-trip fidelity.
     */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                i++;
                continue;
            }
            char next = raw.charAt(i + 1);
            switch (next) {
                case '"' -> { out.append('"'); i += 2; }
                case '\\' -> { out.append('\\'); i += 2; }
                case '/' -> { out.append('/'); i += 2; }
                case 'b' -> { out.append('\b'); i += 2; }
                case 'f' -> { out.append('\f'); i += 2; }
                case 'n' -> { out.append('\n'); i += 2; }
                case 'r' -> { out.append('\r'); i += 2; }
                case 't' -> { out.append('\t'); i += 2; }
                case 'u' -> {
                    if (i + 6 > raw.length()) {
                        throw new EvaluationContractException("Truncated \\u escape in EvaluationEvent JSON");
                    }
                    String hex = raw.substring(i + 2, i + 6);
                    try {
                        out.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException e) {
                        throw new EvaluationContractException("Invalid \\u escape in EvaluationEvent JSON: " + hex);
                    }
                    i += 6;
                }
                default -> throw new EvaluationContractException(
                    "Invalid JSON escape sequence in EvaluationEvent JSON: \\" + next);
            }
        }
        return out.toString();
    }

    private static String unicodeEscape(char c) {
        String hex = Integer.toHexString(c);
        StringBuilder out = new StringBuilder(6);
        out.append("\\u");
        for (int i = hex.length(); i < 4; i++) {
            out.append('0');
        }
        out.append(hex.toLowerCase(Locale.ROOT));
        return out.toString();
    }
}
