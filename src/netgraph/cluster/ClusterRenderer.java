package netgraph.cluster;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.BitSet;

import netgraph.borders.NetworkBorder;
import netgraph.borders.NetworkBorderSnapshot;
import netgraph.util.DeviceTypes;
import netgraph.util.IntBag;

/**
 * Immediate-mode renderer for an immutable cluster projection.
 *
 * The renderer owns only reusable paint-time scratch state. It does not modify
 * the projection, graph, or layout coordinates.
 *
 * This class is intended to be called on the Swing event-dispatch thread.
 */
public final class ClusterRenderer {
    private static final Color OT =
            new Color(0x1D, 0x81, 0xDE);

    private static final Color IT =
            new Color(0xE0, 0xC3, 0x1F);

    private static final Color IOC =
            new Color(0xCF, 0x2B, 0x0A);

    private static final Color UNK =
            new Color(0xA8, 0xAE, 0xB3);

    private static final Color NETWORK_BORDER =
            new Color(0xC6, 0xCB, 0xD1);

    private static final Color ANALYST_BORDER =
            new Color(0x71, 0xB6, 0xE6);

    private static final Color OUTLINE =
            new Color(38, 43, 48);

    private static final Color SELECTED =
            new Color(0x00, 0x5E, 0xB8);

    private static final Color RING_BACKGROUND =
            new Color(245, 247, 249);

    private static final Color LABEL_COLOR =
            new Color(35, 35, 35);

        private static final Color EDGE_OT =
                new Color(0x8D, 0xC7, 0xF2);

        private static final Color EDGE_IT =
                new Color(0xF2, 0xE5, 0x82);

        private static final Color EDGE_IOC =
                new Color(0xF2, 0x9B, 0x91);

        private static final Color EDGE_UNK =
                new Color(0xC8, 0xCE, 0xD4);

    private static final float[] EDGE_DASH =
            { 6.0f, 4.0f };

    private static final float[] NETWORK_BORDER_DASH =
            { 8.0f, 5.0f };

    private static final float[] ANALYST_BORDER_DASH =
            { 10.0f, 5.0f };

    /*
     * For positive long-valued connection counts, 2 * log10(count) is below
     * 38 pixels. Buckets are 1/8-pixel increments and avoid creating a new
     * BasicStroke for every projected edge during every repaint.
     */
    private static final int STROKE_BUCKET_SCALE = 8;
    private static final int STROKE_BUCKET_COUNT = 512;

    private static final int MAX_NODE_RADIUS = 28;
    private static final int SCREEN_CULL_MARGIN =
            MAX_NODE_RADIUS + 4;

    private static final BasicStroke NODE_OUTLINE_STROKE =
            new BasicStroke(1.2f);

    private static final BasicStroke SELECTED_NODE_OUTLINE_STROKE =
            new BasicStroke(2.6f);

    private static final BasicStroke INNER_OUTLINE_STROKE =
            new BasicStroke(0.8f);

    private static final BasicStroke NETWORK_BORDER_STROKE =
            new BasicStroke(
                    1.35f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    10.0f,
                    NETWORK_BORDER_DASH,
                    0.0f);

    private static final BasicStroke SELECTED_NETWORK_BORDER_STROKE =
            new BasicStroke(2.8f);

    private static final BasicStroke ANALYST_BORDER_STROKE =
            new BasicStroke(
                    2.0f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND,
                    10.0f,
                    ANALYST_BORDER_DASH,
                    0.0f);

    private final BasicStroke[] solidEdgeStrokes =
            new BasicStroke[STROKE_BUCKET_COUNT];

    private final BasicStroke[] dashedEdgeStrokes =
            new BasicStroke[STROKE_BUCKET_COUNT];

    /*
     * Maximum ring width is radius / 3, and radius is capped at 28.
     * Index zero is intentionally unused.
     */
    private final BasicStroke[] ringStrokes =
            new BasicStroke[10];

    private final IntBag visibleNodes =
            new IntBag();

    /*
     * Reused by drawArrow(...). This avoids two int[3] allocations per
     * visual edge per repaint.
     */
    private final int[] arrowX =
            new int[3];

    private final int[] arrowY =
            new int[3];

