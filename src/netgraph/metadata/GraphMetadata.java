package netgraph.metadata;

import java.util.Arrays;
import java.util.HashMap;

import netgraph.util.IntBag;
import netgraph.util.IpUtil;

import netgraph.util.DeviceTypes;

/**
 * Analyst-editable node metadata and NIC/CIDR membership metadata.
 *
 * Node identity and high-volume traffic aggregates remain in GraphStore.
 * This class holds only editable or externally enriched metadata.
 */
public final class GraphMetadata {
    private final String[] nodeIp;
    private final String[] nodeHostname;
    private final String[] nodeAnalystComment;
    private final byte[] nodeManualTypeOverride;
    /*
     * A zero mask means no multi-tag manual override is active.
     * Non-zero masks can hold any combination of OT, IT, IOC, and UNK.
     */
    private final byte[] nodeManualTypeMasks;
    private volatile NetworkTable networkTable;
    private volatile String[] networkAnalystComment;
    private volatile String undeterminedNetworkAnalystComment;

    public GraphMetadata(final String[] nodeIp) {
        if (nodeIp == null) {
            throw new IllegalArgumentException(
                    "Node IP array is required.");
        }

        this.nodeIp = nodeIp;
        nodeHostname = new String[nodeIp.length];
        nodeAnalystComment = new String[nodeIp.length];
        nodeManualTypeOverride = new byte[nodeIp.length];
        nodeManualTypeMasks = new byte[nodeIp.length];

        Arrays.fill(
                nodeManualTypeOverride,
                (byte) -1);

        networkTable = NetworkTable.empty(nodeIp.length);
        networkAnalystComment = new String[0];
        undeterminedNetworkAnalystComment = "";
    }

    public String getNodeIp(final int nodeId) {
        return nodeIp[nodeId];
    }

    public String getNodeHostname(final int nodeId) {
        final String value = nodeHostname[nodeId];
        return value == null ? "" : value;
    }

    public void setNodeHostname(
            final int nodeId,
            final String hostname) {

        nodeHostname[nodeId] = normalize(hostname);
    }

    public String getNodeAnalystComment(final int nodeId) {
        final String value = nodeAnalystComment[nodeId];
        return value == null ? "" : value;
    }

    public void setNodeAnalystComment(
            final int nodeId,
            final String comment) {

        nodeAnalystComment[nodeId] = normalize(comment);
    }

    /**
     * Assigns a manual type override. Use -1 to clear the override and use
     * the graph's automatic type again.
     */
    /**
     * Legacy single-tag override API. A single tag is converted to a
     * one-bit manual mask. Use -1 to clear the manual override.
     */
    public void setNodeManualTypeOverride(
            final int nodeId,
            final byte type) {

        if (type != -1 && !DeviceTypes.isValidType(type)) {
            throw new IllegalArgumentException(
                    "Unsupported device type: " + type);
        }

        nodeManualTypeOverride[nodeId] = type;

        nodeManualTypeMasks[nodeId] =
                type < 0
                        ? 0
                        : DeviceTypes.maskFor(type);
    }

    public byte getNodeManualTypeOverride(final int nodeId) {
        return nodeManualTypeOverride[nodeId];
    }

    /**
     * Sets a one-to-four-tag analyst override.
     *
     * A non-zero mask is required. Call clearNodeManualTypeMask() to return
     * the node to automatic classification.
     */
    public void setNodeManualTypeMask(
            final int nodeId,
            final byte typeMask) {

        final byte normalized =
                DeviceTypes.normalizeMask(typeMask);

        if (normalized == 0) {
            throw new IllegalArgumentException(
                    "At least one device type tag is required.");
        }

        nodeManualTypeMasks[nodeId] = normalized;

        /*
         * Preserve backward-compatible behavior for code that checks the
         * legacy single-type override accessor.
         */
        nodeManualTypeOverride[nodeId] =
                DeviceTypes.singleTypeOrMinusOne(normalized);
    }

    public void clearNodeManualTypeMask(final int nodeId) {
        nodeManualTypeMasks[nodeId] = 0;
        nodeManualTypeOverride[nodeId] = -1;
    }

    public byte getNodeManualTypeMask(final int nodeId) {
        return nodeManualTypeMasks[nodeId];
    }

    /**
     * Returns all effective tags for a node.
     *
     * IOC remains present whenever GraphStore automatically identifies the
     * node as IOC from connection data. Analyst tags can add OT, IT, or UNK
     * but cannot suppress an automatic IOC indication.
     */
    public byte effectiveNodeTypeMask(
            final int nodeId,
            final byte automaticType) {

        byte effective =
                DeviceTypes.normalizeMask(
                        nodeManualTypeMasks[nodeId]);

        if (effective == 0) {
            final byte legacyManual =
                    nodeManualTypeOverride[nodeId];

            effective = legacyManual >= 0
                    ? DeviceTypes.maskFor(legacyManual)
                    : DeviceTypes.maskFor(automaticType);
        }

        if (automaticType == DeviceTypes.IOC) {
            effective |= DeviceTypes.IOC_MASK;
        }

        effective = DeviceTypes.normalizeMask(effective);

        return effective == 0
                ? DeviceTypes.UNK_MASK
                : effective;
    }

