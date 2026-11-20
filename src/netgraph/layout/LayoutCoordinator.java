package netgraph.layout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.SwingUtilities;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;

/**
 * Single-threaded, cancellable background graph-layout coordinator.
 */
public final class LayoutCoordinator {
    //private static final int MAX_SPRING_NODES = 40_000;
    //private static final int SPRING_ITERATIONS = 48;

    //private static final float NODE_PITCH = 82.0f;
    //private static final float GROUP_MARGIN = 120.0f;

    private static final int MAX_SPRING_NODES = 40_000;
    private static final int SPRING_ITERATIONS = 48;

    private static final float NODE_PITCH = 56.0f;
    private static final float GROUP_GAP = 76.0f;
    private static final float GROUP_PADDING = 42.0f;
    private static final float MIN_GROUP_SPAN = 220.0f;
    private static final float DESIRED_EDGE_LENGTH = 82.0f;

    private final ExecutorService worker;
    private final AtomicLong requestVersion =
            new AtomicLong();

    public LayoutCoordinator() {
        worker = Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(
                            final Runnable runnable) {

                        final Thread thread =
                                new Thread(
                                        runnable,
                                        "network-graph-layout");

                        thread.setDaemon(true);
                        return thread;
                    }
                });
    }

    public void requestLayout(
            final GraphStore store,
            final boolean refineSprings,
            final Runnable completion) {

        final long version =
                requestVersion.incrementAndGet();

        worker.execute(new Runnable() {
            @Override
            public void run() {
                computeLayout(
                        store,
                        refineSprings,
                        version,
                        completion);
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private void computeLayout(
            final GraphStore store,
            final boolean refineSprings,
            final long version,
            final Runnable completion) {

        if (store == null || store.isEmpty()) {
            return;
        }

        final LayoutState state =
                store.getLayoutState();

        final int nodeCount =
                store.getNodeCount();

        final float[] anchorX =
                new float[nodeCount];

        final float[] anchorY =
                new float[nodeCount];

        synchronized (state.simulationLock()) {
            seedGroupedGrid(
                    store,
                    state,
                    anchorX,
                    anchorY);

            if (cancelled(version)) {
                return;
            }

            if (refineSprings
                    && nodeCount <= MAX_SPRING_NODES) {

                applySpringRefinement(
                        store,
                        state,
                        anchorX,
                        anchorY,
                        version);
            }
        }

        if (cancelled(version)) {
            return;
        }

        state.publishSimulationCoordinates();

        synchronized (state.renderLock()) {
            store.getSpatialIndex().rebuild(
                    state.visibleXUnsafe(),
                    state.visibleYUnsafe(),
                    nodeCount);
        }

        if (completion != null) {
            SwingUtilities.invokeLater(completion);
        }
    }

        private void seedGroupedGrid(
                final GraphStore store,
                final LayoutState state,
                final float[] anchorX,
                final float[] anchorY) {

        final GraphMetadata.NetworkTable networks =
                store.getGraphMetadata()
                        .getNetworkTable();

        final int networkCount =
                networks.networkCount();

        final int undeterminedGroup =
                networkCount;

        final int groupCount =
                networkCount + 1;

        final int[] groupSizes =
                new int[groupCount];

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

                int group =
                        networks.primaryNetworkForNode(nodeId);

                if (group < 0) {
                group = undeterminedGroup;
                }

                groupSizes[group]++;
        }

        final int groupColumns =
                Math.max(
                        1,
                        (int) Math.ceil(
                                Math.sqrt(groupCount)));

        final int groupRows =
                Math.max(
                        1,
                        (groupCount + groupColumns - 1)
                                / groupColumns);

        final int[] groupDeviceColumns =
                new int[groupCount];

        final float[] groupWidths =
                new float[groupCount];

        final float[] groupHeights =
                new float[groupCount];

        final float[] columnWidths =
                new float[groupColumns];

        final float[] rowHeights =
                new float[groupRows];

        for (int group = 0;
                group < groupCount;
                group++) {

                final int size =
                        Math.max(1, groupSizes[group]);

                final int deviceColumns =
                        Math.max(
                                1,
                                (int) Math.ceil(
                                        Math.sqrt(size)));

                final int deviceRows =
                        Math.max(
                                1,
                                (size + deviceColumns - 1)
                                        / deviceColumns);

                groupDeviceColumns[group] =
                        deviceColumns;

                groupWidths[group] =
                        Math.max(
                                MIN_GROUP_SPAN,
                                GROUP_PADDING * 2.0f
                                        + (deviceColumns - 1)
                                                * NODE_PITCH);

                groupHeights[group] =
                        Math.max(
                                MIN_GROUP_SPAN,
                                GROUP_PADDING * 2.0f
                                        + (deviceRows - 1)
                                                * NODE_PITCH);

                final int groupColumn =
                        group % groupColumns;

                final int groupRow =
                        group / groupColumns;

                columnWidths[groupColumn] =
                        Math.max(
                                columnWidths[groupColumn],
                                groupWidths[group]);

                rowHeights[groupRow] =
                        Math.max(
                                rowHeights[groupRow],
                                groupHeights[group]);
        }

        final float[] columnOffsets =
                new float[groupColumns];

        final float[] rowOffsets =
                new float[groupRows];

        for (int column = 1;
                column < groupColumns;
                column++) {

                columnOffsets[column] =
                        columnOffsets[column - 1]
                                + columnWidths[column - 1]
                                + GROUP_GAP;
        }

        for (int row = 1;
                row < groupRows;
                row++) {

                rowOffsets[row] =
                        rowOffsets[row - 1]
                                + rowHeights[row - 1]
                                + GROUP_GAP;
        }

        final int[] groupCursor =
                new int[groupCount];

        final float[] simulationX =
                state.simulationXUnsafe();

        final float[] simulationY =
                state.simulationYUnsafe();

        final float[] velocityX =
                state.velocityXUnsafe();

        final float[] velocityY =
                state.velocityYUnsafe();

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

                int group =
                        networks.primaryNetworkForNode(nodeId);

                if (group < 0) {
                group = undeterminedGroup;
                }

                final int localIndex =
                        groupCursor[group]++;

                final int deviceColumns =
                        groupDeviceColumns[group];

                final int groupColumn =
                        group % groupColumns;

                final int groupRow =
                        group / groupColumns;

                final float x =
                        columnOffsets[groupColumn]
                                + GROUP_PADDING
                                + (localIndex % deviceColumns)
                                        * NODE_PITCH;

                final float y =
                        rowOffsets[groupRow]
                                + GROUP_PADDING
                                + (localIndex / deviceColumns)
                                        * NODE_PITCH;

                simulationX[nodeId] = x;
                simulationY[nodeId] = y;

                velocityX[nodeId] = 0.0f;
                velocityY[nodeId] = 0.0f;

                anchorX[nodeId] = x;
                anchorY[nodeId] = y;
        }
        }

    private void applySpringRefinement(
            final GraphStore store,
            final LayoutState state,
            final float[] anchorX,
            final float[] anchorY,
            final long version) {

        final float[] x = state.simulationXUnsafe();
        final float[] y = state.simulationYUnsafe();
        final float[] velocityX = state.velocityXUnsafe();
        final float[] velocityY = state.velocityYUnsafe();

        for (int iteration = 0;
                iteration < SPRING_ITERATIONS;
                iteration++) {

            if (cancelled(version)) {
                return;
            }

            for (int nodeId = 0;
                    nodeId < store.getNodeCount();
                    nodeId++) {

                velocityX[nodeId] =
                        velocityX[nodeId] * 0.72f
                                + (anchorX[nodeId] - x[nodeId])
                                        * 0.020f;

                velocityY[nodeId] =
                        velocityY[nodeId] * 0.72f
                                + (anchorY[nodeId] - y[nodeId])
                                        * 0.020f;
            }

            for (int edgeId = 0;
                    edgeId < store.getEdgeCount();
                    edgeId++) {

                if ((edgeId & 1023) == 0
                        && cancelled(version)) {

                    return;
                }

                final int source =
                        store.getEdgeSourceNodeId(edgeId);

                final int target =
                        store.getEdgeTargetNodeId(edgeId);

                if (source == target) {
                    continue;
                }

                final float deltaX =
                        x[target] - x[source];

                final float deltaY =
                        y[target] - y[source];

                final float distanceSquared =
                        deltaX * deltaX
                                + deltaY * deltaY;

                final float distance =
                        distanceSquared < 1.0f
                                ? 1.0f
                                : (float) Math.sqrt(
                                        distanceSquared);

                final float desiredLength = DESIRED_EDGE_LENGTH;

                final float force =
                        (distance - desiredLength)
                                * 0.0020f;

                final float forceX =
                        deltaX / distance * force;

                final float forceY =
                        deltaY / distance * force;

                velocityX[source] += forceX;
                velocityY[source] += forceY;

                velocityX[target] -= forceX;
                velocityY[target] -= forceY;
            }

            for (int nodeId = 0;
                    nodeId < store.getNodeCount();
                    nodeId++) {

                x[nodeId] += velocityX[nodeId];
                y[nodeId] += velocityY[nodeId];
            }
        }
    }

    private boolean cancelled(final long version) {
        return requestVersion.get() != version
                || Thread.currentThread().isInterrupted();
    }
}