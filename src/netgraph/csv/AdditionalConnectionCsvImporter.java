package netgraph.csv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.IpUtil;
import netgraph.util.RejectedRecordStore;

/**
 * Imports abbreviated, indicator-centered network connection data and appends
 * it to an existing packed GraphStore.
 *
 * <p>Required CSV headers:</p>
 *
 * <pre>
 * date,
 * last_seen,
 * max_duration_s,
 * dataset,
 * caret_history,
 * indicator_ip,
 * conn_to,
 * conn_from,
 * source_port,
 * destination_port,
 * total_bytes,
 * total_packets,
 * successful_conns,
 * count
 * </pre>
 *
 * <p>The abbreviated source format contains set-valued peer and port fields,
 * rather than one complete flow record per source/destination/port tuple.
 * Consequently, it cannot prove an exact peer-to-port mapping. This importer
 * creates bounded synthetic records while preserving the row-level totals for
 * connection count, successful connections, bytes, and packets exactly.</p>
 *
 * <p>Directed relationships are created as follows:</p>
 *
 * <ul>
 *   <li>indicator_ip -> every IP in conn_to</li>
 *   <li>every IP in conn_from -> indicator_ip</li>
 * </ul>
 *
 * <p>New nodes created by GraphStore.Builder default to UNK. Existing analyst
 * metadata is restored by NetworkGraphFrame through GraphAnalystStateCopier
 * after the merged packed graph is built.</p>
 */
public final class AdditionalConnectionCsvImporter {
    /*
     * Hard guard against a malformed list field causing excessive memory or
     * processing demand. This is not a Cartesian-product limit: the importer
     * deliberately does not form a peer x source-port x destination-port
     * expansion.
     */
    private static final int MAX_DERIVED_RECORDS_PER_ROW =
            250_000;

    private AdditionalConnectionCsvImporter() {
    }

    public static ConnectionImportResult importFile(
            final Path file,
            final GraphStore existingStore)
            throws IOException {

        if (file == null) {
            throw new IllegalArgumentException(
                    "Additional connection CSV file is required.");
        }

        if (existingStore == null || existingStore.isEmpty()) {
            throw new IllegalArgumentException(
                    "Load primary connection data before importing "
                            + "additional connection data.");
        }

        final GraphStore.Builder builder =
                new GraphStore.Builder();

        /*
         * Replay the prior graph into a new packed builder before adding
         * abbreviated records.
         */
        copyExistingGraph(existingStore, builder);

        final RejectedRecordStore rejected =
                new RejectedRecordStore();

        final int[] acceptedRows =
                new int[] { 0 };

        /*
         * Java anonymous callbacks require captured variables to be final.
         */
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
                            validateRequiredHeaders(headers[0]);
                            return;
                        }

                        importAbbreviatedRow(
                                values,
                                headers[0],
                                builder);

                        acceptedRows[0]++;

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