    public void render(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final NetworkBorderSnapshot borders,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedNetworkBorders,
            final boolean selectedUndeterminedBorder,
            final BitSet selectedVisualNodes,
            final boolean showDevices,
            final boolean showEdges) {

        if (graphics == null
                || projection == null
                || projection.isEmpty()
                || panelWidth <= 0
                || panelHeight <= 0
                || zoom <= 0.0) {

            return;
        }

        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        final float inverseZoom =
                (float) (1.0 / zoom);

        final float worldMargin =
                MAX_NODE_RADIUS * inverseZoom;

        final float worldLeft =
                (float) (-panX * inverseZoom) - worldMargin;

        final float worldTop =
                (float) (-panY * inverseZoom) - worldMargin;

        final float worldRight =
                (float) ((panelWidth - panX) * inverseZoom) + worldMargin;

        final float worldBottom =
                (float) ((panelHeight - panY) * inverseZoom) + worldMargin;

        collectVisibleNodes(
                projection,
                worldLeft,
                worldTop,
                worldRight,
                worldBottom);

        drawNetworkBorders(
                graphics,
                borders,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        drawAnalystBorders(
                graphics,
                projection,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY);

        if (showEdges) {
            drawEdges(
                    graphics,
                    projection,
                    panelWidth,
                    panelHeight,
                    zoom,
                    panX,
                    panY);
        }

        if (showDevices) {
            drawNodes(
                    graphics,
                    projection,
                    panelWidth,
                    panelHeight,
                    zoom,
                    panX,
                    panY,
                    selectedVisualNodes);
        }

        /*
         * Preserve the established display layering: redraw borders after
         * graph data so they remain visible above edges and nodes.
         */
        drawNetworkBorders(
                graphics,
                borders,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        drawBorderLabels(
                graphics,
                borders,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY,
                selectedNetworkBorders,
                selectedUndeterminedBorder);

        drawAnalystBorderLabels(
                graphics,
                projection,
                panelWidth,
                panelHeight,
                zoom,
                panX,
                panY);

        if (showDevices
                && zoom >= 0.40
                && projection.getVisualNodeCount() <= 2_000) {

            drawNodeLabels(
                    graphics,
                    projection,
                    panelWidth,
                    panelHeight,
                    zoom,
                    panX,
                    panY);
        }
    }

    private void collectVisibleNodes(
            final ClusterProjection projection,
            final float left,
            final float top,
            final float right,
            final float bottom) {

        visibleNodes.clear();

        projection.getSpatialIndex().query(
                left,
                top,
                right,
                bottom,
                visibleNodes);
    }

        private void drawEdges(
                final Graphics2D graphics,
                final ClusterProjection projection,
                final int panelWidth,
                final int panelHeight,
                final double zoom,
                final double panX,
                final double panY) {

        for (int edgeId = 0;
                edgeId < projection.getVisualEdgeCount();
                edgeId++) {

                final int sourceId =
                        projection.getEdgeSource(edgeId);

                final int targetId =
                        projection.getEdgeTarget(edgeId);

                final int sourceX =
                        screenX(
                                projection.getX(sourceId),
                                zoom,
                                panX);

                final int sourceY =
                        screenY(
                                projection.getY(sourceId),
                                zoom,
                                panY);

                final int targetX =
                        screenX(
                                projection.getX(targetId),
                                zoom,
                                panX);

                final int targetY =
                        screenY(
                                projection.getY(targetId),
                                zoom,
                                panY);

                if (!lineBoundsIntersectsViewport(
                        sourceX,
                        sourceY,
                        targetX,
                        targetY,
                        panelWidth,
                        panelHeight)) {

                continue;
                }

                final float width =
                        edgeWidth(
                                projection.getEdgeConnectionCount(edgeId));

                final boolean successful =
                        projection.getEdgeSuccessfulCount(edgeId) > 0L;

                graphics.setColor(
                        edgeColor(
                                projection.getEdgeEndpointTagMask(edgeId)));

                graphics.setStroke(
                        edgeStroke(
                                width,
                                successful));

                drawDirectedEdge(
                        graphics,
                        sourceX,
                        sourceY,
                        targetX,
                        targetY,
                        radius(
                                zoom,
                                projection.getMemberCount(sourceId)),
                        radius(
                                zoom,
                                projection.getMemberCount(targetId)),
                        width);
        }
        }

    private void drawNodes(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selectedVisualNodes) {

        for (int index = 0;
                index < visibleNodes.size();
                index++) {

            final int visualId =
                    visibleNodes.get(index);

            final int centerX =
                    screenX(
                            projection.getX(visualId),
                            zoom,
                            panX);

            final int centerY =
                    screenY(
                            projection.getY(visualId),
                            zoom,
                            panY);

            final int radius =
                    radius(
                            zoom,
                            projection.getMemberCount(visualId));

            if (!circleIntersectsViewport(
                    centerX,
                    centerY,
                    radius,
                    panelWidth,
                    panelHeight)) {

                continue;
            }

            final int innerRadius =
                    Math.max(2, radius - 4);

            drawOuterRing(
                    graphics,
                    projection,
                    visualId,
                    centerX,
                    centerY,
                    radius);

            drawInner(
                    graphics,
                    projection,
                    visualId,
                    centerX,
                    centerY,
                    innerRadius);

            final boolean selected =
                    selectedVisualNodes != null
                            && selectedVisualNodes.get(visualId);

            graphics.setColor(
                    selected
                            ? SELECTED
                            : OUTLINE);

            graphics.setStroke(
                    selected
                            ? SELECTED_NODE_OUTLINE_STROKE
                            : NODE_OUTLINE_STROKE);

            graphics.drawOval(
                    centerX - radius,
                    centerY - radius,
                    radius * 2,
                    radius * 2);
        }
    }

    private void drawInner(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int visualId,
            final int centerX,
            final int centerY,
            final int radius) {

        int total = 0;

        for (int tag = 0;
                tag < 4;
                tag++) {

            total += projection.getInnerTagCount(
                    visualId,
                    tag);
        }

        if (total <= 0) {
            graphics.setColor(UNK);
            graphics.fillOval(
                    centerX - radius,
                    centerY - radius,
                    radius * 2,
                    radius * 2);
            return;
        }

        int start = 90;
        int used = 0;

        for (int tag = 0;
                tag < 4;
                tag++) {

            final int count =
                    projection.getInnerTagCount(
                            visualId,
                            tag);

            if (count <= 0) {
                continue;
            }

            int extent =
                    (int) Math.round(
                            360.0 * count / total);

            if (used + extent > 360) {
                extent = 360 - used;
            }

            graphics.setColor(
                    color(tag));

            graphics.fillArc(
                    centerX - radius,
                    centerY - radius,
                    radius * 2,
                    radius * 2,
                    start,
                    -extent);

            start -= extent;
            used += extent;
        }

        graphics.setColor(OUTLINE);
        graphics.setStroke(INNER_OUTLINE_STROKE);
        graphics.drawOval(
                centerX - radius,
                centerY - radius,
                radius * 2,
                radius * 2);
    }

    private void drawOuterRing(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int visualId,
            final int centerX,
            final int centerY,
            final int radius) {

        graphics.setColor(RING_BACKGROUND);

        graphics.fillOval(
                centerX - radius,
                centerY - radius,
                radius * 2,
                radius * 2);

        long total = 0L;

        for (int tag = 0;
                tag < 4;
                tag++) {

            total += projection.getOuterTagWeight(
                    visualId,
                    tag);
        }

        if (total <= 0L) {
            return;
        }

        final int ringWidth =
                Math.max(2, radius / 3);

        graphics.setStroke(
                ringStroke(ringWidth));

        int start = 90;
        int used = 0;

        for (int tag = 0;
                tag < 4;
                tag++) {

            final long weight =
                    projection.getOuterTagWeight(
                            visualId,
                            tag);

            if (weight <= 0L) {
                continue;
            }

            int extent =
                    (int) Math.round(
                            360.0 * weight / total);

            if (used + extent > 360) {
                extent = 360 - used;
            }

            graphics.setColor(
                    color(tag));

            graphics.drawArc(
                    centerX - radius + ringWidth / 2,
                    centerY - radius + ringWidth / 2,
                    radius * 2 - ringWidth,
                    radius * 2 - ringWidth,
                    start,
                    -extent);

            start -= extent;
            used += extent;
        }
    }

    private void drawNetworkBorders(
            final Graphics2D graphics,
            final NetworkBorderSnapshot snapshot,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selected,
            final boolean selectedUndetermined) {

        if (snapshot == null) {
            return;
        }

        for (int index = 0;
                index < snapshot.getBorderCount();
                index++) {

            final NetworkBorder border =
                    snapshot.getBorder(index);

            final int x =
                    screenX(
                            border.getMinX(),
                            zoom,
                            panX);

            final int y =
                    screenY(
                            border.getMinY(),
                            zoom,
                            panY);

            final int width =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (border.getMaxX()
                                            - border.getMinX()) * zoom));

            final int height =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (border.getMaxY()
                                            - border.getMinY()) * zoom));

