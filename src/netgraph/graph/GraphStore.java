package netgraph.graph;

import java.util.Arrays;
import java.util.Locale;

import netgraph.layout.LayoutState;
import netgraph.metadata.GraphMetadata;
import netgraph.spatial.SpatialIndex;
import netgraph.util.DeviceTypes;
import netgraph.util.LongIntIndex;
import netgraph.util.StringIntIndex;

/**
 * Immutable packed graph topology and aggregate traffic storage.
 *
 * Graph topology, traffic aggregates, node types, destination-port summaries,
 * node-to-edge incidence, layout state, and spatial-index state are retained
 * without creating a node or edge object per imported record.
 */
public final class GraphStore {
    public static final byte TYPE_IT = DeviceTypes.IT;
    public static final byte TYPE_OT = DeviceTypes.OT;
    public static final byte TYPE_IOC = DeviceTypes.IOC;
    public static final byte TYPE_UNK = DeviceTypes.UNK;

    private final int nodeCount;
    private final int edgeCount;
    private final int portCount;

    private final int[] edgeSource;
    private final int[] edgeTarget;

    private final long[] edgeConnectionCount;
    private final long[] edgeSuccessfulConnectionCount;
    private final long[] edgeTotalBytes;
    private final long[] edgeTotalPackets;
    private final long[] edgeMaxDurationSeconds;
    private final int[] edgeMaxPriorityScore;

    private final int[] edgeFirstPort;
    private final String[] edgeAnalystComment;

    private final int[] portSourceNumber;
    private final int[] portNumber;
    private final int[] portNext;
    private final long[] portConnectionCount;
    private final long[] portSuccessfulConnectionCount;
    private final long[] portTotalBytes;

    private final byte[] nodeTypes;
    private final byte[] nodeConnectedTypeMasks;
    private final long[] nodeConnectionCount;
    private final long[] nodeTotalBytes;
    private final long[] nodeTotalPackets;
    private final int[] nodeDegree;

    private final int[] nodeIncidentEdgeStart;
    private final int[] nodeIncidentEdgeIds;

    private final GraphMetadata graphMetadata;
    private final LayoutState layoutState;
    private final SpatialIndex spatialIndex;

