package netgraph.cluster;

import java.util.Arrays;

import netgraph.metadata.GraphMetadata;

/**
 * Immutable analyst-defined union of NIC networks. Network identity strings
 * keep the definition usable after a packed graph is rebuilt by NGC import.
 */
public final class AnalystClusterDefinition {
    private final String label;
    private final String[] networkKeys;

    public AnalystClusterDefinition(final String label, final String[] networkKeys) {
        this.label = normalizeLabel(label);
        if (networkKeys == null || networkKeys.length == 0) {
            throw new IllegalArgumentException("At least one network is required.");
        }
        final String[] normalizedKeys = Arrays.copyOf(networkKeys, networkKeys.length);
        Arrays.sort(normalizedKeys);
        int write = 0;
        for (int read = 0; read < normalizedKeys.length; read++) {
            final String key = normalizedKeys[read];
            if (key == null || key.isEmpty()
                    || (write > 0 && key.equals(normalizedKeys[write - 1]))) {
                continue;
            }
            normalizedKeys[write++] = key;
        }
        if (write == 0) {
            throw new IllegalArgumentException("At least one valid network is required.");
        }
        this.networkKeys = Arrays.copyOf(normalizedKeys, write);
    }

    public static AnalystClusterDefinition fromNetworkIds(
            final String label,
            final GraphMetadata.NetworkTable networks,
            final int[] networkIds) {
        if (networks == null || networkIds == null || networkIds.length == 0) {
            throw new IllegalArgumentException("Selected NIC networks are required.");
        }
        final String[] keys = new String[networkIds.length];
        for (int index = 0; index < networkIds.length; index++) {
            final int networkId = networkIds[index];
            if (networkId < 0 || networkId >= networks.networkCount()) {
                throw new IllegalArgumentException("Selected network ID is invalid.");
            }
            keys[index] = networks.networkIdentityKey(networkId);
        }
        return new AnalystClusterDefinition(label, keys);
    }

    public String getLabel() {
        return label;
    }

    public String[] getNetworkKeys() {
        return Arrays.copyOf(networkKeys, networkKeys.length);
    }

    String[] networkKeysUnsafe() {
        return networkKeys;
    }

    private static String normalizeLabel(final String value) {
        final String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Analyst cluster label is required.");
        }
        return normalized;
    }
}
