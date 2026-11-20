package netgraph.interaction;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.Timer;

import netgraph.cluster.PartialUnclusterDefinition;
import netgraph.borders.NetworkBorder;
import netgraph.borders.NetworkBorderSnapshot;
import netgraph.cluster.AnalystClusterDefinition;
import netgraph.cluster.ClusterProjection;
import netgraph.cluster.ClusterProjectionCoordinator;
import netgraph.cluster.ClusterRenderer;
import netgraph.cluster.ClusterViewState;
import netgraph.csv.GraphCsvExporter;
import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;
import netgraph.layout.LayoutCoordinator;
import netgraph.layout.LayoutState;
import netgraph.metadata.GraphMetadata;
import netgraph.rendering.GraphRenderer;
import netgraph.util.CidrUtil;
import netgraph.util.DeviceTypes;
import netgraph.util.IntBag;
import netgraph.util.NetworkMembershipUtil;
import netgraph.util.NetworkTrafficSummary;

/**
 * Swing graph-interaction surface.
 *
 * Owns input handling, graph selection state, viewport pan/zoom state,
 * hit testing, node dragging, border snapshots, and repaint scheduling.
 */
@SuppressWarnings("serial")
public final class GraphPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final double MIN_ZOOM = 0.08;
    private static final double MAX_ZOOM = 8.0;
    private static final int MARQUEE_DRAG_THRESHOLD = 4;

    private static final double FIT_LEFT_MARGIN = 48.0;
    private static final double FIT_RIGHT_MARGIN = 320.0;
    private static final double FIT_TOP_MARGIN = 52.0;
    private static final double FIT_BOTTOM_MARGIN = 48.0;
    private static final int CLUSTER_PROJECTION_DRAG_DEBOUNCE_MS = 100;

    private final GraphRenderer renderer =
            new GraphRenderer();

    private final ClusterRenderer clusterRenderer =
            new ClusterRenderer();

    private final ClusterRenderer exportClusterRenderer =
            new ClusterRenderer();

    private final ClusterProjectionCoordinator clusterCoordinator =
            new ClusterProjectionCoordinator();

    private final Timer clusterProjectionDebounceTimer =
            new Timer(
                        CLUSTER_PROJECTION_DRAG_DEBOUNCE_MS,
                        event -> requestClusterProjection());

    private final ClusterViewState clusterViewState =
            new ClusterViewState();

    private final BitSet selectedVisualNodes =
            new BitSet();

    private final BitSet draggedVisualRawNodes =
            new BitSet();

    private boolean draggingVisualNodes;

    private volatile ClusterProjection clusterProjection =
            ClusterProjection.empty();

    private final LayoutCoordinator layoutCoordinator =
            new LayoutCoordinator();

        private final BitSet selectedNodes =
                new BitSet();

        private final BitSet selectedEdges =
                new BitSet();

        private final BitSet selectedNetworkBorders =
                new BitSet();

        private final BitSet draggedBorderNodes =
                new BitSet();

        private boolean selectedUndeterminedBorder;

    private final IntBag hitCandidates =
            new IntBag();

    private volatile NetworkBorderSnapshot borderSnapshot =
            NetworkBorderSnapshot.empty();

    private GraphStore graphStore =
            GraphStore.empty();

    private volatile GraphDisplayFilter displayFilter =
            GraphDisplayFilter.all();

    private double zoom = 1.0;
    private double panX;
    private double panY;

        private boolean panning;
        private boolean draggingNodes;
        private boolean draggingBorders;
        private boolean clusterGeometryDirty;
        private Point lastMousePoint;
        
    /*
     * Shift+left-drag marquee state. Coordinates are screen coordinates so
     * the visual selection rectangle remains stable while displayed.
     */
    private Point marqueeStart;
    private Point marqueeEnd;
    private boolean marqueeSelecting;

    public GraphPanel() {
        setBackground(Color.WHITE);
        setOpaque(true);

        clusterProjectionDebounceTimer.setRepeats(false);

        installMouseHandlers();
    }

    public GraphStore getGraphStore() {
        return graphStore;
    }

    public void setDisplayFilter(
            final GraphDisplayFilter filter) {

        displayFilter = filter == null
                ? GraphDisplayFilter.all()
                : filter;

        refreshClusterProjectionForViewChange();
    }

    public void setGraph(final GraphStore store) {
        setGraph(store, false);
    }

    /**
     * Used only by an in-place NGC append. It retains the analyst's current
     * hierarchy expansion and analyst-defined clusters while invalidating all
     * cached geometry for the new GraphStore instance.
     */
    public void setGraphPreservingClusterState(final GraphStore store) {
        setGraph(store, true);
    }

    private void setGraph(final GraphStore store,
            final boolean preserveClusterViewState) {
        if (!preserveClusterViewState) {
            clusterViewState.resetToTopLevelClusters();
            clusterViewState.clearAnalystClusters();
        }

        graphStore = store == null
                ? GraphStore.empty()
                : store;

        clearSelection();
        draggedBorderNodes.clear();

        panning = false;
        draggingNodes = false;
        draggingBorders = false;
        clearMarqueeSelection();

        borderSnapshot = NetworkBorderSnapshot.empty();

        zoom = 1.0;
        panX = 0.0;
        panY = 0.0;

        selectedVisualNodes.clear();
        clusterProjection = ClusterProjection.empty();
        clusterCoordinator.invalidate();
        clusterProjectionDebounceTimer.stop();
        clusterGeometryDirty = false;
        rebuildBorderSnapshot();
        requestClusterProjection();
        repaint();
    }

    public void shutdown() {
        clusterProjectionDebounceTimer.stop();
        clusterCoordinator.shutdown();
        layoutCoordinator.shutdown();
    }

    public void relayout(final boolean refineSprings) {
        final GraphStore store = graphStore;

        if (store == null || store.isEmpty()) {
            repaint();
            return;
        }

        layoutCoordinator.requestLayout(
                store,
                refineSprings,
                new Runnable() {
                    @Override
                    public void run() {
                        rebuildBorderSnapshot();
                        clusterCoordinator.invalidate();
                        requestClusterProjection();
                        fitToWindow();
                        repaint();
                    }
                });
    }

        public void fitToWindow() {
        final GraphStore store = graphStore;

        if (store == null || store.isEmpty()) {
                zoom = 1.0;
                panX = 0.0;
                panY = 0.0;
                repaint();
                return;
        }

        rebuildBorderSnapshot();

        final NetworkBorderSnapshot snapshot =
                borderSnapshot;

        final LayoutState state =
                store.getLayoutState();

        synchronized (state.renderLock()) {
                final float[] x =
                        state.visibleXUnsafe();

                final float[] y =
                        state.visibleYUnsafe();

                float minX = Float.MAX_VALUE;
                float minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE;
                float maxY = -Float.MAX_VALUE;

                for (int nodeId = 0;
                        nodeId < store.getNodeCount();
                        nodeId++) {

                minX = Math.min(minX, x[nodeId]);
                minY = Math.min(minY, y[nodeId]);
                maxX = Math.max(maxX, x[nodeId]);
                maxY = Math.max(maxY, y[nodeId]);
                }

                final NetworkBorder[] borders =
                        snapshot.getBordersUnsafe();

                for (int index = 0;
                        index < borders.length;
                        index++) {

                final NetworkBorder border =
                        borders[index];

                minX = Math.min(
                        minX,
                        border.getMinX());

                minY = Math.min(
                        minY,
                        border.getMinY());

                maxX = Math.max(
                        maxX,
                        border.getMaxX());

                maxY = Math.max(
                        maxY,
                        border.getMaxY());
                }

                final double graphWidth =
                        Math.max(
                                1.0,
                                maxX - minX);

                final double graphHeight =
                        Math.max(
                                1.0,
                                maxY - minY);

                final double availableWidth =
                        Math.max(
                                100.0,
                                getWidth()
                                        - FIT_LEFT_MARGIN
                                        - FIT_RIGHT_MARGIN);

                final double availableHeight =
                        Math.max(
                                100.0,
                                getHeight()
                                        - FIT_TOP_MARGIN
                                        - FIT_BOTTOM_MARGIN);

                zoom = Math.min(
                        availableWidth / graphWidth,
                        availableHeight / graphHeight);

                zoom = Math.max(
                        MIN_ZOOM,
                        Math.min(MAX_ZOOM, zoom));

                panX = FIT_LEFT_MARGIN
                        + (availableWidth
                                - graphWidth * zoom) * 0.5
                        - minX * zoom;

                panY = FIT_TOP_MARGIN
                        + (availableHeight
                                - graphHeight * zoom) * 0.5
                        - minY * zoom;
        }

        repaint();
        }

    public void exportGraphCsv(final File target)
            throws IOException {

        if (target == null) {
            throw new IllegalArgumentException(
                    "CSV target file is required.");
        }

        GraphCsvExporter.export(
                target.toPath(),
                graphStore);
    }

    public void exportPng(final File target)
            throws IOException {

        if (target == null) {
            throw new IllegalArgumentException(
                    "PNG target file is required.");
        }

        final int width =
                Math.max(1, getWidth());

        final int height =
                Math.max(1, getHeight());

        final GraphStore storeSnapshot =
                graphStore;

        final ClusterProjection projectionSnapshot =
                clusterProjection;

        final NetworkBorderSnapshot borderSnapshotForExport =
                borderSnapshot;

        final GraphDisplayFilter filterSnapshot =
                displayFilter;

        final double zoomSnapshot =
                zoom;

        final double panXSnapshot =
                panX;

        final double panYSnapshot =
                panY;

        final BufferedImage image =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_INT_ARGB);

        final Graphics2D graphics =
                image.createGraphics();

        try {
                graphics.setColor(getBackground());
                graphics.fillRect(
                        0,
                        0,
                        width,
                        height);

                if (storeSnapshot != null
                        && !storeSnapshot.isEmpty()) {

                if (projectionSnapshot != null
                        && !projectionSnapshot.isEmpty()) {

                        /*
                        * ClusterProjection and NetworkBorderSnapshot are immutable
                        * render snapshots. No LayoutState lock is needed here.
                        */
                        clusterRenderer.render(
                                graphics,
                                projectionSnapshot,
                                borderSnapshotForExport,
                                width,
                                height,
                                zoomSnapshot,
                                panXSnapshot,
                                panYSnapshot,
                                selectedNetworkBorders,
                                selectedUndeterminedBorder,
                                selectedVisualNodes,
                                filterSnapshot.isShowDevices(),
                                filterSnapshot.isShowEdges());

                } else {
                        /*
                        * Raw rendering directly reads mutable LayoutState coordinate
                        * arrays, so this branch must remain protected.
                        */
                        final LayoutState state =
                                storeSnapshot.getLayoutState();

                        synchronized (state.renderLock()) {
                        renderer.render(
                                graphics,
                                storeSnapshot,
                                state.visibleXUnsafe(),
                                state.visibleYUnsafe(),
                                borderSnapshotForExport,
                                width,
                                height,
                                zoomSnapshot,
                                panXSnapshot,
                                panYSnapshot,
                                selectedNodes,
                                selectedEdges,
                                selectedNetworkBorders,
                                selectedUndeterminedBorder,
                                filterSnapshot);
                        }
                }
                }

                ImageIO.write(
                        image,
                        "png",
                        target);

        } finally {
                graphics.dispose();
        }
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);

        final GraphStore store =
                graphStore;

        if (store == null || store.isEmpty()) {
                return;
        }

        /*
         * Capture immutable/EDT-owned state once for a consistent frame.
         */
        final ClusterProjection projection =
                clusterProjection;

        final NetworkBorderSnapshot borders =
                borderSnapshot;

        final GraphDisplayFilter filter =
                displayFilter;

        final double currentZoom =
                zoom;

        final double currentPanX =
                panX;

        final double currentPanY =
                panY;

        final Graphics2D graphics2D =
                (Graphics2D) graphics.create();

        try {
                if (projection != null
                        && !projection.isEmpty()) {

                /*
                * IMPORTANT:
                * ClusterRenderer reads only immutable ClusterProjection data and
                * immutable NetworkBorderSnapshot data. It does not read mutable
                * LayoutState visible coordinate arrays.
                *
                * Therefore, do not acquire state.renderLock() here.
                */
                clusterRenderer.render(
                        graphics2D,
                        projection,
                        borders,
                        getWidth(),
                        getHeight(),
                        currentZoom,
                        currentPanX,
                        currentPanY,
                        selectedNetworkBorders,
                        selectedUndeterminedBorder,
                        selectedVisualNodes,
                        filter.isShowDevices(),
                        filter.isShowEdges());

                } else {
                /*
                * Raw GraphRenderer receives mutable visibleX/visibleY arrays.
                * This branch must remain synchronized on renderLock().
                */
                final LayoutState state =
                        store.getLayoutState();

                synchronized (state.renderLock()) {
                        renderer.render(
                                graphics2D,
                                store,
                                state.visibleXUnsafe(),
                                state.visibleYUnsafe(),
                                borders,
                                getWidth(),
                                getHeight(),
                                currentZoom,
                                currentPanX,
                                currentPanY,
                                selectedNodes,
                                selectedEdges,
                                selectedNetworkBorders,
                                selectedUndeterminedBorder,
                                filter);
                }
                }

                drawMarqueeSelection(graphics2D);

        } finally {
                graphics2D.dispose();
        }
        }

        private void showUndeterminedClusterMenu(
                final ClusterProjection projection,
                final int visualId,
                final MouseEvent event) {

        final boolean hasMembers =
                projection.getMemberCount(visualId) > 0;

        final JPopupMenu menu =
                new JPopupMenu();

        final JMenuItem details =
                new JMenuItem("Cluster Details\u2026");

        details.addActionListener(e ->
                showClusterDetails(
                        projection,
                        visualId));

        menu.add(details);
        menu.addSeparator();

        final JMenuItem one =
                new JMenuItem("Uncluster One Level");

        one.setEnabled(hasMembers);

        one.addActionListener(e -> {
                clusterViewState.unclusterUndetermined();
                refreshClusterProjectionForViewChange();
        });

        menu.add(one);

        final JMenuItem partial =
                new JMenuItem("Partial Unclustering\u2026");

        partial.setEnabled(hasMembers);

        partial.addActionListener(e ->
                showPartialUnclusterDialog(
                        -1,
                        true));

        menu.add(partial);

        final JMenuItem reset =
                new JMenuItem("Reset to Top-Level Clusters");

        reset.setEnabled(
                clusterViewState.hasAnyClusterDrillDown());

        reset.addActionListener(e -> {
                clusterViewState.resetToTopLevelClusters();
                refreshClusterProjectionForViewChange();
        });

        menu.add(reset);
        menu.addSeparator();

        final JMenuItem fit =
                new JMenuItem("Fit Cluster to Window");

        fit.addActionListener(e ->
                fitVisualNode(
                        projection,
                        visualId));

        menu.add(fit);

        menu.show(
                this,
                event.getX(),
                event.getY());
        }

        private void showPartialClusterMenu(
                final ClusterProjection projection,
                final int visualId,
                final MouseEvent event) {

        final int networkId =
                projection.getNetworkId(visualId);

        final boolean undetermined =
                networkId < 0;

        final int partialDefinitionId =
                projection.getPartialUnclusterId(visualId);

        final int partialGroupId =
                projection.getPartialGroupId(visualId);

        final ClusterViewState.Snapshot snapshot =
                clusterViewState.snapshot();

        final PartialUnclusterDefinition[] definitions =
                snapshot.getPartialUnclustersUnsafe();

        final boolean validDefinition =
                partialDefinitionId >= 0
                        && partialDefinitionId < definitions.length;

        final PartialUnclusterDefinition definition =
                validDefinition
                        ? definitions[partialDefinitionId]
                        : null;

        final boolean canUnclusterGroup =
                validDefinition
                        && projection.getMemberCount(visualId) > 1
                        && !snapshot.isPartialGroupExpanded(
                                definition,
                                partialGroupId);

        final JPopupMenu menu =
                new JPopupMenu();

        final JMenuItem details =
                new JMenuItem("Cluster Details\u2026");

        details.addActionListener(e ->
                showClusterDetails(
                        projection,
                        visualId));

        menu.add(details);
        menu.addSeparator();

        final JMenuItem unclusterGroup =
                new JMenuItem(
                        validDefinition
                                ? "Uncluster "
                                        + definition.groupDescription(
                                                partialGroupId)
                                : "Uncluster Partial Group");

        unclusterGroup.setEnabled(canUnclusterGroup);

        unclusterGroup.addActionListener(e -> {
                if (!validDefinition) {
                return;
                }

                clusterViewState.unclusterPartialGroup(
                        definition,
                        partialGroupId);

                refreshClusterProjectionForViewChange();
        });

        menu.add(unclusterGroup);

        final JMenuItem replace =
                new JMenuItem(
                        "Change Partial Unclustering\u2026");

        replace.setEnabled(validDefinition);

        replace.addActionListener(e ->
                showPartialUnclusterDialog(
                        networkId,
                        undetermined));

        menu.add(replace);

        final JMenuItem clear =
                new JMenuItem(
                        "Clear Partial Unclustering");

        clear.setEnabled(validDefinition);

        clear.addActionListener(e -> {
                clusterViewState.clearPartialUnclustering(
                        undetermined,
                        networkId,
                        graphStore.getGraphMetadata()
                                .getNetworkTable());

                refreshClusterProjectionForViewChange();
        });

        menu.add(clear);
        menu.addSeparator();

        final JMenuItem fit =
                new JMenuItem("Fit Cluster to Window");

        fit.addActionListener(e ->
                fitVisualNode(
                        projection,
                        visualId));

        menu.add(fit);

        menu.show(
                this,
                event.getX(),
                event.getY());
        }

        private void showPartialUnclusterDialog(
                final int networkId,
                final boolean undetermined) {

        final JCheckBox ot =
                new JCheckBox("OT");

        final JCheckBox it =
                new JCheckBox("IT");

        final JCheckBox ioc =
                new JCheckBox("IOC");

        final JCheckBox unk =
                new JCheckBox("UNK");

        final JPanel deviceTypes =
                new JPanel(
                        new GridLayout(
                                0,
                                4,
                                8,
                                4));

        deviceTypes.add(ot);
        deviceTypes.add(it);
        deviceTypes.add(ioc);
        deviceTypes.add(unk);

        final JPanel typePanel =
                new JPanel(
                        new BorderLayout(
                                0,
                                6));

        typePanel.add(
                new JLabel(
                        "Create a separate visual cluster for each selected "
                                + "effective type combination. All nonmatching "
                                + "devices remain in an Other Device Types cluster."),
                BorderLayout.NORTH);

        typePanel.add(
                deviceTypes,
                BorderLayout.CENTER);

        final JTextField netblockAddress =
                new JTextField(32);

        final JTextField netblockCidr =
                new JTextField(8);

        final JPanel netblockFields =
                new JPanel(
                        new GridLayout(
                                0,
                                2,
                                8,
                                6));

        netblockFields.add(
                new JLabel("IP Address:"));

        netblockFields.add(netblockAddress);

        netblockFields.add(
                new JLabel("CIDR Prefix:"));

        netblockFields.add(netblockCidr);

        final JPanel netblockPanel =
                new JPanel(
                        new BorderLayout(
                                0,
                                6));

        netblockPanel.add(
                new JLabel(
                        "IPv4 or IPv6. Example: 10.20.0.0 + 16, "
                                + "or 2001:db8:: + 32. "
                                + "The address field may also contain "
                                + "a complete CIDR when the prefix field is blank."),
                BorderLayout.NORTH);

        netblockPanel.add(
                netblockFields,
                BorderLayout.CENTER);

        final JTextField connections =
                new JTextField(42);

        final JPanel connectionPanel =
                new JPanel(
                        new BorderLayout(
                                0,
                                6));

        connectionPanel.add(
                new JLabel(
                        "Enter one IP address or a comma-separated IP list. "
                                + "Devices in this cluster with traffic to or "
                                + "from a specified address become a separate "
                                + "cluster."),
                BorderLayout.NORTH);

        connectionPanel.add(
                connections,
                BorderLayout.CENTER);

        final JTabbedPane tabs =
                new JTabbedPane();

        tabs.addTab(
                "By Device Type",
                typePanel);

        tabs.addTab(
                "By Netblock",
                netblockPanel);

        tabs.addTab(
                "By Connection(s)",
                connectionPanel);

        final String scope =
                undetermined
                        ? "Undetermined Network"
                        : graphStore.getGraphMetadata()
                                .getNetworkTable()
                                .getCidr(networkId);

        final int choice =
                JOptionPane.showConfirmDialog(
                        this,
                        tabs,
                        "Partial Unclustering \u2014 " + scope,
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);

        if (choice != JOptionPane.OK_OPTION) {
                return;
        }

        try {
                final GraphMetadata.NetworkTable networks =
                        graphStore.getGraphMetadata()
                                .getNetworkTable();

                final PartialUnclusterDefinition definition;

                if (tabs.getSelectedIndex() == 0) {
                byte selected = 0;

                if (ot.isSelected()) {
                        selected |= DeviceTypes.OT_MASK;
                }

                if (it.isSelected()) {
                        selected |= DeviceTypes.IT_MASK;
                }

                if (ioc.isSelected()) {
                        selected |= DeviceTypes.IOC_MASK;
                }

                if (unk.isSelected()) {
                        selected |= DeviceTypes.UNK_MASK;
                }

                definition =
                        undetermined
                                ? PartialUnclusterDefinition
                                        .forUndeterminedDeviceTypes(
                                                selected)
                                : PartialUnclusterDefinition
                                        .forNetworkDeviceTypes(
                                                networks,
                                                networkId,
                                                selected);

                } else if (tabs.getSelectedIndex() == 1) {
                definition =
                        undetermined
                                ? PartialUnclusterDefinition
                                        .forUndeterminedNetblock(
                                                netblockAddress.getText(),
                                                netblockCidr.getText())
                                : PartialUnclusterDefinition
                                        .forNetworkNetblock(
                                                networks,
                                                networkId,
                                                netblockAddress.getText(),
                                                netblockCidr.getText());

                } else {
                definition =
                        undetermined
                                ? PartialUnclusterDefinition
                                        .forUndeterminedConnections(
                                                connections.getText())
                                : PartialUnclusterDefinition
                                        .forNetworkConnections(
                                                networks,
                                                networkId,
                                                connections.getText());
                }

                clusterViewState.setPartialUnclustering(definition);
                refreshClusterProjectionForViewChange();

        } catch (IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(
                        this,
                        exception.getMessage(),
                        "Partial Unclustering Not Applied",
                        JOptionPane.WARNING_MESSAGE);
        }
        }

        private void installMouseHandlers() {
        addMouseWheelListener(event -> {
                if (graphStore.isEmpty()) {
                return;
                }

                final double oldZoom =
                        zoom;

                final double factor =
                        event.getWheelRotation() < 0
                                ? 1.16
                                : 1.0 / 1.16;

                zoom = Math.max(
                        MIN_ZOOM,
                        Math.min(
                                MAX_ZOOM,
                                zoom * factor));

                final double worldX =
                        (event.getX() - panX)
                                / oldZoom;

                final double worldY =
                        (event.getY() - panY)
                                / oldZoom;

                panX =
                        event.getX()
                                - worldX * zoom;

                panY =
                        event.getY()
                                - worldY * zoom;

                repaint();
        });

        addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(
                        final MouseEvent event) {

                clusterGeometryDirty = false;

                if (graphStore.isEmpty()) {
                        return;
                }

                if (event.isPopupTrigger()) {
                        showContextMenu(event);
                        return;
                }

                if (!SwingUtilities.isLeftMouseButton(event)) {
                        return;
                }

                lastMousePoint =
                        event.getPoint();

                /*
                * Shift+left-drag always starts marquee selection and never moves
                * a node, cluster, border, or viewport.
                */
                if (event.isShiftDown()) {
                        beginMarqueeSelection(
                                event.getPoint());

                        return;
                }

                final boolean multiSelect =
                        event.isControlDown();

                /*
                * Projection nodes are checked first because clustered rendering
                * is normally active. This includes raw devices that happen to be
                * represented by KIND_RAW_NODE in the clustered projection.
                */
                final ClusterProjection projection =
                        clusterProjection;

                final int visualNodeId =
                        findVisualNodeAt(
                                event.getX(),
                                event.getY());

                if (visualNodeId >= 0) {
                        if (multiSelect) {
                        selectedVisualNodes.flip(visualNodeId);
                        stopDragging();
                        panning = false;
                        repaint();
                        return;
                        }

                        if (!selectedVisualNodes.get(visualNodeId)) {
                        clearSelection();
                        selectedVisualNodes.set(visualNodeId);
                        }

                        buildDraggedVisualRawNodeSet(projection);

                        draggingVisualNodes =
                                draggedVisualRawNodes.nextSetBit(0) >= 0;

                        draggingNodes = false;
                        draggingBorders = false;
                        panning = false;

                        repaint();
                        return;
                }

                /*
                * Raw non-projection node support is retained for the fallback
                * GraphRenderer path.
                */
                final int nodeId =
                        findNodeAt(
                                event.getX(),
                                event.getY());

                if (nodeId >= 0) {
                        if (multiSelect) {
                        selectedNodes.flip(nodeId);
                        stopDragging();
                        panning = false;
                        repaint();
                        return;
                        }

                        if (!selectedNodes.get(nodeId)) {
                        clearSelection();
                        selectedNodes.set(nodeId);
                        }

                        draggingNodes =
                                selectedNodes.nextSetBit(0) >= 0;

                        draggingVisualNodes = false;
                        draggingBorders = false;
                        panning = false;

                        repaint();
                        return;
                }

                /*
                * Edges intentionally take priority over borders. A user who
                * clicks an edge inside a network rectangle selects the edge,
                * rather than accidentally selecting/dragging the border.
                */
                final int edgeId =
                        findEdgeAt(
                                event.getX(),
                                event.getY());

                if (edgeId >= 0) {
                        if (multiSelect) {
                        selectedEdges.flip(edgeId);

                        } else if (!selectedEdges.get(edgeId)) {
                        clearSelection();
                        selectedEdges.set(edgeId);
                        }

                        stopDragging();
                        panning = false;
                        repaint();
                        return;
                }

                /*
                * Border hit-testing remains after nodes and edges. Empty space
                * inside a border is still valid panning space unless the border
                * line itself was hit by findBorderAt(...).
                */
                final NetworkBorder border =
                        findBorderAt(
                                event.getX(),
                                event.getY());

                if (border != null) {
                        if (multiSelect) {
                        toggleBorderSelection(border);
                        stopDragging();
                        panning = false;
                        repaint();
                        return;
                        }

                        if (!isBorderSelected(border)) {
                        clearSelection();
                        setBorderSelected(
                                border,
                                true);
                        }

                        buildDraggedBorderNodeSet();

                        draggingNodes = false;
                        draggingVisualNodes = false;

                        draggingBorders =
                                draggedBorderNodes.nextSetBit(0) >= 0;

                        panning = false;
                        repaint();
                        return;
                }

                /*
                * Anywhere not occupied by a node, edge, or border line pans the
                * viewport. This includes empty space inside network borders.
                */
                if (!multiSelect) {
                        clearSelection();
                        panning = true;

                } else {
                        panning = false;
                }

                stopDragging();
                repaint();
                }

                @Override
                public void mouseReleased(
                        final MouseEvent event) {

                if (event.isPopupTrigger()) {
                        finishPointerInteraction();
                        showContextMenu(event);
                        return;
                }

                if (marqueeSelecting) {
                        if (hasMarqueeArea()) {
                        selectItemsInMarquee();
                        }

                        clearMarqueeSelection();
                        finishPointerInteraction();
                        repaint();
                        return;
                }

                finishPointerInteraction();
                }
        });

        addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(
                        final MouseEvent event) {

                if (graphStore.isEmpty()) {
                        return;
                }

                if (marqueeSelecting) {
                        marqueeEnd =
                                event.getPoint();

                        repaint();
                        return;
                }

                if (lastMousePoint == null) {
                        return;
                }

                final float deltaX =
                        (float) (
                                (event.getX()
                                        - lastMousePoint.x)
                                        / zoom);

                final float deltaY =
                        (float) (
                                (event.getY()
                                        - lastMousePoint.y)
                                        / zoom);

                if (draggingVisualNodes) {
                        translateNodes(
                                draggedVisualRawNodes,
                                deltaX,
                                deltaY);

                        lastMousePoint =
                                event.getPoint();

                        return;
                }

                if (draggingNodes) {
                        translateNodes(
                                selectedNodes,
                                deltaX,
                                deltaY);

                        lastMousePoint =
                                event.getPoint();

                        return;
                }

                if (draggingBorders) {
                        translateNodes(
                                draggedBorderNodes,
                                deltaX,
                                deltaY);

                        lastMousePoint =
                                event.getPoint();

                        return;
                }

                if (panning) {
                        panX += event.getX()
                                - lastMousePoint.x;

                        panY += event.getY()
                                - lastMousePoint.y;

                        lastMousePoint =
                                event.getPoint();

                        repaint();
                }
                }
        });
        }

        /**
         * Returns the most-specific currently expanded network containing this raw
         * node. Collapsing that network re-forms the immediate higher-level visual
         * cluster for this device.
         */
        private int findRawNodeReclusterTarget(
                final int rawNodeId) {

        final GraphMetadata.NetworkTable networks =
                graphStore.getGraphMetadata()
                        .getNetworkTable();

        int networkId =
                networks.primaryNetworkForNode(rawNodeId);

        while (networkId >= 0) {
                if (clusterViewState.isExpanded(networkId)) {
                return networkId;
                }

                networkId =
                        networks.getParentNetworkId(networkId);
        }

        return -1;
        }

        /**
         * Finds the visible hierarchy network that would contain this raw node if
         * partial-group expansion were ignored.
         *
         * This intentionally matches ClusterProjectionBuilder.activeNetworkForNode().
         * It must remain consistent with the projection builder so a raw IOC node is
         * associated with the same partial scope that originally produced its IOC
         * cluster.
         */
        private static int activeNetworkForRawNode(
                final GraphMetadata.NetworkTable networks,
                final int rawNodeId,
                final ClusterViewState.Snapshot state) {

        int networkId =
                networks.primaryNetworkForNode(rawNodeId);

        if (networkId < 0) {
                return -1;
        }

        int selectedNetwork = -1;
        boolean primaryNetwork = true;

        while (networkId >= 0) {
                final boolean structuralRoot =
                        networks.getPrefixLength(networkId) == 0
                                && !primaryNetwork;

                if (!structuralRoot
                        && !state.isExpanded(networkId)) {

                selectedNetwork = networkId;
                }

                networkId =
                        networks.getParentNetworkId(networkId);

                primaryNetwork = false;
        }

        return selectedNetwork;
        }

        /**
         * Returns the expanded partial group that produced this displayed raw node,
         * or null when the raw node came from normal hierarchy expansion instead.
         *
         * For example, an IOC device exposed from:
         *
         *   Network A -> Partial Unclustering -> Device Type -> IOC
         *
         * returns the Network A partial definition and its IOC group key.
         */
        private PartialRawGroup findExpandedPartialGroupForRawNode(
                final int rawNodeId) {

        final GraphStore store =
                graphStore;

        if (store == null || store.isEmpty()) {
                return null;
        }

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final GraphMetadata.NetworkTable networks =
                metadata.getNetworkTable();

        final ClusterViewState.Snapshot state =
                clusterViewState.snapshot();

        final int primaryNetwork =
                networks.primaryNetworkForNode(rawNodeId);

        final boolean undetermined =
                primaryNetwork < 0;

        final int activeNetwork =
                undetermined
                        ? -1
                        : activeNetworkForRawNode(
                                networks,
                                rawNodeId,
                                state);

        /*
        * The node is part of a real NIC network, but all hierarchy levels are
        * expanded. It is a normal raw node, not a node exposed by a partial
        * cluster. No partial-group re-cluster action is appropriate.
        */
        if (!undetermined && activeNetwork < 0) {
                return null;
        }

        final String activeNetworkKey =
                undetermined
                        ? null
                        : networks.networkIdentityKey(
                                activeNetwork);

        final byte effectiveTags =
                metadata.effectiveNodeTypeMask(
                        rawNodeId,
                        store.getNodeType(rawNodeId));

        final PartialUnclusterDefinition[] definitions =
                state.getPartialUnclustersUnsafe();

        for (int index = 0;
                index < definitions.length;
                index++) {

                final PartialUnclusterDefinition definition =
                        definitions[index];

                if (!definition.appliesToScope(
                        undetermined,
                        activeNetworkKey)) {

                continue;
                }

                final int group =
                        definition.groupForNode(
                                store,
                                metadata,
                                rawNodeId,
                                effectiveTags);

                if (state.isPartialGroupExpanded(
                        definition,
                        group)) {

                return new PartialRawGroup(
                        definition,
                        group);
                }
        }

        return null;
        }

        /**
         * Small immutable context object used only while constructing a raw-node
         * context menu. It avoids adding state to GraphStore or ClusterProjection.
         */
        private static final class PartialRawGroup {
        private final PartialUnclusterDefinition definition;
        private final int group;

        private PartialRawGroup(
                final PartialUnclusterDefinition definition,
                final int group) {

                this.definition = definition;
                this.group = group;
        }
        }

        private void reClusterRawNodeOneLevel(
                final int rawNodeId) {

        final GraphMetadata.NetworkTable networks =
                graphStore.getGraphMetadata()
                        .getNetworkTable();

        final int primaryNetwork =
                networks.primaryNetworkForNode(rawNodeId);

        if (primaryNetwork < 0) {
                if (clusterViewState.isUndeterminedExpanded()) {
                clusterViewState.collapseUndetermined();
                refreshClusterProjectionForViewChange();
                }

                return;
        }

        final int target =
                findRawNodeReclusterTarget(rawNodeId);

        if (target >= 0) {
                clusterViewState.collapseThisCluster(
                        target,
                        networks);

                refreshClusterProjectionForViewChange();
        }
        }

        private void showRawVisualNodeMenu(
                final ClusterProjection projection,
                final int visualId,
                final MouseEvent event) {

        final int rawNodeId =
                projection.getRawNodeId(visualId);

        /*
        * Detect whether this raw node is visible because one partial cluster group
        * was expanded. If so, re-clustering must collapse that complete partial
        * group, not collapse a CIDR hierarchy level.
        */
        final PartialRawGroup partialGroup =
                findExpandedPartialGroupForRawNode(rawNodeId);

        final GraphMetadata.NetworkTable networks =
                graphStore.getGraphMetadata()
                        .getNetworkTable();

        final int primaryNetwork =
                networks.primaryNetworkForNode(rawNodeId);

        final boolean canReclusterNetwork =
                partialGroup == null
                        && (primaryNetwork < 0
                                ? clusterViewState
                                        .isUndeterminedExpanded()
                                : findRawNodeReclusterTarget(
                                        rawNodeId) >= 0);

        final JPopupMenu menu =
                new JPopupMenu();

        final JMenuItem details =
                new JMenuItem("Device Details\u2026");

        details.addActionListener(e ->
                showNodeDetails(rawNodeId));

        menu.add(details);
        menu.addSeparator();

        /*
        * This is enabled only for raw devices created by expanding one partial
        * cluster group. Selecting it restores the full partial group—for example,
        * all IOC devices return to one IOC Devices cluster.
        */
        final JMenuItem reClusterPartial =
                new JMenuItem(
                        partialGroup == null
                                ? "Re-Cluster Partial Group"
                                : "Re-Cluster "
                                        + partialGroup.definition
                                                .groupDescription(
                                                        partialGroup.group));

        reClusterPartial.setEnabled(
                partialGroup != null);

        reClusterPartial.addActionListener(e -> {
                if (partialGroup == null) {
                return;
                }

                clusterViewState.collapsePartialGroup(
                        partialGroup.definition,
                        partialGroup.group);

                refreshClusterProjectionForViewChange();
        });

        menu.add(reClusterPartial);

        /*
        * This remains the normal CIDR-hierarchy/Undetermined Network re-cluster
        * operation. It is disabled when this raw node belongs to an expanded
        * partial group because that condition requires the specific operation
        * above instead.
        */
        final JMenuItem reClusterOneLevel =
                new JMenuItem("Re-Cluster One Level");

        reClusterOneLevel.setEnabled(
                canReclusterNetwork);

        reClusterOneLevel.addActionListener(e -> {
                if (!canReclusterNetwork) {
                return;
                }

                reClusterRawNodeOneLevel(rawNodeId);
        });

        menu.add(reClusterOneLevel);

        menu.show(
                this,
                event.getX(),
                event.getY());
        }

    private void beginMarqueeSelection(
            final Point startPoint) {

        marqueeStart = startPoint;
        marqueeEnd = startPoint;
        marqueeSelecting = true;

        stopDragging();
        panning = false;

        repaint();
    }

    private void clearMarqueeSelection() {
        marqueeStart = null;
        marqueeEnd = null;
        marqueeSelecting = false;
    }

    private boolean hasMarqueeArea() {
        if (marqueeStart == null
                || marqueeEnd == null) {

            return false;
        }

        return Math.abs(
                marqueeEnd.x - marqueeStart.x)
                        >= MARQUEE_DRAG_THRESHOLD
                || Math.abs(
                        marqueeEnd.y - marqueeStart.y)
                        >= MARQUEE_DRAG_THRESHOLD;
    }

    private void drawMarqueeSelection(
            final Graphics2D graphics) {

        if (!marqueeSelecting
                || marqueeStart == null
                || marqueeEnd == null) {

            return;
        }

        final int left =
                Math.min(

                        marqueeStart.x,
                        marqueeEnd.x);

        final int top =
                Math.min(
                        marqueeStart.y,
                        marqueeEnd.y);

        final int right =
                Math.max(
                        marqueeStart.x,
                        marqueeEnd.x);

        final int bottom =
                Math.max(
                        marqueeStart.y,
                        marqueeEnd.y);

        final Stroke originalStroke =
                graphics.getStroke();

        final Color originalColor =
                graphics.getColor();

        try {
            graphics.setColor(
                    new Color(
                            0x00,
                            0x5E,
                            0xB8,
                            40));

            graphics.fillRect(
                    left,
                    top,
                    right - left,
                    bottom - top);

            graphics.setColor(
                    new Color(
                            0x00,
                            0x5E,
                            0xB8));

            graphics.setStroke(
                    new BasicStroke(
                            1.25f,
                            BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER,
                            10.0f,
                            new float[] { 5.0f, 4.0f },
                            0.0f));

            graphics.drawRect(
                    left,
                    top,
                    right - left,
                    bottom - top);

        } finally {
            graphics.setStroke(originalStroke);
            graphics.setColor(originalColor);
        }
    }

    /**
     * Replaces the current selection with graph objects represented by the
     * Shift+left-drag marquee:
     *
     * <ul>
     *   <li>nodes whose centers are within the rectangle;</li>
     *   <li>edges whose source and destination nodes are both selected; and</li>
     *   <li>network borders whose rendered rectangle line intersects the
     *       marquee.</li>
     * </ul>
     */
    private void selectItemsInMarquee() {
        if (!hasMarqueeArea()) {
            return;
        }

        final int screenLeft =
                Math.min(
                        marqueeStart.x,
                        marqueeEnd.x);

        final int screenTop =
                Math.min(
                        marqueeStart.y,
                        marqueeEnd.y);

        final int screenRight =
                Math.max(
                        marqueeStart.x,
                        marqueeEnd.x);

        final int screenBottom =
                Math.max(
                        marqueeStart.y,
                        marqueeEnd.y);

        final double worldMinX =
                (screenLeft - panX) / zoom;

        final double worldMinY =
                (screenTop - panY) / zoom;

        final double worldMaxX =
                (screenRight - panX) / zoom;

        final double worldMaxY =
                (screenBottom - panY) / zoom;

        clearSelection();

        selectNodesInWorldRectangle(
                worldMinX,
                worldMinY,
                worldMaxX,
                worldMaxY);

        selectEdgesBetweenSelectedNodes();

        selectBordersIntersectingWorldRectangle(
                worldMinX,
                worldMinY,
                worldMaxX,
                worldMaxY);
    }

    private void selectNodesInWorldRectangle(
            final double minX,
            final double minY,
            final double maxX,
            final double maxY) {

        final GraphStore store = graphStore;
        final GraphDisplayFilter filter =
                displayFilter;

        if (isClusterProjectionActive()
                || store == null
                || store.isEmpty()
                || !filter.isShowDevices()) {

            return;
        }

        final LayoutState state =
                store.getLayoutState();

        synchronized (state.renderLock()) {
            final float[] x =
                    state.visibleXUnsafe();

            final float[] y =
                    state.visibleYUnsafe();

            store.getSpatialIndex().query(
                    (float) minX,
                    (float) minY,
                    (float) maxX,
                    (float) maxY,
                    hitCandidates);

            for (int index = 0;
                    index < hitCandidates.size();
                    index++) {

                final int nodeId =
                        hitCandidates.get(index);

                if (!filter.matchesNode(store, nodeId)) {
                    continue;
                }

                if (x[nodeId] >= minX
                        && x[nodeId] <= maxX
                        && y[nodeId] >= minY
                        && y[nodeId] <= maxY) {

                    selectedNodes.set(nodeId);
                }
            }
        }
    }

    private void selectEdgesBetweenSelectedNodes() {
        final GraphStore store = graphStore;
        final GraphDisplayFilter filter =
                displayFilter;

        if (isClusterProjectionActive()
                || store == null
                || store.isEmpty()
                || !filter.isShowDevices()
                || !filter.isShowEdges()) {

            return;
        }

        for (int nodeId = selectedNodes.nextSetBit(0);
                nodeId >= 0;
                nodeId = selectedNodes.nextSetBit(
                        nodeId + 1)) {

            final int start =
                    store.getIncidentEdgeStart(nodeId);

            final int end =
                    store.getIncidentEdgeEnd(nodeId);

            for (int position = start;
                    position < end;
                    position++) {

                final int edgeId =
                        store.getIncidentEdgeId(position);

                if (!filter.matchesEdge(store, edgeId)) {
                    continue;
                }

                final int sourceNodeId =
                        store.getEdgeSourceNodeId(edgeId);

                final int destinationNodeId =
                        store.getEdgeTargetNodeId(edgeId);

                if (selectedNodes.get(sourceNodeId)
                        && selectedNodes.get(
                                destinationNodeId)) {

                    selectedEdges.set(edgeId);
                }
            }
        }
    }

    private void selectBordersIntersectingWorldRectangle(
            final double minX,
            final double minY,
            final double maxX,
            final double maxY) {

        final NetworkBorder[] borders =
                borderSnapshot.getBordersUnsafe();

        for (int index = 0;
                index < borders.length;
                index++) {

            final NetworkBorder border =
                    borders[index];

            if (worldRectangleIntersectsBorder(
                    minX,
                    minY,
                    maxX,
                    maxY,
                    border)) {

                setBorderSelected(
                        border,
                        true);
            }
        }
    }

    private static boolean worldRectangleIntersectsBorder(
            final double selectionMinX,
            final double selectionMinY,
            final double selectionMaxX,
            final double selectionMaxY,
            final NetworkBorder border) {

        if (border == null) {
            return false;
        }

        final boolean overlapsBorderWidth =
                rangesOverlap(
                        selectionMinX,
                        selectionMaxX,
                        border.getMinX(),
                        border.getMaxX());

        final boolean overlapsBorderHeight =
                rangesOverlap(
                        selectionMinY,
                        selectionMaxY,
                        border.getMinY(),
                        border.getMaxY());

        final boolean containsTopOrBottomBorderLine =
                overlapsBorderWidth
                        && (valueInRange(
                                border.getMinY(),
                                selectionMinY,
                                selectionMaxY)
                        || valueInRange(
                                border.getMaxY(),
                                selectionMinY,
                                selectionMaxY));

        final boolean containsLeftOrRightBorderLine =
                overlapsBorderHeight
                        && (valueInRange(
                                border.getMinX(),
                                selectionMinX,
                                selectionMaxX)
                        || valueInRange(
                                border.getMaxX(),
                                selectionMinX,
                                selectionMaxX));

        return containsTopOrBottomBorderLine
                || containsLeftOrRightBorderLine;
    }

    private static boolean rangesOverlap(
            final double firstMin,
            final double firstMax,
            final double secondMin,
            final double secondMax) {

        return firstMax >= secondMin
                && secondMax >= firstMin;
    }

    private static boolean valueInRange(
            final double value,
            final double min,
            final double max) {

        return value >= min
                && value <= max;
    }

        private void clearSelection() {
                selectedNodes.clear();
                selectedEdges.clear();
                selectedVisualNodes.clear();
                selectedNetworkBorders.clear();
                selectedUndeterminedBorder = false;
        }

        private void stopDragging() {
                draggingNodes = false;
                draggingVisualNodes = false;
                draggingBorders = false;

                draggedVisualRawNodes.clear();
                draggedBorderNodes.clear();
        }

    /**
     * Completes a mouse interaction and immediately refreshes a projection
     * whose source coordinates changed during a drag.
     */
    private void finishPointerInteraction() {
        final boolean geometryChanged = clusterGeometryDirty;
        clusterGeometryDirty = false;
        lastMousePoint = null;
        panning = false;
        stopDragging();

        if (geometryChanged) {
            clusterProjectionDebounceTimer.stop();
            clusterCoordinator.invalidate();
            requestClusterProjection();
        }
    }

    /**
     * Drops coordinate-dependent cache entries and coalesces projection work
     * while the analyst is dragging one or more nodes or borders.
     */
    private void scheduleClusterProjectionAfterGeometryChange() {
        clusterCoordinator.invalidate();
        clusterProjectionDebounceTimer.restart();
    }

    /**
     * Changes to hierarchy, analyst clusters, or filters can renumber visual
     * nodes. Clear projection-local selection before rebuilding.
     */
    private void refreshClusterProjectionForViewChange() {
        selectedVisualNodes.clear();
        clusterProjectionDebounceTimer.stop();
        clusterCoordinator.invalidate();
        requestClusterProjection();
        repaint();
    }

        private boolean isBorderSelected(
                final NetworkBorder border) {

        if (border == null) {
                return false;
        }

        return border.isUndetermined()
                ? selectedUndeterminedBorder
                : selectedNetworkBorders.get(
                        border.getNetworkId());
        }

        private void setBorderSelected(
                final NetworkBorder border,
                final boolean selected) {

        if (border == null) {
                return;
        }

        if (border.isUndetermined()) {
                selectedUndeterminedBorder = selected;
                return;
        }

        selectedNetworkBorders.set(
                border.getNetworkId(),
                selected);
        }

        private void toggleBorderSelection(
                final NetworkBorder border) {

        if (border == null) {
                return;
        }

        if (border.isUndetermined()) {
                selectedUndeterminedBorder =
                        !selectedUndeterminedBorder;

                return;
        }

        selectedNetworkBorders.flip(
                border.getNetworkId());
        }

        /**
         * Creates the union of devices contained by all selected network borders.
         * A parent CIDR border includes devices belonging to represented subnetworks
         * because CIDR matches are retained in the packed membership table.
         */
        private void buildDraggedBorderNodeSet() {
        draggedBorderNodes.clear();

        final GraphStore store = graphStore;

        if (store == null || store.isEmpty()) {
                return;
        }

        final GraphMetadata.NetworkTable networks =
                store.getGraphMetadata()
                        .getNetworkTable();

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

                final int start =
                        networks.getMembershipStart(nodeId);

                final int end =
                        networks.getMembershipEnd(nodeId);

                if (start == end) {
                if (selectedUndeterminedBorder) {
                        draggedBorderNodes.set(nodeId);
                }

                continue;
                }

                for (int membership = start;
                        membership < end;
                        membership++) {

                final int networkId =
                        networks.getMembershipNetworkId(
                                membership);

                if (selectedNetworkBorders.get(networkId)) {
                        draggedBorderNodes.set(nodeId);
                        break;
                }
                }
        }
        }

        /**
         * Resolves selected projection nodes back to their packed raw-device members.
         *
         * This allows a drag on a displayed network, partial cluster, analyst cluster,
         * or individual raw-device visual node to translate its underlying raw layout
         * coordinates. The projection is rebuilt asynchronously after the gesture.
         */
        private void buildDraggedVisualRawNodeSet(
                final ClusterProjection projection) {

        draggedVisualRawNodes.clear();

        if (projection == null
                || projection.isEmpty()
                || selectedVisualNodes.nextSetBit(0) < 0) {

                return;
        }

        final int nodeCount =
                graphStore.getNodeCount();

        for (int rawNodeId = 0;
                rawNodeId < nodeCount;
                rawNodeId++) {

                final int visualId =
                        projection.getVisualForRawNode(rawNodeId);

                if (visualId >= 0
                        && selectedVisualNodes.get(visualId)) {

                draggedVisualRawNodes.set(rawNodeId);
                }
        }
        }

        private void translateNodes(
                final BitSet nodes,
                final float deltaX,
                final float deltaY) {

                if (nodes == null
                        || nodes.nextSetBit(0) < 0
                        || (deltaX == 0.0f && deltaY == 0.0f)) {

                        return;
                }

                final GraphStore store = graphStore;

                final LayoutState state =
                        store.getLayoutState();

                state.translateNodesEverywhere(
                        nodes,
                        deltaX,
                        deltaY);

                synchronized (state.renderLock()) {
                        store.getSpatialIndex().rebuild(
                                state.visibleXUnsafe(),
                                state.visibleYUnsafe(),
                                store.getNodeCount());
                }

                rebuildBorderSnapshot();

                clusterGeometryDirty = true;

                scheduleClusterProjectionAfterGeometryChange();

                repaint();
        }

    private boolean isClusterProjectionActive() {
        return clusterProjection != null && !clusterProjection.isEmpty();
    }

        private void requestClusterProjection() {
        clusterProjectionDebounceTimer.stop();

        final GraphStore store =
                graphStore;

        if (store == null || store.isEmpty()) {

                clusterProjection =
                        ClusterProjection.empty();

                return;
        }

        final LayoutState state =
                store.getLayoutState();

        final float[] x;
        final float[] y;

        synchronized (state.renderLock()) {
                x = java.util.Arrays.copyOf(
                        state.visibleXUnsafe(),
                        store.getNodeCount());

                y = java.util.Arrays.copyOf(
                        state.visibleYUnsafe(),
                        store.getNodeCount());
        }

        clusterCoordinator.request(
                store,
                x,
                y,
                displayFilter,
                clusterViewState.snapshot(),
                new ClusterProjectionCoordinator.Listener() {
                        @Override
                        public void accept(
                                final GraphStore completedStore,
                                final ClusterProjection projection) {

                        if (graphStore != completedStore) {
                                return;
                        }

                        clusterProjection = projection;
                        repaint();
                        }
                });
        }

    private int findVisualNodeAt(final int screenX, final int screenY) {
        final ClusterProjection projection = clusterProjection;
        if (projection == null || projection.isEmpty()
                || !displayFilter.isShowDevices()) {
            return -1;
        }
        final float worldX = (float) ((screenX - panX) / zoom);
        final float worldY = (float) ((screenY - panY) / zoom);
        final float radius = (float) (28.0 / zoom);
        projection.getSpatialIndex().query(worldX - radius, worldY - radius,
                worldX + radius, worldY + radius, hitCandidates);
        int closest = -1;
        float closestDistance = Float.MAX_VALUE;
        for (int index = 0; index < hitCandidates.size(); index++) {
            final int visualId = hitCandidates.get(index);
            final float dx = projection.getX(visualId) - worldX;
            final float dy = projection.getY(visualId) - worldY;
            final float distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < closestDistance) {
                closest = visualId;
                closestDistance = distance;
            }
        }
        return closest;
    }

        private void showContextMenu(final MouseEvent event) {
        final ClusterProjection projection =
                clusterProjection;

        if (projection != null
                && !projection.isEmpty()) {

                final int visualId =
                        findVisualNodeAt(
                                event.getX(),
                                event.getY());

                if (visualId >= 0) {
                final byte kind =
                        projection.getKind(visualId);

                if (kind == ClusterProjection.KIND_RAW_NODE) {
                showRawVisualNodeMenu(
                        projection,
                        visualId,
                        event);

                } else if (kind == ClusterProjection.KIND_NETWORK) {
                        showNetworkClusterMenu(
                                projection,
                                visualId,
                                event);

                } else if (kind == ClusterProjection.KIND_UNDETERMINED) {
                        showUndeterminedClusterMenu(
                                projection,
                                visualId,
                                event);

                } else if (kind == ClusterProjection.KIND_PARTIAL) {
                        showPartialClusterMenu(
                                projection,
                                visualId,
                                event);

                } else {
                        showAnalystClusterMenu(
                                projection,
                                visualId,
                                event);
                }

                return;
                }
        }

        final int nodeId =
                findNodeAt(
                        event.getX(),
                        event.getY());

        if (nodeId >= 0) {
                showNodeDetails(nodeId);
                return;
        }

        final int edgeId =
                findEdgeAt(
                        event.getX(),
                        event.getY());

        if (edgeId >= 0) {
                showEdgeDetails(edgeId);
                return;
        }

        final NetworkBorder border =
                findBorderAt(
                        event.getX(),
                        event.getY());

        if (border != null) {
                showBorderContextMenu(border, event);
        }
        }

        private void showNetworkClusterMenu(
                final ClusterProjection projection,
                final int visualId,
                final MouseEvent event) {

        final int networkId =
                projection.getNetworkId(visualId);

        final GraphMetadata.NetworkTable networks =
                graphStore.getGraphMetadata()
                        .getNetworkTable();

        final boolean hasChildren =
                networks.hasChildNetworks(networkId);

        final int memberCount =
                projection.getMemberCount(visualId);

        final boolean hasMembers =
                memberCount > 0;

        final boolean canUnclusterOneLevel =
                hasChildren || memberCount > 1;

        final boolean expanded =
                clusterViewState.isExpanded(networkId);

        final int parent =
                networks.getParentNetworkId(networkId);

        final JPopupMenu menu =
                new JPopupMenu();

        final JMenuItem details =
                new JMenuItem("Cluster Details\u2026");

        details.addActionListener(
                e -> showClusterDetails(
                        projection,
                        visualId));

        menu.add(details);
        menu.addSeparator();

        final JMenuItem one =
                new JMenuItem("Uncluster One Level");

        one.setEnabled(canUnclusterOneLevel);

        one.addActionListener(e -> {
                clusterViewState.unclusterOneLevel(networkId);
                refreshClusterProjectionForViewChange();
        });

        menu.add(one);

        final JMenuItem all =
                new JMenuItem("Uncluster All Descendants");

        all.setEnabled(hasChildren);

        all.addActionListener(e -> {
                clusterViewState.unclusterAllDescendants(
                        networkId,
                        networks);

                refreshClusterProjectionForViewChange();
        });

        menu.add(all);

        final JMenuItem partial =
                new JMenuItem("Partial Unclustering\u2026");

        partial.setEnabled(hasMembers);

        partial.addActionListener(e ->
                showPartialUnclusterDialog(
                        networkId,
                        false));

        menu.add(partial);
        menu.addSeparator();

        final JMenuItem collapse =
                new JMenuItem("Collapse This Cluster");

        collapse.setEnabled(expanded);

        collapse.addActionListener(e -> {
                clusterViewState.collapseThisCluster(
                        networkId,
                        networks);

                refreshClusterProjectionForViewChange();
        });

        menu.add(collapse);

        final JMenuItem parentItem =
                new JMenuItem("Re-Cluster One Level");

        parentItem.setEnabled(
                parent >= 0
                        && clusterViewState.isExpanded(parent));

        parentItem.addActionListener(e -> {
                clusterViewState.collapseParentCluster(
                        networkId,
                        networks);

                refreshClusterProjectionForViewChange();
        });

        menu.add(parentItem);

        final JMenuItem reset =
                new JMenuItem("Reset to Top-Level Clusters");

        reset.setEnabled(
                clusterViewState.hasAnyClusterDrillDown());

        reset.addActionListener(e -> {
                clusterViewState.resetToTopLevelClusters();
                refreshClusterProjectionForViewChange();
        });

        menu.add(reset);
        menu.addSeparator();

        final JMenuItem fit =
                new JMenuItem("Fit Cluster to Window");

        fit.addActionListener(e ->
                fitVisualNode(
                        projection,
                        visualId));

        menu.add(fit);

        menu.show(
                this,
                event.getX(),
                event.getY());
        }

    private void showAnalystClusterMenu(final ClusterProjection projection,
            final int visualId, final MouseEvent event) {
        final JPopupMenu menu = new JPopupMenu();
        final JMenuItem details = new JMenuItem("Cluster Details…");
        details.addActionListener(e -> showClusterDetails(projection, visualId));
        menu.add(details);
        final JMenuItem dissolve = new JMenuItem("Dissolve Analyst Cluster");
        dissolve.addActionListener(e -> { clusterViewState.removeAnalystCluster(
                projection.getAnalystClusterId(visualId)); refreshClusterProjectionForViewChange(); });
        menu.add(dissolve);
        final JMenuItem fit = new JMenuItem("Fit Cluster to Window");
        fit.addActionListener(e -> fitVisualNode(projection, visualId));
        menu.add(fit);
        menu.show(this, event.getX(), event.getY());
    }

    private void showBorderContextMenu(final NetworkBorder border,
            final MouseEvent event) {
        final JPopupMenu menu = new JPopupMenu();
        final JMenuItem details = new JMenuItem("Network Border Details…");
        details.addActionListener(e -> showBorderDetails(border));
        menu.add(details);
        final JMenuItem analyst = new JMenuItem("Cluster Selected Network Borders…");
        analyst.setEnabled(selectedNetworkBorders.nextSetBit(0) >= 0);
        analyst.addActionListener(e -> createAnalystClusterFromSelectedBorders());
        menu.add(analyst);
        menu.show(this, event.getX(), event.getY());
    }

    private void createAnalystClusterFromSelectedBorders() {
        final int[] ids = new int[selectedNetworkBorders.cardinality()];
        int write = 0;
        for (int id = selectedNetworkBorders.nextSetBit(0); id >= 0;
                id = selectedNetworkBorders.nextSetBit(id + 1)) ids[write++] = id;
        if (write == 0) return;
        final String label = JOptionPane.showInputDialog(this,
                "Analyst-defined cluster label:", "Cluster Selected Network Borders",
                JOptionPane.PLAIN_MESSAGE);
        if (label == null || label.trim().isEmpty()) return;
        final GraphMetadata.NetworkTable networks = graphStore.getGraphMetadata().getNetworkTable();
        clusterViewState.addAnalystCluster(AnalystClusterDefinition.fromNetworkIds(
                label, networks, ids));
        refreshClusterProjectionForViewChange();
    }

    private void showClusterDetails(final ClusterProjection projection,
            final int visualId) {
        final StringBuilder text = new StringBuilder(768);
        text.append("Cluster: ").append(projection.getLabel(visualId)).append("\n\n")
                .append("Summarized devices: ").append(projection.getMemberCount(visualId)).append('\n')
                .append("Internal connections: ").append(projection.getInternalConnectionCount(visualId)).append('\n')
                .append("Internal bytes: ").append(projection.getInternalBytes(visualId)).append('\n')
                .append("Internal packets: ").append(projection.getInternalPackets(visualId)).append("\n\n")
                .append("Device Tags\n")
                .append("  OT: ").append(projection.getInnerTagCount(visualId, 0)).append('\n')
                .append("  IT: ").append(projection.getInnerTagCount(visualId, 1)).append('\n')
                .append("  IOC: ").append(projection.getInnerTagCount(visualId, 2)).append('\n')
                .append("  UNK: ").append(projection.getInnerTagCount(visualId, 3));
        JOptionPane.showMessageDialog(this, text.toString(), "Cluster Details",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void fitVisualNode(final ClusterProjection projection, final int visualId) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, 1.35));
        panX = getWidth() * 0.5 - projection.getX(visualId) * zoom;
        panY = getHeight() * 0.5 - projection.getY(visualId) * zoom;
        repaint();
    }

    private int findNodeAt(
            final int screenX,
            final int screenY) {

        final GraphStore store = graphStore;
        final GraphDisplayFilter filter =
                displayFilter;

        if (store == null
                || store.isEmpty()
                || !filter.isShowDevices()) {

            return -1;
        }

        final float worldX =
                (float) ((screenX - panX) / zoom);

        final float worldY =
                (float) ((screenY - panY) / zoom);

        final float hitRadius =
                (float) (16.0 / zoom);

        final LayoutState state =
                store.getLayoutState();

        synchronized (state.renderLock()) {
            final float[] x =
                    state.visibleXUnsafe();

            final float[] y =
                    state.visibleYUnsafe();

            store.getSpatialIndex().query(
                    worldX - hitRadius,
                    worldY - hitRadius,
                    worldX + hitRadius,
                    worldY + hitRadius,
                    hitCandidates);

            int closestNode = -1;

            float closestDistanceSquared =
                    Float.MAX_VALUE;

            for (int index = 0;
                    index < hitCandidates.size();
                    index++) {

                final int nodeId =
                        hitCandidates.get(index);

                if (!filter.matchesNode(store, nodeId)) {
                    continue;
                }

                final float deltaX =
                        x[nodeId] - worldX;

                final float deltaY =
                        y[nodeId] - worldY;

                final float distanceSquared =
                        deltaX * deltaX
                                + deltaY * deltaY;

                if (distanceSquared
                        <= hitRadius * hitRadius
                        && distanceSquared
                                < closestDistanceSquared) {

                    closestNode = nodeId;
                    closestDistanceSquared =
                            distanceSquared;
                }
            }

            return closestNode;
        }
    }

    private int findEdgeAt(
            final int screenX,
            final int screenY) {

        final GraphStore store = graphStore;
        final GraphDisplayFilter filter =
                displayFilter;

        if (store == null
                || store.isEmpty()
                || !filter.isShowDevices()
                || !filter.isShowEdges()) {

            return -1;
        }

        final LayoutState state =
                store.getLayoutState();

        final double hitTolerance = 12.0;
        final double hitToleranceSquared =
                hitTolerance * hitTolerance;

        synchronized (state.renderLock()) {
            final float[] x =
                    state.visibleXUnsafe();

            final float[] y =
                    state.visibleYUnsafe();

            int closestEdge = -1;

            double closestDistanceSquared =
                    hitToleranceSquared;

            for (int edgeId = 0;
                    edgeId < store.getEdgeCount();
                    edgeId++) {

                if (!filter.matchesEdge(store, edgeId)) {
                    continue;
                }

                final int sourceNodeId =
                        store.getEdgeSourceNodeId(edgeId);

                final int destinationNodeId =
                        store.getEdgeTargetNodeId(edgeId);

                final double sourceX =
                        x[sourceNodeId] * zoom + panX;

                final double sourceY =
                        y[sourceNodeId] * zoom + panY;

                final double destinationX =
                        x[destinationNodeId] * zoom + panX;

                final double destinationY =
                        y[destinationNodeId] * zoom + panY;

                final double distanceSquared =
                        pointToSegmentDistanceSquared(
                                screenX,
                                screenY,
                                sourceX,
                                sourceY,
                                destinationX,
                                destinationY);

                if (distanceSquared <= closestDistanceSquared) {
                    closestDistanceSquared = distanceSquared;
                    closestEdge = edgeId;
                }
            }

            return closestEdge;
        }
    }

    private static double pointToSegmentDistanceSquared(
            final double pointX,
            final double pointY,
            final double firstX,
            final double firstY,
            final double secondX,
            final double secondY) {

        final double deltaX = secondX - firstX;
        final double deltaY = secondY - firstY;

        final double lengthSquared =
                deltaX * deltaX + deltaY * deltaY;

        if (lengthSquared <= 0.0001) {
            final double pointDeltaX = pointX - firstX;
            final double pointDeltaY = pointY - firstY;

            return pointDeltaX * pointDeltaX
                    + pointDeltaY * pointDeltaY;
        }

        double projection =
                ((pointX - firstX) * deltaX
                        + (pointY - firstY) * deltaY)
                        / lengthSquared;

        projection = Math.max(
                0.0,
                Math.min(1.0, projection));

        final double nearestX =
                firstX + projection * deltaX;

        final double nearestY =
                firstY + projection * deltaY;

        final double pointDeltaX =
                pointX - nearestX;

        final double pointDeltaY =
                pointY - nearestY;

        return pointDeltaX * pointDeltaX
                + pointDeltaY * pointDeltaY;
    }

    private NetworkBorder findBorderAt(
            final int screenX,
            final int screenY) {

        final double worldX =
                (screenX - panX) / zoom;

        final double worldY =
                (screenY - panY) / zoom;

        final double tolerance =
                8.0 / zoom;

        final NetworkBorder[] borders =
                borderSnapshot.getBordersUnsafe();

        for (int index = borders.length - 1;
                index >= 0;
                index--) {

            final NetworkBorder border =
                    borders[index];

            if (border.containsBorder(
                    worldX,
                    worldY,
                    tolerance)) {

                return border;
            }
        }

        return null;
    }

    private void rebuildBorderSnapshot() {
        final GraphStore store = graphStore;

        if (store == null || store.isEmpty()) {
            borderSnapshot =
                    NetworkBorderSnapshot.empty();
            return;
        }

        final LayoutState state =
                store.getLayoutState();

        synchronized (state.renderLock()) {
            borderSnapshot =
                    NetworkBorderSnapshot.build(
                            store,
                            state.visibleXUnsafe(),
                            state.visibleYUnsafe());
        }
    }

    private void showNodeDetails(final int nodeId) {
        final GraphStore store = graphStore;

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final byte effectiveTags =
                metadata.effectiveNodeTypeMask(
                        nodeId,
                        store.getNodeType(nodeId));

        final byte manualTags =
                metadata.getNodeManualTypeMask(nodeId);

        final StringBuilder text =
                new StringBuilder(2048);

        text.append("Device Information\n\n");

        text.append("IP: ")
                .append(metadata.getNodeIp(nodeId))
                .append('\n');

        text.append("Hostname: ")
                .append(emptyAsNotAvailable(
                        metadata.getNodeHostname(nodeId)))
                .append('\n');

        text.append("Device Type Tags: ")
                .append(DeviceTypes.tagNames(effectiveTags))
                .append('\n');

        text.append("Type Source: ")
                .append(manualTags == 0
                        ? "Automatic / imported graph data"
                        : "Analyst manual override")
                .append('\n');

        text.append("Degree: ")
                .append(store.getNodeDegree(nodeId))
                .append('\n');

        text.append("Connections: ")
                .append(store.getNodeConnectionCount(nodeId))
                .append('\n');

        text.append("Total Bytes: ")
                .append(store.getNodeTotalBytes(nodeId))
                .append('\n');

        text.append("\nNIC Memberships\n");

        appendMemberships(
                text,
                metadata.getNetworkTable(),
                nodeId);

        text.append("\nServer / Client Port Summary\n");

        appendPortSummary(
                text,
                store,
                nodeId);

        final JTextArea detailsArea =
                new JTextArea(
                        text.toString(),
                        22,
                        72);

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setCaretPosition(0);

        final JCheckBox useAutomatic =
                new JCheckBox(
                        "Use automatic / imported type tags",
                        manualTags == 0);

        final JCheckBox ot =
                new JCheckBox(
                        "OT",
                        DeviceTypes.has(effectiveTags, DeviceTypes.OT));

        final JCheckBox it =
                new JCheckBox(
                        "IT",
                        DeviceTypes.has(effectiveTags, DeviceTypes.IT));

        final JCheckBox ioc =
                new JCheckBox(
                        "IOC",
                        DeviceTypes.has(effectiveTags, DeviceTypes.IOC));

        final JCheckBox unk =
                new JCheckBox(
                        "UNK",
                        DeviceTypes.has(effectiveTags, DeviceTypes.UNK));

        final JCheckBox[] typeBoxes = {
                ot, it, ioc, unk
        };

        for (int index = 0;
                index < typeBoxes.length;
                index++) {

            typeBoxes[index].setEnabled(manualTags != 0);
        }

        useAutomatic.addActionListener(event -> {
            final boolean enabled =
                    !useAutomatic.isSelected();

            for (int index = 0;
                    index < typeBoxes.length;
                    index++) {

                typeBoxes[index].setEnabled(enabled);
            }
        });

        final JPanel typePanel =
                new JPanel(new GridLayout(0, 4, 8, 4));

        typePanel.add(ot);
        typePanel.add(it);
        typePanel.add(ioc);
        typePanel.add(unk);

        final JPanel classificationPanel =
                new JPanel(new BorderLayout(0, 4));

        classificationPanel.add(
                new JLabel("Device Type Tags"),
                BorderLayout.NORTH);

        classificationPanel.add(
                useAutomatic,
                BorderLayout.CENTER);

        classificationPanel.add(
                typePanel,
                BorderLayout.SOUTH);

        final JTextArea notesArea =
                new JTextArea(
                        metadata.getNodeAnalystComment(nodeId),
                        7,
                        72);

        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        final JPanel notesPanel =
                new JPanel(new BorderLayout(0, 4));

        notesPanel.add(
                new JLabel("Analyst Notes"),
                BorderLayout.NORTH);

        notesPanel.add(
                new JScrollPane(notesArea),
                BorderLayout.CENTER);

        final JPanel southPanel =
                new JPanel(new BorderLayout(0, 8));

        southPanel.add(
                classificationPanel,
                BorderLayout.NORTH);

        southPanel.add(
                notesPanel,
                BorderLayout.CENTER);

        final JPanel panel =
                new JPanel(new BorderLayout(0, 8));

        panel.add(
                new JScrollPane(detailsArea),
                BorderLayout.CENTER);

        panel.add(
                southPanel,
                BorderLayout.SOUTH);

        final int choice =
                JOptionPane.showConfirmDialog(
                        this,
                        panel,
                        "Device Information / Analyst Notes",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.INFORMATION_MESSAGE);

        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        metadata.setNodeAnalystComment(
                nodeId,
                notesArea.getText());

        if (useAutomatic.isSelected()) {
            metadata.clearNodeManualTypeMask(nodeId);

        } else {
            byte tags = 0;

            if (ot.isSelected()) {
                tags |= DeviceTypes.OT_MASK;
            }

            if (it.isSelected()) {
                tags |= DeviceTypes.IT_MASK;
            }

            if (ioc.isSelected()) {
                tags |= DeviceTypes.IOC_MASK;
            }

            if (unk.isSelected()) {
                tags |= DeviceTypes.UNK_MASK;
            }

            /*
             * A device must retain at least one tag.
             */
            if (tags == 0) {
                tags = DeviceTypes.UNK_MASK;
            }

            metadata.setNodeManualTypeMask(
                    nodeId,
                    tags);
        }

        repaint();
    }

    private void showEdgeDetails(final int edgeId) {
        final GraphStore store = graphStore;

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final int sourceNodeId =
                store.getEdgeSourceNodeId(edgeId);

        final int destinationNodeId =
                store.getEdgeTargetNodeId(edgeId);

        final StringBuilder text =
                new StringBuilder(2048);

        text.append("Edge Information\n\n");

        text.append("Source IP: ")
                .append(metadata.getNodeIp(sourceNodeId))
                .append('\n');

        text.append("Destination IP: ")
                .append(metadata.getNodeIp(destinationNodeId))
                .append('\n');

        text.append("Source Device Type Tags: ")
                .append(DeviceTypes.tagNames(
                        metadata.effectiveNodeTypeMask(
                                sourceNodeId,
                                store.getNodeType(sourceNodeId))))
                .append('\n');

        text.append("Destination Device Type Tags: ")
                .append(DeviceTypes.tagNames(
                        metadata.effectiveNodeTypeMask(
                                destinationNodeId,
                                store.getNodeType(destinationNodeId))))
                .append('\n');

        text.append("Aggregated Connection Count: ")
                .append(store.getEdgeConnectionCount(edgeId))
                .append('\n');

        text.append("Successful Connections: ")
                .append(
                        store.getEdgeSuccessfulConnectionCount(
                                edgeId))
                .append('\n');

        text.append("Total Bytes: ")
                .append(store.getEdgeTotalBytes(edgeId))
                .append('\n');

        text.append("Total Packets: ")
                .append(store.getEdgeTotalPackets(edgeId))
                .append('\n');

        text.append("Maximum Duration (seconds): ")
                .append(store.getEdgeMaxDurationSeconds(edgeId))
                .append('\n');

        text.append("Maximum Priority Score: ")
                .append(store.getEdgeMaxPriorityScore(edgeId))
                .append('\n');

        text.append("\nConnections by Destination Port\n");

        final TreeMap<Integer, long[]> destinationPorts =
                new TreeMap<Integer, long[]>();

        for (int portIndex =
                store.getEdgeFirstPort(edgeId);
                portIndex >= 0;
                portIndex =
                        store.getPortNext(portIndex)) {

            final int destinationPort =
                    store.getPortNumber(portIndex);

            long[] totals =
                    destinationPorts.get(destinationPort);

            if (totals == null) {
                totals = new long[2];
                destinationPorts.put(
                        destinationPort,
                        totals);
            }

            totals[0] +=
                    store.getPortConnectionCount(portIndex);

            totals[1] +=
                    store.getPortSuccessfulConnectionCount(
                            portIndex);
        }

        if (destinationPorts.isEmpty()) {
            text.append(
                    "  (no destination ports recorded)\n");

        } else {
            for (Map.Entry<Integer, long[]> entry
                    : destinationPorts.entrySet()) {

                final long[] totals =
                        entry.getValue();

                text.append("  Destination Port ")
                        .append(entry.getKey())
                        .append(": count=")
                        .append(totals[0])
                        .append(", successful=")
                        .append(totals[1])
                        .append('\n');
            }
        }

        showEditableDetails(
                "Edge Information / Analyst Notes",
                text.toString(),
                store.getEdgeAnalystComment(edgeId),
                new NotesSaver() {
                    @Override
                    public void save(final String notes) {
                        store.setEdgeAnalystComment(
                                edgeId,
                                notes);
                    }
                });
    }

    private void showEditableDetails(
            final String title,
            final String details,
            final String existingNotes,
            final NotesSaver saver) {

        final JTextArea detailsArea =
                new JTextArea(
                        details == null ? "" : details,
                        22,
                        72);

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setCaretPosition(0);

        final JTextArea notesArea =
                new JTextArea(
                        existingNotes == null
                                ? ""
                                : existingNotes,
                        7,
                        72);

        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        final JPanel notesPanel =
                new JPanel(new BorderLayout(0, 4));

        notesPanel.add(
                new JLabel("Analyst Notes"),
                BorderLayout.NORTH);

        notesPanel.add(
                new JScrollPane(notesArea),
                BorderLayout.CENTER);

        final JPanel panel =
                new JPanel(new BorderLayout(0, 8));

        panel.add(
                new JScrollPane(detailsArea),
                BorderLayout.CENTER);

        panel.add(notesPanel, BorderLayout.SOUTH);

        final int choice =
                JOptionPane.showConfirmDialog(
                        this,
                        panel,
                        title,
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.INFORMATION_MESSAGE);

        if (choice == JOptionPane.OK_OPTION
                && saver != null) {

            saver.save(notesArea.getText());
        }
    }

    private interface NotesSaver {
        void save(String notes);
    }

    private void showBorderDetails(
            final NetworkBorder border) {

        final GraphMetadata metadata =
                graphStore.getGraphMetadata();

        final GraphMetadata.NetworkTable networks =
                metadata.getNetworkTable();

        final StringBuilder text =
                new StringBuilder(1536);

        if (border.isUndetermined()) {
            text.append("Network: Undetermined Network\n\n");

            text.append(
                    "Visible devices without a NIC/CIDR membership: ");

            text.append(border.getMemberCount())
                    .append('\n');

            text.append("\nSubnet Names\n");
            text.append("  (not applicable)\n");

            NetworkTrafficSummary.append(
                    text,
                    graphStore,
                    -1,
                    true);

            showEditableDetails(
                    "Network Border / Analyst Notes",
                    text.toString(),
                    metadata.getUndeterminedNetworkAnalystComment(),
                    new NotesSaver() {
                        @Override
                        public void save(final String notes) {
                            metadata.setUndeterminedNetworkAnalystComment(
                                    notes);
                        }
                    });

            return;
        }

        final int networkId =
                border.getNetworkId();

        text.append("Branch: ")
                .append(networks.getBranch(networkId))
                .append('\n');

        text.append("CIDR: ")
                .append(networks.getCidr(networkId))
                .append('\n');

        text.append("Network Name: ")
                .append(networks.getName(networkId))
                .append('\n');

        text.append("Prefix Length: ")
                .append(networks.getPrefixLength(networkId))
                .append('\n');

        text.append("Visible Member Devices: ")
                .append(border.getMemberCount())
                .append('\n');

        text.append("\nSubnet Names\n");

        appendSubnetNames(
                text,
                networks,
                NetworkMembershipUtil.presentNetworkMask(
                        networks),
                networkId);

        NetworkTrafficSummary.append(
                text,
                graphStore,
                networkId,
                false);

        showEditableDetails(
                "Network Border / Analyst Notes",
                text.toString(),
                metadata.getNetworkAnalystComment(networkId),
                new NotesSaver() {
                    @Override
                    public void save(final String notes) {
                        metadata.setNetworkAnalystComment(
                                networkId,
                                notes);
                    }
                });
    }

    private static void appendSubnetNames(
            final StringBuilder text,
            final GraphMetadata.NetworkTable networks,
            final boolean[] presentNetworkMask,
            final int parentNetworkId) {

        boolean found = false;

        final String parentCidr =
                networks.getCidr(parentNetworkId);

        for (int networkId = 0;
                networkId < networks.networkCount();
                networkId++) {

            /*
             * Include only identified child networks that are represented by
             * at least one source or destination node in the loaded graph.
             * Do not display reference-only NIC networks with no graph data.
             */
            if (networkId >= presentNetworkMask.length
                    || !presentNetworkMask[networkId]) {

                continue;
            }

            if (!CidrUtil.isStrictSubnet(
                    parentCidr,
                    networks.getCidr(networkId))) {

                continue;
            }

            final String name =
                    networks.getName(networkId);

            text.append("  - ")
                    .append(
                            name == null || name.trim().isEmpty()
                                    ? networks.getCidr(networkId)
                                    : name)
                    .append(" (")
                    .append(networks.getCidr(networkId))
                    .append(")\n");

            found = true;
        }

        if (!found) {
            text.append(
                    "  (no identified child subnets are represented "
                            + "in the loaded connection data)\n");
        }
    }

    private static void appendMemberships(
            final StringBuilder text,
            final GraphMetadata.NetworkTable networks,
            final int nodeId) {

        final int start =
                networks.getMembershipStart(nodeId);

        final int end =
                networks.getMembershipEnd(nodeId);

        if (start == end) {
            text.append(
                    "  No NIC membership information is assigned.\n");

            return;
        }

        for (int index = start;
                index < end;
                index++) {

            final int networkId =
                    networks.getMembershipNetworkId(index);

            text.append("  - ")
                    .append(networks.getBranch(networkId))
                    .append(" | ")
                    .append(networks.getCidr(networkId))
                    .append(" | ")
                    .append(networks.getName(networkId))
                    .append('\n');
        }
    }

    private static void appendPortSummary(
            final StringBuilder text,
            final GraphStore store,
            final int nodeId) {

        final TreeMap<Integer, long[]> serverPorts =
                new TreeMap<Integer, long[]>();

        final TreeMap<Integer, long[]> clientPorts =
                new TreeMap<Integer, long[]>();

        final int start =
                store.getIncidentEdgeStart(nodeId);

        final int end =
                store.getIncidentEdgeEnd(nodeId);

        for (int position = start;
                position < end;
                position++) {

            final int edgeId =
                    store.getIncidentEdgeId(position);

            final boolean isServer =
                    store.getEdgeTargetNodeId(edgeId)
                            == nodeId;

            final boolean isClient =
                    store.getEdgeSourceNodeId(edgeId)
                            == nodeId;

            for (int portIndex =
                    store.getEdgeFirstPort(edgeId);
                    portIndex >= 0;
                    portIndex =
                            store.getPortNext(portIndex)) {

                final int port =
                        store.getPortNumber(portIndex);

                final TreeMap<Integer, long[]> destination =
                        isServer
                                ? serverPorts
                                : clientPorts;

                final long[] totals =
                        totalsFor(destination, port);

                if (isServer || isClient) {
                    totals[0] +=
                            store.getPortConnectionCount(portIndex);

                    totals[1] +=
                            store.getPortSuccessfulConnectionCount(
                                    portIndex);

                    totals[2] +=
                            store.getPortTotalBytes(portIndex);
                }
            }
        }

        appendPortTotals(
                text,
                "Server Ports",
                serverPorts);

        appendPortTotals(
                text,
                "Client Ports",
                clientPorts);
    }

    private static long[] totalsFor(
            final TreeMap<Integer, long[]> map,
            final int port) {

        long[] totals = map.get(port);

        if (totals == null) {
            totals = new long[3];
            map.put(port, totals);
        }

        return totals;
    }

    private static void appendPortTotals(
            final StringBuilder text,
            final String heading,
            final TreeMap<Integer, long[]> totals) {

        text.append(heading)
                .append('\n');

        if (totals.isEmpty()) {
            text.append("  (none)\n");
            return;
        }

        for (Map.Entry<Integer, long[]> entry
                : totals.entrySet()) {

            final long[] values =
                    entry.getValue();

            text.append("  Port ")
                    .append(entry.getKey())
                    .append(": attempted=")
                    .append(values[0])
                    .append(", successful=")
                    .append(values[1])
                    .append(", bytes=")
                    .append(values[2])
                    .append('\n');
        }
    }

    private static String emptyAsNotAvailable(
            final String value) {

        return value == null
                || value.trim().isEmpty()
                        ? "(not available)"
                        : value;
    }
}