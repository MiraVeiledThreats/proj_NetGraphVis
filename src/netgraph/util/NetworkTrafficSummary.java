package netgraph.util;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;

/**
 * Produces network-border device-tag and connection-tag summaries for the
 * right-click details panel.
 */
public final class NetworkTrafficSummary {
    private static final byte[] TYPES = {
            DeviceTypes.OT,
            DeviceTypes.IT,
            DeviceTypes.IOC,
            DeviceTypes.UNK
    };

    private static final String[] FLOW_NAMES = {
            "Internal",
            "Inbound",
            "Outbound"
    };

    private NetworkTrafficSummary() {
    }

    /**
     * Appends a summary for a named CIDR network or the undetermined group.
     *
     * Multi-tag devices contribute once to every applicable device-tag count.
     * A multi-tag endpoint also contributes to every applicable source-tag /
     * destination-tag connection-pair category.
     */
    public static void append(
            final StringBuilder text,
            final GraphStore store,
            final int networkId,
            final boolean undetermined) {

        if (text == null || store == null) {
            return;
        }

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final GraphMetadata.NetworkTable networks =
                metadata.getNetworkTable();

        final long[] deviceTagCounts =
                new long[TYPES.length];

        final long[] edgeRecordCounts =
                new long[FLOW_NAMES.length];

        final long[] connectionCounts =
                new long[FLOW_NAMES.length];

        final long[][][] tagPairConnections =
                new long[FLOW_NAMES.length]
                        [TYPES.length]
                        [TYPES.length];

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

            if (!isInScope(
                    networks,
                    nodeId,
                    networkId,
                    undetermined)) {

                continue;
            }

            final byte tags =
                    metadata.effectiveNodeTypeMask(
                            nodeId,
                            store.getNodeType(nodeId));

            for (int typeIndex = 0;
                    typeIndex < TYPES.length;
                    typeIndex++) {

                if (DeviceTypes.has(tags, TYPES[typeIndex])) {
                    deviceTagCounts[typeIndex]++;
                }
            }
        }

        for (int edgeId = 0;
                edgeId < store.getEdgeCount();
                edgeId++) {

            final int sourceNodeId =
                    store.getEdgeSourceNodeId(edgeId);

            final int destinationNodeId =
                    store.getEdgeTargetNodeId(edgeId);

            final boolean sourceInside =
                    isInScope(
                            networks,
                            sourceNodeId,
                            networkId,
                            undetermined);

            final boolean destinationInside =
                    isInScope(
                            networks,
                            destinationNodeId,
                            networkId,
                            undetermined);

            final int flow;

            if (sourceInside && destinationInside) {
                flow = 0;

            } else if (!sourceInside && destinationInside) {
                flow = 1;

            } else if (sourceInside && !destinationInside) {
                flow = 2;

            } else {
                continue;
            }

            edgeRecordCounts[flow]++;

            final long connectionCount =
                    store.getEdgeConnectionCount(edgeId);

            connectionCounts[flow] += connectionCount;

            final byte sourceTags =
                    metadata.effectiveNodeTypeMask(
                            sourceNodeId,
                            store.getNodeType(sourceNodeId));

            final byte destinationTags =
                    metadata.effectiveNodeTypeMask(
                            destinationNodeId,
                            store.getNodeType(destinationNodeId));

            for (int sourceType = 0;
                    sourceType < TYPES.length;
                    sourceType++) {

                if (!DeviceTypes.has(
                        sourceTags,
                        TYPES[sourceType])) {

                    continue;
                }

                for (int destinationType = 0;
                        destinationType < TYPES.length;
                        destinationType++) {

                    if (!DeviceTypes.has(
                            destinationTags,
                            TYPES[destinationType])) {

                        continue;
                    }

                    tagPairConnections[flow]
                            [sourceType]
                            [destinationType] +=
                                    connectionCount;
                }
            }
        }

        text.append("\nDevice Type Tag Summary\n");

        for (int typeIndex = 0;
                typeIndex < TYPES.length;
                typeIndex++) {

            text.append("  ")
                    .append(DeviceTypes.typeName(
                            TYPES[typeIndex]))
                    .append(": ")
                    .append(deviceTagCounts[typeIndex])
                    .append(" device(s)\n");
        }

        text.append(
                "\nConnection Scope Summary "
                        + "(aggregated connection count)\n");

        for (int flow = 0;
                flow < FLOW_NAMES.length;
                flow++) {

            text.append("  ")
                    .append(FLOW_NAMES[flow])
                    .append(": ")
                    .append(edgeRecordCounts[flow])
                    .append(" edge record(s), ")
                    .append(connectionCounts[flow])
                    .append(" connection(s)\n");
        }

        text.append(
                "\nConnections by Device-Type Tag Pair\n");

        text.append(
                "  Note: multi-tag endpoints contribute to each "
                        + "applicable tag-pair category.\n");

        boolean foundPair = false;

        for (int flow = 0;
                flow < FLOW_NAMES.length;
                flow++) {

            for (int sourceType = 0;
                    sourceType < TYPES.length;
                    sourceType++) {

                for (int destinationType = 0;
                        destinationType < TYPES.length;
                        destinationType++) {

                    final long count =
                            tagPairConnections[flow]
                                    [sourceType]
                                    [destinationType];

                    if (count <= 0L) {
                        continue;
                    }

                    text.append("  ")
                            .append(FLOW_NAMES[flow])
                            .append(": ")
                            .append(DeviceTypes.typeName(
                                    TYPES[sourceType]))
                            .append(" -> ")
                            .append(DeviceTypes.typeName(
                                    TYPES[destinationType]))
                            .append(" = ")
                            .append(count)
                            .append('\n');

                    foundPair = true;
                }
            }
        }

        if (!foundPair) {
            text.append("  (no matching connections)\n");
        }
    }

    private static boolean isInScope(
            final GraphMetadata.NetworkTable networks,
            final int nodeId,
            final int networkId,
            final boolean undetermined) {

        final int start =
                networks.getMembershipStart(nodeId);

        final int end =
                networks.getMembershipEnd(nodeId);

        if (undetermined) {
            return start == end;
        }

        for (int index = start; index < end; index++) {
            if (networks.getMembershipNetworkId(index)
                    == networkId) {

                return true;
            }
        }

        return false;
    }
}