    /**
     * Legacy single-type compatibility method.
     */
    public byte effectiveNodeType(
            final int nodeId,
            final byte automaticType) {

        return DeviceTypes.primaryType(
                effectiveNodeTypeMask(
                        nodeId,
                        automaticType));
    }

    public String getNetworkAnalystComment(
            final int networkId) {

        final String value = networkAnalystComment[networkId];
        return value == null ? "" : value;
    }

    public void setNetworkAnalystComment(
            final int networkId,
            final String comment) {

        networkAnalystComment[networkId] =
                normalize(comment);
    }

    public String getUndeterminedNetworkAnalystComment() {
        return undeterminedNetworkAnalystComment == null
                ? ""
                : undeterminedNetworkAnalystComment;
    }

    public void setUndeterminedNetworkAnalystComment(
            final String comment) {

        undeterminedNetworkAnalystComment =
                normalize(comment);
    }

    public NetworkTable getNetworkTable() {
        return networkTable;
    }

    public void replaceNetworkTable(
            final NetworkTable replacement) {

        if (replacement == null) {
            networkTable = NetworkTable.empty(nodeIp.length);
            networkAnalystComment = new String[0];
            undeterminedNetworkAnalystComment = "";
            return;
        }

        if (replacement.getNodeCount() != nodeIp.length) {
            throw new IllegalArgumentException(
                    "Network membership table node count does not match "
                            + "graph node count.");
        }

        final NetworkTable previous = networkTable;
        final String[] previousComments =
                networkAnalystComment;

        final String[] replacementComments =
                new String[replacement.networkCount()];

        /*
         * Preserve notes when a NIC file is re-imported and a network still
         * has the same branch, CIDR, and display name.
         */
        for (int replacementId = 0;
                replacementId < replacement.networkCount();
                replacementId++) {

            for (int previousId = 0;
                    previousId < previous.networkCount();
                    previousId++) {

                if (!replacement.getBranch(replacementId).equals(
                        previous.getBranch(previousId))
                        || !replacement.getCidr(replacementId).equals(
                                previous.getCidr(previousId))
                        || !replacement.getName(replacementId).equals(
                                previous.getName(previousId))) {

                    continue;
                }

                if (previousId < previousComments.length) {
                    replacementComments[replacementId] =
                            previousComments[previousId];
                }

                break;
            }
        }

        networkTable = replacement;
        networkAnalystComment = replacementComments;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Immutable, packed network membership table.
     *
     * Node memberships use a compressed sparse row layout:
     * nodeMembershipStart[nodeId] through
     * nodeMembershipStart[nodeId + 1] identify entries in
     * membershipNetworkIds.
     */
    public static final class NetworkTable {
        private final String[] branch;
        private final String[] cidr;
        private final String[] name;
        private final int[] prefixLength;
        private final int[] nodeMembershipStart;
        private final int[] membershipNetworkIds;

        /* Precomputed immutable CIDR hierarchy for borders and clusters. */
        private final int[] parentNetworkIds;
        private final int[] firstChildStart;
        private final int[] childNetworkIds;

        public NetworkTable(final String[] branch,
                final String[] cidr,
                final String[] name,
                final int[] prefixLength,
                final int[] nodeMembershipStart,
                final int[] membershipNetworkIds) {
            if (branch == null || cidr == null || name == null
                    || prefixLength == null || nodeMembershipStart == null
                    || membershipNetworkIds == null) {
                throw new IllegalArgumentException("Network table arrays are required.");
            }
            if (branch.length != cidr.length || branch.length != name.length
                    || branch.length != prefixLength.length) {
                throw new IllegalArgumentException("Network metadata arrays have inconsistent lengths.");
            }
            if (nodeMembershipStart.length == 0 || nodeMembershipStart[0] != 0
                    || nodeMembershipStart[nodeMembershipStart.length - 1]
                            != membershipNetworkIds.length) {
                throw new IllegalArgumentException("Network membership offsets are invalid.");
            }
            this.branch = branch;
            this.cidr = cidr;
            this.name = name;
            this.prefixLength = prefixLength;
            this.nodeMembershipStart = nodeMembershipStart;
            this.membershipNetworkIds = membershipNetworkIds;
            final Hierarchy hierarchy = buildHierarchy(cidr, prefixLength);
            parentNetworkIds = hierarchy.parentNetworkIds;
            firstChildStart = hierarchy.firstChildStart;
            childNetworkIds = hierarchy.childNetworkIds;
        }

        public int networkCount() { return branch.length; }
        public int getNodeCount() { return nodeMembershipStart.length - 1; }
        public String getBranch(final int networkId) { return branch[networkId]; }
        public String getCidr(final int networkId) { return cidr[networkId]; }
        public String getName(final int networkId) { return name[networkId]; }
        public int getPrefixLength(final int networkId) { return prefixLength[networkId]; }
        public int getMembershipStart(final int nodeId) { return nodeMembershipStart[nodeId]; }
        public int getMembershipEnd(final int nodeId) { return nodeMembershipStart[nodeId + 1]; }
        public int getMembershipNetworkId(final int membershipIndex) { return membershipNetworkIds[membershipIndex]; }
        public int primaryNetworkForNode(final int nodeId) {
            final int start = nodeMembershipStart[nodeId];
            return start < nodeMembershipStart[nodeId + 1]
                    ? membershipNetworkIds[start] : -1;
        }

        /** Immediate strict-CIDR parent, or -1 when the network is a root. */
        public int getParentNetworkId(final int networkId) { return parentNetworkIds[networkId]; }
        public int getFirstChildStart(final int networkId) { return firstChildStart[networkId]; }
        public int getFirstChildEnd(final int networkId) { return firstChildStart[networkId + 1]; }
        public int getChildNetworkId(final int childIndex) { return childNetworkIds[childIndex]; }
        public boolean hasChildNetworks(final int networkId) {
            return firstChildStart[networkId] < firstChildStart[networkId + 1];
        }

        /** Stable identity used by analyst clusters across NGC graph rebuilds. */
        public String networkIdentityKey(final int networkId) {
            return safe(branch[networkId]) + '\u0000' + safe(cidr[networkId])
                    + '\u0000' + safe(name[networkId]);
        }

        public static NetworkTable empty(final int nodeCount) {
            return new NetworkTable(new String[0], new String[0], new String[0],
                    new int[0], new int[nodeCount + 1], new int[0]);
        }

        private static Hierarchy buildHierarchy(final String[] cidrs,
                final int[] prefixes) {
            final int count = cidrs.length;
            final int[] parent = new int[count];
            Arrays.fill(parent, -1);
            final long[] addresses = new long[count];
            Arrays.fill(addresses, -1L);
            final HashMap<Long, IntBag> byPrefix = new HashMap<Long, IntBag>();
            for (int id = 0; id < count; id++) {
                final long address = networkAddress(cidrs[id], prefixes[id]);
                addresses[id] = address;
                if (address < 0L || prefixes[id] < 0 || prefixes[id] > 32) continue;
                final long key = prefixKey(prefixes[id], address);
                IntBag ids = byPrefix.get(key);
                if (ids == null) { ids = new IntBag(); byPrefix.put(key, ids); }
                ids.add(id);
            }
            for (int child = 0; child < count; child++) {
                if (addresses[child] < 0L || prefixes[child] <= 0 || prefixes[child] > 32) continue;
                for (int parentPrefix = prefixes[child] - 1; parentPrefix >= 0; parentPrefix--) {
                    final long mask = mask(parentPrefix);
                    final IntBag candidates = byPrefix.get(prefixKey(parentPrefix,
                            addresses[child] & mask));
                    if (candidates == null) continue;
                    int chosen = -1;
                    for (int index = 0; index < candidates.size(); index++) {
                        final int candidate = candidates.get(index);
                        if (candidate != child && (chosen < 0 || candidate < chosen)) chosen = candidate;
                    }
                    if (chosen >= 0) { parent[child] = chosen; break; }
                }
            }
            final int[] childCounts = new int[count];
            for (int id = 0; id < count; id++) if (parent[id] >= 0) childCounts[parent[id]]++;
            final int[] starts = new int[count + 1];
            for (int id = 0; id < count; id++) starts[id + 1] = starts[id] + childCounts[id];
            final int[] childIds = new int[starts[count]];
            final int[] cursor = Arrays.copyOf(starts, count);
            for (int id = 0; id < count; id++) if (parent[id] >= 0) childIds[cursor[parent[id]]++] = id;
            return new Hierarchy(parent, starts, childIds);
        }

        private static long networkAddress(final String cidr, final int prefix) {
            if (cidr == null || prefix < 0 || prefix > 32) return -1L;
            final int slash = cidr.indexOf('/');
            if (slash <= 0) return -1L;
            final long ip = IpUtil.ipv4(cidr.substring(0, slash).trim());
            return ip < 0L ? -1L : ip & mask(prefix);
        }
        private static long mask(final int prefix) {
            return prefix == 0 ? 0L
                    : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        }
        private static long prefixKey(final int prefix, final long address) {
            return ((long) prefix << 32) ^ (address & 0xFFFFFFFFL);
        }
        private static String safe(final String value) { return value == null ? "" : value; }
        private static final class Hierarchy {
            private final int[] parentNetworkIds;
            private final int[] firstChildStart;
            private final int[] childNetworkIds;
            private Hierarchy(final int[] parentNetworkIds, final int[] firstChildStart,
                    final int[] childNetworkIds) {
                this.parentNetworkIds = parentNetworkIds;
                this.firstChildStart = firstChildStart;
                this.childNetworkIds = childNetworkIds;
            }
        }
    }
}