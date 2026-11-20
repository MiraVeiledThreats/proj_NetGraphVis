package netgraph.borders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;

/**
 * Immutable border geometry built from current rendered node coordinates.
 *
 * CIDR parent borders are expanded around represented child-network borders.
 * Networks without a CIDR parent are never structurally nested inside one
 * another merely because their rectangles happen to overlap.
 */
public final class NetworkBorderSnapshot {
    private static final float BASE_PADDING = 34.0f;
    /*
     * Parent CIDR borders must leave enough room for their own foreground
     * labels above child-border labels. At LABEL_ZOOM (0.40), 64 world units
     * produce approximately 26 screen pixels of label-baseline separation.
     */
    private static final float CHILD_BORDER_GAP = 64.0f;

    private static final NetworkBorderSnapshot EMPTY =
            new NetworkBorderSnapshot(
                    new NetworkBorder[0]);

    private final NetworkBorder[] borders;

    public NetworkBorderSnapshot(
            final NetworkBorder[] borders) {

        this.borders = borders == null
                ? new NetworkBorder[0]
                : borders;
    }

    public static NetworkBorderSnapshot empty() {
        return EMPTY;
    }

    public int getBorderCount() {
        return borders.length;
    }

    public NetworkBorder getBorder(final int index) {
        return borders[index];
    }

    public NetworkBorder[] getBordersUnsafe() {
        return borders;
    }

    public static NetworkBorderSnapshot build(
            final GraphStore store,
            final float[] x,
            final float[] y) {

        if (store == null
                || store.isEmpty()
                || x == null
                || y == null
                || x.length < store.getNodeCount()
                || y.length < store.getNodeCount()) {

            return empty();
        }

        final GraphMetadata.NetworkTable networks =
                store.getGraphMetadata()
                        .getNetworkTable();

        final int networkCount =
                networks.networkCount();

        final int undeterminedId =
                networkCount;

        final float[] minX =
                new float[networkCount + 1];

        final float[] minY =
                new float[networkCount + 1];

        final float[] maxX =
                new float[networkCount + 1];

        final float[] maxY =
                new float[networkCount + 1];

        final int[] memberCount =
                new int[networkCount + 1];

        Arrays.fill(minX, Float.MAX_VALUE);
        Arrays.fill(minY, Float.MAX_VALUE);
        Arrays.fill(maxX, -Float.MAX_VALUE);
        Arrays.fill(maxY, -Float.MAX_VALUE);

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

            final int start =
                    networks.getMembershipStart(nodeId);

            final int end =
                    networks.getMembershipEnd(nodeId);

            if (start == end) {
                include(
                        undeterminedId,
                        x[nodeId],
                        y[nodeId],
                        minX,
                        minY,
                        maxX,
                        maxY,
                        memberCount);

                continue;
            }

            for (int membership = start;
                    membership < end;
                    membership++) {

                include(
                        networks.getMembershipNetworkId(
                                membership),
                        x[nodeId],
                        y[nodeId],
                        minX,
                        minY,
                        maxX,
                        maxY,
                        memberCount);
            }
        }

        final boolean[] represented =
                new boolean[networkCount];

        for (int networkId = 0;
                networkId < networkCount;
                networkId++) {

            represented[networkId] =
                    memberCount[networkId] > 0;
        }

        /*
         * Start every visible network with a base-padded rectangle.
         */
        final float[] borderMinX =
                Arrays.copyOf(minX, minX.length);

        final float[] borderMinY =
                Arrays.copyOf(minY, minY.length);

        final float[] borderMaxX =
                Arrays.copyOf(maxX, maxX.length);

        final float[] borderMaxY =
                Arrays.copyOf(maxY, maxY.length);

        for (int networkId = 0;
                networkId <= networkCount;
                networkId++) {

            if (memberCount[networkId] == 0) {
                continue;
            }

            borderMinX[networkId] -= BASE_PADDING;
            borderMinY[networkId] -= BASE_PADDING;
            borderMaxX[networkId] += BASE_PADDING;
            borderMaxY[networkId] += BASE_PADDING;
        }

        final int[] parentId =
                immediateParents(networks, represented);

        final ArrayList<Integer> specificityOrder =
                new ArrayList<Integer>();

