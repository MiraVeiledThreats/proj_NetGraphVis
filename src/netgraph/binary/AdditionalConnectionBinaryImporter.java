package netgraph.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import netgraph.csv.ConnectionImportResult;
import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.RejectedRecordStore;

/**
 * JDK-only importer for version-one additional network connection (.ngc)
 * files. The importer verifies the header, footer index, every block checksum,
 * and every record boundary before adding validated records to a rebuilt packed
 * graph.
 *
 * <p>{@code connected_to} produces {@code scanned_ip -> peer};
 * {@code connected_from} produces {@code peer -> scanned_ip}. The source
 * contract does not carry source ports, so imported records use source port
 * zero as the documented unknown-source-port sentinel.</p>
 *
 * <p>Peer and destination-port arrays are independent lists. Exact peer/port
 * pairing is therefore unknowable. This importer uses the bounded round-robin
 * policy {@code max(peerCount, portCount)} per direction, covers every listed
 * value, does not create a Cartesian product, and distributes row totals
 * exactly across all usable derived records.</p>
 */
public final class AdditionalConnectionBinaryImporter {
    private static final int UNKNOWN_SOURCE_PORT = 0;
    private static final int MAX_INDEX_BYTES = 64 * 1024 * 1024;

    private AdditionalConnectionBinaryImporter() {
    }

    /**
     * Returns the number of length-prefixed source records in an NGC file
     * without building a graph.
     */
    public static long getRecordCount(final Path file)
            throws IOException {

        requireReadableFile(file);

        try (RandomAccessFile input =
                new RandomAccessFile(file.toFile(), "r")) {

            return readHeader(input).recordCount;
        }
    }

    /**
     * Reads one zero-based source record through the footer index. Only the
     * indexed block that contains the requested record is decompressed.
     */
    public static NetworkConnectionBinaryFormat.Record readRecordAt(
            final Path file,
            final long zeroBasedRecordNumber)
            throws IOException {

        requireReadableFile(file);

        try (RandomAccessFile input =
                new RandomAccessFile(file.toFile(), "r")) {

            final Header header = readHeader(input);

            if (zeroBasedRecordNumber < 0L
                    || zeroBasedRecordNumber >= header.recordCount) {

                throw new IndexOutOfBoundsException(
                        "NGC record number is outside the file range.");
            }

            final ArrayList<BlockIndexEntry> blocks = readIndex(input, header);
            final BlockIndexEntry block = findBlock(
                    blocks,
                    zeroBasedRecordNumber);

            final BinaryInput blockInput = new BinaryInput(
                    readBlock(input, header, block));

            final int recordInBlock = (int) (
                    zeroBasedRecordNumber - block.firstRecord);

            for (int index = 0; index < block.recordCount; index++) {
                final int length = blockInput.readUnsignedInt();

                if (length > NetworkConnectionBinaryFormat.MAX_RECORD_BYTES
                        || length > blockInput.remaining()) {

                    throw new IOException(
                            "NGC block contains an invalid record boundary.");
                }

                if (index != recordInBlock) {
                    blockInput.skipBytes(length);
                    continue;
                }

                try {
                    return NetworkConnectionBinaryFormat.readRecord(
                            new BinaryInput(blockInput.readBytes(length)));

                } catch (IllegalArgumentException exception) {
                    throw new IOException(
                            "NGC indexed record is semantically invalid.",
                            exception);
                }
            }

            throw new IOException("NGC footer index did not locate its record.");
        }
    }

