package netgraph.ui;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JSeparator;

/**
 * Main application menu bar.
 */
public final class AppMenuBar extends JMenuBar {
    private static final long serialVersionUID = 1L;

    public AppMenuBar(final NetworkGraphFrame frame) {
        if (frame == null) {
            throw new IllegalArgumentException(
                    "Application frame is required.");
        }

        add(buildFileMenu(frame));
        add(buildViewMenu(frame));
        add(buildHelpMenu(frame));
    }

    private static JMenu buildFileMenu(
            final NetworkGraphFrame frame) {

        final JMenu fileMenu = new JMenu("File");

        final JMenuItem importConnections =
                new JMenuItem(
                        "Import Connection Data (.csv)");

        importConnections.addActionListener(
                event -> frame.importConnectionData());

        final JMenuItem buildGraphFromGraphCsv =
                new JMenuItem(
                        "Build Netgraph from Graph CSV (.csv)");

        buildGraphFromGraphCsv.addActionListener(
                event -> frame.buildNetgraphFromGraphCsv());

        final JMenuItem importAdditionalConnections =
                new JMenuItem(
                        "Import Additional Network Connection Data (.ngc)");

        importAdditionalConnections.addActionListener(
                event -> frame.importAdditionalConnectionData());

        final JMenuItem importNic =
                new JMenuItem(
                        "Import NIC Reference Data (.csv)");

        importNic.addActionListener(
                event -> frame.importNicReferenceData());

        final JMenuItem exportPng =
                new JMenuItem("Export Graph as PNG");

        exportPng.addActionListener(
                event -> frame.exportPng());

        final JMenuItem exportGraphCsv =
                new JMenuItem("Export Graph as CSV");

        exportGraphCsv.addActionListener(
                event -> frame.exportGraphAsCsv());

        final JMenuItem exportUniqueIps =
                new JMenuItem(
                        "Export Unique IP Addresses (.csv)");

        exportUniqueIps.addActionListener(
                event -> frame.exportUniqueIpAddresses());

        final JMenuItem exportRejected =
                new JMenuItem(
                        "Export Rejected Records (.csv)");

        exportRejected.addActionListener(
                event -> frame.exportRejectedRecords());

        final JMenuItem clear =
                new JMenuItem("Clear Graph");

        clear.addActionListener(
                event -> frame.clearGraph());

        final JMenuItem exit =
                new JMenuItem("Exit");

        exit.addActionListener(
                event -> frame.dispose());

        fileMenu.add(importConnections);
        fileMenu.add(buildGraphFromGraphCsv);
        fileMenu.add(importAdditionalConnections);
        fileMenu.add(importNic);

        fileMenu.add(new JSeparator());

        fileMenu.add(exportPng);
        fileMenu.add(exportGraphCsv);
        fileMenu.add(exportUniqueIps);
        fileMenu.add(exportRejected);

        fileMenu.add(new JSeparator());

        fileMenu.add(clear);
        fileMenu.add(exit);

        return fileMenu;
    }

    private static JMenu buildViewMenu(
            final NetworkGraphFrame frame) {

        final JMenu viewMenu = new JMenu("View");

        final JMenuItem fit =
                new JMenuItem("Fit Graph to Window");

        fit.addActionListener(
                event -> frame.fitGraphToWindow());

        final JMenuItem relayout =
                new JMenuItem("Re-layout Graph");

        relayout.addActionListener(
                event -> frame.relayoutGraph());

        viewMenu.add(fit);
        viewMenu.add(relayout);

        return viewMenu;
    }

    private static JMenu buildHelpMenu(
            final NetworkGraphFrame frame) {

        final JMenu helpMenu = new JMenu("Help");

        final JMenuItem about =
                new JMenuItem("About");

        about.addActionListener(event ->
                JOptionPane.showMessageDialog(
                        frame,
                        "Packed Network Graph Explorer\n\n"
                                + "Primitive-array topology\n"
                                + "Immediate-mode Swing rendering\n"
                                + "Background layout\n"
                                + "Spatial-index viewport culling\n"
                                + "Display filters and visualization toggles\n"
                                + "CSV rejected-record capture\n"
                                + "NIC/CIDR network borders",
                        "About",
                        JOptionPane.INFORMATION_MESSAGE));

        helpMenu.add(about);

        return helpMenu;
    }
}