package dev.openmap.client;

import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import dev.sandpaper.core.Priority;
import dev.sandpaper.core.Step;
import dev.sandpaper.core.Tick;
import dev.sandpaper.core.WorkPool;
import dev.openmap.LandNav;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.ChunkSample;
import dev.openmap.map.MapStore;
import dev.openmap.map.MapRegion;
import dev.openmap.map.MapStorage;
import dev.openmap.share.Batch;
import dev.openmap.share.UtcClock;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

public final class ChunkCapture {

    private static final int MIN_SCAN_RADIUS = 4;

    private static final int SCAN_INTERVAL_MILLIS = 500;

    private static final long OVERWORLD_DAY_TICKS = 24_000L;

    private static final int CHUNK_BLOCK_SHIFT = 4;

    private static final int MAX_SCAN_RADIUS = 64;

    private static final int MAX_RING_CELLS =
            (2 * MAX_SCAN_RADIUS + 1) * (2 * MAX_SCAN_RADIUS + 1);

    private final long[] scanOrder = new long[MAX_RING_CELLS];
    private int scanOrderRadius = -1;
    private int scanOrderCells;

    private static int ringOrder(int radius, long[] into) {
        return dev.openmap.map.Spiral.outwardInto(0, 0, radius, into);
    }

    private static int scanRadius(Minecraft client) {
        return Math.clamp(client.options.getEffectiveRenderDistance(),
                MIN_SCAN_RADIUS, MAX_SCAN_RADIUS);
    }

    // Matches ClientChunkCache's retention beyond view.
    private static final int CACHE_SLACK = 3;

    private static final int DEADLINE_CHECK_MASK = 63;

    private static final int MAX_NAMED_SERVERS = 64;

    private static final String DEFAULT_PORT = "25565";

    private static final SystemToast.SystemToastId APPROVED_PORTS_TOAST =
            new SystemToast.SystemToastId();

    private static final long SAVE_INTERVAL_MILLIS = 30_000;

    private static final long NEVER_RESURVEY = Long.MIN_VALUE;

    private static final long NOTHING_TO_RESURVEY = Long.MAX_VALUE;

    private static final long MILLIS_PER_SECOND = 1_000L;

    private volatile long resurveyBefore = NEVER_RESURVEY;

    private long lapOldestStamp = NOTHING_TO_RESURVEY;

    private long scanCompleteOldestStamp = NOTHING_TO_RESURVEY;

    private final MapStorage storage;

    private final ShareSender share;

    private final CollectorCartographerWorld world;

    private final CollectorCartographerGround ground;

    private final ShareSender.Here standingAt =
            new ShareSender.Here(null, null, null, 0, 0, 0, false, false);

    private final LongArrayFIFOQueue pending = new StableLongArrayFIFOQueue(MAX_RING_CELLS);
    private final LongOpenHashSet queued = new LongOpenHashSet(MAX_RING_CELLS);

    private static final int MAX_UNLOADED = (2 * MAX_SCAN_RADIUS + 1) * (2 * MAX_SCAN_RADIUS + 1);

    private static final int MAX_VOID_CHUNKS = (2 * MAX_SCAN_RADIUS + 1) * (2 * MAX_SCAN_RADIUS + 1);

    private static final class StableLongArrayFIFOQueue extends LongArrayFIFOQueue {

        StableLongArrayFIFOQueue(int capacity) {
            super(capacity);
        }

        @Override
        public long dequeueLong() {
            if (start == end) {
                throw new java.util.NoSuchElementException();
            }
            long value = array[start++];
            if (start == length) {
                start = 0;
            }
            return value;
        }
    }

    private final LongOpenHashSet unloaded = new LongOpenHashSet();

    private final LongOpenHashSet voidChunks = new LongOpenHashSet();

    private int unloadedCentreX;
    private int unloadedCentreZ;
    private int unloadedRadius = Integer.MIN_VALUE;

    private Handle captureHandle;

    private Handle handshakeHandle;
    private String currentDimension = "";
    private ClientLevel scannedLevel;
    private ResourceKey<Level> scannedDimensionKey;
    private String scannedDimensionText = "";

    private int scanCursor;
    private int scanCursorCentreX;
    private int scanCursorCentreZ;
    private int scanCursorRadius;
    private MapStore scanCursorStore;
    private long scanCursorRevisions;

    private boolean scanComplete;
    private int scanCompleteCentreX;
    private int scanCompleteCentreZ;
    private int scanCompleteRadius;
    private MapStore scanCompleteStore;
    private long scanCompleteRevisions;

    private boolean backlogRotationCurrent;
    private int backlogRotationCentreX;
    private int backlogRotationCentreZ;
    private int backlogRotationReach;
    long backlogRotationDequeues;

    private final LongOpenHashSet regionsRequestedThisPass = new LongOpenHashSet();
    long regionLoadCalls;

    private final WorldDirectories worldDirs = new WorldDirectories();
    private final ApprovedServers gate = new ApprovedServers();

    private long capturedChunks;
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private long savesStarted;
    private long savesFinished;

    private long savesFailed;

    private static volatile ChunkCapture live;

    private static final int MAX_TRACKED_SAVES = 256;

    private static final class Queued {

        private static final java.util.concurrent.atomic.AtomicReferenceFieldUpdater<Queued, MapStore>
                UNWRITTEN = java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater(
                        Queued.class, MapStore.class, "unwritten");

        final WorkPool.Receipt receipt;
        final String dimension;
        final MapStore store;
        final int regionX;
        final int regionZ;
        final Path file;
        volatile boolean started;
        volatile MapStore unwritten;

        Queued(WorkPool.Receipt receipt, String dimension, MapStore store,
                int regionX, int regionZ, Path file, MapStore unwritten) {
            this.receipt = receipt;
            this.dimension = dimension;
            this.store = store;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.file = file;
            this.unwritten = unwritten;
        }

        MapStore takeUnwritten() {
            return UNWRITTEN.getAndSet(this, null);
        }
    }

    // Tick thread only.
    private final java.util.IdentityHashMap<WorkPool.Receipt, Queued> inFlightSaves =
            new java.util.IdentityHashMap<>();

    private final java.util.Set<String> saidUnapproved =
            new java.util.LinkedHashSet<>();

    // Tick thread only; label and labelCollector are for this address.
    private String labelFor;

    private String label;

    private String labelCollector = "";

    // The share name of the server whose world the store's root holds; null with no server. Any thread reads it.
    private volatile String rootServer = null;

    // Tick thread only; the label rootServer was last made from, and the usable name made from it.
    private String namedLabel = null;

    private String namedAs = null;

    // Tick thread only; null once said.
    private String chosenNotice;

