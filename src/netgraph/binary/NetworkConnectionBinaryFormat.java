package netgraph.binary;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;

import netgraph.util.IpUtil;

/**
 * Version-one contract for additional network connection binary files (.ngc).
 *
 * <p>The format is deliberately schema-specific. It encodes the known
 * additional-connection fields directly using JDK primitives, UTF-8 text,
 * raw four- or sixteen-byte IP addresses, non-negative variable-length
 * integers, independently checksummed blocks, and a footer index. It is not a
 * general-purpose object serialization format.</p>
 */
public final class NetworkConnectionBinaryFormat {
    public static final int FILE_MAGIC = 0x4E474331;       // NGC1
    public static final int BLOCK_MAGIC = 0x4E474231;      // NGB1
    public static final int INDEX_MAGIC = 0x4E474958;      // NGIX
    public static final int VERSION = 1;

    public static final int HEADER_SIZE = 40;
    public static final int BLOCK_HEADER_SIZE = 28;
    public static final int INDEX_ENTRY_SIZE = 28;

    public static final int FLAG_BLOCKS_COMPRESSED = 1;

    public static final int DEFAULT_BLOCK_TARGET_BYTES =
            4 * 1024 * 1024;

    public static final int MAX_BLOCK_UNCOMPRESSED_BYTES =
            16 * 1024 * 1024;

    public static final int MAX_RECORD_BYTES =
            8 * 1024 * 1024;

    public static final int MAX_STRING_BYTES =
            1024 * 1024;

    public static final int MAX_ARRAY_ELEMENTS =
            250_000;

    public static final int MAX_DERIVED_CONNECTIONS_PER_RECORD =
            250_000;

    private static final int IPV4 = 4;
    private static final int IPV6 = 6;

    private NetworkConnectionBinaryFormat() {
    }

    static void writeRecord(
            final BinaryOutput output,
            final Record record) {

        if (output == null || record == null) {
            throw new IllegalArgumentException(
                    "Output and record are required.");
        }

        output.writeInt(epochDay(record.date));
        output.writeLong(epochMicros(record.lastSeen));
        output.writeUnsignedLong(record.maxDurationSeconds);
        output.writeUtf8(record.dataset);
        output.writeByte(record.caretHistory ? 1 : 0);
        writeIp(output, record.scannedIp);
        writeIpArray(output, record.connectedTo);
        writeIpArray(output, record.connectedFrom);
        writePortArray(output, record.connectedToDestinationPorts);
        writePortArray(output, record.connectedFromDestinationPorts);
        output.writeUnsignedLong(record.totalBytes);
        output.writeUnsignedLong(record.totalPackets);
        output.writeUnsignedLong(record.successfulConnections);
        output.writeUnsignedLong(record.connectionCount);
    }

    static Record readRecord(final BinaryInput input)
            throws IOException {

        if (input == null) {
            throw new IllegalArgumentException("Binary input is required.");
        }

        final LocalDate date;
        final Instant lastSeen;

        try {
            date = LocalDate.ofEpochDay(input.readInt());
            lastSeen = instantFromEpochMicros(input.readLong());

        } catch (DateTimeException exception) {
            throw new IOException(
                    "Record contains an invalid date or timestamp.",
                    exception);
        }

        final long maxDurationSeconds = input.readUnsignedLong();
        final String dataset = input.readUtf8(MAX_STRING_BYTES);
        final int caretHistory = input.readUnsignedByte();

        if (caretHistory != 0 && caretHistory != 1) {
            throw new IOException(
                    "Record caret-history flag must be zero or one.");
        }

        final String scannedIp = readIp(input);
        final String[] connectedTo = readIpArray(input);
        final String[] connectedFrom = readIpArray(input);
        final int[] connectedToPorts = readPortArray(input);
        final int[] connectedFromPorts = readPortArray(input);
        final long totalBytes = input.readUnsignedLong();
        final long totalPackets = input.readUnsignedLong();
        final long successfulConnections = input.readUnsignedLong();
        final long connectionCount = input.readUnsignedLong();

        input.requireEnd();

        return new Record(
                date,
                lastSeen,
                maxDurationSeconds,
                dataset,
                caretHistory == 1,
                scannedIp,
                connectedTo,
                connectedFrom,
                connectedToPorts,
                connectedFromPorts,
                totalBytes,
                totalPackets,
                successfulConnections,
                connectionCount);
    }

    private static void writeIpArray(
            final BinaryOutput output,
            final String[] values) {

        output.writeUnsignedInt(values.length);

        for (int index = 0; index < values.length; index++) {
            writeIp(output, values[index]);
        }
    }

    private static String[] readIpArray(final BinaryInput input)
            throws IOException {

        final int count = readArrayLength(input);
        final String[] values = new String[count];

        for (int index = 0; index < count; index++) {
            values[index] = readIp(input);
        }

        return values;
    }

