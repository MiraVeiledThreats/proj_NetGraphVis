package netgraph.csv;

/**
 * Receives one complete logical CSV record.
 *
 * A logical record can span more than one physical text line when quoted CSV
 * fields contain line breaks.
 */
public interface CsvRowConsumer {
    void onRow(
            int lineNumber,
            String rawRecord,
            String[] values);
}