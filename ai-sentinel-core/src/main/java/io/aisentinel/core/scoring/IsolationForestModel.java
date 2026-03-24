package io.aisentinel.core.scoring;

import java.util.List;

/**
 * Immutable Isolation Forest model. Built by {@link IsolationForestTrainer}.
 * Score is in (0, 1]; higher = more anomalous. Caller clamps to [0, 1] if needed.
 */
final class IsolationForestModel {

    private static final double EPS = 1e-10;

    private final TreeNode[] trees;
    private final int numSamples;
    private final int dimension;

    IsolationForestModel(TreeNode[] trees, int numSamples, int dimension) {
        this.trees = trees;
        this.numSamples = numSamples;
        this.dimension = dimension;
    }

    /**
     * Anomaly score: 2^(-E[h(x)]/c(n)). In (0, 1]; 1 = high anomaly.
     * Returns value in (0, 1]; caller should clamp to [0, 1] and handle NaN.
     */
    double score(double[] x) {
        if (x == null || x.length != dimension || trees.length == 0) return 0.5;
        double sumPath = 0;
        for (TreeNode root : trees) {
            sumPath += pathLength(root, x, 0);
        }
        double avgPath = sumPath / trees.length;
        double c = averagePathLength(numSamples);
        double s = Math.pow(2, -avgPath / (c + EPS));
        if (Double.isNaN(s) || Double.isInfinite(s) || s <= 0) return 0.5;
        return Math.min(1.0, s);
    }

    private static double pathLength(TreeNode node, double[] x, int depth) {
        if (node == null) return depth;
        if (node.isLeaf) return depth + leafDepthCorrection(node.size);
        int f = node.featureIndex;
        if (f < 0 || f >= x.length) return depth;
        if (x[f] < node.splitValue) return pathLength(node.left, x, depth + 1);
        return pathLength(node.right, x, depth + 1);
    }

    /** c(n) = 2*H(n-1) - 2*(n-1)/n; approximate with 2*ln(n-1)+0.5772 - 2*(n-1)/n for n>=2 */
    private static double averagePathLength(int n) {
        if (n <= 1) return 1.0;
        double ln = Math.log(n - 1 + EPS);
        return 2 * (ln + 0.5772156649) - 2.0 * (n - 1) / n;
    }

    /** Leaf with size s gets depth += 2*(ln(s-1)+0.5772) - 2*(s-1)/s for s>1 */
    private static double leafDepthCorrection(int size) {
        if (size <= 1) return 0;
        double ln = Math.log(size - 1 + EPS);
        return 2 * (ln + 0.5772156649) - 2.0 * (size - 1) / size;
    }

    static final class TreeNode {
        final int featureIndex;
        final double splitValue;
        final TreeNode left;
        final TreeNode right;
        final boolean isLeaf;
        final int size;

        TreeNode(int featureIndex, double splitValue, TreeNode left, TreeNode right) {
            this.featureIndex = featureIndex;
            this.splitValue = splitValue;
            this.left = left;
            this.right = right;
            this.isLeaf = false;
            this.size = 0;
        }

        TreeNode(int size) {
            this.featureIndex = -1;
            this.splitValue = 0;
            this.left = null;
            this.right = null;
            this.isLeaf = true;
            this.size = size;
        }
    }
}
