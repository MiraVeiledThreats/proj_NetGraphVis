package netgraph.cluster;

import netgraph.spatial.SpatialIndex;

/** Immutable packed visual projection used only by clustered rendering. */
public final class ClusterProjection {
    public static final byte KIND_NETWORK = 1;
    public static final byte KIND_RAW_NODE = 2;
    public static final byte KIND_ANALYST = 3;
    public static final byte KIND_PARTIAL = 4;
    public static final byte KIND_UNDETERMINED = 5;

    private static final ClusterProjection EMPTY = new ClusterProjection(
            new byte[0], new int[0], new int[0], new int[0], new String[0],
            new float[0], new float[0], new int[0], new int[0][0],
            new long[0][0], new long[0], new long[0], new long[0],
            new int[0], new int[0], new long[0], new long[0], new long[0],
            new long[0], new byte[0], new int[0], null, new String[0], new float[0],
            new float[0], new float[0], new float[0], new int[0],
            VisualEdgeIncidenceIndex.build(0, new int[0], new int[0]));

    private final byte[] kinds;
    private final int[] networkIds;
    private final int[] rawNodeIds;
    private final int[] analystClusterIds;
    private final String[] labels;
    private final float[] x;
    private final float[] y;
    private final int[] memberCounts;
    private final int[][] innerTagCounts;
    private final long[][] outerTagWeights;
    private final long[] internalConnectionCounts;
    private final long[] internalBytes;
    private final long[] internalPackets;
    private final int[] edgeSource;
    private final int[] edgeTarget;
    private final long[] edgeConnectionCounts;
    private final long[] edgeSuccessfulCounts;
    private final long[] edgeBytes;
    private final long[] edgePackets;
    private final byte[] edgeEndpointTagMasks;
    private final int[] rawNodeToVisual;
    private final SpatialIndex spatialIndex;
    private final String[] analystBorderLabels;
    private final float[] analystMinX;
    private final float[] analystMinY;
    private final float[] analystMaxX;
    private final float[] analystMaxY;
    private final int[] analystBorderMemberCounts;
    private final VisualEdgeIncidenceIndex visualEdgeIncidenceIndex;

    ClusterProjection(
            final byte[] kinds,
            final int[] networkIds,
            final int[] rawNodeIds,
            final int[] analystClusterIds,
            final String[] labels,
            final float[] x,
            final float[] y,
            final int[] memberCounts,
            final int[][] innerTagCounts,
            final long[][] outerTagWeights,
            final long[] internalConnectionCounts,
            final long[] internalBytes,
            final long[] internalPackets,
            final int[] edgeSource,
            final int[] edgeTarget,
            final long[] edgeConnectionCounts,
            final long[] edgeSuccessfulCounts,
            final long[] edgeBytes,
            final long[] edgePackets,
            final byte[] edgeEndpointTagMasks,
            final int[] rawNodeToVisual,
            final SpatialIndex spatialIndex,
            final String[] analystBorderLabels,
            final float[] analystMinX,
            final float[] analystMinY,
            final float[] analystMaxX,
            final float[] analystMaxY,
            final int[] analystBorderMemberCounts,
            final VisualEdgeIncidenceIndex visualEdgeIncidenceIndex) {

        this.kinds = kinds;
        this.networkIds = networkIds;
        this.rawNodeIds = rawNodeIds;
        this.analystClusterIds = analystClusterIds;
        this.labels = labels;
        this.x = x;
        this.y = y;
        this.memberCounts = memberCounts;
        this.innerTagCounts = innerTagCounts;
        this.outerTagWeights = outerTagWeights;
        this.internalConnectionCounts = internalConnectionCounts;
        this.internalBytes = internalBytes;
        this.internalPackets = internalPackets;
        this.edgeSource = edgeSource;
        this.edgeTarget = edgeTarget;
        this.edgeConnectionCounts = edgeConnectionCounts;
        this.edgeSuccessfulCounts = edgeSuccessfulCounts;
        this.edgeBytes = edgeBytes;
        this.edgePackets = edgePackets;
        this.edgeEndpointTagMasks = edgeEndpointTagMasks;
        this.rawNodeToVisual = rawNodeToVisual;
        this.spatialIndex = spatialIndex;
        this.analystBorderLabels = analystBorderLabels;
        this.analystMinX = analystMinX;
        this.analystMinY = analystMinY;
        this.analystMaxX = analystMaxX;
        this.analystMaxY = analystMaxY;
        this.analystBorderMemberCounts = analystBorderMemberCounts;
        this.visualEdgeIncidenceIndex = visualEdgeIncidenceIndex;
    }

