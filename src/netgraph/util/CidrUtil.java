package netgraph.util;

/**
 * IPv4 CIDR containment helpers for NIC network metadata.
 */
public final class CidrUtil {
    private CidrUtil() {
    }

    /**
     * Returns true when candidateCidr is strictly contained within parentCidr.
     * A network is not considered a subnet of itself.
     */
    public static boolean isStrictSubnet(
            final String parentCidr,
            final String candidateCidr) {

        final Cidr parent = parse(parentCidr);
        final Cidr candidate = parse(candidateCidr);

        if (parent == null || candidate == null) {
            return false;
        }

        if (candidate.prefixLength <= parent.prefixLength) {
            return false;
        }

        return (candidate.networkAddress & parent.mask)
                == parent.networkAddress;
    }

    private static Cidr parse(final String value) {
        if (value == null) {
            return null;
        }

        final int slash = value.indexOf('/');

        if (slash <= 0 || slash == value.length() - 1) {
            return null;
        }

        final long address = IpUtil.ipv4(
                value.substring(0, slash).trim());

        if (address < 0L) {
            return null;
        }

        final int prefixLength;

        try {
            prefixLength = Integer.parseInt(
                    value.substring(slash + 1).trim());

        } catch (NumberFormatException exception) {
            return null;
        }

        if (prefixLength < 0 || prefixLength > 32) {
            return null;
        }

        final long mask =
                prefixLength == 0
                        ? 0L
                        : (0xFFFFFFFFL
                                << (32 - prefixLength))
                                & 0xFFFFFFFFL;

        return new Cidr(
                address & mask,
                prefixLength,
                mask);
    }

    private static final class Cidr {
        private final long networkAddress;
        private final int prefixLength;
        private final long mask;

        private Cidr(
                final long networkAddress,
                final int prefixLength,
                final long mask) {

            this.networkAddress = networkAddress;
            this.prefixLength = prefixLength;
            this.mask = mask;
        }
    }
}