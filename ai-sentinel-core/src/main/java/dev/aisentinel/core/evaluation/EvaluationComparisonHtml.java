package dev.aisentinel.core.evaluation;

import java.util.Locale;

final class EvaluationComparisonHtml {
    private EvaluationComparisonHtml() {
    }

    static String render(EvaluationComparisonModel comparison) {
        StringBuilder html = new StringBuilder();
        html.append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AI-Sentinel Evaluation Comparison</title>
            <style>
            body{font-family:system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172033}
            h1,h2{color:#0b3b60}table{border-collapse:collapse;width:100%;margin:1rem 0 2rem}
            th,td{border:1px solid #ccd5df;padding:.5rem;text-align:left;vertical-align:top}
            th{background:#eef4f8}code{word-break:break-all}.note{background:#fff8dd;padding:.75rem}
            </style></head><body>
            <h1>Evaluation comparison</h1>
            """);
        html.append("<p><code>").append(escape(comparison.comparisonId())).append("</code></p>");
        html.append("<p class=\"note\">Factual deltas only; not a winner or deployment decision.</p>");
        html.append("<h2>Summary</h2><table><tbody>");
        row(html, "Status", comparison.status());
        row(html, "Baseline result", comparison.baseline().resultId());
        row(html, "Candidate result", comparison.candidate().resultId());
        row(html, "Events compared", Integer.toString(comparison.eventsCompared()));
        row(html, "Events with changes", Integer.toString(comparison.eventChanges().size()));
        row(html, "Threshold equal", Boolean.toString(comparison.thresholdEqual()));
        row(html, "New false positives", Integer.toString(comparison.newFalsePositives()));
        row(html, "New false negatives", Integer.toString(comparison.newFalseNegatives()));
        html.append("</tbody></table>");

        html.append("<h2>Metric changes</h2><table><thead><tr>")
            .append("<th>Family</th><th>Metric</th><th>Availability</th>")
            .append("<th>Baseline</th><th>Candidate</th><th>Delta</th>")
            .append("</tr></thead><tbody>");
        for (MetricChange metric : comparison.metricChanges()) {
            html.append("<tr><td>").append(escape(metric.family())).append("</td><td>")
                .append(escape(metric.metric())).append("</td><td>")
                .append(escape(metric.baselineAvailability())).append(" → ")
                .append(escape(metric.candidateAvailability())).append("</td><td>")
                .append(number(metric.baselineValue())).append("</td><td>")
                .append(number(metric.candidateValue())).append("</td><td>")
                .append(number(metric.delta())).append("</td></tr>");
        }
        html.append("</tbody></table>");

        html.append("<h2>Changed events</h2><table><thead><tr>")
            .append("<th>Sequence</th><th>Event</th><th>Changes</th>")
            .append("<th>Score delta</th><th>Correctness</th></tr></thead><tbody>");
        for (EventChange event : comparison.eventChanges()) {
            html.append("<tr><td>").append(event.sequenceNumber()).append("</td><td><code>")
                .append(escape(event.eventId())).append("</code></td><td>")
                .append(escape(String.join(", ", event.changes()))).append("</td><td>")
                .append(number(event.scoreDelta())).append("</td><td>")
                .append(escape(event.correctnessTransition() == null ? "—" : event.correctnessTransition()))
                .append("</td></tr>");
        }
        html.append("</tbody></table></body></html>");
        return html.toString();
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><th>").append(escape(label)).append("</th><td>")
            .append(escape(value)).append("</td></tr>");
    }

    private static String number(Double value) {
        return value == null ? "unavailable" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
