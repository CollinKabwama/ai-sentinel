package dev.aisentinel.core.scenario;

import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.BaselineUpdateStrategy;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.GatedObservation;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.THRESHOLD_ELEVATED;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.THRESHOLD_MODERATE;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.baseFeatures;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.gatedEvaluate;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newDefaultPolicy;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newStatisticalScorer;

/**
 * Test-only baseline update strategy comparison. Real {@link StatisticalScorer} + {@link PolicyEngine};
 * the test runner controls {@code update()} for strategies not yet expressed solely through
 * {@link SentinelDecisionEngine} (production now defaults to {@code ALLOW_OR_MONITOR} gating).
 * <p>
 * Each strategy starts from a <strong>seeded</strong> calm baseline (forced updates) so results are not
 * confounded by warmupScore=0.4 mapping to THROTTLE when this harness scores→policy directly
 * (production {@link SentinelDecisionEngine} applies configurable warmup action, default MONITOR).
 */
class BaselineUpdateStrategyComparisonTest {

    private static final String IDENTITY = "id-strategy";
    private static final String ENDPOINT = "/api/checkout";
    private static final double SCORE_GATE = THRESHOLD_ELEVATED;
    private static final int DELAYED_PROMOTE_AFTER = 3;
    private static final int SEED_UPDATES = 30;

    private static final Set<EnforcementAction> STRICT = EnumSet.of(
        EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);

    @Test
    void strategies_comparedAcrossBenignRampStepSustainedAndRecovery() {
        List<StrategyResult> results = new ArrayList<>();
        for (BaselineUpdateStrategy strategy : new BaselineUpdateStrategy[] {
            BaselineUpdateStrategy.ALWAYS_UPDATE,
            BaselineUpdateStrategy.UPDATE_ON_ALLOW,
            BaselineUpdateStrategy.UPDATE_ON_ALLOW_OR_MONITOR,
            BaselineUpdateStrategy.SCORE_GATE,
            BaselineUpdateStrategy.DELAYED_PROMOTE
        }) {
            StrategyResult r = runMatrix(strategy);
            results.add(r);
            System.out.println(r.line());
        }

        StrategyResult always = results.get(0);
        StrategyResult allowOnly = results.get(1);
        StrategyResult allowMon = results.get(2);
        StrategyResult gate = results.get(3);

        assertThat(always.suddenThrottlePlus).isGreaterThan(0);
        assertThat(allowOnly.suddenFirstScore).isGreaterThanOrEqualTo(0.9);
        assertThat(allowMon.suddenThrottlePlus).isGreaterThanOrEqualTo(always.suddenThrottlePlus);
        assertThat(gate.suddenThrottlePlus).isGreaterThanOrEqualTo(always.suddenThrottlePlus);
        assertThat(allowOnly.benignStaysAllow).isTrue();
        assertThat(always.recoveryAllowAt).isGreaterThan(0);

        // Cold-start footnote: without seeding, UPDATE_ON_ALLOW never learns when this harness
        // maps warmupScore 0.4 → THROTTLE (score→policy only; not the production warmup-action path).
        assertThat(coldStartAllowOnlyNeverUpdates()).isTrue();
    }

    /** Documents warmup ∩ gated-update deadlock without seeding. */
    private static boolean coldStartAllowOnlyNeverUpdates() {
        StatisticalScorer scorer = newStatisticalScorer();
        PolicyEngine policy = newDefaultPolicy();
        int updates = 0;
        for (int i = 0; i < 20; i++) {
            GatedObservation o = gatedEvaluate(scorer, policy,
                baseFeatures(IDENTITY, ENDPOINT, 10),
                BaselineUpdateStrategy.UPDATE_ON_ALLOW, SCORE_GATE);
            if (o.updated()) {
                updates++;
            }
        }
        System.out.printf(Locale.ROOT,
            "coldStart UPDATE_ON_ALLOW without seed: updates=%d (warmup 0.4→THROTTLE blocks learning)%n", updates);
        return updates == 0;
    }

    private static void seedCalm(StatisticalScorer scorer) {
        RequestFeatures calm = baseFeatures(IDENTITY, ENDPOINT, 10);
        for (int i = 0; i < SEED_UPDATES; i++) {
            scorer.update(calm);
        }
    }

