package io.aisentinel.core.scoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds an IsolationForestModel from a list of feature vectors using a fixed seed for determinism.
 */
public final class IsolationForestTrainer {

    private final int numTrees;
    private final int maxDepth;
    private final long randomSeed;

    public IsolationForestTrainer(int numTrees, int maxDepth, long randomSeed) {
        this.numTrees = Math.max(1, numTrees);
        this.maxDepth = Math.max(1, maxDepth);
        this.randomSeed = randomSeed;
    }

    /**
     * Builds a model from the given samples. Returns null if samples are empty or dimension mismatch.
     */
    public IsolationForestModel train(List<double[]> samples) {
        if (samples == null || samples.isEmpty()) return null;
        int dim = samples.get(0).length;
        for (double[] row : samples) {
            if (row.length != dim) return null;
        }
        int n = samples.size();
        IsolationForestModel.TreeNode[] trees = new IsolationForestModel.TreeNode[numTrees];
        for (int t = 0; t < numTrees; t++) {
            Random rng = new Random(randomSeed + t);
            trees[t] = buildTree(samples, 0, dim, rng);
        }
        return new IsolationForestModel(trees, n, dim);
    }

    private IsolationForestModel.TreeNode buildTree(List<double[]> data, int depth, int dimension, Random rng) {
        if (data.isEmpty()) return new IsolationForestModel.TreeNode(0);
        if (data.size() == 1 || depth >= maxDepth) return new IsolationForestModel.TreeNode(data.size());

        int featureIndex = rng.nextInt(dimension);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double[] row : data) {
            double v = row[featureIndex];
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (min >= max) return new IsolationForestModel.TreeNode(data.size());

        double splitValue = min + (max - min) * (0.2 + 0.6 * rng.nextDouble());
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (double[] row : data) {
            if (row[featureIndex] < splitValue) left.add(row);
            else right.add(row);
        }
        if (left.isEmpty() || right.isEmpty()) return new IsolationForestModel.TreeNode(data.size());

        IsolationForestModel.TreeNode leftNode = buildTree(left, depth + 1, dimension, rng);
        IsolationForestModel.TreeNode rightNode = buildTree(right, depth + 1, dimension, rng);
        return new IsolationForestModel.TreeNode(featureIndex, splitValue, leftNode, rightNode);
    }
}
