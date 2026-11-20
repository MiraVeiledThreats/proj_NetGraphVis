package netgraph.cluster;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.spatial.SpatialIndex;
import netgraph.util.DeviceTypes;
import netgraph.util.LongIntIndex;

/**
 * Builds one immutable, filter-specific visual projection.
 *
 * <p>Raw nodes and edges are scanned once per cache miss. Paint operations read
 * only the resulting packed projection arrays; they never rescan raw topology.</p>
 */
public final class ClusterProjectionBuilder {
    private static final byte[] TYPES = {
            DeviceTypes.OT,
            DeviceTypes.IT,
            DeviceTypes.IOC,
            DeviceTypes.UNK
    };

    private static final float ANALYST_BORDER_PADDING =
            44.0f;

    private ClusterProjectionBuilder() {
    }

    public static ClusterProjection build(
            final GraphStore store,
            final float[] rawX,
            final float[] rawY,
            final GraphDisplayFilter requestedFilter,
            final ClusterViewState.Snapshot state) {

        if (store == null
                || store.isEmpty()
                || rawX == null
                || rawY == null
                || rawX.length < store.getNodeCount()
                || rawY.length < store.getNodeCount()
                || state == null) {

            return ClusterProjection.empty();
        }

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final GraphMetadata.NetworkTable networks =
                metadata.getNetworkTable();

        final GraphDisplayFilter filter =
                requestedFilter == null
                        ? GraphDisplayFilter.all()
                        : requestedFilter;

        final int nodeCount =
                store.getNodeCount();

        final int networkCount =
                networks.networkCount();

        final AnalystClusterDefinition[] analystDefinitions =
                state.getAnalystClustersUnsafe();

        final PartialUnclusterDefinition[] partialDefinitions =
                state.getPartialUnclustersUnsafe();

        final int[] analystForNetwork =
                resolveAnalystNetworks(
                        networks,
                        analystDefinitions);

        final PartialScopeResolution partialScopes =
                resolvePartialScopes(
                        networks,
                        partialDefinitions);

        final int[] networkVisual =
                new int[networkCount];

        final int[] analystVisual =
                new int[analystDefinitions.length];

        Arrays.fill(networkVisual, -1);
        Arrays.fill(analystVisual, -1);

        /*
         * Partial scope + group is encoded into a long key. The definition ID
         * occupies the high 32 bits and the partition key occupies the low 32.
         */
        final LongIntIndex partialVisual =
                new LongIntIndex();

        final int[] rawNodeToVisual =
                new int[nodeCount];

        Arrays.fill(rawNodeToVisual, -1);

        int undeterminedVisual = -1;

        final ProjectionBuffer buffer =
                new ProjectionBuffer(
                        Math.max(
                                16,
                                Math.min(
                                        nodeCount
                                                + analystDefinitions.length
                                                + partialDefinitions.length,
                                        4096)));

        final float[] analystMinX =
                new float[analystDefinitions.length];

        final float[] analystMinY =
                new float[analystDefinitions.length];

        final float[] analystMaxX =
                new float[analystDefinitions.length];

        final float[] analystMaxY =
                new float[analystDefinitions.length];

        final int[] analystMembers =
                new int[analystDefinitions.length];

        Arrays.fill(
                analystMinX,
                Float.MAX_VALUE);

        Arrays.fill(
                analystMinY,
                Float.MAX_VALUE);

        Arrays.fill(
                analystMaxX,
                -Float.MAX_VALUE);

        Arrays.fill(
                analystMaxY,
                -Float.MAX_VALUE);

        for (int nodeId = 0;
                nodeId < nodeCount;
                nodeId++) {

            if (!filter.matchesNode(store, nodeId)) {
                continue;
            }

            final byte effectiveTags =
                    metadata.effectiveNodeTypeMask(
                            nodeId,
                            store.getNodeType(nodeId));

        final int analystId =
                analystForNode(
                        networks,
                        nodeId,
                        analystForNetwork);

        final int visualId;

        if (analystId >= 0) {
        visualId =
                analystVisual[analystId] >= 0
                        ? analystVisual[analystId]
                        : (analystVisual[analystId] =
                                buffer.add(
                                        ClusterProjection.KIND_ANALYST,
                                        -1,
                                        -1,
                                        analystId,
                                        analystDefinitions[analystId]
                                                .getLabel()));

        includeAnalystBounds(
                analystId,
                rawX[nodeId],
                rawY[nodeId],
                analystMinX,
                analystMinY,
                analystMaxX,
                analystMaxY,
                analystMembers);

        } else {
        /*
        * A node may have no NIC membership, or it may have NIC membership but
        * be fully expanded through every hierarchy level. These cases must stay
        * distinct:
        *
        * - no NIC membership -> Undetermined Network behavior
        * - assigned network, all levels expanded -> individual raw device
        */
        final int primaryNetwork =
                networks.primaryNetworkForNode(nodeId);

        final boolean hasNetworkMembership =
                primaryNetwork >= 0;

        final int activeNetwork =
                hasNetworkMembership
                        ? activeNetworkForNode(
                                networks,
                                nodeId,
                                state)
                        : -1;

        /*
        * Render an individual raw device when:
        *
        * 1. It belongs to an identified network whose complete hierarchy is
        *    expanded; or
        *
        * 2. It belongs to Undetermined Network and Undetermined Network has
        *    explicitly been expanded.
        */
        if ((hasNetworkMembership && activeNetwork < 0)
                || (!hasNetworkMembership
                        && state.isUndeterminedExpanded())) {

                visualId =
                        buffer.add(
                                ClusterProjection.KIND_RAW_NODE,
                                -1,
                                nodeId,
                                -1,
                                metadata.getNodeIp(nodeId));

        } else {
                /*
                * A partial definition applies either to the current active network
                * or to the Undetermined Network visual scope.
                */
                final int partialId =
                        hasNetworkMembership
                                ? partialScopes.networkPartialIds[
                                        activeNetwork]
                                : partialScopes.undeterminedPartialId;

                if (partialId >= 0) {
                final PartialUnclusterDefinition definition =
                        partialDefinitions[partialId];

                final int group =
                        definition.groupForNode(
                                store,
                                metadata,
                                nodeId,
                                effectiveTags);

                /*
                * The analyst expanded only this one partial group, such as
                * "IOC Devices." Emit each matching device as an individual
                * raw visual node while leaving the other groups clustered.
                */
                if (state.isPartialGroupExpanded(
                        definition,
                        group)) {

                        visualId =
                                buffer.add(
                                        ClusterProjection.KIND_RAW_NODE,
                                        -1,
                                        nodeId,
                                        -1,
                                        metadata.getNodeIp(nodeId));

                } else {
                        final long key =
                                partialKey(
                                        partialId,
                                        group);

                        int existingVisual =
                                partialVisual.get(key);

                        if (existingVisual < 0) {
                        final String scopeLabel =
                                hasNetworkMembership
                                        ? networkLabel(
                                                networks,
                                                activeNetwork)
                                        : "Undetermined Network";

                        existingVisual =
                                buffer.add(
                                        ClusterProjection.KIND_PARTIAL,
                                        activeNetwork,
                                        group,
                                        partialId,
                                        definition.groupLabel(
                                                scopeLabel,
                                                group));

                        partialVisual.put(
                                key,
                                existingVisual);
                        }

                        visualId = existingVisual;
                }

                } else if (hasNetworkMembership) {
                visualId =
                        networkVisual[activeNetwork] >= 0
                                ? networkVisual[activeNetwork]
                                : (networkVisual[activeNetwork] =
                                        buffer.add(
                                                ClusterProjection.KIND_NETWORK,
                                                activeNetwork,
                                                -1,
                                                -1,
                                                networkLabel(
                                                        networks,
                                                        activeNetwork)));

                } else {
                if (undeterminedVisual < 0) {
                        undeterminedVisual =
                                buffer.add(
                                        ClusterProjection.KIND_UNDETERMINED,
                                        -1,
                                        -1,
                                        -1,
                                        "Undetermined Network");
                }

                visualId = undeterminedVisual;
                }
        }
        }
            rawNodeToVisual[nodeId] = visualId;

            buffer.includeNode(
                    visualId,
                    rawX[nodeId],
                    rawY[nodeId],
                    effectiveTags);
        }

        buffer.finishLocations();

        /*
         * Aggregate packed raw edges by their final visual endpoint pair.
         *
         * This is what causes traffic to update correctly as a cluster is
         * expanded: raw flows A-device -> B-device become either:
         *   A-cluster -> B-cluster,
         *   A-subnet -> B-cluster, or
         *   A-device -> B-cluster,
         * depending solely on the current visual mapping.
         */
        final LongIntIndex aggregateIndex =
                new LongIntIndex();

        final EdgeBuffer edges =
                new EdgeBuffer(
                        Math.max(
                                16,
                                Math.min(
                                        store.getEdgeCount(),
                                        4096)));

        for (int edgeId = 0;
                edgeId < store.getEdgeCount();
                edgeId++) {

            if (!filter.matchesEdge(store, edgeId)) {
                continue;
            }

            final int sourceNodeId =
                    store.getEdgeSourceNodeId(edgeId);

            final int targetNodeId =
                    store.getEdgeTargetNodeId(edgeId);

            final int sourceVisual =
                    rawNodeToVisual[sourceNodeId];

            final int targetVisual =
                    rawNodeToVisual[targetNodeId];

            if (sourceVisual < 0
                    || targetVisual < 0) {

                continue;
            }

            final long connections =
                    store.getEdgeConnectionCount(edgeId);

            final long bytes =
                    store.getEdgeTotalBytes(edgeId);

            final long packets =
                    store.getEdgeTotalPackets(edgeId);

            if (sourceVisual == targetVisual) {
                buffer.addInternal(
                        sourceVisual,
                        connections,
                        bytes,
                        packets);

                continue;
            }

            final long key =
                    pairKey(
                            sourceVisual,
                            targetVisual);

            int aggregateId =
                    aggregateIndex.get(key);

            if (aggregateId < 0) {
                aggregateId =
                        edges.add(
                                sourceVisual,
                                targetVisual);

                aggregateIndex.put(
                        key,
                        aggregateId);
            }

        final byte sourceTags =
                metadata.effectiveNodeTypeMask(
                        sourceNodeId,
                        store.getNodeType(sourceNodeId));

        final byte targetTags =
                metadata.effectiveNodeTypeMask(
                        targetNodeId,
                        store.getNodeType(targetNodeId));

        edges.addTraffic(
                aggregateId,
                connections,
                store.getEdgeSuccessfulConnectionCount(edgeId),
                bytes,
                packets,
                sourceTags,
                targetTags);

            final long externalWeight =
                    Math.max(1L, connections);

            buffer.addOuterTags(
                    sourceVisual,
                    metadata.effectiveNodeTypeMask(
                            targetNodeId,
                            store.getNodeType(targetNodeId)),
                    externalWeight);

            buffer.addOuterTags(
                    targetVisual,
                    metadata.effectiveNodeTypeMask(
                            sourceNodeId,
                            store.getNodeType(sourceNodeId)),
                    externalWeight);
        }

        final SpatialIndex spatial =
                new SpatialIndex(
                        buffer.size,
                        250.0f);

        spatial.rebuild(
                buffer.x,
                buffer.y,
                buffer.size);

        final int[] visualEdgeSources =
                edges.trimSource();

        final int[] visualEdgeTargets =
                edges.trimTarget();

        final VisualEdgeIncidenceIndex visualEdgeIncidenceIndex =
                VisualEdgeIncidenceIndex.build(
                        buffer.size,
                        visualEdgeSources,
                        visualEdgeTargets);

        return new ClusterProjection(
                buffer.trimKinds(),
                buffer.trimNetworkIds(),
                buffer.trimRawNodeIds(),
                buffer.trimAnalystIds(),
                buffer.trimLabels(),
                buffer.trimX(),
                buffer.trimY(),
                buffer.trimMembers(),
                buffer.trimInner(),
                buffer.trimOuter(),
                buffer.trimInternalConnections(),
                buffer.trimInternalBytes(),
                buffer.trimInternalPackets(),
                visualEdgeSources,
                visualEdgeTargets,
                edges.trimConnections(),
                edges.trimSuccessful(),
                edges.trimBytes(),
                edges.trimPackets(),
                edges.trimEndpointTagMasks(),
                rawNodeToVisual,
                spatial,
                analystLabels(
                        analystDefinitions,
                        analystMembers),
                padded(
                        analystMinX,
                        analystMembers,
                        -ANALYST_BORDER_PADDING),
                padded(
                        analystMinY,
                        analystMembers,
                        -ANALYST_BORDER_PADDING),
                padded(
                        analystMaxX,
                        analystMembers,
                        ANALYST_BORDER_PADDING),
                padded(
                        analystMaxY,
                        analystMembers,
                        ANALYST_BORDER_PADDING),
                analystMembers,
                visualEdgeIncidenceIndex);
    }

