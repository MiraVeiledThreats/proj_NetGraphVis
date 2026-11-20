package netgraph.cluster;

import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;
import netgraph.util.DeviceTypes;
import netgraph.util.IpUtil;

/**
 * Immutable analyst-directed partition of one currently visible network cluster
 * or the Undetermined Network cluster.
 *
 * <p>The definition stores only the small analyst request. Its derived node
 * match mask is cached weakly per GraphStore, so repeated projection rebuilds
 * caused by pan, layout, or display-filter changes do not rescan all topology
 * for a netblock or connection selection.</p>
 *
 * <p>A partial definition applies only while its scope is the active visual
 * network for a node. If the analyst later expands that network normally, the
 * normal hierarchy takes over and this definition becomes inactive until the
 * scope is collapsed again.</p>
 */
public final class PartialUnclusterDefinition {
    public static final byte MODE_DEVICE_TYPE = 1;
    public static final byte MODE_NETBLOCK = 2;
    public static final byte MODE_CONNECTION = 3;

    private final boolean undeterminedScope;
    private final String scopeNetworkKey;
    private final byte mode;

    private final byte selectedDeviceTypes;
    private final IpNetblock netblock;
    private final String[] connectionAddresses;

    /*
     * This cache is deliberately not part of the logical definition or its
     * signature. The weak GraphStore reference prevents an obsolete graph from
     * being retained after a fresh import.
     */
    private transient WeakReference<GraphStore> cachedStore =
            new WeakReference<GraphStore>(null);

    private transient BitSet cachedMatchingNodes;

    private PartialUnclusterDefinition(
            final boolean undeterminedScope,
            final String scopeNetworkKey,
            final byte mode,
            final byte selectedDeviceTypes,
            final IpNetblock netblock,
            final String[] connectionAddresses) {

        this.undeterminedScope = undeterminedScope;
        this.scopeNetworkKey = scopeNetworkKey;
        this.mode = mode;
        this.selectedDeviceTypes = selectedDeviceTypes;
        this.netblock = netblock;
        this.connectionAddresses = connectionAddresses;
    }

    public static PartialUnclusterDefinition forNetworkDeviceTypes(
            final GraphMetadata.NetworkTable networks,
            final int networkId,
            final byte selectedTypes) {

        return deviceTypes(
                false,
                requireNetworkKey(networks, networkId),
                selectedTypes);
    }

    public static PartialUnclusterDefinition forUndeterminedDeviceTypes(
            final byte selectedTypes) {

        return deviceTypes(true, null, selectedTypes);
    }

    public static PartialUnclusterDefinition forNetworkNetblock(
            final GraphMetadata.NetworkTable networks,
            final int networkId,
            final String address,
            final String cidr) {

        return netblock(
                false,
                requireNetworkKey(networks, networkId),
                address,
                cidr);
    }

    public static PartialUnclusterDefinition forUndeterminedNetblock(
            final String address,
            final String cidr) {

        return netblock(true, null, address, cidr);
    }

    public static PartialUnclusterDefinition forNetworkConnections(
            final GraphMetadata.NetworkTable networks,
            final int networkId,
            final String commaSeparatedAddresses) {

        return connections(
                false,
                requireNetworkKey(networks, networkId),
                commaSeparatedAddresses);
    }

    public static PartialUnclusterDefinition forUndeterminedConnections(
            final String commaSeparatedAddresses) {

        return connections(
                true,
                null,
                commaSeparatedAddresses);
    }

        public boolean isUndeterminedScope() {
        return undeterminedScope;
        }

        /**
         * Internal stable identity for the NIC/CIDR network scope of this partial
         * definition. Null is valid only for the Undetermined Network scope.
         *
         * This method is intentionally package-private: only classes in
         * netgraph.cluster need direct access to resolve partial definitions during
         * projection construction and view-state maintenance.
         */
        String getScopeNetworkKeyUnsafe() {
        return scopeNetworkKey;
        }

        /**
         * Returns true when this partial definition applies to the supplied active
         * visual scope.
         *
         * @param undetermined true when the current device has no NIC/CIDR membership
         * @param networkIdentityKey active network identity key, or null for the
         *        Undetermined Network scope
         */
        public boolean appliesToScope(
                final boolean undetermined,
                final String networkIdentityKey) {

        if (undeterminedScope != undetermined) {
                return false;
        }

        if (undetermined) {
                return true;
        }

        return scopeNetworkKey != null
                && scopeNetworkKey.equals(networkIdentityKey);
        }

