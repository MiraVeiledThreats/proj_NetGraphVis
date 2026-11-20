package netgraph.rendering;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;

import netgraph.borders.NetworkBorder;
import netgraph.borders.NetworkBorderSnapshot;
import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.LongIntIndex;

/**
 * Renders filtered directed traffic aggregates between network boundaries.
 */
final class NetworkTrafficSummaryRenderer {
    private static final Color SUMMARY_COLOR =
            new Color(0x3A, 0x54, 0x80);

    private long[] connectionCounts =
            new long[16];

    private long[] successfulCounts =
            new long[16];

    private int[] sourceGroups =
            new int[16];

    private int[] destinationGroups =
            new int[16];

    private int aggregateCount;

    void draw(
            final Graphics2D graphics,
            final GraphStore store,
            final NetworkBorderSnapshot snapshot,
            final GraphDisplayFilter filter,
            final double zoom,
            final double panX,
            final double panY) {

        if (graphics == null
                || store == null
                || store.isEmpty()
                || snapshot == null
                || snapshot.getBorderCount() == 0
                || filter == null) {

            return;
        }

        final GraphMetadata.NetworkTable networks =
                store.getGraphMetadata()
                        .getNetworkTable();

        final int networkCount =
                networks.networkCount();

        final NetworkBorder[] bordersById =
                new NetworkBorder[networkCount + 1];

        for (int index = 0;
                index < snapshot.getBorderCount();
                index++) {

            final NetworkBorder border =
                    snapshot.getBorder(index);

            final int id =
                    border.isUndetermined()
                            ? networkCount
                            : border.getNetworkId();

            if (id >= 0 && id < bordersById.length) {
                bordersById[id] = border;
            }
        }

        aggregateCount = 0;

        final LongIntIndex aggregateIndex =
                new LongIntIndex();

        for (int edgeId = 0;
                edgeId < store.getEdgeCount();
                edgeId++) {

            if (!filter.matchesEdge(store, edgeId)) {
                continue;
            }

            final int sourceGroup =
                    primaryNetwork(
                            networks,
                            store.getEdgeSourceNodeId(edgeId),
                            networkCount);

            final int destinationGroup =
                    primaryNetwork(
                            networks,
                            store.getEdgeTargetNodeId(edgeId),
                            networkCount);

            /*
             * Internal traffic remains represented by the network border
             * itself; summary arrows are only for traffic between boundaries.
             */
            if (sourceGroup == destinationGroup) {
                continue;
            }

            if (bordersById[sourceGroup] == null
                    || bordersById[destinationGroup] == null) {

                continue;
            }

            final long key =
                    groupPairKey(
                            sourceGroup,
                            destinationGroup);

            int aggregateId =
                    aggregateIndex.get(key);

            if (aggregateId < 0) {
                aggregateId = aggregateCount++;
                aggregateIndex.put(key, aggregateId);

                ensureCapacity(aggregateCount);

                sourceGroups[aggregateId] = sourceGroup;
                destinationGroups[aggregateId] =
                        destinationGroup;
            }

            connectionCounts[aggregateId] +=
                    store.getEdgeConnectionCount(edgeId);

            successfulCounts[aggregateId] +=
                    store.getEdgeSuccessfulConnectionCount(
                            edgeId);
        }

        final Map<Long, Integer> reverseLookup =
                new HashMap<Long, Integer>();

        for (int aggregateId = 0;
                aggregateId < aggregateCount;
                aggregateId++) {

            reverseLookup.put(
                    Long.valueOf(
                            groupPairKey(
                                    sourceGroups[aggregateId],
                                    destinationGroups[aggregateId])),
                    Integer.valueOf(aggregateId));
        }

        for (int aggregateId = 0;
                aggregateId < aggregateCount;
                aggregateId++) {

            final NetworkBorder sourceBorder =
                    bordersById[
                            sourceGroups[aggregateId]];

            final NetworkBorder destinationBorder =
                    bordersById[
                            destinationGroups[aggregateId]];

            final float width =
                    edgeWidth(
                            connectionCounts[aggregateId]);

            final long reverseKey =
                    groupPairKey(
                            destinationGroups[aggregateId],
                            sourceGroups[aggregateId]);

            final boolean reverseExists =
                    reverseLookup.containsKey(
                            Long.valueOf(reverseKey));

            final float offset =
                    reverseExists
                            ? sourceGroups[aggregateId]
                                    < destinationGroups[aggregateId]
                                    ? 7.0f + width
                                    : -7.0f - width
                            : 0.0f;

            graphics.setColor(SUMMARY_COLOR);

            graphics.setStroke(
                    successfulCounts[aggregateId] > 0L
                            ? new BasicStroke(
                                    width,
                                    BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND)
                            : new BasicStroke(
                                    width,
                                    BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND,
                                    10.0f,
                                    new float[] { 8.0f, 5.0f },
                                    0.0f));

            drawArrow(
                    graphics,
                    centerX(sourceBorder, zoom, panX),
                    centerY(sourceBorder, zoom, panY),
                    centerX(destinationBorder, zoom, panX),
                    centerY(destinationBorder, zoom, panY),
                    offset,
                    width);
        }
    }

