package dev.aisentinel.core.evaluation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Self-contained HTML projection for a generated-corpus evaluation result.
 * <p>
 * The report is readable without Java source and embeds CSS inline. It projects existing
 * evaluation evidence and event inspections; it does not recompute detection metrics.
 */
final class GeneratedCorpusEvaluationReportHtml {

    private GeneratedCorpusEvaluationReportHtml() {
    }

    static String render(GeneratedCorpusEvaluationResult result) {
        GeneratedCorpusEvaluationResult safe = Objects.requireNonNull(result, "result");
        GeneratedCorpusProvenance provenance = safe.provenance();
        GeneratedCorpusPhaseCounts phases = safe.phaseCounts();
        DetectionEvaluationMetrics metrics = safe.detectionRun().metrics();
        DetectionConfusionMatrix confusion = metrics.confusionMatrix();
        DetectionMetrics ratios = metrics.metrics();
        DetectionEvaluationEvidence.ReplayProvenance replay = safe.detectionRun().evidence().replay();
        String status = safe.limitations().isEmpty() ? "completed" : "completed_with_limitations";
        String evaluationRunId = EvaluationRunIdentity.evaluationRunId(
            "generated-corpus",
            provenance.corpusId(),
            provenance.eventsSha256(),
            provenance.annotationsSha256(),
            provenance.featureSchemaVersion(),
            provenance.evaluationEventSchemaVersion(),
            replay.configurationFingerprint(),
            replay.scorerId(),
            replay.scorerVersion(),
            replay.policyId(),
            replay.policyVersion(),
            metrics.classification().anomalyThreshold(),
            "1"
        );
        String detectionAvailability = phases.labeledEvaluationEvents() == 0
            ? "unavailable"
            : "available";
        String primaryLimitation = safe.limitations().isEmpty()
            ? "Controlled synthetic reference-evaluation observations only; not production validation."
            : safe.limitations().get(0);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<title>").append(escape(provenance.corpusId())).append(" — evaluation report</title>\n");
        html.append("<style>\n");
        html.append(css());
        html.append("</style>\n</head>\n<body>\n");

        html.append("<header>\n");
        html.append("<p class=\"eyebrow\">Generated corpus evaluation report</p>\n");
        html.append("<h1>").append(escape(provenance.corpusId())).append("</h1>\n");
        html.append("<p class=\"lede\">What ran: Kit reference evaluation of scenario ")
            .append(escape(provenance.scenarioId())).append("</p>\n");
        html.append("<p class=\"disclaimer\">Controlled synthetic reference-evaluation observations only; ")
            .append("not production validation. Ground truth is evaluation-layer metadata and is not detector input.</p>\n");
        html.append("</header>\n");

        html.append("<nav class=\"toc\" aria-label=\"Report sections\">\n<ul>\n");
        html.append("<li><a href=\"#summary\">Run summary</a></li>\n");
        html.append("<li><a href=\"#configuration\">Evaluation configuration</a></li>\n");
        html.append("<li><a href=\"#phases\">Phase counts</a></li>\n");
        html.append("<li><a href=\"#detection\">Detection metrics</a></li>\n");
        html.append("<li><a href=\"#temporal\">Scenario timeline</a></li>\n");
        html.append("<li><a href=\"#events\">Event inspection</a></li>\n");
        html.append("<li><a href=\"#provenance\">Corpus provenance</a></li>\n");
        html.append("<li><a href=\"#artifacts\">Sibling artifacts</a></li>\n");
        html.append("<li><a href=\"#limitations\">Limitations</a></li>\n");
        html.append("</ul>\n</nav>\n");

        html.append("<section id=\"summary\">\n<h2>Run summary</h2>\n");
        html.append("<table>\n<tbody>\n");
        row(html, "sourceType", "generated-corpus");
        row(html, "corpusId", provenance.corpusId());
        row(html, "evaluationRunId", evaluationRunId);
        row(html, "resultId (result family)", EvaluationRunIdentity.resultFamilyId(provenance.corpusId()));
        row(html, "status", status);
        row(html, "anomalyThreshold", formatDouble(metrics.classification().anomalyThreshold()));
        row(html, "eventCount", Long.toString(phases.totalEvents()));
        row(html, "warmupEvents (ground-truth participation)", Long.toString(phases.warmupEvents()));
        row(html, "labeledEvaluationEvents", Long.toString(phases.labeledEvaluationEvents()));
        row(html, "detectionLabeled availability", detectionAvailability);
        if (phases.labeledEvaluationEvents() > 0) {
            row(html, "precision", formatMetric(ratios.precision()));
            row(html, "recall", formatMetric(ratios.recall()));
            row(html, "f1", formatMetric(ratios.f1()));
            row(html, "evaluated binary-labeled events", Long.toString(phases.labeledEvaluationEvents()));
        }
        row(html, "primaryLimitation", primaryLimitation);
        html.append("</tbody>\n</table>\n");
        html.append("<p class=\"note\">Warmup in phase counts is ground-truth participation exclusion. ")
            .append("Runtime status STATISTICAL_WARMUP is a separate axis and may appear on evaluated events.</p>\n");
        html.append("</section>\n");

        html.append("<section id=\"configuration\">\n<h2>Evaluation configuration</h2>\n");
        html.append("<p>Values below apply to this evaluation run only and are not corpus provenance.</p>\n");
        html.append("<table>\n<tbody>\n");
        row(html, "anomalyThreshold", formatDouble(metrics.classification().anomalyThreshold()));
        row(html, "scorerId", replay.scorerId());
        row(html, "scorerVersion (reference configuration)", blank(replay.scorerVersion()));
        row(html, "policyId", replay.policyId());
        row(html, "policyVersion (reference configuration)", blank(replay.policyVersion()));
        html.append("</tbody>\n</table>\n</section>\n");

        html.append("<section id=\"phases\">\n<h2>Phase counts</h2>\n");
        html.append("<table>\n<tbody>\n");
        row(html, "totalEvents", Long.toString(phases.totalEvents()));
        row(html, "warmupEvents", Long.toString(phases.warmupEvents()));
        row(html, "labeledBenignEvents", Long.toString(phases.labeledBenignEvents()));
        row(html, "labeledAnomalousEvents", Long.toString(phases.labeledAnomalousEvents()));
        row(html, "unknownOrUnlabeledEvents", Long.toString(phases.unknownOrUnlabeledEvents()));
        row(html, "labeledEvaluationEvents", Long.toString(phases.labeledEvaluationEvents()));
        html.append("</tbody>\n</table>\n</section>\n");

        html.append("<section id=\"detection\">\n<h2>Detection metrics</h2>\n");
        if (phases.labeledEvaluationEvents() == 0) {
            html.append("<p class=\"unavailable\">Detection labeled metrics unavailable: ")
                .append("no binary-labeled evaluation events were present. ")
                .append("Unavailable is not zero performance and is not an evaluator failure.</p>\n");
        } else {
            html.append("<p>Binary-labeled evaluation events only. Undefined ratios render as unavailable ")
                .append("(for example positive-class metrics when no anomalous ground truth exists). ")
                .append("Unavailable is not zero.</p>\n");
            html.append("<p class=\"note\">Evaluated binary-labeled events: ")
                .append(phases.labeledEvaluationEvents())
                .append(". Controlled reference evaluation; not a production efficacy estimate.</p>\n");
            html.append("<table>\n<tbody>\n");
            row(html, "evaluablePredictions", Long.toString(metrics.evaluablePredictionCount()));
            row(html, "truePositives", Long.toString(confusion.truePositives()));
            row(html, "trueNegatives", Long.toString(confusion.trueNegatives()));
            row(html, "falsePositives", Long.toString(confusion.falsePositives()));
            row(html, "falseNegatives", Long.toString(confusion.falseNegatives()));
            row(html, "precision", formatMetric(ratios.precision()));
            row(html, "recall", formatMetric(ratios.recall()));
            row(html, "f1", formatMetric(ratios.f1()));
            row(html, "falsePositiveRate", formatMetric(ratios.falsePositiveRate()));
            row(html, "falseNegativeRate", formatMetric(ratios.falseNegativeRate()));
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");

        html.append("<section id=\"temporal\">\n<h2>Scenario timeline</h2>\n");
        List<ScenarioTemporalEvaluation> scenarios = safe.detectionRun().temporal().scenarios();
        if (scenarios.isEmpty()) {
            html.append("<p class=\"unavailable\">Temporal scenario slices unavailable.</p>\n");
        } else {
            html.append("<p>compatibilityCategory is a historical metrics-grouping label, not the corpus's ")
                .append("authored category — see Event inspection below for authored category per event.</p>\n");
            html.append("<table>\n<thead><tr>")
                .append("<th>scenarioId</th><th>compatibilityCategory</th><th>observations</th><th>anomalySegments</th>")
                .append("</tr></thead>\n<tbody>\n");
            for (ScenarioTemporalEvaluation scenario : scenarios) {
                html.append("<tr>");
                cell(html, scenario.scenarioId());
                cell(html, scenario.scenarioCategory().name());
                cell(html, Long.toString(scenario.observationCount()));
                cell(html, Integer.toString(scenario.anomalySegments().size()));
                html.append("</tr>\n");
            }
            html.append("</tbody>\n</table>\n");
        }
        html.append("</section>\n");

        html.append("<section id=\"events\">\n<h2>Event inspection</h2>\n");
        html.append("<p>Each row joins authored ground truth with runtime replay outcomes. ")
            .append("Runtime scores and evaluation statuses come only from replay. ")
            .append("expected is authored class; predicted is threshold classification of the anomaly score.</p>\n");
        html.append("<table class=\"events\">\n<thead><tr>")
            .append("<th>#</th><th>eventId</th><th>category</th><th>expected</th>")
            .append("<th>participation</th><th>score</th><th>predicted</th>")
            .append("<th>action</th><th>statuses</th><th>outcome</th>")
            .append("</tr></thead>\n<tbody>\n");
        for (GeneratedCorpusEventInspection event : safe.eventInspections()) {
            html.append("<tr class=\"outcome-").append(escape(event.outcome())).append("\">");
            cell(html, Integer.toString(event.sequenceNumber()));
            cell(html, event.eventId());
            cell(html, event.category());
            cell(html, event.expectedClass());
            cell(html, event.participation());
            cell(html, event.anomalyScore() == null ? "—" : formatDouble(event.anomalyScore()));
            cell(html, event.predictedAnomalous() == null ? "—" : Boolean.toString(event.predictedAnomalous()));
            cell(html, event.action());
            cell(html, event.evaluationStatuses().isEmpty() ? "—" : String.join(", ", event.evaluationStatuses()));
            cell(html, event.outcome());
            html.append("</tr>\n");
        }
        html.append("</tbody>\n</table>\n</section>\n");

        html.append("<section id=\"provenance\">\n<h2>Corpus provenance</h2>\n");
        html.append("<p>Full digests and reference-configuration identity. ")
            .append("aiSentinelVersion / scorerVersion / policyVersion are reference evaluation ")
            .append("configuration identity (not packaging software version).</p>\n");
        html.append("<table>\n<tbody>\n");
        row(html, "corpusId", provenance.corpusId());
        row(html, "scenarioId", provenance.scenarioId());
        row(html, "scenarioVersion", provenance.scenarioVersion());
        row(html, "scenarioSha256", provenance.scenarioSha256());
        row(html, "seed", provenance.seed());
        row(html, "generatorContractVersion", provenance.generatorContractVersion());
        row(html, "generatorBuildId", provenance.generatorBuildId());
        row(html, "featureSchemaVersion", provenance.featureSchemaVersion());
        row(html, "evaluationEventSchemaVersion", provenance.evaluationEventSchemaVersion());
        row(html, "representationMode", provenance.representationMode());
        row(html, "eventsSha256", provenance.eventsSha256());
        row(html, "annotationsSha256", provenance.annotationsSha256());
        row(html, "aiSentinelVersion (reference configuration)", replay.aiSentinelVersion());
        KitSoftwareIdentity.softwareVersion().ifPresent(version ->
            row(html, "softwareVersion (packaging)", version));
        row(html, "aiSentinelBuildId", "absent (exact build/commit identity not available)");
        row(html, "scorerId", replay.scorerId());
        row(html, "scorerVersion (reference configuration)", blank(replay.scorerVersion()));
        row(html, "policyId", replay.policyId());
        row(html, "policyVersion (reference configuration)", blank(replay.policyVersion()));
        html.append("</tbody>\n</table>\n");
        html.append("<p class=\"note\">Specialized evaluation.json uses generic reference.datasetId / ")
            .append("replay.datasetId even for generated Kit runs; those fields carry the corpus identity string.</p>\n");
        html.append("</section>\n");

        html.append("<section id=\"artifacts\">\n<h2>Sibling artifacts</h2>\n<ul>\n");
        html.append("<li><code>").append(escape(DetectionEvaluationEvidenceWriter.JSON_FILE_NAME))
            .append("</code> — specialized detection evaluation evidence (JSON)</li>\n");
        html.append("<li><code>").append(escape(DetectionEvaluationEvidenceWriter.MARKDOWN_FILE_NAME))
            .append("</code> — specialized detection evaluation evidence (Markdown)</li>\n");
        html.append("<li><code>").append(escape(GeneratedCorpusEvaluationReportWriter.KIT_RESULT_FILE_NAME))
            .append("</code> — Evaluation Kit result for this generated corpus (JSON)</li>\n");
        html.append("<li><code>").append(escape(GeneratedCorpusEvaluationReportWriter.EVENT_INSPECTION_FILE_NAME))
            .append("</code> — event-level inspection (JSON)</li>\n");
        html.append("<li><code>").append(escape(GeneratedCorpusEvaluationReportWriter.HTML_REPORT_FILE_NAME))
            .append("</code> — this HTML report</li>\n");
        html.append("</ul>\n</section>\n");

        html.append("<section id=\"limitations\">\n<h2>Limitations</h2>\n<ul>\n");
        for (String limitation : safe.limitations()) {
            html.append("<li>").append(escape(limitation)).append("</li>\n");
        }
        html.append("<li>Controlled synthetic reference-evaluation observations only; not production efficacy.</li>\n");
        html.append("<li>Evaluation is not a deployment decision. Anomalous is not malicious.</li>\n");
        html.append("</ul>\n</section>\n");

        html.append("<footer><p>AI-Sentinel generated-corpus evaluation report</p></footer>\n");
        html.append("</body>\n</html>");
        return html.toString();
    }

    private static String css() {
        return """
            :root { color-scheme: light; --ink:#1a1f24; --muted:#5b6570; --line:#d7dde3; --bg:#f7f8fa; --card:#fff; --accent:#0b5fff; }
            * { box-sizing: border-box; }
            body { margin:0; font:15px/1.5 "IBM Plex Sans", "Segoe UI", sans-serif; color:var(--ink); background:linear-gradient(180deg,#eef2f7,var(--bg) 220px); }
            header, section, nav, footer { max-width:1080px; margin:0 auto; padding:1.25rem 1.5rem; }
            header { padding-top:2rem; }
            .eyebrow { text-transform:uppercase; letter-spacing:.08em; font-size:.75rem; color:var(--muted); margin:0 0 .35rem; }
            h1 { font:600 1.85rem/1.2 "IBM Plex Sans", sans-serif; margin:0 0 .4rem; }
            h2 { font:600 1.2rem/1.3 "IBM Plex Sans", sans-serif; margin:0 0 .75rem; }
            .lede { color:var(--muted); margin:0 0 .75rem; }
            .disclaimer, .note { background:var(--card); border:1px solid var(--line); padding:.75rem 1rem; border-radius:6px; }
            .note { margin-top:.75rem; color:var(--muted); }
            .toc ul { display:flex; flex-wrap:wrap; gap:.5rem 1rem; list-style:none; padding:0; margin:0; }
            .toc a { color:var(--accent); text-decoration:none; }
            .toc a:hover { text-decoration:underline; }
            section { background:var(--card); border:1px solid var(--line); border-radius:8px; margin:1rem auto; }
            table { width:100%; border-collapse:collapse; }
            th, td { text-align:left; vertical-align:top; padding:.45rem .55rem; border-bottom:1px solid var(--line); font-size:.92rem; word-break:break-word; }
            th { color:var(--muted); font-weight:600; width:38%; }
            .events { display:block; overflow-x:auto; }
            .unavailable { color:var(--muted); font-style:italic; }
            code { font-family:"IBM Plex Mono", ui-monospace, monospace; font-size:.88em; }
            footer { color:var(--muted); font-size:.85rem; }
            .outcome-false-positive td:last-child, .outcome-false-negative td:last-child { font-weight:600; }
            """;
    }

    private static void row(StringBuilder html, String key, String value) {
        html.append("<tr><th>").append(escape(key)).append("</th><td>")
            .append(escape(value)).append("</td></tr>\n");
    }

    private static void cell(StringBuilder html, String value) {
        html.append("<td>").append(escape(value)).append("</td>");
    }

    private static String formatMetric(DetectionMetricValue value) {
        if (value == null || !value.defined()) {
            return "unavailable";
        }
        return formatDouble(value.value());
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
