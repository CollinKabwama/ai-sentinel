package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.decision.EvaluationStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReplayJsonSupport {

    private ReplayJsonSupport() {
    }

    static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Missing field: " + field);
        }
        return matcher.group(1);
    }

    static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    static double requireDouble(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!matcher.find()) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Missing numeric field: " + field);
        }
        return Double.parseDouble(matcher.group(1));
    }

    static long requireLong(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Missing integer field: " + field);
        }
        return Long.parseLong(matcher.group(1));
    }

    static Double optionalDouble(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    static String arrayBody(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\\[(.*?)]", Pattern.DOTALL).matcher(json);
        if (!matcher.find()) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Missing array field: " + field);
        }
        return matcher.group(1);
    }

    static List<String> parseStringArray(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(body);
        List<String> out = new ArrayList<>();
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
        return List.copyOf(out);
    }

    static List<EvaluationStatus> parseStatuses(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<String> names = parseStringArray(body);
        List<EvaluationStatus> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(EvaluationStatus.valueOf(name));
        }
        return List.copyOf(out);
    }

    static List<ContractRiskFactor> parseRiskFactors(String json) {
        Matcher arrayMatcher = Pattern.compile("\"riskFactors\":\\[(.*?)](?:,|})", Pattern.DOTALL).matcher(json);
        if (!arrayMatcher.find()) {
            throw new ReplayException(ReplayFailureKind.INPUT_PARSE_FAILURE, "Missing riskFactors");
        }
        String body = arrayMatcher.group(1);
        if (body.isBlank()) {
            return List.of();
        }
        Matcher factorMatcher = Pattern.compile(
            "\\{\"code\":\"([^\"]+)\",\"category\":\"([^\"]+)\",\"severity\":\"([^\"]+)\","
                + "\"contribution\":(-?[0-9]+(?:\\.[0-9]+)?),\"confidence\":(-?[0-9]+(?:\\.[0-9]+)?)(?:,\"evidenceRef\":\"([^\"]*)\")?"
                + "(?:,\"explanation\":\"([^\"]*)\")?(?:,\"source\":\"([^\"]*)\")?\\}")
            .matcher(body);
        List<ContractRiskFactor> out = new ArrayList<>();
        while (factorMatcher.find()) {
            out.add(new ContractRiskFactor(
                factorMatcher.group(1),
                factorMatcher.group(2),
                factorMatcher.group(3),
                Double.parseDouble(factorMatcher.group(4)),
                Double.parseDouble(factorMatcher.group(5)),
                factorMatcher.group(6) == null ? "" : factorMatcher.group(6),
                factorMatcher.group(7) == null ? "" : factorMatcher.group(7),
                factorMatcher.group(8) == null ? "" : factorMatcher.group(8)
            ));
        }
        return List.copyOf(out);
    }

    static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    static String escape(String value) {
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
