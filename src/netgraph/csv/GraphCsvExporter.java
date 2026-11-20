package netgraph.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.CidrUtil;
import netgraph.util.DeviceTypes;
import netgraph.util.NetworkMembershipUtil;

/**
 * Exports the currently loaded graph and analyst annotations to CSV.
 *
 * Output rows use record_type values:
 * NODE, EDGE_PORT_PAIR, NETWORK, and UNDETERMINED_NETWORK.
 */
public final class GraphCsvExporter {
    private static final int COLUMN_COUNT = 25;

    private static final int RECORD_TYPE = 0;
    private static final int NODE_IP = 1;
    private static final int NODE_HOSTNAME = 2;
    private static final int NODE_DEVICE_TYPE = 3;
    private static final int NODE_ANALYST_NOTES = 4;
    private static final int SOURCE_IP = 5;
    private static final int SOURCE_PORT = 6;
    private static final int DESTINATION_IP = 7;
    private static final int DESTINATION_PORT = 8;
    private static final int EDGE_TOTAL_CONNECTION_COUNT = 9;
    private static final int EDGE_TOTAL_SUCCESSFUL_CONNECTION_COUNT = 10;
    private static final int EDGE_TOTAL_BYTES = 11;
    private static final int EDGE_TOTAL_PACKETS = 12;
    private static final int EDGE_MAX_DURATION_SECONDS = 13;
    private static final int EDGE_MAX_PRIORITY_SCORE = 14;
    private static final int CONNECTION_PAIR_COUNT = 15;
    private static final int CONNECTION_PAIR_SUCCESSFUL_COUNT = 16;
    private static final int CONNECTION_PAIR_TOTAL_BYTES = 17;
    private static final int EDGE_ANALYST_NOTES = 18;
    private static final int NETWORK_BRANCH = 19;
    private static final int NETWORK_CIDR = 20;
    private static final int NETWORK_NAME = 21;
    private static final int NETWORK_PREFIX_LENGTH = 22;
    private static final int NETWORK_ANALYST_NOTES = 23;
    private static final int SUBNET_NAMES = 24;

    private GraphCsvExporter() {
    }

