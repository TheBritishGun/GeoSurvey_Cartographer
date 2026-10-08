package dev.openmap.client;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.MapStorage;
import dev.sandpaper.core.Background;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// Writes each imported region on a thread of its own; the importing thread waits for it with a bound.
final class CollectorImportWrite {

    private static final int WRITER_THREADS = 1;

    private static final int MAX_QUEUED = 8;

    private static final long IDLE_SECONDS = 30L;

    private static final String THREAD_PREFIX = "openmap-import-write-";

    enum Taken {
        TAKEN,
        CLOSED,
        FULL
    }

    // One region's write; afterWrite runs on the writer's thread once the file holds the import.
    static final class Write implements Runnable {

        private static final int NOT_WRITTEN = -1;

        private final MapStorage.RegionImport region;

        private final ChunkSample[] imported;

        private final Runnable afterWrite;

        private final CountDownLatch done = new CountDownLatch(1);

        // Set before done opens.
        private volatile int written = NOT_WRITTEN;

        // Set before done opens; null unless the write threw.
        private volatile Exception failed;

        Write(MapStorage.RegionImport region, ChunkSample[] imported, Runnable afterWrite) {
            this.region = region;
            this.imported = imported;
            this.afterWrite = afterWrite;
        }

        @Override
        public void run() {
            try {
                written = MapStorage.writeImport(region, imported);
            } catch (IOException | RuntimeException thrown) {
                failed = thrown;
            } finally {
                tell();
            }
        }

        // False when the time ran out first.
        boolean await(long nanos) throws InterruptedException {
            return done.await(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
        }

        boolean landed() {
            return written != NOT_WRITTEN;
        }

        int written() {
            return written;
        }

        // Null unless the write threw.
        Exception failed() {
            return failed;
        }

        // The client thread hears of a landed write before the importing thread does.
        private void tell() {
            try {
                if (landed()) {
                    afterWrite.run();
                }
            } finally {
                done.countDown();
            }
        }
    }

    private final Object lock = new Object();

    // Guarded by lock; null until a write needs it, and after stop.
    private ThreadPoolExecutor writer = null;

    // Guarded by lock.
    private boolean stopped = false;

    // Any thread but the client thread.
    Taken start(Write write) {
        ThreadPoolExecutor held;
        synchronized (lock) {
            if (!stopped && writer == null) {
                writer = newWriter();
            }
            held = stopped ? null : writer;
        }
        return held == null ? Taken.CLOSED : executed(held, write);
    }

    // Client thread; takes no more writes, and lets a write already taken land.
    void stop() {
        ThreadPoolExecutor held;
        synchronized (lock) {
            stopped = true;
            held = writer;
            writer = null;
        }
        if (held != null) {
            held.shutdown();
        }
    }

    // Client thread; takes writes again after a stop.
    void open() {
        synchronized (lock) {
            stopped = false;
        }
    }

    private static Taken executed(ThreadPoolExecutor held, Write write) {
        Taken taken;
        try {
            held.execute(write);
            taken = Taken.TAKEN;
        } catch (RejectedExecutionException refused) {
            taken = held.isShutdown() ? Taken.CLOSED : Taken.FULL;
        }
        return taken;
    }

    private static ThreadPoolExecutor newWriter() {
        ThreadPoolExecutor made = new ThreadPoolExecutor(WRITER_THREADS, WRITER_THREADS, IDLE_SECONDS,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_QUEUED), Background.factory(THREAD_PREFIX));
        made.allowCoreThreadTimeOut(true);
        return made;
    }
}
