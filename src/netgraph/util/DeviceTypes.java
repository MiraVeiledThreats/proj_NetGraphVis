package netgraph.util;

/**
 * Shared device-type constants, display labels, and bit-mask helpers.
 *
 * A device can have one or more tags. The type constants remain ordinal
 * values because GraphStore uses them in compact bit operations.
 */
public final class DeviceTypes {
    public static final byte IT = 0;
    public static final byte OT = 1;
    public static final byte IOC = 2;
    public static final byte UNK = 3;

    public static final byte IT_MASK = (byte) (1 << IT);
    public static final byte OT_MASK = (byte) (1 << OT);
    public static final byte IOC_MASK = (byte) (1 << IOC);
    public static final byte UNK_MASK = (byte) (1 << UNK);

    public static final byte ALL_MASK =
            (byte) (IT_MASK | OT_MASK | IOC_MASK | UNK_MASK);

    private DeviceTypes() {
    }

    public static boolean isValidType(final byte type) {
        return type == IT
                || type == OT
                || type == IOC
                || type == UNK;
    }

    public static byte maskFor(final byte type) {
        if (!isValidType(type)) {
            throw new IllegalArgumentException(
                    "Unsupported device type: " + type);
        }

        return (byte) (1 << type);
    }

    public static byte normalizeMask(final byte mask) {
        return (byte) (mask & ALL_MASK);
    }

    public static boolean has(
            final byte mask,
            final byte type) {

        return (normalizeMask(mask) & maskFor(type)) != 0;
    }

    public static int tagCount(final byte mask) {
        int count = 0;
        final byte normalized = normalizeMask(mask);

        if (has(normalized, OT)) {
            count++;
        }

        if (has(normalized, IT)) {
            count++;
        }

        if (has(normalized, IOC)) {
            count++;
        }

        if (has(normalized, UNK)) {
            count++;
        }

        return count;
    }

    /**
     * Returns the sole tag in a one-tag mask, or -1 for zero/multi-tag masks.
     */
    public static byte singleTypeOrMinusOne(
            final byte mask) {

        final byte normalized = normalizeMask(mask);

        if (tagCount(normalized) != 1) {
            return -1;
        }

        if (has(normalized, OT)) {
            return OT;
        }

        if (has(normalized, IT)) {
            return IT;
        }

        if (has(normalized, IOC)) {
            return IOC;
        }

        return UNK;
    }

    /**
     * Returns a representative type for legacy single-type callers.
     * IOC has highest precedence, followed by OT, IT, and UNK.
     */
    public static byte primaryType(final byte mask) {
        final byte normalized = normalizeMask(mask);

        if (has(normalized, IOC)) {
            return IOC;
        }

        if (has(normalized, OT)) {
            return OT;
        }

        if (has(normalized, IT)) {
            return IT;
        }

        return UNK;
    }

    public static String typeName(final byte type) {
        switch (type) {
            case OT:
                return "OT";

            case IT:
                return "IT";

            case IOC:
                return "IOC";

            default:
                return "UNK";
        }
    }

    /**
     * Returns device tags in a stable display order.
     */
    public static String tagNames(final byte mask) {
        byte normalized = normalizeMask(mask);

        if (normalized == 0) {
            normalized = UNK_MASK;
        }

        final StringBuilder text = new StringBuilder();

        appendTypeName(text, normalized, OT);
        appendTypeName(text, normalized, IT);
        appendTypeName(text, normalized, IOC);
        appendTypeName(text, normalized, UNK);

        return text.toString();
    }

    private static void appendTypeName(
            final StringBuilder text,
            final byte mask,
            final byte type) {

        if (!has(mask, type)) {
            return;
        }

        if (text.length() > 0) {
            text.append(", ");
        }

        text.append(typeName(type));
    }
}