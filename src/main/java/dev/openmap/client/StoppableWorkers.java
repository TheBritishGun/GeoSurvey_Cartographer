package dev.openmap.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StoppableWorkers {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final StoppableWorkers LIVE = new StoppableWorkers();

    interface Registration {

        void close();
    }

    private interface Worker {

        String name();

        void requestStop();

        boolean await(long boundMillis);

        boolean isLive();
    }

    private record ThreadWorker(String name, Thread worker) implements Worker {

        @Override
        public void requestStop() {
            worker.interrupt();
        }

        @Override
        public boolean await(long boundMillis) {
            Thread.State state = worker.getState();
            if (state == Thread.State.NEW) {
                return false;
            }
            if (boundMillis == 0L) {
                return !worker.isAlive();
            }
            if (state == Thread.State.TERMINATED) {
                return true;
            }
            try {
                worker.join(boundMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return !worker.isAlive();
        }

        @Override
        public boolean isLive() {
            return worker.getState() != Thread.State.TERMINATED;
        }
    }

    private record ExecutorWorker(String name, ExecutorService worker) implements Worker {

        @Override
        public void requestStop() {
            worker.shutdownNow();
        }

        @Override
        public boolean await(long boundMillis) {
            if (boundMillis == 0L) {
                return worker.isTerminated();
            }
            if (worker.isTerminated()) {
                return true;
            }
            boolean ended;
            try {
                ended = worker.awaitTermination(boundMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                ended = false;
            }
            return ended;
        }

        @Override
        public boolean isLive() {
            return !worker.isTerminated();
        }
    }

    private record CloseWorker(String name, Runnable requester, LongPredicate awaiter,
                               BooleanSupplier ended) implements Worker {

        @Override
        public void requestStop() {
            requester.run();
        }

        @Override
        public boolean await(long boundMillis) {
            return awaiter.test(boundMillis);
        }

        @Override
        public boolean isLive() {
            return !ended.getAsBoolean();
        }
    }

    private static volatile StoppableWorkers fallbackForTests;

    private final Object workersLock = new Object();

    // workersLock guards workers and stopping.
    private List<Worker> workers = new ArrayList<>();

    private boolean stopping;

    StoppableWorkers() {
    }

    static void thread(String name, Thread worker) {
        live().trackThread(name, worker);
    }

    static Registration executor(String name, ExecutorService worker) {
        return live().trackExecutor(name, worker);
    }

    static Registration close(String name, Runnable requester, LongPredicate awaiter,
                              BooleanSupplier ended) {
        return live().trackClose(name, requester, awaiter, ended);
    }

    static void stopAll(long boundMillis) {
        live().stop(boundMillis);
    }

    static void runWithFallbackForced(StoppableWorkers workers, Runnable test) {
        fallbackForTests = workers;
        try {
            test.run();
        } finally {
            fallbackForTests = null;
        }
    }

    private static StoppableWorkers live() {
        StoppableWorkers fallback = fallbackForTests;
        return fallback == null ? LIVE : fallback;
    }

    void trackThread(String name, Thread worker) {
        insert(new ThreadWorker(name, worker));
    }

    Registration trackExecutor(String name, ExecutorService worker) {
        return add(new ExecutorWorker(name, worker));
    }

    Registration trackClose(String name, Runnable requester, LongPredicate awaiter,
                            BooleanSupplier ended) {
        return add(new CloseWorker(name, requester, awaiter, ended));
    }

    private Registration add(Worker worker) {
        insert(worker);
        return () -> remove(worker);
    }

    private void insert(Worker worker) {
        boolean stopNow;
        synchronized (workersLock) {
            stopNow = stopping;
            if (!stopNow) {
                pruneDeadWorkers();
                workers.add(worker);
            }
        }
        if (stopNow) {
            stopLateWorker(worker);
        }
    }

    private void pruneDeadWorkers() {
        int size = workers.size();
        int kept = 0;
        for (int at = 0; at < size; at++) {
            Worker held = workers.get(at);
            if (held.isLive()) {
                workers.set(kept, held);
                kept++;
            }
        }
        for (int at = size - 1; at >= kept; at--) {
            workers.remove(at);
        }
    }

    private void stopLateWorker(Worker worker) {
        boolean asked;
        try {
            worker.requestStop();
            asked = true;
        } catch (Throwable failed) {
            LOGGER.warn("Could not stop " + worker.name(), failed);
            asked = false;
        }
        if (asked) {
            LOGGER.warn("{} registered after teardown began", worker.name());
        }
    }

    private void remove(Worker worker) {
        synchronized (workersLock) {
            workers.remove(worker);
        }
    }

    void stop(long boundMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMillis);
        List<Worker> held;
        synchronized (workersLock) {
            if (stopping) {
                held = Collections.emptyList();
            } else {
                held = workers;
                stopping = true;
                workers = Collections.emptyList();
            }
        }
        for (Worker worker : held) {
            try {
                worker.requestStop();
            } catch (Throwable failed) {
                LOGGER.warn("Could not stop " + worker.name(), failed);
            }
        }
        for (Worker worker : held) {
            long remaining = remainingMillis(deadline);
            boolean ended;
            try {
                ended = worker.await(remaining);
            } catch (Throwable failed) {
                LOGGER.warn("Could not stop " + worker.name(), failed);
                ended = false;
            }
            if (!ended) {
                LOGGER.warn("{} did not stop within {} ms", worker.name(), boundMillis);
            }
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }
}
