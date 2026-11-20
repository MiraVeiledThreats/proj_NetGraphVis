package netgraph.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import netgraph.util.RejectedRecordStore;

/**
 * Minimal dependency-free CSV parser with quoted-field and multiline-record
 * support.
 */
public final class Csv {
    private Csv() {
    }

    public static void read(
            final Path file,
            final CsvRowConsumer consumer,
            final RejectedRecordStore rejected)
            throws IOException {

        if (file == null) {
            throw new IllegalArgumentException(
                    "CSV file path is required.");
        }

        if (consumer == null) {
            throw new IllegalArgumentException(
                    "CSV row consumer is required.");
        }

        try (BufferedReader reader =
                Files.newBufferedReader(
                        file,
                        StandardCharsets.UTF_8)) {

            final StringBuilder record =
                    new StringBuilder();

            int recordStartLine = 1;
            int lineNumber = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (record.length() > 0) {
                    record.append('\n');
                }

                record.append(line);

                if (hasUnclosedQuotes(record)) {
                    continue;
                }

                try {
                    final String raw = record.toString();

                    consumer.onRow(
                            recordStartLine,
                            raw,
                            parse(raw));

                } catch (IllegalArgumentException exception) {
                    if (rejected != null) {
                        rejected.record(
                                recordStartLine,
                                exception.getMessage(),
                                record.toString());
                    }
                }

                record.setLength(0);
                recordStartLine = lineNumber + 1;
            }

            if (record.length() > 0
                    && rejected != null) {

                rejected.record(
                        recordStartLine,
                        "Unclosed quoted field in CSV record.",
                        record.toString());
            }
        }
    }

    public static Map<String, Integer> headerMap(
            final String[] headers) {

        if (headers == null || headers.length == 0) {
            throw new IllegalArgumentException(
                    "CSV header row is empty.");
        }

        final HashMap<String, Integer> map =
                new HashMap<String, Integer>();

        for (int index = 0;
                index < headers.length;
                index++) {

            final String normalized =
                    normalizeHeader(headers[index]);

            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "CSV contains an empty header.");
            }

            if (map.put(normalized, index) != null) {
                throw new IllegalArgumentException(
                        "CSV contains duplicate header: "
                                + normalized);
            }
        }

        return map;
    }

    public static String[] parse(final String rawRecord) {
        if (rawRecord == null) {
            throw new IllegalArgumentException(
                    "CSV record is null.");
        }

        final ArrayList<String> values =
                new ArrayList<String>();

        final StringBuilder value =
                new StringBuilder();

        boolean quoted = false;

        for (int index = 0;
                index < rawRecord.length();
                index++) {

            final char character =
                    rawRecord.charAt(index);

            if (character == '"') {
                final boolean escaped =
                        quoted
                                && index + 1 < rawRecord.length()
                                && rawRecord.charAt(index + 1)
                                        == '"';

                if (escaped) {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }

            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);

            } else {
                value.append(character);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException(
                    "Unclosed quoted field in CSV record.");
        }

        values.add(value.toString());

        return values.toArray(
                new String[values.size()]);
    }

    public static String normalizeHeader(
            final String value) {

        if (value == null) {
            return "";
        }

        return value.replace("\uFEFF", "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');
    }

    private static boolean hasUnclosedQuotes(
            final CharSequence text) {

        boolean quoted = false;

        for (int index = 0;
                index < text.length();
                index++) {

            if (text.charAt(index) != '"') {
                continue;
            }

            if (quoted
                    && index + 1 < text.length()
                    && text.charAt(index + 1) == '"') {

                index++;

            } else {
                quoted = !quoted;
            }
        }

        return quoted;
    }
}