    public static ConnectionImportResult importFile(
            final Path file,
            final GraphStore existingStore)
            throws IOException {

        if (file == null) {
            throw new IllegalArgumentException(
                    "Additional connection NGC file is required.");
        }

        requireReadableFile(file);

        if (existingStore == null || existingStore.isEmpty()) {
            throw new IllegalArgumentException(
                    "Load primary connection data before importing "
                            + "additional connection data.");
        }

        final GraphStore.Builder builder = new GraphStore.Builder();
        copyExistingGraph(existingStore, builder);

        final RejectedRecordStore rejected = new RejectedRecordStore();

        try (RandomAccessFile input =
                new RandomAccessFile(file.toFile(), "r")) {

            final Header header = readHeader(input);
            final ArrayList<BlockIndexEntry> blocks = readIndex(input, header);

            int accepted = 0;

            for (int blockNumber = 0;
                    blockNumber < blocks.size();
                    blockNumber++) {

                final BlockIndexEntry block = blocks.get(blockNumber);

                final BinaryInput blockInput = new BinaryInput(
                        readBlock(input, header, block));

                for (int recordInBlock = 0;
                        recordInBlock < block.recordCount;
                        recordInBlock++) {

                    final long recordNumber =
                            block.firstRecord + recordInBlock + 1L;

                    final int recordLength =
                            blockInput.readUnsignedInt();

                    if (recordLength > NetworkConnectionBinaryFormat
                            .MAX_RECORD_BYTES) {

                        throw new IOException(
                                "NGC record " + recordNumber
                                        + " exceeds the supported size limit.");
                    }

                    if (recordLength > blockInput.remaining()) {
                        throw new IOException(
                                "NGC record " + recordNumber
                                        + " extends beyond its block.");
                    }

                    final byte[] recordBytes =
                            blockInput.readBytes(recordLength);

                    try {
                        final NetworkConnectionBinaryFormat.Record record =
                                NetworkConnectionBinaryFormat.readRecord(
                                        new BinaryInput(recordBytes));

                        importRecord(record, builder);

                        if (accepted == Integer.MAX_VALUE) {
                            throw new IOException(
                                    "NGC accepted-record count exceeds the "
                                            + "application result limit.");
                        }

                        accepted++;

                    } catch (IllegalArgumentException exception) {
                        rejected.record(
                                rejectionLineNumber(recordNumber),
                                exception.getMessage(),
                                recordSummary(recordNumber, blockNumber));

                    } catch (IOException exception) {
                        /*
                         * Record payloads are length-prefixed. A malformed
                         * record can therefore be rejected without losing the
                         * boundary of the next record in the validated block.
                         */
                        rejected.record(
                                rejectionLineNumber(recordNumber),
                                exception.getMessage(),
                                recordSummary(recordNumber, blockNumber));
                    }
                }

                if (blockInput.remaining() != 0) {
                    throw new IOException(
                            "NGC block " + blockNumber
                                    + " contains trailing unclaimed bytes.");
                }
            }

            return new ConnectionImportResult(
                    builder.build(),
                    accepted,
                    rejected);

        } catch (IOException exception) {
            rejected.close();
            throw exception;

        } catch (RuntimeException exception) {
            rejected.close();
            throw exception;
        }
    }

    private static void importRecord(
            final NetworkConnectionBinaryFormat.Record record,
            final GraphStore.Builder builder) {

        final DirectionPlan outbound = DirectionPlan.create(
                record.getScannedIp(),
                record.connectedToValues(),
                record.connectedToPortValues(),
                true,
                "connected_to",
                "connected_to_dest_ports");

        final DirectionPlan inbound = DirectionPlan.create(
                record.getScannedIp(),
                record.connectedFromValues(),
                record.connectedFromPortValues(),
                false,
                "connected_from",
                "connected_from_dest_ports");

        final long candidateCount =
                (long) outbound.getCandidateCount()
                        + inbound.getCandidateCount();

        if (candidateCount > NetworkConnectionBinaryFormat.MAX_DERIVED_CONNECTIONS_PER_RECORD) {
            throw new IllegalArgumentException(
                    "NGC record exceeds the derived-connection safety limit "
                            + "of " + NetworkConnectionBinaryFormat.MAX_DERIVED_CONNECTIONS_PER_RECORD
                            + ".");
        }

        final int usableCount =
                outbound.getUsableConnectionCount()
                        + inbound.getUsableConnectionCount();

        if (usableCount == 0) {
            throw new IllegalArgumentException(
                    "connected_to and connected_from contain no usable "
                            + "non-self peer IPs.");
        }

        final LinkedHashSet<String> durationAppliedTo =
                new LinkedHashSet<String>();

        int derivedIndex = appendDirection(
                builder,
                outbound,
                durationAppliedTo,
                record.getMaxDurationSeconds(),
                record.getConnectionCount(),
                record.getSuccessfulConnections(),
                record.getTotalBytes(),
                record.getTotalPackets(),
                usableCount,
                0);

        derivedIndex = appendDirection(
                builder,
                inbound,
                durationAppliedTo,
                record.getMaxDurationSeconds(),
                record.getConnectionCount(),
                record.getSuccessfulConnections(),
                record.getTotalBytes(),
                record.getTotalPackets(),
                usableCount,
                derivedIndex);

        if (derivedIndex != usableCount) {
            throw new IllegalStateException(
                    "NGC derived-connection plan is inconsistent.");
        }
    }

