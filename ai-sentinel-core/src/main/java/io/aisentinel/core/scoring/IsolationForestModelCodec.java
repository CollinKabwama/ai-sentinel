package io.aisentinel.core.scoring;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Binary serialization for {@link IsolationForestModel} (trainer publish → node load).
 * Format is explicit and versioned; not intended as an untrusted cross-boundary format without checksum + metadata gates.
 */
public final class IsolationForestModelCodec {

    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[] {'A', 'I', 'F', '1'};
    private static final int FORMAT_VERSION = 1;

    private IsolationForestModelCodec() {
    }

    public static byte[] encode(IsolationForestModel model) throws IOException {
        Objects.requireNonNull(model, "model");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.write(MAGIC);
        out.writeInt(FORMAT_VERSION);
        IsolationForestModel.TreeNode[] trees = model.treesForCodec();
        out.writeInt(trees.length);
        out.writeInt(model.trainingSampleCount());
        out.writeInt(model.featureDimension());
        for (IsolationForestModel.TreeNode root : trees) {
            writeNode(out, root);
        }
        out.flush();
        byte[] bytes = bos.toByteArray();
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("encoded model exceeds max payload size");
        }
        return bytes;
    }

    public static IsolationForestModel decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < MAGIC.length + 16) {
            throw new IOException("payload too small");
        }
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IOException("payload too large");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        byte[] m = new byte[4];
        in.readFully(m);
        if (!Arrays.equals(m, MAGIC)) {
            throw new IOException("bad magic");
        }
        int ver = in.readInt();
        if (ver != FORMAT_VERSION) {
            throw new IOException("unsupported format version: " + ver);
        }
        int numTrees = in.readInt();
        int numSamples = in.readInt();
        int dimension = in.readInt();
        if (numTrees <= 0 || numTrees > 50_000 || dimension <= 0 || dimension > 256 || numSamples < 0) {
            throw new IOException("invalid header");
        }
        IsolationForestModel.TreeNode[] trees = new IsolationForestModel.TreeNode[numTrees];
        for (int i = 0; i < numTrees; i++) {
            trees[i] = readNode(in);
        }
        if (in.available() > 0) {
            throw new IOException("trailing bytes");
        }
        return new IsolationForestModel(trees, numSamples, dimension);
    }

    private static void writeNode(DataOutputStream out, IsolationForestModel.TreeNode n) throws IOException {
        if (n.isLeaf) {
            out.writeByte(0);
            out.writeInt(n.size);
        } else {
            out.writeByte(1);
            out.writeInt(n.featureIndex);
            out.writeDouble(n.splitValue);
            writeNode(out, n.left);
            writeNode(out, n.right);
        }
    }

    private static IsolationForestModel.TreeNode readNode(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        if (kind == 0) {
            int size = in.readInt();
            if (size < 0) {
                throw new IOException("bad leaf size");
            }
            return new IsolationForestModel.TreeNode(size);
        }
        if (kind != 1) {
            throw new IOException("bad node kind");
        }
        int fi = in.readInt();
        double sv = in.readDouble();
        IsolationForestModel.TreeNode left = readNode(in);
        IsolationForestModel.TreeNode right = readNode(in);
        return new IsolationForestModel.TreeNode(fi, sv, left, right);
    }
}
