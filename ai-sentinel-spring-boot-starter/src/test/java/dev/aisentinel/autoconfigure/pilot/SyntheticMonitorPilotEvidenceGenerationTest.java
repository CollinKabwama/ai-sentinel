package dev.aisentinel.autoconfigure.pilot;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.web.SentinelFilter;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.enforcement.MonitorOnlyEnforcementHandler;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.pilot.LocalPilotEvidenceSession;
import dev.aisentinel.core.pilot.PilotConfigSnapshot;
import dev.aisentinel.core.pilot.PilotObservation;
import dev.aisentinel.core.pilot.PilotPseudonymizer;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.shadow.ShadowScoringExecutor;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes a synthetic MONITOR pilot evidence directory under {@code target/}
 * for {@code scripts/verify-monitor-pilot-evidence.sh}. Uses mock servlet requests only.
 */
class SyntheticMonitorPilotEvidenceGenerationTest {

    @Test
    void generateSyntheticPilotDirectoryForVerifier() throws Exception {
        Path root = Path.of("target", "synthetic-monitor-pilot-evidence");
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort clean
                    }
                });
            }
        }
        Files.createDirectories(root);

        PilotConfigSnapshot config = new PilotConfigSnapshot(
            PilotObservation.RUNTIME_MODE_MONITOR,
            "ALLOW_OR_MONITOR",
            "composite",
            "0.4.0",
            "0.4.0",
            "1",
            PilotObservation.SCHEMA_VERSION,
            PilotObservation.EVIDENCE_CLASS,
            "threshold-policy",
            0.2,
            0.4,
            0.6,
            0.8
        );
        LocalPilotEvidenceSession session = new LocalPilotEvidenceSession(
            root,
            "synthetic-lab-session",
            config,
            new PilotPseudonymizer("synthetic-pilot-hmac-secret-key")
        );

        final double[] holder = {0.0};
        AnomalyScorer mutable = new AnomalyScorer() {
            @Override
            public double score(dev.aisentinel.core.model.RequestFeatures features) {
                return holder[0];
            }

            @Override
            public void update(dev.aisentinel.core.model.RequestFeatures features) {
            }
        };

        SentinelPipeline pipeline = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            mutable,
            null,
            new ThresholdPolicyEngine(),
            new MonitorOnlyEnforcementHandler(
                new CompositeEnforcementHandler(429, 60_000L, 1000.0, e -> {}, 1000, 60_000L),
                e -> {}),
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrainingCandidatePublisher.INSTANCE,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "MONITOR",
            null,
            null,
            null,
            null,
            null,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            BaselineLifecycle.disabled(),
            LastDecisionExplanation.NOOP,
            ShadowScoringExecutor.disabled(),
            session
        );
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);
        props.setMode(SentinelProperties.Mode.MONITOR);
        SentinelFilter filter = new SentinelFilter(pipeline, props, SentinelMetrics.NOOP);

        double[] scores = {0.0, 0.3, 0.5, 0.7, 0.9};
        for (int i = 0; i < scores.length; i++) {
            holder[0] = scores[i];
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/" + (100 + i));
            request.setRemoteAddr("203.0.113." + (20 + i));
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        }
        session.finalizeSession();

        assertThat(Files.exists(root.resolve(LocalPilotEvidenceSession.MANIFEST_FILE))).isTrue();
        assertThat(Files.exists(root.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE))).isTrue();
        assertThat(Files.exists(root.resolve(LocalPilotEvidenceSession.SUMMARY_FILE))).isTrue();
        System.out.println("SYNTHETIC_PILOT_DIR=" + root.toAbsolutePath());
    }
}
