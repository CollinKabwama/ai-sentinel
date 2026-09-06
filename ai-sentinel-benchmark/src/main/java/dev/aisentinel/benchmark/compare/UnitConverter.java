package dev.aisentinel.benchmark.compare;

final class UnitConverter {

    private UnitConverter() {
    }

    static Double convert(Double value, String from, String to) {
        if (value == null || from == null || to == null) {
            return null;
        }
        if (from.equals(to)) {
            return value;
        }
        Double nanos = toNanos(value, from);
        if (nanos != null) {
            return fromNanos(nanos, to);
        }
        Double bytes = toBytes(value, from);
        if (bytes != null) {
            return fromBytes(bytes, to);
        }
        return null;
    }

    private static Double toNanos(Double value, String unit) {
        return switch (unit) {
            case "ns/op" -> value;
            case "us/op" -> value * 1_000.0;
            case "ms/op", "milliseconds" -> value * 1_000_000.0;
            case "s/op" -> value * 1_000_000_000.0;
            default -> null;
        };
    }

    private static Double fromNanos(Double nanos, String unit) {
        return switch (unit) {
            case "ns/op" -> nanos;
            case "us/op" -> nanos / 1_000.0;
            case "ms/op", "milliseconds" -> nanos / 1_000_000.0;
            case "s/op" -> nanos / 1_000_000_000.0;
            default -> null;
        };
    }

    private static Double toBytes(Double value, String unit) {
        return switch (unit) {
            case "bytes", "B/op" -> value;
            case "kB" -> value * 1_000.0;
            case "KiB" -> value * 1024.0;
            case "MB" -> value * 1_000_000.0;
            case "MiB" -> value * 1024.0 * 1024.0;
            case "GB" -> value * 1_000_000_000.0;
            case "GiB" -> value * 1024.0 * 1024.0 * 1024.0;
            default -> null;
        };
    }

    private static Double fromBytes(Double bytes, String unit) {
        return switch (unit) {
            case "bytes", "B/op" -> bytes;
            case "kB" -> bytes / 1_000.0;
            case "KiB" -> bytes / 1024.0;
            case "MB" -> bytes / 1_000_000.0;
            case "MiB" -> bytes / (1024.0 * 1024.0);
            case "GB" -> bytes / 1_000_000_000.0;
            case "GiB" -> bytes / (1024.0 * 1024.0 * 1024.0);
            default -> null;
        };
    }
}