    // Tick thread only; null once said.
    private String emptyListNotice;

    // Tick thread only.
    private boolean setupOwed = true;

    private boolean setupAsked = false;

    private final CollectorSettings.Setup setup = new CollectorSettings.Setup();

    // Tick thread only; null when no line waits.
    private java.util.List<String> setupLines = null;

    private int setupLineAt = 0;

    ChunkCapture(MapStorage storage) {
        this(storage, new ShareSender());
    }

    ChunkCapture(MapStorage storage, ShareSender share) {
        this.storage = storage;
        this.share = share;
        this.world = new CollectorCartographerWorld(storage);
        this.ground = new CollectorCartographerGround(storage, world);

        live = this;
    }

    void register() {
        storage.setLegacyColourConverter(LandCoverClassifier::fromLegacyColour);

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> joined(client));

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_LOAD
                .register((loaded, chunk) ->
                        chunkLoaded(chunk.getPos().x(), chunk.getPos().z()));

        Sandpaper.scheduler().register(
                JobSpec.everyMillis(Lane.TICK, SCAN_INTERVAL_MILLIS)
                        .withOwner(CollectorMod.MOD_ID)
                        .withPriority(Priority.LOW)
                        .neverDrop()
                        .withLabel("geosurvey-scan"),
                tick -> scan(tick));

        captureHandle = Sandpaper.scheduler().registerSliced(
                JobSpec.everyPump(Lane.TICK).withOwner(CollectorMod.MOD_ID)
                        .neverDrop()
                        .withLabel("geosurvey-capture"),
                this::captureOne);
        captureHandle.setPaused(true);

        handshakeHandle = Sandpaper.scheduler().registerSliced(
                JobSpec.everyPump(Lane.TICK).withOwner(CollectorMod.MOD_ID)
                        .neverDrop()
                        .withLabel("geosurvey-handshake"),
                this::handshakeStep);
        handshakeHandle.setPaused(true);

        Sandpaper.scheduler().register(
                JobSpec.everyMillis(Lane.TICK, SAVE_INTERVAL_MILLIS)
                        .withOwner(CollectorMod.MOD_ID)
                        .withPriority(Priority.LOW)
                        .neverDrop()
                        .withLabel("geosurvey-save"),
                tick -> saveDirtyInBackground(tick));

