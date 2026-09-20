package dev.aisentinel.core.dataset.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Scenario/Test Plan parser for the Evaluation Kit JSON machine form.
 * <p>
 * Contract-specific: supports the nested shape required by the scenario schema for generation.
 * It is not a general-purpose JSON parser.
 */
final class ScenarioDocumentParser {

    private ScenarioDocumentParser() {
    }

    static ScenarioDocument parse(String scenarioJson) {
        if (scenarioJson == null || scenarioJson.isBlank()) {
            throw new CorpusGeneratorException("Scenario JSON is required");
        }
        String json = scenarioJson.trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new CorpusGeneratorException("Scenario JSON must be a single object");
        }

        String population = requireObject(json, "population");
        String normalBehavior = requireObject(json, "normalBehavior");
        String timing = requireObject(json, "timing");
        String warmup = requireObject(timing, "warmup");
        String evaluation = requireObject(timing, "evaluation");
        String transitionsBody = requireArrayBody(json, "transitions");
        String metadata = optionalObject(json, "metadata");

        List<ScenarioDocument.Transition> transitions = parseTransitions(transitionsBody);
        List<String> endpoints = optionalStringArray(normalBehavior, "endpointKeys");

        return new ScenarioDocument(
            requireString(json, "scenarioSchemaVersion"),
            requireString(json, "scenarioId"),
            requireString(json, "scenarioVersion"),
            requireString(json, "featureSchemaVersion"),
            optionalString(json, "title"),
            optionalString(json, "description"),
            requireInt(population, "identityCount"),
            optionalString(population, "identityKeyPrefix"),
            requireString(normalBehavior, "summary"),
            endpoints,
            requireInt(warmup, "durationSeconds"),
            requireInt(evaluation, "durationSeconds"),
            transitions,
            metadata.isEmpty() ? "" : optionalString(metadata, CorpusGeneratorSchemas.METADATA_FAMILY_KEY)
        );
    }

    private static List<ScenarioDocument.Transition> parseTransitions(String arrayBody) {
        List<String> objects = splitTopLevelObjects(arrayBody);
        if (objects.isEmpty()) {
            throw new CorpusGeneratorException("transitions must contain at least one object");
        }
        List<ScenarioDocument.Transition> out = new ArrayList<>(objects.size());
        for (String object : objects) {
            String intent = requireString(object, "intent").toLowerCase(Locale.ROOT);
            if (!intent.equals("legitimate")
                && !intent.equals("anomalous")
                && !intent.equals("mixed")
                && !intent.equals("unspecified")) {
                throw new CorpusGeneratorException("Unsupported transition intent: " + intent);
            }
            int start = optionalInt(object, "startsAfterWarmupSeconds", 0);
            out.add(new ScenarioDocument.Transition(
                requireString(object, "transitionId"),
                intent,
                requireString(object, "summary"),
                start
            ));
        }
        return List.copyOf(out);
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
                } else if (depth < 0) {
                    throw new CorpusGeneratorException("Malformed transitions array");
                }
            }
        }
        if (depth != 0) {
            throw new CorpusGeneratorException("Malformed transitions array");
        }
        return objects;
    }

    private static String requireObject(String json, String field) {
        String object = optionalObject(json, field);
        if (object.isEmpty()) {
            throw new CorpusGeneratorException("Missing object field: " + field);
        }
        return object;
    }

    private static String optionalObject(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        int open = matcher.end() - 1;
        return extractBalanced(json, open, '{', '}');
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
        throw new CorpusGeneratorException("Unbalanced JSON structure for nested value");
    }

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing string field: " + field);
        }
        return unescape(matcher.group(1));
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private static int requireInt(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing integer field: " + field);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static int optionalInt(String json, String field, int defaultValue) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : defaultValue;
    }

    private static List<String> optionalStringArray(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return List.of();
        }
        int open = matcher.end() - 1;
        String array = extractBalanced(json, open, '[', ']');
        String body = array.substring(1, array.length() - 1);
        Matcher values = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(body);
        List<String> out = new ArrayList<>();
        while (values.find()) {
            out.add(unescape(values.group(1)));
        }
        return List.copyOf(out);
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char n = raw.charAt(++i);
            out.append(switch (n) {
                case '"', '\\', '/' -> n;
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    if (i + 4 >= raw.length()) {
                        throw new CorpusGeneratorException("Invalid unicode escape in scenario JSON");
                    }
                    int code = Integer.parseInt(raw.substring(i + 1, i + 5), 16);
                    i += 4;
                    yield (char) code;
                }
                default -> throw new CorpusGeneratorException("Invalid escape in scenario JSON: \\" + n);
            });
        }
        return out.toString();
    }
}
