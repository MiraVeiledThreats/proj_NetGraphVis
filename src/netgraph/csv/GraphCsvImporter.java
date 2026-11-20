package netgraph.csv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.nic.NetworkReferenceIndex;
import netgraph.util.DeviceTypes;
import netgraph.util.IpUtil;
import netgraph.util.RejectedRecordStore;

/**
 * Builds a packed graph from a GraphCsvExporter-compatible CSV file.
 *
 * Supported record_type values:
 * NODE, EDGE_PORT_PAIR, NETWORK, UNDETERMINED_NETWORK.
 */
public final class GraphCsvImporter {
    private GraphCsvImporter() {
    }

    public static GraphCsvImportResult importFile(
            final Path file)
            throws IOException {

        if (file == null) {
            throw new IllegalArgumentException(
                    "Graph CSV file is required.");
        }

        final GraphStore.Builder builder =
                new GraphStore.Builder();

        final RejectedRecordStore rejected =
                new RejectedRecordStore();

        final Map<String, NodeState> nodeStates =
                new HashMap<String, NodeState>();

        final Map<String, String> edgeNotes =
                new HashMap<String, String>();

        final ArrayList<NetworkState> networks =
                new ArrayList<NetworkState>();

        final HashSet<String> initializedEdges =
                new HashSet<String>();

        final String[] undeterminedNotes =
                new String[] { "" };

        final int[] accepted =
                new int[] { 0 };

        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Map<String, Integer>[] headers =
                new Map[] { null };

        try {
            Csv.read(file, new CsvRowConsumer() {
                @Override
                public void onRow(
                        final int lineNumber,
                        final String rawRecord,
                        final String[] values) {

                    try {
                        if (headers[0] == null) {
                            headers[0] = Csv.headerMap(values);
                            validateHeaders(headers[0]);
                            return;
                        }

                        final String recordType =
                                required(
                                        values,
                                        headers[0],
                                        "record_type")
                                        .toUpperCase();

                        if ("NODE".equals(recordType)) {
                            importNode(
                                    values,
                                    headers[0],
                                    builder,
                                    nodeStates);

                        } else if ("EDGE_PORT_PAIR".equals(
                                recordType)) {

                            importEdgePortPair(
                                    values,
                                    headers[0],
                                    builder,
                                    initializedEdges,
                                    edgeNotes);

                        } else if ("NETWORK".equals(
                                recordType)) {

                            networks.add(
                                    importNetwork(
                                            values,
                                            headers[0]));

                        } else if ("UNDETERMINED_NETWORK".equals(
                                recordType)) {

                            undeterminedNotes[0] =
                                    optional(
                                            values,
                                            headers[0],
                                            "network_analyst_notes");

                        } else {
                            throw new IllegalArgumentException(
                                    "Unsupported record_type: "
                                            + recordType);
                        }

                        accepted[0]++;

                    } catch (IllegalArgumentException exception) {
                        rejected.record(
                                lineNumber,
                                exception.getMessage(),
                                rawRecord);
                    }
                }
            }, rejected);

        } catch (IOException exception) {
            rejected.close();
            throw exception;

        } catch (RuntimeException exception) {
            rejected.close();
            throw exception;
        }

        if (headers[0] == null) {
            rejected.record(
                    1,
                    "CSV file is empty or has no readable header.",
                    "");
        }

        final GraphStore store =
                builder.build();

        applyNodeMetadata(store, nodeStates);
        applyEdgeNotes(store, edgeNotes);

        final NetworkReferenceIndex index =
                applyNetworkMetadata(
                        store,
                        networks,
                        undeterminedNotes[0]);

        return new GraphCsvImportResult(
                store,
                accepted[0],
                rejected,
                index);
    }