    private static int[] resolveAnalystNetworks(
            final GraphMetadata.NetworkTable networks,
            final AnalystClusterDefinition[] definitions) {

        final int[] result =
                new int[networks.networkCount()];

        Arrays.fill(result, -1);

        final Map<String, Integer> ids =
                new HashMap<String, Integer>();

        for (int id = 0;
                id < networks.networkCount();
                id++) {

            ids.put(
                    networks.networkIdentityKey(id),
                    Integer.valueOf(id));
        }

        /*
         * Existing behavior retained: later analyst definitions win if they
         * overlap the same network.
         */
        for (int analyst = 0;
                analyst < definitions.length;
                analyst++) {

            for (String key :
                    definitions[analyst].networkKeysUnsafe()) {

                final Integer networkId =
                        ids.get(key);

                if (networkId != null) {
                    result[networkId.intValue()] =
                            analyst;
                }
            }
        }

        return result;
    }

    private static PartialScopeResolution resolvePartialScopes(
            final GraphMetadata.NetworkTable networks,
            final PartialUnclusterDefinition[] definitions) {

        final int[] networkPartialIds =
                new int[networks.networkCount()];

        Arrays.fill(networkPartialIds, -1);

        int undeterminedPartialId = -1;

        final Map<String, Integer> byNetworkKey =
                new HashMap<String, Integer>();

        for (int definitionId = 0;
                definitionId < definitions.length;
                definitionId++) {

            final PartialUnclusterDefinition definition =
                    definitions[definitionId];

            if (definition.isUndeterminedScope()) {
                undeterminedPartialId = definitionId;

            } else {
                byNetworkKey.put(
                        definition.getScopeNetworkKeyUnsafe(),
                        Integer.valueOf(definitionId));
            }
        }

        for (int networkId = 0;
                networkId < networks.networkCount();
                networkId++) {

            final Integer definitionId =
                    byNetworkKey.get(
                            networks.networkIdentityKey(networkId));

            if (definitionId != null) {
                networkPartialIds[networkId] =
                        definitionId.intValue();
            }
        }

        return new PartialScopeResolution(
                networkPartialIds,
                undeterminedPartialId);
    }

