package netgraph.binary;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small, JDK-only big-endian binary writer used by the NGC writer.
 */
public final class BinaryOutput {
    private final ByteArrayOutputStream bytes;

    public BinaryOutput() {
        this(256);
    }

    public BinaryOutput(final int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException(
                    "Initial capacity cannot be negative.");
        }

        bytes = new ByteArrayOutputStream(initialCapacity);
    }

    public void writeByte(final int value) {
        bytes.write(value & 0xFF);
    }

    public void writeInt(final int value) {
        writeByte(value >>> 24);
        writeByte(value >>> 16);
        writeByte(value >>> 8);
        writeByte(value);
    }

    public void writeLong(final long value) {
        writeByte((int) (value >>> 56));
        writeByte((int) (value >>> 48));
        writeByte((int) (value >>> 40));
        writeByte((int) (value >>> 32));
        writeByte((int) (value >>> 24));
        writeByte((int) (value >>> 16));
        writeByte((int) (value >>> 8));
        writeByte((int) value);
    }

    public void writeBytes(final byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Byte array is required.");
        }

        bytes.write(value, 0, value.length);
    }

    public void writeBytes(
            final byte[] value,
            final int offset,
            final int length) {

        if (value == null || offset < 0 || length < 0
                || offset > value.length - length) {

            throw new IllegalArgumentException(
                    "Byte-array range is invalid.");
        }

        bytes.write(value, offset, length);
    }

    public void writeUnsignedInt(final int value) {
        VarInt.writeUnsignedInt(this, value);
    }

    public void writeUnsignedLong(final long value) {
        VarInt.writeUnsignedLong(this, value);
    }

    public void writeUtf8(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("Text value is required.");
        }

        final byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedInt(encoded.length);
        writeBytes(encoded);
    }

    public int size() {
        return bytes.size();
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }
}