        return new ConnectionImportResult(
                builder.build(),
                acceptedRows[0],
                rejected);
    }

    /**
     * Imports one abbreviated indicator-centered data row.
     */
    private static void importAbbreviatedRow(
            final String[] values,
            final Map<String, Integer> headers,
            final GraphStore.Builder builder) {

        /*
         * Retain schema validation for fields whose contents are currently
         * provenance-only and not stored in packed graph topology.
         */
        required(values, headers, "date");
        required(values, headers, "last_seen");
        required(values, headers, "dataset");
        required(values, headers, "caret_history");

        final String indicatorIp =
                required(values, headers, "indicator_ip");

        if (!IpUtil.isIpLiteral(indicatorIp)) {
            throw new IllegalArgumentException(
                    "indicator_ip is not a valid IP literal.");
        }

        final ArrayList<String> connectionTargets =
                parseIpList(
                        required(values, headers, "conn_to"),
                        "conn_to");

        final ArrayList<String> connectionSources =
                parseIpList(
                        required(values, headers, "conn_from"),
                        "conn_from");

        final ArrayList<Integer> sourcePorts =
                parsePortList(
                        required(values, headers, "source_port"),
                        "source_port");

        final ArrayList<Integer> destinationPorts =
                parsePortList(
                        required(values, headers, "destination_port"),
                        "destination_port");

        if (sourcePorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "source_port contains no usable port values.");
        }

        if (destinationPorts.isEmpty()) {
            throw new IllegalArgumentException(
                    "destination_port contains no usable port values.");
        }

        final long durationSeconds =
                parseDurationSeconds(
                        required(
                                values,
                                headers,
                                "max_duration_s"),
                        "max_duration_s");

        final long totalBytes =
                parseWhole(
                        required(
                                values,
                                headers,
                                "total_bytes"),
                        "total_bytes");

        final long totalPackets =
                parseWhole(
                        required(
                                values,
                                headers,
                                "total_packets"),
                        "total_packets");

        final long successfulConnections =
                parseWhole(
                        required(
                                values,
                                headers,
                                "successful_conns"),
                        "successful_conns");

        final long connectionCount =
                parseWhole(
                        required(
                                values,
                                headers,
                                "count"),
                        "count");

        if (successfulConnections > connectionCount) {
            throw new IllegalArgumentException(
                    "successful_conns cannot exceed count.");
        }

        final ArrayList<DirectedRelationship> relationships =
                buildRelationships(
                        indicatorIp,
                        connectionTargets,
                        connectionSources);

        if (relationships.isEmpty()) {
            throw new IllegalArgumentException(
                    "conn_to and conn_from contain no usable peer IPs.");
        }

        /*
         * The input lists are marginal summaries. Do not construct a
         * relationship x source-port x destination-port Cartesian product.
         *
         * This count guarantees that:
         * - every directed relationship appears at least once;
         * - every source-port value appears at least once; and
         * - every destination-port value appears at least once.
         */
        final int derivedRecordCount =
                Math.max(
                        relationships.size(),
                        Math.max(
                                sourcePorts.size(),
                                destinationPorts.size()));

        if (derivedRecordCount > MAX_DERIVED_RECORDS_PER_ROW) {
            throw new IllegalArgumentException(
                    "Abbreviated row would create "
                            + derivedRecordCount
                            + " derived records, exceeding the safety "
                            + "limit of "
                            + MAX_DERIVED_RECORDS_PER_ROW
                            + ".");
        }

        /*
         * GraphStore stores max duration per directed edge, not a sum. Apply
         * the row-level maximum duration only once for each generated directed
         * relationship; additional synthetic port records for that same edge
         * receive zero duration so the edge maximum remains unchanged.
         */
        final LinkedHashSet<String> durationAppliedTo =
                new LinkedHashSet<String>();

        for (int recordIndex = 0;
                recordIndex < derivedRecordCount;
                recordIndex++) {

            final DirectedRelationship relationship =
                    relationships.get(
                            recordIndex
                                    % relationships.size());

            final int sourcePort =
                    sourcePorts.get(
                            recordIndex
                                    % sourcePorts.size())
                            .intValue();

            final int destinationPort =
                    destinationPorts.get(
                            recordIndex
                                    % destinationPorts.size())
                            .intValue();

            final long recordConnectionCount =
                    distribute(
                            connectionCount,
                            derivedRecordCount,
                            recordIndex);

            final long recordSuccessfulConnections =
                    distribute(
                            successfulConnections,
                            derivedRecordCount,
                            recordIndex);

            final long recordTotalBytes =
                    distribute(
                            totalBytes,
                            derivedRecordCount,
                            recordIndex);

            final long recordTotalPackets =
                    distribute(
                            totalPackets,
                            derivedRecordCount,
                            recordIndex);

            final String relationshipKey =
                    relationship.sourceIp
                            + '\u0000'
                            + relationship.destinationIp;

            final long recordDuration =
                    durationAppliedTo.add(relationshipKey)
                            ? durationSeconds
                            : 0L;

            /*
             * No IOC indicator or priority score exists in this abbreviated
             * source format. Empty matchOn ensures new devices retain the
             * GraphStore default type of UNK.
             */
            builder.addConnection(
                    relationship.sourceIp,
                    relationship.destinationIp,
                    sourcePort,
                    destinationPort,
                    recordConnectionCount,
                    recordSuccessfulConnections,
                    recordTotalBytes,
                    recordTotalPackets,
                    recordDuration,
                    0,
                    "");
        }
    }

    /**
     * Creates a stable, deduplicated directed relationship list.
     */
    private static ArrayList<DirectedRelationship> buildRelationships(
            final String indicatorIp,
            final ArrayList<String> connectionTargets,
            final ArrayList<String> connectionSources) {

        final ArrayList<DirectedRelationship> relationships =
                new ArrayList<DirectedRelationship>();

        final LinkedHashSet<String> seen =
                new LinkedHashSet<String>();

        /*
         * conn_to means the indicator initiated or was observed sending
         * traffic to the listed remote peer.
         */
        for (int index = 0;
                index < connectionTargets.size();
                index++) {

            addRelationship(
                    relationships,
                    seen,
                    indicatorIp,
                    connectionTargets.get(index));
        }

        /*
         * conn_from means the listed remote peer initiated or was observed
         * sending traffic to the indicator.
         */
        for (int index = 0;
                index < connectionSources.size();
                index++) {

            addRelationship(
                    relationships,
                    seen,
                    connectionSources.get(index),
                    indicatorIp);
        }

        return relationships;
    }

    private static void addRelationship(
            final ArrayList<DirectedRelationship> relationships,
            final LinkedHashSet<String> seen,
            final String sourceIp,
            final String destinationIp) {

        /*
         * Self-relationships are not useful in the current renderer and are
         * skipped rather than creating invisible/self-loop graph records.
         */
        if (sourceIp.equals(destinationIp)) {
            return;
        }

        final String key =
                sourceIp
                        + '\u0000'
                        + destinationIp;

        if (seen.add(key)) {
            relationships.add(
                    new DirectedRelationship(
                            sourceIp,
                            destinationIp));
        }
    }

    /**
     * Replays all original nodes and all aggregate edge/port records into a
     * new packed builder before abbreviated records are appended.
     */
    private static void copyExistingGraph(
            final GraphStore source,
            final GraphStore.Builder builder) {

        final GraphMetadata metadata =
                source.getGraphMetadata();

        /*
         * Preserve isolated nodes as well as nodes represented by edges.
         */
        for (int nodeId = 0;
                nodeId < source.getNodeCount();
                nodeId++) {

            builder.addNode(
                    metadata.getNodeIp(nodeId));
        }

        for (int edgeId = 0;
                edgeId < source.getEdgeCount();
                edgeId++) {

            final int sourceNodeId =
                    source.getEdgeSourceNodeId(edgeId);

            final int destinationNodeId =
                    source.getEdgeTargetNodeId(edgeId);

            final String sourceIp =
                    metadata.getNodeIp(sourceNodeId);

            final String destinationIp =
                    metadata.getNodeIp(destinationNodeId);

            final boolean sourceIsIoc =
                    source.getNodeType(sourceNodeId)
                            == GraphStore.TYPE_IOC;

            final boolean destinationIsIoc =
                    source.getNodeType(destinationNodeId)
                            == GraphStore.TYPE_IOC;

            int portIndex =
                    source.getEdgeFirstPort(edgeId);

            if (portIndex < 0) {
                throw new IllegalStateException(
                        "Cannot append data because an existing edge has "
                                + "no port aggregate.");
            }

            boolean firstPort = true;

            while (portIndex >= 0) {
                final String matchOn;

                if (sourceIsIoc) {
                    matchOn = "source";

                } else if (destinationIsIoc) {
                    matchOn = "destination";

                } else {
                    matchOn = "";
                }

                /*
                 * Edge packet total, max duration, and max priority are
                 * edge-level aggregates. Add them only once per edge to avoid
                 * duplicate accumulation during graph reconstruction.
                 */
                builder.addConnection(
                        sourceIp,
                        destinationIp,
                        source.getPortSourceNumber(portIndex),
                        source.getPortNumber(portIndex),
                        source.getPortConnectionCount(portIndex),
                        source.getPortSuccessfulConnectionCount(
                                portIndex),
                        source.getPortTotalBytes(portIndex),
                        firstPort
                                ? source.getEdgeTotalPackets(edgeId)
                                : 0L,
                        firstPort
                                ? source.getEdgeMaxDurationSeconds(edgeId)
                                : 0L,
                        firstPort
                                ? source.getEdgeMaxPriorityScore(edgeId)
                                : 0,
                        matchOn);

                /*
                 * Preserve an automatically detected IOC designation at both
                 * endpoints if both original nodes were IOC.
                 */
                if (firstPort
                        && sourceIsIoc
                        && destinationIsIoc) {

                    builder.addConnection(
                            sourceIp,
                            destinationIp,
                            source.getPortSourceNumber(portIndex),
                            source.getPortNumber(portIndex),
                            0L,
                            0L,
                            0L,
                            0L,
                            0L,
                            0,
                            "destination");
                }

                firstPort = false;

                portIndex =
                        source.getPortNext(portIndex);
            }
        }
    }

    private static void validateRequiredHeaders(
            final Map<String, Integer> headers) {

        final String[] requiredHeaders = {
                "date",
                "last_seen",
                "max_duration_s",
                "dataset",
                "caret_history",
                "indicator_ip",
                "conn_to",
                "conn_from",
                "source_port",
                "destination_port",
                "total_bytes",
                "total_packets",
                "successful_conns",
                "count"
        };

        for (int index = 0;
                index < requiredHeaders.length;
                index++) {

            if (!headers.containsKey(requiredHeaders[index])) {
                throw new IllegalArgumentException(
                        "Required header is missing: "
                                + requiredHeaders[index]);
            }
        }
    }

    private static String required(
            final String[] values,
            final Map<String, Integer> headers,
            final String header) {

        final Integer index =
                headers.get(header);

        if (index == null || index.intValue() >= values.length) {
            throw new IllegalArgumentException(
                    "Required field is missing: "
                            + header);
        }

        final String value =
                values[index.intValue()] == null
                        ? ""
                        : values[index.intValue()].trim();

        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required field is blank: "
                            + header);
        }

        return value;
    }

    /**
     * Parses a scalar or a bracketed Python-like/JSON-like list value.
     *
     * Supported examples:
     *
     * <pre>
     * 10.0.0.1
     * [10.0.0.1, 10.0.0.2]
     * ['10.0.0.1', '10.0.0.2']
     * ["10.0.0.1", "10.0.0.2"]
     * []
     * </pre>
     */
    private static ArrayList<String> parseListTokens(
            final String text,
            final String field) {

        if (text == null) {
            throw new IllegalArgumentException(
                    field + " is missing.");
        }

        String body = text.trim();

        if (body.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is blank.");
        }

        final boolean beginsWithBracket =
                body.startsWith("[");

        final boolean endsWithBracket =
                body.endsWith("]");

        if (beginsWithBracket != endsWithBracket) {
            throw new IllegalArgumentException(
                    field + " has unbalanced list brackets.");
        }

        if (beginsWithBracket) {
            body = body.substring(
                    1,
                    body.length() - 1).trim();
        }

        final ArrayList<String> result =
                new ArrayList<String>();

        if (body.isEmpty()) {
            return result;
        }

        final StringBuilder token =
                new StringBuilder();

        char quote = 0;
        boolean escaping = false;

        for (int index = 0;
                index < body.length();
                index++) {

            final char character =
                    body.charAt(index);

            if (quote != 0) {
                if (escaping) {
                    token.append(character);
                    escaping = false;

                } else if (character == '\\') {
                    escaping = true;

                } else if (character == quote) {
                    quote = 0;

                } else {
                    token.append(character);
                }

                continue;
            }

            if (character == '\''
                    || character == '"') {

                quote = character;

            } else if (character == ',') {
                addListToken(result, token, field);

            } else {
                token.append(character);
            }
        }

        if (quote != 0) {
            throw new IllegalArgumentException(
                    field + " has an unclosed quoted value.");
        }

        if (escaping) {
            throw new IllegalArgumentException(
                    field + " ends with an incomplete escape sequence.");
        }

        addListToken(result, token, field);

        return result;
    }

    private static void addListToken(
            final ArrayList<String> result,
            final StringBuilder token,
            final String field) {

        final String value =
                token.toString().trim();

        token.setLength(0);

        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains an empty list item.");
        }

        result.add(value);
    }

    private static ArrayList<String> parseIpList(
            final String text,
            final String field) {

        final ArrayList<String> tokens =
                parseListTokens(text, field);

        final LinkedHashSet<String> unique =
                new LinkedHashSet<String>();

        for (int index = 0;
                index < tokens.size();
                index++) {

            final String ip =
                    tokens.get(index).trim();

            if (!IpUtil.isIpLiteral(ip)) {
                throw new IllegalArgumentException(
                        field
                                + " contains an invalid IP literal: "
                                + ip);
            }

            unique.add(ip);
        }

        return new ArrayList<String>(unique);
    }

    private static ArrayList<Integer> parsePortList(
            final String text,
            final String field) {

        final ArrayList<String> tokens =
                parseListTokens(text, field);

        final LinkedHashSet<Integer> unique =
                new LinkedHashSet<Integer>();

        for (int index = 0;
                index < tokens.size();
                index++) {

            unique.add(
                    Integer.valueOf(
                            parsePort(
                                    tokens.get(index),
                                    field)));
        }

        return new ArrayList<Integer>(unique);
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

    /**
     * Converts an abbreviated floating-point maximum-duration field to packed
     * whole seconds using ceiling so non-zero fractional durations are never
     * understated.
     */
    private static long parseDurationSeconds(
            final String text,
            final String field) {

        final double value;

        try {
            value = Double.parseDouble(text.trim());

        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    field + " is not a valid non-negative number.");
        }

        if (Double.isNaN(value)
                || Double.isInfinite(value)
                || value < 0.0
                || value > Long.MAX_VALUE) {

            throw new IllegalArgumentException(
                    field + " is outside the supported range.");
        }

        return (long) Math.ceil(value);
    }

    private static long parseWhole(
            final String text,
            final String field) {

        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is blank.");
        }

        final String value =
                text.trim();

        long result = 0L;

        for (int index = 0;
                index < value.length();
                index++) {

            final char character =
                    value.charAt(index);

            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        field
                                + " is not a non-negative whole number.");
            }

            final int digit =
                    character - '0';

            if (result
                    > (Long.MAX_VALUE - digit) / 10L) {

                throw new IllegalArgumentException(
                        field + " exceeds supported range.");
            }

            result = result * 10L + digit;
        }

        return result;
    }

    /**
     * Returns an exact integer share of a non-negative total. Across every
     * index from zero through partCount - 1, the returned values sum exactly
     * to total.
     */
    private static long distribute(
            final long total,
            final int partCount,
            final int partIndex) {

        if (total < 0L) {
            throw new IllegalArgumentException(
                    "Cannot distribute a negative total.");
        }

        if (partCount <= 0
                || partIndex < 0
                || partIndex >= partCount) {

            throw new IllegalArgumentException(
                    "Invalid distribution parameters.");
        }

        final long base =
                total / partCount;

        final long remainder =
                total % partCount;

        return base
                + (partIndex < remainder ? 1L : 0L);
    }

    private static final class DirectedRelationship {
        private final String sourceIp;
        private final String destinationIp;

        private DirectedRelationship(
                final String sourceIp,
                final String destinationIp) {

            this.sourceIp = sourceIp;
            this.destinationIp = destinationIp;
        }
    }
}