    private static int appendDirection(
            final GraphStore.Builder builder,
            final DirectionPlan plan,
            final LinkedHashSet<String> durationAppliedTo,
            final long durationSeconds,
            final long connectionCount,
            final long successfulConnections,
            final long totalBytes,
            final long totalPackets,
            final int totalDerivedCount,
            final int firstDerivedIndex) {

        int derivedIndex = firstDerivedIndex;

        for (int candidateIndex = 0;
                candidateIndex < plan.getCandidateCount();
                candidateIndex++) {

            final String peer = plan.peerAt(candidateIndex);

            if (plan.isSelf(peer)) {
                continue;
            }

            final String sourceIp = plan.sourceFor(peer);
            final String destinationIp = plan.destinationFor(peer);
            final String edgeKey = sourceIp + '\u0000' + destinationIp;

            builder.addConnection(
                    sourceIp,
                    destinationIp,
                    UNKNOWN_SOURCE_PORT,
                    plan.destinationPortAt(candidateIndex),
                    distribute(
                            connectionCount,
                            totalDerivedCount,
                            derivedIndex),
                    distribute(
                            successfulConnections,
                            totalDerivedCount,
                            derivedIndex),
                    distribute(
                            totalBytes,
                            totalDerivedCount,
                            derivedIndex),
                    distribute(
                            totalPackets,
                            totalDerivedCount,
                            derivedIndex),
                    durationAppliedTo.add(edgeKey)
                            ? durationSeconds
                            : 0L,
                    0,
                    "");

            derivedIndex++;
        }

        return derivedIndex;
    }

    private static void requireReadableFile(final Path file)
            throws IOException {

        if (file == null) {
            throw new IllegalArgumentException(
                    "Additional connection NGC file is required.");
        }

        if (!Files.isRegularFile(file)) {
            throw new IOException(
                    "Additional connection NGC file does not exist or is not "
                            + "a regular file: " + file);
        }
    }

    private static BlockIndexEntry findBlock(
            final ArrayList<BlockIndexEntry> blocks,
            final long zeroBasedRecordNumber)
            throws IOException {

        int low = 0;
        int high = blocks.size() - 1;

        while (low <= high) {
            final int middle = low + (high - low) / 2;
            final BlockIndexEntry candidate = blocks.get(middle);
            final long endExclusive = candidate.firstRecord
                    + candidate.recordCount;

            if (zeroBasedRecordNumber < candidate.firstRecord) {
                high = middle - 1;

            } else if (zeroBasedRecordNumber >= endExclusive) {
                low = middle + 1;

            } else {
                return candidate;
            }
        }

        throw new IOException("NGC footer index does not cover the record.");
    }

    private static Header readHeader(final RandomAccessFile input)
            throws IOException {

        if (input.length() < NetworkConnectionBinaryFormat.HEADER_SIZE) {
            throw new IOException("NGC file is smaller than its fixed header.");
        }

        input.seek(0L);

        final int magic = input.readInt();
        final int version = input.readInt();
        final int flags = input.readInt();
        final int blockTargetBytes = input.readInt();
        final long recordCount = input.readLong();
        final long indexOffset = input.readLong();
        final long createdEpochMillis = input.readLong();

        if (magic != NetworkConnectionBinaryFormat.FILE_MAGIC) {
            throw new IOException("File is not an NGC version-one file.");
        }

        if (version != NetworkConnectionBinaryFormat.VERSION) {
            throw new IOException(
                    "Unsupported NGC version: " + version);
        }

        if ((flags & ~NetworkConnectionBinaryFormat.FLAG_BLOCKS_COMPRESSED)
                != 0) {

            throw new IOException("NGC file uses unsupported feature flags.");
        }

        if (blockTargetBytes <= 0
                || blockTargetBytes
                        > NetworkConnectionBinaryFormat.MAX_BLOCK_UNCOMPRESSED_BYTES) {

            throw new IOException("NGC header has an invalid block target.");
        }

        if (recordCount < 0L || recordCount > Integer.MAX_VALUE) {
            throw new IOException(
                    "NGC record count exceeds supported application limits.");
        }

        if (indexOffset < NetworkConnectionBinaryFormat.HEADER_SIZE
                || indexOffset >= input.length()) {

            throw new IOException("NGC header has an invalid index offset.");
        }

        return new Header(
                flags,
                recordCount,
                indexOffset,
                createdEpochMillis);
    }

