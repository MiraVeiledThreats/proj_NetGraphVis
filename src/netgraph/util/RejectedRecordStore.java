package netgraph.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded in-memory rejection summary plus complete temporary CSV spool file.
 */
public final class RejectedRecordStore
        implements AutoCloseable {

    private static final int MAX_SAMPLES = 100;

    private final ArrayList<String> samples =
            new ArrayList<String>(MAX_SAMPLES);

    private final Path spoolFile;
    private BufferedWriter writer;
    private int rejectedCount;

    public RejectedRecordStore() {
        try {
            spoolFile = Files.createTempFile(
                    "network-graph-rejected-",
                    ".csv");

            writer = Files.newBufferedWriter(
                    spoolFile,
                    StandardCharsets.UTF_8);

            writer.write("line_number,reason,raw_record");
            writer.newLine();

        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Unable to initialize rejected-record store.",
                    exception);
        }
    }

    public void record(
            final int lineNumber,
            final String reason,
            final String rawRecord) {

        rejectedCount++;

        if (samples.size() < MAX_SAMPLES) {
            samples.add(
                    "Line "
                            + lineNumber
                            + ": "
                            + (reason == null ? "" : reason));
        }

        try {
            ensureOpen();

            writer.write(Integer.toString(lineNumber));
            writer.write(',');
            writer.write(csvEscape(reason));
            writer.write(',');
            writer.write(csvEscape(rawRecord));
            writer.newLine();

        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Unable to record rejected CSV row.",
                    exception);
        }
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public List<String> getSamples() {
        return Collections.unmodifiableList(samples);
    }

    public void exportTo(final Path target)
            throws IOException {

        if (target == null) {
            throw new IllegalArgumentException(
                    "Export target is required.");
        }

        ensureOpen();
        writer.flush();

        Files.copy(
                spoolFile,
                target,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void close() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }

        try {
            Files.deleteIfExists(spoolFile);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private void ensureOpen() {
        if (writer == null) {
            throw new IllegalStateException(
                    "Rejected-record store is closed.");
        }
    }

    private static String csvEscape(final String value) {
        final String safe = value == null ? "" : value;

        return "\""
                + safe.replace("\"", "\"\"")
                + "\"";
    }
}