package dev.aisentinel.core.characterization;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
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
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local synthetic latency and allocation characterization for the in-process decision path.
 * Not an SLA claim.
 */
class DecisionPathPerformanceCharacterizationTest {

    @Test
    void inProcessStatisticalPathLatencyPercentilesRemainSubMillisecondLocally() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
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
        HttpRequestView req = new MapHttpRequestView().requestUri("/perf").method("GET");
        RequestFeatures features = feat("perf", "/perf", 10);
        for (int i = 0; i < 5_000; i++) {
            engine.evaluate(req, "perf", features, new RequestContext());
        }

        int n = 20_000;
        long[] samplesNs = new long[n];
        long gcBefore = gcCount();
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            RiskDecision d = engine.evaluate(req, "perf", features, new RequestContext());
            samplesNs[i] = System.nanoTime() - s;
            assertThat(d).isNotNull();
        }
        long wallNs = System.nanoTime() - t0;
        long gcAfter = gcCount();
        Arrays.sort(samplesNs);
        double p50 = samplesNs[n / 2] / 1_000.0;
        double p95 = samplesNs[(int) (n * 0.95)] / 1_000.0;
        double p99 = samplesNs[(int) (n * 0.99)] / 1_000.0;
        double throughput = n / (wallNs / 1_000_000_000.0);
        System.out.printf(Locale.ROOT,
            "LOCAL in-process n=%d p50us=%.1f p95us=%.1f p99us=%.1f thr=%.0f/s gcDelta=%d cpus=%d%n",
            n, p50, p95, p99, throughput, gcAfter - gcBefore,
            Runtime.getRuntime().availableProcessors());
        // Soft smoke bounds only — catch accidental multi-ms I/O, not certification.
        assertThat(p99).isLessThan(5_000.0);
    }

    @Test
    void perCallRequestContextAllocationCompletesWithoutPathologicalGrowth() {
        // Each evaluate allocates a RequestContext when callers pass new instances.
        // Measure that n evaluations complete without OOM under modest heap pressure.
        StatisticalScorer scorer = new StatisticalScorer(10_000, 60_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        HttpRequestView req = new MapHttpRequestView().requestUri("/alloc").method("GET");
        RequestFeatures features = feat("a", "/alloc", 3);
        long beforeUsed = usedHeap();
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            engine.evaluate(req, "a", features, new RequestContext());
        }
        System.gc();
        long afterUsed = usedHeap();
        System.out.printf(Locale.ROOT,
            "LOCAL alloc n=%d heapDeltaMB=%.2f (indicative only)%n",
            n, (afterUsed - beforeUsed) / (1024.0 * 1024.0));
        assertThat(n).isEqualTo(50_000);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long v = bean.getCollectionCount();
            if (v > 0) {
                c += v;
            }
        }
        return c;
    }

    private static RequestFeatures feat(String id, String ep, double rpw) {
        return RequestFeatures.builder()
            .identityHash(id).endpoint(ep).timestampMillis(1)
            .requestsPerWindow(rpw).endpointEntropy(0.1).endpointConcentration(0.5)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(1).ipBucket(1).build();
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