    private static ArrayList<BlockIndexEntry> readIndex(
            final RandomAccessFile input,
            final Header header)
            throws IOException {

        final long fileLength = input.length();
        final long minimumLength = 12L;

        if (fileLength - header.indexOffset < minimumLength) {
            throw new IOException("NGC footer index is truncated.");
        }

        input.seek(header.indexOffset);
        final int magic = input.readInt();
        final int entryCount = input.readInt();

        if (magic != NetworkConnectionBinaryFormat.INDEX_MAGIC) {
            throw new IOException("NGC footer index magic is invalid.");
        }

        if (entryCount < 0) {
            throw new IOException("NGC footer index count is negative.");
        }

        final long dataLength;

        try {
            dataLength = Math.addExact(
                    8L,
                    Math.multiplyExact(
                            (long) entryCount,
                            (long) NetworkConnectionBinaryFormat
                                    .INDEX_ENTRY_SIZE));

        } catch (ArithmeticException exception) {
            throw new IOException("NGC footer index length overflows.", exception);
        }

        final long completeLength = dataLength + 4L;

        if (dataLength > MAX_INDEX_BYTES
                || completeLength > fileLength - header.indexOffset) {

            throw new IOException("NGC footer index exceeds supported bounds.");
        }

        if (header.indexOffset + completeLength != fileLength) {
            throw new IOException(
                    "NGC file has unexpected trailing or missing index bytes.");
        }

        input.seek(header.indexOffset);
        final byte[] data = new byte[(int) dataLength];
        input.readFully(data);

        final int expectedChecksum = input.readInt();

        if (checksum(data) != expectedChecksum) {
            throw new IOException("NGC footer index checksum does not match.");
        }

        final BinaryInput indexInput = new BinaryInput(data);

        if (indexInput.readInt() != NetworkConnectionBinaryFormat.INDEX_MAGIC
                || indexInput.readInt() != entryCount) {

            throw new IOException("NGC footer index header is inconsistent.");
        }

        final ArrayList<BlockIndexEntry> entries =
                new ArrayList<BlockIndexEntry>(entryCount);

        long expectedFirstRecord = 0L;
        long expectedBlockOffset = NetworkConnectionBinaryFormat.HEADER_SIZE;

        for (int entryNumber = 0;
                entryNumber < entryCount;
                entryNumber++) {

            final BlockIndexEntry entry = new BlockIndexEntry(
                    indexInput.readLong(),
                    indexInput.readLong(),
                    indexInput.readInt(),
                    indexInput.readInt(),
                    indexInput.readInt());

            validateIndexEntry(
                    entry,
                    expectedFirstRecord,
                    expectedBlockOffset,
                    header.indexOffset);

            try {
                expectedFirstRecord = Math.addExact(
                        expectedFirstRecord,
                        entry.recordCount);

                expectedBlockOffset = Math.addExact(
                        expectedBlockOffset,
                        NetworkConnectionBinaryFormat.BLOCK_HEADER_SIZE
                                + (long) entry.storedLength);

            } catch (ArithmeticException exception) {
                throw new IOException("NGC index entry range overflows.", exception);
            }

            entries.add(entry);
        }

        indexInput.requireEnd();

        if (expectedFirstRecord != header.recordCount
                || expectedBlockOffset != header.indexOffset) {

            throw new IOException(
                    "NGC header, blocks, and footer index disagree.");
        }

        return entries;
    }

    private static void validateIndexEntry(
            final BlockIndexEntry entry,
            final long expectedFirstRecord,
            final long expectedBlockOffset,
            final long indexOffset)
            throws IOException {

        if (entry.firstRecord != expectedFirstRecord
                || entry.fileOffset != expectedBlockOffset
                || entry.recordCount <= 0
                || entry.uncompressedLength <= 0
                || entry.uncompressedLength
                        > NetworkConnectionBinaryFormat
                                .MAX_BLOCK_UNCOMPRESSED_BYTES
                || entry.storedLength <= 0
                || entry.storedLength > maximumStoredBlockBytes()
                || entry.fileOffset >= indexOffset) {

            throw new IOException("NGC footer index entry is invalid.");
        }
    }