    private static int analystForNode(
            final GraphMetadata.NetworkTable networks,
            final int nodeId,
            final int[] analystForNetwork) {

        int result = -1;

        for (int membership =
                networks.getMembershipStart(nodeId);
                membership < networks.getMembershipEnd(nodeId);
                membership++) {

            final int analyst =
                    analystForNetwork[
                            networks.getMembershipNetworkId(
                                    membership)];

            if (analyst > result) {
                result = analyst;
            }
        }

        return result;
    }

    private static int activeNetworkForNode(
            final GraphMetadata.NetworkTable networks,
            final int nodeId,
            final ClusterViewState.Snapshot state) {

        int network =
                networks.primaryNetworkForNode(nodeId);

        if (network < 0) {
            return -1;
        }

        int selectedNetwork = -1;
        boolean primaryNetwork = true;

        /*
         * Start at the most-specific represented network and walk upward.
         * The highest ancestor that is not expanded is the visual group.
         */
        while (network >= 0) {
            final boolean structuralRoot =
                    networks.getPrefixLength(network) == 0
                            && !primaryNetwork;

            if (!structuralRoot
                    && !state.isExpanded(network)) {

                selectedNetwork = network;
            }

            network =
                    networks.getParentNetworkId(network);

            primaryNetwork = false;
        }

        return selectedNetwork;
    }