    public static ClusterProjection empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return kinds.length == 0;
    }

    public int getVisualNodeCount() {
        return kinds.length;
    }

    public int getVisualEdgeCount() {
        return edgeSource.length;
    }

    public byte getKind(final int id) {
        return kinds[id];
    }

    public boolean isCluster(final int id) {
        return kinds[id] != KIND_RAW_NODE;
    }

    public int getNetworkId(final int id) {
        return networkIds[id];
    }

    public int getRawNodeId(final int id) {
        return rawNodeIds[id];
    }

    public int getAnalystClusterId(final int id) {
        return analystClusterIds[id];
    }

    /**
     * For KIND_PARTIAL nodes, returns the active partial-unclustering definition
     * ID. The backing packed slot is shared with analystClusterIds because a
     * projection node is either an analyst cluster or a partial cluster, never both.
     */
    public int getPartialUnclusterId(final int id) {
        return analystClusterIds[id];
    }

    public int getPartialGroupId(
            final int id) {

        return rawNodeIds[id];
    }

    public String getLabel(final int id) {
        return labels[id];
    }

    public float getX(final int id) {
        return x[id];
    }

    public float getY(final int id) {
        return y[id];
    }

    public int getMemberCount(final int id) {
        return memberCounts[id];
    }

    public int getInnerTagCount(final int id, final int tag) {
        return innerTagCounts[tag][id];
    }

    public long getOuterTagWeight(final int id, final int tag) {
        return outerTagWeights[tag][id];
    }

    public long getInternalConnectionCount(final int id) {
        return internalConnectionCounts[id];
    }

    public long getInternalBytes(final int id) {
        return internalBytes[id];
    }

    public long getInternalPackets(final int id) {
        return internalPackets[id];
    }

    public int getEdgeSource(final int edgeId) {
        return edgeSource[edgeId];
    }

    public int getEdgeTarget(final int edgeId) {
        return edgeTarget[edgeId];
    }

    public long getEdgeConnectionCount(final int edgeId) {
        return edgeConnectionCounts[edgeId];
    }

    public long getEdgeSuccessfulCount(final int edgeId) {
        return edgeSuccessfulCounts[edgeId];
    }

    public long getEdgeBytes(final int edgeId) {
        return edgeBytes[edgeId];
    }

    public long getEdgePackets(final int edgeId) {
        return edgePackets[edgeId];
    }

    public byte getEdgeEndpointTagMask(
            final int edgeId) {

        return edgeEndpointTagMasks[edgeId];
    }

    public int getVisualForRawNode(final int rawNodeId) {
        return rawNodeToVisual[rawNodeId];
    }

    public SpatialIndex getSpatialIndex() {
        return spatialIndex;
    }

    public int getAnalystBorderCount() {
        return analystBorderLabels.length;
    }

    public String getAnalystBorderLabel(final int id) {
        return analystBorderLabels[id];
    }

    public float getAnalystBorderMinX(final int id) {
        return analystMinX[id];
    }

    public float getAnalystBorderMinY(final int id) {
        return analystMinY[id];
    }

    public float getAnalystBorderMaxX(final int id) {
        return analystMaxX[id];
    }

    public float getAnalystBorderMaxY(final int id) {
        return analystMaxY[id];
    }

    public int getAnalystBorderMemberCount(final int id) {
        return analystBorderMemberCounts[id];
    }

    public int getVisualIncidentEdgeStart(final int visualNodeId) {
        return visualEdgeIncidenceIndex.getIncidentEdgeStart(visualNodeId);
    }

    public int getVisualIncidentEdgeEnd(final int visualNodeId) {
        return visualEdgeIncidenceIndex.getIncidentEdgeEnd(visualNodeId);
    }

    public int getVisualIncidentEdgeId(final int incidenceIndex) {
        return visualEdgeIncidenceIndex.getIncidentEdgeId(incidenceIndex);
    }
}