    private static void importNode(
            final String[] values,
            final Map<String, Integer> headers,
            final GraphStore.Builder builder,
            final Map<String, NodeState> states) {

        final String ip =
                required(values, headers, "node_ip");

        requireIpLiteral(ip, "node_ip");

        final byte tags =
                parseTypeMask(
                        optional(
                                values,
                                headers,
                                "node_device_type"));

        /*
         * Required for node-only graph records. Existing GraphStore.Builder
         * needs the public addNode method added in Section 5 below.
         */
        builder.addNode(ip);

        states.put(
                ip,
                new NodeState(
                        optional(
                                values,
                                headers,
                                "node_hostname"),
                        optional(
                                values,
                                headers,
                                "node_analyst_notes"),
                        tags));
    }

    private static void importEdgePortPair(
            final String[] values,
            final Map<String, Integer> headers,
            final GraphStore.Builder builder,
            final HashSet<String> initializedEdges,
            final Map<String, String> edgeNotes) {

        final String sourceIp =
                required(values, headers, "source_ip");

        final String destinationIp =
                required(values, headers, "destination_ip");

        requireIpLiteral(sourceIp, "source_ip");
        requireIpLiteral(destinationIp, "destination_ip");

        final int sourcePort =
                parsePort(
                        required(
                                values,
                                headers,
                                "source_port"),
                        "source_port");

        final int destinationPort =
                parsePort(
                        required(
                                values,
                                headers,
                                "destination_port"),
                        "destination_port");

        /*
         * GraphCsvExporter writes per-port fields. The edge-only totals are
         * accepted as a compatibility fallback for a hand-edited CSV with a
         * single pair row.
         */
        final long pairCount =
                parseWhole(
                        fallback(
                                values,
                                headers,
                                "connection_pair_count",
                                "edge_total_connection_count"),
                        "connection_pair_count");

        final long pairSuccessful =
                parseWhole(
                        fallback(
                                values,
                                headers,
                                "connection_pair_successful_count",
                                "edge_total_successful_connection_count"),
                        "connection_pair_successful_count");

        final long pairBytes =
                parseWhole(
                        fallback(
                                values,
                                headers,
                                "connection_pair_total_bytes",
                                "edge_total_bytes"),
                        "connection_pair_total_bytes");

        final String edgeKey =
                edgeKey(sourceIp, destinationIp);

        final boolean firstPortPair =
                initializedEdges.add(edgeKey);

        final long edgePackets =
                firstPortPair
                        ? parseWhole(
                                required(
                                        values,
                                        headers,
                                        "edge_total_packets"),
                                "edge_total_packets")
                        : 0L;

        final long edgeDuration =
                firstPortPair
                        ? parseWhole(
                                required(
                                        values,
                                        headers,
                                        "edge_max_duration_seconds"),
                                "edge_max_duration_seconds")
                        : 0L;

        final int edgePriority =
                firstPortPair
                        ? parseInt(
                                required(
                                        values,
                                        headers,
                                        "edge_max_priority_score"),
                                "edge_max_priority_score")
                        : 0;

        builder.addConnection(
                sourceIp,
                destinationIp,
                sourcePort,
                destinationPort,
                pairCount,
                pairSuccessful,
                pairBytes,
                edgePackets,
                edgeDuration,
                edgePriority,
                "");

        final String notes =
                optional(
                        values,
                        headers,
                        "edge_analyst_notes");

        if (!notes.isEmpty()) {
            edgeNotes.put(edgeKey, notes);
        }
    }

    private static NetworkState importNetwork(
            final String[] values,
            final Map<String, Integer> headers) {

        final String branch =
                optional(
                        values,
                        headers,
                        "network_branch");

        final String cidr =
                required(
                        values,
                        headers,
                        "network_cidr");

        final String name =
                optional(
                        values,
                        headers,
                        "network_name");

        validateCidr(cidr);

        return new NetworkState(
                branch,
                cidr,
                name,
                optional(
                        values,
                        headers,
                        "network_analyst_notes"));
    }

    private static void applyNodeMetadata(
            final GraphStore store,
            final Map<String, NodeState> states) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

