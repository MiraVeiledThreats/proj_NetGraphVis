package netgraph.cluster;

import java.util.Arrays;

/**
 * Immutable compressed-sparse-row incidence index for projected visual edges.
 *
 * Each visual edge is recorded against its source and target visual node. This
 * supports efficient traversal of edges incident to a set of visible nodes.
 */
public final class VisualEdgeIncidenceIndex {
    private final int nodeCount;
    private final int[] startOffsets;
    private final int[] incidentEdgeIds;

    private VisualEdgeIncidenceIndex(
            final int nodeCount,
            final int[] startOffsets,
            final int[] incidentEdgeIds) {

        this.nodeCount = nodeCount;
        this.startOffsets = startOffsets;
        this.incidentEdgeIds = incidentEdgeIds;
    }

    /**
     * Builds a CSR-style node-to-edge incidence index.
     *
     * Source and target arrays must be immutable final projected-edge arrays.
     */
    public static VisualEdgeIncidenceIndex build(
            final int nodeCount,
            final int[] edgeSources,
            final int[] edgeTargets) {

        if (nodeCount < 0) {
            throw new IllegalArgumentException(
                    "nodeCount must not be negative.");
        }

        if (edgeSources == null || edgeTargets == null) {
            throw new IllegalArgumentException(
                    "Edge source and target arrays are required.");
        }

        if (edgeSources.length != edgeTargets.length) {
            throw new IllegalArgumentException(
                    "Edge source and target array lengths differ.");
        }

        final int edgeCount =
                edgeSources.length;

        if (edgeCount > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException(
                    "Too many edges for a two-ended incidence index.");
        }

        final int[] startOffsets =
                new int[nodeCount + 1];

        /*
         * Count incidences. A self-loop is recorded twice intentionally. The
         * renderer's frame stamp ensures the edge is painted only once.
         */
        for (int edgeId = 0;
                edgeId < edgeCount;
                edgeId++) {

            final int source =
                    edgeSources[edgeId];

            final int target =
                    edgeTargets[edgeId];

            validateNodeId(
                    source,
                    nodeCount,
                    "source",
                    edgeId);

            validateNodeId(
                    target,
                    nodeCount,
                    "target",
                    edgeId);

            startOffsets[source + 1]++;
            startOffsets[target + 1]++;
        }

        /*
         * Convert counts to exclusive end offsets.
         */
        for (int nodeId = 1;
                nodeId < startOffsets.length;
                nodeId++) {

            startOffsets[nodeId] +=
                    startOffsets[nodeId - 1];
        }

        final int[] incidentEdgeIds =
                new int[startOffsets[nodeCount]];

        final int[] writeOffsets =
                Arrays.copyOf(
                        startOffsets,
                        nodeCount);

        for (int edgeId = 0;
                edgeId < edgeCount;
                edgeId++) {

            final int source =
                    edgeSources[edgeId];

            final int target =
                    edgeTargets[edgeId];

            incidentEdgeIds[writeOffsets[source]++] =
                    edgeId;

            incidentEdgeIds[writeOffsets[target]++] =
                    edgeId;
        }

        return new VisualEdgeIncidenceIndex(
                nodeCount,
                startOffsets,
                incidentEdgeIds);
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getIncidentEdgeStart(
            final int visualNodeId) {

        validateVisualNodeId(visualNodeId);

        return startOffsets[visualNodeId];
    }

    public int getIncidentEdgeEnd(
            final int visualNodeId) {

        validateVisualNodeId(visualNodeId);

        return startOffsets[visualNodeId + 1];
    }

    public int getIncidentEdgeId(
            final int incidenceIndex) {

        if (incidenceIndex < 0
                || incidenceIndex >= incidentEdgeIds.length) {

            throw new IndexOutOfBoundsException(
                    "Incidence index: "
                            + incidenceIndex
                            + ", size: "
                            + incidentEdgeIds.length);
        }

        return incidentEdgeIds[incidenceIndex];
    }

    public int getIncidenceCount() {
        return incidentEdgeIds.length;
    }

    private void validateVisualNodeId(
            final int visualNodeId) {

        if (visualNodeId < 0
                || visualNodeId >= nodeCount) {

            throw new IndexOutOfBoundsException(
                    "Visual node ID: "
                            + visualNodeId
                            + ", node count: "
                            + nodeCount);
        }
    }

    private static void validateNodeId(
            final int nodeId,
            final int nodeCount,
            final String endpoint,
            final int edgeId) {

        if (nodeId < 0 || nodeId >= nodeCount) {
            throw new IllegalArgumentException(
                    "Invalid "
                            + endpoint
                            + " node ID "
                            + nodeId
                            + " for visual edge "
                            + edgeId
                            + "; node count is "
                            + nodeCount);
        }
    }
}