    public static void export(
            final Path target,
            final GraphStore store)
            throws IOException {

        if (target == null) {
            throw new IllegalArgumentException(
                    "Graph CSV export target is required.");
        }

        if (store == null) {
            throw new IllegalArgumentException(
                    "Graph store is required.");
        }

        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        target,
                        StandardCharsets.UTF_8)) {

            writeRow(
                    writer,
                    "record_type",
                    "node_ip",
                    "node_hostname",
                    "node_device_type",
                    "node_analyst_notes",
                    "source_ip",
                    "source_port",
                    "destination_ip",
                    "destination_port",
                    "edge_total_connection_count",
                    "edge_total_successful_connection_count",
                    "edge_total_bytes",
                    "edge_total_packets",
                    "edge_max_duration_seconds",
                    "edge_max_priority_score",
                    "connection_pair_count",
                    "connection_pair_successful_count",
                    "connection_pair_total_bytes",
                    "edge_analyst_notes",
                    "network_branch",
                    "network_cidr",
                    "network_name",
                    "network_prefix_length",
                    "network_analyst_notes",
                    "subnet_names");

            writeNodeRows(writer, store);
            writeEdgeRows(writer, store);
            writeNetworkRows(writer, store);
        }
    }

    private static void writeNodeRows(
            final BufferedWriter writer,
            final GraphStore store)
            throws IOException {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

            final String[] row = emptyRow("NODE");

            row[NODE_IP] = metadata.getNodeIp(nodeId);
            row[NODE_HOSTNAME] =
                    metadata.getNodeHostname(nodeId);

            row[NODE_DEVICE_TYPE] =
                    DeviceTypes.tagNames(
                            metadata.effectiveNodeTypeMask(
                                    nodeId,
                                    store.getNodeType(nodeId)));

            row[NODE_ANALYST_NOTES] =
                    metadata.getNodeAnalystComment(nodeId);

            writeRow(writer, row);
        }
    }

    private static void writeEdgeRows(
            final BufferedWriter writer,
            final GraphStore store)
            throws IOException {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int edgeId = 0;
                edgeId < store.getEdgeCount();
                edgeId++) {

            final int sourceNodeId =
                    store.getEdgeSourceNodeId(edgeId);

            final int destinationNodeId =
                    store.getEdgeTargetNodeId(edgeId);

            int portIndex =
                    store.getEdgeFirstPort(edgeId);

            /*
             * Each edge should normally have at least one port pair. Retain
             * an edge-only row if an edge without a pair is encountered.
             */
            if (portIndex < 0) {
                final String[] row =
                        edgeRow(
                                store,
                                metadata,
                                edgeId,
                                sourceNodeId,
                                destinationNodeId);

                writeRow(writer, row);
                continue;
            }

            while (portIndex >= 0) {
                final String[] row =
                        edgeRow(
                                store,
                                metadata,
                                edgeId,
                                sourceNodeId,
                                destinationNodeId);

                row[SOURCE_PORT] = Integer.toString(
                        store.getPortSourceNumber(portIndex));

                row[DESTINATION_PORT] = Integer.toString(
                        store.getPortNumber(portIndex));

                row[CONNECTION_PAIR_COUNT] =
                        Long.toString(
                                store.getPortConnectionCount(
                                        portIndex));

                row[CONNECTION_PAIR_SUCCESSFUL_COUNT] =
                        Long.toString(
                                store.getPortSuccessfulConnectionCount(
                                        portIndex));

                row[CONNECTION_PAIR_TOTAL_BYTES] =
                        Long.toString(
                                store.getPortTotalBytes(
                                        portIndex));

                writeRow(writer, row);

                portIndex = store.getPortNext(portIndex);
            }
        }
    }

    private static String[] edgeRow(
            final GraphStore store,
            final GraphMetadata metadata,
            final int edgeId,
            final int sourceNodeId,
            final int destinationNodeId) {

        final String[] row = emptyRow("EDGE_PORT_PAIR");

        row[SOURCE_IP] = metadata.getNodeIp(sourceNodeId);
        row[DESTINATION_IP] =
                metadata.getNodeIp(destinationNodeId);

        row[EDGE_TOTAL_CONNECTION_COUNT] =
                Long.toString(
                        store.getEdgeConnectionCount(edgeId));

        row[EDGE_TOTAL_SUCCESSFUL_CONNECTION_COUNT] =
                Long.toString(
                        store.getEdgeSuccessfulConnectionCount(
                                edgeId));

        row[EDGE_TOTAL_BYTES] =
                Long.toString(
                        store.getEdgeTotalBytes(edgeId));

        row[EDGE_TOTAL_PACKETS] =
                Long.toString(
                        store.getEdgeTotalPackets(edgeId));

        row[EDGE_MAX_DURATION_SECONDS] =
                Long.toString(
                        store.getEdgeMaxDurationSeconds(edgeId));

        row[EDGE_MAX_PRIORITY_SCORE] =
                Integer.toString(
                        store.getEdgeMaxPriorityScore(edgeId));

        row[EDGE_ANALYST_NOTES] =
                store.getEdgeAnalystComment(edgeId);

        return row;
    }

    private static void writeNetworkRows(
            final BufferedWriter writer,
            final GraphStore store)
            throws IOException {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        final GraphMetadata.NetworkTable networks =
                metadata.getNetworkTable();

        final boolean[] presentNetworkMask =
                NetworkMembershipUtil.presentNetworkMask(
                        networks);

        for (int networkId = 0;
                networkId < networks.networkCount();
                networkId++) {

            final String[] row = emptyRow("NETWORK");

            row[NETWORK_BRANCH] =
                    networks.getBranch(networkId);

            row[NETWORK_CIDR] =
                    networks.getCidr(networkId);

            row[NETWORK_NAME] =
                    networks.getName(networkId);

            row[NETWORK_PREFIX_LENGTH] =
                    Integer.toString(
                            networks.getPrefixLength(networkId));

            row[NETWORK_ANALYST_NOTES] =
                    metadata.getNetworkAnalystComment(networkId);

            row[SUBNET_NAMES] =
                    subnetNames(
                            networks,
                            presentNetworkMask,
                            networkId);

            writeRow(writer, row);
        }

        final String[] undetermined =
                emptyRow("UNDETERMINED_NETWORK");

        undetermined[NETWORK_NAME] =
                "Undetermined Network";

        undetermined[NETWORK_ANALYST_NOTES] =
                metadata.getUndeterminedNetworkAnalystComment();

        writeRow(writer, undetermined);
    }

    private static String subnetNames(
            final GraphMetadata.NetworkTable networks,
            final boolean[] presentNetworkMask,
            final int parentNetworkId) {

        final String parentCidr =
                networks.getCidr(parentNetworkId);

        final StringBuilder names =
                new StringBuilder();

        for (int networkId = 0;
                networkId < networks.networkCount();
                networkId++) {

            /*
             * Export only child subnets that have at least one represented
             * graph node. NIC reference entries with no connection-data
             * presence must not appear in the subnet_names field.
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

            if (names.length() > 0) {
                names.append("; ");
            }

            final String name =
                    networks.getName(networkId);

            names.append(
                    name == null || name.trim().isEmpty()
                            ? networks.getCidr(networkId)
                            : name);

            names.append(" (")
                    .append(networks.getCidr(networkId))
                    .append(')');
        }

        return names.toString();
    }

    private static String[] emptyRow(
            final String recordType) {

        final String[] row = new String[COLUMN_COUNT];

        Arrays.fill(row, "");

        row[RECORD_TYPE] = recordType;
        return row;
    }

    private static void writeRow(
            final BufferedWriter writer,
            final String... values)
            throws IOException {

        if (values == null || values.length != COLUMN_COUNT) {
            throw new IllegalArgumentException(
                    "Graph export row has an invalid column count.");
        }

        for (int index = 0;
                index < values.length;
                index++) {

            if (index > 0) {
                writer.write(',');
            }

            writer.write(csvEscape(values[index]));
        }

        writer.newLine();
    }

    private static String csvEscape(final String value) {
        final String safe = value == null ? "" : value;

        return "\""
                + safe.replace("\"", "\"\"")
                + "\"";
    }
}