    private static void writePortArray(
            final BinaryOutput output,
            final int[] values) {

        output.writeUnsignedInt(values.length);

        for (int index = 0; index < values.length; index++) {
            output.writeUnsignedInt(values[index]);
        }
    }

    private static int[] readPortArray(final BinaryInput input)
            throws IOException {

        final int count = readArrayLength(input);
        final int[] values = new int[count];

        for (int index = 0; index < count; index++) {
            final int port = input.readUnsignedInt();

            if (port > 65535) {
                throw new IOException(
                        "Record contains a port outside 0 through 65535.");
            }

            values[index] = port;
        }

        return values;
    }

    private static int readArrayLength(final BinaryInput input)
            throws IOException {

        final int count = input.readUnsignedInt();

        if (count > MAX_ARRAY_ELEMENTS) {
            throw new IOException(
                    "Record array exceeds configured size limit.");
        }

        return count;
    }

    private static void writeIp(
            final BinaryOutput output,
            final String value) {

        final byte[] bytes = ipBytes(value);

        if (bytes.length == 4) {
            output.writeByte(IPV4);

        } else if (bytes.length == 16) {
            output.writeByte(IPV6);

        } else {
            throw new IllegalArgumentException(
                    "Only IPv4 and IPv6 addresses are supported.");
        }

        output.writeBytes(bytes);
    }

    private static String readIp(final BinaryInput input)
            throws IOException {

        final int type = input.readUnsignedByte();
        final int length;

        if (type == IPV4) {
            length = 4;

        } else if (type == IPV6) {
            length = 16;

        } else {
            throw new IOException("Record contains an unknown IP-address type.");
        }

        final byte[] bytes = input.readBytes(length);

        try {
            return InetAddress.getByAddress(bytes).getHostAddress();

        } catch (UnknownHostException exception) {
            throw new IOException(
                    "Record contains an invalid binary IP address.",
                    exception);
        }
    }

    private static byte[] ipBytes(final String value) {
        final String normalized = requireIp(value, "IP address");

        try {
            return InetAddress.getByName(normalized).getAddress();

        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException(
                    "Invalid IP literal: " + normalized,
                    exception);
        }
    }

    private static int epochDay(final LocalDate value) {
        final long epochDay = value.toEpochDay();

        if (epochDay < Integer.MIN_VALUE
                || epochDay > Integer.MAX_VALUE) {

            throw new IllegalArgumentException(
                    "Date is outside the NGC version-one range.");
        }

        return (int) epochDay;
    }

