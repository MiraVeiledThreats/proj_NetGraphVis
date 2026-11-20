package netgraph.layout;

import java.util.BitSet;

/**
 * Double-buffered layout coordinates and simulation velocity state.
 *
 * Callers must hold simulationLock() before accessing simulation arrays and
 * renderLock() before accessing visible arrays.
 */
public final class LayoutState {
    private final Object simulationLock =
            new Object();

    private final Object renderLock =
            new Object();

    private final float[] simulationX;
    private final float[] simulationY;
    private final float[] velocityX;
    private final float[] velocityY;

    private float[] visibleX;
    private float[] visibleY;
    private float[] stagingX;
    private float[] stagingY;

    public LayoutState(final int nodeCount) {
        if (nodeCount < 0) {
            throw new IllegalArgumentException(
                    "Node count cannot be negative.");
        }

        simulationX = new float[nodeCount];
        simulationY = new float[nodeCount];
        velocityX = new float[nodeCount];
        velocityY = new float[nodeCount];

        visibleX = new float[nodeCount];
        visibleY = new float[nodeCount];

        stagingX = new float[nodeCount];
        stagingY = new float[nodeCount];
    }

    public Object simulationLock() {
        return simulationLock;
    }

    public Object renderLock() {
        return renderLock;
    }

    public float[] simulationXUnsafe() {
        return simulationX;
    }

    public float[] simulationYUnsafe() {
        return simulationY;
    }

    public float[] velocityXUnsafe() {
        return velocityX;
    }

    public float[] velocityYUnsafe() {
        return velocityY;
    }

    public float[] visibleXUnsafe() {
        return visibleX;
    }

    public float[] visibleYUnsafe() {
        return visibleY;
    }

    public void publishSimulationCoordinates() {
        synchronized (simulationLock) {
            System.arraycopy(
                    simulationX,
                    0,
                    stagingX,
                    0,
                    simulationX.length);

            System.arraycopy(
                    simulationY,
                    0,
                    stagingY,
                    0,
                    simulationY.length);

            synchronized (renderLock) {
                final float[] oldVisibleX = visibleX;
                final float[] oldVisibleY = visibleY;

                visibleX = stagingX;
                visibleY = stagingY;

                stagingX = oldVisibleX;
                stagingY = oldVisibleY;
            }
        }
    }

    public void setNodePositionEverywhere(
            final int nodeId,
            final float x,
            final float y) {

        synchronized (simulationLock) {
            simulationX[nodeId] = x;
            simulationY[nodeId] = y;
            velocityX[nodeId] = 0.0f;
            velocityY[nodeId] = 0.0f;

            synchronized (renderLock) {
                visibleX[nodeId] = x;
                visibleY[nodeId] = y;
                stagingX[nodeId] = x;
                stagingY[nodeId] = y;
            }
        }
    }

    /**
     * Translates selected nodes in every coordinate buffer so manual movement
     * remains stable if layout coordinates are published later.
     */
    public void translateNodesEverywhere(
            final BitSet nodeIds,
            final float deltaX,
            final float deltaY) {

        if (nodeIds == null
                || nodeIds.nextSetBit(0) < 0
                || (deltaX == 0.0f && deltaY == 0.0f)) {

            return;
        }

        synchronized (simulationLock) {
            synchronized (renderLock) {
                for (int nodeId = nodeIds.nextSetBit(0);
                        nodeId >= 0;
                        nodeId = nodeIds.nextSetBit(nodeId + 1)) {

                    if (nodeId >= simulationX.length) {
                        break;
                    }

                    simulationX[nodeId] += deltaX;
                    simulationY[nodeId] += deltaY;

                    velocityX[nodeId] = 0.0f;
                    velocityY[nodeId] = 0.0f;

                    visibleX[nodeId] += deltaX;
                    visibleY[nodeId] += deltaY;

                    stagingX[nodeId] += deltaX;
                    stagingY[nodeId] += deltaY;
                }
            }
        }
    }
    
}