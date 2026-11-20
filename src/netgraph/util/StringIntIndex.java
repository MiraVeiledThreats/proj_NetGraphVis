package netgraph.util;

/**
 * Open-addressed String-to-int index used while constructing packed graphs.
 *
 * Values of -1 indicate that a key is not present.
 */
public final class StringIntIndex {
    private String[] keys = new String[16];
    private int[] values = new int[16];
    private byte[] used = new byte[16];

    private int mask = 15;
    private int size;
    private int resizeThreshold = 10;

    public int get(final String key) {
        if (key == null) {
            return -1;
        }

        int slot = key.hashCode() & mask;

        while (used[slot] != 0) {
            if (keys[slot].equals(key)) {
                return values[slot];
            }

            slot = (slot + 1) & mask;
        }

        return -1;
    }

    public void put(
            final String key,
            final int value) {

        if (key == null) {
            throw new IllegalArgumentException(
                    "StringIntIndex does not permit null keys.");
        }

        if (size >= resizeThreshold) {
            resize();
        }

        int slot = key.hashCode() & mask;

        while (used[slot] != 0) {
            if (keys[slot].equals(key)) {
                values[slot] = value;
                return;
            }

            slot = (slot + 1) & mask;
        }

        used[slot] = 1;
        keys[slot] = key;
        values[slot] = value;
        size++;
    }

    private void resize() {
        final String[] oldKeys = keys;
        final int[] oldValues = values;
        final byte[] oldUsed = used;

        keys = new String[oldKeys.length * 2];
        values = new int[keys.length];
        used = new byte[keys.length];

        mask = keys.length - 1;
        size = 0;
        resizeThreshold = (int) (keys.length * 0.65f);

        for (int index = 0;
                index < oldKeys.length;
                index++) {

            if (oldUsed[index] != 0) {
                put(oldKeys[index], oldValues[index]);
            }
        }
    }
}