            if (!rectIntersectsViewport(
                    x,
                    y,
                    width,
                    height,
                    panelWidth,
                    panelHeight)) {

                continue;
            }

            final boolean isSelected =
                    border.isUndetermined()
                            ? selectedUndetermined
                            : selected != null
                                    && selected.get(
                                            border.getNetworkId());

            graphics.setColor(
                    isSelected
                            ? SELECTED
                            : NETWORK_BORDER);

            graphics.setStroke(
                    isSelected
                            ? SELECTED_NETWORK_BORDER_STROKE
                            : NETWORK_BORDER_STROKE);

            graphics.drawRect(
                    x,
                    y,
                    width,
                    height);
        }
    }

    private void drawAnalystBorders(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY) {

        graphics.setColor(ANALYST_BORDER);
        graphics.setStroke(ANALYST_BORDER_STROKE);

        for (int borderId = 0;
                borderId < projection.getAnalystBorderCount();
                borderId++) {

            if (projection.getAnalystBorderMemberCount(borderId) <= 0) {
                continue;
            }

            final int x =
                    screenX(
                            projection.getAnalystBorderMinX(borderId),
                            zoom,
                            panX);

            final int y =
                    screenY(
                            projection.getAnalystBorderMinY(borderId),
                            zoom,
                            panY);

            final int width =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (projection.getAnalystBorderMaxX(borderId)
                                            - projection.getAnalystBorderMinX(borderId))
                                            * zoom));

            final int height =
                    Math.max(
                            1,
                            (int) Math.round(
                                    (projection.getAnalystBorderMaxY(borderId)
                                            - projection.getAnalystBorderMinY(borderId))
                                            * zoom));

            if (!rectIntersectsViewport(
                    x,
                    y,
                    width,
                    height,
                    panelWidth,
                    panelHeight)) {

                continue;
            }

            graphics.drawRoundRect(
                    x,
                    y,
                    width,
                    height,
                    22,
                    22);
        }
    }

    private void drawBorderLabels(
            final Graphics2D graphics,
            final NetworkBorderSnapshot snapshot,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY,
            final BitSet selected,
            final boolean selectedUndetermined) {

        if (snapshot == null || zoom < 0.40) {
            return;
        }

        final Font previousFont =
                graphics.getFont();

        graphics.setFont(
                previousFont.deriveFont(Font.BOLD));

        try {
            for (int index = 0;
                    index < snapshot.getBorderCount();
                    index++) {

                final NetworkBorder border =
                        snapshot.getBorder(index);

                if (border.getLabel().isEmpty()) {
                    continue;
                }

                final int x =
                        screenX(
                                border.getMinX(),
                                zoom,
                                panX);

                final int y =
                        screenY(
                                border.getMinY(),
                                zoom,
                                panY);

                if (x < -SCREEN_CULL_MARGIN
                        || x > panelWidth + SCREEN_CULL_MARGIN
                        || y < -SCREEN_CULL_MARGIN
                        || y > panelHeight + SCREEN_CULL_MARGIN) {

                    continue;
                }

                final boolean isSelected =
                        border.isUndetermined()
                                ? selectedUndetermined
                                : selected != null
                                        && selected.get(
                                                border.getNetworkId());

                graphics.setColor(
                        isSelected
                                ? SELECTED
                                : NETWORK_BORDER);

                graphics.drawString(
                        border.getLabel(),
                        x + 6,
                        y - 6);
            }
        } finally {
            graphics.setFont(previousFont);
        }
    }

    private void drawAnalystBorderLabels(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY) {

        if (zoom < 0.40) {
            return;
        }

        final Font previousFont =
                graphics.getFont();

        graphics.setFont(
                previousFont.deriveFont(Font.BOLD));

        graphics.setColor(ANALYST_BORDER);

        try {
            for (int borderId = 0;
                    borderId < projection.getAnalystBorderCount();
                    borderId++) {

                if (projection.getAnalystBorderMemberCount(borderId) <= 0) {
                    continue;
                }

                final int x =
                        screenX(
                                projection.getAnalystBorderMinX(borderId),
                                zoom,
                                panX);

                final int y =
                        screenY(
                                projection.getAnalystBorderMinY(borderId),
                                zoom,
                                panY);

                if (x < -SCREEN_CULL_MARGIN
                        || x > panelWidth + SCREEN_CULL_MARGIN
                        || y < -SCREEN_CULL_MARGIN
                        || y > panelHeight + SCREEN_CULL_MARGIN) {

                    continue;
                }

                graphics.drawString(
                        projection.getAnalystBorderLabel(borderId),
                        x + 8,
                        y - 7);
            }
        } finally {
            graphics.setFont(previousFont);
        }
    }

    private void drawNodeLabels(
            final Graphics2D graphics,
            final ClusterProjection projection,
            final int panelWidth,
            final int panelHeight,
            final double zoom,
            final double panX,
            final double panY) {

        graphics.setColor(LABEL_COLOR);

        for (int index = 0;
                index < visibleNodes.size();
                index++) {

            final int visualId =
                    visibleNodes.get(index);

            final int centerX =
                    screenX(
                            projection.getX(visualId),
                            zoom,
                            panX);

            final int centerY =
                    screenY(
                            projection.getY(visualId),
                            zoom,
                            panY);

            final int radius =
                    radius(
                            zoom,
                            projection.getMemberCount(visualId));

            if (!circleIntersectsViewport(
                    centerX,
                    centerY,
                    radius,
                    panelWidth,
                    panelHeight)) {

                continue;
            }

            graphics.drawString(
                    projection.getLabel(visualId),
                    centerX + radius + 4,
                    centerY - 4);
        }
    }

    private BasicStroke edgeStroke(
            final float requestedWidth,
            final boolean successful) {

        int bucket =
                Math.round(
                        requestedWidth * STROKE_BUCKET_SCALE);

        if (bucket < 1) {
            bucket = 1;
        } else if (bucket >= STROKE_BUCKET_COUNT) {
            bucket = STROKE_BUCKET_COUNT - 1;
        }

        final BasicStroke[] strokes =
                successful
                        ? solidEdgeStrokes
                        : dashedEdgeStrokes;

        BasicStroke stroke =
                strokes[bucket];

        if (stroke == null) {
            final float width =
                    bucket / (float) STROKE_BUCKET_SCALE;

            stroke =
                    successful
                            ? new BasicStroke(
                                    width,
                                    BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND)
                            : new BasicStroke(
                                    width,
                                    BasicStroke.CAP_ROUND,
                                    BasicStroke.JOIN_ROUND,
                                    10.0f,
                                    EDGE_DASH,
                                    0.0f);

            strokes[bucket] = stroke;
        }

        return stroke;
    }

    private BasicStroke ringStroke(
            final int requestedWidth) {

        final int width =
                Math.max(
                        1,
                        Math.min(
                                requestedWidth,
                                ringStrokes.length - 1));

        BasicStroke stroke =
                ringStrokes[width];

        if (stroke == null) {
            stroke = new BasicStroke(
                    width,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND);

            ringStrokes[width] = stroke;
        }

        return stroke;
    }

        /**
         * Draws an edge that stops at the source and target node boundaries.
         *
         * The arrowhead tip is placed outside the target node rather than at its
         * center. This prevents the target node paint pass from covering the arrow
         * and makes directionality visible at all normal zoom levels.
         */
        private void drawDirectedEdge(
                final Graphics2D graphics,
                final int sourceX,
                final int sourceY,
                final int targetX,
                final int targetY,
                final int sourceRadius,
                final int targetRadius,
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

        final double unitX =
                deltaX / length;

        final double unitY =
                deltaY / length;

        final double arrowLength =
                Math.max(
                        8.0,
                        width * 3.5);

        final double startOffset =
                sourceRadius
                        + width * 0.5
                        + 1.0;

        /*
        * Keep the arrowhead completely outside the target glyph. The target node
        * is rendered after its edges, so an arrowhead placed at target center
        * would otherwise be hidden.
        */
        final double tipOffset =
                targetRadius + 2.0;

        final double baseOffset =
                tipOffset + arrowLength;

        final double lineStartX =
                sourceX + unitX * startOffset;

        final double lineStartY =
                sourceY + unitY * startOffset;

        final double arrowTipX =
                targetX - unitX * tipOffset;

        final double arrowTipY =
                targetY - unitY * tipOffset;

        final double arrowBaseX =
                targetX - unitX * baseOffset;

        final double arrowBaseY =
                targetY - unitY * baseOffset;

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
                Math.max(
                        4.0,
                        arrowLength * 0.48);

        arrowX[0] =
                (int) Math.round(arrowTipX);

        arrowY[0] =
                (int) Math.round(arrowTipY);

        arrowX[1] =
                (int) Math.round(
                        arrowBaseX
                                + perpendicularX
                                        * halfWidth);

        arrowY[1] =
                (int) Math.round(
                        arrowBaseY
                                + perpendicularY
                                        * halfWidth);

        arrowX[2] =
                (int) Math.round(
                        arrowBaseX
                                - perpendicularX
                                        * halfWidth);

        arrowY[2] =
                (int) Math.round(
                        arrowBaseY
                                - perpendicularY
                                        * halfWidth);

        graphics.fillPolygon(
                arrowX,
                arrowY,
                arrowX.length);
        }

        private static Color edgeColor(
                final byte endpointTagMask) {

        final byte tags =
                DeviceTypes.normalizeMask(endpointTagMask);

        if (DeviceTypes.has(tags, DeviceTypes.IOC)) {
                return EDGE_IOC;
        }

        if (DeviceTypes.has(tags, DeviceTypes.OT)) {
                return EDGE_OT;
        }

        if (DeviceTypes.has(tags, DeviceTypes.IT)) {
                return EDGE_IT;
        }

        return EDGE_UNK;
        }

    private static boolean lineBoundsIntersectsViewport(
            final int sourceX,
            final int sourceY,
            final int targetX,
            final int targetY,
            final int panelWidth,
            final int panelHeight) {

        final int minX =
                Math.min(sourceX, targetX);

        final int maxX =
                Math.max(sourceX, targetX);

        final int minY =
                Math.min(sourceY, targetY);

        final int maxY =
                Math.max(sourceY, targetY);

        return maxX >= -SCREEN_CULL_MARGIN
                && minX <= panelWidth + SCREEN_CULL_MARGIN
                && maxY >= -SCREEN_CULL_MARGIN
                && minY <= panelHeight + SCREEN_CULL_MARGIN;
    }

    private static boolean circleIntersectsViewport(
            final int centerX,
            final int centerY,
            final int radius,
            final int panelWidth,
            final int panelHeight) {

        return centerX + radius >= -SCREEN_CULL_MARGIN
                && centerX - radius <= panelWidth + SCREEN_CULL_MARGIN
                && centerY + radius >= -SCREEN_CULL_MARGIN
                && centerY - radius <= panelHeight + SCREEN_CULL_MARGIN;
    }

    private static boolean rectIntersectsViewport(
            final int x,
            final int y,
            final int width,
            final int height,
            final int panelWidth,
            final int panelHeight) {

        return x + width >= -SCREEN_CULL_MARGIN
                && x <= panelWidth + SCREEN_CULL_MARGIN
                && y + height >= -SCREEN_CULL_MARGIN
                && y <= panelHeight + SCREEN_CULL_MARGIN;
    }

    private static float edgeWidth(
            final long connectionCount) {

        return (float) Math.max(
                0.75,
                2.0 * Math.log10(
                        Math.max(
                                1L,
                                connectionCount)));
    }

    private static Color color(
            final int tag) {

        switch (tag) {
            case 0:
                return OT;

            case 1:
                return IT;

            case 2:
                return IOC;

            default:
                return UNK;
        }
    }

    private static int radius(
            final double zoom,
            final int members) {

        final int base =
                zoom < 0.25
                        ? 4
                        : zoom < 0.75
                                ? 8
                                : 13;

        return Math.min(
                MAX_NODE_RADIUS,
                base + (int) Math.round(
                        Math.log10(
                                Math.max(
                                        1,
                                        members)) * 3.0));
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