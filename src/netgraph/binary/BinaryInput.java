package netgraph.binary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Bounds-checked byte-array reader used for independently verified NGC blocks
 * and length-prefixed records.
 */
public final class BinaryInput {
    private final byte[] bytes;
    private final int limit;
    private int position;

    public BinaryInput(final byte[] bytes) {
        this(bytes, 0, bytes == null ? 0 : bytes.length);
    }

    public BinaryInput(
            final byte[] bytes,
            final int offset,
            final int length) {

        if (bytes == null || offset < 0 || length < 0
                || offset > bytes.length - length) {

            throw new IllegalArgumentException(
                    "Binary input range is invalid.");
        }

        this.bytes = bytes;
        position = offset;
        limit = offset + length;
    }

    public int remaining() {
        return limit - position;
    }

    public int readUnsignedByte() throws IOException {
        requireRemaining(1);
        return bytes[position++] & 0xFF;
    }

    public int readInt() throws IOException {
        requireRemaining(4);

        final int value =
                (bytes[position] & 0xFF) << 24
                        | (bytes[position + 1] & 0xFF) << 16
                        | (bytes[position + 2] & 0xFF) << 8
                        | (bytes[position + 3] & 0xFF);

        position += 4;
        return value;
    }

    public long readLong() throws IOException {
        requireRemaining(8);

        final long value =
                ((long) (bytes[position] & 0xFF) << 56)
                        | ((long) (bytes[position + 1] & 0xFF) << 48)
                        | ((long) (bytes[position + 2] & 0xFF) << 40)
                        | ((long) (bytes[position + 3] & 0xFF) << 32)
                        | ((long) (bytes[position + 4] & 0xFF) << 24)
                        | ((long) (bytes[position + 5] & 0xFF) << 16)
                        | ((long) (bytes[position + 6] & 0xFF) << 8)
                        | ((long) (bytes[position + 7] & 0xFF));

        position += 8;
        return value;
    }

    public int readUnsignedInt() throws IOException {
        return VarInt.readUnsignedInt(this);
    }

    public long readUnsignedLong() throws IOException {
        return VarInt.readUnsignedLong(this);
    }

    public void skipBytes(final int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative binary length is invalid.");
        }

        requireRemaining(length);
        position += length;
    }

    public byte[] readBytes(final int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative binary length is invalid.");
        }

        requireRemaining(length);

        final byte[] result = new byte[length];
        System.arraycopy(bytes, position, result, 0, length);
        position += length;
        return result;
    }

    public String readUtf8(final int maximumBytes) throws IOException {
        if (maximumBytes < 0) {
            throw new IllegalArgumentException(
                    "Maximum text length cannot be negative.");
        }

        final int length = readUnsignedInt();

        if (length > maximumBytes) {
            throw new IOException(
                    "UTF-8 value exceeds configured size limit.");
        }

        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    public void requireEnd() throws IOException {
        if (remaining() != 0) {
            throw new IOException("Unexpected trailing record bytes.");
        }
    }

    private void requireRemaining(final int required) throws IOException {
        if (required < 0 || remaining() < required) {
            throw new IOException("Unexpected end of binary data.");
        }
    }
}