    private static String networkLabel(
            final GraphMetadata.NetworkTable networks,
            final int networkId) {

        final String name =
                networks.getName(networkId);

        return networks.getCidr(networkId)
                + (name == null || name.trim().isEmpty()
                        ? ""
                        : " \u2014 " + name);
    }

    private static void includeAnalystBounds(
            final int id,
            final float x,
            final float y,
            final float[] minX,
            final float[] minY,
            final float[] maxX,
            final float[] maxY,
            final int[] members) {

        minX[id] = Math.min(minX[id], x);
        minY[id] = Math.min(minY[id], y);
        maxX[id] = Math.max(maxX[id], x);
        maxY[id] = Math.max(maxY[id], y);
        members[id]++;
    }

    private static String[] analystLabels(
            final AnalystClusterDefinition[] definitions,
            final int[] members) {

        final String[] labels =
                new String[definitions.length];

        for (int id = 0;
                id < labels.length;
                id++) {

            labels[id] =
                    members[id] > 0
                            ? definitions[id].getLabel()
                            : "";
        }

        return labels;
    }

    private static float[] padded(
            final float[] values,
            final int[] members,
            final float padding) {

        final float[] result =
                Arrays.copyOf(
                        values,
                        values.length);

        for (int id = 0;
                id < result.length;
                id++) {

            if (members[id] == 0) {
                result[id] = 0.0f;

            } else {
                result[id] += padding;
            }
        }

        return result;
    }