    private GraphStore(final Builder builder) {
        nodeCount = builder.nodeCount;
        edgeCount = builder.edgeCount;
        portCount = builder.portCount;

        edgeSource = Arrays.copyOf(
                builder.edgeSource,
                edgeCount);

        edgeTarget = Arrays.copyOf(
                builder.edgeTarget,
                edgeCount);

        edgeConnectionCount = Arrays.copyOf(
                builder.edgeConnectionCount,
                edgeCount);

        edgeSuccessfulConnectionCount = Arrays.copyOf(
                builder.edgeSuccessfulConnectionCount,
                edgeCount);

        edgeTotalBytes = Arrays.copyOf(
                builder.edgeTotalBytes,
                edgeCount);

        edgeTotalPackets = Arrays.copyOf(
                builder.edgeTotalPackets,
                edgeCount);

        edgeMaxDurationSeconds = Arrays.copyOf(
                builder.edgeMaxDurationSeconds,
                edgeCount);

        edgeMaxPriorityScore = Arrays.copyOf(
                builder.edgeMaxPriorityScore,
                edgeCount);

        edgeFirstPort = Arrays.copyOf(
                builder.edgeFirstPort,
                edgeCount);

        edgeAnalystComment = new String[edgeCount];

        portSourceNumber = Arrays.copyOf(
                builder.portSourceNumber,
                portCount);

        portNumber = Arrays.copyOf(
                builder.portNumber,
                portCount);

        portNext = Arrays.copyOf(
                builder.portNext,
                portCount);

        portConnectionCount = Arrays.copyOf(
                builder.portConnectionCount,
                portCount);

        portSuccessfulConnectionCount = Arrays.copyOf(
                builder.portSuccessfulConnectionCount,
                portCount);

        portTotalBytes = Arrays.copyOf(
                builder.portTotalBytes,
                portCount);

        nodeTypes = Arrays.copyOf(
                builder.nodeTypes,
                nodeCount);

        nodeConnectedTypeMasks = new byte[nodeCount];

        nodeConnectionCount = Arrays.copyOf(
                builder.nodeConnectionCount,
                nodeCount);

        nodeTotalBytes = Arrays.copyOf(
                builder.nodeTotalBytes,
                nodeCount);

        nodeTotalPackets = Arrays.copyOf(
                builder.nodeTotalPackets,
                nodeCount);

        nodeDegree = new int[nodeCount];

        final int[] degreeCount =
                new int[nodeCount];

        for (int edgeId = 0;
                edgeId < edgeCount;
                edgeId++) {

            degreeCount[edgeSource[edgeId]]++;
            degreeCount[edgeTarget[edgeId]]++;

            nodeDegree[edgeSource[edgeId]]++;
            nodeDegree[edgeTarget[edgeId]]++;
        }

        nodeIncidentEdgeStart =
                new int[nodeCount + 1];

        for (int nodeId = 0;
                nodeId < nodeCount;
                nodeId++) {

            nodeIncidentEdgeStart[nodeId + 1] =
                    nodeIncidentEdgeStart[nodeId]
                            + degreeCount[nodeId];
        }

        nodeIncidentEdgeIds =
                new int[nodeIncidentEdgeStart[nodeCount]];

        final int[] cursor =
                Arrays.copyOf(
                        nodeIncidentEdgeStart,
                        nodeIncidentEdgeStart.length);

        for (int edgeId = 0;
                edgeId < edgeCount;
                edgeId++) {

            final int source = edgeSource[edgeId];
            final int target = edgeTarget[edgeId];

            nodeIncidentEdgeIds[cursor[source]++] = edgeId;
            nodeIncidentEdgeIds[cursor[target]++] = edgeId;

            if (source != target) {
                nodeConnectedTypeMasks[source] |=
                        typeMask(nodeTypes[target]);

                nodeConnectedTypeMasks[target] |=
                        typeMask(nodeTypes[source]);
            }
        }

        graphMetadata =
                new GraphMetadata(
                        Arrays.copyOf(
                                builder.nodeIp,
                                nodeCount));

        layoutState = new LayoutState(nodeCount);

        spatialIndex =
                new SpatialIndex(
                        nodeCount,
                        250.0f);
    }

    public static GraphStore empty() {
        return new Builder().build();
    }

