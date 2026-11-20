package netgraph.cluster;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import netgraph.metadata.GraphMetadata;

/**
 * Thread-safe UI state for hierarchical, analyst-defined, and partial clusters.
 */
public final class ClusterViewState {
    private final BitSet expandedNetworkIds =
            new BitSet();

    private boolean undeterminedExpanded;

    private final ArrayList<AnalystClusterDefinition> analystClusters =
            new ArrayList<AnalystClusterDefinition>();

    /*
     * At most one partial definition is retained for one current visual scope.
     * Replacing a partial definition is intentional: a scope has one active
     * partition at a time, avoiding an ambiguous intersection of partitions.
     */
    private final ArrayList<PartialUnclusterDefinition> partialUnclusters =
            new ArrayList<PartialUnclusterDefinition>();

    /*
    * A partial-unclustering definition can generate several visible groups.
    * For device type mode, group keys are selected type masks; e.g., IOC is
    * DeviceTypes.IOC_MASK. This collection records only groups that the analyst
    * explicitly expanded into individual devices.
    */
    private final ArrayList<PartialGroupExpansion> expandedPartialGroups =
            new ArrayList<PartialGroupExpansion>();

    private long version;

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                (BitSet) expandedNetworkIds.clone(),
                undeterminedExpanded,
                analystClusters.toArray(
                        new AnalystClusterDefinition[
                                analystClusters.size()]),
                partialUnclusters.toArray(
                        new PartialUnclusterDefinition[
                                partialUnclusters.size()]),
                expandedPartialGroups.toArray(
                        new PartialGroupExpansion[
                                expandedPartialGroups.size()]),
                version);
    }

    public synchronized boolean isExpanded(
            final int networkId) {

        return networkId >= 0
                && expandedNetworkIds.get(networkId);
    }

    public synchronized boolean isUndeterminedExpanded() {
        return undeterminedExpanded;
    }

    public synchronized boolean hasAnyExpandedNetwork() {
        return !expandedNetworkIds.isEmpty()
                || undeterminedExpanded;
    }

    public synchronized boolean hasAnyClusterDrillDown() {
        return !expandedNetworkIds.isEmpty()
                || undeterminedExpanded
                || !partialUnclusters.isEmpty();
    }

    public synchronized void unclusterOneLevel(
            final int networkId) {

        if (networkId >= 0
                && !expandedNetworkIds.get(networkId)) {

            expandedNetworkIds.set(networkId);
            version++;
        }
    }

    public synchronized void unclusterUndetermined() {
        if (!undeterminedExpanded) {
            undeterminedExpanded = true;
            version++;
        }
    }

    public synchronized void collapseUndetermined() {
        if (undeterminedExpanded) {
            undeterminedExpanded = false;
            version++;
        }
    }

    public synchronized void unclusterAllDescendants(
            final int networkId,
            final GraphMetadata.NetworkTable networks) {

        if (networkId < 0 || networks == null) {
            return;
        }

        boolean changed = false;

        final int[] stack =
                new int[Math.max(
                        1,
                        networks.networkCount())];

        int size = 0;
        stack[size++] = networkId;

        while (size > 0) {
            final int current =
                    stack[--size];

            if (!expandedNetworkIds.get(current)) {
                expandedNetworkIds.set(current);
                changed = true;
            }

            for (int child =
                    networks.getFirstChildStart(current);
                    child < networks.getFirstChildEnd(current);
                    child++) {

                stack[size++] =
                        networks.getChildNetworkId(child);
            }
        }

        if (changed) {
            version++;
        }
    }

    public synchronized void collapseThisCluster(
            final int networkId,
            final GraphMetadata.NetworkTable networks) {

        if (networkId < 0 || networks == null) {
            return;
        }

        boolean changed = false;

        final int[] stack =
                new int[Math.max(
                        1,
                        networks.networkCount())];

        int size = 0;
        stack[size++] = networkId;

        while (size > 0) {
            final int current =
                    stack[--size];

            if (expandedNetworkIds.get(current)) {
                expandedNetworkIds.clear(current);
                changed = true;
            }

            for (int child =
                    networks.getFirstChildStart(current);
                    child < networks.getFirstChildEnd(current);
                    child++) {

                stack[size++] =
                        networks.getChildNetworkId(child);
            }
        }

        if (changed) {
            version++;
        }
    }

    public synchronized void collapseParentCluster(
            final int networkId,
            final GraphMetadata.NetworkTable networks) {

        if (networkId >= 0 && networks != null) {
            collapseThisCluster(
                    networks.getParentNetworkId(networkId),
                    networks);
        }
    }

    /**
     * Expands one visible partial group into its individual raw-device nodes.
     *
     * Example: the partial Device Type group whose key is IOC_MASK becomes one
     * visual node per IOC device. Other partial groups remain clustered.
     */
    public synchronized void unclusterPartialGroup(
            final PartialUnclusterDefinition definition,
            final int group) {

        if (definition == null) {
            throw new IllegalArgumentException(
                    "Partial-unclustering definition is required.");
        }

        final String definitionKey =
                definition.identityKey();

        for (int index = 0;
                index < expandedPartialGroups.size();
                index++) {

            if (expandedPartialGroups.get(index)
                    .matches(definitionKey, group)) {

                return;
            }
        }

        expandedPartialGroups.add(
                new PartialGroupExpansion(
                        definitionKey,
                        group));

        version++;
    }

    /**
     * Reforms the selected partial group as one visual cluster.
     */
    public synchronized void collapsePartialGroup(
            final PartialUnclusterDefinition definition,
            final int group) {

        if (definition == null) {
            return;
        }

        final String definitionKey =
                definition.identityKey();

        for (int index =
                expandedPartialGroups.size() - 1;
                index >= 0;
                index--) {

            if (expandedPartialGroups.get(index)
                    .matches(definitionKey, group)) {

                expandedPartialGroups.remove(index);
                version++;
                return;
            }
        }
    }

    /**
     * Restores the initial presentation: top-level networks plus one
     * Undetermined Network cluster. Analyst-defined network unions remain
     * unchanged, matching the prior behavior for analyst clusters.
     */
    public synchronized void resetToTopLevelClusters() {
        final boolean changed =
                !expandedNetworkIds.isEmpty()
                        || undeterminedExpanded
                        || !partialUnclusters.isEmpty()
                        || !expandedPartialGroups.isEmpty();

        if (!changed) {
            return;
        }

        expandedNetworkIds.clear();
        undeterminedExpanded = false;
        partialUnclusters.clear();
        expandedPartialGroups.clear();
        version++;
    }

    public synchronized void addAnalystCluster(
            final AnalystClusterDefinition definition) {

        if (definition == null) {
            throw new IllegalArgumentException(
                    "Analyst cluster is required.");
        }

        analystClusters.add(definition);
        version++;
    }

    public synchronized void removeAnalystCluster(
            final int analystClusterId) {

        if (analystClusterId >= 0
                && analystClusterId < analystClusters.size()) {

            analystClusters.remove(analystClusterId);
            version++;
        }
    }

    public synchronized void clearAnalystClusters() {
        if (!analystClusters.isEmpty()) {
            analystClusters.clear();
            version++;
        }
    }

    public synchronized void setPartialUnclustering(
            final PartialUnclusterDefinition definition) {

        if (definition == null) {
            throw new IllegalArgumentException(
                    "Partial-unclustering definition is required.");
        }

        for (int index = 0;
                index < partialUnclusters.size();
                index++) {

            if (sameScope(
                    partialUnclusters.get(index),
                    definition)) {

                clearPartialGroupExpansionsForScope(definition);

                partialUnclusters.set(index, definition);
                version++;
                return;
            }
        }
        clearPartialGroupExpansionsForScope(definition);
        partialUnclusters.add(definition);
        version++;
    }

    public synchronized void clearPartialUnclustering(
            final boolean undeterminedScope,
            final int networkId,
            final GraphMetadata.NetworkTable networks) {

        final String networkKey;

        if (undeterminedScope) {
            networkKey = null;

        } else {
            if (networks == null
                    || networkId < 0
                    || networkId >= networks.networkCount()) {

                return;
            }

            networkKey =
                    networks.networkIdentityKey(networkId);
        }

        boolean changed = false;

        for (int index =
                partialUnclusters.size() - 1;
                index >= 0;
                index--) {

            final PartialUnclusterDefinition definition =
                    partialUnclusters.get(index);

            if (definition.isUndeterminedScope()
                    == undeterminedScope
                    && (undeterminedScope
                            || networkKey.equals(
                                    definition.getScopeNetworkKeyUnsafe()))) {

                final PartialUnclusterDefinition removed =
                        partialUnclusters.remove(index);

                clearPartialGroupExpansions(
                        removed.identityKey());

                changed = true;
            }
        }

        if (changed) {
            version++;
        }
    }

    public synchronized void clearAllPartialUnclusterings() {
        if (!partialUnclusters.isEmpty()
                || !expandedPartialGroups.isEmpty()) {

            partialUnclusters.clear();
            expandedPartialGroups.clear();
            version++;
        }
    }

    private static boolean sameScope(
            final PartialUnclusterDefinition first,
            final PartialUnclusterDefinition second) {

        if (first.isUndeterminedScope()
                != second.isUndeterminedScope()) {

            return false;
        }

        if (first.isUndeterminedScope()) {
            return true;
        }

        return first.getScopeNetworkKeyUnsafe().equals(
                second.getScopeNetworkKeyUnsafe());
    }

    private void clearPartialGroupExpansionsForScope(
            final PartialUnclusterDefinition definition) {

        if (definition == null) {
            return;
        }

        /*
        * A replacement definition always invalidates group expansions produced by
        * the previous definition at this scope. The caller invokes this before
        * replacing or adding the scoped definition, so compare the existing
        * partial definitions to locate prior identities for the same scope.
        */
        for (int definitionIndex =
                partialUnclusters.size() - 1;
                definitionIndex >= 0;
                definitionIndex--) {

            final PartialUnclusterDefinition existing =
                    partialUnclusters.get(definitionIndex);

            final boolean sameUndeterminedScope =
                    existing.isUndeterminedScope()
                            && definition.isUndeterminedScope();

            final boolean sameNetworkScope =
                    !existing.isUndeterminedScope()
                            && !definition.isUndeterminedScope()
                            && existing.getScopeNetworkKeyUnsafe()
                                    .equals(
                                            definition
                                                    .getScopeNetworkKeyUnsafe());

            if (!sameUndeterminedScope
                    && !sameNetworkScope) {

                continue;
            }

            clearPartialGroupExpansions(
                    existing.identityKey());
        }
    }

    /**
     * Removes all expanded-group state associated with one partial definition.
     *
     * This is called when a partial definition is removed or replaced, preventing
     * stale expansion state from applying to a different partial request later.
     */
    private void clearPartialGroupExpansions(
            final String definitionKey) {

        if (definitionKey == null) {
            return;
        }

        for (int index =
                expandedPartialGroups.size() - 1;
                index >= 0;
                index--) {

            if (expandedPartialGroups.get(index)
                    .getDefinitionKey()
                    .equals(definitionKey)) {

                expandedPartialGroups.remove(index);
            }
        }
    }

    /**
     * Immutable identifier for one expanded partial group.
     *
     * Example:
     * - definitionKey identifies the partial Device Type split for Network A
     * - group identifies IOC, OT, IT, UNK, or Other Devices
     */
    private static final class PartialGroupExpansion {
        private final String definitionKey;
        private final int group;

        private PartialGroupExpansion(
                final String definitionKey,
                final int group) {

            if (definitionKey == null
                    || definitionKey.isEmpty()) {

                throw new IllegalArgumentException(
                        "Partial definition key is required.");
            }

            this.definitionKey = definitionKey;
            this.group = group;
        }

        private boolean matches(
                final String requestedDefinitionKey,
                final int requestedGroup) {

            return group == requestedGroup
                    && definitionKey.equals(
                            requestedDefinitionKey);
        }

        private String getDefinitionKey() {
            return definitionKey;
        }
    }

    public static final class Snapshot {
        private final BitSet expandedNetworkIds;
        private final boolean undeterminedExpanded;
        private final AnalystClusterDefinition[] analystClusters;
        private final PartialUnclusterDefinition[] partialUnclusters;
        private final PartialGroupExpansion[] expandedPartialGroups;
        private final long version;

        private Snapshot(
                final BitSet expandedNetworkIds,
                final boolean undeterminedExpanded,
                final AnalystClusterDefinition[] analystClusters,
                final PartialUnclusterDefinition[] partialUnclusters,
                final PartialGroupExpansion[] expandedPartialGroups,
                final long version) {

            this.expandedNetworkIds = expandedNetworkIds;
            this.undeterminedExpanded = undeterminedExpanded;
            this.analystClusters = analystClusters;
            this.partialUnclusters = partialUnclusters;
            this.expandedPartialGroups = expandedPartialGroups;
            this.version = version;
        }

        public boolean isExpanded(
                final int networkId) {

            return networkId >= 0
                    && expandedNetworkIds.get(networkId);
        }

        public boolean isUndeterminedExpanded() {
            return undeterminedExpanded;
        }

        public boolean hasAnyExpandedNetwork() {
            return !expandedNetworkIds.isEmpty()
                    || undeterminedExpanded;
        }

        public AnalystClusterDefinition[] getAnalystClustersUnsafe() {
            return analystClusters;
        }

        public PartialUnclusterDefinition[]
                getPartialUnclustersUnsafe() {

            return partialUnclusters;
        }

        public long getVersion() {
            return version;
        }

        public String signature() {
            final StringBuilder value =
                    new StringBuilder(192);

            value.append(version).append(':');

            for (int id =
                    expandedNetworkIds.nextSetBit(0);
                    id >= 0;
                    id =
                            expandedNetworkIds.nextSetBit(
                                    id + 1)) {

                value.append(id).append(',');
            }

            value.append('|')
                    .append(undeterminedExpanded ? '1' : '0')
                    .append('|');

            for (int index = 0;
                    index < analystClusters.length;
                    index++) {

                final AnalystClusterDefinition definition =
                        analystClusters[index];

                value.append(definition.getLabel())
                        .append('\u0001');

                final String[] keys =
                        definition.networkKeysUnsafe();

                for (int key = 0;
                        key < keys.length;
                        key++) {

                    value.append(keys[key])
                            .append('\u0002');
                }

                value.append('\u0003');
            }

            value.append('|');

            for (int index = 0;
                    index < partialUnclusters.length;
                    index++) {

                partialUnclusters[index]
                        .appendSignature(value);
            }

            return value.toString();
        }
        public boolean isPartialGroupExpanded(
                final PartialUnclusterDefinition definition,
                final int group) {

            if (definition == null) {
                return false;
            }

            final String definitionKey =
                    definition.identityKey();

            for (int index = 0;
                    index < expandedPartialGroups.length;
                    index++) {

                if (expandedPartialGroups[index]
                        .matches(definitionKey, group)) {

                    return true;
                }
            }

            return false;
        }
    }
}