    private static long pairKey(
            final int source,
            final int target) {

        return ((long) source << 32)
                ^ (target & 0xFFFFFFFFL);
    }

    private static long partialKey(
            final int partialDefinitionId,
            final int group) {

        return ((long) partialDefinitionId << 32)
                ^ (group & 0xFFFFFFFFL);
    }

    private static final class PartialScopeResolution {
        private final int[] networkPartialIds;
        private final int undeterminedPartialId;

        private PartialScopeResolution(
                final int[] networkPartialIds,
                final int undeterminedPartialId) {

            this.networkPartialIds = networkPartialIds;
            this.undeterminedPartialId =
                    undeterminedPartialId;
        }
    }

    private static final class ProjectionBuffer {
        private byte[] kinds;
        private int[] networks;
        private int[] rawNodes;
        private int[] analysts;
        private String[] labels;
        private float[] x;
        private float[] y;
        private float[] sumX;
        private float[] sumY;
        private int[] members;
        private final int[][] inner =
                new int[4][];
        private final long[][] outer =
                new long[4][];
        private long[] internalConnections;
        private long[] internalBytes;
        private long[] internalPackets;
        private int size;

        private ProjectionBuffer(final int capacity) {
            allocate(capacity);
        }

        private void allocate(final int capacity) {
            kinds = new byte[capacity];
            networks = new int[capacity];
            rawNodes = new int[capacity];
            analysts = new int[capacity];
            labels = new String[capacity];
            x = new float[capacity];
            y = new float[capacity];
            sumX = new float[capacity];
            sumY = new float[capacity];
            members = new int[capacity];
            internalConnections = new long[capacity];
            internalBytes = new long[capacity];
            internalPackets = new long[capacity];

            for (int index = 0;
                    index < 4;
                    index++) {

                inner[index] = new int[capacity];
                outer[index] = new long[capacity];
            }
        }

        private void grow() {
            final int next =
                    size + (size >> 1) + 1;

            kinds = Arrays.copyOf(kinds, next);
            networks = Arrays.copyOf(networks, next);
            rawNodes = Arrays.copyOf(rawNodes, next);
            analysts = Arrays.copyOf(analysts, next);
            labels = Arrays.copyOf(labels, next);
            x = Arrays.copyOf(x, next);
            y = Arrays.copyOf(y, next);
            sumX = Arrays.copyOf(sumX, next);
            sumY = Arrays.copyOf(sumY, next);
            members = Arrays.copyOf(members, next);
            internalConnections =
                    Arrays.copyOf(
                            internalConnections,
                            next);
            internalBytes =
                    Arrays.copyOf(
                            internalBytes,
                            next);
            internalPackets =
                    Arrays.copyOf(
                            internalPackets,
                            next);

            for (int index = 0;
                    index < 4;
                    index++) {

                inner[index] =
                        Arrays.copyOf(
                                inner[index],
                                next);

                outer[index] =
                        Arrays.copyOf(
                                outer[index],
                                next);
            }
        }

        private int add(
                final byte kind,
                final int network,
                final int rawNode,
                final int analyst,
                final String label) {

            if (size == kinds.length) {
                grow();
            }

            final int id = size++;

            kinds[id] = kind;
            networks[id] = network;
            rawNodes[id] = rawNode;
            analysts[id] = analyst;
            labels[id] = label;

            return id;
        }

        private void includeNode(
                final int id,
                final float pointX,
                final float pointY,
                final byte tags) {

            members[id]++;
            sumX[id] += pointX;
            sumY[id] += pointY;

            for (int index = 0;
                    index < 4;
                    index++) {

                if (DeviceTypes.has(tags, TYPES[index])) {
                    inner[index][id]++;
                }
            }
        }

        private void finishLocations() {
            for (int id = 0;
                    id < size;
                    id++) {

                x[id] =
                        sumX[id]
                                / Math.max(
                                        1,
                                        members[id]);

                y[id] =
                        sumY[id]
                                / Math.max(
                                        1,
                                        members[id]);

                labels[id] =
                        labels[id]
                                + " ("
                                + members[id]
                                + ")";
            }
        }

        private void addInternal(
                final int id,
                final long connections,
                final long bytes,
                final long packets) {

            internalConnections[id] += connections;
            internalBytes[id] += bytes;
            internalPackets[id] += packets;
        }

