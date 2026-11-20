package netgraph.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.BitSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import netgraph.display.GraphDisplayFilter;
import netgraph.graph.GraphStore;
import netgraph.util.DeviceTypes;

/**
 * Persistent right-side display and visualization control panel.
 */
public final class GraphFilterPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private transient Consumer<GraphDisplayFilter> filterConsumer;

    private final JCheckBox ot =
            new JCheckBox("OT", true);

    private final JCheckBox it =
            new JCheckBox("IT", true);

    private final JCheckBox ioc =
            new JCheckBox("IOC", true);

    private final JCheckBox unk =
            new JCheckBox("UNK", true);

    private final JCheckBox dataTransfer =
            new JCheckBox("Data Transfer", true);

    private final JCheckBox noDataTransfer =
            new JCheckBox("No Data Transfer", true);

    private final JCheckBox successfulConnections =
            new JCheckBox("Successful Connections", true);

    private final JCheckBox attemptedConnections =
            new JCheckBox("Attempted Connections", true);

    private final JCheckBox showDevices =
            new JCheckBox("Devices", true);

    private final JCheckBox showEdges =
            new JCheckBox("Edges", true);

    private final JButton portsButton =
            new JButton();

    private final JTextField portSearch =
            new JTextField();

    private final DefaultListModel<Integer> visiblePortModel =
            new DefaultListModel<Integer>();

    private final JList<Integer> portList =
            new JList<Integer>(visiblePortModel);

    private final TreeSet<Integer> allPorts =
            new TreeSet<Integer>();

    private final TreeSet<Integer> selectedPorts =
            new TreeSet<Integer>();

    private boolean updatingPorts;

    public GraphFilterPanel(
        final Consumer<GraphDisplayFilter> filterConsumer) {

        if (filterConsumer == null) {
            throw new IllegalArgumentException(
                    "Filter consumer is required.");
        }

        this.filterConsumer = filterConsumer;

        setLayout(new BorderLayout());
        setBorder(
                BorderFactory.createEmptyBorder(
                        8,
                        8,
                        8,
                        8));

        setPreferredSize(new Dimension(292, 400));
        setMinimumSize(new Dimension(250, 220));

        final JPanel content = new JPanel();
        content.setLayout(
                new BoxLayout(
                        content,
                        BoxLayout.Y_AXIS));

        content.add(buildDisplayFilters());
        content.add(Box.createVerticalStrut(8));
        content.add(buildVisualToggles());
        content.add(Box.createVerticalGlue());

        add(
                new JScrollPane(
                        content,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
                BorderLayout.CENTER);

        installListeners();
        rebuildVisiblePortModel();
        publishFilter();
    }

    /**
     * Reset destination-port selections when a new graph replaces the current
     * graph. Every discovered destination port starts selected.
     */
    public void setGraph(final GraphStore store) {
        allPorts.clear();
        selectedPorts.clear();

        if (store != null && !store.isEmpty()) {
            for (int edgeId = 0;
                    edgeId < store.getEdgeCount();
                    edgeId++) {

                for (int portIndex =
                        store.getEdgeFirstPort(edgeId);
                        portIndex >= 0;
                        portIndex =
                                store.getPortNext(portIndex)) {

                    allPorts.add(
                            Integer.valueOf(
                                    store.getPortNumber(portIndex)));
                }
            }

            selectedPorts.addAll(allPorts);
        }

        portSearch.setText("");
        rebuildVisiblePortModel();
        updatePortButtonText();
        publishFilter();
    }

    private JPanel buildDisplayFilters() {
        final JPanel panel = new JPanel();
        panel.setLayout(
                new BoxLayout(
                        panel,
                        BoxLayout.Y_AXIS));

        panel.setBorder(
                BorderFactory.createTitledBorder(
                        "Display Filters"));

        final JPanel types =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                4,
                                0));

        types.add(ot);
        types.add(it);
        types.add(ioc);
        types.add(unk);

        panel.add(labelPanel("Device Type"));
        panel.add(types);
        panel.add(Box.createVerticalStrut(6));

        panel.add(labelPanel("Destination Ports"));
        panel.add(buildPortSelector());
        panel.add(Box.createVerticalStrut(6));

        final JPanel transferPanel =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                4,
                                0));

        transferPanel.add(dataTransfer);
        transferPanel.add(noDataTransfer);

        panel.add(labelPanel("Data Transfers"));
        panel.add(transferPanel);
        panel.add(Box.createVerticalStrut(6));

        final JPanel connectionPanel =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                4,
                                0));

        connectionPanel.add(successfulConnections);
        connectionPanel.add(attemptedConnections);

        panel.add(labelPanel("Connection Success"));
        panel.add(connectionPanel);

        return panel;
    }

    private JPanel buildVisualToggles() {
        final JPanel panel = new JPanel();
        panel.setLayout(
                new BoxLayout(
                        panel,
                        BoxLayout.Y_AXIS));

        panel.setBorder(
                BorderFactory.createTitledBorder(
                        "Visual Toggles"));

        panel.add(showDevices);
        panel.add(showEdges);

        return panel;
    }

    private JPanel buildPortSelector() {
        portsButton.setHorizontalAlignment(
                SwingConstants.LEFT);

        updatePortButtonText();

        portsButton.addActionListener(event ->
                showPortPopup());

        final JPanel panel =
                new JPanel(new BorderLayout());

        panel.add(portsButton, BorderLayout.CENTER);

        return panel;
    }

    private JPanel labelPanel(final String text) {
        final JPanel panel =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                0,
                                0));

        panel.add(
                new javax.swing.JLabel(text));

        return panel;
    }

    private void showPortPopup() {
        final JPopupMenu popup =
                new JPopupMenu();

        popup.setLayout(
                new BorderLayout(4, 4));

        portSearch.setToolTipText(
                "Filter displayed destination ports");

        popup.add(portSearch, BorderLayout.NORTH);

        portList.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        portList.setVisibleRowCount(14);

        popup.add(
                new JScrollPane(portList),
                BorderLayout.CENTER);

        final JPanel bottom =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.RIGHT,
                                4,
                                2));

        final JButton selectAll =
                new JButton("Select All");

        final JButton clear =
                new JButton("Clear");

        selectAll.addActionListener(event -> {
            selectedPorts.clear();
            selectedPorts.addAll(allPorts);
            rebuildVisiblePortModel();
            updatePortButtonText();
            publishFilter();
        });

        clear.addActionListener(event -> {
            selectedPorts.clear();
            rebuildVisiblePortModel();
            updatePortButtonText();
            publishFilter();
        });

        bottom.add(selectAll);
        bottom.add(clear);

        popup.add(bottom, BorderLayout.SOUTH);

        popup.setPreferredSize(
                new Dimension(260, 330));

        popup.show(
                portsButton,
                0,
                portsButton.getHeight());

        portSearch.requestFocusInWindow();
    }

    private void installListeners() {
        final ActionListener actionListener =
                event -> publishFilter();

        ot.addActionListener(actionListener);
        it.addActionListener(actionListener);
        ioc.addActionListener(actionListener);
        unk.addActionListener(actionListener);

        dataTransfer.addActionListener(actionListener);
        noDataTransfer.addActionListener(actionListener);

        successfulConnections.addActionListener(actionListener);
        attemptedConnections.addActionListener(actionListener);

        showDevices.addActionListener(actionListener);
        showEdges.addActionListener(actionListener);

        portSearch.getDocument().addDocumentListener(
                new DocumentListener() {
                    @Override
                    public void insertUpdate(
                            final DocumentEvent event) {

                        rebuildVisiblePortModel();
                    }

                    @Override
                    public void removeUpdate(
                            final DocumentEvent event) {

                        rebuildVisiblePortModel();
                    }

                    @Override
                    public void changedUpdate(
                            final DocumentEvent event) {

                        rebuildVisiblePortModel();
                    }
                });

        portList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()
                    || updatingPorts) {

                return;
            }

            for (int index = 0;
                    index < visiblePortModel.size();
                    index++) {

                final Integer port =
                        visiblePortModel.get(index);

                if (portList.isSelectedIndex(index)) {
                    selectedPorts.add(port);
                } else {
                    selectedPorts.remove(port);
                }
            }

            updatePortButtonText();
            publishFilter();
        });
    }

    private void rebuildVisiblePortModel() {
        updatingPorts = true;

        try {
            final String query =
                    portSearch.getText()
                            .trim()
                            .toLowerCase();

            visiblePortModel.clear();

            for (Integer port : allPorts) {
                if (!query.isEmpty()
                        && !port.toString()
                                .contains(query)) {

                    continue;
                }

                visiblePortModel.addElement(port);
            }

            final int[] selectedIndexes =
                    new int[visiblePortModel.size()];

            int selectedCount = 0;

            for (int index = 0;
                    index < visiblePortModel.size();
                    index++) {

                if (selectedPorts.contains(
                        visiblePortModel.get(index))) {

                    selectedIndexes[selectedCount++] = index;
                }
            }

            final int[] trimmed =
                    new int[selectedCount];

            System.arraycopy(
                    selectedIndexes,
                    0,
                    trimmed,
                    0,
                    selectedCount);

            portList.setSelectedIndices(trimmed);

        } finally {
            updatingPorts = false;
        }
    }

    private void updatePortButtonText() {
        if (allPorts.isEmpty()) {
            portsButton.setText("All ports (none loaded)");

        } else if (selectedPorts.size() == allPorts.size()) {
            portsButton.setText(
                    "All ports selected ("
                            + allPorts.size()
                            + ")");

        } else {
            portsButton.setText(
                    selectedPorts.size()
                            + " of "
                            + allPorts.size()
                            + " ports selected");
        }
    }

    private void publishFilter() {
        if (filterConsumer == null) {
            /*
             * This can occur only if a Swing serializer/deserializer restored
             * this component without reconstructing application callbacks.
             * Normal application startup always provides the callback.
             */
            return;
        }

        byte deviceMask = 0;

        if (ot.isSelected()) {
            deviceMask |= DeviceTypes.OT_MASK;
        }

        if (it.isSelected()) {
            deviceMask |= DeviceTypes.IT_MASK;
        }

        if (ioc.isSelected()) {
            deviceMask |= DeviceTypes.IOC_MASK;
        }

        if (unk.isSelected()) {
            deviceMask |= DeviceTypes.UNK_MASK;
        }

        final BitSet destinationPortBits =
                new BitSet(65536);

        for (Integer port : selectedPorts) {
            destinationPortBits.set(port.intValue());
        }

        filterConsumer.accept(
                new GraphDisplayFilter(
                        deviceMask,
                        destinationPortBits,
                        true,
                        dataTransfer.isSelected(),
                        noDataTransfer.isSelected(),
                        successfulConnections.isSelected(),
                        attemptedConnections.isSelected(),
                        showDevices.isSelected(),
                        showEdges.isSelected()));
    }
}