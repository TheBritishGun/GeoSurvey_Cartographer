package dev.openmap.map;

import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
final class LinkedLongMap<V> {

    private static final int NONE = 0;
    private static final int SPARSE_CLEAR_DENSITY_DIVISOR = 4;
    private static final int SPARSE_CLEAR_WRITES_PER_ENTRY = 2;

    private static final long[] EMPTY = new long[0];

    private final int expected;

    private final Collection<V> valuesView;

    private int[] buckets;
    private int mask;
    private int threshold;

    private long[] keys;
    private Object[] values;
    private int[] chained;
    private int[] older;
    private int[] newer;

    private int size;
    private int used;
    private int free;
    private int modifications;

    private boolean countWork;
    private int lookups;
    private int puts;
    private int growths;
    private int clearWrites;
    private int finds;
    private int mixCalls;
    private int clearCalls;

    private long pendingMix;
    private boolean pendingMixValid;

    private long probeKey;
    private long probeMix;
    private int probeNode;
    private boolean probeValid;

    private final java.util.function.LongConsumer onRemove;

    LinkedLongMap(int expected) {
        this(expected, null);
    }

    LinkedLongMap(int expected, java.util.function.LongConsumer removeHook) {
        this.expected = expected;
        this.onRemove = removeHook;
        this.valuesView = new ValuesView();
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    boolean containsKey(long key) {
        if (countWork) {
            lookups++;
        }
        return find(key) != NONE;
    }

    V get(long key) {
        int node = find(key);
        if (node == NONE) {
            return null;
        }
        moveToYoungest(node);
        return valueAt(node);
    }

    V peekValue(long key) {
        int node = find(key);
        return node == NONE ? null : valueAt(node);
    }

    boolean touch(long key) {
        int node = find(key);
        if (node == NONE) {
            return false;
        }
        moveToYoungest(node);
        return true;
    }

    V put(long key, V value) {
        if (countWork) {
            puts++;
        }
        int node = find(key);
        V previous = null;
        if (node != NONE) {
            previous = valueAt(node);
            values[node] = value;
            moveToYoungest(node);
        } else {
            insert(key, value);
        }
        return previous;
    }

    boolean add(long key) {
        if (find(key) != NONE) {
            return false;
        }
        insert(key, null);
        return true;
    }

    boolean remove(long key) {
        if (size == 0) {
            return false;
        }
        int bucket = probeValid && probeKey == key
                ? (int) probeMix & mask
                : bucketOf(key);
        int previous = NONE;
        int node = buckets[bucket];
        while (node != NONE && keys[node] != key) {
            previous = node;
            node = chained[node];
        }
        boolean removed = false;
        if (node != NONE) {
            if (previous == NONE) {
                buckets[bucket] = chained[node];
            } else {
                chained[previous] = chained[node];
            }
            newer[older[node]] = newer[node];
            older[newer[node]] = older[node];
            values[node] = null;
            chained[node] = free;
            free = node;
            size--;
            modifications++;
            probeValid = false;
            if (onRemove != null) {
                onRemove.accept(key);
            }
            removed = true;
        }
        return removed;
    }

    long eldestKey() {
        if (size == 0) {
            throw new NoSuchElementException();
        }
        return keys[newer[NONE]];
    }

    boolean removeEldest() {
        if (size == 0) {
            return false;
        }
        int node = newer[NONE];
        long key = keys[node];
        int bucket = bucketOf(key);
        int previous = NONE;
        for (int walk = buckets[bucket]; walk != node && walk != NONE; walk = chained[walk]) {
            previous = walk;
        }
        unlink(node, bucket, previous);
        size--;
        modifications++;
        probeValid = false;
        if (onRemove != null) {
            onRemove.accept(key);
        }
        return true;
    }

    long[] keysInOrder() {
        if (size == 0) {
            return EMPTY;
        }
        long[] out = new long[size];
        int i = 0;
        for (int node = newer[NONE]; node != NONE; node = newer[node]) {
            out[i++] = keys[node];
        }
        return out;
    }

    int firstNode() {
        return size == 0 ? NONE : newer[NONE];
    }

    int nextNode(int node) {
        return newer[node];
    }

    void copyAllFrom(LinkedLongMap<V> src) {
        if (src.size == 0) {
            return;
        }
        buckets = src.buckets.clone();
        mask = src.mask;
        threshold = src.threshold;
        keys = src.keys.clone();
        values = src.values.clone();
        chained = src.chained.clone();
        older = src.older.clone();
        newer = src.newer.clone();
        size = src.size;
        used = src.used;
        free = src.free;
        modifications++;
        probeValid = false;
    }

    void clear() {
        if (countWork) {
            clearCalls++;
        }
        if (buckets != null) {
            if (size == 0) {
                if (countWork) {
                    clearWrites = 0;
                }
            } else if ((long) size * SPARSE_CLEAR_DENSITY_DIVISOR < buckets.length) {
                if (countWork) {
                    clearWrites = size * SPARSE_CLEAR_WRITES_PER_ENTRY;
                }
                for (int node = newer[NONE]; node != NONE; node = newer[node]) {
                    buckets[bucketOf(keys[node])] = NONE;
                    values[node] = null;
                }
            } else {
                if (countWork) {
                    clearWrites = buckets.length + (used + 1);
                }
                Arrays.fill(buckets, NONE);
                Arrays.fill(values, 0, used + 1, null);
            }
            older[NONE] = NONE;
            newer[NONE] = NONE;
        }
        size = 0;
        used = 0;
        free = NONE;
        modifications++;
        probeValid = false;
    }

    Collection<V> values() {
        return valuesView;
    }

    private int bucketOf(long key) {
        if (countWork) {
            mixCalls++;
        }
        return (int) LongHash.mix(key) & mask;
    }

    private int find(long key) {
        if (size == 0) {
            pendingMixValid = false;
            return NONE;
        }
        if (probeValid && probeKey == key) {
            pendingMix = probeMix;
            pendingMixValid = true;
            return probeNode;
        }
        if (countWork) {
            finds++;
        }
        long mixed = LongHash.mix(key);
        if (countWork) {
            mixCalls++;
        }
        pendingMix = mixed;
        pendingMixValid = true;
        int node = buckets[(int) mixed & mask];
        while (node != NONE && keys[node] != key) {
            node = chained[node];
        }
        probeKey = key;
        probeMix = mixed;
        probeNode = node;
        probeValid = true;
        return node;
    }

    @SuppressWarnings("unchecked")
    V valueAt(int node) {
        return (V) values[node];
    }

    private void moveToYoungest(int node) {
        int youngest = older[NONE];
        if (youngest == node) {
            return;
        }
        newer[older[node]] = newer[node];
        older[newer[node]] = older[node];
        newer[youngest] = node;
        older[node] = youngest;
        newer[node] = NONE;
        older[NONE] = node;
        modifications++;
    }

    private void unlink(int node, int bucket, int previous) {
        if (previous == NONE) {
            buckets[bucket] = chained[node];
        } else {
            chained[previous] = chained[node];
        }
        newer[older[node]] = newer[node];
        older[newer[node]] = older[node];
        values[node] = null;
        chained[node] = free;
        free = node;
    }

    private void insert(long key, V value) {
        probeValid = false;
        if (buckets == null) {
            allocate(LongHash.tableSizeFor(expected));
        } else {
            if (size >= threshold) {
                grow();
            }
        }
        int node;
        if (free != NONE) {
            node = free;
            free = chained[node];
        } else {
            node = ++used;
        }
        keys[node] = key;
        values[node] = value;
        long mixed = pendingMixValid ? pendingMix : LongHash.mix(key);
        if (!pendingMixValid && countWork) {
            mixCalls++;
        }
        pendingMixValid = false;
        int bucket = (int) mixed & mask;
        chained[node] = buckets[bucket];
        buckets[bucket] = node;
        int youngest = older[NONE];
        newer[youngest] = node;
        older[node] = youngest;
        newer[node] = NONE;
        older[NONE] = node;
        size++;
        modifications++;
    }

    private void allocate(int bucketCount) {
        buckets = new int[bucketCount];
        mask = bucketCount - 1;
        threshold = LongHash.thresholdFor(bucketCount);
        int nodes = threshold + 1;
        keys = new long[nodes];
        values = new Object[nodes];
        chained = new int[nodes];
        older = new int[nodes];
        newer = new int[nodes];
    }

    private void grow() {
        if (countWork) {
            growths++;
        }
        int bucketCount = buckets.length << 1;
        if (bucketCount > LongHash.MAXIMUM_CAPACITY || bucketCount <= 0) {
            throw new IllegalStateException(
                    "MapStore cannot grow past " + LongHash.MAXIMUM_CAPACITY + " slots");
        }
        buckets = new int[bucketCount];
        mask = bucketCount - 1;
        threshold = LongHash.thresholdFor(bucketCount);
        int nodes = threshold + 1;
        keys = Arrays.copyOf(keys, nodes);
        values = Arrays.copyOf(values, nodes);
        older = Arrays.copyOf(older, nodes);
        newer = Arrays.copyOf(newer, nodes);
        if (free == NONE) {
            chained = new int[nodes];
            for (int node = 1; node <= used; node++) {
                int bucket = bucketOf(keys[node]);
                chained[node] = buckets[bucket];
                buckets[bucket] = node;
            }
        } else {
            rebuildWithFreeSlots(nodes);
        }
    }

    private void rebuildWithFreeSlots(int nodes) {
        int pendingFree = 0;
        for (int node = free; node != NONE; node = chained[node]) {
            pendingFree++;
        }
        int[] freed = new int[pendingFree];
        int at = 0;
        for (int node = free; node != NONE; node = chained[node]) {
            freed[at++] = node;
        }
        chained = new int[nodes];
        for (int node = newer[NONE]; node != NONE; node = newer[node]) {
            int bucket = bucketOf(keys[node]);
            chained[node] = buckets[bucket];
            buckets[bucket] = node;
        }
        free = NONE;
        for (int i = pendingFree - 1; i >= 0; i--) {
            chained[freed[i]] = free;
            free = freed[i];
        }
    }

    private final class InOrder implements Iterator<V> {

        private int next = size == 0 ? NONE : newer[NONE];
        private int last = NONE;
        private int expectedModifications = modifications;

        @Override
        public boolean hasNext() {
            return next != NONE;
        }

        @Override
        public V next() {
            if (modifications != expectedModifications) {
                throw new ConcurrentModificationException();
            }
            if (next == NONE) {
                throw new NoSuchElementException();
            }
            last = next;
            next = newer[next];
            return valueAt(last);
        }

        @Override
        public void remove() {
            if (last == NONE) {
                throw new IllegalStateException();
            }
            if (modifications != expectedModifications) {
                throw new ConcurrentModificationException();
            }
            LinkedLongMap.this.remove(keys[last]);
            last = NONE;
            expectedModifications = modifications;
        }
    }

    private final class ValuesView extends AbstractCollection<V> {

        @Override
        public Iterator<V> iterator() {
            return new InOrder();
        }

        @Override
        public int size() {
            return size;
        }
    }
}
