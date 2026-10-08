package dev.openmap.json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class SaveWriter {
    public static final long SETTLE_NANOS = 700_000_000L;
    public static final long HOLD_NANOS = 2_000_000_000L;
    private static final int FINISH_ROUNDS = 3;
    private static final LinkOption[] FOLLOWING_LINKS = {};
    private static final SaveWriter LIVE = new SaveWriter();
    private static final Document REFUSED = temp -> {
    };

    public enum Timing {
        SOON, SETTLED
    }
    public interface Keeper {
        Path file();
        Document document() throws IOException;
        default void write(Document document) throws IOException {
            writeOne(file(), document);
        }
        default void landed(Document savedDocument) {
        }
    }
    @FunctionalInterface
    public interface Document {
        void writeTo(Path temp) throws IOException;
    }

    public interface Saving {
        Saving NONE = new Callbacks(() -> {
        }, why -> {
        });

        void landed();
        void failed(IOException why);

        static Saving of(Runnable onLanded, Failed onFailed) {
            return new Callbacks(Objects.requireNonNull(onLanded), Objects.requireNonNull(onFailed));
        }
    }

    @FunctionalInterface
    public interface Failed {
        void failed(IOException why);
    }

    @FunctionalInterface
    public interface Runner {
        boolean start(Runnable task);
    }

    private static final class Callbacks implements Saving {
        private final Runnable onLanded;
        private final Failed onFailed;

        private Callbacks(Runnable onLanded, Failed onFailed) {
            this.onLanded = Objects.requireNonNull(onLanded);
            this.onFailed = Objects.requireNonNull(onFailed);
        }

        @Override
        public void landed() {
            onLanded.run();
        }

        @Override
        public void failed(IOException why) {
            onFailed.failed(why);
        }
    }

    private static final class Handed {
        private final Keeper keeper;
        private final Path file;
        private final Document document;

        private Handed(Keeper keeper, Path file, Document document) {
            this.keeper = Objects.requireNonNull(keeper);
            this.file = Objects.requireNonNull(file);
            this.document = Objects.requireNonNull(document);
        }
    }

    private static final class Outcome {
        private final Path file;
        private final Document document;
        private final IOException failure;
        private Outcome(Path file, Document document, IOException failure) {
            this.file = Objects.requireNonNull(file);
            this.document = Objects.requireNonNull(document);
            this.failure = failure;
        }
    }

    private static final class Entry {
        private Keeper keeper;
        private ArrayList<Saving> waiting = new ArrayList<>();
        private ArrayList<Saving> flying = null;
        private Document flyingDocument = null;
        private long first = 0L;
        private long last = 0L;
        private boolean soon = false;

        private Entry(Keeper keeper) {
            this.keeper = keeper;
        }
    }

    private static final class Answer implements Saving {
        private final Saving delegate;
        private final SaveWriter owner;
        private final Path file;
        private boolean done = false;
        private boolean saved = false;
        private boolean expired = false;

        private Answer(Saving delegate, SaveWriter owner, Path file) {
            this.delegate = Objects.requireNonNull(delegate);
            this.owner = Objects.requireNonNull(owner);
            this.file = Objects.requireNonNull(file);
        }

        @Override
        public void landed() {
            done = true;
            saved = true;
            try {
                delegate.landed();
            } finally {
                finishedLate();
            }
        }

        @Override
        public void failed(IOException why) {
            done = true;
            try {
                delegate.failed(why);
            } finally {
                finishedLate();
            }
        }

        private void finishedLate() {
            if (expired) {
                owner.say("The write completed late: " + file);
            }
        }
    }
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>();
    private boolean delivering = false;
    private boolean synchronous = false;
    private final ConcurrentLinkedQueue<Handed> handed = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Outcome> outcomes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writing = new AtomicBoolean(false);
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final Semaphore writingReleased = new Semaphore(0);
    private volatile boolean waitingForWriting = false;
    private final Runnable task = this::runScheduled;
    private volatile Runner runner = null;
    private volatile Consumer<String> note = null;

    private SaveWriter() {
    }

    public static SaveWriter live() {
        return LIVE;
    }
    public void runner(Runner nextRunner) {
        runner = nextRunner;
    }
    public void note(Consumer<String> nextNote) {
        note = nextNote;
    }

    public void changed(Keeper changedKeeper, Saving saving, Timing timing) {
        long nowNanos = System.nanoTime();
        record(changedKeeper, saving, timing, nowNanos);
        if (runner == null && !delivering && !synchronous) {
            for (int round = 0; round < FINISH_ROUNDS && !entries.isEmpty(); round++) {
                flush(nowNanos);
            }
        }
    }

    private void record(Keeper changedKeeper, Saving saving, Timing timing, long nowNanos) {
        Objects.requireNonNull(saving);
        Objects.requireNonNull(timing);
        Path file = Objects.requireNonNull(changedKeeper.file());
        Entry entry = entries.get(file);
        if (entry == null) {
            entry = new Entry(changedKeeper);
            entries.put(file, entry);
        }
        entry.keeper = changedKeeper;
        if (entry.waiting.isEmpty()) {
            entry.first = nowNanos;
        }
        entry.last = nowNanos;
        entry.waiting.add(saving);
        entry.soon |= timing == Timing.SOON;
    }

    public void beat(long nowNanos) {
        if (entries.isEmpty()) {
            return;
        }
        deliver();
        handOverDue(nowNanos, false);
        kick();
        deliver();
    }

    public void flush(long nowNanos) {
        if (entries.isEmpty()) {
            return;
        }
        deliver();
        handOverDue(nowNanos, true);
        kick();
        deliver();
    }

    private void handOverDue(long nowNanos, boolean all) {
        for (Entry entry : entries.values()) {
            if (all) {
                entry.soon = true;
            }
            boolean due = entry.soon || (nowNanos - entry.last >= SETTLE_NANOS)
                    || (nowNanos - entry.first >= HOLD_NANOS);
            if (entry.flying == null && !entry.waiting.isEmpty() && due) {
                handOver(entry);
            }
        }
    }

    private void handOver(Entry entry) {
        entry.flying = entry.waiting;
        entry.waiting = new ArrayList<>();
        entry.soon = false;
        try {
            entry.flyingDocument = Objects.requireNonNull(entry.keeper.document());
            handed.add(new Handed(entry.keeper, entry.keeper.file(), entry.flyingDocument));
        } catch (IOException failure) {
            refuse(entry, failure);
        } catch (RuntimeException failure) {
            refuse(entry, new IOException(failure));
        }
    }

    private void refuse(Entry entry, IOException failure) {
        entry.flyingDocument = REFUSED;
        outcomes.add(new Outcome(entry.keeper.file(), REFUSED, failure));
    }

    private void kick() {
        if (handed.isEmpty() || writing.get()) {
            return;
        }
        Runner selected = runner;
        if (selected == null) {
            drain();
        } else if (scheduled.compareAndSet(false, true)) {
            boolean accepted;
            try {
                accepted = selected.start(task);
            } catch (RuntimeException failure) {
                say("Cannot start the save writer: " + failure);
                accepted = false;
            }
            if (!accepted) {
                scheduled.set(false);
                drain();
            }
        } else {
        }
    }

    private void runScheduled() {
        scheduled.set(false);
        drain();
    }

    public void droppedUnrun() {
        scheduled.set(false);
    }
    public void drain() {
        boolean pending = !handed.isEmpty();
        while (pending) {
            if (!takeWriting()) {
                break;
            }
            try {
                drainTaken();
            } finally {
                pending = releaseWriting();
            }
        }
    }

    private boolean takeWriting() {
        return writing.compareAndSet(false, true);
    }

    private boolean releaseWriting() {
        writing.set(false);
        if (waitingForWriting) {
            writingReleased.release();
        }
        return !handed.isEmpty();
    }

    private void drainTaken() {
        Handed next = handed.poll();
        while (next != null) {
            write(next);
            next = handed.poll();
        }
    }

    private void write(Handed next) {
        try {
            next.keeper.write(next.document);
            outcomes.add(new Outcome(next.file, next.document, null));
        } catch (IOException failure) {
            outcomes.add(new Outcome(next.file, next.document, failure));
        } catch (RuntimeException failure) {
            outcomes.add(new Outcome(next.file, next.document, new IOException(failure)));
        } catch (Error failure) {
            outcomes.add(new Outcome(next.file, next.document,
                    new IOException("the save writer stopped: " + failure, failure)));
            throw failure;
        }
    }

    private void deliver() {
        if (delivering) {
            return;
        }
        delivering = true;
        try {
            Outcome outcome = outcomes.poll();
            while (outcome != null) {
                complete(outcome);
                outcome = outcomes.poll();
            }
        } finally {
            delivering = false;
        }
    }

    private void complete(Outcome outcome) {
        Entry entry = entries.get(outcome.file);
        if (entry == null || entry.flyingDocument != outcome.document) {
            return;
        }
        ArrayList<Saving> carried = entry.flying;
        ArrayList<Saving> newer = entry.waiting;
        Keeper owner = entry.keeper;
        entry.flying = null;
        entry.flyingDocument = null;
        if (outcome.failure != null || newer.isEmpty()) {
            entries.remove(outcome.file);
        }
        if (outcome.failure == null) {
            try {
                owner.landed(outcome.document);
            } catch (RuntimeException failure) {
                say("A save landing hook failed for " + outcome.file + ": " + failure);
            }
            for (Saving saving : carried) {
                tell(saving, null, outcome.file);
            }
        } else {
            failNewest(newer, outcome.failure, outcome.file);
            failNewest(carried, outcome.failure, outcome.file);
        }
    }

    private void failNewest(ArrayList<Saving> savings, IOException failure, Path file) {
        for (int index = savings.size() - 1; index >= 0; index--) {
            tell(savings.get(index), failure, file);
        }
    }
    private void tell(Saving saving, IOException failure, Path file) {
        try {
            if (failure == null) {
                saving.landed();
            } else {
                saving.failed(failure);
            }
        } catch (RuntimeException callbackFailure) {
            say("A save result handler failed for " + file + ": " + callbackFailure);
        }
    }

    public boolean finish(long nowNanos, long boundNanos) {
        if (delivering) {
            say("Cannot finish the save writer inside a result handler");
            return false;
        }
        runner = null;
        long started = System.nanoTime();
        boolean finished = settle(nowNanos, started, boundNanos, null);
        if (!finished) {
            unconfirmed();
        }
        return finished;
    }

    public boolean saveNow(Keeper changedKeeper, Saving saving, long boundNanos) {
        if (delivering) {
            say("Cannot saveNow inside a save result handler: " + changedKeeper.file());
            return false;
        }
        long started = System.nanoTime();
        boolean acquired = takeBefore(started, boundNanos);
        boolean saved = false;
        if (acquired) {
            releaseWriting();
            Answer answer = new Answer(saving, this, changedKeeper.file());
            synchronous = true;
            try {
                record(changedKeeper, answer, Timing.SOON, System.nanoTime());
                handOverSince(started);
            } finally {
                synchronous = false;
            }
            boolean settled = settle(System.nanoTime(), started, boundNanos, answer);
            saved = answer.done && answer.saved;
            if (!settled) {
                answer.expired = !answer.done;
                say("Save caller stopped waiting: " + changedKeeper.file());
            }
        }
        return saved;
    }
    private boolean settle(long nowNanos, long started, long boundNanos, Answer answer) {
        int rounds = 0;
        deliver();
        boolean pending = unsettledSince(started, answer);
        while (pending && (rounds < FINISH_ROUNDS) && within(started, boundNanos)) {
            if (answer == null) {
                handOverDue(nowNanos, true);
            } else {
                handOverSince(started);
            }
            if (!takeBefore(started, boundNanos)) {
                break;
            }
            synchronous = true;
            try {
                drainTaken();
            } finally {
                releaseWriting();
                synchronous = false;
            }
            deliver();
            rounds++;
            pending = unsettledSince(started, answer);
        }
        deliver();
        return !unsettledSince(started, answer) && ((answer == null) || answer.done);
    }

    private void handOverSince(long started) {
        for (Entry entry : entries.values()) {
            if ((entry.last - started >= 0L) && (entry.flying == null) && !entry.waiting.isEmpty()) {
                handOver(entry);
            }
        }
    }
    private boolean unsettledSince(long started, Answer answer) {
        if (answer == null) {
            return !entries.isEmpty();
        }
        if (!handed.isEmpty()) {
            return true;
        }
        if (entries.isEmpty()) {
            return false;
        }
        boolean pending = false;
        for (Entry entry : entries.values()) {
            if (entry.last - started >= 0L) {
                pending = true;
                break;
            }
        }
        return pending;
    }

    private boolean takeBefore(long started, long boundNanos) {
        boolean acquired = false;
        writingReleased.drainPermits();
        waitingForWriting = true;
        try {
            while (within(started, boundNanos)) {
                deliver();
                acquired = takeWriting();
                if (acquired || Thread.currentThread().isInterrupted()) {
                    break;
                }
                long remaining = Math.max(0L, boundNanos - (System.nanoTime() - started));
                try {
                    boolean signalled = writingReleased.tryAcquire(remaining, TimeUnit.NANOSECONDS);
                    if (!signalled) {
                        break;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            waitingForWriting = false;
        }
        return acquired;
    }

    private static boolean within(long started, long boundNanos) {
        return boundNanos > 0L && (System.nanoTime() - started < boundNanos);
    }
    public Keeper heldFor(Path file) {
        Entry entry = entries.get(file);
        return entry == null ? null : entry.keeper;
    }

    private void unconfirmed() {
        for (Path file : entries.keySet()) {
            say("Save not confirmed before shutdown: " + file);
        }
    }

    private void say(String message) {
        Consumer<String> sink = note;
        if (sink != null) {
            try {
                sink.accept(message);
            } catch (RuntimeException failedNote) {
            }
        }
    }

    public static void writeOne(Path target, Document savedDocument) throws IOException {
        Path parent = target.getParent();
        if (parent != null && !Files.isDirectory(parent, FOLLOWING_LINKS)) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        AtomicFileReplace.write(temp, target, savedDocument::writeTo);
    }
}
