package netgraph.nic;

/**
 * One validated IPv4 CIDR network-reference record.
 *
 * This object is used only for NIC reference-data records. The imported
 * network graph itself remains stored in primitive arrays within GraphStore.
 */
public final class NetworkRecord {
    private final String branch;
    private final String cidr;
    private final String name;
    private final int prefixLength;
    private final long networkAddress;

    public NetworkRecord(
            final String branch,
            final String cidr,
            final String name,
            final int prefixLength,
            final long networkAddress) {

        this.branch =
                branch == null ? "" : branch.trim();

        this.cidr =
                cidr == null ? "" : cidr.trim();

        this.name =
                name == null ? "" : name.trim();

        this.prefixLength = prefixLength;
        this.networkAddress = networkAddress;
    }

    public String getBranch() {
        return branch;
    }

    public String getCidr() {
        return cidr;
    }

    public String getName() {
        return name;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public long getNetworkAddress() {
        return networkAddress;
    }
}