            final NodeState state =
                    states.get(
                            metadata.getNodeIp(nodeId));

            if (state == null) {
                continue;
            }

            metadata.setNodeHostname(
                    nodeId,
                    state.hostname);

            metadata.setNodeAnalystComment(
                    nodeId,
                    state.notes);

            metadata.setNodeManualTypeMask(
                    nodeId,
                    state.typeMask);
        }
    }

    private static void applyEdgeNotes(
            final GraphStore store,
            final Map<String, String> edgeNotes) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int edgeId = 0;
                edgeId < store.getEdgeCount();
                edgeId++) {

            final String key =
                    edgeKey(
                            metadata.getNodeIp(
                                    store.getEdgeSourceNodeId(
                                            edgeId)),
                            metadata.getNodeIp(
                                    store.getEdgeTargetNodeId(
                                            edgeId)));

            final String notes =
                    edgeNotes.get(key);

            if (notes != null) {
                store.setEdgeAnalystComment(
                        edgeId,
                        notes);
            }
        }
    }

    private static NetworkReferenceIndex applyNetworkMetadata(
            final GraphStore store,
            final ArrayList<NetworkState> networks,
            final String undeterminedNotes) {

        final GraphMetadata metadata =
                store.getGraphMetadata();

        metadata.setUndeterminedNetworkAnalystComment(
                undeterminedNotes);

        if (networks.isEmpty()) {
            return null;
        }

        final String[] branches =
                new String[networks.size()];

        final String[] cidrs =
                new String[networks.size()];

        final String[] names =
                new String[networks.size()];

        final Map<String, String> notesByKey =
                new HashMap<String, String>();

        for (int index = 0;
                index < networks.size();
                index++) {

            final NetworkState state =
                    networks.get(index);

            branches[index] = state.branch;
            cidrs[index] = state.cidr;
            names[index] = state.name;

            notesByKey.put(
                    networkKey(
                            state.branch,
                            state.cidr,
                            state.name),
                    state.notes);
        }

        final NetworkReferenceIndex networkIndex =
                NetworkReferenceIndex.fromRecords(
                        branches,
                        cidrs,
                        names);

        networkIndex.applyTo(store);

        final GraphMetadata.NetworkTable table =
                metadata.getNetworkTable();

        for (int networkId = 0;
                networkId < table.networkCount();
                networkId++) {

            final String notes =
                    notesByKey.get(
                            networkKey(
                                    table.getBranch(networkId),
                                    table.getCidr(networkId),
                                    table.getName(networkId)));

            if (notes != null) {
                metadata.setNetworkAnalystComment(
                        networkId,
                        notes);
            }
        }

        return networkIndex;
    }

    private static void validateHeaders(
            final Map<String, Integer> headers) {

        final String[] required = {
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
                "network_analyst_notes"
        };

        for (int index = 0;
                index < required.length;
                index++) {

            if (!headers.containsKey(required[index])) {
                throw new IllegalArgumentException(
                        "Required Graph CSV header is missing: "
                                + required[index]);
            }
        }
    }

    private static String required(
            final String[] values,
            final Map<String, Integer> headers,
            final String header) {

        final String value =
                optional(values, headers, header);

        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Required field is blank: "
                            + header);
        }

        return value.trim();
    }

    private static String optional(
            final String[] values,
            final Map<String, Integer> headers,
            final String header) {

        final Integer index =
                headers.get(header);

        if (index == null || index.intValue() >= values.length) {
            return "";
        }

        final String value =
                values[index.intValue()];

        return value == null ? "" : value.trim();
    }

    private static String fallback(
            final String[] values,
            final Map<String, Integer> headers,
            final String preferredHeader,
            final String fallbackHeader) {

        final String preferred =
                optional(
                        values,
                        headers,
                        preferredHeader);

        return preferred.isEmpty()
                ? required(
                        values,
                        headers,
                        fallbackHeader)
                : preferred;
    }

    private static int parsePort(
            final String text,
            final String field) {

        final long value =
                parseWhole(text, field);

        if (value > 65535L) {
            throw new IllegalArgumentException(
                    field + " must be between 0 and 65535.");
        }

        return (int) value;
    }

    private static int parseInt(
            final String text,
            final String field) {

        final long value =
                parseWhole(text, field);

        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " exceeds integer range.");
        }

        return (int) value;
    }

    private static long parseWhole(
            final String text,
            final String field) {

        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is blank.");
        }

        long value = 0L;

        for (int index = 0;
                index < text.length();
                index++) {

            final char character =
                    text.charAt(index);

            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        field
                                + " is not a non-negative whole number.");
            }

            final int digit =
                    character - '0';

            if (value
                    > (Long.MAX_VALUE - digit) / 10L) {

                throw new IllegalArgumentException(
                        field + " exceeds supported range.");
            }

            value = value * 10L + digit;
        }

        return value;
    }

    private static byte parseTypeMask(
            final String text) {

        if (text == null || text.trim().isEmpty()) {
            return DeviceTypes.UNK_MASK;
        }

        byte result = 0;

        final String[] parts =
                text.split(",");

        for (int index = 0;
                index < parts.length;
                index++) {

            final String tag =
                    parts[index]
                            .trim()
                            .toUpperCase();

            if ("OT".equals(tag)) {
                result |= DeviceTypes.OT_MASK;

            } else if ("IT".equals(tag)) {
                result |= DeviceTypes.IT_MASK;

            } else if ("IOC".equals(tag)) {
                result |= DeviceTypes.IOC_MASK;

            } else if ("UNK".equals(tag)) {
                result |= DeviceTypes.UNK_MASK;

            } else {
                throw new IllegalArgumentException(
                        "Unsupported node device type tag: "
                                + tag);
            }
        }

        return result == 0
                ? DeviceTypes.UNK_MASK
                : result;
    }

    private static void requireIpLiteral(
            final String value,
            final String field) {

        if (!IpUtil.isIpLiteral(value)) {
            throw new IllegalArgumentException(
                    field + " is not a valid IP literal.");
        }
    }

    private static void validateCidr(
            final String cidr) {

        final int slash =
                cidr == null ? -1 : cidr.indexOf('/');

        if (slash <= 0
                || slash == cidr.length() - 1) {

            throw new IllegalArgumentException(
                    "network_cidr is invalid.");
        }

        final long address =
                IpUtil.ipv4(
                        cidr.substring(0, slash).trim());

        if (address < 0L) {
            throw new IllegalArgumentException(
                    "network_cidr is not an IPv4 CIDR.");
        }

        final int prefix;

        try {
            prefix = Integer.parseInt(
                    cidr.substring(slash + 1).trim());

        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "network_cidr prefix is invalid.");
        }

        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException(
                    "network_cidr prefix must be between 0 and 32.");
        }
    }

    private static String edgeKey(
            final String sourceIp,
            final String destinationIp) {

        return sourceIp
                + '\u0000'
                + destinationIp;
    }

    private static String networkKey(
            final String branch,
            final String cidr,
            final String name) {

        return safe(branch)
                + '\u0000'
                + safe(cidr)
                + '\u0000'
                + safe(name);
    }

    private static String safe(final String value) {
        return value == null ? "" : value;
    }

    private static final class NodeState {
        private final String hostname;
        private final String notes;
        private final byte typeMask;

        private NodeState(
                final String hostname,
                final String notes,
                final byte typeMask) {

            this.hostname = hostname;
            this.notes = notes;
            this.typeMask = typeMask;
        }
    }

    private static final class NetworkState {
        private final String branch;
        private final String cidr;
        private final String name;
        private final String notes;

        private NetworkState(
                final String branch,
                final String cidr,
                final String name,
                final String notes) {

            this.branch = branch;
            this.cidr = cidr;
            this.name = name;
            this.notes = notes;
        }
    }
}