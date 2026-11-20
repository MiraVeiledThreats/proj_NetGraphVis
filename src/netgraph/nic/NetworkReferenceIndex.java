package netgraph.nic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import netgraph.csv.Csv;
import netgraph.csv.CsvRowConsumer;
import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.IntBag;
import netgraph.util.IpUtil;

/**
 * Loads IPv4 NIC/CIDR reference data and applies matching network memberships
 * to the graph's metadata table.
 */
public final class NetworkReferenceIndex {
    private final ArrayList<NetworkRecord> records =
            new ArrayList<NetworkRecord>();

    private final HashMap<Long, IntBag> prefixLookup =
            new HashMap<Long, IntBag>();

    private int rejectedCount;

    public static NetworkReferenceIndex load(
            final Path file)
            throws IOException {

        final NetworkReferenceIndex index =
                new NetworkReferenceIndex();

        /*
         * Mutable callback holder. Use headers[0], not headers.value.
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Map<String, Integer>[] headers =
                new Map[] { null };

        Csv.read(file, new CsvRowConsumer() {
            @Override
            public void onRow(
                    final int lineNumber,
                    final String rawRecord,
                    final String[] values) {

                try {
                    if (headers[0] == null) {
                        headers[0] = Csv.headerMap(values);

                        requireHeader(
                                headers[0],
                                "branch");

                        requireHeader(
                                headers[0],
                                "ip_cidr");

                        requireHeader(
                                headers[0],
                                "name");

                        return;
                    }

                    final String branch =
                            value(
                                    values,
                                    headers[0],
                                    "branch");

                    final String cidr =
                            value(
                                    values,
                                    headers[0],
                                    "ip_cidr");

                    final String name =
                            value(
                                    values,
                                    headers[0],
                                    "name");

                    index.add(branch, cidr, name);

                } catch (IllegalArgumentException exception) {
                    index.rejectedCount++;
                }
            }
        }, null);

        if (headers[0] == null) {
            throw new IOException(
                    "NIC reference file is empty.");
        }

        return index;
    }

    /**
     * Builds an in-memory NIC/CIDR reference index from Graph CSV NETWORK
     * records. This allows a Graph CSV import to restore memberships without
     * requiring a separate NIC CSV import.
     */
    public static NetworkReferenceIndex fromRecords(
            final String[] branches,
            final String[] cidrs,
            final String[] names) {

        if (branches == null
                || cidrs == null
                || names == null
                || branches.length != cidrs.length
                || branches.length != names.length) {

            throw new IllegalArgumentException(
                    "Network record arrays are missing or inconsistent.");
        }

        final NetworkReferenceIndex index =
                new NetworkReferenceIndex();

        for (int record = 0;
                record < cidrs.length;
                record++) {

            index.add(
                    branches[record],
                    cidrs[record],
                    names[record]);
        }

        return index;
    }

    public int getNetworkCount() {
        return records.size();
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    /** Rebuilds every graph-membership row from this NIC reference index. */
    public void applyTo(final GraphStore store) {
        if (store == null) {
            throw new IllegalArgumentException(
                    "Graph store is required.");
        }

        replaceMemberships(store, null, 0);
    }

    /**
     * Applies memberships after an append-style graph rebuild.
     *
     * <p>AdditionalConnectionBinaryImporter replays all pre-existing nodes in
     * node-ID order and only appends newly discovered IPs. If that invariant
     * and the NIC definitions held by the baseline graph both match, existing
     * compressed-sparse-row membership rows are copied and CIDR lookup is
     * performed only for appended nodes. All other cases deliberately fall
     * back to {@link #applyTo(GraphStore)}.</p>
     */
    public void applyToAfterAppend(
            final GraphStore appendedStore,
            final GraphStore baselineStore) {

        if (appendedStore == null || baselineStore == null) {
            throw new IllegalArgumentException(
                    "Appended and baseline graphs are required.");
        }

        final GraphMetadata.NetworkTable baselineTable =
                baselineStore.getGraphMetadata()
                        .getNetworkTable();

        if (!hasCompatiblePrefix(appendedStore, baselineStore)
                || !hasMatchingNetworkDefinitions(baselineTable)) {

            applyTo(appendedStore);
            return;
        }

        replaceMemberships(
                appendedStore,
                baselineTable,
                baselineStore.getNodeCount());
    }

    /**
     * Builds membership rows for {@code store}. A compatible prefix table,
     * when supplied, is copied verbatim; remaining nodes are evaluated against
     * this index. This keeps the normal full-rebuild path and the incremental
     * append path on the same output format.
     */
    private void replaceMemberships(
            final GraphStore store,
            final GraphMetadata.NetworkTable reusablePrefix,
            final int reusableNodeCount) {

        final GraphMetadata metadata = store.getGraphMetadata();
        final int nodeCount = store.getNodeCount();
        final int prefixNodeCount = reusablePrefix == null
                ? 0
                : Math.min(reusableNodeCount, nodeCount);

        final int[] membershipCounts = new int[nodeCount];
        int totalMemberships = 0;
        final IntBag matches = new IntBag();

        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            final int count;

            if (nodeId < prefixNodeCount) {
                count = reusablePrefix.getMembershipEnd(nodeId)
                        - reusablePrefix.getMembershipStart(nodeId);
            } else {
                lookup(metadata.getNodeIp(nodeId), matches);
                count = matches.size();
            }

            membershipCounts[nodeId] = count;

            if (count > Integer.MAX_VALUE - totalMemberships) {
                throw new IllegalStateException(
                        "Network membership count exceeds supported limit.");
            }

            totalMemberships += count;
        }

        final int[] nodeMembershipStart = new int[nodeCount + 1];

        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            nodeMembershipStart[nodeId + 1] =
                    nodeMembershipStart[nodeId] + membershipCounts[nodeId];
        }

        final int[] membershipNetworkIds = new int[totalMemberships];
        int writeIndex = 0;

        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            if (nodeId < prefixNodeCount) {
                final int start = reusablePrefix.getMembershipStart(nodeId);
                final int end = reusablePrefix.getMembershipEnd(nodeId);

                for (int membershipIndex = start;
                        membershipIndex < end;
                        membershipIndex++) {

                    membershipNetworkIds[writeIndex++] =
                            reusablePrefix.getMembershipNetworkId(
                                    membershipIndex);
                }

                continue;
            }

            lookup(metadata.getNodeIp(nodeId), matches);

            for (int match = 0; match < matches.size(); match++) {
                membershipNetworkIds[writeIndex++] = matches.get(match);
            }
        }