    private static StrategyResult runMatrix(BaselineUpdateStrategy strategy) {
        DelayedState delay = new DelayedState();

        // --- 1. Benign after seed ---
        StatisticalScorer scorer = newStatisticalScorer();
        PolicyEngine policy = newDefaultPolicy();
        seedCalm(scorer);
        delay.pending.clear();
        int updates = 0;
        int rejected = 0;
        int allowStreak = 0;
        for (int i = 1; i <= 20; i++) {
            GatedObservation o = step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, 10), delay);
            if (o.updated()) {
                updates++;
            } else {
                rejected++;
            }
            if (o.action() == EnforcementAction.ALLOW) {
                allowStreak++;
            }
        }
        boolean benignStaysAllow = allowStreak >= 15;

        // --- 2. Gradual ramp ---
        scorer = newStatisticalScorer();
        seedCalm(scorer);
        delay = new DelayedState();
        int rampThrottlePlus = 0;
        int rampMonitorPlus = 0;
        for (int rpw = 1; rpw <= 40; rpw++) {
            GatedObservation o = step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, rpw), delay);
            if (STRICT.contains(o.action())) {
                rampThrottlePlus++;
            }
            if (o.anomalyScore() >= THRESHOLD_MODERATE) {
                rampMonitorPlus++;
            }
        }

        // --- 3. Sudden step ---
        scorer = newStatisticalScorer();
        seedCalm(scorer);
        delay = new DelayedState();
        double[] pat = {9, 10, 11, 10, 9, 11, 10};
        for (int r = 0; r < 4; r++) {
            for (double v : pat) {
                step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, v), delay);
            }
        }
        step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, 10), delay);
        List<GatedObservation> elevated = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            elevated.add(step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, 100), delay));
        }
        double suddenFirst = elevated.get(0).anomalyScore();
        int suddenThrottlePlus = 0;
        Integer firstBelowThrottle = null;
        for (int i = 0; i < elevated.size(); i++) {
            if (STRICT.contains(elevated.get(i).action())) {
                suddenThrottlePlus++;
            } else if (firstBelowThrottle == null) {
                firstBelowThrottle = i + 1;
            }
        }

        // --- 4–5. Sustained elevated then return to normal ---
        scorer = newStatisticalScorer();
        seedCalm(scorer);
        delay = new DelayedState();
        int sustainedStrict = 0;
        EnforcementAction lastSustained = null;
        for (int i = 0; i < 40; i++) {
            GatedObservation o = step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, 100), delay);
            if (STRICT.contains(o.action())) {
                sustainedStrict++;
            }
            lastSustained = o.action();
        }
        int recoveryAllowAt = -1;
        for (int i = 1; i <= 40; i++) {
            GatedObservation o = step(scorer, policy, strategy, baseFeatures(IDENTITY, ENDPOINT, 10), delay);
            if (recoveryAllowAt < 0 && o.action() == EnforcementAction.ALLOW) {
                recoveryAllowAt = i;
            }
        }

        return new StrategyResult(
            strategy.name(),
            benignStaysAllow,
            rampThrottlePlus,
            rampMonitorPlus,
            suddenFirst,
            suddenThrottlePlus,
            firstBelowThrottle,
            sustainedStrict,
            lastSustained,
            recoveryAllowAt,
            updates,
            rejected
        );
    }

    private static GatedObservation step(StatisticalScorer scorer, PolicyEngine policy,
                                         BaselineUpdateStrategy strategy, RequestFeatures features,
                                         DelayedState delay) {
        if (strategy != BaselineUpdateStrategy.DELAYED_PROMOTE) {
            return gatedEvaluate(scorer, policy, features, strategy, SCORE_GATE);
        }
        double score = scorer.score(features);
        EnforcementAction action = policy.evaluate(score, features, features.endpoint());
        boolean lowRisk = action == EnforcementAction.ALLOW || action == EnforcementAction.MONITOR;
        if (lowRisk) {
            delay.pending.add(features);
            if (delay.pending.size() >= DELAYED_PROMOTE_AFTER) {
                for (RequestFeatures f : delay.pending) {
                    scorer.update(f);
                }
                delay.pending.clear();
                return new GatedObservation(score, action, true);
            }
            return new GatedObservation(score, action, false);
        }
        delay.pending.clear();
        return new GatedObservation(score, action, false);
    }

    private static final class DelayedState {
        final List<RequestFeatures> pending = new ArrayList<>();
    }

    private record StrategyResult(
        String name,
        boolean benignStaysAllow,
        int rampThrottlePlus,
        int rampMonitorPlus,
        double suddenFirstScore,
        int suddenThrottlePlus,
        Integer firstBelowThrottle,
        int sustainedStrict,
        EnforcementAction lastSustained,
        int recoveryAllowAt,
        int updatesAccepted,
        int rejected
    ) {
        String line() {
            return String.format(Locale.ROOT,
                "strategy=%s benignStaysAllow=%s rampThrottle+=%d rampMonitor+=%d "
                    + "suddenFirst=%.4f suddenThrottle+=%d firstBelowThrottle=%s "
                    + "sustainedStrict=%d lastSustained=%s recoveryAllowAt=%d updates=%d rejected=%d",
                name, benignStaysAllow, rampThrottlePlus, rampMonitorPlus,
                suddenFirstScore, suddenThrottlePlus, firstBelowThrottle,
                sustainedStrict, lastSustained, recoveryAllowAt, updatesAccepted, rejected);
        }
    }
}
