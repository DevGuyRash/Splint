package splint.core.util;

import java.lang.ref.WeakReference;
import java.util.Arrays;

/**
 * {@code int} key → weakly held value. Open addressing with linear probing and backward-shift
 * deletion: no boxing, no allocation per lookup, no tombstones. Values the garbage collector
 * clears drop out on lookup and on resize. Not thread-safe.
 */
public final class IntWeakMap<T> {
    private static final int EMPTY = Integer.MIN_VALUE;
    private int[] keys;
    private WeakReference<T>[] values;
    private int size;

    public IntWeakMap(int initialCapacity) {
        int cap = Integer.highestOneBit(Math.max(16, initialCapacity) - 1) << 1;
        keys = filled(cap);
        values = array(cap);
    }

    /** The value for {@code key}, or null when absent or collected. {@code key} must not be Integer.MIN_VALUE. */
    public T get(int key) {
        int slot = find(key);
        if (keys[slot] != key) return null;
        T v = values[slot].get();
        if (v == null) removeAt(slot);
        return v;
    }

    public void put(int key, T value) {
        int slot = find(key);
        if (keys[slot] == key) {
            if (values[slot].get() != value) values[slot] = new WeakReference<>(value);
            return;
        }
        keys[slot] = key;
        values[slot] = new WeakReference<>(value);
        if (++size * 2 > keys.length) grow();
    }

    /** Removes {@code key} only while it maps to {@code value} (or to a collected value). */
    public void remove(int key, T value) {
        int slot = find(key);
        if (keys[slot] != key) return;
        T current = values[slot].get();
        if (current == null || current == value) removeAt(slot);
    }

    /** Entries, including ones whose value was collected but not yet dropped. */
    public int size() { return size; }

    public int capacity() { return keys.length; }

    private int find(int key) {
        int mask = keys.length - 1;
        int slot = mix(key) & mask;
        while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) & mask;
        return slot;
    }

    private void removeAt(int slot) {
        int mask = keys.length - 1;
        keys[slot] = EMPTY;
        values[slot] = null;
        size--;
        for (int next = (slot + 1) & mask; keys[next] != EMPTY; next = (next + 1) & mask) {
            int k = keys[next];
            WeakReference<T> v = values[next];
            keys[next] = EMPTY;
            values[next] = null;
            int s = find(k);
            keys[s] = k;
            values[s] = v;
        }
    }

    /**
     * Rebuilds from the entries whose values are still alive; the table only doubles when the
     * survivors alone would fill it past half. Churn (animals created and dropped) therefore
     * cannot grow it without bound.
     */
    private void grow() {
        int alive = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY && values[i].get() != null) alive++;
        rehash(alive * 4 > keys.length ? keys.length * 2 : keys.length);
    }

    private void rehash(int capacity) {
        int[] oldKeys = keys;
        WeakReference<T>[] oldValues = values;
        keys = filled(capacity);
        values = array(capacity);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY && oldValues[i].get() != null) {
                int s = find(oldKeys[i]);
                keys[s] = oldKeys[i];
                values[s] = oldValues[i];
                size++;
            }
        }
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, EMPTY);
        return a;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> WeakReference<T>[] array(int n) {
        return new WeakReference[n];
    }

    /** Fibonacci mixing: sequential keys (the ID counter) would otherwise cluster. */
    private static int mix(int x) {
        x *= 0x9E3779B9;
        return x ^ (x >>> 16);
    }
}
