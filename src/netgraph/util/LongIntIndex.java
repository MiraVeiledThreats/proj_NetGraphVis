package netgraph.util;

/**
 * Open-addressed primitive long-to-int index.
 *
 * Values of -1 indicate that a key is not present.
 */
public final class LongIntIndex {
    private long[] keys = new long[16];
    private int[] values = new int[16];
    private byte[] used = new byte[16];

    private int mask = 15;
    private int size;
    private int resizeThreshold = 10;

    public int get(final long key) {
        int slot = mix(key) & mask;

        while (used[slot] != 0) {
            if (keys[slot] == key) {
                return values[slot];
            }

            slot = (slot + 1) & mask;
        }

        return -1;
    }

    public void put(
            final long key,
            final int value) {

        if (size >= resizeThreshold) {
            resize();
        }

        int slot = mix(key) & mask;

        while (used[slot] != 0) {
            if (keys[slot] == key) {
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
        final long[] oldKeys = keys;
        final int[] oldValues = values;
        final byte[] oldUsed = used;

        keys = new long[oldKeys.length * 2];
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

    private static int mix(final long value) {
        long mixed = value;

        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;

        return (int) mixed;
    }
}