        /**
         * Returns a small stable partition key for a device.
         *
         * Device Type mode:
         * - selected matching effective type mask, for example IOC_MASK
         * - zero for devices not matching any selected type
         *
         * Netblock and Connection modes:
         * - one for matching devices
         * - zero for remaining devices
         */
        public int groupForNode(
                final GraphStore store,
                final GraphMetadata metadata,
                final int nodeId,
                final byte effectiveTags) {

        if (mode == MODE_DEVICE_TYPE) {
                return DeviceTypes.normalizeMask(
                        (byte) (effectiveTags & selectedDeviceTypes))
                        & 0xFF;
        }

        return matchingNodes(
                store,
                metadata)
                .get(nodeId)
                        ? 1
                        : 0;
        }

        /**
         * Full visual-node label for one partial group.
         */
        public String groupLabel(
                final String scopeLabel,
                final int group) {

        final String safeScope =
                scopeLabel == null
                        || scopeLabel.trim().isEmpty()
                                ? "Network"
                                : scopeLabel;

        if (mode == MODE_DEVICE_TYPE) {
                return safeScope
                        + " \u2014 "
                        + (group == 0
                                ? "Other Device Types"
                                : DeviceTypes.tagNames(
                                        (byte) group));
        }

        if (mode == MODE_NETBLOCK) {
                return safeScope
                        + " \u2014 "
                        + (group == 1
                                ? "Netblock "
                                        + netblock.getDisplayText()
                                : "Remaining Devices");
        }

        return safeScope
                + " \u2014 "
                + (group == 1
                        ? "Connected to Selected Address(es)"
                        : "Remaining Devices");
        }

        /**
         * Stable identity for one partial-unclustering request. It deliberately
         * excludes transient GraphStore/BitSet caches.
         */
        public String identityKey() {
        final StringBuilder value =
                new StringBuilder(128);

        appendSignature(value);

        return value.toString();
        }

        /**
         * Short label used in context-menu actions.
         *
         * Examples:
         * - IOC Devices
         * - OT, IT Devices
         * - Other Devices
         * - Netblock Devices
         * - Connected Devices
         */
        public String groupDescription(
                final int group) {

        if (mode == MODE_DEVICE_TYPE) {
                return group == 0
                        ? "Other Devices"
                        : DeviceTypes.tagNames(
                                (byte) group)
                                + " Devices";
        }

        if (mode == MODE_NETBLOCK) {
                return group == 1
                        ? "Netblock Devices"
                        : "Remaining Devices";
        }

        return group == 1
                ? "Connected Devices"
                : "Remaining Devices";
        }

        /**
         * Appends the stable content of this partial definition to a caller-provided
         * signature buffer. This is used by ClusterViewState projection caching.
         */
        void appendSignature(
                final StringBuilder output) {

        if (output == null) {
                throw new IllegalArgumentException(
                        "Signature output is required.");
        }

        output.append(mode)
                .append('\u0001')
                .append(
                        undeterminedScope
                                ? 'U'
                                : 'N')
                .append('\u0001')
                .append(
                        scopeNetworkKey == null
                                ? ""
                                : scopeNetworkKey)
                .append('\u0001');

        if (mode == MODE_DEVICE_TYPE) {
                output.append(
                        selectedDeviceTypes & 0xFF);

        } else if (mode == MODE_NETBLOCK) {
                output.append(
                        netblock.getDisplayText());

        } else {
                for (int index = 0;
                        index < connectionAddresses.length;
                        index++) {

                output.append(
                        connectionAddresses[index])
                        .append('\u0002');
                }
        }

        output.append('\u0003');
        }

    private static PartialUnclusterDefinition deviceTypes(
            final boolean undeterminedScope,
            final String scopeNetworkKey,
            final byte selectedTypes) {

        final byte normalized =
                DeviceTypes.normalizeMask(selectedTypes);

        if (normalized == 0) {
            throw new IllegalArgumentException(
                    "Select at least one device type.");
        }

        return new PartialUnclusterDefinition(
                undeterminedScope,
                scopeNetworkKey,
                MODE_DEVICE_TYPE,
                normalized,
                null,
                new String[0]);
    }

    private static PartialUnclusterDefinition netblock(
            final boolean undeterminedScope,
            final String scopeNetworkKey,
            final String address,
            final String cidr) {

        return new PartialUnclusterDefinition(
                undeterminedScope,
                scopeNetworkKey,
                MODE_NETBLOCK,
                (byte) 0,
                IpNetblock.parse(address, cidr),
                new String[0]);
    }

    private static PartialUnclusterDefinition connections(
            final boolean undeterminedScope,
            final String scopeNetworkKey,
            final String commaSeparatedAddresses) {

        return new PartialUnclusterDefinition(
                undeterminedScope,
                scopeNetworkKey,
                MODE_CONNECTION,
                (byte) 0,
                null,
                parseConnectionAddresses(commaSeparatedAddresses));
    }