        Handle imports = Sandpaper.scheduler().registerSliced(
                JobSpec.everyPump(Lane.TICK).withOwner(CollectorMod.MOD_ID)
                        .neverDrop()
                        .withLabel("openmap-import"),
                ground::importStep);
        imports.setPaused(true);
        ground.drainWith(imports);
    }

    // Client thread; the player joined a server or a single-player world.
    void joined(Minecraft client) {
        rootAt(worldRoot(client, LandNav.config()), uploadLabel(shareServer(client)));
        world.followRoot(storage.root());
    }

    // Moves the store's root, then the share name of its server; the tick thread. label may be null: no server.
    void rootAt(Path root, String label) {
        storage.setRoot(root);
        rootServer = shareName(label);
    }

    // The share name of the server whose world the store's root holds, or null; any thread.
    String rootServer() {
        return rootServer;
    }

    // Tick thread only; the usable name of a label, made again only when the label changes.
    private String shareName(String label) {
        if (label != null && !label.equals(namedLabel)) {
            namedAs = Batch.usableServerName(label);
            namedLabel = label;
        }
        return label == null ? null : namedAs;
    }

    Step handshakeStep(Tick tick) {
        if (!share.groundReadingOpen()) {
            if (handshakeHandle != null) {
                handshakeHandle.setPaused(true);
            }
            return Step.YIELD;
        }
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? Step.YIELD : share.readingGround(client.level, tick);
    }

    void unregister() {
        Sandpaper.scheduler().cancelAllFor(CollectorMod.MOD_ID);
        ground.stopImports();

        share.sync(LandNav.config(), null, null);
        share.standing(null);

        Sandpaper.workPool().cancelAllFor(CollectorMod.MOD_ID);
        takeBackDroppedSaves();
        storage.flushQuietly();
        share.close();
        captureHandle = null;
    }

    String currentDimension() {
        return currentDimension;
    }

    MapStore activeStore() {
        return currentDimension.isEmpty() ? null : storage.store(currentDimension);
    }

    CollectorCartographerWorld world() {
        return world;
    }

    CollectorCartographerGround ground() {
        return ground;
    }


    private static final int MAX_NOTICES_PER_SCAN = 3;

    private static final String EMPTY_LIST_LINE = CollectorSettings.Setup.CHAT_PREFIX
            + "Contribute ground is off: the"
            + " approved-server list is empty. Add a server with /geosurvey server add"
            + " <host>, or turn contribute ground on for"
            + " every server.";

    private void scan(Tick tick) {
        long deadline = System.nanoTime() + tick.remainingNanos();
        Minecraft client = Minecraft.getInstance();
        LandNavConfig config = LandNav.config();
        resurveyBefore = resurveyCutoff(config);
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            CollectorFinder.worldLeft();
            world.left();
            share.sync(config, null, null);
            share.closeGroundReading();
            share.standing(null);
            pending.clear();
            queued.clear();
            unloaded.clear();
            voidChunks.clear();
            backlogRotationCurrent = false;
            currentDimension = "";
            scannedLevel = null;
            setupOwed = true;
            setupLines = null;
            scanRestart();
            if (handshakeHandle != null) {
                handshakeHandle.setPaused(true);
            }
            return;
        }
        sayApprovedPorts(client, config);
        noteEmptyServerList(config);
        String server = shareServer(client);
        String uploadServer = uploadLabel(server);
        boolean contributing = sharing(config, server);
        if (!contributing) {
            share.sync(config, null, null);
            share.standing(null);
            tell(client, server);
        }
        sayNotices(client);
        rootAt(worldRoot(client, config), uploadServer);
        world.followRoot(storage.root());
        followLevel(level);
        MapStore store = storage.store(currentDimension);

        if (contributing) {
            ResourceKey<Level> dimensionKey = level.dimension();
            if (!dimensionKey.equals(scannedDimensionKey)) {
                scannedDimensionText = dimensionKey.identifier().toString();
                scannedDimensionKey = dimensionKey;
            }
            String dimensionId = scannedDimensionText;
            chooseCollector(config, server);
            share.sync(config, uploadServer,
                    dimensionId);
            if (uploadServer != null) {
                standingAt.server = uploadServer;
                standingAt.dimension = dimensionId;
                standingAt.name = client.player.getGameProfile().name();
                standingAt.x = client.player.blockPosition().getX();
                standingAt.z = client.player.blockPosition().getZ();
                standingAt.ticks = (int) (level.getOverworldClockTime() % OVERWORLD_DAY_TICKS);
                standingAt.storm = level.isRaining();
                standingAt.thunder = level.isThundering();
                share.standing(standingAt);

                share.walking(level, uploadServer,
                        dimensionId,
                        client.player.blockPosition().getX(),
                        client.player.blockPosition().getZ());
            }
        }
        noteSetup(config, contributing);

        int centreX = client.player.blockPosition().getX() >> CHUNK_BLOCK_SHIFT;
        int centreZ = client.player.blockPosition().getZ() >> CHUNK_BLOCK_SHIFT;
        int radius = scanRadius(client);
        dropBacklogOutOfReach(centreX, centreZ, radius + CACHE_SLACK);
        scanRing(centreX, centreZ, radius, store, deadline);
        if (!pending.isEmpty() && captureHandle != null) {
            captureHandle.setPaused(false);
        }
        if (handshakeHandle != null) {
            handshakeHandle.setPaused(!share.groundReadingOpen());
        }
    }

    int scanRing(int centreX, int centreZ, int radius, MapStore store, long deadline) {
        if (radius != scanOrderRadius) {
            scanOrderCells = ringOrder(radius, scanOrder);
            scanOrderRadius = radius;
        }
        long[] ring = scanOrder;
        int cells = scanOrderCells;
        long revisions = store.revisions();
        regionsRequestedThisPass.clear();
        if (unloadedRadius != radius || unloadedCentreX != centreX
                || unloadedCentreZ != centreZ) {
            unloaded.clear();
            unloadedCentreX = centreX;
            unloadedCentreZ = centreZ;
            unloadedRadius = radius;
        }
        int at;
        long lapRevisions;
        if (scanIsComplete(centreX, centreZ, radius, store, revisions)) {
            at = cells;
            lapRevisions = revisions;
        } else {
            at = scanResume(centreX, centreZ, radius, store, revisions);
            lapRevisions = at == 0 ? revisions : scanCursorRevisions;
            if (at == 0) {
                lapOldestStamp = NOTHING_TO_RESURVEY;
            }
            store.allowRegionLoads(1);
            try {
                int walked = 0;
                while (at < cells) {
                    long offset = ring[at];
                    if ((walked++ & DEADLINE_CHECK_MASK) == 0 && System.nanoTime() >= deadline) {
                        break;
                    }
                    int cx = centreX + dev.openmap.map.Spiral.x(offset);
                    int cz = centreZ + dev.openmap.map.Spiral.z(offset);
                    long key = MapStore.key(cx, cz);
                    if (!queued.contains(key) && (unloaded.isEmpty() || !unloaded.contains(key))
                            && (voidChunks.isEmpty() || !voidChunks.contains(key))) {
                        ChunkSample existing = store.peek(cx, cz);
                        boolean missing = existing == null;
                        if (missing) {
                            requestMissingRegion(store, cx, cz);
                        } else {
                            noteLapStamp(existing);
                        }
                        if (missing || !existing.isCurrent() || dueForResurvey(existing)) {
                            queued.add(key);
                            pending.enqueue(key);
                        }
                    }
                    at++;
                }
            } finally {
                store.allowRegionLoads(MapStore.UNLIMITED_LOADS);
            }
        }
        if (at < cells) {
            scanStopped(at, centreX, centreZ, radius, store, lapRevisions);
        } else if (store.revisions() == lapRevisions && unloaded.isEmpty()) {
            markScanComplete(centreX, centreZ, radius, store, revisions);
        } else {
            scanRestart();
        }
        return at;
    }

    void followLevel(ClientLevel level) {
        String dimension = level.dimension().toString();
        if (!dimension.equals(currentDimension)) {
            currentDimension = dimension;
            pending.clear();
            queued.clear();
            unloaded.clear();
            voidChunks.clear();
            backlogRotationCurrent = false;
            scanRestart();
        }
        if (level != scannedLevel) {
            scannedLevel = level;
            partial = null;
            partialColumn = 0;
            pending.clear();
            queued.clear();
            unloaded.clear();
            voidChunks.clear();
            backlogRotationCurrent = false;
            scanRestart();
        }
        if (Sandpaper.isReady()) {
            storage.loadLandmarksInBackground(Sandpaper.workPool(), CollectorMod.MOD_ID,
                    currentDimension);
        }
    }

    int scanResume(int centreX, int centreZ, int radius, MapStore store,
                   long revisions) {
        if (scanCursor == 0 || scanCursorCentreX != centreX || scanCursorCentreZ != centreZ
                || scanCursorRadius != radius || scanCursorStore != store) {
            return 0;
        }
        return scanCursor;
    }

    void scanStopped(int at, int centreX, int centreZ, int radius, MapStore store,
                     long revisions) {
        scanCursor = at;
        scanCursorCentreX = centreX;
        scanCursorCentreZ = centreZ;
        scanCursorRadius = radius;
        scanCursorStore = store;
        scanCursorRevisions = revisions;
    }

    void scanRestart() {
        scanCursor = 0;
        scanCursorStore = null;
        scanComplete = false;
        scanCompleteStore = null;
        scanCompleteOldestStamp = NOTHING_TO_RESURVEY;
    }

    void rememberUnloaded(long key) {
        if (unloaded.size() < MAX_UNLOADED) {
            unloaded.add(key);
        }
    }

    void chunkLoaded(int chunkX, int chunkZ) {
        long key = MapStore.key(chunkX, chunkZ);
        boolean changed = unloaded.remove(key);
        if (voidChunks.remove(key)) {
            changed = true;
        }
        if (changed) {
            scanComplete = false;
        }
    }

    int rememberedUnloaded() {
        return unloaded.size();
    }

    private void rememberVoid(long key) {
        if (voidChunks.size() < MAX_VOID_CHUNKS) {
            voidChunks.add(key);
        }
    }

    boolean scanIsComplete(int centreX, int centreZ, int radius, MapStore store,
                           long revisions) {
        return scanComplete
                && unloaded.isEmpty()
                && scanCompleteCentreX == centreX
                && scanCompleteCentreZ == centreZ
                && scanCompleteRadius == radius
                && scanCompleteStore == store
                && scanCompleteRevisions == revisions
                && !heldGroundIsDue();
    }

    private boolean heldGroundIsDue() {
        return resurveyBefore >= scanCompleteOldestStamp;
    }

    private void noteLapStamp(ChunkSample held) {
        if (held.hasWallClockCapture() && held.capturedAt() < lapOldestStamp) {
            lapOldestStamp = held.capturedAt();
        }
    }

    void markScanComplete(int centreX, int centreZ, int radius, MapStore store,
                          long revisions) {
        scanCursor = 0;
        scanCursorStore = null;
        scanComplete = true;
        scanCompleteCentreX = centreX;
        scanCompleteCentreZ = centreZ;
        scanCompleteRadius = radius;
        scanCompleteStore = store;
        scanCompleteRevisions = revisions;
        scanCompleteOldestStamp = lapOldestStamp;
    }

    void dropBacklogOutOfReach(int centreX, int centreZ, int reach) {
        if (backlogRotationCurrent && backlogRotationCentreX == centreX
                && backlogRotationCentreZ == centreZ && backlogRotationReach == reach) {
            return;
        }
        for (int left = pending.size(); left > 0; left--) {
            long key = pending.dequeueLong();
            backlogRotationDequeues++;
            if (Math.abs(MapStore.chunkX(key) - centreX) <= reach
                    && Math.abs(MapStore.chunkZ(key) - centreZ) <= reach) {
                pending.enqueue(key);
            } else {
                queued.remove(key);
            }
        }
        backlogRotationCurrent = true;
        backlogRotationCentreX = centreX;
        backlogRotationCentreZ = centreZ;
        backlogRotationReach = reach;
    }

    void requestMissingRegion(MapStore store, int cx, int cz) {
        long region = MapRegion.keyOfChunk(cx, cz);
        if (!regionsRequestedThisPass.add(region)) {
            return;
        }
        regionLoadCalls++;
        storage.loadRegionInBackground(Sandpaper.workPool(), CollectorMod.MOD_ID,
                currentDimension, store, MapRegion.of(cx), MapRegion.of(cz));
    }

    private Path worldRoot(Minecraft client, LandNavConfig config) {
        return worldDirs.worldRoot(client, config);
    }

    static String serverWorldKey(String address) {
        return ApprovedServers.serverWorldKey(address);
    }

    static Path serverWorldDir(Path dataDir, String address) {
        return WorldDirectories.serverWorldDir(dataDir, address);
    }

    static String saveFolderName(Path worldPath) {
        return WorldDirectories.saveFolderName(worldPath);
    }

    static String singleplayerPrior(String saveFolder, String displayName) {
        return WorldDirectories.singleplayerPrior(saveFolder, displayName);
    }

    public static void noteTransferTarget(String host, int port, ServerData from) {
        WorldDirectories.noteTransferTarget(host, port, from);
    }

    public static void noteCommonListenerDisconnect(boolean wasTransferring) {
        WorldDirectories.noteCommonListenerDisconnect(wasTransferring);
    }

    private static String rawServerIp(Minecraft client) {
        return WorldDirectories.rawServerIp(client);
    }

    static String shareServer(Minecraft client) {
        return WorldDirectories.shareServer(client);
    }

    // Null off a server.
    static String joinedServer() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : shareServer(client);
    }

    // Empty unless the address is in a known group.
    static String knownCollector(String serverAddress) {
        return ApprovedServers.knownCollector(serverAddress);
    }


    private static long serverKeyCalls;

    static long serverKeyCalls() {
        return serverKeyCalls;
    }

    static String serverKey(String address) {
        return ApprovedServers.serverKey(address);
    }

    // Shares the config's list; creates it when null.
    static java.util.List<String> approvedServers(LandNavConfig config) {
        return ApprovedServers.approvedServers(config);
    }

    static boolean listed(LandNavConfig config, String key) {
        return ApprovedServers.listed(config, key);
    }

    static boolean gatesOn(LandNavConfig config, String serverAddress) {
        return ApprovedServers.gatesOn(config, serverAddress);
    }

    static boolean narrowsAnApprovedPort(LandNavConfig config) {
        return ApprovedServers.narrowsAnApprovedPort(config);
    }

    static boolean gating(LandNavConfig config) {
        return ApprovedServers.gating(config);
    }

    static boolean approved(LandNavConfig config, String serverAddress) {
        return ApprovedServers.approved(config, serverAddress);
    }

    boolean sharing(LandNavConfig config, String server) {
        return gate.sharing(config, server);
    }

    boolean sharing() {
        return gate.sharing();
    }

    private static void sayApprovedPorts(Minecraft client, LandNavConfig config) {
        if (!client.isGameLoadFinished()) {
            return;
        }
        if (!config.takeLoadedBeforeApprovedPorts()) {
            return;
        }
        if (!narrowsAnApprovedPort(config)) {
            return;
        }
        SystemToast.addOrUpdate(client.gui.toastManager(), APPROVED_PORTS_TOAST,
                Component.translatableWithFallback(
                        "openmap-collect.toast.approved_ports.title",
                        "Approved servers match the port you typed"),
                Component.translatableWithFallback(
                        "openmap-collect.toast.approved_ports.body",
                        "An entry with a port approves only that host and port."
                                + " An entry with no port,"
                                + " or port " + DEFAULT_PORT + ", approves that"
                                + " host on any"
                                + " port."));
        LOGGER.info("[openmap-collect] an approved-server entry with a port"
                + " approves only that host and port. An entry with no port,"
                + " or naming 25565, approves that host"
                + " on any port.");
    }

    private void sayNotices(Minecraft client) {
        if (client.gui == null) {
            return;
        }
        int first = sayOnce(client, emptyListNotice);
        emptyListNotice = null;
        first += sayOnce(client, chosenNotice);
        chosenNotice = null;
        first += saySetup(client, MAX_NOTICES_PER_SCAN - first);
        for (int said = first; said < MAX_NOTICES_PER_SCAN; said++) {
            String line = share.groundNotice();
            if (line == null) {
                break;
            }
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.literal(ShareCommand.drawable(line)).withStyle(ChatFormatting.GRAY));
        }
    }

    private String lastToldServer;

    private void tell(Minecraft client, String server) {
        if (client.gui == null) {
            return;
        }
        if (server == lastToldServer) {
            return;
        }
        lastToldServer = server;
        String key = serverKey(server);
        String named = key.isEmpty() ? String.valueOf(server) : key;
        if (sayOnce(named)) {
            String said = key.isEmpty()
                    ? "GeoSurvey is mapping this server but not contributing."
                            + " See the list"
                            + " with /geosurvey"
                            + " server."
                    : "GeoSurvey is mapping " + key + " but not contributing."
                            + " Add it with"
                            + " /geosurvey server add " + key + ".";
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.literal(said).withStyle(ChatFormatting.GRAY));
        }
    }

    boolean sayOnce(String server) {
        return saidUnapproved.size() < MAX_NAMED_SERVERS && saidUnapproved.add(server);
    }

    // Tick thread only.
    private void learn(String joined) {
        if (joined != labelFor) {
            labelFor = joined;
            String key = joined == null ? null : serverWorldKey(joined);
            String name = LandNavConfig.KnownServers.nameOf(key);
            label = name.isEmpty() ? joined : name;
            labelCollector = LandNavConfig.KnownServers.collectorOf(key);
        }
    }

    // Tick thread only; a known group's name, else the address.
    String uploadLabel(String joined) {
        learn(joined);
        return label;
    }

    // Tick thread only.
    void chooseCollector(LandNavConfig config, String joined) {
        learn(joined);
        if (config.shareEnabled && !labelCollector.isEmpty() && blank(config.shareCollector)) {
            config.shareCollector = labelCollector;
            CollectorOptions.persistLive();
            String host = serverKey(joined);
            chosenNotice = "GeoSurvey set its collector address to " + labelCollector
                    + " for " + host + ". Ground is uploaded there with your"
                    + " Minecraft UUID; a shared claim or marker sends your player name.";
            LOGGER.info("[openmap-collect] collector address set to {} for {}",
                    labelCollector, host);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    // Tick thread only.
    private void noteEmptyServerList(LandNavConfig config) {
        if (config.takeEmptyServerListTurnedOff()) {
            emptyListNotice = EMPTY_LIST_LINE;
        }
    }

    // line may be null.
    private static int sayOnce(Minecraft client, String line) {
        if (line == null) {
            return 0;
        }
        client.gui.hud.getChat().addClientSystemMessage(Component.literal(
                ShareCommand.drawable(line)).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    // Shows the setup at the next scan; tick thread only.
    void showSetup() {
        setupAsked = true;
    }

    // Tick thread only.
    private void noteSetup(LandNavConfig config, boolean listed) {
        if (!setupAsked && !(setupOwed && listed && config.shareEnabled && labelFor != null)) {
            return;
        }
        boolean due = setupAsked;
        if (!due) {
            setup.read(config, share, labelFor, listed, false);
            due = setup.missing();
        }
        if (due) {
            setup.read(config, share, labelFor, listed, true);
            setupLines = setup.said();
            setupLineAt = 0;
            setupAsked = false;
            setupOwed = false;
        }
    }

    private int saySetup(Minecraft client, int room) {
        java.util.List<String> lines = setupLines;
        int said = 0;
        while (lines != null && said < room && setupLineAt < lines.size()) {
            client.gui.hud.getChat().addClientSystemMessage(Component.literal(
                    ShareCommand.drawable(lines.get(setupLineAt))).withStyle(ChatFormatting.GRAY));
            setupLineAt++;
            said++;
        }
        if (lines != null && setupLineAt >= lines.size()) {
            setupLines = null;
        }
        return said;
    }


    private Step captureOne(Tick tick) {
        return captureOne(Minecraft.getInstance().level,
                System.nanoTime() + tick.remainingNanos());
    }

    Step captureOne(ClientLevel level) {
        return captureOne(level, NO_DEADLINE);
    }

    private static final long NO_DEADLINE = Long.MIN_VALUE;

    private static final int COLUMNS_PER_SLICE = 13;

    // Null when no chunk is mid-sample.
    private ChunkSample partial;

    private long partialKey;

    private int partialColumn;

    Step captureOne(ClientLevel level, long deadline) {
        if (partial == null && pending.isEmpty()) {
            if (captureHandle != null) {
                captureHandle.setPaused(true);
            }
            return Step.YIELD;
        }
        if (level == null || currentDimension.isEmpty()) {
            partial = null;
            return Step.YIELD;
        }
        if (level != scannedLevel) {
            partial = null;
            pending.clear();
            queued.clear();
            unloaded.clear();
            voidChunks.clear();
            backlogRotationCurrent = false;
            scanRestart();
            return Step.YIELD;
        }
        boolean resuming = partial != null;
        long key = resuming ? partialKey : pending.dequeueLong();
        if (!resuming) {
            queued.remove(key);
        }
        return captureQueued(level, key, resuming, deadline);
    }

    private Step captureQueued(ClientLevel level, long key, boolean resuming, long deadline) {
        int cx = MapStore.chunkX(key);
        int cz = MapStore.chunkZ(key);
        ChunkSample held = resuming ? null : heldGround(cx, cz);
        if (resuming || held == null || !held.isCurrent() || dueForResurvey(held)) {
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                partial = null;
                rememberUnloaded(key);
            } else {
                boolean floored = bedrockFloored(level.dimension());
                if (floored && chunk.getSections() != null && chunk.getHighestFilledSectionIndex()
                        == ChunkAccess.NO_FILLED_SECTION) {
                    partial = null;
                    rememberVoid(key);
                } else {
                    ChunkSample done = sample(level, chunk, cx, cz, deadline, held);
                    if (done == null) {
                        if (!sampledUnchanged) {
                            partialKey = key;
                        }
                    } else if (!floored || done.holdsGround()) {
                        captured(currentDimension, done);
                    } else {
                        rememberVoid(key);
                    }
                }
            }
        }
        return Step.MORE;
    }

    private ChunkSample heldGround(int cx, int cz) {
        MapStore store = storage.loadedStore(currentDimension);
        storage.countAsUse(currentDimension, store);
        return store == null ? null : store.peek(cx, cz);
    }

    void captured(String dimension, ChunkSample sample) {
        MapStore store = storage.store(dimension);
        ChunkSample held = store.peek(sample.chunkX, sample.chunkZ);
        if (held != null && held.isCurrent() && sameGround(held, sample)) {
            held.setCapturedAt(sample.capturedAt());
            return;
        }
        store.put(sample);
        storage.markDirty(dimension);
        ground.stored(dimension, sample);
        if (sharing()) {
            share.offer(sample);
        }
        capturedChunks++;
    }

    private static long resurveyCutoff(LandNavConfig config) {
        if (!config.resurveyEnabled) {
            return NEVER_RESURVEY;
        }
        return UtcClock.collector().nowMillis() - config.resurveySeconds * MILLIS_PER_SECOND;
    }

    private boolean dueForResurvey(ChunkSample held) {
        return resurveyBefore != NEVER_RESURVEY && held.hasWallClockCapture()
                && held.capturedAt() <= resurveyBefore;
    }

    private static boolean sameGround(ChunkSample first, ChunkSample second) {
        for (int z = 0; z < ChunkSample.SIZE; z++) {
            for (int x = 0; x < ChunkSample.SIZE; x++) {
                if (first.height(x, z) != second.height(x, z)
                        || first.coverOrdinal(x, z) != second.coverOrdinal(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean bedrockFloored(ResourceKey<Level> dimension) {
        return Level.OVERWORLD.equals(dimension) || Level.NETHER.equals(dimension);
    }

    private static final class MutableSurface implements ChunkSampler.Surface {
        private LevelChunk chunk;
        int calls;

        @Override
        public int topBlockY(int localX, int localZ) {
            calls++;
            return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
        }
    }

    private final MutableSurface surface = new MutableSurface();

    private final ChunkSampler.Scratch scratch = ChunkSampler.newScratch();

    private final ChunkSample resurveyScratch = ChunkSample.forFullWriter(0, 0);

    private boolean sampledUnchanged;

    // Null if the budget ends first or held ground is unchanged.
    private ChunkSample sample(ClientLevel level, LevelChunk chunk, int cx, int cz,
                               long deadline, ChunkSample held) {
        sampledUnchanged = false;
        boolean reusing = partial == null && held != null && held.isCurrent()
                && dueForResurvey(held);
        ChunkSample out;
        if (partial != null) {
            out = partial;
        } else if (reusing) {
            resurveyScratch.resetForFullWriter(cx, cz);
            out = resurveyScratch;
        } else {
            out = ChunkSample.forFullWriter(cx, cz);
        }
        int at = partial != null ? partialColumn : 0;
        surface.chunk = chunk;
        try {
            do {
                at = ChunkSampler.sample(out, chunk, level.getMinY(), surface,
                        scratch, at, COLUMNS_PER_SLICE);
            } while (at < ChunkSample.COLUMNS
                    && (deadline == NO_DEADLINE || System.nanoTime() - deadline < 0L));
        } finally {
            surface.chunk = null;
        }
        ChunkSample result;
        if (at < ChunkSample.COLUMNS) {
            partial = reusing ? out.copyForFullWriter() : out;
            partialColumn = at;
            result = null;
        } else {
            partial = null;
            out.setCapturedAt(UtcClock.collector().nowMillis());
            if (reusing && sameGround(held, out)) {
                held.setCapturedAt(out.capturedAt());
                sampledUnchanged = true;
                result = null;
            } else {
                result = reusing ? out.copyForFullWriter() : out;
            }
        }
        return result;
    }


    void saveDirtyInBackground(Tick tick) {
        saveDirtyInBackground(System.nanoTime() + tick.remainingNanos());
    }

    void saveDirtyInBackground() {
        saveDirtyInBackground(Long.MAX_VALUE);
    }

    private void saveDirtyInBackground(long deadline) {
        takeBackDroppedSaves();
        storage.endSaveCycles();
        boolean[] stopAfterThisEntry = {false};
        Path savingRoot = storage.root();
        storage.forEachLoadedStore((dimension, live) -> {
            if (stopAfterThisEntry[0] || System.nanoTime() >= deadline) {
                stopAfterThisEntry[0] = true;
                return;
            }
            if (live == null || !live.hasDirtyRegions()) {
                return;
            }
            if (!roomToTrackASave()) {
                stopAfterThisEntry[0] = true;
                return;
            }
            boolean allQueued = true;
            for (long region : live.dirtyRegionsInOrder()) {
                int rx = MapRegion.x(region);
                int rz = MapRegion.z(region);
                if (System.nanoTime() >= deadline) {
                    allQueued = false;
                    break;
                }
                if (inFlightSaves.size() >= MAX_TRACKED_SAVES) {
                    allQueued = false;
                    break;
                }
                Path file = storage.regionFileFor(dimension, rx, rz);
                if (file == null) {
                    allQueued = false;
                    break;
                }
                MapStore prepared = storage.regionGroundFor(dimension, live, rx, rz);

                WorkPool.Receipt receipt = new WorkPool.Receipt();
                java.util.concurrent.atomic.AtomicBoolean landed =
                        new java.util.concurrent.atomic.AtomicBoolean();
                Queued queued = new Queued(receipt, dimension, live, rx, rz, file, prepared);
                ChunkSample[] held = prepared.all().toArray(new ChunkSample[0]);

                java.util.function.Consumer<Boolean> onFinished = ok -> {
                    // Tick thread only.
                    inFlightSaves.remove(queued.receipt);
                    savesFinished++;
                    if (!Boolean.TRUE.equals(ok)) {
                        savesFailed++;
                        storage.countAsUse(dimension, live);
                        if (!storage.holds(dimension, live)) {
                            LOGGER.warn("region save failed for {} {},{};"
                                + " the world is"
                                + " gone."
                                + " Filed"
                                + " against {} for the"
                                + " next"
                                + " flush.", dimension, rx, rz, file);
                        } else {
                            live.markRegionDirty(rx, rz);
                            storage.markDirty(dimension);
                        }
                    } else {
                        ground.saved(savingRoot, dimension, rx, rz);
                    }
                };

                boolean queuedOk = Sandpaper.workPool().submit(
                        CollectorMod.MOD_ID,
                        () -> {
                            Boolean saved;
                            try {
                                MapStorage.writeRegionFile(prepared, file,
                                        LandCoverClassifier::fromLegacyColour,
                                        storage.damageNote());
                                queued.started = true;
                                queued.unwritten = null;
                                saved = Boolean.TRUE;
                            } catch (Exception failed) {
                                storage.fileFailedRegionWrite(dimension, live, rx, rz, file,
                                        prepared);
                                LOGGER.warn("region save failed for {} {},{}",
                                        dimension, rx, rz, failed);
                                queued.started = true;
                                queued.unwritten = null;
                                saved = Boolean.FALSE;
                            } finally {
                                landed.set(true);
                            }
                            return saved;
                        },
                        onFinished,
                        receipt,
                        () -> {
                        },
                        onFinished);
                if (queuedOk) {
                    savesStarted++;
                    inFlightSaves.put(receipt, queued);
                    storage.rememberRegionWrite(dimension, live, rx, rz, held, landed,
                            receipt::isDroppedUnrun);
                    live.markRegionHandedOff(rx, rz);
                } else {
                    allQueued = false;
                    stopAfterThisEntry[0] = true;
                    break;
                }
            }
            if (allQueued && !live.hasDirtyRegions()) {
                storage.markClean(dimension);
            }
        });
    }

    private boolean roomToTrackASave() {
        if (inFlightSaves.size() < MAX_TRACKED_SAVES) {
            return true;
        }
        inFlightSaves.values().removeIf(waiting -> waiting.started);
        return inFlightSaves.size() < MAX_TRACKED_SAVES;
    }

    private void takeBackDroppedSaves() {
        java.util.Iterator<Queued> waiting = inFlightSaves.values().iterator();
        while (waiting.hasNext()) {
            Queued queued = waiting.next();
            boolean crashed = queued.receipt.isCrashed();
            if (queued.receipt.isDroppedUnrun() || crashed) {
                waiting.remove();
            savesFinished++;
            savesFailed++;
            MapStore carried = queued.takeUnwritten();
            String how = crashed
                    ? " crashed running it"
                    : " dropped it unrun";
                storage.countAsUse(queued.dimension, queued.store);

                if (!storage.holds(queued.dimension, queued.store)) {
                if (carried != null) {
                    if (crashed) {
                        storage.fileFailedRegionWrite(queued.dimension, queued.store,
                                queued.regionX, queued.regionZ, queued.file, carried);
                    } else {
                        storage.fileDroppedRegionWrite(queued.dimension, queued.store,
                                queued.regionX, queued.regionZ, queued.file, carried);
                    }
                    LOGGER.warn("geosurvey queued {} {},{};"
                            + " the work pool" + how + "."
                            + " Handed"
                            + " to storage,"
                            + " written to {} at"
                            + " the next flush.", queued.dimension,
                            queued.regionX, queued.regionZ, queued.file);
                } else {
                    LOGGER.warn("geosurvey queued {} {},{};"
                            + " the work pool" + how + "."
                            + " Nothing was carried back:"
                            + " lost, aimed"
                            + " at {}.", queued.dimension,
                            queued.regionX, queued.regionZ, queued.file);
                }
                } else {
                    if (carried != null) {
                if (crashed) {
                    storage.fileFailedRegionWrite(queued.dimension, queued.store,
                            queued.regionX, queued.regionZ, queued.file, carried);
                } else {
                    storage.fileDroppedRegionWrite(queued.dimension, queued.store,
                            queued.regionX, queued.regionZ, queued.file, carried);
                }
                    }
                    queued.store.markRegionDirty(queued.regionX, queued.regionZ);
                    storage.markDirty(queued.dimension);
                    LOGGER.warn("geosurvey queued {} {},{};"
                    + " the work pool" + how + "."
                    + " Put back, will write to {}.",
                    queued.dimension, queued.regionX, queued.regionZ,
                            queued.file);
                }
            }
        }
    }

    void flushNow() {
        takeBackDroppedSaves();
        storage.flushQuietly();
    }

    long capturedChunks() {
        return capturedChunks;
    }

    int pendingChunks() {
        return pending.size();
    }

    long savesStarted() {
        return savesStarted;
    }

    long savesFinished() {
        return savesFinished;
    }

    long savesFailed() {
        return savesFailed;
    }

    // Null before a capture is built.
    static ChunkCapture live() {
        return live;
    }

    private static final class ApprovedServers {
        static String serverKey(String address) {
            serverKeyCalls++;
            return CollectorGuess.hostOf(address);
        }

        static java.util.List<String> approvedServers(LandNavConfig config) {
            if (config.approvedServers == null) {
                config.approvedServers = new java.util.ArrayList<>();
            }
            return config.approvedServers;
        }

        private static boolean listedCacheStale(LandNavConfig config, java.util.List<String> entries) {
            boolean stale;
            if (config != listedCacheConfig || entries.size() != listedSnapshot.length) {
                stale = true;
            } else {
                stale = false;
                for (int i = 0; i < listedSnapshot.length && !stale; i++) {
                    if (entries.get(i) != listedSnapshot[i]) {
                        stale = true;
                    }
                }
            }
            return stale;
        }

        static boolean listed(LandNavConfig config, String key) {
            if (key.isEmpty()) {
                return false;
            }
            java.util.List<String> entries = approvedServers(config);
            if (listedCacheStale(config, entries)) {
                String[] snapshot = entries.toArray(new String[0]);
                java.util.Set<String> keys = new java.util.HashSet<>();
                for (String entry : snapshot) {
                    keys.add(serverKey(entry));
                }
                listedSnapshot = snapshot;
                listedKeys = keys;
                listedCacheConfig = config;
            }
            return listedKeys.contains(key);
        }

        static boolean gatesOn(LandNavConfig config, String serverAddress) {
            return listedAs(config, serverAddress, false);
        }

        // asOneServer: also match entries for group members.
        private static boolean listedAs(LandNavConfig config, String serverAddress,
                boolean asOneServer) {
            String host = serverKey(serverAddress);
            if (host.isEmpty()) {
                return false;
            }
            String joined = serverWorldKey(serverAddress, host);
            String group = asOneServer ? LandNavConfig.KnownServers.nameOf(joined) : "";
            boolean approved = false;
            for (String entry : approvedServers(config)) {
                if (entry != null) {
                    WorldKey entryKey = worldKeyOf(entry);
                    String named = entryKey.joined();
                    if (named.equals(joined)) {
                        approved = true;
                        break;
                    }
                    if (named.equals(host) && entryKey.key().equals(host)) {
                        approved = true;
                        break;
                    }
                    if (!group.isEmpty() && group.equals(LandNavConfig.KnownServers.nameOf(named))) {
                        approved = true;
                        break;
                    }
                }
            }
            return approved;
        }

        static boolean narrowsAnApprovedPort(LandNavConfig config) {
            boolean narrows = false;
            for (String entry : approvedServers(config)) {
                if (entry != null) {
                    String host = serverKey(entry);
                    if (!host.isEmpty() && !serverWorldKey(entry, host).equals(host)) {
                        narrows = true;
                        break;
                    }
                }
            }
            return narrows;
        }

        static boolean gating(LandNavConfig config) {
            return config != null && !approvedServers(config).isEmpty();
        }

        static boolean approved(LandNavConfig config, String serverAddress) {
            // Null means singleplayer.
            if (serverAddress == null) {
                return true;
            }
            return !gating(config) || listedAs(config, serverAddress, true);
        }

        // Any thread.
        static String knownCollector(String address) {
            return address == null ? "" : LandNavConfig.KnownServers.collectorOf(
                    serverWorldKey(address, CollectorGuess.hostOf(address)));
        }

        boolean sharing(LandNavConfig config, String server) {
            java.util.List<String> entries = config == null ? null : approvedServers(config);
            boolean gating = gating(config);
            if (sharingMemo && sharingConfig == config && sharingGating == gating
                    && java.util.Objects.equals(sharingServer, server)
                    && sameApprovedEntries(entries)) {
                sharing = sharingVerdict;
                return sharing;
            }
            sharing = approved(config, server);
            sharingConfig = config;
            sharingServer = server;
            sharingGating = gating;
            sharingEntries = entries == null ? null : entries.toArray(new String[0]);
            sharingVerdict = sharing;
            sharingMemo = true;
            return sharing;
        }

        private boolean sameApprovedEntries(java.util.List<String> entries) {
            boolean same;
            if (entries == null || sharingEntries == null) {
                same = entries == null && sharingEntries == null;
            } else if (entries.size() != sharingEntries.length) {
                same = false;
            } else {
                same = true;
                for (int i = 0; i < sharingEntries.length && same; i++) {
                    if (entries.get(i) != sharingEntries[i]) {
                        same = false;
                    }
                }
            }
            return same;
        }

        boolean sharing() {
            return sharing;
        }

        static String serverWorldKey(String address) {
            return serverWorldKey(address, serverKey(address));
        }

        private static String serverWorldKey(String address, String key) {
            String worldKey;
            if (key.isEmpty()) {
                worldKey = address;
            } else {
                String typed = address.trim().toLowerCase(java.util.Locale.ROOT);
                int port = key.length() + 1;
                boolean rootDotted = typed.length() > port && typed.charAt(port - 1) == '.';
                if (rootDotted) {
                    port++;
                }
                if (typed.length() <= port || !typed.startsWith(key)
                        || typed.charAt(port - 1) != ':') {
                    worldKey = key;
                } else {
                    int limit = typed.length() - 1;
                    int digits = port;
                    while (digits < limit && typed.charAt(digits) == '0') {
                        digits++;
                    }
                    if (typed.length() - digits == DEFAULT_PORT.length()
                            && typed.startsWith(DEFAULT_PORT, digits)) {
                        worldKey = key;
                    } else {
                        worldKey = rootDotted || digits != port
                                ? key + ":" + typed.substring(digits) : typed;
                    }
                }
            }
            return worldKey;
        }

        private record WorldKey(String key, String joined) {
        }

        private static WorldKey worldKeyOf(String address) {
            String key = serverKey(address);
            return new WorldKey(key, serverWorldKey(address, key));
        }

        private static LandNavConfig listedCacheConfig;

        private static String[] listedSnapshot = new String[0];

        private static java.util.Set<String> listedKeys = java.util.Set.of();

        private boolean sharing = false;

        private boolean sharingMemo;

        private LandNavConfig sharingConfig;

        private String sharingServer;

        private boolean sharingGating;

        private String[] sharingEntries;

        private boolean sharingVerdict;
    }

    private static final class WorldDirectories {
        // Read only; does not claim the directory.
        static Path serverWorldDir(Path dataDir, String address) {
            String host = serverKey(address);
            return worldDirFor(dataDir, ApprovedServers.serverWorldKey(address, host), address,
                    null);
        }

        private static Path worldDirFor(Path dataDir, String key, String priorSpelling,
                                        String hostSpelling) {
            boolean prior = priorSpelling != null && !priorSpelling.equals(key);
            boolean host = hostSpelling != null && !hostSpelling.isEmpty()
                    && !hostSpelling.equals(key);
            Path worldDir;
            if (prior && host) {
                worldDir = MapStorage.worldDir(dataDir, key, priorSpelling, hostSpelling);
            } else if (prior || host) {
                worldDir = MapStorage.worldDir(dataDir, key, prior ? priorSpelling : hostSpelling);
            } else {
                worldDir = MapStorage.worldDir(dataDir, key);
            }
            return worldDir;
        }

        private static String levelId(IntegratedServer local) {
            String name;
            try {
                name = saveFolderName(local.getWorldPath(LevelResource.ROOT));
            } catch (RuntimeException noPath) {
                name = null;
            }
            String levelId;
            if (name == null) {
                levelId = local.getWorldData().getLevelName();
            } else {
                levelId = name;
            }
            return levelId;
        }

        // Null if the path has no file name.
        static String saveFolderName(Path worldPath) {
            if (worldPath == null) {
                return null;
            }
            Path name = worldPath.normalize().getFileName();
            if (name == null) {
                return null;
            }
            String text = name.toString();
            return text.isBlank() ? null : text;
        }

        static String singleplayerPrior(String saveFolder, String displayName) {
            return saveFolder == null ? MapStorage.saveKeyPrefix() + displayName : null;
        }

        public static void noteTransferTarget(String host, int port, ServerData from) {
            if (from == null) {
                return;
            }
            transferTargetAddress = host + ":" + port;
            transferTargetOwner = from;
        }

        public static void noteCommonListenerDisconnect(boolean wasTransferring) {
            if (!wasTransferring) {
                transferTargetAddress = null;
                transferTargetOwner = null;
            }
        }

        private static String rawServerIp(Minecraft client) {
            ServerData server = client.getCurrentServer();
            return server != null && server.ip != null && !server.ip.isBlank()
                    ? server.ip : null;
        }

        static String shareServer(Minecraft client) {
            String server;
            if (transferTargetAddress != null) {
                ServerData now = client == null ? null : client.getCurrentServer();
                if (client == null || transferTargetOwner == now) {
                    server = transferTargetAddress;
                } else {
                    transferTargetAddress = null;
                    transferTargetOwner = null;
                    server = rawServerIp(client);
                }
            } else {
                server = rawServerIp(client);
            }
            return server;
        }

        private Path worldRoot(Minecraft client, LandNavConfig config) {
            String probe = shareServer(client);
            Object identity = probe != null ? probe : client.getSingleplayerServer();
            if (rootPath != null && identity != null && identity == rootIdentity) {
                return rootPath;
            }
            String key;
            String address = shareServer(client);
            String host = address == null ? null : serverKey(address);
            IntegratedServer local = address == null ? client.getSingleplayerServer() : null;
            if (address != null) {
                key = ApprovedServers.serverWorldKey(address, host);
            } else {
                key = local == null ? "unknown" : MapStorage.saveKeyPrefix() + levelId(local);
            }
            Path root;
            if (rootPath != null && key.equals(rootKey)) {
                rootIdentity = identity;
                root = rootPath;
            } else {
                String was;
                if (address != null) {
                    was = address;
                } else if (local == null) {
                    was = null;
                } else {
                    String saveFolder;
                    try {
                        saveFolder = saveFolderName(local.getWorldPath(LevelResource.ROOT));
                    } catch (RuntimeException noPath) {
                        saveFolder = null;
                    }
                    was = singleplayerPrior(saveFolder, local.getWorldData().getLevelName());
                }
                Path data = client.gameDirectory.toPath().resolve(LandNav.DATA_DIR);

                root = worldDirFor(data, key, was, null);
                MapStorage.claim(root, key);
                rootKey = key;
                rootPath = root;
                rootIdentity = identity;
            }
            return root;
        }

        // Tick thread only.
        private String rootKey;

        private Path rootPath;

        // Compared by reference only.
        private Object rootIdentity;

        // Null except during a server transfer.
        private static String transferTargetAddress;

        private static ServerData transferTargetOwner;
    }
}
