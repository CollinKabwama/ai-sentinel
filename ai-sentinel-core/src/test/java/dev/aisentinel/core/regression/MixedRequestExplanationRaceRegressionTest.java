package dev.aisentinel.core.regression;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV11-001 — mixed-request explanation race.
 * <p>
 * Latch-controlled proof that decision A must publish explanation A (never B).
 * Fidelity: concurrency regression (pipeline + real statistical scorer).
 */
class MixedRequestExplanationRaceRegressionTest {

    private static final String ID_A = "id-slow";
    private static final String ID_B = "id-fast";
    private static final String EP_A = "/slow";
    private static final String EP_B = "/fast";

    @Test
    void decisionExplanation_doesNotMixAcrossConcurrentRequests() throws Exception {
        StatisticalScorer statistical = new StatisticalScorer(10_000, 300_000L, 2, 0.33);
        seedBaseline(statistical, ID_A, EP_A, features(ID_A, EP_A, 10, 2));
        seedBaseline(statistical, ID_B, EP_B, features(ID_B, EP_B, 10, 2));

        CountDownLatch aPublishedSnapshot = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);

        statistical.setAfterScoreHookForTests(features -> {
            if (ID_A.equals(features.identityHash())) {
                aPublishedSnapshot.countDown();
                try {
                    assertThat(bFinished.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });

        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(statistical, 1.0);

        LastDecisionExplanation holder = new LastDecisionExplanation();
        AtomicReference<LastDecisionExplanation.Snapshot> afterA = new AtomicReference<>();
        AtomicReference<LastDecisionExplanation.Snapshot> afterB = new AtomicReference<>();

        FeatureExtractor extractor = (request, identityHash, ctx) -> {
            if (ID_A.equals(identityHash)) {
                return features(ID_A, EP_A, 100, 2);
            }
            return features(ID_B, EP_B, 10, 50);
        };
        var pipeline = newPipeline(extractor, composite, holder);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> fa = pool.submit(() -> {
                pipeline.process(
                    new MapHttpRequestView().requestUri(EP_A).method("GET"),
                    DiscardingEnforcementResponse.INSTANCE,
                    ID_A);
                afterA.set(holder.get());
            });

            assertThat(aPublishedSnapshot.await(5, TimeUnit.SECONDS)).isTrue();

            pipeline.process(
                new MapHttpRequestView().requestUri(EP_B).method("GET"),
                DiscardingEnforcementResponse.INSTANCE,
                ID_B);
            afterB.set(holder.get());
            bFinished.countDown();

            fa.get(5, TimeUnit.SECONDS);

            assertThat(afterB.get()).isNotNull();
            assertThat(afterB.get().statisticalExplanation().dominantFeature())
                .isEqualTo("parameterCount");

            assertThat(afterA.get()).isNotNull();
            assertThat(afterA.get().statisticalExplanation().dominantFeature())
                .as("A must keep requestsPerWindow; mixing B's parameterCount is the REV11 race")
                .isEqualTo("requestsPerWindow");

            // Completion-order: A publishes last → JVM lastDecision is A
            assertThat(holder.get().statisticalExplanation().dominantFeature())
                .isEqualTo("requestsPerWindow");
        } finally {
            statistical.setAfterScoreHookForTests(null);
            bFinished.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentEvaluations_neverCrossMatchDominantFeature() throws Exception {
        StatisticalScorer statistical = new StatisticalScorer(50_000, 300_000L, 2, 0.33);
        int n = 64;
        for (int i = 0; i < n; i++) {
            seedBaseline(statistical, "rpw-" + i, "/r", features("rpw-" + i, "/r", 10, 2));
            seedBaseline(statistical, "par-" + i, "/p", features("par-" + i, "/p", 10, 2));
        }

        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(statistical, 1.0);
        AtomicInteger mismatches = new AtomicInteger();

        FeatureExtractor extractor = (request, identityHash, ctx) -> {
            if (identityHash.startsWith("rpw-")) {
                return features(identityHash, "/r", 100, 2);
            }
            return features(identityHash, "/p", 10, 50);
        };

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final String rpwId = "rpw-" + i;
                final String parId = "par-" + i;
                futures.add(pool.submit(() -> {
                    LastDecisionExplanation h1 = new LastDecisionExplanation();
                    LastDecisionExplanation h2 = new LastDecisionExplanation();
                    var p1 = newPipeline(extractor, composite, h1);
                    var p2 = newPipeline(extractor, composite, h2);
                    p1.process(new MapHttpRequestView().requestUri("/r").method("GET"),
                        DiscardingEnforcementResponse.INSTANCE, rpwId);
                    p2.process(new MapHttpRequestView().requestUri("/p").method("GET"),
                        DiscardingEnforcementResponse.INSTANCE, parId);
                    StatisticalScoreSnapshot s1 = h1.get() != null ? h1.get().statisticalExplanation() : null;
                    StatisticalScoreSnapshot s2 = h2.get() != null ? h2.get().statisticalExplanation() : null;
                    if (s1 == null || !"requestsPerWindow".equals(s1.dominantFeature())) {
                        mismatches.incrementAndGet();
                    }
                    if (s2 == null || !"parameterCount".equals(s2.dominantFeature())) {
                        mismatches.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            assertThat(mismatches.get()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void seedBaseline(StatisticalScorer scorer, String id, String ep, RequestFeatures calm) {
        for (int i = 0; i < 40; i++) {
            scorer.update(calm);
        }
    }

    private static RequestFeatures features(String id, String ep, double rpw, int params) {
        return RequestFeatures.builder()
            .identityHash(id)
            .endpoint(ep)
            .timestampMillis(0)
            .requestsPerWindow(rpw)
            .endpointEntropy(0.5)
            .endpointConcentration(0.5)
            .tokenAgeSeconds(60)
            .parameterCount(params)
            .payloadSizeBytes(100)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    private static dev.aisentinel.core.SentinelPipeline newPipeline(FeatureExtractor extractor,
                                                                   CompositeScorer composite,
                                                                   LastDecisionExplanation holder) {
        return new dev.aisentinel.core.SentinelPipeline(
            extractor,
            composite,
            composite,
            new ThresholdPolicyEngine(0.3, 0.4, 0.7, 0.9),
            (action, request, response, identityHash, endpoint) -> true,
            event -> { },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            null,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            BaselineLifecycle.disabled(),
            holder
        );
    }
}
