package netgraph.display;

import java.util.BitSet;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.DeviceTypes;

/**
 * Immutable display-filter snapshot applied by the renderer and interaction
 * layer. A snapshot is cheap to read repeatedly during a render pass.
 */
public final class GraphDisplayFilter {
    private static final GraphDisplayFilter ALL =
            new GraphDisplayFilter(
                    DeviceTypes.ALL_MASK,
                    new BitSet(),
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true);

    private final byte deviceTypeMask;
    private final BitSet selectedDestinationPorts;
    private final boolean restrictDestinationPorts;

    private final boolean includeDataTransfer;
    private final boolean includeNoDataTransfer;

    private final boolean includeSuccessfulConnections;
    private final boolean includeAttemptedConnections;

    private final boolean showDevices;
    private final boolean showEdges;

    public GraphDisplayFilter(
            final byte deviceTypeMask,
            final BitSet selectedDestinationPorts,
            final boolean restrictDestinationPorts,
            final boolean includeDataTransfer,
            final boolean includeNoDataTransfer,
            final boolean includeSuccessfulConnections,
            final boolean includeAttemptedConnections,
            final boolean showDevices,
            final boolean showEdges) {

        this.deviceTypeMask =
                DeviceTypes.normalizeMask(deviceTypeMask);

        this.selectedDestinationPorts =
                selectedDestinationPorts == null
                        ? new BitSet()
                        : (BitSet) selectedDestinationPorts.clone();

        this.restrictDestinationPorts =
                restrictDestinationPorts;

        this.includeDataTransfer = includeDataTransfer;
        this.includeNoDataTransfer = includeNoDataTransfer;

        this.includeSuccessfulConnections =
                includeSuccessfulConnections;

        this.includeAttemptedConnections =
                includeAttemptedConnections;

        this.showDevices = showDevices;
        this.showEdges = showEdges;
    }

    public static GraphDisplayFilter all() {
        return ALL;
    }

    public boolean isShowDevices() {
        return showDevices;
    }

    public boolean isShowEdges() {
        return showEdges;
    }

    /**
     * A device passes when it has at least one selected effective type tag.
     * This implements the requested logical OR behavior for OT, IT, IOC,
     * and UNK selections.
     */
    public boolean matchesNode(
            final GraphStore store,
            final int nodeId) {

        if (store == null
                || nodeId < 0
                || nodeId >= store.getNodeCount()
                || deviceTypeMask == 0) {

            return false;
        }

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final byte effectiveMask =
                metadata.effectiveNodeTypeMask(
                        nodeId,
                        store.getNodeType(nodeId));

        return (effectiveMask & deviceTypeMask) != 0;
    }

    /**
     * An edge is visible only when:
     * <ul>
     *   <li>both endpoint devices pass the selected device-type filter;</li>
     *   <li>at least one selected destination port is represented, when the
     *       destination-port control is active;</li>
     *   <li>its byte-transfer state passes; and</li>
     *   <li>its connection-outcome state passes.</li>
     * </ul>
     */
    public boolean matchesEdge(
            final GraphStore store,
            final int edgeId) {

        if (store == null
                || edgeId < 0
                || edgeId >= store.getEdgeCount()) {

            return false;
        }

        final int sourceNodeId =
                store.getEdgeSourceNodeId(edgeId);

        final int destinationNodeId =
                store.getEdgeTargetNodeId(edgeId);

        if (!matchesNode(store, sourceNodeId)
                || !matchesNode(store, destinationNodeId)) {

            return false;
        }

        if (!matchesDestinationPort(store, edgeId)) {
            return false;
        }

        final long totalBytes =
                store.getEdgeTotalBytes(edgeId);

        if (totalBytes > 0L) {
            if (!includeDataTransfer) {
                return false;
            }
        } else if (!includeNoDataTransfer) {
            return false;
        }

        final long connectionCount =
                store.getEdgeConnectionCount(edgeId);

        final long successfulCount =
                store.getEdgeSuccessfulConnectionCount(edgeId);

        final boolean successful =
                successfulCount > 0L;

        final boolean attempted =
                connectionCount > successfulCount;

        return (successful && includeSuccessfulConnections)
                || (attempted && includeAttemptedConnections);
    }

    private boolean matchesDestinationPort(
            final GraphStore store,
            final int edgeId) {

        if (!restrictDestinationPorts) {
            return true;
        }

        for (int portIndex =
                store.getEdgeFirstPort(edgeId);
                portIndex >= 0;
                portIndex =
                        store.getPortNext(portIndex)) {

            if (selectedDestinationPorts.get(
                    store.getPortNumber(portIndex))) {

                return true;
            }
        }

        return false;
    }
    public String projectionSignature() {
        final StringBuilder value =
                new StringBuilder(96);

        value.append(deviceTypeMask).append(':')
                .append(restrictDestinationPorts ? '1' : '0')
                .append(includeDataTransfer ? '1' : '0')
                .append(includeNoDataTransfer ? '1' : '0')
                .append(includeSuccessfulConnections ? '1' : '0')
                .append(includeAttemptedConnections ? '1' : '0')
                .append(showDevices ? '1' : '0')
                .append(showEdges ? '1' : '0')
                .append(':');

        for (int port =
                selectedDestinationPorts.nextSetBit(0);
                port >= 0;
                port =
                        selectedDestinationPorts.nextSetBit(
                                port + 1)) {

            value.append(port).append(',');
        }

        return value.toString();
    }
}