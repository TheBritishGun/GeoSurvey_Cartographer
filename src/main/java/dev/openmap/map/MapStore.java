package dev.openmap.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class MapStore implements ChunkSource {

    public static final int DEFAULT_CAPACITY = 81 * MapRegion.CHUNKS * MapRegion.CHUNKS;

    private static final int MAX_UNSAVED_OVERFLOW = MapRegion.CHUNKS * MapRegion.CHUNKS;

    private static final int MAX_EMPTY_REGIONS = 1024;

    private static final int MAX_LOAD_ATTEMPTS = 3;

    private static final int CHUNK_SHIFT = Integer.numberOfTrailingZeros(MapRegion.CHUNKS);
    private static final int BLOCK_TO_CHUNK_SHIFT = Integer.numberOfTrailingZeros(ChunkSample.SIZE);
    private static final int CHUNK_COORDINATE_BITS = Integer.SIZE;
    private static final long CHUNK_Z_MASK = 0xFFFFFFFFL;
    private static final int INITIAL_REGION_TRACKING_CAPACITY = 16;
    private static final int SECOND_LOAD_ATTEMPT = 2;

    private static final Object FIRST_FAILURE = new Object();
    private static final Object SECOND_FAILURE = new Object();
    private static final Object LAST_FAILURE = new Object();

    private final int capacity;
    private final int unsavedAllowance;
    private final LinkedLongMap<ChunkSample> samples;

    private final LinkedLongMap<RegionMirror> mirrors = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);

    private long evictions;

    private long unsavedDrops;

    private long revisions;

    private final LinkedLongMap<Object> dirtyRegions = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);

    private final LinkedLongMap<Object> fetchedRegions = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);

    private final LinkedLongMap<Object> emptyRegions = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);

    private final LinkedLongMap<Object> failedRegions = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);

    private long inserts;

    private RegionLoader loader;
    private boolean retryRefusedOverflow;

    private RegionWriter writer;

    private SizeListener sizeListener;

    // Null unless set.
    private LostGroundListener lostGroundListener;

    private boolean writing;

    // True after a refused write, until the next drainDirtyRegions().
    private boolean writeRefused;

    private static final int MAX_TRACKED_CHANGES = 4096;

    private final LinkedLongMap<Object> changed = new LinkedLongMap<>(INITIAL_REGION_TRACKING_CAPACITY);
    private boolean changedOverflowed;

    private static final long[] NOTHING_CHANGED = new long[0];

    public MapStore() {
        this(DEFAULT_CAPACITY);
    }

    public MapStore(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity is below 1: " + capacity);
        }
        this.capacity = capacity;
        this.unsavedAllowance = Math.min(capacity, MAX_UNSAVED_OVERFLOW);
        this.samples = new LinkedLongMap<>(Math.min(capacity, DEFAULT_CAPACITY), this::unmirror);
    }

    private void hold(long k, ChunkSample sample, long region) {
        ChunkSample displaced = samples.put(k, sample);
        inserts++;
        mirror(region, sample);
        if (displaced != null) {
            if (displaced != sample) {
                revisions++;
            }
        } else {
            if (sizeListener != null) {
                sizeListener.sizeChanged(1);
            }
            if (!emptyRegions.isEmpty()) {
                if (emptyRegions.remove(region)) {
                    fetchedRegions.add(region);
                }
            }
            if (samples.size() > capacity) {
                dropEldest();
            }
        }
    }

    private void dropEldest() {
        long eldest = samples.eldestKey();
        long region = MapRegion.keyOfChunk(chunkX(eldest), chunkZ(eldest));
        if (!mayDrop(region, samples.size())) {
            return;
        }
        evictions++;
        revisions++;
        fetchedRegions.remove(region);
        samples.remove(eldest);
        if (sizeListener != null) {
            sizeListener.sizeChanged(-1);
        }
    }

    private boolean mayDrop(long region, int size) {
        if (!dirtyRegions.containsKey(region)) {
            return true;
        }
        if (writer == null) {
            return true;
        }
        boolean drop;
        if ((!retryRefusedOverflow || writing || writeRefused)
                && size - capacity > unsavedAllowance) {
            unsavedDrops++;
            if (lostGroundListener != null) {
                lostGroundListener.dropped(MapRegion.x(region), MapRegion.z(region));
            }
            drop = true;
        } else if (writing || writeRefused) {
            drop = false;
        } else {
            writing = true;
            Handoff answer;
            try {
                answer = writer.write(this, MapRegion.x(region), MapRegion.z(region));
            } finally {
                writing = false;
            }
            if (answer == null || answer == Handoff.REFUSED) {
                writeRefused = true;
                drop = false;
            } else {
                dirtyRegions.remove(region);
                drop = switch (answer) {
                    case SAVED, QUEUED -> true;
                    case REFUSED -> false;
                };
            }
        }
        return drop;
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << CHUNK_COORDINATE_BITS) | (chunkZ & CHUNK_Z_MASK);
    }

    public static int chunkX(long key) {
        return (int) (key >> CHUNK_COORDINATE_BITS);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }

    public interface RegionLoader {
        boolean load(MapStore store, int regionX, int regionZ);
    }

    // Report a write only when it happened.
    public interface RegionWriter {
        Handoff write(MapStore store, int regionX, int regionZ);
    }

    public interface SizeListener {
        void sizeChanged(int delta);
    }

    // Told the region of each sample dropped unwritten.
    public interface LostGroundListener {
        void dropped(int regionX, int regionZ);
    }

    public enum Handoff {

        // On disk before return; the sample may go.
        SAVED,

        // A writer finishes it off this thread.
        QUEUED,

        // The store keeps the sample and stops asking until the next drainDirtyRegions().
        REFUSED
    }

    public void setLoader(RegionLoader loader) {
        this.loader = loader;
    }

    public static final int UNLIMITED_LOADS = Integer.MAX_VALUE;

    private int loadsAllowed = UNLIMITED_LOADS;

    // Caps how many more regions get() may read from disk.
    public void allowRegionLoads(int regions) {
        this.loadsAllowed = Math.max(0, regions);
    }

    public void setWriter(RegionWriter writer) {
        this.writer = writer;
    }

    public void setSizeListener(SizeListener listener) {
        this.sizeListener = listener;
    }

    public void setLostGroundListener(LostGroundListener listener) {
        this.lostGroundListener = listener;
    }

    public void put(ChunkSample sample) {
        long k = key(sample.chunkX, sample.chunkZ);
        long region = MapRegion.keyOfChunk(sample.chunkX, sample.chunkZ);
        hold(k, sample, region);
        noteChanged(k);
        dirtyRegions.add(region);
    }

    public void putClean(ChunkSample sample) {
        long region = MapRegion.keyOfChunk(sample.chunkX, sample.chunkZ);
        hold(key(sample.chunkX, sample.chunkZ), sample, region);
    }

    public void markRegionDirty(int regionX, int regionZ) {
        dirtyRegions.add(MapRegion.key(regionX, regionZ));
    }

    public long[] drainDirtyRegions() {
        long[] out = dirtyRegions.keysInOrder();
        dirtyRegions.clear();
        endSaveCycle();
        return out;
    }

    public long[] dirtyRegionsInOrder() {
        long[] out = dirtyRegions.keysInOrder();
        endSaveCycle();
        return out;
    }

    public void endSaveCycle() {
        writeRefused = false;
    }

    void retryRefusedOverflow() {
        retryRefusedOverflow = true;
    }

    public boolean markRegionHandedOff(int regionX, int regionZ) {
        return dirtyRegions.remove(MapRegion.key(regionX, regionZ));
    }

    public boolean hasDirtyRegions() {
        return !dirtyRegions.isEmpty();
    }

    public void forgetFetched() {
        fetchedRegions.clear();
        emptyRegions.clear();
        failedRegions.clear();
    }

    private void noteChanged(long k) {
        if (changedOverflowed) {
            return;
        }
        if (changed.size() >= MAX_TRACKED_CHANGES) {
            changed.clear();
            changedOverflowed = true;
            return;
        }
        changed.add(k);
    }

    public long[] drainChanged() {
        if (changedOverflowed) {
            changedOverflowed = false;
            return null;
        }
        if (changed.isEmpty()) {
            return NOTHING_CHANGED;
        }
        long[] out = changed.keysInOrder();
        changed.clear();
        return out;
    }

    public ChunkSample get(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        ChunkSample found = samples.get(k);
        if (found != null || loader == null) {
            return found;
        }
        long region = MapRegion.keyOfChunk(chunkX, chunkZ);
        if (fetchedRegions.containsKey(region) || emptyRegions.touch(region)) {
            return null;
        }
        if (loadsAllowed <= 0) {
            return null;
        }
        if (loadsAllowed != UNLIMITED_LOADS) {
            loadsAllowed--;
        }
        fetchedRegions.add(region);
        long before = inserts;
        if (!loader.load(this, MapRegion.x(region), MapRegion.z(region))) {
            if (noteFailure(region) < MAX_LOAD_ATTEMPTS) {
                fetchedRegions.remove(region);
            }
        } else {
            clearFailure(region);
            if (inserts == before) {
                rememberEmpty(region);
            }
        }
        return samples.get(k);
    }

    private int noteFailure(long region) {
        Object held = failedRegions.peekValue(region);
        Object next = FIRST_FAILURE;
        int attempts = 1;
        if (held == FIRST_FAILURE) {
            next = SECOND_FAILURE;
            attempts = SECOND_LOAD_ATTEMPT;
        } else if (held != null) {
            next = LAST_FAILURE;
            attempts = MAX_LOAD_ATTEMPTS;
        }
        failedRegions.put(region, next);
        if (failedRegions.size() > MAX_EMPTY_REGIONS) {
            failedRegions.removeEldest();
        }
        return attempts;
    }

    private void clearFailure(long region) {
        if (!failedRegions.isEmpty()) {
            failedRegions.remove(region);
        }
    }

    boolean regionLoadsGivenUp(int regionX, int regionZ) {
        return failedRegions.peekValue(MapRegion.key(regionX, regionZ)) == LAST_FAILURE;
    }

    boolean regionLoadFailedBefore(int regionX, int regionZ) {
        return failedRegions.peekValue(MapRegion.key(regionX, regionZ)) != null;
    }

    void noteRegionLoad(int regionX, int regionZ, boolean failed) {
        long region = MapRegion.key(regionX, regionZ);
        if (failed) {
            noteFailure(region);
        } else {
            clearFailure(region);
        }
    }

    private void rememberEmpty(long region) {
        if (!fetchedRegions.containsKey(region) || holdsGroundIn(region)) {
            return;
        }
        fetchedRegions.remove(region);
        emptyRegions.add(region);
        if (emptyRegions.size() > MAX_EMPTY_REGIONS) {
            emptyRegions.removeEldest();
        }
    }

    private boolean holdsGroundIn(long region) {
        return mirrors.peekValue(region) != null;
    }

    public ChunkSample peek(int chunkX, int chunkZ) {
        return samples.get(key(chunkX, chunkZ));
    }

    public ChunkSample peekValue(int chunkX, int chunkZ) {
        return samples.peekValue(key(chunkX, chunkZ));
    }

    public boolean contains(int chunkX, int chunkZ) {
        return samples.containsKey(key(chunkX, chunkZ));
    }

    public int size() {
        return samples.size();
    }

    public int capacity() {
        return capacity;
    }

    public long evictions() {
        return evictions;
    }

    public long revisions() {
        return revisions;
    }

    public long unsavedDrops() {
        return unsavedDrops;
    }

    public void clear() {
        int was = samples.size();
        samples.clear();
        mirrors.clear();
        forgetFetched();
        dirtyRegions.clear();
        changed.clear();
        changedOverflowed = false;
        memoValid = false;
        revisions++;
        if (was != 0 && sizeListener != null) {
            sizeListener.sizeChanged(-was);
        }
    }

    public List<ChunkSample> inBlockBounds(int minX, int minZ, int maxX, int maxZ) {
        int cx0 = Math.min(minX, maxX) >> BLOCK_TO_CHUNK_SHIFT;
        int cz0 = Math.min(minZ, maxZ) >> BLOCK_TO_CHUNK_SHIFT;
        int cx1 = Math.max(minX, maxX) >> BLOCK_TO_CHUNK_SHIFT;
        int cz1 = Math.max(minZ, maxZ) >> BLOCK_TO_CHUNK_SHIFT;
        List<ChunkSample> out = new ArrayList<>(
                (int) Math.min((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1), samples.size()));
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                ChunkSample s = samples.get(key(cx, cz));
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // A live view; removing through it removes from the store.
    public Collection<ChunkSample> all() {
        return samples.values();
    }

    int firstIndex() {
        return samples.firstNode();
    }

    int nextIndex(int index) {
        return samples.nextNode(index);
    }

    ChunkSample sampleAt(int index) {
        return samples.valueAt(index);
    }

    public static int regionSlot(int chunkX, int chunkZ) {
        return (chunkX & (MapRegion.CHUNKS - 1)) * MapRegion.CHUNKS
                + (chunkZ & (MapRegion.CHUNKS - 1));
    }

    public ChunkSample[] regionSnapshotForWriting(int regionX, int regionZ) {
        RegionMirror held = mirrors.peekValue(MapRegion.key(regionX, regionZ));
        if (held == null) {
            return new ChunkSample[MapRegion.CHUNKS * MapRegion.CHUNKS];
        }
        ChunkSample[] out = held.toDenseArray();
        int firstX = MapRegion.firstChunk(regionX);
        int firstZ = MapRegion.firstChunk(regionZ);
        for (int at = 0; at < out.length; at++) {
            if (out[at] != null) {
                samples.touch(key(firstX + (at >> CHUNK_SHIFT),
                        firstZ + (at & (MapRegion.CHUNKS - 1))));
            }
        }
        return out;
    }

    private long memoRegion;
    private RegionMirror memoMirror;
    private boolean memoValid;

    private void mirror(long region, ChunkSample sample) {
        RegionMirror held;
        if (memoValid && memoRegion == region) {
            held = memoMirror;
        } else {
            held = mirrors.peekValue(region);
            if (held == null) {
                held = new RegionMirror();
                mirrors.put(region, held);
            }
            memoRegion = region;
            memoMirror = held;
            memoValid = true;
        }
        held.put(regionSlot(sample.chunkX, sample.chunkZ), sample);
    }

    private void unmirror(long k) {
        int cx = chunkX(k);
        int cz = chunkZ(k);
        long region = MapRegion.keyOfChunk(cx, cz);
        RegionMirror held = mirrors.peekValue(region);
        if (held == null) {
            return;
        }
        if (!held.clear(regionSlot(cx, cz))) {
            return;
        }
        if (held.count == 0) {
            mirrors.remove(region);
            if (memoValid && memoRegion == region) {
                memoValid = false;
            }
        }
    }

    private static final class RegionMirror {

        private static final int DENSE_SIZE = MapRegion.CHUNKS * MapRegion.CHUNKS;
        private static final int SPARSE_LIMIT = 8;

        private short[] sparseSlots;
        private ChunkSample[] sparseSamples;
        private ChunkSample[] dense;
        private int count;

        private int indexOfSparse(int slot) {
            int index = -1;
            for (int i = 0; i < count && index == -1; i++) {
                if (sparseSlots[i] == slot) {
                    index = i;
                }
            }
            return index;
        }

        void put(int slot, ChunkSample sample) {
            if (dense != null) {
                if (dense[slot] == null) {
                    count++;
                }
                dense[slot] = sample;
                return;
            }
            int at = indexOfSparse(slot);
            if (at >= 0) {
                sparseSamples[at] = sample;
                return;
            }
            if (count == SPARSE_LIMIT) {
                promote();
                dense[slot] = sample;
                count++;
                return;
            }
            if (sparseSlots == null) {
                sparseSlots = new short[SPARSE_LIMIT];
                sparseSamples = new ChunkSample[SPARSE_LIMIT];
            }
            sparseSlots[count] = (short) slot;
            sparseSamples[count] = sample;
            count++;
        }

        boolean clear(int slot) {
            if (dense != null) {
                if (dense[slot] == null) {
                    return false;
                }
                dense[slot] = null;
                count--;
                return true;
            }
            int at = indexOfSparse(slot);
            if (at < 0) {
                return false;
            }
            int last = count - 1;
            sparseSlots[at] = sparseSlots[last];
            sparseSamples[at] = sparseSamples[last];
            sparseSamples[last] = null;
            count--;
            return true;
        }

        private void promote() {
            ChunkSample[] full = new ChunkSample[DENSE_SIZE];
            for (int i = 0; i < count; i++) {
                full[sparseSlots[i]] = sparseSamples[i];
            }
            dense = full;
            sparseSlots = null;
            sparseSamples = null;
        }

        ChunkSample[] toDenseArray() {
            if (dense != null) {
                return dense.clone();
            }
            ChunkSample[] full = new ChunkSample[DENSE_SIZE];
            for (int i = 0; i < count; i++) {
                full[sparseSlots[i]] = sparseSamples[i];
            }
            return full;
        }

        RegionMirror copy() {
            RegionMirror out = new RegionMirror();
            if (dense != null) {
                out.dense = dense.clone();
            } else {
                if (sparseSlots != null) {
                    out.sparseSlots = sparseSlots.clone();
                    out.sparseSamples = sparseSamples.clone();
                }
            }
            out.count = count;
            return out;
        }

        int backingLength() {
            if (dense != null) {
                return dense.length;
            }
            return sparseSlots == null ? 0 : sparseSlots.length;
        }
    }

    public MapStore snapshotForWriting() {
        MapStore copy = new MapStore(Math.max(1, samples.size()));
        copy.samples.copyAllFrom(samples);
        for (long region : mirrors.keysInOrder()) {
            RegionMirror held = mirrors.peekValue(region);
            copy.mirrors.put(region, held.copy());
        }
        return copy;
    }

    public long approximateBytes() {
        return (long) samples.size() * ChunkSample.approximateBytes();
    }

}
