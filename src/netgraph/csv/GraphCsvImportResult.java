package netgraph.csv;

import netgraph.graph.GraphStore;
import netgraph.nic.NetworkReferenceIndex;
import netgraph.util.RejectedRecordStore;

/**
 * Result of building a graph from a GraphCsvExporter-compatible file.
 */
public final class GraphCsvImportResult {
    private final GraphStore graphStore;
    private final int acceptedRecords;
    private final RejectedRecordStore rejectedRecords;
    private final NetworkReferenceIndex networkReferenceIndex;

    public GraphCsvImportResult(
            final GraphStore graphStore,
            final int acceptedRecords,
            final RejectedRecordStore rejectedRecords,
            final NetworkReferenceIndex networkReferenceIndex) {

        if (graphStore == null) {
            throw new IllegalArgumentException(
                    "Graph store is required.");
        }

        if (rejectedRecords == null) {
            throw new IllegalArgumentException(
                    "Rejected-record store is required.");
        }

        this.graphStore = graphStore;
        this.acceptedRecords = acceptedRecords;
        this.rejectedRecords = rejectedRecords;
        this.networkReferenceIndex = networkReferenceIndex;
    }

    public GraphStore getGraphStore() {
        return graphStore;
    }

    public int getAcceptedRecords() {
        return acceptedRecords;
    }

    public RejectedRecordStore getRejectedRecords() {
        return rejectedRecords;
    }

    public NetworkReferenceIndex getNetworkReferenceIndex() {
        return networkReferenceIndex;
    }

    public String buildSummary() {
        return "Graph CSV build complete.\n\n"
                + "Accepted CSV records: "
                + acceptedRecords
                + "\n"
                + "Rejected CSV records: "
                + rejectedRecords.getRejectedCount()
                + "\n"
                + "Nodes: "
                + graphStore.getNodeCount()
                + "\n"
                + "Aggregated directed edges: "
                + graphStore.getEdgeCount()
                + "\n"
                + "Destination-port aggregates: "
                + graphStore.getPortCount();
    }
}