    private static String requireNetworkKey(
            final GraphMetadata.NetworkTable networks,
            final int networkId) {

        if (networks == null
                || networkId < 0
                || networkId >= networks.networkCount()) {

            throw new IllegalArgumentException(
                    "A valid network cluster is required.");
        }

        return networks.networkIdentityKey(networkId);
    }

    private static String[] parseConnectionAddresses(
            final String commaSeparatedAddresses) {

        if (commaSeparatedAddresses == null
                || commaSeparatedAddresses.trim().isEmpty()) {

            throw new IllegalArgumentException(
                    "Enter at least one IP address.");
        }

        final String[] values =
                commaSeparatedAddresses.split(",", -1);

        final String[] normalized =
                new String[values.length];

        int write = 0;

        for (int index = 0;
                index < values.length;
                index++) {

            final String value =
                    values[index] == null
                            ? ""
                            : values[index].trim();

            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "Connection address list contains a blank value.");
            }

            if (!IpUtil.isIpLiteral(value)) {
                throw new IllegalArgumentException(
                        "Invalid IP literal: " + value);
            }

            normalized[write++] = value;
        }

        Arrays.sort(normalized, 0, write);

        int unique = 0;

        for (int index = 0;
                index < write;
                index++) {

            if (unique == 0
                    || !normalized[index].equals(
                            normalized[unique - 1])) {

                normalized[unique++] =
                        normalized[index];
            }
        }

        return Arrays.copyOf(normalized, unique);
    }

    private BitSet matchingNodes(
            final GraphStore store,
            final GraphMetadata metadata) {

        final GraphStore cached =
                cachedStore == null
                        ? null
                        : cachedStore.get();

        final BitSet existing =
                cachedMatchingNodes;

        if (cached == store && existing != null) {
            return existing;
        }

        synchronized (this) {
            final GraphStore synchronizedCached =
                    cachedStore == null
                            ? null
                            : cachedStore.get();

            if (synchronizedCached == store
                    && cachedMatchingNodes != null) {

                return cachedMatchingNodes;
            }

            final BitSet calculated =
                    new BitSet(store.getNodeCount());

            if (mode == MODE_NETBLOCK) {
                for (int nodeId = 0;
                        nodeId < store.getNodeCount();
                        nodeId++) {

                    if (netblock.contains(
                            metadata.getNodeIp(nodeId))) {

                        calculated.set(nodeId);
                    }
                }

            } else if (mode == MODE_CONNECTION) {
                final HashSet<String> requested =
                        new HashSet<String>(
                                Math.max(
                                        16,
                                        connectionAddresses.length * 2));

                for (int index = 0;
                        index < connectionAddresses.length;
                        index++) {

                    requested.add(
                            connectionAddresses[index]);
                }

                final BitSet requestedNodeIds =
                        new BitSet(store.getNodeCount());

                for (int nodeId = 0;
                        nodeId < store.getNodeCount();
                        nodeId++) {

                    if (requested.contains(
                            metadata.getNodeIp(nodeId))) {

                        requestedNodeIds.set(nodeId);
                    }
                }

                /*
                 * Scan each packed directed edge once. A device qualifies when
                 * it is the opposite endpoint of an edge containing one of the
                 * analyst-provided IPs. This covers both to/from traffic.
                 */
                for (int edgeId = 0;
                        edgeId < store.getEdgeCount();
                        edgeId++) {

                    final int source =
                            store.getEdgeSourceNodeId(edgeId);

                    final int destination =
                            store.getEdgeTargetNodeId(edgeId);

                    if (requestedNodeIds.get(source)) {
                        calculated.set(destination);
                    }

                    if (requestedNodeIds.get(destination)) {
                        calculated.set(source);
                    }
                }
            }

            cachedStore =
                    new WeakReference<GraphStore>(store);

            cachedMatchingNodes = calculated;

            return calculated;
        }
    }

    /**
     * One parsed IPv4 or IPv6 CIDR. IPv4 is handled through the existing
     * allocation-free IpUtil fast path. IPv6 parsing is required only during
     * the first match-mask build and is then cached as a BitSet.
     */
    private static final class IpNetblock {
        private final byte[] networkAddress;
        private final int prefixLength;
        private final String displayText;

        private IpNetblock(
                final byte[] networkAddress,
                final int prefixLength,
                final String displayText) {

            this.networkAddress = networkAddress;
            this.prefixLength = prefixLength;
            this.displayText = displayText;
        }

        static IpNetblock parse(
                final String addressInput,
                final String cidrInput) {

            String address =
                    addressInput == null
                            ? ""
                            : addressInput.trim();

            String prefixText =
                    cidrInput == null
                            ? ""
                            : cidrInput.trim();

            final int slash =
                    address.indexOf('/');

            if (slash >= 0) {
                if (!prefixText.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Provide CIDR either in the address field or "
                                    + "the CIDR field, not both.");
                }

                prefixText =
                        address.substring(slash + 1).trim();

                address =
                        address.substring(0, slash).trim();
            }

            if (prefixText.startsWith("/")) {
                prefixText =
                        prefixText.substring(1).trim();
            }

            if (address.isEmpty()
                    || prefixText.isEmpty()
                    || prefixText.indexOf('/') >= 0) {

                throw new IllegalArgumentException(
                        "Enter an IP address and CIDR prefix.");
            }

            final byte[] bytes =
                    addressBytes(address);

            final int prefix;

            try {
                prefix = Integer.parseInt(prefixText);

            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "CIDR prefix must be a whole number.",
                        exception);
            }

            final int maximum =
                    bytes.length == 4
                            ? 32
                            : 128;

            if (prefix < 0 || prefix > maximum) {
                throw new IllegalArgumentException(
                        "CIDR prefix must be between 0 and "
                                + maximum + ".");
            }

            final byte[] normalized =
                    Arrays.copyOf(bytes, bytes.length);

            zeroHostBits(normalized, prefix);

            return new IpNetblock(
                    normalized,
                    prefix,
                    address + "/" + prefix);
        }

        String getDisplayText() {
            return displayText;
        }

        boolean contains(final String candidate) {
            if (candidate == null
                    || candidate.trim().isEmpty()) {

                return false;
            }

            final long ipv4 =
                    IpUtil.ipv4(candidate);

            if (networkAddress.length == 4
                    && ipv4 >= 0L) {

                final long mask =
                        prefixLength == 0
                                ? 0L
                                : (0xFFFFFFFFL
                                        << (32 - prefixLength))
                                        & 0xFFFFFFFFL;

                return (ipv4 & mask)
                        == unsignedIpv4(networkAddress);
            }

            if (networkAddress.length != 16
                    || candidate.indexOf(':') < 0
                    || !IpUtil.isIpLiteral(candidate)) {

                return false;
            }

            final byte[] candidateBytes;

            try {
                candidateBytes =
                        InetAddress.getByName(candidate)
                                .getAddress();

            } catch (UnknownHostException exception) {
                return false;
            }

            if (candidateBytes.length != 16) {
                return false;
            }

            return samePrefix(
                    networkAddress,
                    candidateBytes,
                    prefixLength);
        }

        private static byte[] addressBytes(
                final String value) {

            final long ipv4 =
                    IpUtil.ipv4(value);

            if (ipv4 >= 0L) {
                return new byte[] {
                        (byte) (ipv4 >>> 24),
                        (byte) (ipv4 >>> 16),
                        (byte) (ipv4 >>> 8),
                        (byte) ipv4
                };
            }

            if (!IpUtil.isIpLiteral(value)
                    || value.indexOf(':') < 0) {

                throw new IllegalArgumentException(
                        "Invalid IP literal: " + value);
            }

            try {
                final byte[] bytes =
                        InetAddress.getByName(value)
                                .getAddress();

                if (bytes.length != 16) {
                    throw new IllegalArgumentException(
                            "Invalid IPv6 literal: " + value);
                }

                return bytes;

            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException(
                        "Invalid IPv6 literal: " + value,
                        exception);
            }
        }

        private static void zeroHostBits(
                final byte[] value,
                final int prefix) {

            final int fullBytes =
                    prefix / 8;

            final int partialBits =
                    prefix % 8;

            if (fullBytes < value.length
                    && partialBits != 0) {

                final int mask =
                        (0xFF << (8 - partialBits))
                                & 0xFF;

                value[fullBytes] =
                        (byte) ((value[fullBytes] & 0xFF)
                                & mask);
            }

            final int firstZero =
                    partialBits == 0
                            ? fullBytes
                            : fullBytes + 1;

            for (int index = firstZero;
                    index < value.length;
                    index++) {

                value[index] = 0;
            }
        }

        private static boolean samePrefix(
                final byte[] first,
                final byte[] second,
                final int prefix) {

            final int fullBytes =
                    prefix / 8;

            for (int index = 0;
                    index < fullBytes;
                    index++) {

                if (first[index] != second[index]) {
                    return false;
                }
            }

            final int partialBits =
                    prefix % 8;

            if (partialBits == 0) {
                return true;
            }

            final int mask =
                    (0xFF << (8 - partialBits))
                            & 0xFF;

            return ((first[fullBytes] ^ second[fullBytes])
                    & mask) == 0;
        }

        private static long unsignedIpv4(
                final byte[] bytes) {

            return ((long) (bytes[0] & 0xFF) << 24)
                    | ((long) (bytes[1] & 0xFF) << 16)
                    | ((long) (bytes[2] & 0xFF) << 8)
                    | ((long) (bytes[3] & 0xFF));
        }
    }
}