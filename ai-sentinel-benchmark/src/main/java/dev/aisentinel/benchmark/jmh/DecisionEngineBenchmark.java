package dev.aisentinel.benchmark.jmh;

import dev.aisentinel.benchmark.fixture.BenchmarkComponentFactory;
import dev.aisentinel.benchmark.fixture.BenchmarkFeatureFactory;
import dev.aisentinel.benchmark.fixture.BenchmarkHttpRequestView;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Framework-independent decision-engine latency for representative workloads.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class DecisionEngineBenchmark {

    @Param({"establishedBaseline", "warmupSparse", "abruptDeviation", "invalidScore"})
    public String workload;

    private SentinelDecisionEngine engine;
    private HttpRequestView request;
    private RequestFeatures features;
    private String identity;

    @Setup(Level.Trial)
    public void setup() {
        identity = "engine-id";
        request = BenchmarkHttpRequestView.typical();
        switch (workload) {
            case "establishedBaseline" -> {
                StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
                BenchmarkComponentFactory.seedEstablishedBaseline(scorer, identity, "/api/benchmark", 64);
                engine = BenchmarkComponentFactory.newDecisionEngine(scorer);
                features = BenchmarkFeatureFactory.establishedBaseline(identity, "/api/benchmark");
            }
            case "warmupSparse" -> {
                StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
                engine = BenchmarkComponentFactory.newDecisionEngine(scorer);
                features = BenchmarkFeatureFactory.warmupSparse(identity, "/api/benchmark");
            }
            case "abruptDeviation" -> {
                StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
                BenchmarkComponentFactory.seedEstablishedBaseline(scorer, identity, "/api/benchmark", 64);
                engine = BenchmarkComponentFactory.newDecisionEngine(scorer);
                features = BenchmarkFeatureFactory.abruptVolumeDeviation(identity, "/api/benchmark");
            }
            case "invalidScore" -> {
                engine = BenchmarkComponentFactory.newDecisionEngine(BenchmarkComponentFactory.invalidScoreScorer());
                features = BenchmarkFeatureFactory.establishedBaseline(identity, "/api/benchmark");
            }
            default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        }
        RiskDecision probe = engine.evaluate(request, identity, features, new RequestContext());
        if (probe == null || probe.action() == null) {
            throw new IllegalStateException("Decision engine probe returned null decision");
        }
        if ("warmupSparse".equals(workload)) {
            resetWarmupSparse();
        }
    }

    @Setup(Level.Invocation)
    public void setupInvocation() {
        if ("warmupSparse".equals(workload)) {
            resetWarmupSparse();
        }
    }

    @Benchmark
    public void evaluate(Blackhole blackhole) {
        blackhole.consume(engine.evaluate(request, identity, features, new RequestContext()));
    }

    private void resetWarmupSparse() {
        StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
        engine = BenchmarkComponentFactory.newDecisionEngine(scorer);
        features = BenchmarkFeatureFactory.warmupSparse(identity, "/api/benchmark");
    }
}
