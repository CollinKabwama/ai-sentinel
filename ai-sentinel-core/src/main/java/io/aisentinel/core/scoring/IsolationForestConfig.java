package io.aisentinel.core.scoring;

/**
 * Configuration for Isolation Forest scorer and training.
 * Used by IsolationForestScorer; values typically come from SentinelProperties in the starter.
 */
public final class IsolationForestConfig {

    private final double fallbackScore;
    private final int minTrainingSamples;
    private final int numTrees;
    private final int maxDepth;
    private final long randomSeed;
    private final double sampleRate;
    /** Samples with anomaly score above this threshold are not added to the training buffer (anti-poisoning). */
    private final double trainingRejectionScoreThreshold;

    public IsolationForestConfig(double fallbackScore, int minTrainingSamples,
                                  int numTrees, int maxDepth, long randomSeed, double sampleRate) {
        this(fallbackScore, minTrainingSamples, numTrees, maxDepth, randomSeed, sampleRate, 0.7);
    }

    public IsolationForestConfig(double fallbackScore, int minTrainingSamples,
                                  int numTrees, int maxDepth, long randomSeed, double sampleRate,
                                  double trainingRejectionScoreThreshold) {
        this.fallbackScore = clamp(fallbackScore, 0.0, 1.0);
        this.minTrainingSamples = Math.max(1, minTrainingSamples);
        this.numTrees = Math.max(1, numTrees);
        this.maxDepth = Math.max(1, maxDepth);
        this.randomSeed = randomSeed;
        this.sampleRate = clamp(sampleRate, 0.0, 1.0);
        this.trainingRejectionScoreThreshold = clamp(trainingRejectionScoreThreshold, 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        if (v < lo) return lo;
        return Math.min(hi, v);
    }

    public double getFallbackScore() { return fallbackScore; }
    public int getMinTrainingSamples() { return minTrainingSamples; }
    public int getNumTrees() { return numTrees; }
    public int getMaxDepth() { return maxDepth; }
    public long getRandomSeed() { return randomSeed; }
    public double getSampleRate() { return sampleRate; }

    public double getTrainingRejectionScoreThreshold() {
        return trainingRejectionScoreThreshold;
    }
}
