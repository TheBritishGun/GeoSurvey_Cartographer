package dev.openmap.client;

import dev.openmap.api.CartographerGround;
import dev.openmap.api.GroundBatch;
import dev.openmap.api.GroundChunk;
import dev.openmap.api.GroundListener;
import dev.openmap.api.ImportResult;
import dev.openmap.api.Registration;
import dev.openmap.map.ChunkSample;
import dev.openmap.map.MapRegion;
import dev.openmap.map.MapStorage;
import dev.openmap.map.MapStore;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.Step;
import dev.sandpaper.core.Tick;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// The ground part of the published cartographer: it pushes captured ground and takes imported regions.
final class CollectorCartographerGround implements CartographerGround {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final GroundListener[] NO_LISTENERS = new GroundListener[0];

    private static final int REGION_CHUNKS = MapRegion.CHUNKS * MapRegion.CHUNKS;

    private static final int MAX_DIMENSIONS = 16;

    private static final String NO_BATCH = "No ground was given.";

    private static final String TOO_MANY = "The import holds more chunks than one region.";

    private static final String EMPTY_ENTRY = "The import holds an empty entry.";

    private static final String OUTSIDE = "The import holds a chunk outside its region.";

    private static final String UNREADABLE = "Could not read the ground.";

    private static final String ON_CLIENT_THREAD =
            "Do not run the import on the game thread.";

    private static final String NO_DIMENSION = "The import names no dimension.";

    private static final String NO_WORLD = "No world is open. Nothing was written.";

    private static final String NOT_RUNNING = "The collector is not running. Nothing was written.";

    private static final String TIMED_OUT =
            "The collector did not take the import in time. Nothing was written.";

    private static final String INTERRUPTED = "The import was stopped. Nothing was written.";

    private static final String WORLD_CHANGED = "The world changed. Nothing was written.";

    private static final String NO_FOLDER =
            "Could not open the dimension folder. Nothing was written.";

    private static final String STOPPED = "The collector stopped. Nothing was written.";

    private static final String PREPARE_FAILED = "The collector could not take the import. Nothing was written.";

    private static final String WRITE_TIMED_OUT =
            "The collector did not write the import in time. The write can still finish.";

    private static final String WRITE_STOPPED =
            "The wait for the import was stopped. The write can still finish.";

    private static final String BUSY = "The collector is busy with other imports. Nothing was written.";

    // One region of an import, between the caller's thread and the client thread.
    private static final class Import {

        private static final int WAITING = 0;

        private static final int PREPARING = 1;

        private static final int PREPARED = 2;

        private static final int LANDED = 3;

        private static final int DONE = 4;

        private static final int ABANDONED = 5;

        private final String storedKey;

        private final String dimensionId;

        private final int regionX;

        private final int regionZ;

        private final Path world;

        private final AtomicInteger state = new AtomicInteger(WAITING);

        private final CountDownLatch ready = new CountDownLatch(1);

        // Set by the client thread before ready opens; null when no region was prepared.
        private volatile MapStorage.RegionImport region;

        // Set by the client thread before ready opens; null when the region was prepared.
        private volatile String failure;

        private Import(String storedKey, String dimensionId, int regionX, int regionZ, Path world) {
            this.storedKey = storedKey;
            this.dimensionId = dimensionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.world = world;
        }
    }

    // The batch as the caller's thread copied it; slots is null when the copy failed.
    private record Copy(ChunkSample[] slots, int chunks, String failure) {
    }

    private final MapStorage storage;

    private final CollectorCartographerWorld world;

    private final ConcurrentLinkedQueue<Import> imports = new ConcurrentLinkedQueue<>();

    // Writes each import's region file; stopImports stops it.
    private final CollectorImportWrite importWrite = new CollectorImportWrite();

    // Null while the collector's import job is not registered.
    private volatile Handle drain;

    private volatile Thread clientThread;

    // Client thread; replaced on each change, never changed in place.
    private GroundListener[] listeners = NO_LISTENERS;

    // Client thread; a stored dimension key to its resource id text.
    private final Map<String, String> dimensionIds = new HashMap<>();

    // Client thread.
    private boolean failureSaid = false;

    // Client thread.
    private long abandoned = 0L;

    CollectorCartographerGround(MapStorage storage, CollectorCartographerWorld world) {
        this.storage = storage;
        this.world = world;
        this.clientThread = Thread.currentThread();
    }

    @Override
    public Registration onGround(GroundListener listener) {
        Objects.requireNonNull(listener, "listener");
        GroundListener[] held = listeners;
        GroundListener[] grown = Arrays.copyOf(held, held.length + 1);
        grown[held.length] = listener;
        listeners = grown;
        return new CollectorListenerRegistration(() -> remove(listener));
    }

    @Override
    public ImportResult importRegion(String dimensionId, int regionX, int regionZ, GroundBatch batch,
            long timeoutNanos) {
        Copy copy = copy(batch, regionX, regionZ);
        Path at = world.worldFolder();
        String refusal = refusal(copy, dimensionId, at);
        ImportResult result;
        if (refusal != null) {
            result = refused(refusal);
        } else if (copy.chunks() == 0) {
            result = new ImportResult(0, 0, null);
        } else {
            result = queued(new Import(MapStorage.asStored(dimensionId), dimensionId, regionX, regionZ, at),
                    copy, timeoutNanos);
        }
        return result;
    }