        final String[] branch = new String[records.size()];
        final String[] cidr = new String[records.size()];
        final String[] name = new String[records.size()];
        final int[] prefixLength = new int[records.size()];

        for (int networkId = 0; networkId < records.size(); networkId++) {
            final NetworkRecord record = records.get(networkId);
            branch[networkId] = record.getBranch();
            cidr[networkId] = record.getCidr();
            name[networkId] = record.getName();
            prefixLength[networkId] = record.getPrefixLength();
        }

        metadata.replaceNetworkTable(
                new GraphMetadata.NetworkTable(
                        branch,
                        cidr,
                        name,
                        prefixLength,
                        nodeMembershipStart,
                        membershipNetworkIds));
    }

    /**
     * Verifies that the rebuilt graph retains every baseline node at its
     * original node ID. This is the safety condition needed to copy prefix CSR
     * membership rows without re-running old-node CIDR lookup.
     */
    private static boolean hasCompatiblePrefix(
            final GraphStore appendedStore,
            final GraphStore baselineStore) {

        if (appendedStore.getNodeCount() < baselineStore.getNodeCount()) {
            return false;
        }

        final GraphMetadata appendedMetadata =
                appendedStore.getGraphMetadata();
        final GraphMetadata baselineMetadata =
                baselineStore.getGraphMetadata();

        for (int nodeId = 0;
                nodeId < baselineStore.getNodeCount();
                nodeId++) {

            final String appendedIp = appendedMetadata.getNodeIp(nodeId);
            final String baselineIp = baselineMetadata.getNodeIp(nodeId);

            if (baselineIp == null
                    ? appendedIp != null
                    : !baselineIp.equals(appendedIp)) {

                return false;
            }
        }

        return true;
    }

    /**
     * Ensures copied membership IDs still refer to the same NIC records and
     * retain their primary-most-specific ordering.
     */
    private boolean hasMatchingNetworkDefinitions(
            final GraphMetadata.NetworkTable table) {

        if (table == null || table.networkCount() != records.size()) {
            return false;
        }

        for (int networkId = 0; networkId < records.size(); networkId++) {
            final NetworkRecord record = records.get(networkId);

            if (!record.getBranch().equals(table.getBranch(networkId))
                    || !record.getCidr().equals(table.getCidr(networkId))
                    || !record.getName().equals(table.getName(networkId))
                    || record.getPrefixLength()
                            != table.getPrefixLength(networkId)) {

                return false;
            }
        }

        return true;
    }

    private void add(
            final String branch,
            final String cidr,
            final String name) {

        final int slash =
                cidr.indexOf('/');

        if (slash <= 0
                || slash == cidr.length() - 1) {

            throw new IllegalArgumentException(
                    "CIDR is invalid.");
        }

        final String ipText =
                cidr.substring(0, slash).trim();

        final int prefix =
                Integer.parseInt(
                        cidr.substring(slash + 1).trim());

        final long ip =
                IpUtil.ipv4(ipText);

        if (ip < 0L || prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException(
                    "CIDR is invalid.");
        }

        final long mask =
                prefix == 0
                        ? 0L
                        : (0xFFFFFFFFL
                                << (32 - prefix))
                                & 0xFFFFFFFFL;

        final long network = ip & mask;
        final int networkId = records.size();

        records.add(
                new NetworkRecord(
                        branch,
                        cidr,
                        name,
                        prefix,
                        network));

        final long lookupKey =
                prefixKey(prefix, network);

        IntBag ids = prefixLookup.get(lookupKey);

        if (ids == null) {
            ids = new IntBag();
            prefixLookup.put(lookupKey, ids);
        }

        ids.add(networkId);
    }

    private void lookup(
            final String ipText,
            final IntBag output) {

        output.clear();

        final long ip =
                IpUtil.ipv4(ipText);

        if (ip < 0L) {
            return;
        }

        /*
         * Descending traversal preserves most-specific membership at index
         * zero. Layout uses that membership as the primary grouping anchor.
         */
        for (int prefix = 32;
                prefix >= 0;
                prefix--) {

            final long mask =
                    prefix == 0
                            ? 0L
                            : (0xFFFFFFFFL
                                    << (32 - prefix))
                                    & 0xFFFFFFFFL;

            final IntBag matches =
                    prefixLookup.get(
                            prefixKey(
                                    prefix,
                                    ip & mask));

            if (matches == null) {
                continue;
            }

            for (int index = 0;
                    index < matches.size();
                    index++) {

                output.add(matches.get(index));
            }
        }
    }

    private static long prefixKey(
            final int prefix,
            final long network) {

        return ((long) prefix << 32)
                ^ (network & 0xFFFFFFFFL);
    }

    private static void requireHeader(
            final Map<String, Integer> headers,
            final String name) {

        if (!headers.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Required NIC header is missing: "
                            + name);
        }
    }

    private static String value(
            final String[] values,
            final Map<String, Integer> headers,
            final String header) {

        final Integer index =
                headers.get(header);

        if (index == null || index >= values.length) {
            return "";
        }

        return values[index].trim();
    }
}