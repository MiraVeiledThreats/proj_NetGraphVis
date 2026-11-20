package netgraph.rendering;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.BitSet;

import netgraph.borders.NetworkBorder;
import netgraph.borders.NetworkBorderSnapshot;
import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.DeviceTypes;
import netgraph.util.IntBag;

/**
 * Immediate-mode packed-graph renderer.
 *
 * Nodes use a multi-tag inner disk and connected-device composition outer
 * ring. Edges use type-aware color, log-scaled thickness, line style, and
 * direction arrows.
 */
public final class GraphRenderer {
    private static final Color OT_COLOR =
            new Color(0x1D, 0x81, 0xDE);

    private static final Color IT_COLOR =
            new Color(0xE0, 0xC3, 0x1F);

    private static final Color IOC_COLOR =
            new Color(0xCF, 0x2B, 0x0A);

    private static final Color UNK_COLOR =
            new Color(0xA8, 0xAE, 0xB3);

    private static final Color BORDER_COLOR =
            new Color(0xC6, 0xCB, 0xD1);

    private static final Color NODE_OUTLINE =
            new Color(38, 43, 48);

    private static final Color SELECTED_OUTLINE =
            new Color(0, 0, 0);

        private static final Color SELECTION_COLOR =
                new Color(0x00, 0x5E, 0xB8);

    private static final Color LABEL_COLOR =
            new Color(35, 35, 35);

    private static final Color RING_BACKGROUND =
            new Color(245, 247, 249);

    private static final Color DENSITY_COLOR =
            new Color(58, 84, 128, 105);

    private static final Color EDGE_OT_COLOR =
            new Color(0x8D, 0xC7, 0xF2);

    private static final Color EDGE_IT_COLOR =
            new Color(0xF2, 0xE5, 0x82);

    private static final Color EDGE_IOC_COLOR =
            new Color(0xF2, 0x9B, 0x91);

    private static final Color EDGE_UNK_COLOR =
            new Color(0xC8, 0xCE, 0xD4);

    private static final byte[] TYPES = {
            DeviceTypes.OT,
            DeviceTypes.IT,
            DeviceTypes.IOC,
            DeviceTypes.UNK
    };

    private static final int LABEL_NODE_LIMIT = 2_000;
    private static final double LABEL_ZOOM = 0.40;
    private static final int DENSITY_NODE_LIMIT = 100_000;

    private final IntBag candidateNodes =
            new IntBag();

    private final IntBag visibleNodes =
            new IntBag();

    private final NetworkTrafficSummaryRenderer
            networkTrafficSummaryRenderer =
                    new NetworkTrafficSummaryRenderer();

    private final int[] neighborTypeCounts =
            new int[TYPES.length];

    private int[] edgeFrameStamp =
            new int[0];

    private int[] neighborSeenStamp =
            new int[0];

    private int frameStamp = 1;
    private int neighborStamp = 1;

    /**
     * Backward-compatible render entry point. Existing callers that have not
     * yet been updated render the complete graph.
     */
    public void render(
            final Graphics2D graphics,
            final GraphStore store,
            final float[] x,
            final float[] y,
            final NetworkBorderSnapshot borders,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNodes,
            final BitSet selectedEdges,
            final BitSet selectedNetworkBorders,
            final boolean selectedUndeterminedBorder) {

        render(
                graphics,
                store,
                x,
                y,
                borders,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY,
                selectedNodes,
                selectedEdges,
                selectedNetworkBorders,
                selectedUndeterminedBorder,
                GraphDisplayFilter.all());
    }

