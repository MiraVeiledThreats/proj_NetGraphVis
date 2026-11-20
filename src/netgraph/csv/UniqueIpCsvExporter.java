package netgraph.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import netgraph.graph.GraphStore;
import netgraph.metadata.GraphMetadata;

/**
 * Exports every unique source or destination IP currently represented in the
 * loaded graph.
 */
public final class UniqueIpCsvExporter {
    private UniqueIpCsvExporter() {
    }

    public static void export(
            final Path target,
            final GraphStore store)
            throws IOException {

        if (target == null) {
            throw new IllegalArgumentException(
                    "Unique-IP CSV export target is required.");
        }

        if (store == null) {
            throw new IllegalArgumentException(
                    "Graph store is required.");
        }

        final TreeSet<String> uniqueIps =
                new TreeSet<String>();

        final GraphMetadata metadata =
                store.getGraphMetadata();

        for (int nodeId = 0;
                nodeId < store.getNodeCount();
                nodeId++) {

            final String ip =
                    metadata.getNodeIp(nodeId);

            if (ip != null && !ip.trim().isEmpty()) {
                uniqueIps.add(ip.trim());
            }
        }

        try (BufferedWriter writer =
                Files.newBufferedWriter(
                        target,
                        StandardCharsets.UTF_8)) {

            writer.write("ip_addr");
            writer.newLine();

            for (String ip : uniqueIps) {
                writer.write(ip);
                writer.newLine();
            }
        }
    }
}