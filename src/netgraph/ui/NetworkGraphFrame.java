package netgraph.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import netgraph.binary.AdditionalConnectionBinaryImporter;
import netgraph.csv.ConnectionCsvImporter;
import netgraph.csv.ConnectionImportResult;
import netgraph.csv.GraphCsvImporter;
import netgraph.csv.GraphCsvImportResult;
import netgraph.csv.UniqueIpCsvExporter;
import netgraph.graph.GraphStore;
import netgraph.interaction.GraphPanel;
import netgraph.nic.NetworkReferenceIndex;
import netgraph.util.GraphAnalystStateCopier;
import netgraph.util.RejectedRecordStore;

/**
 * Main Swing application frame and workflow coordinator.
 *
 * Owns file-selection dialogs, graph-import workflows, export actions,
 * display-filter-panel integration, and application lifecycle cleanup.
 */
public final class NetworkGraphFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private final GraphPanel graphPanel;

    /*
     * Persistent right-side control panel. It publishes immutable filter
     * snapshots directly to GraphPanel.
     */
    private final GraphFilterPanel graphFilterPanel;

    private transient RejectedRecordStore rejectedRecords;
    private transient NetworkReferenceIndex nicIndex;

    public NetworkGraphFrame() {
        super("Packed Network Graph Explorer");

        graphPanel = new GraphPanel();

        graphFilterPanel =
                new GraphFilterPanel(
                        graphPanel::setDisplayFilter);

        /*
         * Initializes the panel's destination-port selector and sends the
         * default "show everything" filter to the graph panel.
         */
        graphFilterPanel.setGraph(GraphStore.empty());

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        setMinimumSize(
                new Dimension(1100, 720));

        setSize(1500, 940);
        setLocationRelativeTo(null);

        /*
         * BorderLayout.CENTER receives the graph; EAST receives the persistent
         * filtering/display-control side panel.
         */
        add(graphPanel, BorderLayout.CENTER);
        add(graphFilterPanel, BorderLayout.EAST);
        add(buildStatusPanel(), BorderLayout.SOUTH);

        setJMenuBar(new AppMenuBar(this));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(
                    final WindowEvent event) {

                graphPanel.shutdown();
                closeRejectedRecords();
            }
        });
    }

    /**
     * Imports a standard connection CSV as a fresh graph.
     */
    public void importConnectionData() {
        final File file = chooseOpenCsv(
                "Import Connection Data",
                "Connection CSV Files");

        if (file == null) {
            return;
        }

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.WAIT_CURSOR));

        final SwingWorker<ConnectionImportResult, Void> worker =
                new SwingWorker<ConnectionImportResult, Void>() {
                    @Override
                    protected ConnectionImportResult doInBackground()
                            throws Exception {

                        return ConnectionCsvImporter.importFile(
                                file.toPath());
                    }

                    @Override
                    protected void done() {
                        try {
                            final ConnectionImportResult result =
                                    get();

                            final GraphStore importedStore =
                                    result.getGraphStore();

                            /*
                             * Apply any previously loaded NIC/CIDR reference
                             * data before presenting the graph.
                             */
                            if (nicIndex != null) {
                                nicIndex.applyTo(importedStore);
                            }

                            closeRejectedRecords();

                            rejectedRecords =
                                    result.getRejectedRecords();

                            installGraph(importedStore);
                            graphPanel.relayout(true);

                            JOptionPane.showMessageDialog(
                                    NetworkGraphFrame.this,
                                    result.buildSummary(),
                                    "Connection Import Complete",
                                    result.getRejectedRecords()
                                                    .getRejectedCount() > 0
                                            ? JOptionPane.WARNING_MESSAGE
                                            : JOptionPane.INFORMATION_MESSAGE);

                        } catch (Exception exception) {
                            showError(
                                    "Connection import failed.",
                                    exception);

                        } finally {
                            setCursor(
                                    Cursor.getDefaultCursor());
                        }
                    }
                };

        worker.execute();
    }

    /**
     * Imports additional NGC connection records and merges them with the
     * currently loaded graph while retaining analyst-entered metadata.
     */
    public void importAdditionalConnectionData() {
        final GraphStore existingStore =
                graphPanel.getGraphStore();

        if (existingStore == null || existingStore.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Import primary connection data before importing "
                            + "additional connection data.",
                    "Import Additional Network Connection Data (.ngc)",
                    JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        final File file = chooseOpenNgc(
                "Import Additional Network Connection Data (.ngc)",
                "Additional Network Connection NGC Files");

        if (file == null) {
            return;
        }

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.WAIT_CURSOR));

        final SwingWorker<ConnectionImportResult, Void> worker =
                new SwingWorker<ConnectionImportResult, Void>() {
                    @Override
                    protected ConnectionImportResult doInBackground()
                            throws Exception {

                        return AdditionalConnectionBinaryImporter.importFile(
                                file.toPath(),
                                existingStore);
                    }

                    @Override
                    protected void done() {
                        ConnectionImportResult result = null;

                        try {
                            result = get();

                            /*
                             * Do not allow a completed background import to
                             * overwrite a graph installed by another workflow
                             * after this import began.
                             */
                            if (graphPanel.getGraphStore() != existingStore) {
                                JOptionPane.showMessageDialog(
                                        NetworkGraphFrame.this,
                                        "The graph changed while the additional "
                                                + "NGC file was importing. No "
                                                + "merge was installed; repeat "
                                                + "the import for the current graph.",
                                        "Additional NGC Import Not Applied",
                                        JOptionPane.WARNING_MESSAGE);
                                return;
                            }

                            final GraphStore mergedStore =
                                    result.getGraphStore();

                            /*
                             * Reapply NIC/CIDR memberships so newly introduced
                             * IPv4 nodes are placed in applicable borders.
                             */
                            if (nicIndex != null) {
                                nicIndex.applyToAfterAppend(
                                        mergedStore,
                                        existingStore);
                            }

                            /*
                             * Reapply editable analyst state after the packed
                             * topology was rebuilt by the additional import.
                             */
                            GraphAnalystStateCopier.copy(
                                    existingStore,
                                    mergedStore);

                            final int acceptedRecords =
                                    result.getAcceptedRecords();

                            installGraphPreservingClusterState(mergedStore);
                            graphPanel.relayout(false);

                            closeRejectedRecords();
                            rejectedRecords =
                                    result.getRejectedRecords();

                            /*
                             * The frame now owns the temporary rejection spool.
                             * It must remain open for Export Rejected Records.
                             */
                            result = null;

                            JOptionPane.showMessageDialog(
                                    NetworkGraphFrame.this,
                                    buildAdditionalImportSummary(
                                            acceptedRecords,
                                            rejectedRecords,
                                            mergedStore),
                                    "Additional NGC Connection Import Complete",
                                    rejectedRecords.getRejectedCount() > 0
                                            ? JOptionPane.WARNING_MESSAGE
                                            : JOptionPane.INFORMATION_MESSAGE);

                        } catch (Exception exception) {
                            showError(
                                    "Additional NGC connection import failed.",
                                    exception);

                        } finally {
                            if (result != null) {
                                result.getRejectedRecords().close();
                            }

                            setCursor(
                                    Cursor.getDefaultCursor());
                        }
                    }
                };

        worker.execute();
    }

    /**
     * Builds a new visualized graph from an exported Graph CSV file.
     *
     * The Graph CSV importer restores nodes, edges, port-pair aggregates,
     * analyst notes, device types, and embedded network records.
     */
    public void buildNetgraphFromGraphCsv() {
        final File file = chooseOpenCsv(
                "Build Netgraph from Graph CSV",
                "Graph CSV Files");

        if (file == null) {
            return;
        }

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.WAIT_CURSOR));

        final SwingWorker<GraphCsvImportResult, Void> worker =
                new SwingWorker<GraphCsvImportResult, Void>() {
                    @Override
                    protected GraphCsvImportResult doInBackground()
                            throws Exception {

                        return GraphCsvImporter.importFile(
                                file.toPath());
                    }

                    @Override
                    protected void done() {
                        try {
                            final GraphCsvImportResult result =
                                    get();

                            closeRejectedRecords();

                            rejectedRecords =
                                    result.getRejectedRecords();

                            /*
                             * Persist the network reference index restored
                             * from NETWORK records in the Graph CSV. This
                             * allows future additional-data imports to retain
                             * network membership behavior.
                             */
                            nicIndex =
                                    result.getNetworkReferenceIndex();

                            installGraph(
                                    result.getGraphStore());

                            graphPanel.relayout(true);

                            JOptionPane.showMessageDialog(
                                    NetworkGraphFrame.this,
                                    result.buildSummary(),
                                    "Graph CSV Build Complete",
                                    result.getRejectedRecords()
                                                    .getRejectedCount() > 0
                                            ? JOptionPane.WARNING_MESSAGE
                                            : JOptionPane.INFORMATION_MESSAGE);

                        } catch (Exception exception) {
                            showError(
                                    "Graph CSV build failed.",
                                    exception);

                        } finally {
                            setCursor(
                                    Cursor.getDefaultCursor());
                        }
                    }
                };

        worker.execute();
    }

    /**
     * Imports NIC/CIDR reference data and applies memberships to the loaded
     * graph, if any.
     */
    public void importNicReferenceData() {
        final File file = chooseOpenCsv(
                "Import NIC Reference Data",
                "NIC Reference CSV Files");

        if (file == null) {
            return;
        }

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.WAIT_CURSOR));

        final SwingWorker<NetworkReferenceIndex, Void> worker =
                new SwingWorker<NetworkReferenceIndex, Void>() {
                    @Override
                    protected NetworkReferenceIndex doInBackground()
                            throws Exception {

                        return NetworkReferenceIndex.load(
                                file.toPath());
                    }

                    @Override
                    protected void done() {
                        try {
                            nicIndex = get();

                            final GraphStore store =
                                    graphPanel.getGraphStore();

                            if (store != null && !store.isEmpty()) {
                                nicIndex.applyTo(store);

                                /*
                                 * Reinstall to refresh side-panel destination
                                 * ports consistently with all other graph
                                 * replacement/update workflows.
                                 */
                                installGraph(store);
                                graphPanel.relayout(true);
                            }

                            JOptionPane.showMessageDialog(
                                    NetworkGraphFrame.this,
                                    "NIC reference data loaded.\n\n"
                                            + "Valid network records: "
                                            + nicIndex.getNetworkCount()
                                            + "\n"
                                            + "Rejected NIC records: "
                                            + nicIndex.getRejectedCount()
                                            + "\n\n"
                                            + "Network borders use CIDR, "
                                            + "branch, and network-name "
                                            + "metadata.",
                                    "NIC Reference Import Complete",
                                    nicIndex.getRejectedCount() > 0
                                            ? JOptionPane.WARNING_MESSAGE
                                            : JOptionPane.INFORMATION_MESSAGE);

                        } catch (Exception exception) {
                            showError(
                                    "NIC reference import failed.",
                                    exception);

                        } finally {
                            setCursor(
                                    Cursor.getDefaultCursor());
                        }
                    }
                };

        worker.execute();
    }

    public void exportPng() {
        final File target = chooseSavePng();

        if (target == null) {
            return;
        }

        try {
            graphPanel.exportPng(target);

            JOptionPane.showMessageDialog(
                    this,
                    "PNG exported to:\n"
                            + target.getAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException exception) {
            showError(
                    "PNG export failed.",
                    exception);
        }
    }

    public void exportGraphAsCsv() {
        final GraphStore store =
                graphPanel.getGraphStore();

        if (store == null || store.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "There is no graph data available to export.",
                    "Export Graph as CSV",
                    JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        final File target = chooseSaveCsv(
                "Export Graph as CSV");

        if (target == null) {
            return;
        }

        try {
            graphPanel.exportGraphCsv(target);

            JOptionPane.showMessageDialog(
                    this,
                    "Graph CSV exported to:\n"
                            + target.getAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException exception) {
            showError(
                    "Graph CSV export failed.",
                    exception);
        }
    }

    public void exportUniqueIpAddresses() {
        final GraphStore store =
                graphPanel.getGraphStore();

        if (store == null || store.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "There is no graph data available to export.",
                    "Export Unique IP Addresses",
                    JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        final File target = chooseSaveCsv(
                "Export Unique IP Addresses");

        if (target == null) {
            return;
        }

        try {
            UniqueIpCsvExporter.export(
                    target.toPath(),
                    store);

            JOptionPane.showMessageDialog(
                    this,
                    "Unique IP address CSV exported to:\n"
                            + target.getAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException exception) {
            showError(
                    "Unique IP address CSV export failed.",
                    exception);
        }
    }

    public void exportRejectedRecords() {
        if (rejectedRecords == null
                || rejectedRecords.getRejectedCount() == 0) {

            JOptionPane.showMessageDialog(
                    this,
                    "No rejected connection records are available.",
                    "Rejected Records",
                    JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        final File target = chooseSaveCsv(
                "Export Rejected Records");

        if (target == null) {
            return;
        }

        try {
            rejectedRecords.exportTo(target.toPath());

            JOptionPane.showMessageDialog(
                    this,
                    "Rejected records exported to:\n"
                            + target.getAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException exception) {
            showError(
                    "Rejected-record export failed.",
                    exception);
        }
    }

    public void clearGraph() {
        installGraph(GraphStore.empty());
        closeRejectedRecords();
    }

    public void fitGraphToWindow() {
        graphPanel.fitToWindow();
    }

    public void relayoutGraph() {
        graphPanel.relayout(true);
    }

    public GraphPanel getGraphPanel() {
        return graphPanel;
    }

    /**
     * One controlled graph-install path. This prevents the graph panel and
     * side panel from becoming stale relative to one another.
     */
    private void installGraph(final GraphStore store) {
        final GraphStore safeStore =
                store == null
                        ? GraphStore.empty()
                        : store;

        graphPanel.setGraph(safeStore);

        /*
         * Repopulates destination-port choices and resets them to "all
         * selected" for the newly installed graph.
         */
        graphFilterPanel.setGraph(safeStore);

        revalidate();
        repaint();
    }

/**
 * Installs a graph created by an additional NGC append while retaining the
 * analyst's current hierarchical cluster expansion and analyst-defined
 * cluster definitions.
 *
 * Use this method only for an in-place additional-NGC append. Fresh graph
 * imports must continue to use installGraph(...), which clears stale
 * session-only cluster view state.
 */
    private void installGraphPreservingClusterState(
            final GraphStore store) {

        final GraphStore safeStore =
                store == null
                        ? GraphStore.empty()
                        : store;

        graphPanel.setGraphPreservingClusterState(safeStore);
        graphFilterPanel.setGraph(safeStore);

        revalidate();
        repaint();
    }

    private JPanel buildStatusPanel() {
        final JPanel panel =
                new JPanel(new BorderLayout());

        panel.setBorder(
                BorderFactory.createEmptyBorder(
                        4,
                        8,
                        4,
                        8));

        final JLabel label = new JLabel(
                "Use the right-side panel to filter device types, "
                        + "destination ports, transfer state, "
                        + "connection outcome, devices, and edges.");

        panel.add(label, BorderLayout.WEST);

        final JButton relayoutButton =
                new JButton("Re-layout");

        relayoutButton.addActionListener(
                event -> graphPanel.relayout(true));

        panel.add(relayoutButton, BorderLayout.EAST);

        return panel;
    }

    private File chooseOpenCsv(
            final String title,
            final String description) {

        final JFileChooser chooser =
                new JFileChooser();

        chooser.setDialogTitle(title);

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        description,
                        "csv"));

        return chooser.showOpenDialog(this)
                == JFileChooser.APPROVE_OPTION
                        ? chooser.getSelectedFile()
                        : null;
    }

    private File chooseOpenNgc(
            final String title,
            final String description) {

        final JFileChooser chooser =
                new JFileChooser();

        chooser.setDialogTitle(title);

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        description,
                        "ngc"));

        return chooser.showOpenDialog(this)
                == JFileChooser.APPROVE_OPTION
                        ? chooser.getSelectedFile()
                        : null;
    }

    private File chooseSaveCsv(
            final String title) {

        final JFileChooser chooser =
                new JFileChooser();

        chooser.setDialogTitle(title);

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        "CSV Files",
                        "csv"));

        if (chooser.showSaveDialog(this)
                != JFileChooser.APPROVE_OPTION) {

            return null;
        }

        return ensureExtension(
                chooser.getSelectedFile(),
                ".csv");
    }

    private File chooseSavePng() {
        final JFileChooser chooser =
                new JFileChooser();

        chooser.setDialogTitle("Export Graph as PNG");

        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        "PNG Images",
                        "png"));

        if (chooser.showSaveDialog(this)
                != JFileChooser.APPROVE_OPTION) {

            return null;
        }

        return ensureExtension(
                chooser.getSelectedFile(),
                ".png");
    }

    private static File ensureExtension(
            final File file,
            final String extension) {

        if (file == null) {
            throw new IllegalArgumentException(
                    "File is required.");
        }

        final String name =
                file.getName()
                        .toLowerCase(Locale.ROOT);

        if (name.endsWith(extension)) {
            return file;
        }

        return new File(
                file.getParentFile(),
                file.getName() + extension);
    }

    private static String buildAdditionalImportSummary(
            final int acceptedRecords,
            final RejectedRecordStore rejected,
            final GraphStore graphStore) {

        return "Additional NGC connection-data import complete.\n\n"
                + "Accepted additional records: "
                + acceptedRecords
                + "\n"
                + "Rejected additional records: "
                + rejected.getRejectedCount()
                + "\n"
                + "Total nodes: "
                + graphStore.getNodeCount()
                + "\n"
                + "Total aggregated directed edges: "
                + graphStore.getEdgeCount();
    }

    private void closeRejectedRecords() {
        if (rejectedRecords != null) {
            rejectedRecords.close();
            rejectedRecords = null;
        }
    }

    private void showError(
            final String message,
            final Exception exception) {

        final String detail =
                exception == null
                        ? ""
                        : exception.getMessage();

        JOptionPane.showMessageDialog(
                this,
                message
                        + (detail == null || detail.trim().isEmpty()
                                ? ""
                                : "\n\n" + detail),
                "Application Error",
                JOptionPane.ERROR_MESSAGE);
    }
}