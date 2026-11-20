package netgraph.csv;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import netgraph.graph.GraphStore;
import netgraph.util.IpUtil;
import netgraph.util.RejectedRecordStore;

/**
 * Streams connection-data CSV records into a packed GraphStore.Builder.
 */
public final class ConnectionCsvImporter {
    private ConnectionCsvImporter() {
    }

    public static ConnectionImportResult importFile(
            final Path file)
            throws IOException {

        final GraphStore.Builder builder =
                new GraphStore.Builder();

        final RejectedRecordStore rejected =
                new RejectedRecordStore();

        final int[] accepted =
                new int[] { 0 };

        /*
         * The one-element array is a mutable holder for an anonymous callback.
         * Java requires the captured array reference itself to remain final.
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Map<String, Integer>[] headers =
                new Map[] { null };

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

                    final String sourceIp =
                            required(
                                    values,
                                    headers[0],
                                    "source_ip");

                    final String destinationIp =
                            required(
                                    values,
                                    headers[0],
                                    "destination_ip");

                    if (!IpUtil.isIpLiteral(sourceIp)
                            || !IpUtil.isIpLiteral(destinationIp)) {

                        throw new IllegalArgumentException(
                                "source_ip or destination_ip "
                                        + "is not a valid IP literal.");
                    }

                    final int sourcePort =
                            parsePort(
                                    required(
                                            values,
                                            headers[0],
                                            "source_port"),
                                    "source_port");

                    final int destinationPort =
                            parsePort(
                                    required(
                                            values,
                                            headers[0],
                                            "destination_port"),
                                    "destination_port");

                    final long duration =
                            parseWhole(
                                    required(
                                            values,
                                            headers[0],
                                            "max_duration_s"),
                                    "max_duration_s");

                    final long bytes =
                            parseWhole(
                                    required(
                                            values,
                                            headers[0],
                                            "total_bytes"),
                                    "total_bytes");

                    final long packets =
                            parseWhole(
                                    required(
                                            values,
                                            headers[0],
                                            "total_packets"),
                                    "total_packets");

                    final long successful =
                            parseWhole(
                                    required(
                                            values,
                                            headers[0],
                                            "successful_conns"),
                                    "successful_conns");

                    final long connectionCount =
                            parseWhole(
                                    required(
                                            values,
                                            headers[0],
                                            "count"),
                                    "count");

                    final int priority =
                            parseInt(
                                    required(
                                            values,
                                            headers[0],
                                            "priority_score"),
                                    "priority_score");

                    final String matchOn =
                            required(
                                    values,
                                    headers[0],
                                    "match_on");

                    /*
                     * These required fields remain validated even though the
                     * packed baseline does not use them for topology keys.
                     */
                    required(
                            values,
                            headers[0],
                            "date");

                    required(
                            values,
                            headers[0],
                            "dataset");

                    required(
                            values,
                            headers[0],
                            "caret_history");

                    builder.addConnection(
                            sourceIp,
                            destinationIp,
                            sourcePort,
                            destinationPort,
                            connectionCount,
                            successful,
                            bytes,
                            packets,
                            duration,
                            priority,
                            matchOn);

                    accepted[0]++;

                } catch (IllegalArgumentException exception) {
                    rejected.record(
                            lineNumber,
                            exception.getMessage(),
                            rawRecord);
                }
            }
        }, rejected);

        if (headers[0] == null) {
            rejected.record(
                    1,
                    "CSV file is empty or has no readable header.",
                    "");
        }

        return new ConnectionImportResult(
                builder.build(),
                accepted[0],
                rejected);
    }

    private static void validateRequiredHeaders(
            final Map<String, Integer> headers) {

        final String[] required = {
                "date",
                "max_duration_s",
                "dataset",
                "caret_history",
                "source_ip",
                "source_port",
                "destination_ip",
                "destination_port",
                "match_on",
                "total_bytes",
                "total_packets",
                "successful_conns",
                "count",
                "priority_score"
        };

        for (int index = 0;
                index < required.length;
                index++) {

            if (!headers.containsKey(required[index])) {
                throw new IllegalArgumentException(
                        "Required header is missing: "
                                + required[index]);
            }
        }
    }

    private static String required(
            final String[] values,
            final Map<String, Integer> headers,
            final String header) {

        final Integer index =
                headers.get(header);

        if (index == null || index >= values.length) {
            throw new IllegalArgumentException(
                    "Required field is missing: "
                            + header);
        }

        final String value =
                values[index].trim();

        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Required field is blank: "
                            + header);
        }

        return value;
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

        long result = 0L;

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

            if (result
                    > (Long.MAX_VALUE - digit) / 10L) {

                throw new IllegalArgumentException(
                        field + " exceeds supported range.");
            }

            result = result * 10L + digit;
        }

        return result;
    }
}