    private static byte[] readBlock(
            final RandomAccessFile input,
            final Header header,
            final BlockIndexEntry expected)
            throws IOException {

        input.seek(expected.fileOffset);

        final int magic = input.readInt();
        final long firstRecord = input.readLong();
        final int recordCount = input.readInt();
        final int uncompressedLength = input.readInt();
        final int storedLength = input.readInt();
        final int expectedChecksum = input.readInt();

        if (magic != NetworkConnectionBinaryFormat.BLOCK_MAGIC
                || firstRecord != expected.firstRecord
                || recordCount != expected.recordCount
                || uncompressedLength != expected.uncompressedLength
                || storedLength != expected.storedLength) {

            throw new IOException("NGC block header disagrees with its index.");
        }

        final byte[] stored = new byte[storedLength];
        input.readFully(stored);

        final byte[] uncompressed =
                (header.flags
                        & NetworkConnectionBinaryFormat.FLAG_BLOCKS_COMPRESSED)
                                != 0
                        ? inflate(stored, uncompressedLength)
                        : stored;

        if (uncompressed.length != uncompressedLength) {
            throw new IOException("NGC block length does not match its header.");
        }

        if (checksum(uncompressed) != expectedChecksum) {
            throw new IOException("NGC block checksum does not match.");
        }

        return uncompressed;
    }

