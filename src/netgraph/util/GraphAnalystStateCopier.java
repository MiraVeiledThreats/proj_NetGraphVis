package netgraph.util;

import java.util.HashMap;
import java.util.Map;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;

/**
 * Copies analyst-entered state from one graph instance to a rebuilt graph.
 *
 * Additional connection imports rebuild packed graph storage so connection
 * aggregates can be extended safely. This class restores analyst metadata for
 * nodes, directed edges, named network borders, and the undetermined border.
 */
public final class GraphAnalystStateCopier {
    private GraphAnalystStateCopier() {
    }

    public static void copy(
            final GraphStore source,
            final GraphStore destination) {

        if (source == null || destination == null) {
            throw new IllegalArgumentException(
                    "Source and destination graphs are required.");
        }

        copyNodeState(source, destination);
        copyEdgeNotes(source, destination);
        copyNetworkNotes(source, destination);
    }

    private static void copyNodeState(
            final GraphStore source,
            final GraphStore destination) {

        final GraphMetadata sourceMetadata =
                source.getGraphMetadata();

        final GraphMetadata destinationMetadata =
                destination.getGraphMetadata();

        final Map<String, Integer> destinationNodes =
                new HashMap<String, Integer>();

        for (int nodeId = 0;
                nodeId < destination.getNodeCount();
                nodeId++) {

            destinationNodes.put(
                    destinationMetadata.getNodeIp(nodeId),
                    Integer.valueOf(nodeId));
        }

        for (int sourceNodeId = 0;
                sourceNodeId < source.getNodeCount();
                sourceNodeId++) {

            final String ip =
                    sourceMetadata.getNodeIp(sourceNodeId);

            final Integer destinationNodeId =
                    destinationNodes.get(ip);

            if (destinationNodeId == null) {
                continue;
            }

            final int targetNodeId =
                    destinationNodeId.intValue();

            destinationMetadata.setNodeHostname(
                    targetNodeId,
                    sourceMetadata.getNodeHostname(sourceNodeId));

            destinationMetadata.setNodeAnalystComment(
                    targetNodeId,
                    sourceMetadata.getNodeAnalystComment(
                            sourceNodeId));

            final byte manualTypeMask =
                    sourceMetadata.getNodeManualTypeMask(
                            sourceNodeId);

            if (manualTypeMask == 0) {
                destinationMetadata.clearNodeManualTypeMask(
                        targetNodeId);

            } else {
                destinationMetadata.setNodeManualTypeMask(
                        targetNodeId,
                        manualTypeMask);
            }
        }
    }

    private static void copyEdgeNotes(
            final GraphStore source,
            final GraphStore destination) {

        final GraphMetadata sourceMetadata =
                source.getGraphMetadata();

        final GraphMetadata destinationMetadata =
                destination.getGraphMetadata();

        final Map<String, Integer> destinationEdges =
                new HashMap<String, Integer>();

        for (int edgeId = 0;
                edgeId < destination.getEdgeCount();
                edgeId++) {

            final int sourceNodeId =
                    destination.getEdgeSourceNodeId(edgeId);

            final int destinationNodeId =
                    destination.getEdgeTargetNodeId(edgeId);

            destinationEdges.put(
                    edgeKey(
                            destinationMetadata.getNodeIp(
                                    sourceNodeId),
                            destinationMetadata.getNodeIp(
                                    destinationNodeId)),
                    Integer.valueOf(edgeId));
        }

        for (int edgeId = 0;
                edgeId < source.getEdgeCount();
                edgeId++) {

            final int sourceNodeId =
                    source.getEdgeSourceNodeId(edgeId);

            final int destinationNodeId =
                    source.getEdgeTargetNodeId(edgeId);

            final Integer targetEdgeId =
                    destinationEdges.get(
                            edgeKey(
                                    sourceMetadata.getNodeIp(
                                            sourceNodeId),
                                    sourceMetadata.getNodeIp(
                                            destinationNodeId)));

            if (targetEdgeId == null) {
                continue;
            }

            destination.setEdgeAnalystComment(
                    targetEdgeId.intValue(),
                    source.getEdgeAnalystComment(edgeId));
        }
    }

    private static void copyNetworkNotes(
            final GraphStore source,
            final GraphStore destination) {

        final GraphMetadata sourceMetadata =
                source.getGraphMetadata();

        final GraphMetadata destinationMetadata =
                destination.getGraphMetadata();

        final GraphMetadata.NetworkTable sourceNetworks =
                sourceMetadata.getNetworkTable();

        final GraphMetadata.NetworkTable destinationNetworks =
                destinationMetadata.getNetworkTable();

        final Map<String, String> sourceComments =
                new HashMap<String, String>();

        for (int networkId = 0;
                networkId < sourceNetworks.networkCount();
                networkId++) {

            sourceComments.put(
                    networkKey(
                            sourceNetworks.getBranch(networkId),
                            sourceNetworks.getCidr(networkId),
                            sourceNetworks.getName(networkId)),
                    sourceMetadata.getNetworkAnalystComment(
                            networkId));
        }

        for (int networkId = 0;
                networkId < destinationNetworks.networkCount();
                networkId++) {

            final String comment =
                    sourceComments.get(
                            networkKey(
                                    destinationNetworks.getBranch(
                                            networkId),
                                    destinationNetworks.getCidr(
                                            networkId),
                                    destinationNetworks.getName(
                                            networkId)));

            if (comment != null) {
                destinationMetadata.setNetworkAnalystComment(
                        networkId,
                        comment);
            }
        }

        destinationMetadata.setUndeterminedNetworkAnalystComment(
                sourceMetadata.getUndeterminedNetworkAnalystComment());
    }

    private static String edgeKey(
            final String sourceIp,
            final String destinationIp) {

        return safe(sourceIp)
                + '\u0000'
                + safe(destinationIp);
    }

    private static String networkKey(
            final String branch,
            final String cidr,
            final String name) {

        return safe(branch)
                + '\u0000'
                + safe(cidr)
                + '\u0000'
                + safe(name);
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }
}