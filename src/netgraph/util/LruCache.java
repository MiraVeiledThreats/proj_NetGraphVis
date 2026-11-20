package netgraph.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small synchronized LRU cache for immutable values.
 *
 * Values stored in this cache must not be mutated after insertion.
 */
public final class LruCache<K, V> {
    private final int maximumEntries;

    private final LinkedHashMap<K, V> values =
            new LinkedHashMap<K, V>(
                    16,
                    0.75f,
                    true) {

                private static final long serialVersionUID =
                        1L;

                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<K, V> eldest) {

                    return size() > maximumEntries;
                }
            };

    public LruCache(final int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException(
                    "maximumEntries must be at least one.");
        }

        this.maximumEntries = maximumEntries;
    }

    public synchronized V get(final K key) {
        return values.get(key);
    }

    public synchronized void put(
            final K key,
            final V value) {

        if (key == null) {
            throw new IllegalArgumentException(
                    "Cache key is required.");
        }

        if (value == null) {
            throw new IllegalArgumentException(
                    "Cache value is required.");
        }

        values.put(key, value);
    }

    public synchronized void clear() {
        values.clear();
    }

    public synchronized int size() {
        return values.size();
    }
}