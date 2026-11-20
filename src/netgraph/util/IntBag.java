package netgraph.util;

import java.util.Arrays;

/**
 * Growable primitive int collection for hot-path graph operations.
 */
public final class IntBag {
    private int[] values;
    private int size;

    public IntBag() {
        this(16);
    }

    public IntBag(final int initialCapacity) {
        values = new int[Math.max(1, initialCapacity)];
    }

    public void add(final int value) {
        if (size == values.length) {
            values = Arrays.copyOf(
                    values,
                    values.length * 2);
        }

        values[size++] = value;
    }

    public int get(final int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", size: " + size);
        }

        return values[index];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }
}