    public boolean isEmpty() {
        return nodeCount == 0;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public int getPortCount() {
        return portCount;
    }

    public byte getNodeType(final int nodeId) {
        return nodeTypes[nodeId];
    }

    public byte getNodeConnectedTypeMask(
            final int nodeId) {

        return nodeConnectedTypeMasks[nodeId];
    }

    public long getNodeConnectionCount(
            final int nodeId) {

        return nodeConnectionCount[nodeId];
    }

    public long getNodeTotalBytes(final int nodeId) {
        return nodeTotalBytes[nodeId];
    }

    public long getNodeTotalPackets(final int nodeId) {
        return nodeTotalPackets[nodeId];
    }

    public int getNodeDegree(final int nodeId) {
        return nodeDegree[nodeId];
    }

    public int getEdgeSourceNodeId(final int edgeId) {
        return edgeSource[edgeId];
    }

    public int getEdgeTargetNodeId(final int edgeId) {
        return edgeTarget[edgeId];
    }

    public long getEdgeConnectionCount(
            final int edgeId) {

        return edgeConnectionCount[edgeId];
    }

    public long getEdgeSuccessfulConnectionCount(
            final int edgeId) {

        return edgeSuccessfulConnectionCount[edgeId];
    }

    public long getEdgeTotalBytes(final int edgeId) {
        return edgeTotalBytes[edgeId];
    }

    public long getEdgeTotalPackets(final int edgeId) {
        return edgeTotalPackets[edgeId];
    }

    public long getEdgeMaxDurationSeconds(
            final int edgeId) {

        return edgeMaxDurationSeconds[edgeId];
    }

    public int getEdgeMaxPriorityScore(
            final int edgeId) {

        return edgeMaxPriorityScore[edgeId];
    }

    public String getEdgeAnalystComment(
            final int edgeId) {

        final String value = edgeAnalystComment[edgeId];
        return value == null ? "" : value;
    }

    public void setEdgeAnalystComment(
            final int edgeId,
            final String comment) {

        edgeAnalystComment[edgeId] =
                comment == null ? "" : comment.trim();
    }

    public int getEdgeFirstPort(final int edgeId) {
        return edgeFirstPort[edgeId];
    }

    public int getPortSourceNumber(
            final int portIndex) {

        return portSourceNumber[portIndex];
    }

    public int getPortNumber(final int portIndex) {
        return portNumber[portIndex];
    }

    public int getPortNext(final int portIndex) {
        return portNext[portIndex];
    }

    public long getPortConnectionCount(
            final int portIndex) {

        return portConnectionCount[portIndex];
    }

    public long getPortSuccessfulConnectionCount(
            final int portIndex) {

        return portSuccessfulConnectionCount[portIndex];
    }

    public long getPortTotalBytes(final int portIndex) {
        return portTotalBytes[portIndex];
    }

    public int getIncidentEdgeStart(
            final int nodeId) {

        return nodeIncidentEdgeStart[nodeId];
    }

    public int getIncidentEdgeEnd(
            final int nodeId) {

        return nodeIncidentEdgeStart[nodeId + 1];
    }

    public int getIncidentEdgeId(
            final int position) {

        return nodeIncidentEdgeIds[position];
    }

    public GraphMetadata getGraphMetadata() {
        return graphMetadata;
    }

    public LayoutState getLayoutState() {
        return layoutState;
    }

    public SpatialIndex getSpatialIndex() {
        return spatialIndex;
    }

    private static byte typeMask(final byte type) {
        return (byte) (1 << type);
    }

    /**
     * Mutable import-time builder. The built GraphStore owns trimmed copies
     * of the arrays, so the builder can be discarded after import.
     */
    public static final class Builder {
        private static final int INITIAL_CAPACITY = 16;

        private final StringIntIndex nodeIndex =
                new StringIntIndex();

        private final LongIntIndex edgeIndex =
                new LongIntIndex();

        private final LongIntIndex portIndex =
                new LongIntIndex();

        private int nodeCount;
        private int edgeCount;
        private int portCount;

        private String[] nodeIp =
                new String[INITIAL_CAPACITY];

        private byte[] nodeTypes =
                new byte[INITIAL_CAPACITY];

        private long[] nodeConnectionCount =
                new long[INITIAL_CAPACITY];

        private long[] nodeTotalBytes =
                new long[INITIAL_CAPACITY];

        private long[] nodeTotalPackets =
                new long[INITIAL_CAPACITY];

        private int[] edgeSource =
                new int[INITIAL_CAPACITY];

        private int[] edgeTarget =
                new int[INITIAL_CAPACITY];

        private long[] edgeConnectionCount =
                new long[INITIAL_CAPACITY];

        private long[] edgeSuccessfulConnectionCount =
                new long[INITIAL_CAPACITY];

        private long[] edgeTotalBytes =
                new long[INITIAL_CAPACITY];

        private long[] edgeTotalPackets =
                new long[INITIAL_CAPACITY];

        private long[] edgeMaxDurationSeconds =
                new long[INITIAL_CAPACITY];

        private int[] edgeMaxPriorityScore =
                new int[INITIAL_CAPACITY];

        private int[] edgeFirstPort =
                new int[INITIAL_CAPACITY];

        private int[] portSourceNumber =
                new int[INITIAL_CAPACITY];

        private int[] portNumber =
                new int[INITIAL_CAPACITY];

        private int[] portNext =
                new int[INITIAL_CAPACITY];

        private long[] portConnectionCount =
                new long[INITIAL_CAPACITY];

        private long[] portSuccessfulConnectionCount =
                new long[INITIAL_CAPACITY];

        private long[] portTotalBytes =
                new long[INITIAL_CAPACITY];

        public Builder() {
            Arrays.fill(edgeFirstPort, -1);
            Arrays.fill(portNext, -1);
        }

        /**
         * Ensures an IP is represented as a graph node even when the exported
         * graph contains no connection record for it.
         */
        public void addNode(final String ipText) {
            if (ipText == null || ipText.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Node IP is required.");
            }

            getOrCreateNode(ipText.trim());
        }

        public void addConnection(
                final String sourceIpText,
                final String destinationIpText,
                final int sourcePort,
                final int destinationPort,
                final long connectionCount,
                final long successfulConnections,
                final long totalBytes,
                final long totalPackets,
                final long maxDurationSeconds,
                final int priorityScore,
                final String matchOn) {

            final int sourceNode =
                    getOrCreateNode(sourceIpText);

            final int destinationNode =
                    getOrCreateNode(destinationIpText);

            markIoc(
                    sourceNode,
                    destinationNode,
                    matchOn);

            final long edgeKey =
                    pairKey(
                            sourceNode,
                            destinationNode);

            int edgeId = edgeIndex.get(edgeKey);

            if (edgeId < 0) {
                edgeId = edgeCount++;
                edgeIndex.put(edgeKey, edgeId);

                ensureEdgeCapacity(edgeCount);

                edgeSource[edgeId] = sourceNode;
                edgeTarget[edgeId] = destinationNode;
                edgeFirstPort[edgeId] = -1;
            }

            edgeConnectionCount[edgeId] += connectionCount;
            edgeSuccessfulConnectionCount[edgeId] +=
                    successfulConnections;

            edgeTotalBytes[edgeId] += totalBytes;
            edgeTotalPackets[edgeId] += totalPackets;

            if (maxDurationSeconds
                    > edgeMaxDurationSeconds[edgeId]) {

                edgeMaxDurationSeconds[edgeId] =
                        maxDurationSeconds;
            }

            if (priorityScore
                    > edgeMaxPriorityScore[edgeId]) {

                edgeMaxPriorityScore[edgeId] =
                        priorityScore;
            }

            /*
             * Preserve the full source-port/destination-port pair for each
             * directed IP-to-IP edge. Node summaries still aggregate these
             * pairs by destination port where appropriate.
             */
            final long portKey =
                    portPairKey(
                            edgeId,
                            sourcePort,
                            destinationPort);

            int portId = portIndex.get(portKey);

            if (portId < 0) {
                portId = portCount++;
                portIndex.put(portKey, portId);

                ensurePortCapacity(portCount);

                portSourceNumber[portId] = sourcePort;
                portNumber[portId] = destinationPort;
                portNext[portId] = edgeFirstPort[edgeId];
                edgeFirstPort[edgeId] = portId;
            }

            portConnectionCount[portId] += connectionCount;

            portSuccessfulConnectionCount[portId] +=
                    successfulConnections;

            portTotalBytes[portId] += totalBytes;

            addNodeTraffic(
                    sourceNode,
                    connectionCount,
                    totalBytes,
                    totalPackets);

            addNodeTraffic(
                    destinationNode,
                    connectionCount,
                    totalBytes,
                    totalPackets);
        }

        public GraphStore build() {
            return new GraphStore(this);
        }

        private int getOrCreateNode(final String ip) {
            int nodeId = nodeIndex.get(ip);

            if (nodeId >= 0) {
                return nodeId;
            }

            nodeId = nodeCount++;
            nodeIndex.put(ip, nodeId);

            ensureNodeCapacity(nodeCount);

            nodeIp[nodeId] = ip;
            nodeTypes[nodeId] = TYPE_UNK;

            return nodeId;
        }

        private void markIoc(
                final int sourceNode,
                final int destinationNode,
                final String matchOn) {

            if (matchOn == null) {
                return;
            }

            final String value =
                    matchOn.trim()
                            .toLowerCase(Locale.ROOT);

            if ("source".equals(value)) {
                nodeTypes[sourceNode] = TYPE_IOC;

            } else if ("destination".equals(value)) {
                nodeTypes[destinationNode] = TYPE_IOC;
            }
        }

        private void addNodeTraffic(
                final int nodeId,
                final long connections,
                final long bytes,
                final long packets) {

            nodeConnectionCount[nodeId] += connections;
            nodeTotalBytes[nodeId] += bytes;
            nodeTotalPackets[nodeId] += packets;
        }

        private void ensureNodeCapacity(
                final int required) {

            if (required <= nodeIp.length) {
                return;
            }

            final int capacity =
                    growCapacity(
                            nodeIp.length,
                            required);

            nodeIp = Arrays.copyOf(nodeIp, capacity);
            nodeTypes = Arrays.copyOf(nodeTypes, capacity);

            nodeConnectionCount = Arrays.copyOf(
                    nodeConnectionCount,
                    capacity);

            nodeTotalBytes = Arrays.copyOf(
                    nodeTotalBytes,
                    capacity);

            nodeTotalPackets = Arrays.copyOf(
                    nodeTotalPackets,
                    capacity);
        }

        private void ensureEdgeCapacity(
                final int required) {

            if (required <= edgeSource.length) {
                return;
            }

            final int oldLength = edgeSource.length;

            final int capacity =
                    growCapacity(
                            oldLength,
                            required);

            edgeSource = Arrays.copyOf(edgeSource, capacity);
            edgeTarget = Arrays.copyOf(edgeTarget, capacity);

            edgeConnectionCount = Arrays.copyOf(
                    edgeConnectionCount,
                    capacity);

            edgeSuccessfulConnectionCount = Arrays.copyOf(
                    edgeSuccessfulConnectionCount,
                    capacity);

            edgeTotalBytes = Arrays.copyOf(
                    edgeTotalBytes,
                    capacity);

            edgeTotalPackets = Arrays.copyOf(
                    edgeTotalPackets,
                    capacity);

            edgeMaxDurationSeconds = Arrays.copyOf(
                    edgeMaxDurationSeconds,
                    capacity);

            edgeMaxPriorityScore = Arrays.copyOf(
                    edgeMaxPriorityScore,
                    capacity);

            edgeFirstPort = Arrays.copyOf(
                    edgeFirstPort,
                    capacity);

            Arrays.fill(
                    edgeFirstPort,
                    oldLength,
                    capacity,
                    -1);
        }

        private void ensurePortCapacity(
                final int required) {

            if (required <= portNumber.length) {
                return;
            }

            final int oldLength = portNumber.length;

            final int capacity =
                    growCapacity(
                            oldLength,
                            required);

            portSourceNumber = Arrays.copyOf(
                    portSourceNumber,
                    capacity);

            portNumber = Arrays.copyOf(portNumber, capacity);
            portNext = Arrays.copyOf(portNext, capacity);

            portConnectionCount = Arrays.copyOf(
                    portConnectionCount,
                    capacity);

            portSuccessfulConnectionCount = Arrays.copyOf(
                    portSuccessfulConnectionCount,
                    capacity);

            portTotalBytes = Arrays.copyOf(
                    portTotalBytes,
                    capacity);

            Arrays.fill(
                    portNext,
                    oldLength,
                    capacity,
                    -1);
        }

        private static int growCapacity(
                final int current,
                final int required) {

            int capacity =
                    Math.max(
                            INITIAL_CAPACITY,
                            current);

            while (capacity < required) {
                capacity = capacity + (capacity >> 1) + 1;
            }

            return capacity;
        }

        private static long portPairKey(
                final int edgeId,
                final int sourcePort,
                final int destinationPort) {

            return ((long) edgeId << 32)
                    | ((long) (sourcePort & 0xFFFF) << 16)
                    | (destinationPort & 0xFFFFL);
        }

        private static long pairKey(
                final int high,
                final int low) {

            return ((long) high << 32)
                    ^ (low & 0xFFFFFFFFL);
        }
    }
}