    private static int primaryNetwork(
            final GraphMetadata.NetworkTable networks,
            final int nodeId,
            final int undeterminedId) {

        final int start =
                networks.getMembershipStart(nodeId);

        final int end =
                networks.getMembershipEnd(nodeId);

        return start == end
                ? undeterminedId
                : networks.getMembershipNetworkId(start);
    }

    private static long groupPairKey(
            final int source,
            final int destination) {

        return ((long) source << 32)
                ^ (destination & 0xFFFFFFFFL);
    }

    private void ensureCapacity(final int required) {
        if (required <= connectionCounts.length) {
            return;
        }

        final int capacity =
                Math.max(
                        required,
                        connectionCounts.length * 2);

        connectionCounts =
                java.util.Arrays.copyOf(
                        connectionCounts,
                        capacity);

        successfulCounts =
                java.util.Arrays.copyOf(
                        successfulCounts,
                        capacity);

        sourceGroups =
                java.util.Arrays.copyOf(
                        sourceGroups,
                        capacity);

        destinationGroups =
                java.util.Arrays.copyOf(
                        destinationGroups,
                        capacity);
    }

    private static int centerX(
            final NetworkBorder border,
            final double zoom,
            final double panX) {

        return (int) Math.round(
                ((border.getMinX()
                        + border.getMaxX()) * 0.5)
                        * zoom
                        + panX);
    }

    private static int centerY(
            final NetworkBorder border,
            final double zoom,
            final double panY) {

        return (int) Math.round(
                ((border.getMinY()
                        + border.getMaxY()) * 0.5)
                        * zoom
                        + panY);
    }

    private static float edgeWidth(
            final long connectionCount) {

        final double exact =
                2.0 * Math.log10(
                        Math.max(1L, connectionCount));

        return (float) Math.max(0.75, exact);
    }

    private static void drawArrow(
            final Graphics2D graphics,
            final int sourceX,
            final int sourceY,
            final int targetX,
            final int targetY,
            final float perpendicularOffset,
            final float width) {

        final double deltaX =
                targetX - sourceX;

        final double deltaY =
                targetY - sourceY;

        final double length =
                Math.sqrt(
                        deltaX * deltaX
                                + deltaY * deltaY);

        if (length < 2.0) {
            return;
        }

        final double unitX = deltaX / length;
        final double unitY = deltaY / length;

        final double perpendicularX = -unitY;
        final double perpendicularY = unitX;

        final double startX =
                sourceX
                        + perpendicularX
                                * perpendicularOffset;

        final double startY =
                sourceY
                        + perpendicularY
                                * perpendicularOffset;

        final double endX =
                targetX
                        + perpendicularX
                                * perpendicularOffset;

        final double endY =
                targetY
                        + perpendicularY
                                * perpendicularOffset;

        final double arrowLength =
                Math.max(10.0, width * 4.0);

        final double lineEndX =
                endX - unitX * arrowLength;

        final double lineEndY =
                endY - unitY * arrowLength;

        graphics.drawLine(
                (int) Math.round(startX),
                (int) Math.round(startY),
                (int) Math.round(lineEndX),
                (int) Math.round(lineEndY));

        final double arrowHalfWidth =
                Math.max(5.0, arrowLength * 0.48);

        final int[] x = {
                (int) Math.round(endX),
                (int) Math.round(
                        lineEndX
                                + perpendicularX
                                        * arrowHalfWidth),
                (int) Math.round(
                        lineEndX
                                - perpendicularX
                                        * arrowHalfWidth)
        };

        final int[] y = {
                (int) Math.round(endY),
                (int) Math.round(
                        lineEndY
                                + perpendicularY
                                        * arrowHalfWidth),
                (int) Math.round(
                        lineEndY
                                - perpendicularY
                                        * arrowHalfWidth)
        };

        graphics.fillPolygon(x, y, x.length);
    }
}