        private void addOuterTags(
                final int id,
                final byte tags,
                final long weight) {

            for (int index = 0;
                    index < 4;
                    index++) {

                if (DeviceTypes.has(tags, TYPES[index])) {
                    outer[index][id] += weight;
                }
            }
        }

        private byte[] trimKinds() {
            return Arrays.copyOf(kinds, size);
        }

        private int[] trimNetworkIds() {
            return Arrays.copyOf(networks, size);
        }

        private int[] trimRawNodeIds() {
            return Arrays.copyOf(rawNodes, size);
        }

        private int[] trimAnalystIds() {
            return Arrays.copyOf(analysts, size);
        }

        private String[] trimLabels() {
            return Arrays.copyOf(labels, size);
        }

        private float[] trimX() {
            return Arrays.copyOf(x, size);
        }

        private float[] trimY() {
            return Arrays.copyOf(y, size);
        }

        private int[] trimMembers() {
            return Arrays.copyOf(members, size);
        }

        private int[][] trimInner() {
            final int[][] result =
                    new int[4][];

            for (int index = 0;
                    index < 4;
                    index++) {

                result[index] =
                        Arrays.copyOf(
                                inner[index],
                                size);
            }

            return result;
        }

        private long[][] trimOuter() {
            final long[][] result =
                    new long[4][];

            for (int index = 0;
                    index < 4;
                    index++) {

                result[index] =
                        Arrays.copyOf(
                                outer[index],
                                size);
            }

            return result;
        }

        private long[] trimInternalConnections() {
            return Arrays.copyOf(
                    internalConnections,
                    size);
        }

        private long[] trimInternalBytes() {
            return Arrays.copyOf(
                    internalBytes,
                    size);
        }

        private long[] trimInternalPackets() {
            return Arrays.copyOf(
                    internalPackets,
                    size);
        }
    }

        private static final class EdgeBuffer {
                private int[] source;
                private int[] target;
                private long[] connections;
                private long[] successful;
                private long[] bytes;
                private long[] packets;

                /*
                * OR-combined effective endpoint tags for every raw edge contributing to
                * this projected directed visual edge.
                */
                private byte[] endpointTagMasks;

                private int size;

                private EdgeBuffer(final int capacity) {
                        source = new int[capacity];
                        target = new int[capacity];
                        connections = new long[capacity];
                        successful = new long[capacity];
                        bytes = new long[capacity];
                        packets = new long[capacity];
                        endpointTagMasks = new byte[capacity];
                }

                private int add(
                        final int sourceId,
                        final int targetId) {

                        if (size == source.length) {
                        grow();
                        }

                        source[size] = sourceId;
                        target[size] = targetId;

                        return size++;
                }

                private void grow() {
                        final int next =
                                size + (size >> 1) + 1;

                        source = Arrays.copyOf(source, next);
                        target = Arrays.copyOf(target, next);
                        connections = Arrays.copyOf(connections, next);
                        successful = Arrays.copyOf(successful, next);
                        bytes = Arrays.copyOf(bytes, next);
                        packets = Arrays.copyOf(packets, next);
                        endpointTagMasks =
                                Arrays.copyOf(
                                        endpointTagMasks,
                                        next);
                }

                private void addTraffic(
                        final int id,
                        final long connectionCount,
                        final long successfulCount,
                        final long totalBytes,
                        final long totalPackets,
                        final byte sourceTags,
                        final byte targetTags) {

                        connections[id] += connectionCount;
                        successful[id] += successfulCount;
                        bytes[id] += totalBytes;
                        packets[id] += totalPackets;

                        endpointTagMasks[id] |=
                                DeviceTypes.normalizeMask(
                                        (byte) (sourceTags | targetTags));
                }

                private int[] trimSource() {
                        return Arrays.copyOf(source, size);
                }

                private int[] trimTarget() {
                        return Arrays.copyOf(target, size);
                }

                private long[] trimConnections() {
                        return Arrays.copyOf(connections, size);
                }

                private long[] trimSuccessful() {
                        return Arrays.copyOf(successful, size);
                }

                private long[] trimBytes() {
                        return Arrays.copyOf(bytes, size);
                }

                private long[] trimPackets() {
                        return Arrays.copyOf(packets, size);
                }

                private byte[] trimEndpointTagMasks() {
                        return Arrays.copyOf(
                                endpointTagMasks,
                                size);
                }
        }
}