    private static long epochMicros(final Instant value) {
        try {
            return Math.addExact(
                    Math.multiplyExact(
                            value.getEpochSecond(),
                            1_000_000L),
                    value.getNano() / 1_000L);

        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Timestamp is outside the NGC version-one range.",
                    exception);
        }
    }

    private static Instant instantFromEpochMicros(final long value) {
        final long seconds = Math.floorDiv(value, 1_000_000L);
        final long microAdjustment = Math.floorMod(value, 1_000_000L);
        return Instant.ofEpochSecond(seconds, microAdjustment * 1_000L);
    }

    private static String requireText(
            final String value,
            final String label) {

        if (value == null) {
            throw new IllegalArgumentException(label + " is required.");
        }

        final String normalized = value.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " is blank.");
        }

        if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_STRING_BYTES) {

            throw new IllegalArgumentException(
                    label + " exceeds the NGC text size limit.");
        }

        return normalized;
    }

    private static String requireIp(
            final String value,
            final String label) {

        final String normalized = requireText(value, label);

        if (!IpUtil.isIpLiteral(normalized)) {
            throw new IllegalArgumentException(
                    label + " is not a valid IP literal: " + normalized);
        }

        return normalized;
    }

    private static String[] copyIps(
            final String[] values,
            final String label) {

        if (values == null) {
            throw new IllegalArgumentException(label + " array is required.");
        }

        if (values.length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException(
                    label + " exceeds the NGC array size limit.");
        }

        final String[] copy = new String[values.length];

        for (int index = 0; index < values.length; index++) {
            copy[index] = requireIp(values[index], label + " item");
        }

        return copy;
    }

    private static void validateDirectionPair(
            final String[] peers,
            final int[] ports,
            final String peerLabel,
            final String portLabel) {

        if ((peers.length == 0) != (ports.length == 0)) {
            throw new IllegalArgumentException(
                    peerLabel + " and " + portLabel
                            + " must both be empty or both contain values.");
        }
    }

    private static int[] copyPorts(
            final int[] values,
            final String label) {

        if (values == null) {
            throw new IllegalArgumentException(label + " array is required.");
        }

        if (values.length > MAX_ARRAY_ELEMENTS) {
            throw new IllegalArgumentException(
                    label + " exceeds the NGC array size limit.");
        }

        final int[] copy = Arrays.copyOf(values, values.length);

        for (int index = 0; index < copy.length; index++) {
            if (copy[index] < 0 || copy[index] > 65535) {
                throw new IllegalArgumentException(
                        label + " contains a port outside 0 through 65535.");
            }
        }

        return copy;
    }

    private static long nonNegative(
            final long value,
            final String label) {

        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative.");
        }

        return value;
    }

    /**
     * Immutable source record used by the JDK-only exporter and importer.
     * Array values are defensively copied at construction and when returned
     * through public accessors.
     */
    public static final class Record {
        private final LocalDate date;
        private final Instant lastSeen;
        private final long maxDurationSeconds;
        private final String dataset;
        private final boolean caretHistory;
        private final String scannedIp;
        private final String[] connectedTo;
        private final String[] connectedFrom;
        private final int[] connectedToDestinationPorts;
        private final int[] connectedFromDestinationPorts;
        private final long totalBytes;
        private final long totalPackets;
        private final long successfulConnections;
        private final long connectionCount;

        public Record(
                final LocalDate date,
                final Instant lastSeen,
                final long maxDurationSeconds,
                final String dataset,
                final boolean caretHistory,
                final String scannedIp,
                final String[] connectedTo,
                final String[] connectedFrom,
                final int[] connectedToDestinationPorts,
                final int[] connectedFromDestinationPorts,
                final long totalBytes,
                final long totalPackets,
                final long successfulConnections,
                final long connectionCount) {

            if (date == null || lastSeen == null) {
                throw new IllegalArgumentException(
                        "Date and last-seen timestamp are required.");
            }

            if (lastSeen.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "lastSeen must have microsecond precision.");
            }

            this.date = date;
            this.lastSeen = lastSeen;
            this.maxDurationSeconds = nonNegative(
                    maxDurationSeconds, "maxDurationSeconds");
            this.dataset = requireText(dataset, "dataset");
            this.caretHistory = caretHistory;
            this.scannedIp = requireIp(scannedIp, "scannedIp");
            this.connectedTo = copyIps(connectedTo, "connectedTo");
            this.connectedFrom = copyIps(connectedFrom, "connectedFrom");
            this.connectedToDestinationPorts = copyPorts(
                    connectedToDestinationPorts,
                    "connectedToDestinationPorts");
            this.connectedFromDestinationPorts = copyPorts(
                    connectedFromDestinationPorts,
                    "connectedFromDestinationPorts");

            validateDirectionPair(
                    this.connectedTo,
                    this.connectedToDestinationPorts,
                    "connectedTo",
                    "connectedToDestinationPorts");

            validateDirectionPair(
                    this.connectedFrom,
                    this.connectedFromDestinationPorts,
                    "connectedFrom",
                    "connectedFromDestinationPorts");

            final long derivedConnectionCount =
                    (long) Math.max(
                            this.connectedTo.length,
                            this.connectedToDestinationPorts.length)
                            + Math.max(
                                    this.connectedFrom.length,
                                    this.connectedFromDestinationPorts.length);

            if (derivedConnectionCount
                    > MAX_DERIVED_CONNECTIONS_PER_RECORD) {
                throw new IllegalArgumentException(
                        "Record exceeds the NGC derived-connection limit.");
            }

            this.totalBytes = nonNegative(totalBytes, "totalBytes");
            this.totalPackets = nonNegative(totalPackets, "totalPackets");
            this.successfulConnections = nonNegative(
                    successfulConnections, "successfulConnections");
            this.connectionCount = nonNegative(
                    connectionCount, "connectionCount");

            if (successfulConnections > connectionCount) {
                throw new IllegalArgumentException(
                        "successfulConnections cannot exceed connectionCount.");
            }
        }

        public LocalDate getDate() {
            return date;
        }

        public Instant getLastSeen() {
            return lastSeen;
        }

        public long getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public String getDataset() {
            return dataset;
        }

        public boolean isCaretHistory() {
            return caretHistory;
        }

        public String getScannedIp() {
            return scannedIp;
        }

        public String[] getConnectedTo() {
            return Arrays.copyOf(connectedTo, connectedTo.length);
        }

        public String[] getConnectedFrom() {
            return Arrays.copyOf(connectedFrom, connectedFrom.length);
        }

        public int[] getConnectedToDestinationPorts() {
            return Arrays.copyOf(
                    connectedToDestinationPorts,
                    connectedToDestinationPorts.length);
        }

        public int[] getConnectedFromDestinationPorts() {
            return Arrays.copyOf(
                    connectedFromDestinationPorts,
                    connectedFromDestinationPorts.length);
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getTotalPackets() {
            return totalPackets;
        }

        public long getSuccessfulConnections() {
            return successfulConnections;
        }

        public long getConnectionCount() {
            return connectionCount;
        }

        String[] connectedToValues() {
            return connectedTo;
        }

        String[] connectedFromValues() {
            return connectedFrom;
        }

        int[] connectedToPortValues() {
            return connectedToDestinationPorts;
        }

        int[] connectedFromPortValues() {
            return connectedFromDestinationPorts;
        }
    }
}
