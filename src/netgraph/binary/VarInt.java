package netgraph.binary;

import java.io.IOException;

/**
 * Unsigned variable-length integer codec for the NGC format.
 *
 * <p>Only non-negative Java {@code int} and {@code long} values are accepted.
 * The codec is intentionally small, deterministic, and independent of any
 * third-party serialization framework.</p>
 */
public final class VarInt {
    private VarInt() {
    }

    public static void writeUnsignedInt(
            final BinaryOutput output,
            final int value) {

        if (value < 0) {
            throw new IllegalArgumentException(
                    "Unsigned variable int cannot be negative.");
        }

        int remaining = value;

        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }

        output.writeByte(remaining);
    }

    public static void writeUnsignedLong(
            final BinaryOutput output,
            final long value) {

        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Unsigned variable long cannot be negative.");
        }

        long remaining = value;

        while ((remaining & ~0x7FL) != 0L) {
            output.writeByte((int) (remaining & 0x7FL) | 0x80);
            remaining >>>= 7;
        }

        output.writeByte((int) remaining);
    }

    public static int readUnsignedInt(final BinaryInput input)
            throws IOException {

        long value = 0L;

        for (int shift = 0; shift < 35; shift += 7) {
            final int next = input.readUnsignedByte();

            value |= (long) (next & 0x7F) << shift;

            if ((next & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) {
                    throw new IOException(
                            "Variable integer exceeds supported range.");
                }

                return (int) value;
            }
        }

        throw new IOException("Variable integer is malformed.");
    }

    public static long readUnsignedLong(final BinaryInput input)
            throws IOException {

        long value = 0L;

        for (int shift = 0; shift < 63; shift += 7) {
            final int next = input.readUnsignedByte();

            value |= (long) (next & 0x7F) << shift;

            if ((next & 0x80) == 0) {
                if (value < 0L) {
                    throw new IOException(
                            "Variable long exceeds supported range.");
                }

                return value;
            }
        }

        final int tenth = input.readUnsignedByte();

        if (tenth != 0) {
            throw new IOException("Variable long exceeds supported range.");
        }

        return value;
    }
}
