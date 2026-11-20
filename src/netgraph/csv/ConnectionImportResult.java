package netgraph.csv;

import netgraph.graph.GraphStore;
import netgraph.util.RejectedRecordStore;

/**
 * Result of a connection-data CSV import.
 */
public final class ConnectionImportResult {
    private final GraphStore graphStore;
    private final int acceptedRecords;
    private final RejectedRecordStore rejectedRecords;

    public ConnectionImportResult(
            final GraphStore graphStore,
            final int acceptedRecords,
            final RejectedRecordStore rejectedRecords) {

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

    public String buildSummary() {
        return "Connection-data import complete.\n\n"
                + "Accepted records: "
                + acceptedRecords
                + "\n"
                + "Rejected records: "
                + rejectedRecords.getRejectedCount()
                + "\n"
                + "Nodes: "
                + graphStore.getNodeCount()
                + "\n"
                + "Aggregated directed edges: "
                + graphStore.getEdgeCount();
    }
}