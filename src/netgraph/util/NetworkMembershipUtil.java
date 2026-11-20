package netgraph.util;

import netgraph.metadata.GraphMetadata;

/**
 * Utilities for determining which NIC/CIDR reference networks are actually
 * represented by at least one node in the loaded graph.
 */
public final class NetworkMembershipUtil {
    private NetworkMembershipUtil() {
    }

    /**
     * Returns a mask indexed by network ID. A true value means at least one
     * graph node has membership in that network.
     *
     * This intentionally uses the graph membership table rather than the
     * complete NIC reference list, so reference-only networks that have no
     * represented source or destination node are excluded.
     */
    public static boolean[] presentNetworkMask(
            final GraphMetadata.NetworkTable networks) {

        if (networks == null) {
            throw new IllegalArgumentException(
                    "Network table is required.");
        }

        final int networkCount =
                networks.networkCount();

        final boolean[] present =
                new boolean[networkCount];

        for (int nodeId = 0;
                nodeId < networks.getNodeCount();
                nodeId++) {

            final int start =
                    networks.getMembershipStart(nodeId);

            final int end =
                    networks.getMembershipEnd(nodeId);

            for (int membershipIndex = start;
                    membershipIndex < end;
                    membershipIndex++) {

                final int networkId =
                        networks.getMembershipNetworkId(
                                membershipIndex);

                /*
                 * NetworkTable membership IDs are expected to be valid.
                 * Retain this bound check so malformed external metadata
                 * cannot cause an annotation or export failure.
                 */
                if (networkId >= 0
                        && networkId < networkCount) {

                    present[networkId] = true;
                }
            }
        }

        return present;
    }
}