package netgraph.spatial;

import java.util.Arrays;

import netgraph.util.IntBag;

/**
 * Primitive uniform-grid spatial index for viewport culling and hit testing.
 */
public final class SpatialIndex {
    private static final int MAX_QUERY_CELLS = 262_144;

    private final float cellSize;

    private long[] cellKeys;
    private int[] cellHeads;
    private byte[] used;
    private int[] nextNode;
    private int mask;

    public SpatialIndex(
            final int expectedNodeCount,
            final float cellSize) {

        if (expectedNodeCount < 0) {
            throw new IllegalArgumentException(
                    "Expected node count cannot be negative.");
        }

        if (!(cellSize > 0.0f)) {
            throw new IllegalArgumentException(
                    "Cell size must be positive.");
        }

        this.cellSize = cellSize;

        allocate(
                Math.max(
                        16,
                        expectedNodeCount * 2));

        nextNode = new int[Math.max(0, expectedNodeCount)];
    }

    public void rebuild(
            final float[] x,
            final float[] y,
            final int nodeCount) {

        if (x == null || y == null
                || nodeCount < 0
                || x.length < nodeCount
                || y.length < nodeCount) {

            throw new IllegalArgumentException(
                    "Invalid coordinate arrays or node count.");
        }

        if (nextNode.length < nodeCount) {
            nextNode = new int[nodeCount];
        }

        final int required =
                Math.max(16, nodeCount * 2);

        if (cellHeads.length < required) {
            allocate(required);
        } else {
            Arrays.fill(used, (byte) 0);
            Arrays.fill(cellHeads, -1);
        }

        for (int nodeId = 0;
                nodeId < nodeCount;
                nodeId++) {

            final int cellX = floorCell(x[nodeId]);
            final int cellY = floorCell(y[nodeId]);

            final int slot =
                    findOrCreateSlot(
                            cellKey(cellX, cellY));

            nextNode[nodeId] = cellHeads[slot];
            cellHeads[slot] = nodeId;
        }
    }

    public void query(
            final float left,
            final float top,
            final float right,
            final float bottom,
            final IntBag output) {

        if (output == null) {
            throw new IllegalArgumentException(
                    "Output collection is required.");
        }

        output.clear();

        final int minX = floorCell(left);
        final int minY = floorCell(top);
        final int maxX = floorCell(right);
        final int maxY = floorCell(bottom);

        final long width =
                (long) maxX - minX + 1L;

        final long height =
                (long) maxY - minY + 1L;

        if (width <= 0L
                || height <= 0L
                || width * height > MAX_QUERY_CELLS) {

            return;
        }

        for (int cellX = minX;
                cellX <= maxX;
                cellX++) {

            for (int cellY = minY;
                    cellY <= maxY;
                    cellY++) {

                final int slot =
                        findSlot(
                                cellKey(cellX, cellY));

                if (slot < 0) {
                    continue;
                }

                for (int nodeId = cellHeads[slot];
                        nodeId >= 0;
                        nodeId = nextNode[nodeId]) {

                    output.add(nodeId);
                }
            }
        }
    }

    private int floorCell(final float coordinate) {
        return (int) Math.floor(
                coordinate / cellSize);
    }

    private int findOrCreateSlot(final long key) {
        int slot = mix(key) & mask;

        while (used[slot] != 0) {
            if (cellKeys[slot] == key) {
                return slot;
            }

            slot = (slot + 1) & mask;
        }

        used[slot] = 1;
        cellKeys[slot] = key;
        cellHeads[slot] = -1;

        return slot;
    }

    private int findSlot(final long key) {
        int slot = mix(key) & mask;

        while (used[slot] != 0) {
            if (cellKeys[slot] == key) {
                return slot;
            }

            slot = (slot + 1) & mask;
        }

        return -1;
    }

    private void allocate(final int expected) {
        int capacity = 1;

        while (capacity < expected) {
            capacity <<= 1;
        }

        cellKeys = new long[capacity];
        cellHeads = new int[capacity];
        used = new byte[capacity];

        Arrays.fill(cellHeads, -1);

        mask = capacity - 1;
    }

    private static long cellKey(
            final int cellX,
            final int cellY) {

        return ((long) cellX << 32)
                ^ (cellY & 0xFFFFFFFFL);
    }

    private static int mix(final long value) {
        long mixed = value;

        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;

        return (int) mixed;
    }
}