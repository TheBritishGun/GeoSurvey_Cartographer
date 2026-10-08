package dev.openmap.json;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public final class AtomicFileReplace {

    public static final Turn ALWAYS = () -> true;

    private static final int FIRST_LOCKS = 16;

    private static final int GROWTH = 2;

    private static final int MOST_REFUSALS = 100;

    private static final long PATIENCE_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private static final int LONGEST_PAUSE_SHIFT = 4;

    private static final int LONGEST_DOT_NAME = 2;

    private static final String ASIDE_SUFFIX = ".replacing";

    private static final CopyOption[] ATOMIC_MOVE_OPTIONS = {
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
    };

    private static final CopyOption[] MOVE_OPTIONS = {};

    private static final Object LOCKS_GUARD = new Object();

    private static final ThreadLocal<int[]> NO_WAIT_SCOPES = ThreadLocal.withInitial(() -> new int[1]);

    @FunctionalInterface
    public interface Body {

        void writeTo(Path temp) throws IOException;
    }

    // Both run under the file's lock.
    public interface Turn {

        boolean stillWanted();

        default void landed() {
        }
    }

    public enum Moved {
        ATOMICALLY,
        WITHOUT_ATOMIC_MOVE,
        UNWANTED
    }

    public static final class FileLock {

        private String name;

        private int users;

        private FileLock() {
        }
    }

    public abstract static class Refused extends FileSystemException {

        private static final long serialVersionUID = 1L;

        protected Refused(String file, String other, String reason) {
            super(file, other, reason);
        }
    }

    public static final class Unresolved extends IOException {

        private static final long serialVersionUID = 1L;

        private Unresolved(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class RefusedWithoutWaiting extends Refused {

        private static final long serialVersionUID = 1L;

        private RefusedWithoutWaiting(Path file, IOException refused) {
            super(file.toString(), null, "the replace was refused, not retried");
            initCause(refused);
        }
    }

    // Guarded by LOCKS_GUARD.
    private static FileLock[] locks = new FileLock[FIRST_LOCKS];

    private AtomicFileReplace() {
    }

    public static void write(Path temp, Path target, Body body) throws IOException {
        writeTemp(temp, body);
        replace(temp, target, true, ALWAYS);
    }

    public static void writeTemp(Path temp, Body body) throws IOException {
        try {
            body.writeTo(temp);
        } catch (IOException failure) {
            throw dropped(temp, failure);
        } catch (RuntimeException failure) {
            throw dropped(temp, failure);
        }
    }

    public static <T extends Exception> T dropped(Path temp, T failed) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException | RuntimeException undeletable) {
            failed.addSuppressed(undeletable);
        }
        return failed;
    }

    public static Moved replace(Path temp, Path file, boolean atomic, Turn turn) throws IOException {
        long deadline = System.nanoTime() + PATIENCE_NANOS;
        int refusals = 0;
        Moved moved = null;
        while (moved == null) {
            IOException refused = null;
            RuntimeException broken = null;
            FileLock lock = take(file);
            try {
                synchronized (lock) {
                    if (turn.stillWanted()) {
                        try {
                            moved = move(temp, file, atomic);
                            turn.landed();
                        } catch (IOException thrown) {
                            refused = thrown;
                        } catch (RuntimeException thrown) {
                            broken = thrown;
                        }
                    } else {
                        moved = Moved.UNWANTED;
                    }
                }
            } finally {
                give(lock);
            }
            if (broken != null) {
                throw dropped(temp, broken);
            }
            if (refused != null) {
                refusals++;
                pauseOrThrow(temp, file, refused, refusals, deadline);
            }
        }
        if (moved == Moved.UNWANTED) {
            Files.deleteIfExists(temp);
        }
        return moved;
    }

    // The caller holds file's lock.
    public static void replaceHeld(Path temp, Path file) throws IOException {
        long deadline = System.nanoTime() + PATIENCE_NANOS;
        int refusals = 0;
        boolean landed = false;
        while (!landed) {
            try {
                move(temp, file, true);
                landed = true;
            } catch (IOException refused) {
                refusals++;
                pauseOrThrow(temp, file, refused, refusals, deadline);
            } catch (RuntimeException broken) {
                throw dropped(temp, broken);
            }
        }
    }

    public static void settle(Path file, Turn turn) {
        FileLock lock = take(file);
        try {
            synchronized (lock) {
                if (turn.stillWanted()) {
                    turn.landed();
                }
            }
        } finally {
            give(lock);
        }
    }

    public static FileLock take(Path file) {
        String name = nameOf(file);
        FileLock lock;
        synchronized (LOCKS_GUARD) {
            lock = held(name);
            if (lock == null) {
                lock = free();
                lock.name = name;
            }
            lock.users++;
        }
        return lock;
    }

    public static void give(FileLock lock) {
        synchronized (LOCKS_GUARD) {
            lock.users--;
            if (lock.users == 0) {
                lock.name = null;
            }
        }
    }

    public static void beginNoWait() {
        NO_WAIT_SCOPES.get()[0]++;
    }

    public static void endNoWait() {
        NO_WAIT_SCOPES.get()[0]--;
    }

    public static boolean recoverStaleAside(Path target) throws IOException {
        Path aside = asideFor(target);
        boolean parked = Files.exists(aside);
        if (parked) {
            try {
                Files.move(aside, target, MOVE_OPTIONS);
            } catch (FileAlreadyExistsException | NoSuchFileException alreadyRecovered) {
                if (!Files.exists(target)) {
                    throw alreadyRecovered;
                }
            }
        }
        return parked;
    }

    private static FileLock held(String name) {
        FileLock found = null;
        for (int at = 0; found == null && at < locks.length; at++) {
            FileLock lock = locks[at];
            if (lock != null && lock.users > 0 && name.equalsIgnoreCase(lock.name)) {
                found = lock;
            }
        }
        return found;
    }

    private static FileLock free() {
        FileLock found = null;
        int at = 0;
        while (found == null && at < locks.length) {
            if (locks[at] == null) {
                locks[at] = new FileLock();
            }
            if (locks[at].users == 0) {
                found = locks[at];
            }
            at++;
        }
        if (found == null) {
            locks = Arrays.copyOf(locks, locks.length * GROWTH);
            found = new FileLock();
            locks[at] = found;
        }
        return found;
    }

    private static String nameOf(Path file) {
        String spelled = file.toString();
        return namesADot(spelled) ? file.normalize().toString() : spelled;
    }

    private static boolean namesADot(String spelled) {
        boolean dotted = false;
        int start = 0;
        int end = spelled.length();
        for (int at = 0; at <= end && !dotted; at++) {
            if (at == end || isSeparator(spelled.charAt(at))) {
                int length = at - start;
                dotted = length > 0 && length <= LONGEST_DOT_NAME
                        && spelled.charAt(start) == '.' && spelled.charAt(at - 1) == '.';
                start = at + 1;
            }
        }
        return dotted;
    }

    private static boolean isSeparator(char c) {
        return c == '/' || c == '\\';
    }

    private static Moved move(Path temp, Path file, boolean atomic) throws IOException {
        Moved moved = atomic ? atomicMove(temp, file) : Moved.WITHOUT_ATOMIC_MOVE;
        if (moved == Moved.WITHOUT_ATOMIC_MOVE) {
            Path aside = asideFor(file);
            reconcileStaleAside(file, aside);
            fallbackMove(temp, file, aside);
        }
        return moved;
    }

    private static Moved atomicMove(Path temp, Path file) throws IOException {
        Moved moved = Moved.ATOMICALLY;
        try {
            Files.move(temp, file, ATOMIC_MOVE_OPTIONS);
        } catch (AtomicMoveNotSupportedException noAtomicMove) {
            moved = Moved.WITHOUT_ATOMIC_MOVE;
        }
        return moved;
    }

    private static void reconcileStaleAside(Path target, Path aside) throws IOException {
        if (Files.exists(target)) {
            Files.deleteIfExists(aside);
        } else if (Files.exists(aside)) {
            Files.move(aside, target, MOVE_OPTIONS);
        }
    }

    private static void fallbackMove(Path temp, Path target, Path aside) throws IOException {
        if (Files.exists(target)) {
            Files.move(target, aside, MOVE_OPTIONS);
            try {
                Files.move(temp, target, MOVE_OPTIONS);
            } catch (IOException couldNotLand) {
                try {
                    Files.move(aside, target, MOVE_OPTIONS);
                } catch (IOException couldNotRestore) {
                    couldNotLand.addSuppressed(couldNotRestore);
                    throw new Unresolved("could not replace " + target + ": restoring"
                            + " from " + aside + " failed; " + oldFileAt(target, aside)
                            + " The new file is at " + temp + "; neither was touched.", couldNotLand);
                }
                throw couldNotLand;
            }
            try {
                Files.deleteIfExists(aside);
            } catch (IOException | RuntimeException leftForTheNextFallback) {
            }
        } else {
            Files.move(temp, target, MOVE_OPTIONS);
        }
    }

    private static Path asideFor(Path target) {
        return target.resolveSibling(target.getFileName() + ASIDE_SUFFIX);
    }

    private static String oldFileAt(Path target, Path aside) {
        String sentence;
        if (Files.exists(aside)) {
            sentence = "the old file is at " + aside + ".";
        } else if (Files.exists(target)) {
            sentence = "the old file is at " + target + ".";
        } else {
            sentence = "the old file is at neither " + aside + " nor " + target + ".";
        }
        return sentence;
    }

    private static void pauseOrThrow(Path temp, Path file, IOException refused, int refusals, long deadline)
            throws IOException {
        if (refused instanceof Unresolved) {
            throw refused;
        }
        if (!worthAnotherTry(refused, file, refusals, deadline)) {
            throw dropped(temp, refused);
        }
        if (NO_WAIT_SCOPES.get()[0] > 0) {
            throw dropped(temp, new RefusedWithoutWaiting(file, refused));
        }
        pause(refusals);
    }

    private static boolean worthAnotherTry(IOException refused, Path file, int refusals, long deadline) {
        return refusals < MOST_REFUSALS && System.nanoTime() - deadline < 0L
                && !(refused instanceof NoSuchFileException)
                && !Thread.currentThread().isInterrupted() && !Files.isDirectory(file);
    }

    private static void pause(int refusals) {
        try {
            Thread.sleep(1L << Math.min(refusals - 1, LONGEST_PAUSE_SHIFT));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