    private static byte[] inflate(
            final byte[] stored,
            final int expectedLength)
            throws IOException {

        final Inflater inflater = new Inflater();

        try {
            inflater.setInput(stored);

            final ByteArrayOutputStream output =
                    new ByteArrayOutputStream(expectedLength);

            final byte[] buffer = new byte[8192];

            while (!inflater.finished()) {
                final int count;

                try {
                    count = inflater.inflate(buffer);

                } catch (DataFormatException exception) {
                    throw new IOException(
                            "NGC compressed block is malformed.",
                            exception);
                }

                if (count > 0) {
                    if (output.size() > expectedLength - count) {
                        throw new IOException(
                                "NGC compressed block exceeds its expected size.");
                    }

                    output.write(buffer, 0, count);
                    continue;
                }

                if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw new IOException(
                            "NGC compressed block ended before completion.");
                }

                throw new IOException("NGC compressed block made no progress.");
            }

            if (inflater.getRemaining() != 0) {
                throw new IOException(
                        "NGC compressed block contains trailing bytes.");
            }

            return output.toByteArray();

        } finally {
            inflater.end();
        }
    }

    private static int maximumStoredBlockBytes() {
        return NetworkConnectionBinaryFormat.MAX_BLOCK_UNCOMPRESSED_BYTES
                + 1024 * 1024;
    }

    private static int checksum(final byte[] value) {
        final CRC32 checksum = new CRC32();
        checksum.update(value, 0, value.length);
        return (int) checksum.getValue();
    }

    private static long distribute(
            final long total,
            final int partCount,
            final int partIndex) {

        if (total < 0L || partCount <= 0
                || partIndex < 0 || partIndex >= partCount) {

            throw new IllegalArgumentException(
                    "Invalid aggregate distribution parameters.");
        }

        return total / partCount
                + (partIndex < total % partCount ? 1L : 0L);
    }

    /**
     * Replays existing packed topology and traffic aggregates before new NGC
     * records are added. Analyst-editable state is copied by the frame after
     * the rebuilt GraphStore is returned.
     */
    private static void copyExistingGraph(
            final GraphStore source,
            final GraphStore.Builder builder) {

        final GraphMetadata metadata = source.getGraphMetadata();

        for (int nodeId = 0;
                nodeId < source.getNodeCount();
                nodeId++) {

            builder.addNode(metadata.getNodeIp(nodeId));
        }

        for (int edgeId = 0;
                edgeId < source.getEdgeCount();
                edgeId++) {

            final int sourceNodeId = source.getEdgeSourceNodeId(edgeId);
            final int destinationNodeId = source.getEdgeTargetNodeId(edgeId);
            final String sourceIp = metadata.getNodeIp(sourceNodeId);
            final String destinationIp = metadata.getNodeIp(destinationNodeId);

            final boolean sourceIsIoc = source.getNodeType(sourceNodeId)
                    == GraphStore.TYPE_IOC;
            final boolean destinationIsIoc = source.getNodeType(destinationNodeId)
                    == GraphStore.TYPE_IOC;

            int portIndex = source.getEdgeFirstPort(edgeId);

            if (portIndex < 0) {
                throw new IllegalStateException(
                        "Cannot append data because an existing edge has no "
                                + "port aggregate.");
            }

            boolean firstPort = true;

            while (portIndex >= 0) {
                final String matchOn = sourceIsIoc
                        ? "source"
                        : destinationIsIoc ? "destination" : "";

                builder.addConnection(
                        sourceIp,
                        destinationIp,
                        source.getPortSourceNumber(portIndex),
                        source.getPortNumber(portIndex),
                        source.getPortConnectionCount(portIndex),
                        source.getPortSuccessfulConnectionCount(portIndex),
                        source.getPortTotalBytes(portIndex),
                        firstPort ? source.getEdgeTotalPackets(edgeId) : 0L,
                        firstPort
                                ? source.getEdgeMaxDurationSeconds(edgeId)
                                : 0L,
                        firstPort
                                ? source.getEdgeMaxPriorityScore(edgeId)
                                : 0,
                        matchOn);

                if (firstPort && sourceIsIoc && destinationIsIoc) {
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
                portIndex = source.getPortNext(portIndex);
            }
        }
    }

    private static int rejectionLineNumber(final long recordNumber) {
        return recordNumber > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) recordNumber;
    }

    private static String recordSummary(
            final long recordNumber,
            final int blockNumber) {

        return "NGC record=" + recordNumber + ", block=" + blockNumber;
    }

    private static final class Header {
        private final int flags;
        private final long recordCount;
        private final long indexOffset;
        @SuppressWarnings("unused")
        private final long createdEpochMillis;

        private Header(
                final int flags,
                final long recordCount,
                final long indexOffset,
                final long createdEpochMillis) {

            this.flags = flags;
            this.recordCount = recordCount;
            this.indexOffset = indexOffset;
            this.createdEpochMillis = createdEpochMillis;
        }
    }

    private static final class BlockIndexEntry {
        private final long firstRecord;
        private final long fileOffset;
        private final int recordCount;
        private final int uncompressedLength;
        private final int storedLength;

        private BlockIndexEntry(
                final long firstRecord,
                final long fileOffset,
                final int recordCount,
                final int uncompressedLength,
                final int storedLength) {

            this.firstRecord = firstRecord;
            this.fileOffset = fileOffset;
            this.recordCount = recordCount;
            this.uncompressedLength = uncompressedLength;
            this.storedLength = storedLength;
        }
    }

    private static final class DirectionPlan {
        private final String scannedIp;
        private final String[] peers;
        private final int[] destinationPorts;
        private final boolean outbound;
        private final int candidateCount;
        private final int usableConnectionCount;

        private DirectionPlan(
                final String scannedIp,
                final String[] peers,
                final int[] destinationPorts,
                final boolean outbound,
                final int candidateCount,
                final int usableConnectionCount) {

            this.scannedIp = scannedIp;
            this.peers = peers;
            this.destinationPorts = destinationPorts;
            this.outbound = outbound;
            this.candidateCount = candidateCount;
            this.usableConnectionCount = usableConnectionCount;
        }

        private static DirectionPlan create(
                final String scannedIp,
                final String[] peers,
                final int[] destinationPorts,
                final boolean outbound,
                final String peerColumn,
                final String portColumn) {

            if (peers.length == 0) {
                if (destinationPorts.length != 0) {
                    throw new IllegalArgumentException(
                            portColumn + " has values but "
                                    + peerColumn + " is empty.");
                }

                return new DirectionPlan(
                        scannedIp,
                        peers,
                        destinationPorts,
                        outbound,
                        0,
                        0);
            }

            if (destinationPorts.length == 0) {
                throw new IllegalArgumentException(
                        portColumn + " contains no destination ports for "
                                + peerColumn + ".");
            }

            final int candidateCount = Math.max(
                    peers.length,
                    destinationPorts.length);

            int usableCount = 0;

            for (int index = 0; index < candidateCount; index++) {
                if (!scannedIp.equals(peers[index % peers.length])) {
                    usableCount++;
                }
            }

            return new DirectionPlan(
                    scannedIp,
                    peers,
                    destinationPorts,
                    outbound,
                    candidateCount,
                    usableCount);
        }

        private int getCandidateCount() {
            return candidateCount;
        }

        private int getUsableConnectionCount() {
            return usableConnectionCount;
        }

        private String peerAt(final int index) {
            return peers[index % peers.length];
        }

        private int destinationPortAt(final int index) {
            return destinationPorts[index % destinationPorts.length];
        }

        private boolean isSelf(final String peer) {
            return scannedIp.equals(peer);
        }

        private String sourceFor(final String peer) {
            return outbound ? scannedIp : peer;
        }

        private String destinationFor(final String peer) {
            return outbound ? peer : scannedIp;
        }
    }
}