    /**
     * Filter-aware render entry point.
     *
     * Devices mode:
     * - filtered devices and filtered directed edges are rendered normally.
     *
     * Network-summary mode:
     * - devices are hidden;
     * - each directed inter-network aggregate is rendered as one arrow;
     * - arrows use the same logarithmic width formula as normal edges.
     */
    public void render(
            final Graphics2D graphics,
            final GraphStore store,
            final float[] x,
            final float[] y,
            final NetworkBorderSnapshot borders,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNodes,
            final BitSet selectedEdges,
            final BitSet selectedNetworkBorders,
            final boolean selectedUndeterminedBorder,
            final GraphDisplayFilter requestedFilter) {

        if (graphics == null
                || store == null
                || store.isEmpty()
                || x == null
                || y == null
                || x.length < store.getNodeCount()
                || y.length < store.getNodeCount()
                || zoom <= 0.0) {

            return;
        }

        final GraphDisplayFilter filter =
                requestedFilter == null
                        ? GraphDisplayFilter.all()
                        : requestedFilter;

        ensureCapacity(
                store.getNodeCount(),
                store.getEdgeCount());

        advanceFrameStamp();

        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        final float inverseZoom =
                (float) (1.0 / zoom);

        final float worldLeft =
                (float) (-panX * inverseZoom);

        final float worldTop =
                (float) (-panY * inverseZoom);

        final float worldRight =
                (float) ((panelWidth - panX) * inverseZoom);

        final float worldBottom =
                (float) ((panelHeight - panY) * inverseZoom);

        store.getSpatialIndex().query(
                worldLeft,
                worldTop,
                worldRight,
                worldBottom,
                candidateNodes);

        visibleNodes.clear();

        for (int index = 0;
                index < candidateNodes.size();
                index++) {

            final int nodeId =
                    candidateNodes.get(index);

            if (x[nodeId] < worldLeft
                    || x[nodeId] > worldRight
                    || y[nodeId] < worldTop
                    || y[nodeId] > worldBottom
                    || !filter.matchesNode(store, nodeId)) {

                continue;
            }

            visibleNodes.add(nodeId);
        }

        /*
         * Draw background network borders before graph data. Borders are
         * redrawn later as foreground elements, preserving the established
         * visual hierarchy.
         */
        drawNetworkBorders(
                graphics,
                borders,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        if (!filter.isShowDevices()) {
            /*
             * Devices unchecked: display aggregate filtered network-to-network
             * traffic only when edges remain enabled.
             */
            if (filter.isShowEdges()) {
                networkTrafficSummaryRenderer.draw(
                        graphics,
                        store,
                        borders,
                        filter,
                        zoom,
                        panX,
                        panY);
            }

        } else if (visibleNodes.size() > DENSITY_NODE_LIMIT) {
            /*
             * Preserve existing high-density behavior. At this volume,
             * individual edges and detailed device glyphs are intentionally
             * suppressed for responsiveness.
             */
            drawNodeDensity(
                    graphics,
                    x,
                    y,
                    zoom,
                    panX,
                    panY);

        } else {
            if (filter.isShowEdges()) {
                drawEdges(
                        graphics,
                        store,
                        x,
                        y,
                        zoom,
                        panX,
                        panY,
                        selectedEdges,
                        filter);
            }

            drawNodes(
                    graphics,
                    store,
                    x,
                    y,
                    zoom,
                    panX,
                    panY,
                    selectedNodes);
        }

        /*
         * Existing foreground border behavior is retained.
         */
        drawNetworkBorders(
                graphics,
                borders,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        drawNetworkBorderLabels(
                graphics,
                borders,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        /*
         * Device labels are irrelevant while devices are hidden.
         */
        if (filter.isShowDevices()
                && zoom >= LABEL_ZOOM
                && visibleNodes.size() <= LABEL_NODE_LIMIT) {

            drawLabels(
                    graphics,
                    store,
                    x,
                    y,
                    zoom,
                    panX,
                    panY);
        }
    }

    private void drawNetworkBorders(
            final Graphics2D graphics,
            final NetworkBorderSnapshot snapshot,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNetworkBorders,
            final boolean selectedUndeterminedBorder) {

        if (snapshot == null
                || snapshot.getBorderCount() == 0) {

            return;
        }

        for (int index = 0;
                index < snapshot.getBorderCount();
                index++) {

            final NetworkBorder border =
                    snapshot.getBorder(index);

            final boolean selected =
                    border.isUndetermined()
                            ? selectedUndeterminedBorder
                            : selectedNetworkBorders != null
                                    && selectedNetworkBorders.get(
                                            border.getNetworkId());

            graphics.setColor(
                    selected
                            ? SELECTION_COLOR
                            : BORDER_COLOR);

            graphics.setStroke(
                    selected
                            ? new BasicStroke(
                                    2.8f,
                                    BasicStroke.CAP_BUTT,
                                    BasicStroke.JOIN_MITER)
                            : new BasicStroke(
                                    1.5f,
                                    BasicStroke.CAP_BUTT,
                                    BasicStroke.JOIN_MITER,
                                    10.0f,
                                    new float[] { 8.0f, 5.0f },
                                    0.0f));

            final int screenX =
                    screenX(
                            border.getMinX(),
                            zoom,
                            panX);

            final int screenY =
                    screenY(
                            border.getMinY(),
                            zoom,
                            panY);

            final int width =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (border.getMaxX()
                                            - border.getMinX())
                                            * zoom));

            final int height =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (border.getMaxY()
                                            - border.getMinY())
                                            * zoom));

            graphics.drawRect(
                    screenX,
                    screenY,
                    width,
                    height);
        }
    }

    /**
     * Draws bold network-border labels after all graph geometry so the labels
     * remain readable in the foreground.
     */
    private void drawNetworkBorderLabels(
            final Graphics2D graphics,
            final NetworkBorderSnapshot snapshot,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNetworkBorders,
            final boolean selectedUndeterminedBorder) {

        if (snapshot == null
                || snapshot.getBorderCount() == 0
                || zoom < LABEL_ZOOM) {

            return;
        }

        final Font originalFont =
                graphics.getFont();

        graphics.setFont(
                originalFont.deriveFont(
                        Font.BOLD));

        try {
            for (int index = 0;
                    index < snapshot.getBorderCount();
                    index++) {

                final NetworkBorder border =
                        snapshot.getBorder(index);

                final String label =
                        border.getLabel();

                if (label == null
                        || label.isEmpty()) {

                    continue;
                }

                final boolean selected =
                        border.isUndetermined()
                                ? selectedUndeterminedBorder
                                : selectedNetworkBorders != null
                                        && selectedNetworkBorders.get(
                                                border.getNetworkId());

                graphics.setColor(
                        selected
                                ? SELECTION_COLOR
                                : BORDER_COLOR);

                final int screenX =
                        screenX(
                                border.getMinX(),
                                zoom,
                                panX);

                final int screenY =
                        screenY(
                                border.getMinY(),
                                zoom,
                                panY);

                graphics.drawString(
                        label,
                        screenX + 6,
                        screenY - 6);
            }

        } finally {
            graphics.setFont(originalFont);
        }
    }

    private void drawEdges(
            final Graphics2D graphics,
            final GraphStore store,
            final float[] x,
            final float[] y,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedEdges,
            final GraphDisplayFilter filter) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int visibleIndex = 0;
                visibleIndex < visibleNodes.size();
                visibleIndex++) {

            final int visibleNode =
                    visibleNodes.get(visibleIndex);

            final int start =
                    store.getIncidentEdgeStart(visibleNode);

            final int end =
                    store.getIncidentEdgeEnd(visibleNode);

            for (int position = start;
                    position < end;
                    position++) {

                final int edgeId =
                        store.getIncidentEdgeId(position);

                if (edgeFrameStamp[edgeId] == frameStamp) {
                    continue;
                }

                edgeFrameStamp[edgeId] = frameStamp;

                final int sourceNode =
                        store.getEdgeSourceNodeId(edgeId);

                final int targetNode =
                        store.getEdgeTargetNodeId(edgeId);

                if (sourceNode == targetNode
                        || !filter.matchesEdge(store, edgeId)) {

                    continue;
                }

                final long connectionCount =
                        store.getEdgeConnectionCount(edgeId);

                final long successfulCount =
                        store.getEdgeSuccessfulConnectionCount(
                                edgeId);

                final byte sourceTags =
                        metadata.effectiveNodeTypeMask(
                                sourceNode,
                                store.getNodeType(sourceNode));

                final byte targetTags =
                        metadata.effectiveNodeTypeMask(
                                targetNode,
                                store.getNodeType(targetNode));

                final boolean selected =
                        selectedEdges != null
                                && selectedEdges.get(edgeId);

                final float baseWidth =
                        edgeWidth(connectionCount);

                final float drawWidth =
                        selected
                                ? baseWidth + 1.8f
                                : baseWidth;

                final boolean dashed =
                        successfulCount <= 0L;

                graphics.setStroke(
                        dashed
                                ? new BasicStroke(
                                        drawWidth,
                                        BasicStroke.CAP_ROUND,
                                        BasicStroke.JOIN_ROUND,
                                        10.0f,
                                        new float[] { 7.0f, 5.0f },
                                        0.0f)
                                : new BasicStroke(
                                        drawWidth,
                                        BasicStroke.CAP_ROUND,
                                        BasicStroke.JOIN_ROUND));

                graphics.setColor(
                        selected
                                ? SELECTION_COLOR
                                : edgeColor(
                                        sourceTags,
                                        targetTags));

                drawDirectedEdge(
                        graphics,
                        screenX(
                                x[sourceNode],
                                zoom,
                                panX),
                        screenY(
                                y[sourceNode],
                                zoom,
                                panY),
                        screenX(
                                x[targetNode],
                                zoom,
                                panX),
                        screenY(
                                y[targetNode],
                                zoom,
                                panY),
                        outerRadius(zoom),
                        drawWidth);
            }
        }
    }

    private void drawDirectedEdge(
            final Graphics2D graphics,
            final int sourceX,
            final int sourceY,
            final int targetX,
            final int targetY,
            final int nodeRadius,
            final float width) {

        final double deltaX =
                targetX - sourceX;

        final double deltaY =
                targetY - sourceY;

        final double length =
                Math.sqrt(
                        deltaX * deltaX
                                + deltaY * deltaY);

        if (length < 1.0) {
            return;
        }

        final double unitX =
                deltaX / length;

        final double unitY =
                deltaY / length;

        final double arrowLength =
                Math.max(8.0, width * 3.5);

        final double startOffset =
                nodeRadius + width * 0.5 + 1.0;

        final double endOffset =
                nodeRadius + arrowLength;

        final double lineStartX =
                sourceX + unitX * startOffset;

        final double lineStartY =
                sourceY + unitY * startOffset;

        final double arrowTipX =
                targetX - unitX * nodeRadius;

        final double arrowTipY =
                targetY - unitY * nodeRadius;

        final double arrowBaseX =
                targetX - unitX * endOffset;

        final double arrowBaseY =
                targetY - unitY * endOffset;

        graphics.drawLine(
                (int) Math.round(lineStartX),
                (int) Math.round(lineStartY),
                (int) Math.round(arrowBaseX),
                (int) Math.round(arrowBaseY));

        final double perpendicularX =
                -unitY;

        final double perpendicularY =
                unitX;

        final double halfWidth =
                Math.max(4.0, arrowLength * 0.48);

        final int[] arrowX = {
                (int) Math.round(arrowTipX),
                (int) Math.round(
                        arrowBaseX
                                + perpendicularX * halfWidth),
                (int) Math.round(
                        arrowBaseX
                                - perpendicularX * halfWidth)
        };

        final int[] arrowY = {
                (int) Math.round(arrowTipY),
                (int) Math.round(
                        arrowBaseY
                                + perpendicularY * halfWidth),
                (int) Math.round(
                        arrowBaseY
                                - perpendicularY * halfWidth)
        };

        graphics.fillPolygon(
                arrowX,
                arrowY,
                arrowX.length);
    }

    private void drawNodes(
            final Graphics2D graphics,
            final GraphStore store,
            final float[] x,
            final float[] y,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNodes) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final int outerRadius =
                outerRadius(zoom);

        final int innerRadius =
                Math.max(
                        2,
                        outerRadius - 4);

        for (int index = 0;
                index < visibleNodes.size();
                index++) {

            final int nodeId =
                    visibleNodes.get(index);

            final int centerX =
                    screenX(
                            x[nodeId],
                            zoom,
                            panX);

            final int centerY =
                    screenY(
                            y[nodeId],
                            zoom,
                            panY);

            final byte tags =
                    metadata.effectiveNodeTypeMask(
                            nodeId,
                            store.getNodeType(nodeId));

            if (outerRadius < 6) {
                graphics.setColor(
                        colorFor(
                                DeviceTypes.primaryType(tags)));

                graphics.fillOval(
                        centerX - outerRadius,
                        centerY - outerRadius,
                        outerRadius * 2,
                        outerRadius * 2);

            } else {
                drawOuterRing(
                        graphics,
                        store,
                        metadata,
                        nodeId,
                        centerX,
                        centerY,
                        outerRadius);

                drawInnerSegments(
                        graphics,
                        tags,
                        centerX,
                        centerY,
                        innerRadius);
            }

            graphics.setStroke(
                    new BasicStroke(
                            selectedNodes != null
                                    && selectedNodes.get(nodeId)
                                            ? 2.4f
                                            : 1.2f));

            graphics.setColor(
                    selectedNodes != null
                            && selectedNodes.get(nodeId)
                                    ? SELECTED_OUTLINE
                                    : NODE_OUTLINE);

            graphics.drawOval(
                    centerX - outerRadius,
                    centerY - outerRadius,
                    outerRadius * 2,
                    outerRadius * 2);
        }
    }

    private void drawInnerSegments(
            final Graphics2D graphics,
            final byte tags,
            final int centerX,
            final int centerY,
            final int radius) {

        final int tagCount =
                Math.max(
                        1,
                        DeviceTypes.tagCount(tags));

        int startAngle = 90;

        for (int typeIndex = 0;
                typeIndex < TYPES.length;
                typeIndex++) {

            final byte type = TYPES[typeIndex];

            if (!DeviceTypes.has(tags, type)) {
                continue;
            }

            final int extent =
                    360 / tagCount;

            graphics.setColor(colorFor(type));

            graphics.fillArc(
                    centerX - radius,
                    centerY - radius,
                    radius * 2,
                    radius * 2,
                    startAngle,
                    -extent);

            startAngle -= extent;
        }

        graphics.setColor(NODE_OUTLINE);
        graphics.setStroke(
                new BasicStroke(0.8f));

        graphics.drawOval(
                centerX - radius,
                centerY - radius,
                radius * 2,
                radius * 2);
    }

    private void drawOuterRing(
            final Graphics2D graphics,
            final GraphStore store,
            final GraphMetadata metadata,
            final int nodeId,
            final int centerX,
            final int centerY,
            final int radius) {

        graphics.setColor(RING_BACKGROUND);

        graphics.fillOval(
                centerX - radius,
                centerY - radius,
                radius * 2,
                radius * 2);

        final int total =
                countNeighborTags(
                        store,
                        metadata,
                        nodeId);

        if (total <= 0) {
            return;
        }

        final int ringWidth =
                Math.max(2, radius / 3);

        graphics.setStroke(
                new BasicStroke(
                        ringWidth,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_ROUND));

        int startAngle = 90;
        int usedAngle = 0;

        for (int typeIndex = 0;
                typeIndex < TYPES.length;
                typeIndex++) {

            final int count =
                    neighborTypeCounts[typeIndex];

            if (count <= 0) {
                continue;
            }

            int extent =
                    (int) Math.round(
                            360.0 * count / total);

            if (usedAngle + extent > 360) {
                extent = 360 - usedAngle;
            }

            if (extent <= 0) {
                continue;
            }

            graphics.setColor(
                    colorFor(TYPES[typeIndex]));

            graphics.drawArc(
                    centerX - radius + ringWidth / 2,
                    centerY - radius + ringWidth / 2,
                    radius * 2 - ringWidth,
                    radius * 2 - ringWidth,
                    startAngle,
                    -extent);

            startAngle -= extent;
            usedAngle += extent;
        }
    }

    /**
     * Counts unique directly connected devices, rather than directed edges.
     * A neighbor holding OT and IT contributes to both OT and IT counts.
     */
    private int countNeighborTags(
            final GraphStore store,
            final GraphMetadata metadata,
            final int nodeId) {

        Arrays.fill(neighborTypeCounts, 0);

        final int stamp =
                nextNeighborStamp();

        int total = 0;

        final int start =
                store.getIncidentEdgeStart(nodeId);

        final int end =
                store.getIncidentEdgeEnd(nodeId);

        for (int position = start;
                position < end;
                position++) {

            final int edgeId =
                    store.getIncidentEdgeId(position);

            final int source =
                    store.getEdgeSourceNodeId(edgeId);

            final int target =
                    store.getEdgeTargetNodeId(edgeId);

            final int other =
                    source == nodeId
                            ? target
                            : source;

            if (other == nodeId
                    || neighborSeenStamp[other] == stamp) {

                continue;
            }

            neighborSeenStamp[other] = stamp;

            final byte tags =
                    metadata.effectiveNodeTypeMask(
                            other,
                            store.getNodeType(other));

            for (int typeIndex = 0;
                    typeIndex < TYPES.length;
                    typeIndex++) {

                if (DeviceTypes.has(tags, TYPES[typeIndex])) {
                    neighborTypeCounts[typeIndex]++;
                    total++;
                }
            }
        }

        return total;
    }

    private void drawLabels(
            final Graphics2D graphics,
            final GraphStore store,
            final float[] x,
            final float[] y,
            final double zoom,
            final double panX,
            final double panY) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final Font originalFont =
                graphics.getFont();

        graphics.setFont(
                originalFont.deriveFont(
                        Font.BOLD));

        graphics.setColor(LABEL_COLOR);

        for (int index = 0;
                index < visibleNodes.size();
                index++) {

            final int nodeId =
                    visibleNodes.get(index);

            graphics.drawString(
                    metadata.getNodeIp(nodeId),
                    screenX(
                            x[nodeId],
                            zoom,
                            panX) + outerRadius(zoom) + 4,
                    screenY(
                            y[nodeId],
                            zoom,
                            panY) - outerRadius(zoom) - 3);
        }
        graphics.setFont(originalFont);
    }

    private void drawNodeDensity(
            final Graphics2D graphics,
            final float[] x,
            final float[] y,
            final double zoom,
            final double panX,
            final double panY) {

        graphics.setColor(DENSITY_COLOR);

        for (int index = 0;
                index < visibleNodes.size();
                index++) {

            final int nodeId =
                    visibleNodes.get(index);

            graphics.fillRect(
                    screenX(
                            x[nodeId],
                            zoom,
                            panX),
                    screenY(
                            y[nodeId],
                            zoom,
                            panY),
                    2,
                    2);
        }
    }

    private void ensureCapacity(
            final int nodeCount,
            final int edgeCount) {

        if (edgeFrameStamp.length < edgeCount) {
            edgeFrameStamp = Arrays.copyOf(
                    edgeFrameStamp,
                    edgeCount);
        }

        if (neighborSeenStamp.length < nodeCount) {
            neighborSeenStamp = Arrays.copyOf(
                    neighborSeenStamp,
                    nodeCount);
        }
    }

    private void advanceFrameStamp() {
        if (frameStamp == Integer.MAX_VALUE) {
            Arrays.fill(edgeFrameStamp, 0);
            frameStamp = 1;
            return;
        }

        frameStamp++;
    }

    private int nextNeighborStamp() {
        if (neighborStamp == Integer.MAX_VALUE) {
            Arrays.fill(neighborSeenStamp, 0);
            neighborStamp = 1;
        }

        return neighborStamp++;
    }

    private static float edgeWidth(
            final long connectionCount) {

        final double exact =
                2.0 * Math.log10(
                        Math.max(1L, connectionCount));

        /*
         * The requested formula yields zero for a count of one. Retain a
         * visible minimum while preserving the requested value above it.
         */
        return (float) Math.max(0.75, exact);
    }

        private static Color edgeColor(
                final byte sourceTags,
                final byte targetTags) {

        if (DeviceTypes.has(sourceTags, DeviceTypes.IOC)
                || DeviceTypes.has(targetTags, DeviceTypes.IOC)) {

                return EDGE_IOC_COLOR;
        }

        if (DeviceTypes.has(sourceTags, DeviceTypes.OT)
                || DeviceTypes.has(targetTags, DeviceTypes.OT)) {

                return EDGE_OT_COLOR;
        }

        if (sourceTags == DeviceTypes.IT_MASK
                && targetTags == DeviceTypes.IT_MASK) {

                return EDGE_IT_COLOR;
        }

        return EDGE_UNK_COLOR;
        }

    private static Color colorFor(final byte type) {
        switch (type) {
            case DeviceTypes.OT:
                return OT_COLOR;

            case DeviceTypes.IT:
                return IT_COLOR;

            case DeviceTypes.IOC:
                return IOC_COLOR;

            default:
                return UNK_COLOR;
        }
    }

    private static int outerRadius(final double zoom) {
        return zoom < 0.25
                ? 3
                : zoom < 0.75
                        ? 7
                        : 12;
    }

    private static int screenX(
            final float worldX,
            final double zoom,
            final double panX) {

        return (int) Math.round(
                worldX * zoom + panX);
    }

    private static int screenY(
            final float worldY,
            final double zoom,
            final double panY) {

        return (int) Math.round(
                worldY * zoom + panY);
    }
}