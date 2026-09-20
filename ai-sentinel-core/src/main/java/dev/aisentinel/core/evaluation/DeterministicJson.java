package dev.aisentinel.core.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal deterministic JSON parser for baseline/verification trust boundaries.
 * <p>
 * Unknown object fields are preserved so callers may ignore additive future fields
 * while validating required known semantics. Duplicate object keys are rejected.
 */
final class DeterministicJson {

    private DeterministicJson() {
    }

    static Object parse(String text) {
        Parser parser = new Parser(Objects.requireNonNull(text, "text"));
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.done()) {
            throw new IllegalArgumentException("trailing JSON content at index " + parser.index);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> requireObject(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> requireArray(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
        return (List<Object>) list;
    }

    static String requireString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> object, String field, String defaultValue) {
        if (!object.containsKey(field) || object.get(field) == null) {
            return defaultValue;
        }
        Object value = object.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return text;
    }

    static double requireDouble(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        double converted = number.doubleValue();
        if (!Double.isFinite(converted)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return converted;
    }

    static long requireLong(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue) || Math.rint(doubleValue) != doubleValue) {
                throw new IllegalArgumentException(field + " must be an integral number");
            }
            if (doubleValue < Long.MIN_VALUE || doubleValue > Long.MAX_VALUE) {
                throw new IllegalArgumentException(field + " out of long range");
            }
        }
        return number.longValue();
    }

    static int requireInt(Map<String, Object> object, String field) {
        long value = requireLong(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " out of int range");
        }
        return (int) value;
    }

    static boolean requireBoolean(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return bool;
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private boolean done() {
            return index >= text.length();
        }

        private Object parseValue() {
            skipWhitespace();
            if (done()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = text.charAt(index);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                if (object.containsKey(key)) {
                    throw new IllegalArgumentException("duplicate JSON object key: " + key);
                }
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!done()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (done()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> out.append(escaped);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("invalid unicode escape");
                            }
                            String hex = text.substring(index, index + 4);
                            index += 4;
                            out.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new IllegalArgumentException("invalid escape: \\" + escaped);
                    }
                } else if (c < 0x20) {
                    throw new IllegalArgumentException("unescaped control character in string");
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (done() || !Character.isDigit(text.charAt(index))) {
                throw new IllegalArgumentException("invalid number at index " + start);
            }
            if (text.charAt(index) == '0') {
                index++;
                if (!done() && Character.isDigit(text.charAt(index))) {
                    throw new IllegalArgumentException("leading zeros are not allowed");
                }
            } else {
                while (!done() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            boolean fractional = false;
            if (!done() && text.charAt(index) == '.') {
                fractional = true;
                index++;
                if (done() || !Character.isDigit(text.charAt(index))) {
                    throw new IllegalArgumentException("invalid fractional number");
                }
                while (!done() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (!done() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                fractional = true;
                index++;
                if (!done() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                if (done() || !Character.isDigit(text.charAt(index))) {
                    throw new IllegalArgumentException("invalid exponent");
                }
                while (!done() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String raw = text.substring(start, index);
            if (fractional || raw.contains(".")) {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("number must be finite at index " + start);
                }
                return value;
            }
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return (int) value;
            }
            return value;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("expected " + literal);
            }
            index += literal.length();
            return value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (done() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("expected '" + expected + "' at index " + index);
            }
            index++;
        }

        private boolean peek(char expected) {
            return !done() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (!done()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }
    }
}