        for (int networkId = 0;
                networkId < networkCount;
                networkId++) {

            if (represented[networkId]) {
                specificityOrder.add(networkId);
            }
        }

        /*
         * Process most-specific networks first. Each child's complete padded
         * rectangle is included in its immediate CIDR parent's rectangle.
         */
        specificityOrder.sort(
                new Comparator<Integer>() {
                    @Override
                    public int compare(
                            final Integer first,
                            final Integer second) {

                        return Integer.compare(
                                networks.getPrefixLength(second),
                                networks.getPrefixLength(first));
                    }
                });

        for (int index = 0;
                index < specificityOrder.size();
                index++) {

            final int childId =
                    specificityOrder.get(index);

            final int parent =
                    parentId[childId];

            if (parent < 0) {
                continue;
            }

            borderMinX[parent] =
                    Math.min(
                            borderMinX[parent],
                            borderMinX[childId]
                                    - CHILD_BORDER_GAP);

            borderMinY[parent] =
                    Math.min(
                            borderMinY[parent],
                            borderMinY[childId]
                                    - CHILD_BORDER_GAP);

            borderMaxX[parent] =
                    Math.max(
                            borderMaxX[parent],
                            borderMaxX[childId]
                                    + CHILD_BORDER_GAP);

            borderMaxY[parent] =
                    Math.max(
                            borderMaxY[parent],
                            borderMaxY[childId]
                                    + CHILD_BORDER_GAP);
        }

        final ArrayList<NetworkBorder> borders =
                new ArrayList<NetworkBorder>();

        for (int networkId = 0;
                networkId < networkCount;
                networkId++) {

            if (!represented[networkId]) {
                continue;
            }

            borders.add(
                    new NetworkBorder(
                            networkId,
                            false,
                            networks.getCidr(networkId)
                                    + " — "
                                    + networks.getName(networkId),
                            borderMinX[networkId],
                            borderMinY[networkId],
                            borderMaxX[networkId],
                            borderMaxY[networkId],
                            memberCount[networkId]));
        }

        if (memberCount[undeterminedId] > 0) {
            borders.add(
                    new NetworkBorder(
                            undeterminedId,
                            true,
                            "Undetermined Network",
                            borderMinX[undeterminedId],
                            borderMinY[undeterminedId],
                            borderMaxX[undeterminedId],
                            borderMaxY[undeterminedId],
                            memberCount[undeterminedId]));
        }

        /*
         * Broad parent networks draw first; child borders draw later and
         * remain easier to see and select.
         */
        borders.sort(
                new Comparator<NetworkBorder>() {
                    @Override
                    public int compare(
                            final NetworkBorder first,
                            final NetworkBorder second) {

                        if (first.isUndetermined()) {
                            return -1;
                        }

                        if (second.isUndetermined()) {
                            return 1;
                        }

                        return Integer.compare(
                                networks.getPrefixLength(
                                        first.getNetworkId()),
                                networks.getPrefixLength(
                                        second.getNetworkId()));
                    }
                });

        return new NetworkBorderSnapshot(
                borders.toArray(
                        new NetworkBorder[borders.size()]));
    }

    /*
     * The NetworkTable precomputes immediate strict-CIDR parents once when
     * membership metadata is installed. Filter by represented networks here;
     * do not perform an O(R^2) CIDR comparison during every border snapshot.
     */
    private static int[] immediateParents(
            final GraphMetadata.NetworkTable networks,
            final boolean[] represented) {

        final int[] parents = new int[networks.networkCount()];
        Arrays.fill(parents, -1);

        for (int networkId = 0;
                networkId < networks.networkCount();
                networkId++) {

            if (!represented[networkId]) {
                continue;
            }

            final int parent = networks.getParentNetworkId(networkId);

            if (parent >= 0 && represented[parent]) {
                parents[networkId] = parent;
            }
        }

        return parents;
    }

    private static void include(
            final int networkId,
            final float x,
            final float y,
            final float[] minX,
            final float[] minY,
            final float[] maxX,
            final float[] maxY,
            final int[] memberCount) {

        minX[networkId] =
                Math.min(minX[networkId], x);

        minY[networkId] =
                Math.min(minY[networkId], y);

        maxX[networkId] =
                Math.max(maxX[networkId], x);

        maxY[networkId] =
                Math.max(maxY[networkId], y);

        memberCount[networkId]++;
    }
}