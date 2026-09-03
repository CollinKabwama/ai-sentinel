package dev.aisentinel.core.characterization;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes client-controlled feature influence and statistical score saturation behavior.
 */
class RequestTrustBoundaryAndSaturationTest {

    @Test
    void clientControlledHeaders_changeHeaderFingerprintAndCanMoveScore() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 10_000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store, 10_000, 300_000L);
        StatisticalScorer scorer = new StatisticalScorer(10_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer);

        String id = "char-trust";
        String ep = "/api/trust";
        // Establish baseline with stable headers/params
        for (int i = 0; i < 40; i++) {
            MapHttpRequestView req = baseRequest(ep)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .remoteAddr("203.0.113.10");
            RequestFeatures f = extractor.extract(req, id, new RequestContext());
            scorer.update(f);
        }

        MapHttpRequestView calm = baseRequest(ep)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .remoteAddr("203.0.113.10");
        RiskDecision calmD = engine.evaluate(calm, id, extractor.extract(calm, id, new RequestContext()), new RequestContext());

        // Attacker flips non-auth header lengths (fingerprint uses name→length) and Content-Length / params
        MapHttpRequestView hostile = baseRequest(ep)
            .header("User-Agent", "X") // length change
            .header("Accept", "text/html")
            .header("X-Custom-Noise", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .header("Content-Length", "999999")
            .parameter("a", "1")
            .parameter("b", "2")
            .parameter("c", "3")
            .remoteAddr("198.51.100.20");
        RequestFeatures hostileF = extractor.extract(hostile, id, new RequestContext());
        RiskDecision hostileD = engine.evaluate(hostile, id, hostileF, new RequestContext());

        System.out.printf(Locale.ROOT,
            "calmScore=%.4f hostileScore=%.4f delta=%.4f headerFpCalm=%d headerFpHostile=%d payload=%d params=%d ipBucket=%d%n",
            calmD.anomalyScore(), hostileD.anomalyScore(),
            hostileD.anomalyScore() - calmD.anomalyScore(),
            0, // fingerprint not on decision; log feature deltas
            hostileF.headerFingerprintHash(),
            hostileF.payloadSizeBytes(),
            hostileF.parameterCount(),
            hostileF.ipBucket());

        // Client metadata is intentionally part of the feature model — influence is expected.
        assertThat(hostileF.payloadSizeBytes()).isEqualTo(999999L);
        assertThat(hostileF.parameterCount()).isEqualTo(3);
        // Spoofed X-Forwarded-For is NOT used for ipBucket — remoteAddr is.
        MapHttpRequestView spoofFwd = baseRequest(ep)
            .header("X-Forwarded-For", "1.2.3.4")
            .remoteAddr("203.0.113.10");
        RequestFeatures spoofF = extractor.extract(spoofFwd, id + "-fwd", new RequestContext());
        MapHttpRequestView noFwd = baseRequest(ep).remoteAddr("203.0.113.10");
        RequestFeatures noFwdF = extractor.extract(noFwd, id + "-fwd2", new RequestContext());
        assertThat(spoofF.ipBucket())
            .as("X-Forwarded-For must not change ipBucket; extractor uses remoteAddr")
            .isEqualTo(noFwdF.ipBucket());
    }

    @Test
    void authorizationAndTokenIssuedAt_affectTokenAgeFeature() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 1000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store);
        long nowSec = System.currentTimeMillis() / 1000L;

        MapHttpRequestView withToken = baseRequest("/api/t")
            .header("Authorization", "Bearer synthetic-token")
            .header("X-Token-Issued-At", String.valueOf(nowSec - 60));
        RequestFeatures f = extractor.extract(withToken, "tok", new RequestContext());
        assertThat(f.tokenAgeSeconds()).isBetween(50.0, 70.0);

        MapHttpRequestView missing = baseRequest("/api/t");
        RequestFeatures m = extractor.extract(missing, "tok2", new RequestContext());
        assertThat(m.tokenAgeSeconds()).isEqualTo(-1.0);
    }

    @Test
    void largeStepSaturation_distinguishesModerateFromExtremeUnderRealisticVariance() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer);
        String id = "sat";
        String ep = "/api/sat";
        HttpRequestView req = new MapHttpRequestView().requestUri(ep).method("GET");

        // Mild realistic variance baseline
        double[] pattern = {9, 10, 11, 10, 9.5, 10.5, 10};
        for (int r = 0; r < 10; r++) {
            for (double v : pattern) {
                scorer.update(feat(id, ep, v));
            }
        }
        RiskDecision mild = engine.evaluate(req, id, feat(id, ep, 15), new RequestContext());
        RiskDecision large = engine.evaluate(req, id, feat(id, ep, 100), new RequestContext());
        RiskDecision huge = engine.evaluate(req, id, feat(id, ep, 1000), new RequestContext());

        System.out.printf(Locale.ROOT,
            "saturation mild=%.4f/%s large=%.4f/%s huge=%.4f/%s%n",
            mild.anomalyScore(), mild.action(),
            large.anomalyScore(), large.action(),
            huge.anomalyScore(), huge.action());

        assertThat(mild.anomalyScore()).isLessThan(large.anomalyScore());
        // Large and huge both may saturate sigmoid — document if indistinguishable at top.
        assertThat(large.anomalyScore()).isGreaterThanOrEqualTo(0.9);
        assertThat(Math.abs(huge.anomalyScore() - large.anomalyScore()))
            .as("extreme steps may saturate similarly at the top of the sigmoid")
            .isLessThan(0.05);
    }

    private static RequestFeatures feat(String id, String ep, double rpw) {
        return RequestFeatures.builder()
            .identityHash(id).endpoint(ep).timestampMillis(1L)
            .requestsPerWindow(rpw).endpointEntropy(0.2).endpointConcentration(0.5)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(1).ipBucket(1).build();
    }

    private static MapHttpRequestView baseRequest(String ep) {
        return new MapHttpRequestView().requestUri(ep).method("GET");
    }

    private static SentinelDecisionEngine engine(StatisticalScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
    }

    private enum NeverQ implements EnforcementHandler {
        INSTANCE;
        @Override
        public boolean apply(EnforcementAction a, HttpRequestView r, EnforcementResponse s, String i, String e) {
            return true;
        }
        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
