package dev.openmap.map;

import dev.openmap.api.GroundChunk;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.share.UtcClock;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MapStorage {

    public static final String EXTENSION = ".landnav";

    private static final int STORE_MAP_CAPACITY = 16;

    private static final float STORE_MAP_LOAD_FACTOR = 0.75f;

    private final Map<String, MapStore> stores = new LinkedHashMap<>(STORE_MAP_CAPACITY, STORE_MAP_LOAD_FACTOR, true);
    private final Map<String, LandmarkStore> landmarks = new LinkedHashMap<>();
    private final Map<String, dev.openmap.draw.AnnotationStore> overlays =
            new LinkedHashMap<>();
    private final Map<String, Boolean> dirty = new LinkedHashMap<>();
    private final Map<String, Folder> folders =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> claimedFolders =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, String> folderOwners =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Object claiming = new Object();
    private final Map<String, java.util.concurrent.CountDownLatch> claimsInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final int capacity;

    private record Folder(Path root, String name, Path dir) {
    }

    private record FolderName(String name, boolean markerWasNull) {
    }

    private final class RegionLoad implements MapCodec.ColumnGate {

        private final WorkPool.Receipt receipt = new WorkPool.Receipt();

        private final java.util.concurrent.atomic.AtomicBoolean abandoned =
                new java.util.concurrent.atomic.AtomicBoolean();

        private final List<ChunkSample> samples = new ArrayList<>(MAX_REGION_CHUNKS);

        private final java.util.function.Supplier<RegionLoad> compute = this::read;

        private final java.util.function.Consumer<RegionLoad> apply =
                MapStorage.this::regionLoadLanded;

        private final Runnable drop = this::dropped;

        private final java.util.function.Consumer<RegionLoad> discard =
                MapStorage.this::regionLoadDiscarded;

        private final java.util.function.Consumer<ChunkSample> sink = this::take;

        private boolean taken;

        private MapStore store;

        private String dimensionId;

        private int regionX;

        private int regionZ;

        private Path dir;

        private volatile Path file;

        private volatile java.util.function.IntFunction<LandCover> legacy;

        private volatile boolean sayFailure;

        private ChunkSample[] slots;

        private volatile ChunkSample[] resident;

        private volatile boolean failed;

        private ChunkSample fresh;

        private long diskCapturedAt;

        private int diskCaptureVersion;

        private MapStore store() {
            return store;
        }

        private int regionX() {
            return regionX;
        }

        private int regionZ() {
            return regionZ;
        }

        private WorkPool.Receipt receipt() {
            return receipt;
        }

        private java.util.concurrent.atomic.AtomicBoolean abandoned() {
            return abandoned;
        }

        private ChunkSample[] resident() {
            return resident;
        }

        private boolean free() {
            return !taken && receipt.isEnded();
        }

        private boolean holdsFile(Path askedDir, int askedX, int askedZ) {
            return file != null && dir == askedDir && regionX == askedX && regionZ == askedZ;
        }

        private void aim(String askedDimension, MapStore askedStore, Path askedDir, int askedX,
                         int askedZ) {
            if (!holdsFile(askedDir, askedX, askedZ)) {
                file = askedDir == null ? null
                        : askedDir.resolve(MapRegion.fileName(askedX, askedZ));
                dir = askedDir;
            }
            dimensionId = askedDimension;
            store = askedStore;
            regionX = askedX;
            regionZ = askedZ;
            abandoned.set(false);
            failed = false;
        }

        private RegionLoad read() {
            try {
                if (!abandoned.get() && file != null) {
                    stream();
                }
            } catch (RuntimeException unexpected) {
                samples.clear();
                failed = true;
                if (sayFailure) {
                    sayUnreadable(file, unexpected);
                }
            }
            return this;
        }

        private void stream() {
            try {
                MapCodec.stream(file, legacy, this, sink, MAX_REGION_CHUNKS, damage);
            } catch (IOException unreadable) {
                failed = true;
                if (sayFailure) {
                    sayUnreadable(file);
                }
            } catch (RuntimeException unexpected) {
                failed = true;
                if (sayFailure) {
                    sayUnreadable(file, unexpected);
                }
            }
        }

        @Override
        public boolean keeps(int chunkX, int chunkZ, long captured, int captureVersion) {
            fresh = residentSlot(chunkX, chunkZ);
            diskCapturedAt = captured;
            diskCaptureVersion = captureVersion;
            return fresh == null || mayOutrank(captured, captureVersion, fresh);
        }

        @Override
        public boolean keepsColumns(byte[] columns, int version,
                                    java.util.function.IntFunction<LandCover> legacy) {
            return fresh == null || mayOutrank(diskCapturedAt, diskCaptureVersion, fresh, columns,
                    version, legacy);
        }

        private ChunkSample residentSlot(int chunkX, int chunkZ) {
            if (resident == null) {
                return null;
            }
            return resident[MapStore.regionSlot(chunkX, chunkZ)];
        }

        private void take(ChunkSample sample) {
            if (wantsResidentOrNewer(resident, sample)) {
                samples.add(sample);
            }
        }

        @Override
        public boolean stops() {
            return abandoned.get();
        }

        private void dropped() {
            regionLoadDropped(this);
        }

        private void letGo() {
            taken = false;
            store = null;
            dimensionId = null;
        }

        private void settle() {
            samples.clear();
            resident = null;
            if (slots != null) {
                java.util.Arrays.fill(slots, null);
            }
        }
    }

    private static final int REGION_LOAD_POOL_CAP = 16;

    private final List<RegionLoad> regionLoadPool = new ArrayList<>(REGION_LOAD_POOL_CAP);

    private final List<RegionLoad> regionLoads = new ArrayList<>();

    private static long regionLoadReceiptProbes;

    static long regionLoadReceiptProbes() {
        return regionLoadReceiptProbes;
    }

    private final java.util.IdentityHashMap<MapStore, List<RegionLoad>> regionLoadsByStore =
            new java.util.IdentityHashMap<>();

    private List<RegionLoad> spareRegionLoadList;

    private static final int REGION_LOAD_PRUNE_PERIOD = 8;

    private static final int FIRST_LOADS_PER_STORE = 2;

    private int regionLoadsSincePrune;

    private boolean forgetRegionLoad(MapStore store, RegionLoad load) {
        if (!regionLoads.remove(load)) {
            return false;
        }
        unindexRegionLoad(store, load);
        return true;
    }

    private void regionLoadEnded(RegionLoad load, boolean failed) {
        if (forgetRegionLoad(load.store(), load)) {
            load.store().noteRegionLoad(load.regionX(), load.regionZ(), failed);
        }
    }

    private void regionLoadDropped(RegionLoad load) {
        if (load.receipt().isCrashed()) {
            regionLoadEnded(load, true);
        } else {
            forgetRegionLoad(load.store(), load);
        }
        releaseRegionLoad(load);
    }

    private void regionLoadLanded(RegionLoad load) {
        regionLoadEnded(load, load.failed);
        countAsUse(load.dimensionId, load.store);
        if (holds(load.dimensionId, load.store)) {
            PendingFold fold = pendingFold(load.dimensionId, load.store, load.samples, load);
            drainFoldSlice(fold);
            if (fold.drained()) {
                retireFold(fold);
            } else {
                pendingFolds.add(fold);
            }
        } else {
            releaseRegionLoad(load);
        }
    }

    private void regionLoadDiscarded(RegionLoad load) {
        regionLoadEnded(load, load.failed);
        releaseRegionLoad(load);
    }

    private RegionLoad takeRegionLoad(Path dir, int regionX, int regionZ) {
        RegionLoad found = null;
        RegionLoad spare = null;
        for (int at = 0; at < regionLoadPool.size() && found == null; at++) {
            RegionLoad held = regionLoadPool.get(at);
            boolean free = held.free();
            if (free && held.holdsFile(dir, regionX, regionZ)) {
                found = held;
            }
            if (free && spare == null) {
                spare = held;
            }
        }
        if (found == null) {
            found = spare;
        }
        if (found == null) {
            found = new RegionLoad();
            if (regionLoadPool.size() < REGION_LOAD_POOL_CAP) {
                regionLoadPool.add(found);
            }
        }
        found.receipt.rearm();
        found.taken = true;
        return found;
    }

    private void releaseRegionLoad(RegionLoad load) {
        load.letGo();
        load.settle();
    }

    private static RegionLoad regionLoadOpen(List<RegionLoad> mine, long region) {
        RegionLoad found = null;
        for (int at = mine.size() - 1; at >= 0 && found == null; at--) {
            RegionLoad held = mine.get(at);
            if (MapRegion.key(held.regionX(), held.regionZ()) == region) {
                found = held;
            }
        }
        return found;
    }

    private void unindexRegionLoad(MapStore store, RegionLoad load) {
        List<RegionLoad> mine = regionLoadsByStore.get(store);
        if (mine == null) {
            return;
        }
        if (mine.remove(load) && mine.isEmpty()) {
            regionLoadsByStore.remove(store);
            spareRegionLoadList = mine;
        }
    }

    private void trackRegionLoad(MapStore store, RegionLoad load) {
        List<RegionLoad> mine = regionLoadsByStore.get(store);
        if (mine == null) {
            mine = spareRegionLoadList;
            if (mine == null) {
                mine = new ArrayList<>(FIRST_LOADS_PER_STORE);
            } else {
                spareRegionLoadList = null;
            }
            regionLoadsByStore.put(store, mine);
        }
        mine.add(load);
    }

    private void forgetDroppedRegionLoads(MapStore store, List<RegionLoad> mine) {
        for (int at = mine.size() - 1; at >= 0; at--) {
            RegionLoad held = mine.get(at);
            if (droppedRegionLoad(held)) {
                regionLoadDropped(held);
            }
        }
    }

    private void sweepDroppedRegionLoads() {
        regionLoadsSincePrune++;
        if (regionLoadsSincePrune >= REGION_LOAD_PRUNE_PERIOD) {
            regionLoadsSincePrune = 0;
            for (int at = regionLoads.size() - 1; at >= 0; at--) {
                RegionLoad load = regionLoads.get(at);
                if (droppedRegionLoad(load)) {
                    regionLoadDropped(load);
                }
            }
        }
    }

    private static boolean droppedRegionLoad(RegionLoad load) {
        regionLoadReceiptProbes++;
        boolean dropped = load.receipt().isDroppedUnrun();
        if (!dropped) {
            regionLoadReceiptProbes++;
            dropped = load.receipt().isCrashed();
        }
        return dropped;
    }

    // Samples folded per fold in one drainPendingFolds pass.
    private static final int FOLD_SLICE = 128;

    private static final class PendingFold {

        private String dimensionId;
        private MapStore store;
        private List<ChunkSample> remaining;
        private int cursor;
        private RegionLoad load;

        private PendingFold(String dimensionId, MapStore store, List<ChunkSample> loaded,
                            RegionLoad load) {
            reset(dimensionId, store, loaded, load);
        }

        private void reset(String dimensionId, MapStore store, List<ChunkSample> loaded,
                           RegionLoad load) {
            this.dimensionId = dimensionId;
            this.store = store;
            this.remaining = loaded;
            this.cursor = 0;
            this.load = load;
        }

        private boolean drained() {
            return cursor >= remaining.size();
        }
    }

    private final List<PendingFold> pendingFolds = new ArrayList<>();

    private final java.util.ArrayDeque<PendingFold> pendingFoldPool =
            new java.util.ArrayDeque<>();

    private PendingFold pendingFold(String dimensionId, MapStore store,
                                    List<ChunkSample> loaded, RegionLoad load) {
        PendingFold fold = pendingFoldPool.poll();
        if (fold == null) {
            return new PendingFold(dimensionId, store, loaded, load);
        }
        fold.reset(dimensionId, store, loaded, load);
        return fold;
    }

    private void retireFold(PendingFold fold) {
        releaseRegionLoad(fold.load);
        fold.reset(null, null, null, null);
        pendingFoldPool.push(fold);
    }

    private void drainFoldSlice(PendingFold fold) {
        int at = fold.cursor;
        int end = Math.min(fold.remaining.size(), at + FOLD_SLICE);
        while (at < end) {
            ChunkSample sample = fold.remaining.get(at);
            ChunkSample held = fold.store.peek(sample.chunkX, sample.chunkZ);
            if (held == null || outranks(sample, held)) {
                fold.store.putClean(sample);
            }
            at++;
        }
        fold.cursor = at;
    }

    private void drainPendingFolds() {
        if (pendingFolds.isEmpty()) {
            return;
        }
        for (int at = pendingFolds.size() - 1; at >= 0; at--) {
            PendingFold fold = pendingFolds.get(at);
            countAsUse(fold.dimensionId, fold.store);
            if (!holds(fold.dimensionId, fold.store)) {
                pendingFolds.remove(at);
                retireFold(fold);
            } else {
                drainFoldSlice(fold);
                if (fold.drained()) {
                    pendingFolds.remove(at);
                    retireFold(fold);
                }
            }
        }
    }

    private final class LandmarkLoad {

        private final WorkPool.Receipt receipt = new WorkPool.Receipt();

        private final java.util.function.Supplier<LandmarkStore> compute = this::read;

        private final java.util.function.Consumer<LandmarkStore> apply = this::landed;

        private final Runnable drop = this::dropped;

        private final java.util.function.Consumer<LandmarkStore> discard = this::discarded;

        private boolean taken;

        private volatile String dimensionId;

        private volatile long generation;

        private volatile Path file;

        private volatile boolean sayFailure;

        private String dimensionId() {
            return dimensionId;
        }

        private long rootGeneration() {
            return generation;
        }

        private WorkPool.Receipt receipt() {
            return receipt;
        }

        private boolean free() {
            return !taken && receipt.isEnded();
        }

        private void aim(String askedDimension, long askedGeneration, Path askedFile,
                         boolean firstAttempt) {
            dimensionId = askedDimension;
            generation = askedGeneration;
            file = askedFile;
            sayFailure = firstAttempt;
        }

        private LandmarkStore read() {
            LandmarkStore loaded;
            try {
                if (file == null || generation != MapStorage.this.rootGeneration) {
                    loaded = new LandmarkStore();
                } else {
                    loaded = LandmarkStore.load(file);
                }
            } catch (RuntimeException unreadable) {
                if (sayFailure) {
                    sayMarkersUnreadable(dimensionId, unreadable);
                }
                loaded = null;
            }
            return loaded;
        }

        private void landed(LandmarkStore loaded) {
            landmarkLoadEnded(dimensionId, this, loaded == null);
            if (loaded != null && generation == MapStorage.this.rootGeneration
                    && !landmarks.containsKey(dimensionId)) {
                landmarkLoadFailures.remove(dimensionId);
                landmarks.put(dimensionId, loaded);
            }
        }

        private void dropped() {
            landmarkLoadEnded(dimensionId, this, receipt.isCrashed());
        }

        private void discarded(LandmarkStore loaded) {
            landmarkLoadEnded(dimensionId, this, loaded == null);
        }

        private void letGo() {
            taken = false;
        }
    }

    private static final int LANDMARK_LOAD_POOL_CAP = 4;

    private final List<LandmarkLoad> landmarkLoadPool = new ArrayList<>(LANDMARK_LOAD_POOL_CAP);

    private final List<LandmarkLoad> landmarkLoads = new ArrayList<>();

    private static long landmarkLoadReceiptProbes;

    static long landmarkLoadReceiptProbes() {
        return landmarkLoadReceiptProbes;
    }

    private final java.util.Map<String, LandmarkLoad> landmarkLoadsByDimension =
            new java.util.LinkedHashMap<>();

    private static final int LANDMARK_LOAD_PRUNE_PERIOD = 8;

    private int landmarkLoadsSincePrune;

    private record LandmarkLoadFailure(int attempts, long retryAt) {
    }

    private static final int MAX_LANDMARK_LOAD_ATTEMPTS = 3;

    private static final long LANDMARK_LOAD_RETRY_STEP_SECONDS = 30L;

    private final Map<String, LandmarkLoadFailure> landmarkLoadFailures = new LinkedHashMap<>();

    long landmarkLoadRetryStepNanos =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(LANDMARK_LOAD_RETRY_STEP_SECONDS);

    private boolean forgetLandmarkLoad(String dimensionId, LandmarkLoad load) {
        boolean tracked = landmarkLoads.remove(load);
        landmarkLoadsByDimension.remove(dimensionId, load);
        if (tracked) {
            load.letGo();
        }
        return tracked;
    }

    private LandmarkLoad takeLandmarkLoad() {
        LandmarkLoad found = null;
        for (int at = 0; at < landmarkLoadPool.size() && found == null; at++) {
            LandmarkLoad held = landmarkLoadPool.get(at);
            if (held.free()) {
                found = held;
            }
        }
        if (found == null) {
            found = new LandmarkLoad();
            if (landmarkLoadPool.size() < LANDMARK_LOAD_POOL_CAP) {
                landmarkLoadPool.add(found);
            }
        }
        found.receipt.rearm();
        found.taken = true;
        return found;
    }

    private void landmarkLoadEnded(String dimensionId, LandmarkLoad load, boolean failed) {
        boolean tracked = forgetLandmarkLoad(dimensionId, load);
        if (!tracked || !failed || load.rootGeneration() != rootGeneration) {
            return;
        }
        LandmarkLoadFailure was = landmarkLoadFailures.get(dimensionId);
        int attempts = was == null ? 1 : was.attempts() + 1;
        landmarkLoadFailures.put(dimensionId, new LandmarkLoadFailure(attempts,
                System.nanoTime() + attempts * landmarkLoadRetryStepNanos));
    }

    private void sweepDroppedLandmarkLoads() {
        landmarkLoadsSincePrune++;
        if (landmarkLoadsSincePrune >= LANDMARK_LOAD_PRUNE_PERIOD) {
            landmarkLoadsSincePrune = 0;
            for (int at = landmarkLoads.size() - 1; at >= 0; at--) {
                LandmarkLoad load = landmarkLoads.get(at);
                if (droppedLandmarkLoad(load)) {
                    landmarkLoadEnded(load.dimensionId(), load, load.receipt().isCrashed());
                }
            }
        }
    }

    private static boolean droppedLandmarkLoad(LandmarkLoad load) {
        landmarkLoadReceiptProbes++;
        boolean dropped = load.receipt().isDroppedUnrun();
        if (!dropped) {
            landmarkLoadReceiptProbes++;
            dropped = load.receipt().isCrashed();
        }
        return dropped;
    }

    private record LandmarkWrite(String dimensionId, LandmarkStore store, Path file,
                                 long sequence,
                                 java.util.function.BooleanSupplier droppedUnrun,
                                 java.util.concurrent.atomic.AtomicBoolean landed,
                                 java.util.concurrent.atomic.AtomicBoolean settled) {
    }

    private final List<LandmarkWrite> landmarkWrites = new ArrayList<>();

    private final java.util.Map<Path, LandmarkWrite> refusedLandmarkWrites =
            new java.util.LinkedHashMap<>();

    private final java.util.Map<Path, LandmarkWrite> leftBehindLandmarkWrites =
            new java.util.LinkedHashMap<>();

    private record OverlaySave(String dimensionId, dev.openmap.draw.AnnotationStore store) {
    }

    private final java.util.Map<Path, OverlaySave> refusedOverlaySaves =
            new java.util.LinkedHashMap<>();

    private final java.util.Map<Path, Long> landmarkWriteSequences =
            new java.util.concurrent.ConcurrentHashMap<>();

    private long stampLandmarkWrite(Path file) {
        return landmarkWriteSequences.merge(file, 1L, Long::sum);
    }

    private final java.util.Map<Path, Long> landmarkLastWritten =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Coalesced-away saves: file to the dimension that needs a follow-up snapshot.
    private final java.util.Map<Path, String> landmarksWantedAgain =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Told after each landmark save, on the client thread; null: nobody.
    private volatile Markers.Saved landmarksSavedListener;

    // Chunks all dimensions may hold together before the coldest flushes.
    private final long residentBudget;

    private long residentChunks;

    private static final long STORES_KEPT_RESIDENT = 2L;

    private volatile Path root;
    private volatile long rootGeneration;
    private java.util.function.IntFunction<LandCover> legacyColourToCover;

    public MapStorage() {
        this(MapStore.DEFAULT_CAPACITY);
    }

    public MapStorage(int capacity) {
        this.capacity = capacity;
        this.residentBudget = STORES_KEPT_RESIDENT * capacity;
    }

    // The file naming the raw key that claimed a directory.
    public static final String MARKER = "world.id";

    public static final String DIMENSION_MARKER = "dimension.id";

    // The key GeoSurvey knows a folder by now; this class reads it and never writes it.
    private static final String CURRENT_MARKER = "world.current";

    private static final String SAVE_KEY_PREFIX = "singleplayer-";

    private static final String[] NO_PRIOR_KEYS = new String[0];

    // The start of the key of a single-player save.
    public static String saveKeyPrefix() {
        return SAVE_KEY_PREFIX;
    }


    // The directory holding one world's map only.
    public static Path worldDir(Path dataDir, String rawKey) {
        return worldDir(dataDir, rawKey, NO_PRIOR_KEYS);
    }

    // priorKeys: this world's older spellings, newest first.
    public static Path worldDir(Path dataDir, String rawKey, String... priorKeys) {
        String stripped = stripUnsafe(rawKey);
        String name = guardDevice(stripped);
        Path first = dataDir.resolve(name);
        String held = markerOf(first);
        String key = rawKey == null ? "" : rawKey.strip();
        if (held != null && (held.equals(key) || spellsSameServer(held, key))) {
            return first;
        }
        Path current = key.isEmpty() ? null : currentKeyDir(dataDir, key);
        if (current != null) {
            return current;
        }

        boolean heldBefore = held != null && heldUnderAPrior(held, priorKeys);

        Path found = heldBefore ? first : null;
        for (int at = 0; at < priorKeys.length && found == null; at++) {
            String prior = priorKeys[at];
            String priorKey = prior == null ? "" : prior.strip();
            found = priorFolder(new WorldSearch(dataDir, first, held, priorKey, NO_PRIOR_KEYS), sanitise(prior),
                    prior);
        }
        if (found == null) {
            found = ownFolder(new WorldSearch(dataDir, first, held, key, priorKeys), stripped, name, rawKey);
        }
        return found;
    }

    // A server key written in another letter case is the same server; a save key is exact.
    private static boolean spellsSameServer(String marker, String key) {
        return !key.startsWith(SAVE_KEY_PREFIX) && marker.equalsIgnoreCase(key);
    }

    // This world's marker: its key, the same server in another letter case, or an older spelling.
    private static boolean markerIsThisWorld(String marker, String key, String[] priorKeys) {
        return marker.equals(key) || spellsSameServer(marker, key) || heldUnderAPrior(marker, priorKeys);
    }

    // The directory whose current marker names this key, or null.
    private static Path currentKeyDir(Path dataDir, String key) {
        Path found = null;
        if (Files.isDirectory(dataDir)) {
            try (java.nio.file.DirectoryStream<Path> directories = Files.newDirectoryStream(dataDir)) {
                java.util.Iterator<Path> each = directories.iterator();
                while (found == null && each.hasNext()) {
                    Path directory = each.next();
                    if (Files.isRegularFile(directory.resolve(CURRENT_MARKER))
                            && key.equals(markerOf(directory, CURRENT_MARKER))) {
                        found = directory;
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                found = null;
            }
        }
        return found;
    }

    private static boolean heldUnderAPrior(String held, String[] priorKeys) {
        boolean matched = false;
        for (int at = 0; at < priorKeys.length && !matched; at++) {
            String prior = priorKeys[at];
            matched = held.equals(prior == null ? "" : prior.strip());
        }
        return matched;
    }

    // A folder search over both collision chains: where it looks, how it reads a marker, and whose marker counts.
    private interface Search {

        Path dataDir();

        String markerAt(Path dir);

        boolean isOurs(String marker);
    }

    // One key's folder search: the first folder, its marker, the key and its older spellings.
    private record WorldSearch(Path dataDir, Path first, String held, String key, String[] priorKeys)
            implements Search {

        // The first folder's marker is already held.
        @Override
        public String markerAt(Path dir) {
            return dir.equals(first) ? held : markerOf(dir);
        }

        @Override
        public boolean isOurs(String marker) {
            return marker != null && markerIsThisWorld(marker, key, priorKeys);
        }
    }

    // One dimension's folder search: the world folder its folders lie in, and the dimension id.
    private record DimensionSearch(Path dataDir, String id) implements Search {

        @Override
        public String markerAt(Path dir) {
            return markerOf(dir, DIMENSION_MARKER);
        }

        @Override
        public boolean isOurs(String marker) {
            return id.equals(marker);
        }
    }

    // ours: a folder whose marker counts; unmarked: one with no marker; free: where a new folder goes.
    private record Collisions(Path ours, Path unmarked, Path free) {
    }

    // An older spelling's own folder, else its collision folder; null when neither holds that spelling.
    private static Path priorFolder(WorldSearch prior, String priorName, String rawPrior) {
        Path was = prior.dataDir().resolve(priorName);
        String there = was.equals(prior.first()) ? null : markerOf(was);
        Path found;
        if (there != null && there.equals(prior.key())) {
            found = was;
        } else {
            found = collisions(prior, priorName + "-" + shortHash(rawPrior)).ours();
        }
        return found;
    }

    // The folder from before the device guard, then this key's collision folders, then the first folder.
    private static Path ownFolder(WorldSearch own, String stripped, String name, String rawKey) {
        Path unguarded = own.dataDir().resolve(stripped);
        Path found;
        if (!unguarded.equals(own.first()) && own.isOurs(markerOf(unguarded))) {
            found = unguarded;
        } else {
            Collisions chains = collisions(own, name + "-" + shortHash(rawKey));
            found = own.held() == null ? ownOrFirst(chains, own.first()) : ownOrFree(chains);
        }
        return found;
    }

    // The hashed collision folder, then the collector's chain after it, then GeoSurvey's numbered chain.
    private static Collisions collisions(Search search, String stem) {
        Path hashed = search.dataDir().resolve(stem);
        String there = search.markerAt(hashed);
        Collisions found;
        if (there == null) {
            found = new Collisions(null, null, hashed);
        } else if (search.isOurs(there)) {
            found = new Collisions(hashed, null, null);
        } else {
            found = pastTheHashedFolder(search, stem);
        }
        return found;
    }

    // Each chain stops at a folder with no marker; the collector's chain is asked first.
    private static Collisions pastTheHashedFolder(Search search, String stem) {
        Collisions collectors = collectorChain(search, stem);
        Collisions found;
        if (collectors.ours() != null) {
            found = collectors;
        } else {
            Collisions numbered = numberedChain(search, stem);
            found = new Collisions(numbered.ours(), collectors.unmarked(),
                    numbered.free() == null ? collectors.free() : numbered.free());
        }
        return found;
    }

    // The collector's own chain: each name adds a dash and the hash of the name before it.
    private static Collisions collectorChain(Search search, String stem) {
        Path ours = null;
        Path stopped = null;
        String divert = stem;
        for (int looked = 1; looked < MAX_FOLDER_DIVERTS && ours == null && stopped == null; looked++) {
            divert = divert + "-" + shortHash(divert);
            Path at = search.dataDir().resolve(divert);
            String there = search.markerAt(at);
            if (there == null) {
                stopped = at;
            }
            if (search.isOurs(there)) {
                ours = at;
            }
        }
        boolean exhausted = ours == null && stopped == null;
        Path free = exhausted ? search.dataDir().resolve(divert + "-" + shortHash(divert)) : stopped;
        Path unmarked = (stopped != null && Files.isDirectory(stopped)) ? stopped : null;
        return new Collisions(ours, unmarked, free);
    }

    // GeoSurvey's numbered chain: the hashed name, a dash, and 1 to NUMBERED_COLLISIONS.
    private static Collisions numberedChain(Search search, String stem) {
        Path ours = null;
        Path free = null;
        for (int probe = 1; probe <= NUMBERED_COLLISIONS && ours == null && free == null; probe++) {
            Path at = search.dataDir().resolve(stem + "-" + probe);
            String there = search.markerAt(at);
            if (there == null) {
                free = at;
            }
            if (search.isOurs(there)) {
                ours = at;
            }
        }
        return new Collisions(ours, null, free);
    }

    // The first folder says nothing: this world's collision folder, else the first folder.
    private static Path ownOrFirst(Collisions chains, Path first) {
        return chains.ours() == null ? first : chains.ours();
    }

    // Another world or dimension holds the first folder: its own collision folder, an unmarked one, else a free one.
    private static Path ownOrFree(Collisions chains) {
        Path found;
        if (chains.ours() != null) {
            found = chains.ours();
        } else if (chains.unmarked() != null) {
            found = chains.unmarked();
        } else {
            found = chains.free();
        }
        return found;
    }

    private static final int MAX_FOLDER_DIVERTS = 28;

    // GeoSurvey's numbered collision folders after the hashed one.
    private static final int NUMBERED_COLLISIONS = 8;

    private static final String STORED_DIMENSION = "ResourceKey[minecraft:dimension / ";

    private static final String STORED_END = "]";

    public static String asStored(String dimension) {
        String id = dimension == null ? "" : dimension.trim();
        if (id.isEmpty() || id.startsWith("ResourceKey[")) {
            return id;
        }
        return STORED_DIMENSION + id + STORED_END;
    }

    // The resource id text of a stored key, such as minecraft:overworld; other text comes back trimmed.
    public static String dimensionIdOf(String stored) {
        String key = stored == null ? "" : stored.trim();
        if (!key.startsWith(STORED_DIMENSION) || !key.endsWith(STORED_END)) {
            return key;
        }
        return key.substring(STORED_DIMENSION.length(), key.length() - STORED_END.length());
    }

    // The raw key that claimed the directory, or null.
    public static String markerOf(Path dir) {
        return markerOf(dir, MARKER);
    }

    private static String markerOf(Path dir, String marker) {
        String held;
        try {
            Path at = dir.resolve(marker);
            held = new String(Files.readAllBytes(at), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            held = null;
        }
        return held;
    }

    // Never overwrites an existing marker.
    public static void claim(Path dir, String rawKey) {
        claim(dir, rawKey, MARKER);
    }

    private static void claim(Path dir, String rawKey, String marker) {
        try {
            Files.createDirectories(dir);
            Path at = dir.resolve(marker);
            if (!Files.exists(at)) {
                Files.write(at, (rawKey == null ? "" : rawKey)
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
        }
    }

    static final class Naming {

        private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

        private static final int HEX_DIGIT_BITS = 4;

        private static final int HEX_DIGIT_MASK = 0xF;

        private static final int HEX_DIGITS_PER_BYTE = 2;

        private static final int SHORT_HASH_BYTES = 4;

        private static final int SHORT_HASH_LENGTH = SHORT_HASH_BYTES * HEX_DIGITS_PER_BYTE;

        private static final int ASCII_CASE_BIT = 0x20;

        private static final ThreadLocal<MessageDigest> SHORT_HASH = ThreadLocal.withInitial(() -> {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException unavailable) {
                digest = null;
            }
            return digest;
        });

        private static final String LEGACY_PREFIX = "resourcekey_";

        private static final String LEGACY_MARKER = "_dimension___";

        private Naming() {
        }

        private static String shortHash(String raw) {
            MessageDigest digest = SHORT_HASH.get();
            if (digest == null) {
                return "x";
            }
            digest.reset();
            byte[] out = digest.digest((raw == null ? "" : raw)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SHORT_HASH_LENGTH);
            for (int i = 0; i < SHORT_HASH_BYTES; i++) {
                appendHex(sb, out[i]);
            }
            return sb.toString();
        }

        private static void appendHex(StringBuilder sb, int b) {
            sb.append(HEX_DIGITS[(b >> HEX_DIGIT_BITS) & HEX_DIGIT_MASK]).append(HEX_DIGITS[b & HEX_DIGIT_MASK]);
        }

        private static String sanitise(String name) {
            return guardDevice(stripUnsafe(name));
        }

        private static String guardDevice(String out) {
            return guardDevice(out, RESERVED_DEVICE_NAMES,
                    SHORTEST_RESERVED_NAME, LONGEST_RESERVED_NAME);
        }

        private static String guardDevice(String out, java.util.Set<String> reserved,
                                          int shortest, int longest) {
            int dot = out.indexOf('.');
            int head = dot < 0 ? out.length() : dot;
            if (head < shortest || head > longest) {
                return out;
            }
            String named = dot < 0 ? out : out.substring(0, dot);
            if (!reserved.contains(named)) {
                return out;
            }
            return dot < 0 ? out + "_" : named + "_" + out.substring(dot);
        }

        private static String stripUnsafe(String name) {
            if (name == null || name.isBlank()) {
                return "unknown";
            }
            StringBuilder sb = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean upper = c >= 'A' && c <= 'Z';
                boolean safe = (c >= 'a' && c <= 'z') || upper
                        || (c >= '0' && c <= '9') || c == '-' || c == '.';
                sb.append(!safe ? '_' : (upper ? (char) (c | ASCII_CASE_BIT) : c));
            }
            int cut = sb.length();
            while (cut > 0) {
                char tail = sb.charAt(cut - 1);
                if (tail != '.' && tail != '_') {
                    break;
                }
                cut--;
            }
            String stripped;
            if (cut == 0) {
                stripped = "unknown";
            } else {
                sb.setLength(cut);
                stripped = sb.toString();
            }
            return stripped;
        }

        private static String canonicalDimensionFolder(String folder) {
            if (folder == null || !folder.startsWith(LEGACY_PREFIX)) {
                return folder;
            }
            int at = folder.indexOf(LEGACY_MARKER);
            if (at < LEGACY_PREFIX.length() || at != folder.lastIndexOf(LEGACY_MARKER)) {
                return folder;
            }
            int idStart = at + LEGACY_MARKER.length();
            if (!sanitisedRange(folder, LEGACY_PREFIX.length(), at)
                    || !sanitisedRange(folder, idStart, folder.length())
                    || folder.startsWith(LEGACY_PREFIX, idStart)
                    || folder.indexOf('_', idStart) <= idStart) {
                return folder;
            }
            return folder.substring(idStart);
        }

        private static boolean sanitisedRange(String text, int from, int to) {
            if (to <= from || text.charAt(from) == '_' || text.charAt(to - 1) == '_'
                    || text.charAt(to - 1) == '.') {
                return false;
            }
            boolean clean = true;
            for (int i = from; i < to && clean; i++) {
                char c = text.charAt(i);
                clean = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '.' || c == '_';
            }
            return clean;
        }
    }

    public static String sanitise(String name) {
        return Naming.sanitise(name);
    }

    private static String guardDevice(String out) {
        return Naming.guardDevice(out);
    }

    static String guardDevice(String out, java.util.Set<String> reserved,
                              int shortest, int longest) {
        return Naming.guardDevice(out, reserved, shortest, longest);
    }

    private static String stripUnsafe(String name) {
        return Naming.stripUnsafe(name);
    }

    public static String canonicalDimensionFolder(String folder) {
        return Naming.canonicalDimensionFolder(folder);
    }

    private static String shortHash(String raw) {
        return Naming.shortHash(raw);
    }

    private static void appendHex(StringBuilder sb, int b) {
        Naming.appendHex(sb, b);
    }

    private static final int RESERVED_NAME_TABLE = 32;

    private static final int HIGHEST_RESERVED_PORT = 9;

    private static final java.util.Set<String> RESERVED_DEVICE_NAMES = reservedDeviceNames();

    private static java.util.Set<String> reservedDeviceNames() {
        java.util.Set<String> names = new java.util.HashSet<>(RESERVED_NAME_TABLE);
        names.addAll(List.of("con", "prn", "aux", "nul"));
        for (int port = 0; port <= HIGHEST_RESERVED_PORT; port++) {
            names.add("com" + port);
            names.add("lpt" + port);
        }
        return java.util.Set.copyOf(names);
    }

    private static final int SHORTEST_RESERVED_NAME = shortestReservedName();

    private static final int LONGEST_RESERVED_NAME = longestReservedName();

    private static int shortestReservedName() {
        int shortest = Integer.MAX_VALUE;
        for (String name : RESERVED_DEVICE_NAMES) {
            shortest = Math.min(shortest, name.length());
        }
        return shortest;
    }

    private static int longestReservedName() {
        int longest = 0;
        for (String name : RESERVED_DEVICE_NAMES) {
            longest = Math.max(longest, name.length());
        }
        return longest;
    }

    public void setLegacyColourConverter(java.util.function.IntFunction<LandCover> converter) {
        this.legacyColourToCover = converter;
    }

    public void setRoot(Path newRoot) {
        if (root != null && root.equals(newRoot)) {
            return;
        }
        boolean noWait = noWaitScope();
        try {
            flushQuietly(sharedWrites != null);
        } catch (RuntimeException | Error failed) {
            note.accept("geosurvey: could not flush the world on exit."
                    + " Unsaved ground is"
                    + " lost (" + failed.getClass().getSimpleName() + ").");
        } finally {
            endNoWaitScope(noWait);
        }
        for (MapStore going : stores.values()) {
            going.setSizeListener(null);
        }
        stores.clear();
        residentChunks = 0;
        landmarks.clear();
        overlays.clear();
        dirty.clear();
        synchronized (claiming) {
            folders.clear();
            folderOwners.clear();
            claimedFolders.clear();
            root = newRoot;
        }
        outstanding.clear();
        askedGround.clear();
        for (RegionLoad leaving : regionLoads) {
            leaving.abandoned().set(true);
            leaving.letGo();
        }
        regionLoads.clear();
        regionLoadsByStore.clear();
        for (int at = 0; at < pendingFolds.size(); at++) {
            retireFold(pendingFolds.get(at));
        }
        pendingFolds.clear();
        for (int at = 0; at < landmarkLoads.size(); at++) {
            landmarkLoads.get(at).letGo();
        }
        landmarkLoads.clear();
        landmarkLoadsByDimension.clear();
        landmarkLoadFailures.clear();
        landmarksWantedAgain.clear();
        leftBehindLandmarkWrites.putAll(refusedLandmarkWrites);
        refusedLandmarkWrites.clear();
        for (Migration migration : migrations) {
            migration.waiting = null;
        }
        rootGeneration++;
    }

    // Drops the store without writing; the caller owns its content.
    public void unload(String dimensionId) {
        MapStore going = stores.remove(dimensionId);
        if (going != null) {
            residentChunks -= going.size();
            going.setSizeListener(null);
            for (RegionLoad leaving : regionLoads) {
                if (leaving.store() == going) {
                    leaving.abandoned().set(true);
                }
            }
        }
        dirty.remove(dimensionId);
    }

    public Path root() {
        return root;
    }

    private Folder folderFor(Path at, String dimensionId) {
        String id = dimensionId == null ? "" : dimensionId;
        Folder known = folders.get(id);
        if (known != null && known.root() == at) {
            return known;
        }
        FolderName picked = resolveFolder(at, id);
        Folder settled = settleFolder(at, id, picked.name(), picked.markerWasNull());
        return settled != null ? settled : divertFolder(at, id);
    }

    private Folder settleFolder(Path at, String id, String name, boolean ownerMatters) {
        synchronized (claiming) {
            if (at != root) {
                return new Folder(at, name, at.resolve(name));
            }
            Folder known = folders.get(id);
            if (known == null || known.root() != at) {
                String owner = ownerMatters ? folderOwners.get(name) : null;
                if (owner == null || owner.equals(id)) {
                    known = new Folder(at, name, at.resolve(name));
                    folders.put(id, known);
                    folderOwners.put(name, id);
                } else {
                    known = null;
                }
            }
            return known;
        }
    }

    // The hashed folder, then GeoSurvey's numbered chain, then the collector's: the first no other dimension holds.
    private Folder divertFolder(Path at, String id) {
        String stem = dimensionFolder(id) + "-" + shortHash(id);
        Folder settled = freeFolder(at, id, stem);
        for (int probe = 1; settled == null && probe <= NUMBERED_COLLISIONS; probe++) {
            settled = freeFolder(at, id, stem + "-" + probe);
        }
        String candidate = stem;
        for (int looked = 1; settled == null && looked < MAX_FOLDER_DIVERTS; looked++) {
            candidate = candidate + "-" + shortHash(candidate);
            settled = freeFolder(at, id, candidate);
        }
        return settled != null ? settled
                : settleFolder(at, id, candidate + "-" + shortHash(candidate), false);
    }

    private Folder freeFolder(Path at, String id, String candidate) {
        return folderIsFree(at, candidate, id) ? settleFolder(at, id, candidate, true) : null;
    }

    private boolean folderIsFree(Path at, String name, String id) {
        String owner = folderOwners.get(name);
        if (owner != null && !owner.equals(id)) {
            return false;
        }
        String held = markerOf(at.resolve(name), DIMENSION_MARKER);
        return held == null || held.equals(id);
    }

    public static String dimensionFolder(String dimensionId) {
        return guardDevice(dev.openmap.live.WorldMapping.publishedFolder(dimensionId));
    }

    // An older spelling's folder, else its collision folder of either chain; null when neither says this dimension.
    private static String keptFolder(DimensionSearch search, String name, String hash) {
        String was = sanitise(search.id());
        if (was.equals(name)) {
            return null;
        }
        String kept;
        if (search.isOurs(search.markerAt(search.dataDir().resolve(was)))) {
            kept = was;
        } else {
            kept = nameOf(collisions(search, was + "-" + hash).ours());
        }
        return kept;
    }

    private static FolderName resolveFolder(Path at, String id) {
        String name = dimensionFolder(id);
        String held = markerOf(at.resolve(name), DIMENSION_MARKER);
        FolderName picked;
        if (id.equals(held)) {
            picked = new FolderName(name, false);
        } else if (held == null) {
            picked = unmarkedFolder(new DimensionSearch(at, id), name, shortHash(id));
        } else {
            Collisions chains = collisions(new DimensionSearch(at, id), name + "-" + shortHash(id));
            picked = new FolderName(nameOf(ownOrFree(chains)), chains.ours() == null);
        }
        return picked;
    }

    // Its own folder says nothing: an older spelling's folder, a collision folder marked for it, else its own.
    private static FolderName unmarkedFolder(DimensionSearch search, String name, String hash) {
        String kept = keptFolder(search, name, hash);
        if (kept == null) {
            kept = nameOf(collisions(search, name + "-" + hash).ours());
        }
        return kept == null ? new FolderName(name, true) : new FolderName(kept, false);
    }

    // The folder's name; null for a null folder.
    private static String nameOf(Path folder) {
        return folder == null ? null : folder.getFileName().toString();
    }

    private static final long CLAIM_WAIT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

    private static final int MAX_CLAIM_ROUNDS = 4;

    private boolean claimFolder(Path at, String dimensionId) {
        String id = dimensionId == null ? "" : dimensionId;
        if (at == null || (at == root && claimedFolders.contains(id))) {
            return true;
        }
        long deadline = System.nanoTime() + CLAIM_WAIT_NANOS;
        boolean again = true;
        for (int round = 0; again && round < MAX_CLAIM_ROUNDS; round++) {
            again = claimOrAwait(at, id, deadline);
        }
        return claimedFolders.contains(id) || !claimsInFlight.containsKey(id);
    }

    private boolean claimOrAwait(Path at, String id, long deadline) {
        if (at == root && claimedFolders.contains(id)) {
            return false;
        }
        java.util.concurrent.CountDownLatch mine = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch running = claimsInFlight.putIfAbsent(id, mine);
        boolean again = false;
        if (running != null) {
            again = awaitClaim(running, deadline);
        } else {
            claimAs(at, id, mine);
        }
        return again;
    }

    private void claimAs(Path at, String id, java.util.concurrent.CountDownLatch mine) {
        try {
            if (at != root || !claimedFolders.contains(id)) {
                Folder held = folderFor(at, id);
                claim(held.dir(), id, DIMENSION_MARKER);
                settleClaim(id, held, id.equals(markerOf(held.dir(), DIMENSION_MARKER)));
            }
        } finally {
            claimsInFlight.remove(id, mine);
            mine.countDown();
        }
    }

    private static boolean awaitClaim(java.util.concurrent.CountDownLatch running,
                                      long deadline) {
        long left = deadline - System.nanoTime();
        boolean settled = false;
        if (left > 0L) {
            try {
                settled = running.await(left, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return settled;
    }

    private void settleClaim(String id, Folder held, boolean marked) {
        synchronized (claiming) {
            if (held.root() != root) {
                return;
            }
            if (marked) {
                if (held.equals(folders.get(id))) {
                    claimedFolders.add(id);
                }
            } else {
                folders.remove(id, held);
                folderOwners.remove(held.name(), id);
            }
        }
    }

    public Path fileFor(String dimensionId) {
        return fileFor(root, dimensionId);
    }

    private Path fileFor(Path at, String dimensionId) {
        if (at == null) {
            return null;
        }
        return at.resolve(folderFor(at, dimensionId).name() + EXTENSION);
    }

    public Path regionDirFor(String dimensionId) {
        return regionDirFor(root, dimensionId);
    }

    private Path regionDirFor(Path at, String dimensionId) {
        if (at == null) {
            return null;
        }
        return folderFor(at, dimensionId).dir();
    }

    public Path regionFileFor(String dimensionId, int regionX, int regionZ) {
        Path at = root;
        if (!claimFolder(at, dimensionId)) {
            return null;
        }
        return regionFileIn(at, dimensionId, regionX, regionZ);
    }

    private Path regionFileIn(Path at, String dimensionId, int regionX, int regionZ) {
        Path dir = regionDirFor(at, dimensionId);
        return dir == null ? null : dir.resolve(MapRegion.fileName(regionX, regionZ));
    }

    private static final int PENDING_REGIONS = 32;

    private static final int MIGRATION_MAX_DEFERRALS = 4;

    private static final int MIGRATION_MAX_RUNS = 5;

    private static final long DEFAULT_BACKOFF_STEP_NANOS = 250_000_000L;

    private long migrationBackoffStepNanos = DEFAULT_BACKOFF_STEP_NANOS;

    void setMigrationBackoffStepNanos(long migrationBackoffStepNanos) {
        this.migrationBackoffStepNanos = migrationBackoffStepNanos;
    }

    public int migrateToRegions(String dimensionId) {
        return migrateToRegions(dimensionId, PENDING_REGIONS);
    }

    int migrateToRegions(String dimensionId, int maxPending) {
        Path at = root;
        Path old = fileFor(at, dimensionId);
        if (old == null || !Files.isRegularFile(old)) {
            return 0;
        }
        if (!claimFolder(at, dimensionId)) {
            return -1;
        }
        Path dir = regionDirFor(at, dimensionId);
        boolean noWait = noWaitScope();
        int moved;
        try {
            moved = migrate(legacyBeside(dir), dir, legacyColourToCover, maxPending,
                    damage, unconverted);
        } finally {
            endNoWaitScope(noWait);
        }
        return moved;
    }

    private static Path legacyBeside(Path dir) {
        return dir.resolveSibling(dir.getFileName() + EXTENSION);
    }

    private static int migrate(Path old, Path dir,
                               java.util.function.IntFunction<LandCover> legacy,
                               int maxPending,
                               java.util.function.Consumer<String> damage,
                               java.util.function.Consumer<String> unconverted) {
        PendingRegions pending = new PendingRegions();
        java.util.Set<Long> flushed = new java.util.HashSet<>();
        Map<Long, Path> spilled = new LinkedHashMap<>();
        int moved;
        try {
            moved = MapCodec.stream(old, legacy, sample -> {
                long region = MapRegion.keyOfChunk(sample.chunkX, sample.chunkZ);
                pending.storeFor(region).putClean(sample);
                while (pending.size() > maxPending) {
                    long eldest = pending.eldestKey();
                    MapStore gone = pending.removeEldest();
                    if (flushed.add(eldest)) {
                        writeRegion(dir, eldest, gone, legacy, damage);
                    } else {
                        spill(gone, dir, eldest, spilled);
                    }
                }
            }, MapCodec.UNLIMITED_CHUNKS, damage);
            for (int at = 0; at < pending.size(); at++) {
                long region = pending.keyAt(at);
                MapStore store = pending.storeAt(at);
                if (flushed.add(region)) {
                    writeRegion(dir, region, store, legacy, damage);
                } else {
                    spill(store, dir, region, spilled);
                }
            }
            for (Map.Entry<Long, Path> entry : spilled.entrySet()) {
                assemble(dir, entry.getKey(), entry.getValue(), legacy, damage);
            }
            Files.move(old, old.resolveSibling(old.getFileName() + ".premigration"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            for (Path spool : spilled.values()) {
                try {
                    Files.deleteIfExists(spool);
                } catch (IOException | RuntimeException leftBehind) {
                    damage.accept(spool.getFileName() + " could not be deleted"
                            + " (" + leftBehind.getClass().getSimpleName()
                            + ")");
                }
            }
            unconverted.accept(unconvertedWhy(old, dir, failed));
            moved = -1;
        }
        return moved;
    }

    private static final class PendingRegions {

        private static final int FIRST_SLOTS = 8;

        private static final int SLOT_GROWTH = 2;

        private long[] regions = new long[FIRST_SLOTS];
        private MapStore[] stores = new MapStore[FIRST_SLOTS];
        private int size;

        MapStore storeFor(long region) {
            int at = size - 1;
            while (at >= 0 && regions[at] != region) {
                at--;
            }
            MapStore found;
            if (at < 0) {
                if (size == regions.length) {
                    regions = java.util.Arrays.copyOf(regions, regions.length * SLOT_GROWTH);
                    stores = java.util.Arrays.copyOf(stores, stores.length * SLOT_GROWTH);
                }
                regions[size] = region;
                found = newRegionStore();
                stores[size] = found;
                size++;
            } else if (at == size - 1) {
                found = stores[at];
            } else {
                found = stores[at];
                System.arraycopy(regions, at + 1, regions, at, size - at - 1);
                System.arraycopy(stores, at + 1, stores, at, size - at - 1);
                regions[size - 1] = region;
                stores[size - 1] = found;
            }
            return found;
        }

        int size() {
            return size;
        }

        long eldestKey() {
            return regions[0];
        }

        MapStore removeEldest() {
            MapStore gone = stores[0];
            size--;
            System.arraycopy(regions, 1, regions, 0, size);
            System.arraycopy(stores, 1, stores, 0, size);
            stores[size] = null;
            return gone;
        }

        long keyAt(int at) {
            return regions[at];
        }

        MapStore storeAt(int at) {
            return stores[at];
        }
    }

    private static final String SPOOLED_SUFFIX = ".migrating";

    private static final int SPOOL_BUFFER_BYTES = 1 << 16;

    private static Path spooled(Path dir, long region) {
        return dir.resolve(MapRegion.fileName(MapRegion.x(region), MapRegion.z(region))
                + SPOOLED_SUFFIX);
    }

    private static void spill(MapStore store, Path dir, long region,
                              Map<Long, Path> spilled) {
        java.nio.file.OpenOption[] options = spilled.containsKey(region)
                ? new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND}
                : new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING};
        Path spool = spooled(dir, region);
        spilled.put(region, spool);
        try {
            Files.createDirectories(dir);
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(spool, options),
                            SPOOL_BUFFER_BYTES))) {
                MapCodec.ChunkWriter writer = new MapCodec.ChunkWriter();
                for (ChunkSample sample : store.all()) {
                    MapCodec.writeChunk(out, sample, writer);
                }
            }
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private static void assemble(Path dir, long region, Path spool,
                                 java.util.function.IntFunction<LandCover> legacy,
                                 java.util.function.Consumer<String> damage) throws IOException {
        MapStore store = newRegionStore();
        byte[] record = new byte[MapCodec.CHUNK_BYTES];
        MapCodec.ChunkReader reader = new MapCodec.ChunkReader();
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(spool),
                        SPOOL_BUFFER_BYTES))) {
            int filled = fillRecord(in, record);
            while (filled == record.length) {
                ChunkSample sample = reader.read(record);
                ChunkSample held = store.peek(sample.chunkX, sample.chunkZ);
                if (held == null || outranks(sample, held)) {
                    store.putClean(sample);
                }
                filled = fillRecord(in, record);
            }
        }
        Files.deleteIfExists(spool);
        writeRegion(dir, region, store, legacy, damage);
    }

    private static int fillRecord(java.io.InputStream in, byte[] record) throws IOException {
        int filled = 0;
        boolean ended = false;
        while (!ended && filled < record.length) {
            int got = in.read(record, filled, record.length - filled);
            if (got < 0) {
                ended = true;
            } else {
                filled += got;
            }
        }
        return filled;
    }

    private static void writeRegion(Path dir, long region, MapStore store,
                                    java.util.function.IntFunction<LandCover> legacy,
                                    java.util.function.Consumer<String> damage) {
        MIGRATED_REGION_WRITES.incrementAndGet();
        Path file = dir.resolve(
                MapRegion.fileName(MapRegion.x(region), MapRegion.z(region)));
        try {
            writeRegionFile(store, file, legacy, damage);
        } catch (IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private static final class Migration {

        private final Path legacy;
        private final Path dir;
        private final java.util.function.IntFunction<LandCover> legacyColours;
        private final java.util.function.Consumer<String> damage;
        private final java.util.function.Consumer<String> unconverted;
        private final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean failed;
        private java.util.function.BooleanSupplier droppedUnrun;
        private MapStore waiting;
        private volatile long nextAttemptNanos;
        private volatile int attempts;
        private volatile boolean ran;
        private volatile int runs;
        private volatile boolean givenUp;

        private Migration(Path legacy, Path dir,
                          java.util.function.IntFunction<LandCover> legacyColours,
                          MapStore waiting, java.util.function.Consumer<String> damage,
                          java.util.function.Consumer<String> unconverted) {
            this.legacy = legacy;
            this.dir = dir;
            this.legacyColours = legacyColours;
            this.waiting = waiting;
            this.damage = damage;
            this.unconverted = unconverted;
            this.nextAttemptNanos = System.nanoTime();
        }

        private void run() {
            ran = true;
            try {
                if (migrate(legacy, dir, legacyColours, PENDING_REGIONS, damage,
                        unconverted) >= 0) {
                    done.set(true);
                } else {
                    failed = true;
                    runs++;
                }
            } catch (RuntimeException crashed) {
                failed = true;
                runs++;
                unconverted.accept(unconvertedWhy(legacy, dir, crashed));
            } catch (Error crashed) {
                failed = true;
                runs++;
                throw crashed;
            }
        }
    }

    private final List<Migration> migrations = new ArrayList<>();

    private void migrateSoon(String dimensionId, MapStore made) {
        Writes shared = sharedWrites;
        if (shared == null) {
            migrateToRegions(dimensionId);
            return;
        }
        Path at = root;
        Path old = fileFor(at, dimensionId);
        if (old == null || !Files.isRegularFile(old)) {
            return;
        }
        boolean joined = false;
        for (int look = 0; look < migrations.size() && !joined; look++) {
            Migration running = migrations.get(look);
            if (running.legacy.equals(old) && !running.done.get()) {
                running.waiting = made;
                joined = true;
            }
        }
        if (!joined) {
            if (claimFolder(at, dimensionId)) {
                Path dir = regionDirFor(at, dimensionId);
                Migration migration = new Migration(legacyBeside(dir), dir,
                        legacyColourToCover, made, damage, unconverted);
                migrations.add(migration);
                offer(shared, migration);
            }
        }
    }

    private void offer(Writes shared, Migration migration) {
        java.util.function.BooleanSupplier droppedUnrun;
        migration.ran = false;
        try {
            droppedUnrun = shared.off(migration::run);
        } catch (RuntimeException | Error refusedLoudly) {
            migrations.remove(migration);
            throw refusedLoudly;
        }
        if (droppedUnrun == null) {
            migration.failed = true;
        } else {
            migration.droppedUnrun = droppedUnrun;
        }
    }

    private void settleMigrations() {
        for (int at = migrations.size() - 1; at >= 0; at--) {
            Migration migration = migrations.get(at);
            if (!migration.done.get() && !migration.givenUp && (migration.failed
                    || (migration.droppedUnrun != null
                    && migration.droppedUnrun.getAsBoolean()))) {
                if (System.nanoTime() - migration.nextAttemptNanos < 0) {
                    continue;
                }
                if (migration.ran && migration.runs >= MIGRATION_MAX_RUNS) {
                    migration.givenUp = true;
                    note.accept("geosurvey stopped converting "
                            + migration.legacy.getFileName() + " into region files after "
                            + migration.runs + " attempts failed. Old map untouched;"
                            + " its ground is not in the new map;"
                            + " retry at the next"
                            + " game start.");
                } else {
                    migration.failed = false;
                    migration.attempts++;
                    migration.nextAttemptNanos = System.nanoTime()
                            + migration.attempts * migrationBackoffStepNanos;
                    Writes shared = sharedWrites;
                    if (shared == null
                            || (!migration.ran
                            && migration.attempts > MIGRATION_MAX_DEFERRALS)) {
                        migration.run();
                    } else {
                        offer(shared, migration);
                    }
                    dropSettledMigration(at, migration);
                }
            } else {
                dropSettledMigration(at, migration);
            }
        }
    }

    private void dropSettledMigration(int at, Migration migration) {
        if (migration.done.get()) {
            migrations.remove(at);
            if (migration.waiting != null) {
                migration.waiting.forgetFetched();
            }
        }
    }

    private static final int REGION_STORE_CAPACITY = MapRegion.CHUNKS * MapRegion.CHUNKS;

    private static MapStore newRegionStore() {
        return new MapStore(REGION_STORE_CAPACITY);
    }

    static final int MAX_REGION_CHUNKS = MapRegion.CHUNKS * MapRegion.CHUNKS;

    private boolean loadRegion(String dimensionId, MapStore store, int regionX, int regionZ) {
        Path file = regionFileIn(root, dimensionId, regionX, regionZ);
        if (file == null) {
            return true;
        }
        boolean read;
        try {
            if (MapCodec.present(file)) {
                restoreQueuedWrite(dimensionId, store, regionX, regionZ);
                LoadedRegionGate gate = new LoadedRegionGate(store);
                MapCodec.stream(file, legacyColourToCover, gate,
                        sample -> putUnlessOutranked(store, sample), MAX_REGION_CHUNKS, damage);
            }
            read = true;
        } catch (IOException unreadable) {
            sayUnreadable(file);
            read = false;
        }
        return read;
    }

    private static final class LoadedRegionGate implements MapCodec.ColumnGate {

        private final MapStore store;

        private ChunkSample fresh;

        private long diskCapturedAt;

        private int diskCaptureVersion;

        private LoadedRegionGate(MapStore store) {
            this.store = store;
        }

        @Override
        public boolean keeps(int chunkX, int chunkZ, long capturedAt, int captureVersion) {
            fresh = store.peek(chunkX, chunkZ);
            diskCapturedAt = capturedAt;
            diskCaptureVersion = captureVersion;
            return fresh == null || mayOutrank(capturedAt, captureVersion, fresh);
        }

        @Override
        public boolean keepsColumns(byte[] columns, int version,
                                    java.util.function.IntFunction<LandCover> legacy) {
            return fresh == null
                    || mayOutrank(diskCapturedAt, diskCaptureVersion, fresh, columns, version,
                            legacy);
        }
    }

    private static void putUnlessOutranked(MapStore store, ChunkSample sample) {
        ChunkSample held = store.peekValue(sample.chunkX, sample.chunkZ);
        if (held == null || outranks(sample, held)) {
            store.putClean(sample);
        }
    }

    private ChunkSample[] queuedWriteSnapshot(String dimensionId, MapStore store,
                                              int regionX, int regionZ) {
        ChunkSample[] snapshot = null;
        for (int at = outstanding.size() - 1; at >= 0 && snapshot == null; at--) {
            Outstanding write = outstanding.get(at);
            if (write.store() == store && write.regionX() == regionX
                    && write.regionZ() == regionZ && !write.landed().get()
                    && !write.droppedUnrun().getAsBoolean()
                    && dimensionId.equals(write.dimensionId())) {
                snapshot = write.ground();
            }
        }
        return snapshot;
    }

    private void restoreQueuedWrite(String dimensionId, MapStore store,
                                    int regionX, int regionZ) {
        ChunkSample[] held = queuedWriteSnapshot(dimensionId, store, regionX, regionZ);
        if (held == null) {
            return;
        }
        for (ChunkSample sample : held) {
            if (sample != null && store.peek(sample.chunkX, sample.chunkZ) == null) {
                store.putClean(sample);
            }
        }
    }

    private boolean pruneRegionLoads(MapStore store, long region) {
        List<RegionLoad> mine = regionLoadsByStore.get(store);
        RegionLoad open = mine == null ? null : regionLoadOpen(mine, region);
        boolean stillOpen;
        if (open == null) {
            if (mine != null) {
                forgetDroppedRegionLoads(store, mine);
            }
            stillOpen = false;
        } else if (droppedRegionLoad(open)) {
            regionLoadDropped(open);
            stillOpen = false;
        } else {
            stillOpen = true;
        }
        return stillOpen;
    }

    public void loadRegionInBackground(WorkPool pool, String owner, String dimensionId,
                                       MapStore store, int regionX, int regionZ) {
        if (pruneRegionLoads(store, MapRegion.key(regionX, regionZ))) {
            return;
        }
        countAsUse(dimensionId, store);
        boolean loading = holds(dimensionId, store);
        if (loading && store.regionLoadsGivenUp(regionX, regionZ)) {
            restoreQueuedWrite(dimensionId, store, regionX, regionZ);
            loading = false;
        }
        if (loading) {
            sweepDroppedRegionLoads();
            Path dir = regionDirFor(dimensionId);
            RegionLoad load = takeRegionLoad(dir, regionX, regionZ);
            load.aim(dimensionId, store, dir, regionX, regionZ);
            load.legacy = legacyColourToCover;
            load.sayFailure = !store.regionLoadFailedBefore(regionX, regionZ);
            restoreQueuedWrite(dimensionId, store, regionX, regionZ);
            load.resident = store.size() == 0 ? null
                    : heldRegionSlots(load, store, regionX, regionZ);
            boolean queued = pool.submit(owner, load.compute, load.apply, load.receipt(),
            load.drop,
            load.discard);
            if (queued) {
                regionLoads.add(load);
                trackRegionLoad(store, load);
            } else {
                releaseRegionLoad(load);
            }
        }
    }

    private static boolean wantsResidentOrNewer(ChunkSample[] resident, ChunkSample sample) {
        if (resident == null) {
            return true;
        }
        ChunkSample held = resident[MapStore.regionSlot(sample.chunkX, sample.chunkZ)];
        return held == null || outranks(sample, held);
    }

    private ChunkSample[] heldRegionSlots(RegionLoad load, MapStore store, int regionX,
                                          int regionZ) {
        ChunkSample[] held = load.slots;
        if (held == null) {
            held = new ChunkSample[MAX_REGION_CHUNKS];
            load.slots = held;
        } else {
            java.util.Arrays.fill(held, null);
        }
        int firstX = MapRegion.firstChunk(regionX);
        int firstZ = MapRegion.firstChunk(regionZ);
        for (int dx = 0; dx < MapRegion.CHUNKS; dx++) {
            for (int dz = 0; dz < MapRegion.CHUNKS; dz++) {
                int chunkX = firstX + dx;
                int chunkZ = firstZ + dz;
                ChunkSample sample = store.peekValue(chunkX, chunkZ);
                if (sample != null) {
                    held[MapStore.regionSlot(chunkX, chunkZ)] = sample;
                }
            }
        }
        return held;
    }

    public MapStore store(String dimensionId) {
        drainPendingFolds();
        MapStore store = stores.get(dimensionId);
        if (store == null) {
            MapStore made = new MapStore(capacity);
            made.retryRefusedOverflow();
            countIn(made);
            migrateSoon(dimensionId, made);
            made.setLoader((s, rx, rz) -> loadRegion(dimensionId, s, rx, rz));
            made.setWriter((s, rx, rz) -> writeRegionSoon(dimensionId, s, rx, rz));
            made.setLostGroundListener((rx, rz) -> note.accept("geosurvey dropped a"
                    + " surveyed chunk of " + dimensionId + " region " + rx + "," + rz
                    + "; write-back refused or not"
                    + " retried (" + made.unsavedDrops() + " unsaved for"
                    + " this dimension)."));
            store = made;
            stores.put(dimensionId, made);
        }
        if (!migrations.isEmpty()) {
            boolean noWait = noWaitScope();
            try {
                settleMigrations();
            } finally {
                endNoWaitScope(noWait);
            }
        }
        retryLandmarkWrites();
        boundResidentStores(dimensionId);
        return store;
    }

    private void retryLandmarkWrites() {
        if (refusedLandmarkWrites.isEmpty() && landmarksWantedAgain.isEmpty()) {
            return;
        }
        java.util.Set<String> dimensions = new java.util.LinkedHashSet<>();
        for (LandmarkWrite refused : refusedLandmarkWrites.values()) {
            dimensions.add(refused.dimensionId());
        }
        dimensions.addAll(landmarksWantedAgain.values());
        for (String dimensionId : dimensions) {
            try {
                saveLandmarksInBackground(dimensionId);
            } catch (IOException failed) {
                String why = failed.getMessage();
                note.accept("geosurvey could not save the markers for " + dimensionId
                        + " (" + failed.getClass().getSimpleName()
                        + (why == null ? "" : ": " + why) + ").");
            }
        }
    }

    private void countIn(MapStore store) {
        residentChunks += store.size();
        store.setSizeListener(delta -> residentChunks += delta);
    }

    private void boundResidentStores(String keep) {
        if (root == null) {
            return;
        }
        boolean evicting = true;
        while (evicting && stores.size() > 1 && residentChunks > residentBudget) {
            String coldest = null;
            MapStore going = null;
            for (Map.Entry<String, MapStore> entry : stores.entrySet()) {
                if (!entry.getKey().equals(keep)) {
                    coldest = entry.getKey();      // access order: eldest first
                    going = entry.getValue();
                    break;
                }
            }
            int queued;
            try {
                queued = coldest == null ? 0 : writeOut(coldest, going);
            } catch (IOException failed) {
                // A sentinel: save() already re-marked the regions it did not write.
                queued = 1;
            }
            boolean letGo = coldest != null && !(going != null && going.hasDirtyRegions());
            if (letGo) {
                letGo = !stillWriting(going);
            }
            if (letGo && queued > 0) {
                takeBackFailedWrites();
                letGo = !(going != null && going.hasDirtyRegions());
            }
            if (letGo) {
                unload(coldest);
            }
            evicting = letGo;
        }
    }

    private int writeOut(String dimensionId, MapStore going) throws IOException {
        boolean writing;
        if (going == null) {
            writing = false;
        } else {
            writing = stillWriting(going);
        }
        int handed;
        if (writing) {
            handed = 0;
        } else if (sharedWrites == null || going == null) {
            save(dimensionId);
            handed = 0;
        } else {
            boolean wasDirty = going.hasDirtyRegions();
            takeBackFailedWrites();
            handed = 0;
            if (going.hasDirtyRegions()) {
                long[] regions = going.dirtyRegionsInOrder();
                int limit = wasDirty ? MAX_QUEUED_REGION_WRITES : 1;
                try {
                    boolean refused = false;
                    while (!refused && handed < regions.length && handed < limit) {
                        refused = handOff(dimensionId, going, MapRegion.x(regions[handed]),
                                MapRegion.z(regions[handed])) != MapStore.Handoff.QUEUED;
                        if (!refused) {
                            going.markRegionHandedOff(MapRegion.x(regions[handed]),
                                    MapRegion.z(regions[handed]));
                            handed++;
                        }
                    }
                } finally {
                    if (going.hasDirtyRegions()) {
                        markDirty(dimensionId);
                    } else {
                        markClean(dimensionId);
                    }
                }
            }
        }
        return handed;
    }

    // Tick thread only; another thread writes.
    // Never returns SAVED.
    MapStore.Handoff writeRegionSoon(String dimensionId, MapStore store,
                                     int regionX, int regionZ) {
        takeBackFailedWrites();
        countAsUse(dimensionId, store);
        return holds(dimensionId, store) ? handOff(dimensionId, store, regionX, regionZ)
                : MapStore.Handoff.REFUSED;
    }

    private MapStore.Handoff handOff(String dimensionId, MapStore store,
                                     int regionX, int regionZ) {
        Writes shared = sharedWrites;
        boolean room = shared == null || handedOver.size() < MAX_TRACKED_HANDOVERS;
        if (!room) {
            takeBackDroppedWrites();
            room = handedOver.size() < MAX_TRACKED_HANDOVERS;
        }
        Path file;
        if (room) {
            file = regionFileFor(dimensionId, regionX, regionZ);
        } else {
            file = null;
        }
        MapStore.Handoff answer;
        if (file == null) {
            answer = MapStore.Handoff.REFUSED;
        } else {
            ChunkSample[] prepared = store.regionSnapshotForWriting(regionX, regionZ);
            Unlanded kept = keptAside(file);
            if (kept != null) {
                foldInto(prepared, kept.ground());
            }
            boolean coalesced;
            if (shared != null) {
                coalesced = joinOpenHandoff(dimensionId, store, regionX, regionZ, file, prepared, kept);
            } else {
                coalesced = false;
            }
            answer = coalesced ? MapStore.Handoff.QUEUED
                    : handOffAnew(shared, dimensionId, store, MapRegion.key(regionX, regionZ), file,
                            prepared, kept);
        }
        return answer;
    }

    private MapStore.Handoff handOffAnew(Writes shared, String dimensionId, MapStore store,
                                         long region, Path file, ChunkSample[] prepared,
                                         Unlanded kept) {
        int regionX = MapRegion.x(region);
        int regionZ = MapRegion.z(region);
        java.util.concurrent.atomic.AtomicReference<ChunkSample[]> unwritten =
                new java.util.concurrent.atomic.AtomicReference<>(prepared);
        java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean landed =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.IntFunction<LandCover> legacy = legacyColourToCover;
        java.util.function.BooleanSupplier droppedUnrun = writeInBackground(shared, () -> {
            ChunkSample[] taken = unwritten.getAndSet(null);
            boolean done = taken == null;
            try {
                if (taken != null) {
                    writeRegionFile(taken.clone(), file, legacy, damage);
                    done = true;
                }
            } catch (IOException | RuntimeException unwritable) {
            } finally {
                if (!done) {
                    failedWrites.add(new Unwritten(dimensionId, store, regionX, regionZ,
                            regionStore(taken), file));
                }
                landed.set(true);
            }
        }, settled);
        MapStore.Handoff answer;
        if (droppedUnrun == null) {
            answer = MapStore.Handoff.REFUSED;
        } else {
            if (kept != null) {
                unlanded.remove(file);
            }
            if (shared != null) {
                track(new Handed(droppedUnrun, settled, dimensionId, store,
                        regionX, regionZ, file, unwritten, landed));
            }
            remember(new Outstanding(dimensionId, store, regionX, regionZ,
                    prepared, landed, droppedUnrun));
            answer = MapStore.Handoff.QUEUED;
        }
        return answer;
    }

    private boolean joinOpenHandoff(String dimensionId, MapStore store, int regionX, int regionZ,
                                    Path file, ChunkSample[] prepared, Unlanded kept) {
        Handed open = openHandoffFor(dimensionId, store, regionX, regionZ, file);
        ChunkSample[] queued = open == null ? null : open.unwritten().get();
        boolean joined;
        if (queued == null) {
            joined = false;
        } else {
            foldSlots(prepared, queued);
            joined = open.unwritten().compareAndSet(queued, prepared);
            if (joined) {
                if (kept != null) {
                    unlanded.remove(file);
                }
                remember(new Outstanding(dimensionId, store, regionX, regionZ,
                        prepared, open.landed(), open.droppedUnrun()));
            }
        }
        return joined;
    }

    private Handed openHandoffFor(String dimensionId, MapStore store,
                                  int regionX, int regionZ, Path file) {
        Handed found = null;
        for (int at = handedOver.size() - 1; at >= 0 && found == null; at--) {
            Handed handed = handedOver.get(at);
            if (handed.regionX() == regionX && handed.regionZ() == regionZ
                    && handed.store() == store && !handed.landed().get()
                    && handed.unwritten().get() != null && file.equals(handed.file())
                    && dimensionId.equals(handed.dimensionId())) {
                found = handed;
            }
        }
        return found;
    }

    private record Outstanding(MapStore store,
                               java.util.concurrent.atomic.AtomicBoolean landed,
                               java.util.function.BooleanSupplier droppedUnrun,
                               String dimensionId, int regionX, int regionZ,
                               ChunkSample[] ground) {

        Outstanding(String dimensionId, MapStore store, int regionX, int regionZ,
                    ChunkSample[] ground, java.util.concurrent.atomic.AtomicBoolean landed,
                    java.util.function.BooleanSupplier droppedUnrun) {
            this(store, landed, droppedUnrun, dimensionId, regionX, regionZ, ground);
        }
    }

    private final List<Outstanding> outstanding = new ArrayList<>();

    private void remember(Outstanding write) {
        stillWriting(null);
        outstanding.add(write);
    }

    private boolean stillWriting(MapStore store) {
        boolean writing = false;
        int kept = 0;
        for (int i = 0; i < outstanding.size(); i++) {
            Outstanding write = outstanding.get(i);
            if (write.landed().get() || write.droppedUnrun().getAsBoolean()) {
                continue;
            }
            writing |= write.store() == store;
            outstanding.set(kept++, write);
        }
        for (int last = outstanding.size() - 1; last >= kept; last--) {
            outstanding.remove(last);
        }
        return writing;
    }

    // A region write that did not land; the writer thread leaves it here.
    private record Unwritten(String dimensionId, MapStore store,
                             int regionX, int regionZ, MapStore ground, Path file,
                             boolean dropped) {

        Unwritten(String dimensionId, MapStore store, int regionX, int regionZ,
                  MapStore ground, Path file) {
            this(dimensionId, store, regionX, regionZ, ground, file, false);
        }
    }

    private final java.util.Queue<Unwritten> failedWrites =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private record Unlanded(String dimensionId, int regionX, int regionZ, Path file,
                            MapStore ground) {
    }

    private final Map<Path, Unlanded> unlanded = new LinkedHashMap<>();

    private Unlanded keptAside(Path file) {
        if (file == null || unlanded.isEmpty()) {
            return null;
        }
        return unlanded.get(file);
    }

    private final java.util.IdentityHashMap<MapStore, Path> askedGround =
            new java.util.IdentityHashMap<>();

    private boolean keepUnlanded(String dimensionId, int regionX, int regionZ, Path file,
                                 MapStore ground) {
        Unlanded kept = keptAside(file);
        if (kept != null) {
            fold(ground, kept.ground());
            return true;
        }
        if (unlanded.size() >= MAX_QUEUED_REGION_WRITES) {
            return false;
        }
        unlanded.put(file, new Unlanded(dimensionId, regionX, regionZ, file, ground));
        return true;
    }

    private static void fold(MapStore from, MapStore into) {
        for (int cursor = from.firstIndex(); cursor != 0; cursor = from.nextIndex(cursor)) {
            ChunkSample sample = from.sampleAt(cursor);
            ChunkSample held = into.peek(sample.chunkX, sample.chunkZ);
            if (held == null || outranks(sample, held)) {
                into.putClean(sample);
            }
        }
    }

    private static void foldInto(ChunkSample[] region, MapStore from) {
        for (int cursor = from.firstIndex(); cursor != 0; cursor = from.nextIndex(cursor)) {
            ChunkSample sample = from.sampleAt(cursor);
            int at = MapStore.regionSlot(sample.chunkX, sample.chunkZ);
            ChunkSample held = region[at];
            if (held == null || outranks(sample, held)) {
                region[at] = sample;
            }
        }
    }

    private static void foldSlots(ChunkSample[] region, ChunkSample[] from) {
        for (ChunkSample sample : from) {
            if (sample == null) {
                continue;
            }
            int at = MapStore.regionSlot(sample.chunkX, sample.chunkZ);
            ChunkSample held = region[at];
            if (held == null || outranks(sample, held)) {
                region[at] = sample;
            }
        }
    }

    // Test-only.
    private static final java.util.concurrent.atomic.AtomicLong REGION_STORE_BUILDS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SAVE_DIRTY_SWEEPS =
            new java.util.concurrent.atomic.AtomicLong();

    static long saveDirtySweeps() {
        return SAVE_DIRTY_SWEEPS.get();
    }

    private static final java.util.concurrent.atomic.AtomicLong LANDMARK_SAVES =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong MIGRATED_REGION_WRITES =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong REGION_FILE_WRITES =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong REDUNDANT_REGION_WRITES_SKIPPED =
            new java.util.concurrent.atomic.AtomicLong();

    private static final int KEY_SET_FLOOR = 4;

    private static final int SLOTS_PER_KEY = 2;

    private static long landmarkSnapshots;

    static long landmarkSnapshots() {
        return landmarkSnapshots;
    }

    private static MapStore regionStore(ChunkSample[] region) {
        REGION_STORE_BUILDS.incrementAndGet();
        MapStore out = newRegionStore();
        for (ChunkSample sample : region) {
            if (sample != null) {
                out.putClean(sample);
            }
        }
        return out;
    }

    private void writeUnlanded() {
        java.util.Iterator<Unlanded> waiting = unlanded.values().iterator();
        while (waiting.hasNext()) {
            Unlanded kept = waiting.next();
            try {
                writeRegionFile(kept.ground(), kept.file(), legacyColourToCover, damage);
                waiting.remove();
            } catch (IOException | RuntimeException unwritten) {
            }
        }
    }

    private record Handed(java.util.function.BooleanSupplier droppedUnrun,
                          java.util.concurrent.atomic.AtomicBoolean settled,
                          String dimensionId, MapStore store,
                          int regionX, int regionZ, Path file,
                          java.util.concurrent.atomic.AtomicReference<ChunkSample[]> unwritten,
                          java.util.concurrent.atomic.AtomicBoolean landed) {
    }

    private static final int MAX_TRACKED_HANDOVERS = 128;

    // Tick thread only.
    private final List<Handed> handedOver = new ArrayList<>();

    private void track(Handed handed) {
        handedOver.add(handed);
    }

    private static java.util.function.LongSupplier unstartedWriteClock = System::nanoTime;

    static java.util.function.LongSupplier swapUnstartedWriteClock(
            java.util.function.LongSupplier clock) {
        java.util.function.LongSupplier was = unstartedWriteClock;
        unstartedWriteClock = clock == null ? System::nanoTime : clock;
        return was;
    }

    private void writeUnstarted(boolean all, long until) {
        boolean timed = until != Long.MAX_VALUE;
        for (int at = handedOver.size() - 1;
                at >= 0 && (!timed || unstartedWriteClock.getAsLong() < until); at--) {
            Handed handed = handedOver.get(at);
            boolean dropped = handed.droppedUnrun().getAsBoolean();
            if (!all && !dropped) {
                continue;
            }
            ChunkSample[] taken = handed.unwritten().getAndSet(null);
            if (taken == null) {
                continue;
            }
            handedOver.remove(at);
            settle(handed.settled());
            boolean done = false;
            try {
                writeRegionFile(taken, handed.file(), legacyColourToCover, damage);
                done = true;
            } catch (IOException | RuntimeException unwritable) {
            } finally {
                if (!done) {
                    failedWrites.add(new Unwritten(handed.dimensionId(), handed.store(),
                            handed.regionX(), handed.regionZ(), regionStore(taken),
                            handed.file()));
                }
                handed.landed().set(true);
            }
            if (dropped) {
                note.accept("geosurvey: the work pool dropped the write of " + handed.dimensionId()
                        + " region " + handed.regionX() + "," + handed.regionZ() + "."
                        + (done
                                ? " Written to " + handed.file() + " on the way out."
                                : " Failed writing to " + handed.file() + " on the way out"
                                  + " too."));
            }
        }
    }

    // Tick thread only.
    private void takeBackFailedWrites() {
        if (handedOver.isEmpty() && failedWrites.isEmpty()) {
            return;
        }
        takeBackDroppedWrites();
        for (Unwritten lost = failedWrites.poll(); lost != null;
                lost = failedWrites.poll()) {
            boolean kept = keepUnlanded(lost.dimensionId(), lost.regionX(), lost.regionZ(),
                    lost.file(), lost.ground());
            String cause = lost.dropped()
                    ? "geosurvey: the work pool dropped the write of " + lost.dimensionId()
                            + " region " + lost.regionX() + "," + lost.regionZ() + "."
                    : "geosurvey: could not write the ground of "
                            + lost.dimensionId() + " region " + lost.regionX() + ","
                            + lost.regionZ() + " and the write FAILED.";
            countAsUse(lost.dimensionId(), lost.store());
            if (!holds(lost.dimensionId(), lost.store())) {
                note.accept(cause + " World already left"
                        + (kept
                                ? ": kept aside for " + lost.file() + " at the next"
                                  + " flush."
                                : "; " + MAX_QUEUED_REGION_WRITES
                                  + " other regions already kept aside;"
                                  + " unwritten chunks are lost,"
                                  + " " + lost.file()
                                  + " keeps the older ground."));
            } else {
                lost.store().markRegionDirty(lost.regionX(), lost.regionZ());
                markDirty(lost.dimensionId());
                if (!kept) {
                    note.accept(cause + " Requeued for"
                            + " " + lost.file() + "; "
                            + MAX_QUEUED_REGION_WRITES + " other regions already kept aside;"
                            + " its chunks no longer in memory"
                            + " are lost.");
                }
            }
        }
    }

    // Must run before stores are dropped.
    private void takeBackDroppedWrites() {
        for (int at = handedOver.size() - 1; at >= 0; at--) {
            Handed handed = handedOver.get(at);
            if (!handed.droppedUnrun().getAsBoolean()) {
                if (handed.settled().get()) {
                    handedOver.remove(at);
                }
            } else {
                ChunkSample[] taken = handed.unwritten().getAndSet(null);
                if (taken == null) {
                    if (handed.settled().get()) {
                        handedOver.remove(at);
                    }
                } else {
                    handedOver.remove(at);
                    settle(handed.settled());
                    handed.landed().set(true);
                    boolean kept = keepUnlanded(handed.dimensionId(), handed.regionX(),
                            handed.regionZ(), handed.file(), regionStore(taken));

                    countAsUse(handed.dimensionId(), handed.store());
                    if (!holds(handed.dimensionId(), handed.store())) {
                        note.accept("geosurvey: the work pool dropped the write of "
                                + handed.dimensionId() + " region " + handed.regionX() + ","
                                + handed.regionZ() + "."
                                + (kept
                                        ? " World already left: kept aside for " + handed.file() + " at the next"
                                          + " flush."
                                        : " World already left; " + MAX_QUEUED_REGION_WRITES
                                          + " other regions already kept aside;"
                                          + " unwritten chunks are lost,"
                                          + " " + handed.file()
                                          + " keeps the older ground."));
                    } else {
                        handed.store().markRegionDirty(handed.regionX(), handed.regionZ());
                        markDirty(handed.dimensionId());
                        note.accept("geosurvey: the work pool dropped the write of " + handed.dimensionId()
                                + " region " + handed.regionX() + "," + handed.regionZ() + ". Requeued for"
                                + " "
                                + handed.file()
                                + (kept
                                        ? "."
                                        : "; " + MAX_QUEUED_REGION_WRITES + " other regions"
                                          + " already kept aside; chunks no longer in memory"
                                          + " are lost."));
                    }
                }
            }
        }
    }

    private volatile java.util.function.Consumer<String> note = message -> { };

    // Set once, at start-up.
    public void setLostGroundNote(java.util.function.Consumer<String> note) {
        this.note = note == null ? message -> { } : note;
    }

    private final java.util.function.Consumer<String> damage = this::sayDamage;

    public java.util.function.Consumer<String> damageNote() {
        return damage;
    }

    // Called while the region file's lock is held.
    private void sayDamage(String damage) {
        note.accept("geosurvey read a damaged map file: " + damage);
    }

    private void sayUnreadable(Path file) {
        note.accept("geosurvey could not read a map file: " + file.getFileName());
    }

    private void sayUnreadable(Path file, RuntimeException why) {
        String message = why.getMessage();
        note.accept("geosurvey could not read a map file: " + file.getFileName() + " ("
                + why.getClass().getSimpleName() + (message == null ? "" : ": " + message)
                + ")");
    }

    private void sayMarkersUnreadable(String dimensionId, RuntimeException why) {
        String message = why.getMessage();
        note.accept("geosurvey: could not read the markers for " + dimensionId + " ("
                + why.getClass().getSimpleName() + (message == null ? "" : ": " + message)
                + "). Not loaded; file unchanged.");
    }

    private final java.util.function.Consumer<String> unconverted = this::sayUnconverted;

    private void sayUnconverted(String why) {
        note.accept("geosurvey could not convert " + why);
    }

    private static String unconvertedWhy(Path legacy, Path dir, Exception failed) {
        String message = failed.getMessage();
        return legacy.getFileName() + " into region files in " + dir.getFileName() + " ("
                + failed.getClass().getSimpleName() + (message == null ? "" : ": " + message)
                + ")";
    }

    private static final int MAX_QUEUED_REGION_WRITES = 8;

    private static final long REGION_WRITER_IDLE_SECONDS = 30L;

    // The one thread for region writes.
    private java.util.concurrent.ThreadPoolExecutor regionWrites;

    // The shared pool to write through; null keeps this class's own thread.
    private volatile Writes sharedWrites;

    public interface Writes {

        // null: refused, caller keeps the ground;
        // WILL_RUN: taken, no way to report a later discard.
        // otherwise: a probe, true once the work is thrown away unrun.
        java.util.function.BooleanSupplier off(Runnable write);
    }

    // Compare by identity; calling it answers false.
    public static final java.util.function.BooleanSupplier WILL_RUN = () -> false;

    // Set once, at start-up, before any world is opened.
    public void writeThrough(Writes sink) {
        this.sharedWrites = sink;
    }

    private enum Driver {
        UNNAMED,
        GAME_THREAD,
        WORKER
    }

    private volatile Driver driver = Driver.UNNAMED;

    public void drivenByTheGameThread() {
        driver = Driver.GAME_THREAD;
    }

    public void drivenByAWorker() {
        driver = Driver.WORKER;
    }

    private boolean noWaitScope() {
        boolean noWait = driver != Driver.WORKER;
        if (noWait) {
            AtomicFileReplace.beginNoWait();
        }
        return noWait;
    }

    private static void endNoWaitScope(boolean began) {
        if (began) {
            AtomicFileReplace.endNoWait();
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger inFlightWrites =
            new java.util.concurrent.atomic.AtomicInteger();

    private volatile Thread awaitingWrites;

    private void settle(java.util.concurrent.atomic.AtomicBoolean settled) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        if (inFlightWrites.decrementAndGet() > 0) {
            return;
        }
        Thread waiting = awaitingWrites;
        if (waiting != null) {
            java.util.concurrent.locks.LockSupport.unpark(waiting);
        }
    }

    private java.util.function.BooleanSupplier writeInBackground(Writes shared,
            Runnable write, java.util.concurrent.atomic.AtomicBoolean settled) {
        if (shared != null) {
            inFlightWrites.incrementAndGet();
            java.util.function.BooleanSupplier droppedUnrun;
            try {
                droppedUnrun = shared.off(() -> {
                    try {
                        write.run();
                    } finally {
                        settle(settled);
                    }
                });
            } catch (RuntimeException | Error refusedLoudly) {

                settle(settled);
                throw refusedLoudly;
            }
            if (droppedUnrun == null) {

                settle(settled);
            }
            return droppedUnrun;
        }
        if (regionWrites == null) {
            // Daemon threads.
            regionWrites = new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, REGION_WRITER_IDLE_SECONDS, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(
                            MAX_QUEUED_REGION_WRITES),
                    dev.sandpaper.core.Background.factory(
                            "geosurvey-region-write-"));
            regionWrites.allowCoreThreadTimeOut(true);
        }
        java.util.function.BooleanSupplier taken;
        try {
            regionWrites.execute(write);
            taken = WILL_RUN;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            taken = null;
        }
        return taken;
    }

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private void awaitRegionWrites(boolean claim, long until, long flushStarted) {
        if (sharedWrites != null) {

            int waitingFor = inFlightWrites.get();
            if (waitingFor != 0) {
                long began = System.nanoTime();
                if (claim) {
                    writeUnstarted(true,
                            began + java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
                }
                awaitingWrites = Thread.currentThread();
                boolean waitInterrupted = false;
                try {
                    while (!waitInterrupted && inFlightWrites.get() > 0 && System.nanoTime() < until) {
                        takeBackDroppedWrites();
                        if (inFlightWrites.get() == 0) {
                            break;
                        }
                        long nap = until - System.nanoTime();
                        if (nap > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(this, nap);
                        }
                        if (Thread.interrupted()) {
                            Thread.currentThread().interrupt();
                            note.accept("geosurvey was interrupted after "
                                    + ((System.nanoTime() - flushStarted) / NANOS_PER_MILLI)
                                    + " ms holding the game thread for "
                                    + inFlightWrites.get() + " unfinished write(s).");
                            waitInterrupted = true;
                        }
                    }
                } finally {
                    awaitingWrites = null;
                }
                if (!waitInterrupted) {
                    long heldMillis = (System.nanoTime() - flushStarted) / NANOS_PER_MILLI;
                    int stillGoing = inFlightWrites.get();
                    note.accept("geosurvey held the game thread " + heldMillis
                            + " ms for " + waitingFor + " write(s)."
                            + (stillGoing > 0
                                    ? " Gave up at 1s, "
                                      + stillGoing + " still going; a failed"
                                      + " write is"
                                      + " kept for the next"
                                      + " flush, up to "
                                      + MAX_QUEUED_REGION_WRITES + " regions."
                                    : ""));
                }
            }
        } else if (regionWrites != null) {
            try {
                regionWrites.submit(() -> { }).get(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException
                    | java.util.concurrent.RejectedExecutionException waited) {
            }
        }
    }

    private void stopRegionWrites() {
        java.util.concurrent.ThreadPoolExecutor running = regionWrites;
        if (running == null) {
            return;
        }
        regionWrites = null;
        running.shutdown();
    }

    private MapStore legacyStore(String dimensionId) {
        return stores.computeIfAbsent(dimensionId, id -> {
            Path file = fileFor(id);
            MapStore made = file == null
                    ? new MapStore(capacity)
                    : MapCodec.read(file, capacity, legacyColourToCover);
            countIn(made);
            return made;
        });
    }

    public Path overlayFileFor(String dimensionId) {
        Path at = root;
        if (at == null) {
            return null;
        }
        return at.resolve(folderFor(at, dimensionId).name()
                + dev.openmap.draw.AnnotationStore.EXTENSION);
    }

    public dev.openmap.draw.AnnotationStore annotations(String dimensionId) {
        return overlays.computeIfAbsent(dimensionId, id -> overlayAt(overlayFileFor(id)));
    }

    private dev.openmap.draw.AnnotationStore overlayAt(Path file) {
        OverlaySave kept = refusedOverlaySaves.get(file);
        dev.openmap.draw.AnnotationStore loaded;
        if (kept != null) {
            loaded = kept.store();
        } else if (file == null) {
            loaded = new dev.openmap.draw.AnnotationStore();
        } else {
            loaded = dev.openmap.draw.AnnotationStore.load(file);
        }
        return loaded;
    }

    public void saveAnnotationsQuietly(String dimensionId) {
        dev.openmap.draw.AnnotationStore store = overlays.get(dimensionId);
        Path file = overlayFileFor(dimensionId);
        if (store == null || file == null) {
            return;
        }
        saveOverlay(dimensionId, store, file);
    }

    private void saveOverlay(String dimensionId, dev.openmap.draw.AnnotationStore store,
                             Path file) {
        boolean kept = false;
        boolean noWait = noWaitScope();
        try {
            store.save(file);
        } catch (IOException refused) {
            kept = refused instanceof AtomicFileReplace.Refused;
            String why = refused.getMessage();
            StringBuilder detail = new StringBuilder("geosurvey could not save the overlay for ")
                    .append(dimensionId)
                    .append(" (").append(refused.getClass().getSimpleName())
                    .append(why == null ? "" : ": " + why);
            for (Throwable also : refused.getSuppressed()) {
                String alsoWhy = also.getMessage();
                detail.append("; also ").append(also.getClass().getSimpleName())
                        .append(alsoWhy == null ? "" : ": " + alsoWhy);
            }
            detail.append(")");
            note.accept(detail.toString());
        } finally {
            endNoWaitScope(noWait);
        }
        if (kept) {
            refusedOverlaySaves.put(file, new OverlaySave(dimensionId, store));
        } else {
            refusedOverlaySaves.remove(file);
        }
    }

    private void saveRefusedOverlays() {
        for (Path file : new ArrayList<>(refusedOverlaySaves.keySet())) {
            OverlaySave kept = refusedOverlaySaves.get(file);
            if (!overlays.containsValue(kept.store())) {
                saveOverlay(kept.dimensionId(), kept.store(), file);
            }
        }
    }

    public Path landmarkFileFor(String dimensionId) {
        Path at = root;
        if (at == null) {
            return null;
        }
        return at.resolve(folderFor(at, dimensionId).name() + LandmarkStore.EXTENSION);
    }

    public LandmarkStore landmarks(String dimensionId) {
        return landmarks.computeIfAbsent(dimensionId, id -> landmarksAt(landmarkFileFor(id)));
    }

    // Null while the dimension's markers are not loaded; it never loads them.
    public LandmarkStore loadedLandmarks(String dimensionId) {
        return landmarks.get(dimensionId);
    }

    // listener may be null: nobody is told. It runs on the client thread, after the markers were saved or queued.
    public void afterLandmarksSaved(Markers.Saved listener) {
        landmarksSavedListener = listener;
    }

    private void tellLandmarksSaved(String dimensionId, LandmarkStore store) {
        Markers.Saved listener = landmarksSavedListener;
        if (listener != null) {
            try {
                listener.saved(landmarkSource(dimensionId), dimensionIdOf(dimensionId), store);
            } catch (RuntimeException fromListener) {
                String why = fromListener.getMessage();
                note.accept("geosurvey: a marker listener failed for " + dimensionId + " ("
                        + fromListener.getClass().getSimpleName() + (why == null ? "" : ": " + why)
                        + ").");
            }
        }
    }

    // The dimension's folder, then its stored key.
    private String landmarkSource(String dimensionId) {
        Path at = root;
        return at == null ? "" : regionDirFor(at, dimensionId).toString() + "/" + asStored(dimensionId);
    }

    private LandmarkStore landmarksAt(Path file) {
        LandmarkWrite kept = keptLandmarkSave(file);
        LandmarkStore loaded;
        if (kept != null) {
            loaded = kept.store();
        } else if (file == null) {
            loaded = new LandmarkStore();
        } else {
            loaded = LandmarkStore.load(file);
        }
        return loaded;
    }

    private LandmarkWrite keptLandmarkSave(Path file) {
        LandmarkWrite kept = refusedLandmarkWrites.get(file);
        if (kept == null) {
            kept = leftBehindLandmarkWrites.get(file);
        }
        return kept;
    }

    public void saveLandmarksQuietly(String dimensionId) {
        try {
            saveLandmarks(dimensionId);
        } catch (AtomicFileReplace.Refused unwaited) {
            saveLandmarksLater(dimensionId);
        } catch (IOException refused) {
            String why = refused.getMessage();
            note.accept("geosurvey could not save the markers for " + dimensionId
                    + " (" + refused.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why)
                    + ")");
        }
    }

    public void saveLandmarks(String dimensionId) throws IOException {
        LandmarkStore store = landmarks.get(dimensionId);
        Path file = landmarkFileFor(dimensionId);
        if (store != null && file != null) {
            boolean noWait = noWaitScope();
            try {
                saveLandmarks(store, file, stampLandmarkWrite(file));
            } finally {
                endNoWaitScope(noWait);
            }
            tellLandmarksSaved(dimensionId, store);
        }
    }

    private void saveLandmarksLater(String dimensionId) {
        try {
            saveLandmarksInBackground(dimensionId);
        } catch (IOException refused) {
            String why = refused.getMessage();
            note.accept("geosurvey could not save the markers for " + dimensionId
                    + " (" + refused.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why)
                    + ")");
        }
    }

    private void saveLandmarks(LandmarkStore store, Path file, long sequence) throws IOException {
        LandmarkTicket ticket = new LandmarkTicket(file, sequence);
        if (ticket.stillWanted()) {
            LANDMARK_SAVES.incrementAndGet();
            long revision = store.revision();
            Path aside = store.writeAside(file, false);
            if (aside == null) {
                AtomicFileReplace.settle(file, ticket);
            } else {
                store.moveInto(aside, file, revision, ticket);
            }
        }
    }

    private final class LandmarkTicket implements AtomicFileReplace.Turn {

        private final Path file;

        private final long sequence;

        private LandmarkTicket(Path file, long sequence) {
            this.file = file;
            this.sequence = sequence;
        }

        @Override
        public boolean stillWanted() {
            Long lastWritten = landmarkLastWritten.get(file);
            return lastWritten == null || sequence >= lastWritten.longValue();
        }

        @Override
        public void landed() {
            landmarkLastWritten.put(file, Long.valueOf(sequence));
        }
    }

    private LandmarkStore snapshotLandmarks(LandmarkStore store) {
        landmarkSnapshots++;
        LandmarkStore snapshot = new LandmarkStore();
        for (Landmark landmark : store.all()) {
            Landmark copy = new Landmark(landmark.name(), landmark.x(), landmark.z(), landmark.colour(),
                    landmark.affiliation(), landmark.icon());
            copy.setShared(landmark.shared());
            snapshot.add(copy);
        }
        return snapshot;
    }

    private boolean pruneLandmarkLoad(String dimensionId) {
        LandmarkLoad open = landmarkLoadsByDimension.get(dimensionId);
        boolean stillOpen;
        if (open == null) {
            stillOpen = false;
        } else if (droppedLandmarkLoad(open)) {
            landmarkLoadEnded(dimensionId, open, open.receipt().isCrashed());
            stillOpen = false;
        } else {
            stillOpen = open.rootGeneration() == rootGeneration;
        }
        return stillOpen;
    }

    public void loadLandmarksInBackground(WorkPool pool, String owner, String dimensionId) {
        if (landmarks.containsKey(dimensionId)) {
            return;
        }
        if (pruneLandmarkLoad(dimensionId)) {
            return;
        }
        LandmarkLoadFailure failed = landmarkLoadFailures.get(dimensionId);
        if (failed != null && (failed.attempts() >= MAX_LANDMARK_LOAD_ATTEMPTS
                || System.nanoTime() - failed.retryAt() < 0L)) {
            return;
        }
        sweepDroppedLandmarkLoads();
        Path file = landmarkFileFor(dimensionId);
        LandmarkWrite kept = keptLandmarkSave(file);
        if (kept != null) {
            landmarkLoadFailures.remove(dimensionId);
            landmarks.put(dimensionId, kept.store());
        } else {
            submitLandmarkLoad(pool, owner, dimensionId, file, failed == null);
        }
    }

    private void submitLandmarkLoad(WorkPool pool, String owner, String dimensionId, Path file,
                                    boolean firstAttempt) {
        LandmarkLoad load = takeLandmarkLoad();
        load.aim(dimensionId, rootGeneration, file, firstAttempt);
        boolean queued = pool.submit(owner, load.compute, load.apply, load.receipt(),
                load.drop,
                load.discard);
        if (queued) {
            landmarkLoads.add(load);
            landmarkLoadsByDimension.put(dimensionId, load);
        } else {
            load.letGo();
        }
    }

    public void saveLandmarksInBackground(String dimensionId) throws IOException {
        boolean noWait = noWaitScope();
        try {
            takeBackDroppedLandmarkWrites();
        } finally {
            endNoWaitScope(noWait);
        }
        saveLandmarksSoon(dimensionId);
    }

    private void saveLandmarksSoon(String dimensionId) throws IOException {
        LandmarkStore store = landmarks.get(dimensionId);
        Path file = landmarkFileFor(dimensionId);
        if (store == null || file == null) {
            return;
        }
        String unreadable = store.unreadable();
        if (!unreadable.isEmpty()) {
            note.accept("geosurvey: could not save the markers for " + dimensionId + ". "
                    + file.getFileName() + " could not be read (" + unreadable + "); nothing is"
                    + " written.");
            return;
        }
        Writes shared = sharedWrites;
        if (shared == null) {
            boolean noWait = noWaitScope();
            try {
                saveLandmarks(store, file, stampLandmarkWrite(file));
            } finally {
                endNoWaitScope(noWait);
            }
        } else {
            queueLandmarkSave(shared, dimensionId, store, file);
        }
        tellLandmarksSaved(dimensionId, store);
    }

    private void queueLandmarkSave(Writes shared, String dimensionId, LandmarkStore store,
                                   Path file) {
        boolean coalesced = false;
        for (int at = 0; at < landmarkWrites.size() && !coalesced; at++) {
            LandmarkWrite open = landmarkWrites.get(at);
            if (file.equals(open.file()) && !open.landed().get()
                    && !open.droppedUnrun().getAsBoolean()) {
                landmarksWantedAgain.put(file, dimensionId);
                coalesced = true;
            }
        }
        if (!coalesced) {
            landmarksWantedAgain.remove(file);
            LandmarkStore snapshot = snapshotLandmarks(store);
            long sequence = stampLandmarkWrite(file);
            java.util.concurrent.atomic.AtomicBoolean landed = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean settled =
                    new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.BooleanSupplier droppedUnrun = writeInBackground(shared, () -> {
                try {
                    saveLandmarks(snapshot, file, sequence);
                } catch (IOException | RuntimeException failed) {
                    String why = failed.getMessage();
                    note.accept("geosurvey could not save the markers for " + dimensionId
                            + " (" + failed.getClass().getSimpleName()
                            + (why == null ? "" : ": " + why)
                            + "). A restart may undo the most recent change.");
                } finally {
                    landed.set(true);
                }
            }, settled);
            if (droppedUnrun == null) {
                refusedLandmarkWrites.put(file, new LandmarkWrite(dimensionId, snapshot, file,
                        sequence, WILL_RUN, landed, settled));
            } else {
                refusedLandmarkWrites.remove(file);
                if (droppedUnrun != WILL_RUN) {
                    landmarkWrites.add(new LandmarkWrite(dimensionId, snapshot, file, sequence,
                            droppedUnrun, landed, settled));
                }
            }
        }
    }

    private void takeBackDroppedLandmarkWrites() {
        int kept = 0;
        for (int at = 0; at < landmarkWrites.size(); at++) {
            LandmarkWrite write = landmarkWrites.get(at);
            if (write.landed().get()) {
                continue;
            }
            if (!write.droppedUnrun().getAsBoolean()) {
                landmarkWrites.set(kept++, write);
            } else {
                settle(write.settled());
                try {
                    saveLandmarks(write.store(), write.file(), write.sequence());
                    note.accept("geosurvey: the work pool dropped the write of markers for " + write.dimensionId()
                            + "."
                            + " Written before the world closed.");
                } catch (IOException | RuntimeException failed) {
                    String why = failed.getMessage();
                    note.accept("geosurvey: the work pool dropped the write of markers for " + write.dimensionId()
                            + "."
                            + " Not written ("
                            + failed.getClass().getSimpleName()
                            + (why == null ? "" : ": " + why)
                            + "); a restart may undo the most recent change.");
                }
            }
        }
        landmarkWrites.subList(kept, landmarkWrites.size()).clear();
    }

    public void markDirty(String dimensionId) {
        dirty.put(dimensionId, Boolean.TRUE);
    }

    public void markClean(String dimensionId) {
        dirty.put(dimensionId, Boolean.FALSE);
    }

    public boolean isDirty(String dimensionId) {
        return dirty.getOrDefault(dimensionId, Boolean.FALSE);
    }

    private boolean anyDirty() {
        return anyDirtyFlag() || anyStoreWithDirtyRegions();
    }

    private boolean anyDirtyFlag() {
        boolean found = false;
        java.util.Iterator<Boolean> flags = dirty.values().iterator();
        while (!found && flags.hasNext()) {
            found = flags.next().booleanValue();
        }
        return found;
    }

    private boolean anyStoreWithDirtyRegions() {
        boolean found = false;
        java.util.Iterator<MapStore> held = stores.values().iterator();
        while (!found && held.hasNext()) {
            found = held.next().hasDirtyRegions();
        }
        return found;
    }

    public List<String> loadedDimensions() {
        return new ArrayList<>(stores.keySet());
    }

    public MapStore loadedStore(String dimensionId) {
        return handedOut(dimensionId);
    }

    public void forEachLoadedStore(java.util.function.BiConsumer<String, MapStore> action) {
        for (Map.Entry<String, MapStore> entry : stores.entrySet()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    public void endSaveCycles() {
        for (MapStore store : stores.values()) {
            store.endSaveCycle();
        }
    }

    // Whether store is the one handed out for the dimension.
    public boolean holds(String dimensionId, MapStore store) {
        boolean held;
        if (store == null) {
            held = false;
        } else {
            held = handedOut(dimensionId) == store;
        }
        return held;
    }

    public void countAsUse(String dimensionId, MapStore store) {
        if (store != null) {
            stores.get(dimensionId);
        }
    }

    private MapStore handedOut(String dimensionId) {
        return seek.in(stores, dimensionId);
    }

    private static final class Seek implements java.util.function.BiConsumer<String, MapStore> {

        private String sought;

        private MapStore found;

        private MapStore in(Map<String, MapStore> held, String dimensionId) {
            sought = dimensionId;
            found = null;
            held.forEach(this);
            MapStore answer = found;
            sought = null;
            found = null;
            return answer;
        }

        @Override
        public void accept(String dimensionId, MapStore store) {
            if (java.util.Objects.equals(dimensionId, sought)) {
                found = store;
            }
        }
    }

    private final Seek seek = new Seek();

    public static final class FileReplace {

        public static final Turn ALWAYS = () -> true;

        public interface Turn extends AtomicFileReplace.Turn {
        }

        public static final class Moved {

            public static final AtomicFileReplace.Moved WITHOUT_ATOMIC_MOVE =
                    AtomicFileReplace.Moved.WITHOUT_ATOMIC_MOVE;

            private Moved() {
            }
        }

        public static final class Refused extends AtomicFileReplace.Refused {

            private static final long serialVersionUID = 1L;

            private Refused(AtomicFileReplace.Refused refused) {
                super(refused.getFile(), refused.getOtherFile(), refused.getReason());
                initCause(refused.getCause());
                setStackTrace(refused.getStackTrace());
                for (Throwable also : refused.getSuppressed()) {
                    addSuppressed(also);
                }
            }
        }

        private FileReplace() {
        }

        public static AtomicFileReplace.Moved replace(Path temp, Path file, boolean atomic, Turn turn)
                throws IOException {
            AtomicFileReplace.Moved moved;
            try {
                moved = AtomicFileReplace.replace(temp, file, atomic, turn);
            } catch (AtomicFileReplace.Refused refused) {
                throw new Refused(refused);
            }
            return moved;
        }

    }

    // Merges a region snapshot into its file; the better copy of each chunk wins.
    // The file's lock is held throughout.
    public static void writeRegionFile(MapStore prepared, Path file,
                                       java.util.function.IntFunction<LandCover> legacy)
            throws IOException {
        writeRegionFile(prepared, file, legacy, null);
    }

    public static void writeRegionFile(MapStore prepared, Path file,
                                       java.util.function.IntFunction<LandCover> legacy,
                                       java.util.function.Consumer<String> damage)
            throws IOException {
        AtomicFileReplace.FileLock lock = AtomicFileReplace.take(file);
        try {
            writeRegionFile(prepared, file, legacy, damage, lock);
        } finally {
            AtomicFileReplace.give(lock);
        }
    }

    private static void writeRegionFile(MapStore prepared, Path file,
                                        java.util.function.IntFunction<LandCover> legacy,
                                        java.util.function.Consumer<String> damage,
                                        AtomicFileReplace.FileLock lock)
            throws IOException {
        synchronized (lock) {
            int had = prepared.size();
            java.util.Set<Long> original = new java.util.HashSet<>(Math.max(KEY_SET_FLOOR, had * SLOTS_PER_KEY));
            for (ChunkSample sample : prepared.all()) {
                original.add(MapStore.key(sample.chunkX, sample.chunkZ));
            }
            java.util.Set<Long> covered = new java.util.HashSet<>(Math.max(KEY_SET_FLOOR, had * SLOTS_PER_KEY));
            int[] preparedOutranked = {0};
            PreparedRegionGate gate = new PreparedRegionGate(prepared, covered, preparedOutranked);
            MapCodec.stream(file, legacy, gate, gate::sink, MAX_REGION_CHUNKS, damage);
            if (preparedOutranked[0] == 0 && covered.containsAll(original)) {
                REDUNDANT_REGION_WRITES_SKIPPED.incrementAndGet();
            } else {
                REGION_FILE_WRITES.incrementAndGet();
                writeRegionStore(prepared, file);
            }
        }
    }

    private static final class PreparedRegionGate implements MapCodec.ColumnGate {

        private final MapStore prepared;

        private final java.util.Set<Long> covered;

        private final int[] outranked;

        private ChunkSample fresh;

        private long diskCapturedAt;

        private int diskCaptureVersion;

        private PreparedRegionGate(MapStore prepared, java.util.Set<Long> covered,
                                  int[] outranked) {
            this.prepared = prepared;
            this.covered = covered;
            this.outranked = outranked;
        }

        @Override
        public boolean keeps(int chunkX, int chunkZ, long capturedAt, int captureVersion) {
            fresh = prepared.peek(chunkX, chunkZ);
            if (fresh == null) {
                return true;
            }
            covered.add(MapStore.key(chunkX, chunkZ));
            boolean diskWinsByAge = outranks(capturedAt, captureVersion, fresh);
            if (!diskWinsByAge) {
                outranked[0]++;
            }
            diskCapturedAt = capturedAt;
            diskCaptureVersion = captureVersion;
            return mayOutrank(capturedAt, captureVersion, fresh);
        }

        @Override
        public boolean keepsColumns(byte[] columns, int version,
                                    java.util.function.IntFunction<LandCover> legacy) {
            return fresh == null || mayOutrank(diskCapturedAt, diskCaptureVersion, fresh, columns,
                    version, legacy);
        }

        private void sink(ChunkSample sample) {
            ChunkSample held = prepared.peekValue(sample.chunkX, sample.chunkZ);
            if (held == null) {
                prepared.putClean(sample);
                return;
            }
            boolean diskWins = outranks(sample, held);
            if (diskWins) {
                prepared.putClean(sample);
            }
            outranked[0] -= groundTurn(sample, held, diskWins);
        }
    }

    static void writeRegionFile(ChunkSample[] prepared, Path file,
                                java.util.function.IntFunction<LandCover> legacy,
                                java.util.function.Consumer<String> damage)
            throws IOException {
        AtomicFileReplace.FileLock lock = AtomicFileReplace.take(file);
        try {
            writeRegionFile(prepared, file, legacy, damage, lock);
        } finally {
            AtomicFileReplace.give(lock);
        }
    }

    private static void writeRegionFile(ChunkSample[] prepared, Path file,
                                        java.util.function.IntFunction<LandCover> legacy,
                                        java.util.function.Consumer<String> damage,
                                        AtomicFileReplace.FileLock lock)
            throws IOException {
        synchronized (lock) {
            boolean redundant;
            if (MapCodec.present(file)) {
                java.util.BitSet uncovered =
                        new java.util.BitSet(MapRegion.CHUNKS * MapRegion.CHUNKS);
                for (int slot = 0; slot < prepared.length; slot++) {
                    if (prepared[slot] != null) {
                        uncovered.set(slot);
                    }
                }
                java.util.BitSet matched =
                        new java.util.BitSet(MapRegion.CHUNKS * MapRegion.CHUNKS);
                int[] preparedOutranked = {0};
                RegionSlotGate gate = new RegionSlotGate(prepared, matched, preparedOutranked);
                MapCodec.stream(file, legacy, gate, gate::sink, MAX_REGION_CHUNKS, damage);
                uncovered.andNot(matched);
                redundant = preparedOutranked[0] == 0 && uncovered.isEmpty();
            } else {
                redundant = false;
            }
            if (redundant) {
                REDUNDANT_REGION_WRITES_SKIPPED.incrementAndGet();
            } else {
                REGION_FILE_WRITES.incrementAndGet();
                writeRegionArray(prepared, file);
            }
        }
    }

    private static final class RegionSlotGate implements MapCodec.ColumnGate {

        private final ChunkSample[] prepared;

        private final java.util.BitSet matched;

        private final int[] outranked;

        private ChunkSample fresh;

        private long diskCapturedAt;

        private int diskCaptureVersion;

        private RegionSlotGate(ChunkSample[] prepared, java.util.BitSet matched,
                               int[] outranked) {
            this.prepared = prepared;
            this.matched = matched;
            this.outranked = outranked;
        }

        @Override
        public boolean keeps(int chunkX, int chunkZ, long capturedAt, int captureVersion) {
            int slot = MapStore.regionSlot(chunkX, chunkZ);
            fresh = prepared[slot];
            if (fresh == null) {
                return true;
            }
            matched.set(slot);
            boolean diskWinsByAge = outranks(capturedAt, captureVersion, fresh);
            if (!diskWinsByAge) {
                outranked[0]++;
            }
            diskCapturedAt = capturedAt;
            diskCaptureVersion = captureVersion;
            return mayOutrank(capturedAt, captureVersion, fresh);
        }

        @Override
        public boolean keepsColumns(byte[] columns, int version,
                                    java.util.function.IntFunction<LandCover> legacy) {
            return fresh == null || mayOutrank(diskCapturedAt, diskCaptureVersion, fresh, columns,
                    version, legacy);
        }

        private void sink(ChunkSample sample) {
            int slot = MapStore.regionSlot(sample.chunkX, sample.chunkZ);
            ChunkSample held = prepared[slot];
            if (held == null) {
                prepared[slot] = sample;
                return;
            }
            boolean diskWins = outranks(sample, held);
            if (diskWins) {
                prepared[slot] = sample;
            }
            outranked[0] -= groundTurn(sample, held, diskWins);
        }
    }

    private static void writeRegionArray(ChunkSample[] region, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int occupied = occupied(region);
        Path temp = path.resolveSibling(
                path.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (java.io.OutputStream out = Files.newOutputStream(temp)) {
            try (MapCodec.RegionWriter dout = MapCodec.openForWriting(out, occupied)) {
                for (ChunkSample sample : region) {
                    if (sample != null) {
                        dout.writeRecord(sample);
                    }
                }
            }
        } catch (IOException failed) {
            throw AtomicFileReplace.dropped(temp, failed);
        } catch (RuntimeException failed) {
            throw AtomicFileReplace.dropped(temp, failed);
        }
        AtomicFileReplace.replaceHeld(temp, path);
    }

    private static void writeRegionStore(MapStore region, Path path) throws IOException {
        Path temp = path.resolveSibling(
                path.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
        try (java.io.OutputStream out = openRegionTemp(temp, path.getParent())) {
            MapCodec.write(region, out);
        } catch (IOException failed) {
            throw AtomicFileReplace.dropped(temp, failed);
        } catch (RuntimeException failed) {
            throw AtomicFileReplace.dropped(temp, failed);
        }
        AtomicFileReplace.replaceHeld(temp, path);
    }

    private static java.io.OutputStream openRegionTemp(Path temp, Path parent)
            throws IOException {
        java.io.OutputStream out;
        try {
            out = Files.newOutputStream(temp);
        } catch (java.nio.file.NoSuchFileException missingParent) {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            out = Files.newOutputStream(temp);
        }
        return out;
    }

    private static int occupied(ChunkSample[] region) {
        int counted = 0;
        for (ChunkSample sample : region) {
            if (sample != null) {
                counted++;
            }
        }
        return counted;
    }

    // Returns how many incoming chunks the file's copy outranked.
    public static int mergeRegionFile(MapStore incoming, Path file,
                                      java.util.function.IntFunction<LandCover> legacy)
            throws IOException {
        return mergeRegionFile(incoming, file, legacy, null);
    }

    public static int mergeRegionFile(MapStore incoming, Path file,
                                      java.util.function.IntFunction<LandCover> legacy,
                                      java.util.function.Consumer<String> damage)
            throws IOException {
        AtomicFileReplace.FileLock lock = AtomicFileReplace.take(file);
        int kept;
        try {
            kept = mergeRegionFile(incoming, file, legacy, damage, lock);
        } finally {
            AtomicFileReplace.give(lock);
        }
        return kept;
    }

    private static int mergeRegionFile(MapStore incoming, Path file,
                                       java.util.function.IntFunction<LandCover> legacy,
                                       java.util.function.Consumer<String> damage,
                                       AtomicFileReplace.FileLock lock)
            throws IOException {
        int[] kept = {0};
        synchronized (lock) {
            int had = incoming.size();
            java.util.Set<Long> original = new java.util.HashSet<>(Math.max(KEY_SET_FLOOR, had * SLOTS_PER_KEY));
            for (ChunkSample sample : incoming.all()) {
                original.add(MapStore.key(sample.chunkX, sample.chunkZ));
            }
            java.util.Set<Long> matched = new java.util.HashSet<>(Math.max(KEY_SET_FLOOR, had * SLOTS_PER_KEY));
            int[] incomingOutranked = {0};
            MergedRegionGate gate = new MergedRegionGate(incoming, matched, kept, incomingOutranked);
            MapCodec.stream(file, legacy, gate, gate::sink, MAX_REGION_CHUNKS, damage);
            if (incomingOutranked[0] == 0 && matched.containsAll(original)) {
                REDUNDANT_REGION_WRITES_SKIPPED.incrementAndGet();
            } else {
                REGION_FILE_WRITES.incrementAndGet();
                writeRegionStore(incoming, file);
            }
        }
        return kept[0];
    }

    private static final class MergedRegionGate implements MapCodec.ColumnGate {

        private final MapStore incoming;

        private final java.util.Set<Long> matched;

        private final int[] kept;

        private final int[] outranked;

        private ChunkSample fresh;

        private long diskCapturedAt;

        private int diskCaptureVersion;

        private MergedRegionGate(MapStore incoming, java.util.Set<Long> matched, int[] kept,
                                 int[] outranked) {
            this.incoming = incoming;
            this.matched = matched;
            this.kept = kept;
            this.outranked = outranked;
        }

        @Override
        public boolean keeps(int chunkX, int chunkZ, long capturedAt, int captureVersion) {
            fresh = incoming.peek(chunkX, chunkZ);
            if (fresh == null) {
                return true;
            }
            matched.add(MapStore.key(chunkX, chunkZ));
            boolean diskWinsByAge = outranks(capturedAt, captureVersion, fresh);
            if (diskWinsByAge) {
                kept[0]++;
            } else {
                outranked[0]++;
            }
            diskCapturedAt = capturedAt;
            diskCaptureVersion = captureVersion;
            return mayOutrank(capturedAt, captureVersion, fresh);
        }

        @Override
        public boolean keepsColumns(byte[] columns, int version,
                                    java.util.function.IntFunction<LandCover> legacy) {
            return fresh == null || mayOutrank(diskCapturedAt, diskCaptureVersion, fresh, columns,
                    version, legacy);
        }

        private void sink(ChunkSample sample) {
            ChunkSample held = incoming.peekValue(sample.chunkX, sample.chunkZ);
            if (held == null) {
                incoming.putClean(sample);
                return;
            }
            boolean diskWins = outranks(sample, held);
            if (diskWins) {
                incoming.putClean(sample);
            }
            int turn = groundTurn(sample, held, diskWins);
            kept[0] += turn;
            outranked[0] -= turn;
        }
    }

    // Whether the sample on disk outranks the incoming one: ground first, then age.
    // Undated (pre-wall-clock) samples always lose to dated ones; two undated samples compare by raw tick.
    public static boolean outranks(ChunkSample existing, ChunkSample fresh) {
        boolean ground = existing.holdsGround();
        if (ground != fresh.holdsGround()) {
            return ground;
        }
        return outranks(existing.capturedAt(), existing.captureVersion(), fresh);
    }

    private static boolean outranks(long existingCapturedAt, int existingCaptureVersion,
                                    ChunkSample fresh) {
        boolean dated = ChunkSample.isWallClock(existingCaptureVersion);
        if (dated != fresh.hasWallClockCapture()) {
            return dated;
        }
        if (dated && existingCapturedAt > UtcClock.collector().nowMillis()) {
            return false;
        }
        return existingCapturedAt > fresh.capturedAt();
    }

    private static boolean mayOutrank(long existingCapturedAt, int existingCaptureVersion,
                                      ChunkSample fresh) {
        return outranks(existingCapturedAt, existingCaptureVersion, fresh) || !fresh.holdsGround();
    }

    private static boolean mayOutrank(long existingCapturedAt, int existingCaptureVersion,
                                      ChunkSample fresh, byte[] columns, int columnsVersion,
                                      java.util.function.IntFunction<LandCover> legacy) {
        if (outranks(existingCapturedAt, existingCaptureVersion, fresh)) {
            return true;
        }
        return !fresh.holdsGround()
                && MapCodec.columnsHoldGround(columns, columnsVersion, legacy);
    }

    // How ground turned the counted age verdict: 1 to existing, -1 to fresh, else 0.
    private static int groundTurn(ChunkSample existing, ChunkSample fresh, boolean existingWins) {
        int turn;
        if (existingWins == outranks(existing.capturedAt(), existing.captureVersion(), fresh)) {
            turn = 0;
        } else if (existingWins) {
            turn = 1;
        } else {
            turn = -1;
        }
        return turn;
    }

    public static MapStore snapshotRegion(MapStore store, int regionX, int regionZ) {
        MapStore out = new MapStore(REGION_STORE_CAPACITY);
        int firstX = MapRegion.firstChunk(regionX);
        int firstZ = MapRegion.firstChunk(regionZ);
        for (int cx = firstX; cx < firstX + MapRegion.CHUNKS; cx++) {
            for (int cz = firstZ; cz < firstZ + MapRegion.CHUNKS; cz++) {
                ChunkSample held = store.peek(cx, cz);
                if (held != null) {
                    out.putClean(held);
                }
            }
        }
        return out;
    }

    public MapStore regionGroundFor(String dimensionId, MapStore store,
                                    int regionX, int regionZ) {
        Path file = regionFileFor(dimensionId, regionX, regionZ);
        Unlanded kept = keptAside(file);
        if (kept == null) {
            askedGround.remove(store);
        } else {
            askedGround.put(store, file);
        }
        return foldKeptAside(snapshotRegion(store, regionX, regionZ), kept);
    }

    private MapStore groundFor(MapStore store, int regionX, int regionZ, Path file) {
        return foldKeptAside(snapshotRegion(store, regionX, regionZ), keptAside(file));
    }

    private static MapStore foldKeptAside(MapStore prepared, Unlanded kept) {
        if (kept != null) {
            fold(kept.ground(), prepared);
        }
        return prepared;
    }

    private static final byte UNKNOWN_COVER_CODE = (byte) LandCover.UNKNOWN.code();

    // A stored copy of the chunk; heights and cover are scratch columns of ChunkSample.COLUMNS.
    public static ChunkSample copyOf(GroundChunk chunk, short[] heights, byte[] cover) {
        java.util.Arrays.fill(heights, ChunkSample.NO_HEIGHT);
        java.util.Arrays.fill(cover, UNKNOWN_COVER_CODE);
        chunk.copyHeights(heights);
        chunk.copyCoverCodes(cover);
        ChunkSample copy = ChunkSample.forFullWriter(chunk.chunkX(), chunk.chunkZ());
        copy.setColumns(heights, cover);
        copy.setCapturedAt(chunk.capturedAt());
        copy.setCaptureVersion(chunk.captureVersion());
        return copy;
    }

    // One region of an import: the ground the collector held for it, and its file.
    public static final class RegionImport {

        private final String dimensionId;

        private final Path root;

        private final Path file;

        // The import writer's thread's until the import is written; the client thread's after.
        private final MapStore ground;

        private final Unlanded kept;

        private final java.util.function.IntFunction<LandCover> legacy;

        private final java.util.function.Consumer<String> damage;

        // Null until the import is written.
        private volatile ChunkSample[] imported;

        private RegionImport(String dimensionId, Path root, Path file, MapStore ground, Unlanded kept,
                             java.util.function.IntFunction<LandCover> legacy,
                             java.util.function.Consumer<String> damage) {
            this.dimensionId = dimensionId;
            this.root = root;
            this.file = file;
            this.ground = ground;
            this.kept = kept;
            this.legacy = legacy;
            this.damage = damage;
        }
    }

    // Client thread; null outside a world, or when the dimension's folder cannot be claimed.
    public RegionImport prepareImport(String dimensionId, int regionX, int regionZ) {
        Path at = root;
        Path file = at == null ? null : regionFileFor(dimensionId, regionX, regionZ);
        if (file == null) {
            return null;
        }
        MapStore resident = handedOut(dimensionId);
        MapStore ground = resident == null ? newRegionStore() : snapshotRegion(resident, regionX, regionZ);
        Unlanded kept = keptAside(file);
        return new RegionImport(dimensionId, at, file, foldKeptAside(ground, kept), kept,
                legacyColourToCover, damage);
    }

    // Any thread but the client thread; returns how many imported chunks the file holds now.
    public static int writeImport(RegionImport region, ChunkSample[] imported) throws IOException {
        MapStore ground = region.ground;
        for (ChunkSample sample : imported) {
            if (sample == null) {
                continue;
            }
            ChunkSample held = ground.peekValue(sample.chunkX, sample.chunkZ);
            if (held == null || importTakes(held, sample)) {
                ground.putClean(sample);
            }
        }
        writeRegionFile(ground, region.file, region.legacy, region.damage);
        region.imported = imported;
        int written = 0;
        for (ChunkSample sample : imported) {
            if (sample != null && ground.peekValue(sample.chunkX, sample.chunkZ) == sample) {
                written++;
            }
        }
        return written;
    }

    // Client thread; puts the written import over resident ground it outranks or ties.
    // False when its world was left.
    public boolean landImport(RegionImport region) {
        if (region.root != root) {
            return false;
        }
        MapStore resident = handedOut(region.dimensionId);
        ChunkSample[] imported = region.imported;
        if (resident != null && imported != null) {
            for (ChunkSample sample : imported) {
                if (sample != null && region.ground.peekValue(sample.chunkX, sample.chunkZ) == sample) {
                    ChunkSample held = resident.peek(sample.chunkX, sample.chunkZ);
                    if (held != null && importTakes(held, sample)) {
                        resident.putClean(sample);
                    }
                }
            }
        }
        if (region.kept != null && unlanded.get(region.file) == region.kept) {
            unlanded.remove(region.file);
        }
        return true;
    }

    // An import takes a held chunk it outranks, or one with the same ground, dated form and time.
    private static boolean importTakes(ChunkSample held, ChunkSample sample) {
        return outranks(sample, held) || ties(held, sample);
    }

    private static boolean ties(ChunkSample held, ChunkSample sample) {
        return (held.holdsGround() == sample.holdsGround())
                && (held.hasWallClockCapture() == sample.hasWallClockCapture())
                && (held.capturedAt() == sample.capturedAt());
    }

    public void rememberRegionWrite(MapStore store,
            java.util.concurrent.atomic.AtomicBoolean landed,
            java.util.function.BooleanSupplier droppedUnrun) {
        rememberRegionWrite(null, store, -1, -1, null, landed, droppedUnrun);
    }

    public void rememberRegionWrite(String dimensionId, MapStore store,
            int regionX, int regionZ, ChunkSample[] held,
            java.util.concurrent.atomic.AtomicBoolean landed,
            java.util.function.BooleanSupplier droppedUnrun) {
        Path asked = askedGround.remove(store);
        if (asked != null) {
            unlanded.remove(asked);
        }
        remember(new Outstanding(dimensionId, store, regionX, regionZ, held,
                landed, droppedUnrun));
    }

    public void fileFailedRegionWrite(String dimensionId, MapStore store,
                                      int regionX, int regionZ, Path file,
                                      MapStore ground) {
        failedWrites.add(new Unwritten(dimensionId, store, regionX, regionZ, ground, file));
    }

    public void fileDroppedRegionWrite(String dimensionId, MapStore store,
                                       int regionX, int regionZ, Path file,
                                       MapStore ground) {
        failedWrites.add(new Unwritten(dimensionId, store, regionX, regionZ, ground, file,
                true));
    }

    public void save(String dimensionId) throws IOException {
        takeBackFailedWrites();
        MapStore store = handedOut(dimensionId);
        countAsUse(dimensionId, store);
        Path at = root;
        if (store != null && at != null) {
            long[] regions = store.drainDirtyRegions();
            int done = 0;
            boolean noWait = noWaitScope();
            try {
                if (claimFolder(at, dimensionId)) {
                    Path dir = regionDirFor(at, dimensionId);
                    while (done < regions.length) {
                        int rx = MapRegion.x(regions[done]);
                        int rz = MapRegion.z(regions[done]);
                        Path file = dir == null ? null : dir.resolve(MapRegion.fileName(rx, rz));
                        Unlanded kept = keptAside(file);
                        MapStore ground = groundFor(store, rx, rz, file);
                        try {
                            writeRegionFile(ground, file, legacyColourToCover, damage);
                            if (kept != null && unlanded.get(file) == kept) {
                                unlanded.remove(file);
                            }
                        } catch (AtomicFileReplace.Refused unwaited) {
                            failedWrites.add(new Unwritten(dimensionId, store, rx, rz, ground, file));
                        }
                        done++;
                    }
                    dirty.put(dimensionId, Boolean.FALSE);
                }
            } finally {
                endNoWaitScope(noWait);
                if (done < regions.length) {
                    for (int back = done; back < regions.length; back++) {
                        store.markRegionDirty(MapRegion.x(regions[back]),
                                MapRegion.z(regions[back]));
                    }
                    markDirty(dimensionId);
                }
            }
        }
    }

    public void saveDirty() throws IOException {
        SAVE_DIRTY_SWEEPS.incrementAndGet();
        IOException failed = null;
        for (String id : new ArrayList<>(stores.keySet())) {
            if (isDirty(id)) {
                try {
                    save(id);
                } catch (IOException unwritten) {
                    if (failed == null) {
                        failed = unwritten;
                    } else {
                        failed.addSuppressed(unwritten);
                    }
                }
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    private static final class SharedWrite {

        private final String dimensionId;
        private final MapStore store;
        private final int regionX;
        private final int regionZ;
        private final java.util.concurrent.atomic.AtomicBoolean taken =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean written =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean settled =
                new java.util.concurrent.atomic.AtomicBoolean();
        private java.util.function.BooleanSupplier droppedUnrun;
        private Path file;
        private MapStore prepared;
        private Unlanded folded;

        private SharedWrite(String dimensionId, MapStore store, int regionX, int regionZ) {
            this.dimensionId = dimensionId;
            this.store = store;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }
    }

    void saveDirtyShared(long until) throws IOException {
        Writes shared = sharedWrites;
        if (shared == null) {
            saveDirty();
            return;
        }
        if (root == null) {
            return;
        }
        java.util.function.IntFunction<LandCover> legacy = legacyColourToCover;
        List<SharedWrite> writes = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger returned =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean waiting =
                new java.util.concurrent.atomic.AtomicBoolean();
        Thread here = Thread.currentThread();
        SettleTracking tracking = new SettleTracking(running, returned, waiting, here);
        boolean noWait = noWaitScope();
        try {
            for (String id : new ArrayList<>(stores.keySet())) {
                MapStore store = isDirty(id) ? handedOut(id) : null;
                if (store == null) {
                    continue;
                }
                countAsUse(id, store);
                long[] regions = store.drainDirtyRegions();
                markClean(id);
                for (long region : regions) {
                    writes.add(new SharedWrite(id, store,
                            MapRegion.x(region), MapRegion.z(region)));
                }
            }
            OfferProgress offered = offerSharedWrites(shared, writes, until, legacy, tracking);
            awaitOfferedSharedWrites(writes, offered, until, tracking);
            writeOfferedWritesHere(writes, offered.front(), legacy);
        } finally {
            endNoWaitScope(noWait);
            waiting.set(false);
            for (SharedWrite write : writes) {
                if (write.written.get()) {
                    retireUnlanded(write);
                } else {
                    write.store.markRegionDirty(write.regionX, write.regionZ);
                    markDirty(write.dimensionId);
                }
            }
        }
    }

    private record SettleTracking(java.util.concurrent.atomic.AtomicInteger running,
            java.util.concurrent.atomic.AtomicInteger returned,
            java.util.concurrent.atomic.AtomicBoolean waiting,
            Thread here) {
    }

    private record OfferProgress(int front, int offered) {
    }

    private OfferProgress offerSharedWrites(Writes shared, List<SharedWrite> writes, long until,
            java.util.function.IntFunction<LandCover> legacy, SettleTracking tracking) {
        int front = 0;
        int back = writes.size() - 1;
        int offered = 0;
        boolean offering = true;
        while (front <= back) {
            while (offering && front <= back
                    && offered - tracking.returned().get() < MAX_QUEUED_REGION_WRITES) {
                SharedWrite write = prepare(writes.get(front));
                if (write.file == null) {
                    write.taken.set(true);
                    front++;
                } else {
                    MapStore prepared = write.prepared;
                    Path file = write.file;
                    java.util.concurrent.atomic.AtomicBoolean taken = write.taken;
                    java.util.concurrent.atomic.AtomicBoolean written = write.written;
                    tracking.running().incrementAndGet();
                    java.util.function.BooleanSupplier took = shared.off(() -> {
                        try {
                            if (taken.compareAndSet(false, true)) {
                                writeRegionFile(prepared, file, legacy, damage);
                                written.set(true);
                            }
                        } catch (IOException | RuntimeException unwritten) {
                        } finally {
                            settleSharedWrite(write, tracking.running(), tracking.returned(),
                                    tracking.waiting(), tracking.here());
                        }
                    });
                    if (took == null) {
                        tracking.running().decrementAndGet();
                        offering = false;
                    } else {
                        write.droppedUnrun = took;
                        offered++;
                        front++;
                    }
                }
            }
            if (front <= back) {
                if (System.nanoTime() >= until) {
                    break;
                }
                writeHere(writes.get(back--), legacy);
            }
        }
        return new OfferProgress(front, offered);
    }

    private void awaitOfferedSharedWrites(List<SharedWrite> writes, OfferProgress offered,
            long until, SettleTracking tracking) {
        tracking.waiting().set(true);
        while (offered.offered() - tracking.returned().get() > 0
                && (tracking.running().get() > 0 || tracking.returned().get() > 0)
                && !tracking.here().isInterrupted()) {
            for (int handed = 0; handed < offered.front(); handed++) {
                SharedWrite open = writes.get(handed);
                java.util.function.BooleanSupplier dropped = open.droppedUnrun;
                if (dropped == null || dropped == WILL_RUN || open.settled.get()
                        || !dropped.getAsBoolean()) {
                    continue;
                }
                settleSharedWrite(open, tracking.running(), tracking.returned(),
                        tracking.waiting(), tracking.here());
            }
            long left = until - System.nanoTime();
            if (left <= 0) {
                break;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(this, left);
        }
        tracking.waiting().set(false);
    }

    private void writeOfferedWritesHere(List<SharedWrite> writes, int front,
            java.util.function.IntFunction<LandCover> legacy) {
        for (int offeredAt = front - 1; offeredAt >= 0; offeredAt--) {
            writeHere(writes.get(offeredAt), legacy);
        }
    }


    private static void settleSharedWrite(SharedWrite write,
            java.util.concurrent.atomic.AtomicInteger running,
            java.util.concurrent.atomic.AtomicInteger returned,
            java.util.concurrent.atomic.AtomicBoolean waiting,
            Thread here) {
        if (!write.settled.compareAndSet(false, true)) {
            return;
        }
        returned.incrementAndGet();
        if (running.decrementAndGet() == 0 && waiting.get()) {
            java.util.concurrent.locks.LockSupport.unpark(here);
        }
    }

    private SharedWrite prepare(SharedWrite write) {
        if (write.prepared == null) {
            write.file = regionFileFor(write.dimensionId, write.regionX, write.regionZ);
            write.folded = keptAside(write.file);
            write.prepared = groundFor(write.store, write.regionX, write.regionZ, write.file);
        }
        return write;
    }

    private void retireUnlanded(SharedWrite write) {
        Unlanded folded = write.folded;
        if (folded != null && unlanded.get(write.file) == folded) {
            unlanded.remove(write.file);
        }
    }

    private void writeHere(SharedWrite write, java.util.function.IntFunction<LandCover> legacy) {
        if (!write.taken.compareAndSet(false, true)) {
            return;
        }
        prepare(write);
        if (write.file != null) {
            try {
                writeRegionFile(write.prepared, write.file, legacy, damage);
                write.written.set(true);
            } catch (IOException unwritten) {
            }
        }
    }

    private void flushQuietly(boolean sharing) {
        long started = System.nanoTime();
        long until = started + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        writeUnstarted(false, Long.MAX_VALUE);
        takeBackFailedWrites();
        takeBackDroppedLandmarkWrites();
        try {
            if (sharing) {
                saveDirtyShared(until);
            } else {
                saveDirty();
            }
        } catch (IOException ignored) {
        }
        awaitRegionWrites(sharing, until, started);
        stopRegionWrites();
        takeBackFailedWrites();
        try {
            if (anyDirty()) {
                saveDirty();
            }
        } catch (IOException unwritten) {
            StringBuilder still = new StringBuilder();
            for (Map.Entry<String, MapStore> entry : stores.entrySet()) {
                if (isDirty(entry.getKey()) || entry.getValue().hasDirtyRegions()) {
                    if (still.length() > 0) {
                        still.append(", ");
                    }
                    still.append(entry.getKey());
                }
            }
            note.accept("geosurvey: could not write the last save of " + still
                    + " on exit."
                    + " Unsaved ground is lost ("
                    + unwritten.getClass().getSimpleName() + ").");
        }
        writeUnlanded();

        for (String id : new ArrayList<>(overlays.keySet())) {
            saveAnnotationsQuietly(id);
        }
        saveRefusedOverlays();
        for (String id : new ArrayList<>(landmarks.keySet())) {
            saveLandmarksQuietly(id);
        }
        saveRefusedLandmarks(refusedLandmarkWrites);
        saveRefusedLandmarks(leftBehindLandmarkWrites);
    }

    private void saveRefusedLandmarks(java.util.Map<Path, LandmarkWrite> keeping) {
        for (LandmarkWrite refused : new ArrayList<>(keeping.values())) {
            try {
                saveLandmarks(refused.store(), refused.file(), refused.sequence());
                keeping.remove(refused.file(), refused);
            } catch (IOException | RuntimeException failed) {
                note.accept("geosurvey: could not save the markers for " + refused.dimensionId()
                        + "; the write pool refused them.");
                if (!(failed instanceof AtomicFileReplace.Refused)) {
                    keeping.remove(refused.file(), refused);
                }
            }
        }
    }

    public void flushQuietly() {
        boolean noWait = noWaitScope();
        try {
            flushQuietly(sharedWrites != null);
        } finally {
            endNoWaitScope(noWait);
        }
    }

    long residentChunks() {
        return residentChunks;
    }

    public int totalChunks() {
        int n = 0;
        for (MapStore s : stores.values()) {
            n += s.size();
        }
        return n;
    }
}
