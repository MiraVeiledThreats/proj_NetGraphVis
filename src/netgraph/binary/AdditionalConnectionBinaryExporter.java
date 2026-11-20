package netgraph.binary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * JDK-only writer for version-one additional network connection (.ngc) files.
 *
 * <p>The writer emits bounded independently compressed blocks and a footer
 * index. It writes a temporary sibling first, updates the header only after
 * all blocks and index entries are present, and then replaces the target. A
 * successful return therefore never intentionally leaves a partially written
 * target file.</p>
 */
public final class AdditionalConnectionBinaryExporter {
    private static final int RECORD_COUNT_OFFSET = 16;
    private static final int INDEX_OFFSET_OFFSET = 24;

    private AdditionalConnectionBinaryExporter() {
    }

    public static void exportFile(
            final Path target,
            final Iterable<NetworkConnectionBinaryFormat.Record> records)
            throws IOException {

        exportFile(
                target,
                records,
                NetworkConnectionBinaryFormat.DEFAULT_BLOCK_TARGET_BYTES,
                true);
    }

    public static void exportFile(
            final Path target,
            final Iterable<NetworkConnectionBinaryFormat.Record> records,
            final int blockTargetBytes,
            final boolean compressBlocks)
            throws IOException {

        if (target == null || records == null) {
            throw new IllegalArgumentException(
                    "Target file and record iterable are required.");
        }

        if (blockTargetBytes <= 0
                || blockTargetBytes
                        > NetworkConnectionBinaryFormat.MAX_BLOCK_UNCOMPRESSED_BYTES) {

            throw new IllegalArgumentException(
                    "Block target must be from one through "
                            + NetworkConnectionBinaryFormat
                                    .MAX_BLOCK_UNCOMPRESSED_BYTES
                            + " bytes.");
        }

        final Path absoluteTarget = target.toAbsolutePath();
        final Path parent = absoluteTarget.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        final Path temporary =
                Files.createTempFile(
                        parent == null
                                ? absoluteTarget.toAbsolutePath().getParent()
                                : parent,
                        absoluteTarget.getFileName().toString() + ".",
                        ".tmp");

        boolean completed = false;

        try {
            writeTemporary(
                    temporary,
                    records,
                    blockTargetBytes,
                    compressBlocks);

            moveReplacing(temporary, absoluteTarget);
            completed = true;

        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void writeTemporary(
            final Path temporary,
            final Iterable<NetworkConnectionBinaryFormat.Record> records,
            final int blockTargetBytes,
            final boolean compressBlocks)
            throws IOException {

        final int flags = compressBlocks
                ? NetworkConnectionBinaryFormat.FLAG_BLOCKS_COMPRESSED
                : 0;

        try (RandomAccessFile file =
                new RandomAccessFile(temporary.toFile(), "rw")) {

            writeHeader(
                    file,
                    flags,
                    blockTargetBytes,
                    0L,
                    0L,
                    System.currentTimeMillis());

            final ArrayList<BlockIndexEntry> index =
                    new ArrayList<BlockIndexEntry>();

            BinaryOutput block =
                    new BinaryOutput(blockTargetBytes);

            int blockRecordCount = 0;
            long firstRecord = 0L;
            long totalRecords = 0L;

            for (NetworkConnectionBinaryFormat.Record record : records) {
                if (record == null) {
                    throw new IllegalArgumentException(
                            "Record iterable contains a null record.");
                }

                final BinaryOutput encodedRecord = new BinaryOutput();
                NetworkConnectionBinaryFormat.writeRecord(
                        encodedRecord,
                        record);

                final byte[] recordBytes = encodedRecord.toByteArray();

                if (recordBytes.length
                        > NetworkConnectionBinaryFormat.MAX_RECORD_BYTES) {

                    throw new IOException(
                            "One NGC record exceeds the configured size limit.");
                }

                final BinaryOutput wrappedRecord = new BinaryOutput(
                        recordBytes.length + 8);

                wrappedRecord.writeUnsignedInt(recordBytes.length);
                wrappedRecord.writeBytes(recordBytes);

                final byte[] wrappedBytes = wrappedRecord.toByteArray();

                if (wrappedBytes.length
                        > NetworkConnectionBinaryFormat.MAX_BLOCK_UNCOMPRESSED_BYTES) {

                    throw new IOException(
                            "One NGC record cannot fit into a supported block.");
                }

                if (blockRecordCount > 0
                        && block.size() + wrappedBytes.length
                                > blockTargetBytes) {

                    writeBlock(
                            file,
                            index,
                            firstRecord,
                            blockRecordCount,
                            block.toByteArray(),
                            compressBlocks);

                    firstRecord = totalRecords;
                    block = new BinaryOutput(blockTargetBytes);
                    blockRecordCount = 0;
                }

                if (totalRecords == Integer.MAX_VALUE) {
                    throw new IOException(
                            "NGC record count exceeds the application result "
                                    + "limit.");
                }

                block.writeBytes(wrappedBytes);
                blockRecordCount++;
                totalRecords++;
            }

            if (blockRecordCount > 0) {
                writeBlock(
                        file,
                        index,
                        firstRecord,
                        blockRecordCount,
                        block.toByteArray(),
                        compressBlocks);
            }

            final long indexOffset = file.getFilePointer();
            writeIndex(file, index);

            file.seek(RECORD_COUNT_OFFSET);
            file.writeLong(totalRecords);
            file.seek(INDEX_OFFSET_OFFSET);
            file.writeLong(indexOffset);
            file.getFD().sync();
        }
    }

    private static void writeHeader(
            final RandomAccessFile file,
            final int flags,
            final int blockTargetBytes,
            final long recordCount,
            final long indexOffset,
            final long createdEpochMillis)
            throws IOException {

        file.writeInt(NetworkConnectionBinaryFormat.FILE_MAGIC);
        file.writeInt(NetworkConnectionBinaryFormat.VERSION);
        file.writeInt(flags);
        file.writeInt(blockTargetBytes);
        file.writeLong(recordCount);
        file.writeLong(indexOffset);
        file.writeLong(createdEpochMillis);
    }

    private static void writeBlock(
            final RandomAccessFile file,
            final ArrayList<BlockIndexEntry> index,
            final long firstRecord,
            final int recordCount,
            final byte[] uncompressed,
            final boolean compressBlocks)
            throws IOException {

        if (uncompressed.length == 0 || recordCount <= 0) {
            throw new IllegalArgumentException(
                    "A non-empty block and positive record count are required.");
        }

        if (uncompressed.length
                > NetworkConnectionBinaryFormat.MAX_BLOCK_UNCOMPRESSED_BYTES) {

            throw new IOException("NGC block exceeds the supported size limit.");
        }

        final byte[] stored = compressBlocks
                ? deflate(uncompressed)
                : uncompressed;

        final long offset = file.getFilePointer();
        final int checksum = checksum(uncompressed);

        file.writeInt(NetworkConnectionBinaryFormat.BLOCK_MAGIC);
        file.writeLong(firstRecord);
        file.writeInt(recordCount);
        file.writeInt(uncompressed.length);
        file.writeInt(stored.length);
        file.writeInt(checksum);
        file.write(stored);

        index.add(new BlockIndexEntry(
                firstRecord,
                offset,
                recordCount,
                uncompressed.length,
                stored.length));
    }

    private static void writeIndex(
            final RandomAccessFile file,
            final ArrayList<BlockIndexEntry> entries)
            throws IOException {

        final BinaryOutput data = new BinaryOutput(
                8 + entries.size()
                        * NetworkConnectionBinaryFormat.INDEX_ENTRY_SIZE);

        data.writeInt(NetworkConnectionBinaryFormat.INDEX_MAGIC);
        data.writeInt(entries.size());

        for (int index = 0; index < entries.size(); index++) {
            final BlockIndexEntry entry = entries.get(index);
            data.writeLong(entry.firstRecord);
            data.writeLong(entry.fileOffset);
            data.writeInt(entry.recordCount);
            data.writeInt(entry.uncompressedLength);
            data.writeInt(entry.storedLength);
        }

        final byte[] bytes = data.toByteArray();
        file.write(bytes);
        file.writeInt(checksum(bytes));
    }

    private static byte[] deflate(final byte[] input)
            throws IOException {

        final Deflater deflater = new Deflater(Deflater.BEST_SPEED);

        try {
            deflater.setInput(input);
            deflater.finish();

            final ByteArrayOutputStream output =
                    new ByteArrayOutputStream(input.length / 2);

            final byte[] buffer = new byte[8192];

            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer);

                if (written <= 0) {
                    throw new IOException(
                            "Unable to compress NGC block.");
                }

                output.write(buffer, 0, written);
            }

            return output.toByteArray();

        } finally {
            deflater.end();
        }
    }

    private static int checksum(final byte[] value) {
        final CRC32 checksum = new CRC32();
        checksum.update(value, 0, value.length);
        return (int) checksum.getValue();
    }

    private static void moveReplacing(
            final Path temporary,
            final Path target)
            throws IOException {

        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
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
}
