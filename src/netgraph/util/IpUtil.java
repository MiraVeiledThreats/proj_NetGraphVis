package netgraph.util;

/**
 * IP-literal validation and IPv4 conversion without DNS lookups.
 */
public final class IpUtil {
    private IpUtil() {
    }

    public static boolean isIpLiteral(final String value) {
        if (ipv4(value) >= 0L) {
            return true;
        }

        if (value == null || value.isEmpty()) {
            return false;
        }

        /*
         * IPv6 literal acceptance without DNS lookup. NIC enrichment remains
         * IPv4-CIDR-based because the supplied reference data is IPv4 CIDR.
         */
        for (int index = 0;
                index < value.length();
                index++) {

            final char character = value.charAt(index);

            final boolean valid =
                    (character >= '0' && character <= '9')
                            || (character >= 'a'
                                    && character <= 'f')
                            || (character >= 'A'
                                    && character <= 'F')
                            || character == ':'
                            || character == '.';

            if (!valid) {
                return false;
            }
        }

        return value.indexOf(':') >= 0;
    }

    /**
     * Returns an unsigned IPv4 value in the low 32 bits, or -1 for invalid
     * input.
     */
    public static long ipv4(final String value) {
        if (value == null) {
            return -1L;
        }

        int octet = 0;
        int octetCount = 0;
        long result = 0L;
        boolean digitSeen = false;

        for (int index = 0;
                index <= value.length();
                index++) {

            final char character =
                    index == value.length()
                            ? '.'
                            : value.charAt(index);

            if (character >= '0' && character <= '9') {
                octet = octet * 10 + character - '0';

                if (octet > 255) {
                    return -1L;
                }

                digitSeen = true;
                continue;
            }

            if (character != '.'
                    || !digitSeen
                    || octetCount >= 4) {

                return -1L;
            }

            result = (result << 8) | octet;

            octet = 0;
            octetCount++;
            digitSeen = false;
        }

        return octetCount == 4
                ? result & 0xFFFFFFFFL
                : -1L;
    }
}