    // Client thread; allocates nothing once the dimension is known.
    void stored(String storedKey, GroundChunk chunk) {
        GroundListener[] told = listeners;
        if (told.length == 0) {
            return;
        }
        String dimensionId = dimensionIdOf(storedKey);
        for (GroundListener listener : told) {
            try {
                listener.stored(dimensionId, chunk);
            } catch (RuntimeException failed) {
                sayFailure(failed);
            }
        }
    }

    // Client thread; a region save landed in the world whose folder is root.
    void saved(Path root, String storedKey, int regionX, int regionZ) {
        if (listeners.length == 0 || root == null || !root.equals(world.worldFolder())) {
            return;
        }
        regionWritten(dimensionIdOf(storedKey), regionX, regionZ);
    }

    // Client thread; the import job's handle, or null to stop taking imports.
    void drainWith(Handle handle) {
        clientThread = Thread.currentThread();
        if (handle != null) {
            importWrite.open();
        }
        drain = handle;
    }

    // Client thread; refuses every import still waiting.
    void stopImports() {
        drain = null;
        importWrite.stop();
        Import job = imports.poll();
        while (job != null) {
            if (job.state.compareAndSet(Import.WAITING, Import.DONE)) {
                job.failure = STOPPED;
                job.ready.countDown();
            }
            job = imports.poll();
        }
    }

    // Client thread; the body of the import job.
    Step importStep(Tick tick) {
        clientThread = Thread.currentThread();
        Import job = imports.poll();
        Step step;
        if (job == null) {
            rest();
            step = Step.YIELD;
        } else {
            take(job);
            step = Step.MORE;
        }
        return step;
    }

    // Client thread; imports dropped after their caller stopped waiting.
    long abandoned() {
        return abandoned;
    }

    private static Copy copy(GroundBatch batch, int regionX, int regionZ) {
        if (batch == null) {
            return new Copy(null, 0, NO_BATCH);
        }
        ChunkSample[] slots = new ChunkSample[REGION_CHUNKS];
        short[] heights = new short[ChunkSample.COLUMNS];
        byte[] cover = new byte[ChunkSample.COLUMNS];
        int entries = 0;
        String failure = null;
        try {
            entries = batch.size();
            if (entries < 0 || entries > REGION_CHUNKS) {
                failure = TOO_MANY;
            }
            for (int at = 0; failure == null && at < entries; at++) {
                failure = copyInto(slots, batch.chunk(at), regionX, regionZ, heights, cover);
            }
        } catch (RuntimeException unreadable) {
            failure = UNREADABLE;
        }
        return failure == null ? new Copy(slots, filled(slots), null) : new Copy(null, 0, failure);
    }

    // The chunks the slots hold: 1 for each chunk, however many entries the batch gave it.
    private static int filled(ChunkSample[] slots) {
        int chunks = 0;
        for (ChunkSample slot : slots) {
            if (slot != null) {
                chunks++;
            }
        }
        return chunks;
    }

    // Keeps the better of 2 entries for one chunk; null when the chunk was taken.
    private static String copyInto(ChunkSample[] slots, GroundChunk chunk, int regionX, int regionZ,
            short[] heights, byte[] cover) {
        if (chunk == null) {
            return EMPTY_ENTRY;
        }
        if (MapRegion.of(chunk.chunkX()) != regionX || MapRegion.of(chunk.chunkZ()) != regionZ) {
            return OUTSIDE;
        }
        ChunkSample copied = MapStorage.copyOf(chunk, heights, cover);
        int slot = MapStore.regionSlot(copied.chunkX, copied.chunkZ);
        ChunkSample held = slots[slot];
        if (held == null || MapStorage.outranks(copied, held)) {
            slots[slot] = copied;
        }
        return null;
    }

    private String refusal(Copy copy, String dimensionId, Path at) {
        String refusal;
        if (copy.failure() != null) {
            refusal = copy.failure();
        } else if (Thread.currentThread() == clientThread) {
            refusal = ON_CLIENT_THREAD;
        } else if (dimensionId == null || dimensionId.isBlank()) {
            refusal = NO_DIMENSION;
        } else if (at == null) {
            refusal = NO_WORLD;
        } else {
            refusal = null;
        }
        return refusal;
    }

    private ImportResult queued(Import job, Copy copy, long timeoutNanos) {
        if (!offer(job)) {
            return refused(NOT_RUNNING);
        }
        long started = System.nanoTime();
        long budget = Math.max(0L, timeoutNanos);
        String failure = awaitPrepared(job, budget);
        return failure == null ? written(job, copy, budget - (System.nanoTime() - started)) : refused(failure);
    }

