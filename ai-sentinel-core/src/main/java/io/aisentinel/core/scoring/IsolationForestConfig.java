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

    public IsolationForestConfig(double fallbackScore, int minTrainingSamples,
                                  int numTrees, int maxDepth, long randomSeed, double sampleRate) {
        this.fallbackScore = clamp(fallbackScore, 0.0, 1.0);
        this.minTrainingSamples = Math.max(1, minTrainingSamples);
        this.numTrees = Math.max(1, numTrees);
        this.maxDepth = Math.max(1, maxDepth);
        this.randomSeed = randomSeed;
        this.sampleRate = clamp(sampleRate, 0.0, 1.0);
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
}
