package dev.aisentinel.core.pilot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Operational aggregates for a finalized MONITOR pilot session.
 * No detection-accuracy metrics.
 */
public final class PilotSummary {

    private final String schemaVersion;
    private final String pilotSessionId;
    private final String evidenceClass;
    private final long observations;
    private final long uniquePseudonymousIdentities;
    private final Map<String, Long> evaluationStatuses;
    private final Map<String, Long> riskDerivedActions;
    private final long baselineUpdatesAccepted;
    private final long baselineUpdatesSkipped;
    private final long baselineUpdatesUnavailable;
    private final long invalidScoreCount;
    private final long degradedCount;
    private final long failOpenCount;
    private final long scoreCount;
    private final double scoreMin;
    private final double scoreMax;
    private final double scoreMean;
    private final long latencyCount;
    private final long latencyP50Nanos;
    private final long latencyP95Nanos;

    public PilotSummary(
        String schemaVersion,
        String pilotSessionId,
        String evidenceClass,
        long observations,
        long uniquePseudonymousIdentities,
        Map<String, Long> evaluationStatuses,
        Map<String, Long> riskDerivedActions,
        long baselineUpdatesAccepted,
        long baselineUpdatesSkipped,
        long baselineUpdatesUnavailable,
        long invalidScoreCount,
        long degradedCount,
        long failOpenCount,
        long scoreCount,
        double scoreMin,
        double scoreMax,
        double scoreMean,
        long latencyCount,
        long latencyP50Nanos,
        long latencyP95Nanos
    ) {
        this.schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
        this.pilotSessionId = Objects.requireNonNull(pilotSessionId, "pilotSessionId");
        this.evidenceClass = Objects.requireNonNull(evidenceClass, "evidenceClass");
        this.observations = observations;
        this.uniquePseudonymousIdentities = uniquePseudonymousIdentities;
        this.evaluationStatuses = Map.copyOf(evaluationStatuses);
        this.riskDerivedActions = Map.copyOf(riskDerivedActions);
        this.baselineUpdatesAccepted = baselineUpdatesAccepted;
        this.baselineUpdatesSkipped = baselineUpdatesSkipped;
        this.baselineUpdatesUnavailable = baselineUpdatesUnavailable;
        this.invalidScoreCount = invalidScoreCount;
        this.degradedCount = degradedCount;
        this.failOpenCount = failOpenCount;
        this.scoreCount = scoreCount;
        this.scoreMin = scoreMin;
        this.scoreMax = scoreMax;
        this.scoreMean = scoreMean;
        this.latencyCount = latencyCount;
        this.latencyP50Nanos = latencyP50Nanos;
        this.latencyP95Nanos = latencyP95Nanos;
    }

    public String schemaVersion() { return schemaVersion; }
    public String pilotSessionId() { return pilotSessionId; }
    public String evidenceClass() { return evidenceClass; }
    public long observations() { return observations; }
    public long uniquePseudonymousIdentities() { return uniquePseudonymousIdentities; }
    public Map<String, Long> evaluationStatuses() { return evaluationStatuses; }
    public Map<String, Long> riskDerivedActions() { return riskDerivedActions; }
    public long baselineUpdatesAccepted() { return baselineUpdatesAccepted; }
    public long baselineUpdatesSkipped() { return baselineUpdatesSkipped; }
    public long baselineUpdatesUnavailable() { return baselineUpdatesUnavailable; }
    public long invalidScoreCount() { return invalidScoreCount; }
    public long degradedCount() { return degradedCount; }
    public long failOpenCount() { return failOpenCount; }
    public long scoreCount() { return scoreCount; }
    public double scoreMin() { return scoreMin; }
    public double scoreMax() { return scoreMax; }
    public double scoreMean() { return scoreMean; }
    public long latencyCount() { return latencyCount; }
    public long latencyP50Nanos() { return latencyP50Nanos; }
    public long latencyP95Nanos() { return latencyP95Nanos; }

    public String toJson() {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "schemaVersion", schemaVersion, true);
        appendString(json, "pilotSessionId", pilotSessionId, false);
        appendString(json, "evidenceClass", evidenceClass, false);
        json.append(",\"observations\":").append(observations);
        json.append(",\"uniquePseudonymousIdentities\":").append(uniquePseudonymousIdentities);
        appendCountMap(json, "evaluationStatuses", evaluationStatuses);
        appendCountMap(json, "riskDerivedActions", riskDerivedActions);
        json.append(",\"baselineUpdates\":{");
        json.append("\"accepted\":").append(baselineUpdatesAccepted);
        json.append(",\"skipped\":").append(baselineUpdatesSkipped);
        json.append(",\"unavailable\":").append(baselineUpdatesUnavailable);
        json.append('}');
        json.append(",\"invalidScoreCount\":").append(invalidScoreCount);
        json.append(",\"degradedCount\":").append(degradedCount);
        json.append(",\"failOpenCount\":").append(failOpenCount);
        json.append(",\"scores\":{");
        json.append("\"count\":").append(scoreCount);
        if (scoreCount > 0) {
            json.append(",\"min\":").append(Double.toString(scoreMin));
            json.append(",\"max\":").append(Double.toString(scoreMax));
            json.append(",\"mean\":").append(Double.toString(scoreMean));
        }
        json.append('}');
        json.append(",\"latency\":{");
        json.append("\"count\":").append(latencyCount);
        if (latencyCount > 0) {
            json.append(",\"p50Nanos\":").append(latencyP50Nanos);
            json.append(",\"p95Nanos\":").append(latencyP95Nanos);
        }
        json.append('}');
        json.append('}');
        return json.toString();
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(field).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendCountMap(StringBuilder json, String field, Map<String, Long> map) {
        json.append(",\"").append(field).append("\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(e.getKey())).append("\":").append(e.getValue());
        }
        json.append('}');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static Map<String, Long> sortedCounts(Map<String, Long> source) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        source.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