    // The caller's thread; the write runs on the writer's thread, and the caller waits for it at most leftNanos.
    private ImportResult written(Import job, Copy copy, long leftNanos) {
        CollectorImportWrite.Write write =
                new CollectorImportWrite.Write(job.region, copy.slots(), () -> writtenOnDisk(job));
        return switch (importWrite.start(write)) {
            case TAKEN -> answered(write, copy, leftNanos);
            case FULL -> refused(BUSY);
            case CLOSED -> refused(STOPPED);
        };
    }

    // The caller's thread; a write still running when the wait ends can land later.
    private static ImportResult answered(CollectorImportWrite.Write write, Copy copy, long leftNanos) {
        boolean ended;
        boolean interrupted = false;
        try {
            ended = write.await(leftNanos);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            ended = false;
            interrupted = true;
        }
        ImportResult result;
        if (!ended) {
            result = refused(interrupted ? WRITE_STOPPED : WRITE_TIMED_OUT);
        } else if (write.landed()) {
            result = new ImportResult(write.written(), copy.chunks() - write.written(), null);
        } else {
            Exception failed = write.failed();
            result = refused("Could not write the region file ("
                    + (failed == null ? "Error" : failed.getClass().getSimpleName()) + "). Nothing was written.");
        }
        return result;
    }

    // The writer's thread; the client thread folds the written region in its next step.
    private void writtenOnDisk(Import job) {
        job.state.set(Import.LANDED);
        if (!offer(job)) {
            job.state.set(Import.DONE);
        }
    }

    private static ImportResult refused(String failure) {
        return new ImportResult(0, 0, failure);
    }

    // Any thread; false while the collector's import job is not registered.
    private boolean offer(Import job) {
        Handle held = drain;
        if (held == null) {
            return false;
        }
        imports.add(job);
        held.setPaused(false);
        return true;
    }

    // The caller's thread; null once the client thread has prepared the region.
    private static String awaitPrepared(Import job, long timeoutNanos) {
        boolean ready;
        boolean interrupted = false;
        try {
            ready = job.ready.await(Math.max(0L, timeoutNanos), TimeUnit.NANOSECONDS);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            ready = false;
            interrupted = true;
        }
        String failure;
        if (ready) {
            failure = job.failure;
        } else {
            job.state.set(Import.ABANDONED);
            failure = interrupted ? INTERRUPTED : TIMED_OUT;
        }
        return failure;
    }

    // Client thread; the queue was found empty.
    private void rest() {
        Handle held = drain;
        if (held == null) {
            return;
        }
        held.setPaused(true);
        if (!imports.isEmpty()) {
            held.setPaused(false);
        }
    }

    private void take(Import job) {
        if (job.state.get() == Import.LANDED) {
            land(job);
        } else if (job.state.compareAndSet(Import.WAITING, Import.PREPARING)) {
            prepare(job);
        } else {
            abandoned++;
        }
    }

    private void prepare(Import job) {
        MapStorage.RegionImport region = null;
        String failure;
        try {
            if (!Objects.equals(job.world, world.worldFolder())) {
                failure = WORLD_CHANGED;
            } else {
                region = storage.prepareImport(job.storedKey, job.regionX, job.regionZ);
                failure = region == null ? NO_FOLDER : null;
            }
        } catch (RuntimeException crashed) {
            LOGGER.warn("The collector could not take an import of {} region {},{}.", job.dimensionId,
                    job.regionX, job.regionZ, crashed);
            region = null;
            failure = PREPARE_FAILED;
        }
        job.region = region;
        job.failure = failure;
        job.state.set(failure == null ? Import.PREPARED : Import.DONE);
        job.ready.countDown();
    }

    private void land(Import job) {
        job.state.set(Import.DONE);
        if (Objects.equals(job.world, world.worldFolder())) {
            boolean open = storage.landImport(job.region);
            if (open) {
                regionWritten(job.dimensionId, job.regionX, job.regionZ);
            }
        }
    }

    private String dimensionIdOf(String storedKey) {
        String id = dimensionIds.get(storedKey);
        if (id == null) {
            if (dimensionIds.size() >= MAX_DIMENSIONS) {
                dimensionIds.clear();
            }
            id = MapStorage.dimensionIdOf(storedKey);
            dimensionIds.put(storedKey, id);
        }
        return id;
    }

    private void regionWritten(String dimensionId, int regionX, int regionZ) {
        for (GroundListener listener : listeners) {
            try {
                listener.regionWritten(dimensionId, regionX, regionZ);
            } catch (RuntimeException failed) {
                sayFailure(failed);
            }
        }
    }

    private void sayFailure(RuntimeException failed) {
        if (failureSaid) {
            LOGGER.debug("A ground listener failed again.", failed);
        } else {
            failureSaid = true;
            LOGGER.warn("A ground listener failed. The collector continues.", failed);
        }
    }

    private void remove(GroundListener listener) {
        GroundListener[] held = listeners;
        int at = held.length - 1;
        while (at >= 0 && held[at] != listener) {
            at--;
        }
        if (at >= 0) {
            GroundListener[] shrunk = new GroundListener[held.length - 1];
            System.arraycopy(held, 0, shrunk, 0, at);
            System.arraycopy(held, at + 1, shrunk, at, held.length - at - 1);
            listeners = shrunk;
        }
    }
}
