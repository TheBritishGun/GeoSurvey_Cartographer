package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.map.MapRegion;
import dev.openmap.map.MapStorage;
import dev.openmap.share.SharedRecord;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class Claims {

    private static final long COMMAND_WAIT_MILLIS = 30_000L;

    private static final long NO_RESERVATION = -1L;

    private static final long NAMES_REFRESH_MILLIS = 250L;

    private static final long NEVER_REFRESHED = Long.MIN_VALUE;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final long UUID_CLEAR_VERSION = 0xFFFFFFFFFFFF0FFFL;

    private static final long UUID_VERSION_4 = 0x0000000000004000L;

    private static final long UUID_CLEAR_VARIANT = 0x3FFFFFFFFFFFFFFFL;

    private static final long UUID_IETF_VARIANT = 0x8000000000000000L;

    private static final int BOUNDS_MIN_X = 0;

    private static final int BOUNDS_MIN_Z = 1;

    private static final int BOUNDS_MAX_X = 2;

    private static final int BOUNDS_MAX_Z = 3;

    private static final int NO_SUCH_CLAIM_CHARS = 64;

    private static final int CHARS_PER_LISTED_NAME = 24;

    private static final int NAMES_LISTED = 8;

    private static final char LINE_BREAK = '\n';

    private static final MarkerColour[] COLOURS = MarkerColour.values();

    private static final LoweredColour[] LOWERED = loweredColours();

    private static final Said STARTED = new Said(true, "");

    private static final Said OUTSIDE_WORLD_BORDER = new Said(false,
            "That corner is outside the"
                    + " world border.");

    private static final Said NEEDS_NEW_NAME = new Said(false,
            "Use /geosurvey claim rename"
                    + " <name> <new name>.");

    private static final Said NEEDS_CLAIM_NAME = new Said(false,
            "A claim needs a name. Use /geosurvey"
                    + " claim start <name>.");

    private static final Said NOTHING_DRAWN_TO_FINISH = new Said(false,
            "No boundary is being drawn. Start one"
                    + " with /geosurvey claim start <name>.");

    private static final Said NOTHING_DRAWN_TO_UNDO = new Said(false,
            "No boundary is being"
                    + " drawn.");

    private static final Said NOTHING_DRAWN_TO_CANCEL = new Said(false,
            "No boundary is being drawn.");

    private static final Said NOTHING_DRAWN_TO_RECOLOUR = new Said(false,
            "No boundary is being drawn. Change a"
                    + " finished claim with /geosurvey claim colour <colour> <name>.");

    private static final Said NO_WORLD_SAID = new Said(false,
            "No world is open. Nothing was"
                    + " written.");

    private static final String PORTED_FROM = " from the retired claims book into this world.";

    private static final String SAVED = " Saved.";

    private static final String ON_ITS_WAY = " Queued for the collector.";

    private static final String NODE_NOT_TOLD = " The collector was not told.";

    private static final String NODE_NOT_TOLD_YET = " The collector is not told yet.";

    private static final String ENTRIES_LOADING = "The shared claims are loading."
            + " Nothing was ported.";

    private static final Said NEEDS_COLLECTOR = new Said(false,
            "Set a collector first: /geosurvey collector <address>.");

    private static final SharedFrom NO_SHARED_ENTRIES = claimId -> null;

    private static final Said CORNER_REPEATED = new Said(false,
            "You have not moved since the last"
                    + " corner. Walk to the next turn.");

    public record Standing(String dimension, double x, double z,
                           String player, String playerId) {

        public Standing {
            dimension = dimension == null ? "" : dimension.trim();
            player = player == null ? "" : player.trim();
            playerId = playerId == null ? "" : playerId.trim();
        }
    }

    public record Said(boolean ok, String text) {
    }

    // Whether a save sent a shared claim to the collector; its answer is said later.
    public enum Sent { NOTHING, TO_NODE, NOT_TOLD, HELD }

    // What the collector answered for a claim a command sent; NONE is no answer in time.
    public enum NodeAnswer { SAVED, REFUSED, NONE, NOT_SHARING, FULL, NOT_TOLD_TO_DROP }

    // Whether a claim can be a shared record, and if not, what of it the record refuses.
    public enum Shareable {
        YES(""),
        OWNER_NAME("its owner name is not 1 to " + SharedRecord.MAX_NAME + " letters, digits or _"),
        CORNER_COUNT("its corner count is not " + SharedRecord.MIN_CORNERS + " to " + SharedRecord.MAX_CORNERS),
        CORNER("a corner is not a whole block"),
        ID("its id is not 1 to " + SharedRecord.MAX_ID + " letters, digits, dots, dashes, colons or _"),
        NAME("its name has no character a map can show"),
        WORLD("its world is not a dimension id like minecraft:overworld");

        private final String wrong;

        Shareable(String wrong) {
            this.wrong = wrong;
        }
    }

    // Told the file a book was saved to, and the book; answers what the save sent.
    @FunctionalInterface
    public interface BookSaved {

        Sent saved(Path file, ClaimBook book);
    }

    // What the book that last saved an entry did with its claim.
    public enum Kept { SHARED, UNSHARED, REMOVED }

    // The book a shared claim's entry was last saved from, and what that book did with the claim.
    public record SharedEntry(String book, Kept kept) {
    }

    // The entries of the claims sent to the collector.
    @FunctionalInterface
    public interface SharedFrom {

        // Null when no world's book is known for the claim.
        SharedEntry entryOf(String claimId);

        // False while another thread first reads the entries.
        default boolean ready() {
            return true;
        }

        // Keeps a world's one-time port, to run once the entries are ready; asked only while they are not.
        default void holdPort(Path book, Supplier<Said> port) {
        }
    }

    @FunctionalInterface
    public interface SaveFailureSink {

        void failed(IOException couldNotWrite);
    }

    public enum Outcome {
        ANSWERED_OK,
        ANSWERED_REFUSAL,
        ANSWER_STILL_QUEUED
    }

    private enum CommandState {
        IDLE,
        WAITING,
        EXPIRED
    }

    private static final class CommandSlot {

        private final AtomicBoolean commandPending = new AtomicBoolean();

        private final AtomicLong commandPendingSince = new AtomicLong();

        private final AtomicLong commandGeneration = new AtomicLong();

        private final LongSupplier monotonic;

        private CommandSlot(LongSupplier monotonic) {
            this.monotonic = monotonic;
        }

        private long take() {
            if (!commandPending.compareAndSet(false, true)) {
                return NO_RESERVATION;
            }
            return commandGeneration.incrementAndGet();
        }

        private void started() {
            commandPendingSince.set(monotonic.getAsLong());
        }

        private CommandState expire() {
            if (!commandPending.get()) {
                return CommandState.IDLE;
            }
            long since = commandPendingSince.get();
            long now = monotonic.getAsLong();
            if (now < since || now - since < COMMAND_WAIT_MILLIS) {
                return CommandState.WAITING;
            }
            long generation = commandGeneration.get();
            CommandState state;
            if (!commandGeneration.compareAndSet(generation, generation + 1)) {
                state = idleOrWaiting();
            } else if (!commandPending.compareAndSet(true, false)) {
                state = idleOrWaiting();
            } else {
                commandPendingSince.set(0L);
                state = CommandState.EXPIRED;
            }
            return state;
        }

        private CommandState idleOrWaiting() {
            return commandPending.get() ? CommandState.WAITING : CommandState.IDLE;
        }

        private void complete(long generation) {
            if (commandGeneration.get() == generation) {
                commandPendingSince.set(0L);
                commandPending.set(false);
            }
        }

        private boolean isCurrent(long generation) {
            return commandGeneration.get() == generation;
        }
    }

    private final class ReadCaches {

        private volatile NamesRead namesCache = new NamesRead(null, 0L, 0L,
                List.of());

        private volatile long namesRefreshedAt = NEVER_REFRESHED;

        private volatile Path cachedReadOnlyBookFile;

        private volatile long cachedReadOnlyBookStamp;

        private volatile long cachedReadOnlyBookSize;

        private volatile ClaimBook cachedReadOnlyBook;

        private final AtomicLong namesGeneration = new AtomicLong();

        private void rememberNames(NamesRead read, long generation) {
            synchronized (Claims.this) {
                if (namesGeneration.get() == generation) {
                    namesCache = read;
                }
            }
        }

        private void rememberRefresh(NamesRead read, long generation) {
            synchronized (Claims.this) {
                if (namesGeneration.get() == generation) {
                    namesCache = read;
                    namesRefreshedAt = monotonic.getAsLong();
                }
            }
        }

        private void forgetNames() {
            synchronized (Claims.this) {
                namesCache = new NamesRead(null, 0L, 0L, java.util.List.of());
                namesRefreshedAt = NEVER_REFRESHED;
                cachedReadOnlyBookFile = null;
                cachedReadOnlyBookStamp = 0L;
                cachedReadOnlyBookSize = 0L;
                cachedReadOnlyBook = null;
                namesGeneration.incrementAndGet();
            }
        }

        private ClaimBook rememberedBook(Path path, long stamp, long size) {
            synchronized (Claims.this) {
                ClaimBook remembered = cachedReadOnlyBook;
                boolean same = remembered != null && path.equals(cachedReadOnlyBookFile)
                        && stamp == cachedReadOnlyBookStamp && size == cachedReadOnlyBookSize;
                return same ? remembered : null;
            }
        }

        private void rememberBook(Path path, long stamp, long size, ClaimBook book,
                                  long generation) {
            synchronized (Claims.this) {
                if (namesGeneration.get() == generation) {
                    cachedReadOnlyBookFile = path;
                    cachedReadOnlyBookStamp = stamp;
                    cachedReadOnlyBookSize = size;
                    cachedReadOnlyBook = book;
                }
            }
        }
    }

    private final Supplier<Path> file;

    private final Supplier<Path> shared;

    private final LongSupplier clock;

    private final LongSupplier monotonic;

    private final Supplier<String> ids;

    private final ClaimPen pen = new ClaimPen();

    // Client thread only.
    private Path penBook;

    private final AtomicBoolean writingBook = new AtomicBoolean();

    private final ReadCaches readCaches = new ReadCaches();

    private volatile WorkPool namesPool;

    private volatile WorkPool commandPool;

    private volatile SaveFailureSink saveFailure = problem -> {
    };

    private volatile BookSaved afterSave;

    private volatile Consumer<String> listenerFailureNote = message -> { };

    private volatile SharedFrom sharedFrom = NO_SHARED_ENTRIES;

    private final AtomicBoolean namesRefreshPending = new AtomicBoolean();

    private final CommandSlot commandSlot;

    public Claims(Supplier<Path> file) {
        this(file, NO_SHARED_BOOK);
    }

    // shared gives the shared book's path; null for none.
    public Claims(Supplier<Path> file, Supplier<Path> shared) {
        this(file, shared, System::currentTimeMillis, Claims::monotonicMillis, Claims::newClaimId);
    }

    private static final Supplier<Path> NO_SHARED_BOOK = () -> null;

    private static String newClaimId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSigBits = random.nextLong();
        long leastSigBits = random.nextLong();
        mostSigBits &= UUID_CLEAR_VERSION;
        mostSigBits |= UUID_VERSION_4;
        leastSigBits &= UUID_CLEAR_VARIANT;
        leastSigBits |= UUID_IETF_VARIANT;
        return "geosurvey-" + new UUID(mostSigBits, leastSigBits);
    }

    public Claims(Supplier<Path> file, LongSupplier clock, Supplier<String> ids) {
        this(file, NO_SHARED_BOOK, clock, clock, ids);
    }

    public Claims(Supplier<Path> file, Supplier<Path> shared, LongSupplier clock,
                  Supplier<String> ids) {
        this(file, shared, clock, clock, ids);
    }

    Claims(Supplier<Path> file, LongSupplier clock, LongSupplier monotonic,
           Supplier<String> ids) {
        this(file, NO_SHARED_BOOK, clock, monotonic, ids);
    }

    private Claims(Supplier<Path> file, Supplier<Path> shared, LongSupplier clock,
                   LongSupplier monotonic, Supplier<String> ids) {
        this.file = file;
        this.shared = shared;
        this.clock = clock;
        this.monotonic = monotonic;
        this.ids = ids;
        this.commandSlot = new CommandSlot(monotonic);
    }

    private static long monotonicMillis() {
        return Math.floorDiv(System.nanoTime(), NANOS_PER_MILLI);
    }

    // The same pen the commands share.
    public ClaimPen pen() {
        return pen;
    }

    public void workPool(WorkPool pool) {
        namesPool = pool;
        commandPool = pool;
    }

    public void namesWorkPool(WorkPool pool) {
        namesPool = pool;
    }

    public void saveFailureSink(SaveFailureSink sink) {
        saveFailure = sink == null ? problem -> {
        } : sink;
    }

    // listener may be null: nobody is told of a save.
    public void afterSave(BookSaved listener) {
        afterSave = listener;
    }

    // lookup may be null: no shared claim's entry is known.
    public void sharedFrom(SharedFrom lookup) {
        sharedFrom = (lookup == null) ? NO_SHARED_ENTRIES : lookup;
    }

    // note may be null: a failing listener is not said.
    public void listenerFailureNote(Consumer<String> note) {
        listenerFailureNote = (note == null) ? message -> { } : note;
    }

    public Outcome startAsync(Standing here, String name, Consumer<Said> answer) {
        if (here == null || here.dimension().isEmpty()) {
            answer.accept(nowhere("start"));
            return Outcome.ANSWERED_REFUSAL;
        }
        if (outsideWorldBorder(here)) {
            answer.accept(outsideWorldBorder());
            return Outcome.ANSWERED_REFUSAL;
        }
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            answer.accept(NEEDS_CLAIM_NAME);
            return Outcome.ANSWERED_REFUSAL;
        }
        String dropped = forgetPenElsewhereInWorld(here.dimension());
        // -1 when there is no pool.
        long[] reserved = {-1L};
        return submitClaim(() -> startChecked(wanted), checked -> {
            if (!checked.ok()) {
                answer.accept(noted(checked, dropped));
                return;
            }
            if (reserved[0] >= 0 && !isCurrentGeneration(reserved[0])) {
                answer.accept(new Said(false, "\"" + storedName(wanted) + "\" was not started:"
                        + " the claim command stopped responding."
                        + " Nothing drawn over."));
                return;
            }
            boolean interrupted = pen.drawing();
            String was = pen.name();
            int had = pen.size();
            pen.begin(wanted, here.dimension(), here.x(), here.z());
            penBook = file.get();
            answer.accept(new Said(true, "Started \"" + storedName(wanted) + "\" at "
                    + (long) Math.floor(here.x()) + "," + (long) Math.floor(here.z()) + " in "
                    + here.dimension() + "."
                    + " Finish it with /geosurvey claim finish."
                    + afterLine(interrupted ? thrownAway(was, had) : dropped)));
        }, generation -> reserved[0] = generation);
    }

    public Outcome finishAsync(Standing here, Consumer<Said> answer) {
        String dropped = here == null ? "" : forgetPenElsewhereInWorld(here.dimension());
        if (!pen.drawing()) {
            answer.accept(noted(NOTHING_DRAWN_TO_FINISH, dropped));
            return Outcome.ANSWERED_REFUSAL;
        }
        int corners = pen.size();
        if (corners < Claim.MIN_CORNERS) {
            answer.accept(new Said(false, "\"" + pen.name() + "\" has " + corners
                    + (corners == 1 ? " corner" : " corners") + " and a claim"
                    + " needs at least " + Claim.MIN_CORNERS + ". Add a corner"
                    + " with /geosurvey claim corner."));
            return Outcome.ANSWERED_REFUSAL;
        }
        Path startedBook = penBook;
        Outcome outcome;
        if (commandPool == null) {
            outcome = finishUnreserved(here, startedBook, answer);
        } else {
            outcome = finishReserved(here, startedBook, answer);
        }
        return outcome;
    }

    private Outcome finishUnreserved(Standing here, Path startedBook, Consumer<Said> answer) {
        long now = clock.getAsLong();
        Claim made;
        RuntimeException unclosed;
        try {
            made = pen.finish(ids, here == null ? "" : here.player(),
                    here == null ? "" : here.playerId(), now);
            unclosed = null;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            made = null;
            unclosed = invalid;
        }
        Outcome outcome;
        if (unclosed != null) {
            answer.accept(invalidBoundary(unclosed));
            outcome = Outcome.ANSWERED_REFUSAL;
        } else {
            Claim closed = made;
            AtomicReference<FinishResult> finished = new AtomicReference<>();
            outcome = submitClaim(() -> {
                FinishResult checked = finishChecked(closed, now, NO_RESERVATION, startedBook);
                finished.set(checked);
                return checked.said();
            }, written -> {
                FinishResult checked = finished.getAndSet(null);
                if (written.ok() || (checked != null && checked.discarded())) {
                    pen.clear();
                    penBook = null;
                }
                answer.accept(written);
            });
        }
        return outcome;
    }

    private Outcome finishReserved(Standing here, Path startedBook, Consumer<Said> answer) {
        long generation = commandSlot.take();
        if (generation == NO_RESERVATION) {
            answer.accept(commandWaiting());
            return Outcome.ANSWERED_REFUSAL;
        }
        commandSlot.started();
        long now = clock.getAsLong();
        Claim made;
        RuntimeException unclosed;
        try {
            made = pen.finish(ids, here == null ? "" : here.player(),
                    here == null ? "" : here.playerId(), now);
            unclosed = null;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            made = null;
            unclosed = invalid;
        }
        Outcome outcome;
        if (unclosed != null) {
            completeCommand(generation);
            answer.accept(invalidBoundary(unclosed));
            outcome = Outcome.ANSWERED_REFUSAL;
        } else {
            Claim closed = made;
            AtomicReference<FinishResult> finished = new AtomicReference<>();
            outcome = submitReserved(generation,
                    () -> {
                        FinishResult checked = finishChecked(closed, now, generation, startedBook);
                        finished.set(checked);
                        return checked.said();
                    }, written -> {
                FinishResult checked = finished.getAndSet(null);
                if ((written.ok() || (checked != null && checked.discarded()))
                        && isCurrentGeneration(generation)) {
                    pen.clear();
                    penBook = null;
                }
                answer.accept(written);
            });
        }
        return outcome;
    }

    public Outcome renameAsync(String handle, String wanted, Consumer<Said> answer) {
        if (renameTarget(wanted).isEmpty()) {
            answer.accept(unnamedTarget());
            return Outcome.ANSWERED_REFUSAL;
        }
        return submitClaim(() -> rename(handle, wanted), answer);
    }

    public Outcome recolourAsync(String handle, String wanted, Consumer<Said> answer) {
        if (colour(wanted) == null) {
            answer.accept(unknownColour(wanted));
            return Outcome.ANSWERED_REFUSAL;
        }
        return submitClaim(() -> recolour(handle, wanted), answer);
    }

    public Outcome removeAsync(String handle, Consumer<Said> answer) {
        return submitClaim(() -> remove(handle), answer);
    }

    public Outcome removeAsync(String handle, String playerId, Consumer<Said> answer) {
        return submitClaim(() -> remove(handle, playerId), answer);
    }

    public Outcome shareAsync(String handle, String node, String playerId, Consumer<Said> answer) {
        return submitClaim(() -> share(handle, node, playerId), answer);
    }

    public Outcome unshareAsync(String handle, String playerId, Consumer<Said> answer) {
        return submitClaim(() -> unshare(handle, playerId), answer);
    }

    public Outcome portAsync(Consumer<Said> answer) {
        return submitClaim(this::port, answer);
    }

    public Outcome retireAndPortOnceAsync(Path path, Path sharedBook, Consumer<Said> note) {
        return submitClaim(() -> retireAndPortOnce(path, sharedBook), said -> {
            if (!said.text().isEmpty()) {
                note.accept(said);
            }
        });
    }

    // Empty without a readable book.
    public java.util.List<String> names() {
        WorkPool pool = namesPool;
        if (pool == null) {
            try {
                pool = Sandpaper.workPool();
                namesPool = pool;
            } catch (IllegalStateException | LinkageError notReady) {
                return namesSynchronously();
            }
        }
        long generation = readCaches.namesGeneration.get();
        NamesRead cache = readCaches.namesCache;
        List<String> answer = cache.names();
        long since = readCaches.namesRefreshedAt;
        long now = monotonic.getAsLong();
        boolean fresh = since != NEVER_REFRESHED && now >= since
                && now - since < NAMES_REFRESH_MILLIS;
        if (!fresh) {
            refreshNames(pool, cache, generation);
        }
        return answer;
    }

    private void refreshNames(WorkPool pool, NamesRead snapshot, long generation) {
        if (namesRefreshPending.compareAndSet(false, true)) {
            boolean queued = pool.submit(
                    () -> readNames(snapshot),
                    read -> applyNames(read, generation),
                    () -> namesRefreshPending.set(false),
                    read -> namesRefreshPending.set(false));
            if (!queued) {
                namesRefreshPending.set(false);
            }
        }
    }

    private List<String> namesSynchronously() {
        Path path = file.get();
        if (path == null) {
            readCaches.forgetNames();
            return java.util.List.of();
        }
        List<String> answer;
        try {
            long generation = readCaches.namesGeneration.get();
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                readCaches.forgetNames();
                answer = java.util.List.of();
            } else {
                long stamp = attributes.lastModifiedTime().toMillis();
                long size = attributes.size();
                NamesRead cache = readCaches.namesCache;
                if (!path.equals(cache.path()) || stamp != cache.stamp()
                        || size != cache.size()) {
                    cache = new NamesRead(path, stamp, size, ClaimBook.names(path));
                    readCaches.rememberNames(cache, generation);
                }
                answer = cache.names();
            }
        } catch (IOException unreadable) {
            readCaches.forgetNames();
            answer = java.util.List.of();
        }
        return answer;
    }

    private NamesRead readNames(NamesRead snapshot) {
        Path path = file.get();
        if (path == null) {
            return new NamesRead(null, 0L, 0L, List.of());
        }
        NamesRead read;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                read = new NamesRead(null, 0L, 0L, List.of());
            } else {
                long stamp = attributes.lastModifiedTime().toMillis();
                long size = attributes.size();
                if (path.equals(snapshot.path()) && stamp == snapshot.stamp()
                        && size == snapshot.size()) {
                    read = snapshot;
                } else {
                    read = new NamesRead(path, stamp, size, ClaimBook.names(path));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            read = new NamesRead(null, 0L, 0L, List.of());
        }
        return read;
    }

    private synchronized void applyNames(NamesRead read, long generation) {
        readCaches.rememberRefresh(read, generation);
        namesRefreshPending.set(false);
    }

    private record NamesRead(Path path, long stamp, long size, List<String> names) {
    }

    public Said list() {
        Path path = file.get();
        if (path == null) {
            return noWorld();
        }
        String dropped = forgetPenElsewhere(path);
        ClaimBook book = readOnlyBook(path);
        if (!book.unreadable().isEmpty()) {
            return noted(damaged(book, path), dropped);
        }
        StringBuilder said = new StringBuilder();
        int count = book.size();
        if (count == 0) {
            said.append("No claims yet. Start one with /geosurvey claim start"
                    + " <name>.");
        } else {
            said.append(count).append(count == 1 ? " claim:" : " claims:");
            for (Claim claim : book.claims()) {
                double[] box = claim.bounds();
                said.append(LINE_BREAK).append('"').append(flat(claim.name())).append("\" - ")
                        .append(LOWERED[claim.colour().ordinal()].label())
                        .append(", ").append(claim.corners()).append(" corners in ")
                        .append(flat(claim.dimension())).append(" from ")
                        .append((long) Math.floor(box[BOUNDS_MIN_X])).append(',')
                        .append((long) Math.floor(box[BOUNDS_MIN_Z]))
                        .append(" to ").append((long) Math.floor(box[BOUNDS_MAX_X])).append(',')
                        .append((long) Math.floor(box[BOUNDS_MAX_Z]));
                if (!claim.owner().isEmpty()) {
                    said.append(", claimed by ").append(flat(claim.owner()));
                }
                said.append('.');
            }
        }
        if (pen.drawing()) {
            int corners = pen.size();
            said.append(LINE_BREAK).append("You are drawing \"").append(pen.name())
                    .append("\": ").append(corners)
                    .append(corners == 1 ? " corner" : " corners")
                    .append(corners >= Claim.MIN_CORNERS
                            ? pen.closeable() ? ", enough to finish."
                                    : "; a claim must enclose ground."
                            : "; a claim needs at least "
                                    + Claim.MIN_CORNERS + ".");
        }
        said.append(LINE_BREAK).append(publishing());
        return noted(new Said(true, said.toString()), dropped);
    }

    public Said start(Standing here, String name) {
        if (here == null || here.dimension().isEmpty()) {
            return nowhere("start");
        }
        if (outsideWorldBorder(here)) {
            return outsideWorldBorder();
        }
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            return NEEDS_CLAIM_NAME;
        }
        Path path = file.get();
        if (path == null) {
            return noWorld();
        }
        String dropped = forgetPenElsewhere(path);
        Said checked = startChecked(wanted);
        if (!checked.ok()) {
            return noted(checked, dropped);
        }
        boolean interrupted = pen.drawing();
        String was = pen.name();
        int had = pen.size();
        pen.begin(wanted, here.dimension(), here.x(), here.z());
        penBook = path;
        return new Said(true, "Started \"" + storedName(wanted) + "\" at " + (long) Math.floor(here.x())
                + "," + (long) Math.floor(here.z()) + " in " + here.dimension() + "."
                + " Finish it with /geosurvey claim finish."
                + afterLine(interrupted ? thrownAway(was, had) : dropped));
    }

    public Said corner(Standing here) {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else if (here == null) {
            said = afterExpiredCommand(nowhere("add a corner to"), expired);
        } else {
            String dropped = forgetPenElsewhere(file.get());
            if (!pen.drawing()) {
                said = afterExpiredCommand(noted(NOTHING_DRAWN_TO_FINISH, dropped), expired);
            } else {
                ClaimPen.Refusal why = pen.corner(here.dimension(), here.x(), here.z());
                Said placed = switch (why) {
                    case NONE -> {
                        int corners = pen.size();
                        yield new Said(true, "Corner " + corners + " at "
                                + (long) Math.floor(here.x()) + "," + (long) Math.floor(here.z()) + "."
                                + (corners >= Claim.MIN_CORNERS
                                        ? pen.closeable()
                                                ? " That is enough to finish: /geosurvey claim finish."
                                                : " A claim must enclose ground."
                                        : " A claim needs at least " + Claim.MIN_CORNERS + "."));
                    }
                    case ELSEWHERE -> new Said(false, "\"" + pen.name() + "\" is being drawn"
                            + " in " + pen.dimension() + " and you are in " + here.dimension()
                            + ". Go back, or start again here.");
                    case REPEATED -> CORNER_REPEATED;
                    case FULL -> new Said(false, "\"" + pen.name() + "\" has "
                            + Claim.MAX_CORNERS + " corners, the most allowed. Finish"
                            + " it with /geosurvey claim finish.");
                    case OUTSIDE_BORDER -> outsideWorldBorder();
                };
                said = afterExpiredCommand(placed, expired);
            }
        }
        return said;
    }

    public Said undo() {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else {
            String dropped = forgetPenElsewhere(file.get());
            if (!pen.drawing()) {
                said = afterExpiredCommand(noted(NOTHING_DRAWN_TO_UNDO, dropped), expired);
            } else if (!pen.undo()) {
                said = afterExpiredCommand(new Said(false, "\"" + pen.name() + "\" has no corners left."
                        + " Use /geosurvey claim corner,"
                        + " or /geosurvey claim cancel."), expired);
            } else {
                int corners = pen.size();
                said = afterExpiredCommand(new Said(true, "Took back the last corner. " + corners
                        + (corners == 1 ? " corner" : " corners") + " left"
                        + (corners >= Claim.MIN_CORNERS
                                ? pen.closeable() ? ", still enough to finish"
                                        : "; a claim must enclose ground"
                                : ", too few to finish") + "."), expired);
            }
        }
        return said;
    }

    public Said cancel() {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else {
            String dropped = forgetPenElsewhere(file.get());
            if (!pen.drawing()) {
                said = afterExpiredCommand(noted(NOTHING_DRAWN_TO_CANCEL, dropped), expired);
            } else {
                String was = pen.name();
                int had = pen.size();
                pen.clear();
                penBook = null;
                said = afterExpiredCommand(new Said(true, "Threw away \"" + was + "\" and its " + had
                        + (had == 1 ? " corner" : " corners")
                        + ". Nothing on disk touched."), expired);
            }
        }
        return said;
    }

    public Said penColour(String wanted) {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else {
            String dropped = forgetPenElsewhere(file.get());
            if (!pen.drawing()) {
                said = afterExpiredCommand(noted(NOTHING_DRAWN_TO_RECOLOUR, dropped), expired);
            } else {
                MarkerColour colour = colour(wanted);
                if (colour == null) {
                    said = afterExpiredCommand(noted(unknownColour(wanted), dropped), expired);
                } else {
                    pen.colour(colour);
                    said = afterExpiredCommand(new Said(true, "\"" + pen.name() + "\" will be drawn in "
                            + LOWERED[colour.ordinal()].label() + " - "
                            + LOWERED[colour.ordinal()].meaning() + "."), expired);
                }
            }
        }
        return said;
    }

    public Said finish(Standing here) {
        Path path = file.get();
        String dropped = forgetPenElsewhere(path);
        if (!pen.drawing()) {
            return noted(NOTHING_DRAWN_TO_FINISH, dropped);
        }
        int corners = pen.size();
        if (corners < Claim.MIN_CORNERS) {
            return new Said(false, "\"" + pen.name() + "\" has " + corners
                    + (corners == 1 ? " corner" : " corners") + " and a claim"
                    + " needs at least " + Claim.MIN_CORNERS + ". Add a corner"
                    + " with /geosurvey claim corner.");
        }
        if (path == null) {
            return noWorld();
        }
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                long now = clock.getAsLong();
                Claim made;
                RuntimeException unclosed;
                try {
                    made = pen.finish(ids,
                            here == null ? "" : here.player(),
                            here == null ? "" : here.playerId(), now);
                    unclosed = null;
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    made = null;
                    unclosed = invalid;
                }
                if (unclosed != null) {
                    said = invalidBoundary(unclosed);
                } else {
                    ClaimBook.Refusal refused = book.insertIfAllowed(made);
                    if (refused == ClaimBook.Refusal.DUPLICATE) {
                        said = new Said(false, "A claim called \"" + pen.name() + "\" appeared"
                                + " while you were drawing."
                                + " Remove that one, or rename this one, and finish again.");
                    } else if (refused == ClaimBook.Refusal.FULL) {
                        said = new Said(false, "This installation holds "
                                + ClaimBook.MAX_CLAIMS + " claims."
                                + " Remove one and finish again.");
                    } else {
                        said = write(book, path, now, "Claimed \"" + made.name() + "\": "
                                + made.corners() + " corners in " + made.dimension() + ", drawn in "
                                + LOWERED[made.colour().ordinal()].label() + ".");
                        if (said.ok()) {
                            pen.clear();
                            penBook = null;
                        }
                    }
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    public Said rename(String handle, String wanted) {
        String to = renameTarget(wanted);
        if (to.isEmpty()) {
            return unnamedTarget();
        }
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
            if (path == null) {
                return noWorld();
            }
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                int at = book.indexByName(handle);
                if (at < 0) {
                    said = noSuchClaim(book, handle);
                } else {
                    Claim claim = book.at(at);
                    int clash = book.indexByCleanName(to, at);
                    if (clash >= 0) {
                        said = new Said(false, "There is a claim called \"" + nameAt(book, clash) + "\".");
                    } else {
                        long now = clock.getAsLong();
                        if (!book.replaceAt(at, claim.named(to, now))) {
                            said = noSuchClaim(book, handle);
                        } else {
                            said = write(book, path, now, "\"" + flat(claim.name()) + "\" is now \"" + to
                                    + "\".");
                        }
                    }
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    public Said recolour(String handle, String wanted) {
        MarkerColour colour = colour(wanted);
        if (colour == null) {
            return unknownColour(wanted);
        }
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
            if (path == null) {
                return noWorld();
            }
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                int at = book.indexByName(handle);
                if (at < 0) {
                    said = noSuchClaim(book, handle);
                } else {
                    Claim claim = book.at(at);
                    long now = clock.getAsLong();
                    if (!book.replaceAt(at, claim.coloured(colour, now))) {
                        said = noSuchClaim(book, handle);
                    } else {
                        said = write(book, path, now, "\"" + flat(claim.name()) + "\" is now drawn in "
                                + LOWERED[colour.ordinal()].label() + " - "
                                + LOWERED[colour.ordinal()].meaning() + ".");
                    }
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    public Said remove(String handle) {
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
            if (path == null) {
                return noWorld();
            }
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                Claim gone = book.remove(handle);
                if (gone == null) {
                    said = noSuchClaim(book, handle);
                } else {
                    said = write(book, path, clock.getAsLong(), removed(gone), gone.shared());
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    public Said remove(String handle, String playerId) {
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
            if (path == null) {
                return noWorld();
            }
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                Claim present = book.byName(handle);
                if (present != null && !canChange(present, playerId)) {
                    said = new Said(false, "Only its owner can remove \"" + flat(present.name()) + "\".");
                } else {
                    Claim gone = book.remove(handle);
                    if (gone == null) {
                        said = noSuchClaim(book, handle);
                    } else {
                        said = write(book, path, clock.getAsLong(), removed(gone), gone.shared());
                    }
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    // node may be null: no collector is set.
    public Said share(String handle, String node, String playerId) {
        String target = node == null ? "" : node.trim();
        Said said;
        if (target.isEmpty()) {
            said = NEEDS_COLLECTOR;
        } else if (!writingBook.compareAndSet(false, true)) {
            said = commandWaiting();
        } else {
            try {
                said = shareChecked(handle, target, playerId);
            } finally {
                writingBook.set(false);
            }
        }
        return said;
    }

    private Said shareChecked(String handle, String target, String playerId) {
        Path path = file.get();
        Said said;
        if (path == null) {
            said = noWorld();
        } else {
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                said = shared(book, path, handle, target, playerId);
            }
        }
        return said;
    }

    private Said shared(ClaimBook book, Path path, String handle, String target, String playerId) {
        Claim claim = book.byName(handle);
        Said said;
        if (claim == null) {
            said = noSuchClaim(book, handle);
        } else if (!claim.ownerId().equals(playerId)) {
            said = new Said(false, "Only its owner can share \"" + flat(claim.name()) + "\".");
        } else {
            Shareable fit = shareable(claim);
            if (fit != Shareable.YES) {
                said = new Said(false, "\"" + flat(claim.name()) + "\" cannot be shared: " + fit.wrong + ".");
            } else if (claim.shared()) {
                said = new Said(false, "\"" + flat(claim.name()) + "\" is already shared.");
            } else {
                book.setShared(claim.id(), true);
                said = write(book, path, clock.getAsLong(),
                        "Shared \"" + flat(claim.name()) + "\" with " + target + ".");
            }
        }
        return said;
    }

    public Said unshare(String handle, String playerId) {
        Said said;
        if (!writingBook.compareAndSet(false, true)) {
            said = commandWaiting();
        } else {
            try {
                said = unshareChecked(handle, playerId);
            } finally {
                writingBook.set(false);
            }
        }
        return said;
    }

    private Said unshareChecked(String handle, String playerId) {
        Path path = file.get();
        Said said;
        if (path == null) {
            said = noWorld();
        } else {
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                said = unshared(book, path, handle, playerId);
            }
        }
        return said;
    }

    private Said unshared(ClaimBook book, Path path, String handle, String playerId) {
        Claim claim = book.byName(handle);
        Said said;
        if (claim == null) {
            said = noSuchClaim(book, handle);
        } else if (!canChange(claim, playerId)) {
            said = new Said(false, "Only its owner can stop sharing \"" + flat(claim.name()) + "\".");
        } else if (!claim.shared()) {
            said = new Said(false, "\"" + flat(claim.name()) + "\" is not shared.");
        } else {
            book.setShared(claim.id(), false);
            said = write(book, path, clock.getAsLong(), "Stopped sharing \"" + flat(claim.name()) + "\".", true);
        }
        return said;
    }

    private static boolean canChange(Claim claim, String playerId) {
        return claim.ownerId().isEmpty() || claim.ownerId().equals(playerId);
    }

    private static String removed(Claim gone) {
        String said = "Removed \"" + flat(gone.name()) + "\".";
        return gone.shared() ? said : said + LINE_BREAK + "A node with an old copy keeps drawing it.";
    }

    public Said port() {
        Path path = file.get();
        if (path == null) {
            return noWorld();
        }
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path from = shared.get();
            String unmoved = retireShared(from);
            List<Path> copies = ClaimBook.retiredCopies(from);
            if (!unmoved.isEmpty()) {
                said = unported(from, unmoved);
            } else if (copies.isEmpty()) {
                said = new Said(false, "There is no retired claims book.");
            } else {
                said = portInto(path, copies, from.getParent());
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    // Null when there is nothing to say.
    public Said portOnce(Path path, Path sharedBook) {
        if ((path == null) || (sharedBook == null) || ClaimBook.ported(path)) {
            return null;
        }
        SharedFrom entries = sharedFrom;
        Said said;
        if (entries.ready()) {
            said = portOnceGuarded(path, sharedBook);
        } else {
            entries.holdPort(path, () -> portOnceGuarded(path, sharedBook));
            said = null;
        }
        return said;
    }

    private Said portOnceGuarded(Path path, Path sharedBook) {
        Said said = null;
        if (writingBook.compareAndSet(false, true)) {
            try {
                said = portOnceNow(path, sharedBook);
            } finally {
                writingBook.set(false);
            }
        }
        return said;
    }

    private Said retireAndPortOnce(Path path, Path sharedBook) {
        if (sharedBook == null) {
            return new Said(true, "");
        }
        String unmoved = retireShared(sharedBook);
        if (!unmoved.isEmpty()) {
            return unported(sharedBook, unmoved);
        }
        Said said = portOnce(path, sharedBook);
        return said == null ? new Said(true, "") : said;
    }

    private Said portOnceNow(Path path, Path sharedBook) {
        if (ClaimBook.ported(path)) {
            return null;
        }
        List<Path> copies = ClaimBook.retiredCopies(sharedBook);
        ClaimBook book = copies.isEmpty() ? null : ClaimBook.load(path);
        if ((book == null) || !book.unreadable().isEmpty()) {
            return null;
        }
        Retired retired = Retired.read(copies);
        Said said;
        if (retired.unreadable() != null) {
            said = unported(retired.unreadable(), retired.why());
        } else {
            said = merge(path, book, retired.books(), Worlds.around(path, sharedBook.getParent()), true);
            if ((said == null) || said.ok()) {
                markPorted(path);
            }
        }
        return said;
    }

    private void markPorted(Path path) {
        try {
            ClaimBook.markPorted(path);
        } catch (IOException unmarked) {
            saveFailure.failed(unmarked);
        }
    }

    private static String retireShared(Path from) {
        String why = "";
        try {
            ClaimBook.retire(from);
        } catch (IOException unmoved) {
            why = unmoved.toString();
        }
        return why;
    }

    private Said portInto(Path path, List<Path> copies, Path dataDir) {
        ClaimBook book = ClaimBook.load(path);
        Said said;
        if (!book.unreadable().isEmpty()) {
            for (Path copy : copies) {
                Retired.recoverUnused(copy);
            }
            said = damaged(book, path);
        } else {
            Retired retired = Retired.read(copies);
            if (retired.unreadable() != null) {
                said = unported(retired.unreadable(), retired.why());
            } else {
                said = merge(path, book, retired.books(), Worlds.around(path, dataDir), false);
            }
        }
        return said;
    }

    private Said merge(Path path, ClaimBook book, List<ClaimBook> retired, Worlds worlds, boolean told) {
        SharedFrom entries = sharedFrom;
        if (worlds.unread != null) {
            return worldsUnread(worlds.unread, worlds.why);
        }
        if (!entries.ready()) {
            return new Said(false, ENTRIES_LOADING);
        }
        Tally tally = new Tally();
        Set<String> seen = new HashSet<>(seenCapacity(retired));
        for (ClaimBook old : retired) {
            for (Claim claim : old.claims()) {
                if (seen.add(claim.id())) {
                    SharedEntry entry = entries.entryOf(claim.id());
                    Home home = worlds.homeOf(claim, entry, book.removedHere(claim.id()));
                    tally.place(book, claim, claim.shared() && stillShared(entry), home, told);
                }
            }
        }
        String line = tally.said(told);
        Said said;
        if (line == null) {
            said = null;
        } else if (tally.ported == 0) {
            said = new Said(true, line);
        } else {
            said = write(book, path, clock.getAsLong(), line);
        }
        return said;
    }

    private static final int SEEN_CLAIMS = ClaimBook.MAX_RETIRED * ClaimBook.MAX_CLAIMS;

    private static final int IDS_PER_FOUR_SLOTS = 3;

    private static int seenCapacity(List<ClaimBook> retired) {
        int ids = 0;
        for (ClaimBook book : retired) {
            ids += book.size();
        }
        int bounded = (ids > SEEN_CLAIMS) ? SEEN_CLAIMS : ids;
        return bounded * 4 / IDS_PER_FOUR_SLOTS + 1;
    }

    private static Said unported(Path path, String why) {
        return new Said(false, path + " could not be ported (" + why + "). Nothing was written.");
    }

    private static Said worldsUnread(Path path, String why) {
        return new Said(false, path + " could not be read (" + why + "). Nothing was ported or"
                + " written.");
    }

    private static Said noWorld() {
        return NO_WORLD_SAID;
    }

    private static Said noted(Said said, String note) {
        return note.isEmpty() ? said : new Said(said.ok(), said.text() + afterLine(note));
    }

    private static String afterLine(String note) {
        return note.isEmpty() ? "" : LINE_BREAK + note;
    }

    // A name read from a file stays on its own line.
    private static String flat(String name) {
        return name.replace('\n', ' ').replace('\r', ' ');
    }

    // The name of the claim at the row, on one line.
    private static String nameAt(ClaimBook book, int row) {
        return flat(book.at(row).name());
    }

    // The name the book stores for a typed name, on one line.
    private static String storedName(String typed) {
        return flat(Claim.cleaned(typed).trim());
    }

    private static String thrownAway(String name, int corners) {
        return "Removed the unfinished \"" + name + "\" and its " + corners
                + (corners == 1 ? " corner" : " corners") + ".";
    }

    private String forgetPenElsewhere(Path path) {
        String dropped = "";
        if (path != null && penBook != null && pen.drawing() && !penBook.equals(path)) {
            dropped = thrownAway(pen.name(), pen.size());
            pen.clear();
            penBook = null;
        }
        return dropped;
    }

    private String forgetPenElsewhereInWorld(String world) {
        String dropped = "";
        if (pen.drawing() && !pen.dimension().equals(world)) {
            dropped = thrownAway(pen.name(), pen.size());
            pen.clear();
            penBook = null;
        }
        return dropped;
    }

    private enum Home { HERE, ELSEWHERE, WAS_HERE, UNTOLD }

    private record Retired(List<ClaimBook> books, Path unreadable, String why) {

        private static Retired read(List<Path> copies) {
            List<ClaimBook> books = new ArrayList<>(copies.size());
            boolean readable = true;
            for (Path copy : copies) {
                if (readable) {
                    ClaimBook loaded = ClaimBook.load(copy);
                    books.add(loaded);
                    readable = loaded.unreadable().isEmpty();
                } else {
                    recoverUnused(copy);
                }
            }
            int bad = firstUnreadable(books);
            return (bad < books.size()) ? new Retired(books, copies.get(bad), books.get(bad).unreadable())
                    : new Retired(books, null, "");
        }

        private static void recoverUnused(Path copy) {
            try {
                AtomicFileReplace.recoverStaleAside(copy);
            } catch (IOException unread) {
            }
        }

        private static int firstUnreadable(List<ClaimBook> books) {
            int at = 0;
            while ((at < books.size()) && books.get(at).unreadable().isEmpty()) {
                at++;
            }
            return at;
        }
    }

    private static final class Tally {

        private final List<String> clashed = new ArrayList<>();

        private final List<String> full = new ArrayList<>();

        private final List<String> elsewhere = new ArrayList<>();

        private final List<String> removed = new ArrayList<>();

        private int ported = 0;

        private int held = 0;

        private int untold = 0;

        private void place(ClaimBook book, Claim claim, boolean shared, Home home, boolean told) {
            if (book.byId(claim.id()) != null) {
                held++;
            } else if (home == Home.ELSEWHERE) {
                if (!told) {
                    elsewhere.add(claim.name());
                }
            } else if (home == Home.WAS_HERE) {
                if (!told) {
                    removed.add(claim.name());
                }
            } else if (told && (home != Home.HERE)) {
                if (home == Home.UNTOLD) {
                    untold++;
                }
            } else if (book.indexByCleanName(claim.name(), -1) >= 0) {
                clashed.add(claim.name());
            } else if (!book.add(claim.sharedAs(shared))) {
                full.add(claim.name());
            } else {
                ported++;
            }
        }

        private String said(boolean told) {
            boolean anything = (ported + held + untold + clashed.size() + full.size() + elsewhere.size()
                    + removed.size()) > 0;
            String line;
            if (told && !anything) {
                line = null;
            } else {
                line = "Ported " + ported
                        + ((ported == 1) ? " claim" : " claims") + PORTED_FROM
                        + counted(held, " was already here.", " were already here.")
                        + named(clashed, "Not ported, its name is used here: ",
                                "Not ported, their names are used here: ")
                        + named(full, "Not ported, this book is full: ", "Not ported, this book is full: ")
                        + named(elsewhere, "Not ported, it belongs to another world: ",
                                "Not ported, they belong to other worlds: ")
                        + named(removed, "Not ported, it was removed here: ", "Not ported, they were removed here: ")
                        + counted(untold, " could not be placed in a world; /geosurvey claim port copies it into"
                                + " this world.", " could not be placed in a world; /geosurvey claim"
                                + " port copies them into this world.");
            }
            return line;
        }

        private static String counted(int count, String one, String many) {
            String said;
            if (count == 0) {
                said = "";
            } else {
                said = LINE_BREAK + (count + ((count == 1) ? one : many));
            }
            return said;
        }

        private static String named(List<String> names, String one, String many) {
            String said;
            if (names.isEmpty()) {
                said = "";
            } else {
                StringBuilder quoted = new StringBuilder().append(LINE_BREAK)
                        .append((names.size() == 1) ? one : many);
                int shown = Math.min(names.size(), NAMES_LISTED);
                for (int at = 0; at < shown; at++) {
                    quoted.append((at == 0) ? "\"" : ", \"").append(flat(names.get(at))).append('"');
                }
                if (names.size() > shown) {
                    quoted.append(" and ").append(names.size() - shown).append(" more");
                }
                said = quoted.append('.').toString();
            }
            return said;
        }
    }

    private static final class Worlds {

        private final Path here;

        private final String hereBook;

        private final Set<String> elsewhereIds;

        private final List<Path> mapped;

        private final Path unread;

        private final String why;

        private final Map<Path, Boolean> dimensionFolders = new HashMap<>();

        private final Map<String, Boolean> booksOnDisk = new HashMap<>();

        private Worlds(Path book, Set<String> elsewhereIds, List<Path> mapped, Path unread, String why) {
            this.here = placeOf(book.getParent());
            this.hereBook = book.toString();
            this.elsewhereIds = elsewhereIds;
            this.mapped = mapped;
            this.unread = unread;
            this.why = why;
        }

        private static Worlds around(Path book, Path dataDir) {
            Set<String> otherIds = new HashSet<>();
            List<Path> maps = new ArrayList<>();
            Path ownFolder = placeOf(book.getParent());
            Path unread = null;
            String why = "";
            if (dataDir != null) {
                Path ownName = ownFolder.getFileName();
                Path ownListed = (ownName == null) ? null : dataDir.resolve(ownName);
                try (DirectoryStream<Path> folders = Files.newDirectoryStream(dataDir)) {
                    for (Path folder : folders) {
                        if (!folder.equals(ownListed)) {
                            Path other = ClaimBook.bookIn(folder);
                            if (Files.isRegularFile(other) && !placeOf(folder).equals(ownFolder)) {
                                ClaimBook otherBook = ClaimBook.load(other);
                                if ((unread == null) && !otherBook.unreadable().isEmpty()) {
                                    unread = other;
                                    why = otherBook.unreadable();
                                }
                                for (Claim claim : otherBook.claims()) {
                                    otherIds.add(claim.id());
                                }
                                otherIds.addAll(otherBook.removedIds());
                            }
                        }
                        if (Files.isRegularFile(folder.resolve(MapStorage.MARKER))) {
                            maps.add(folder);
                        }
                    }
                } catch (IOException | DirectoryIteratorException unlisted) {
                    otherIds.clear();
                    maps.clear();
                    unread = dataDir;
                    why = unlisted.toString();
                }
            }
            return new Worlds(book, otherIds, maps, unread, why);
        }

        // entry may be null: the claim has none.
        private Home homeOf(Claim claim, SharedEntry entry, boolean removedHere) {
            Home home;
            if (elsewhereIds.contains(claim.id())) {
                home = Home.ELSEWHERE;
            } else if (removedHere) {
                home = Home.WAS_HERE;
            } else if (entry == null) {
                home = mappedHome(claim);
            } else if (entry.book().equals(hereBook)) {
                home = (entry.kept() == Kept.REMOVED) ? Home.WAS_HERE : Home.HERE;
            } else if (onDisk(entry.book())) {
                home = Home.ELSEWHERE;
            } else {
                home = goneBookHome(mappedHome(claim), entry.kept());
            }
            return home;
        }

        // A claim whose book is gone goes where its maps say; a removed claim stays out.
        private static Home goneBookHome(Home mapped, Kept kept) {
            Home home;
            if (kept != Kept.REMOVED) {
                home = mapped;
            } else if (mapped == Home.HERE) {
                home = Home.WAS_HERE;
            } else {
                home = Home.ELSEWHERE;
            }
            return home;
        }

        private boolean onDisk(String book) {
            Boolean known = booksOnDisk.get(book);
            boolean there;
            if (known != null) {
                there = known.booleanValue();
            } else {
                there = isBookFile(book);
                booksOnDisk.put(book, Boolean.valueOf(there));
            }
            return there;
        }

        private static boolean isBookFile(String book) {
            boolean there;
            try {
                there = Files.isRegularFile(Path.of(book));
            } catch (InvalidPathException notAPath) {
                there = false;
            }
            return there;
        }

        private Home mappedHome(Claim claim) {
            Path holding = null;
            int matches = 0;
            for (Path world : mapped) {
                if (mappedBy(world, claim.revised())) {
                    boolean holds = holdsCorners(world, claim);
                    if (holds) {
                        holding = world;
                        matches++;
                    }
                }
            }
            Home home;
            if (holding == null || matches != 1) {
                home = Home.UNTOLD;
            } else if (placeOf(holding).equals(here)) {
                home = Home.HERE;
            } else {
                home = Home.ELSEWHERE;
            }
            return home;
        }

        private static boolean mappedBy(Path world, long revised) {
            return (revised <= 0L) || (firstMapped(world) <= revised);
        }

        private static long firstMapped(Path world) {
            long millis;
            try {
                millis = Files.getLastModifiedTime(world.resolve(MapStorage.MARKER)).toMillis();
            } catch (IOException unread) {
                millis = Long.MAX_VALUE;
            }
            return millis;
        }

        private boolean holdsCorners(Path world, Claim claim) {
            Path regions = world.resolve(MapStorage.dimensionFolder(MapStorage.asStored(claim.dimension())));
            boolean holds = ofDimension(regions, claim.dimension());
            if (holds) {
                double[] xs = claim.xs();
                double[] zs = claim.zs();
                int regionX = regionOf(xs[0]);
                int regionZ = regionOf(zs[0]);
                Path region = regions.resolve(MapRegion.fileName(regionX, regionZ));
                holds = Files.isRegularFile(region);
                for (int at = 1; holds && (at < xs.length); at++) {
                    int nextX = regionOf(xs[at]);
                    int nextZ = regionOf(zs[at]);
                    if ((nextX != regionX) || (nextZ != regionZ)) {
                        region = regions.resolve(MapRegion.fileName(nextX, nextZ));
                        regionX = nextX;
                        regionZ = nextZ;
                    }
                    holds = Files.isRegularFile(region);
                }
            }
            return holds;
        }

        private boolean ofDimension(Path regions, String dimension) {
            Boolean known = dimensionFolders.get(regions);
            boolean ours;
            if (known != null) {
                ours = known.booleanValue();
            } else {
                Path marker = regions.resolve(MapStorage.DIMENSION_MARKER);
                ours = !Files.isRegularFile(marker) || MapStorage.asStored(dimension).equals(markerText(marker));
                dimensionFolders.put(regions, Boolean.valueOf(ours));
            }
            return ours;
        }

        private static String markerText(Path marker) {
            String text;
            try {
                text = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).strip();
            } catch (IOException unread) {
                text = "";
            }
            return text;
        }

        private static int regionOf(double corner) {
            return MapRegion.ofBlock((int) Math.floor(corner));
        }

        private static Path placeOf(Path folder) {
            return folder.toAbsolutePath().normalize();
        }
    }

    public Said where() {
        Path path = file.get();
        if (path == null) {
            return noWorld();
        }
        ClaimBook book = readOnlyBook(path);
        String unreadable = book.unreadable();
        int count = book.size();
        return new Said(unreadable.isEmpty(), unreadable.isEmpty()
                ? count + (count == 1 ? " claim is" : " claims are")
                        + " written to " + path + "." + LINE_BREAK + publishing()
                : path + " could not be read (" + unreadable + "). No"
                        + " claim can change until"
                        + " you move that file aside.");
    }

    private static String publishing() {
        return "A shared claim reaches every node."
                + " To show any other claim, copy its file into the"
                + " node's published site directory, beside claims-archive.json.";
    }

    private ClaimBook readOnlyBook(Path path) {
        if (path == null) {
            return ClaimBook.load(null);
        }
        ClaimBook book;
        try {
            long generation = readCaches.namesGeneration.get();
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                book = ClaimBook.load(path);
            } else {
                long stamp = attributes.lastModifiedTime().toMillis();
                long size = attributes.size();
                ClaimBook remembered = readCaches.rememberedBook(path, stamp, size);
                if (remembered != null) {
                    book = remembered;
                } else {
                    book = ClaimBook.load(path);
                    if (book.unreadable().isEmpty()) {
                        readCaches.rememberBook(path, stamp, size, book, generation);
                    }
                }
            }
        } catch (IOException unreadable) {
            book = ClaimBook.load(path);
        }
        return book;
    }

    private Said startChecked(String wanted) {
        Path path = file.get();
        if (path == null) {
            return noWorld();
        }
        ClaimBook book = ClaimBook.load(path);
        if (!book.unreadable().isEmpty()) {
            return damaged(book, path);
        }
        int taken = book.indexByCleanName(wanted, -1);
        if (taken >= 0) {
            String held = nameAt(book, taken);
            return new Said(false, "There is a claim called \"" + held
                    + "\". Pick another name, or remove that one with /geosurvey"
                    + " claim remove " + held + ".");
        }
        if (book.full()) {
            return new Said(false, "This installation holds "
                    + ClaimBook.MAX_CLAIMS + " claims. Remove one before starting"
                    + " another.");
        }
        return STARTED;
    }

    private record FinishResult(Said said, boolean discarded) {
    }

    private FinishResult finishChecked(Claim made, long now, long reservation, Path startedBook) {
        if (!writingBook.compareAndSet(false, true)) {
            return new FinishResult(commandWaiting(), false);
        }
        Said said;
        try {
            Path path = file.get();
            if (path == null) {
                return new FinishResult(noWorld(), false);
            }
            if (startedBook != null && !startedBook.equals(path)) {
                return new FinishResult(noted(NOTHING_DRAWN_TO_FINISH,
                        thrownAway(made.name(), made.corners())), true);
            }
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else if (reservation >= 0 && !isCurrentGeneration(reservation)) {
                said = unwritten(made);
            } else {
                ClaimBook.Refusal refused = book.insertIfAllowed(made);
                if (refused == ClaimBook.Refusal.DUPLICATE) {
                    said = new Said(false, "A claim called \"" + made.name() + "\" appeared"
                            + " while you were drawing."
                            + " Remove that one, or rename this one, and finish again.");
                } else if (refused == ClaimBook.Refusal.FULL) {
                    said = new Said(false, "This installation holds "
                            + ClaimBook.MAX_CLAIMS + " claims."
                            + " Remove one and finish again.");
                } else {
                    said = write(book, path, now, "Claimed \"" + made.name() + "\": "
                            + made.corners() + " corners in " + made.dimension() + ", drawn in "
                            + LOWERED[made.colour().ordinal()].label() + ".");
                }
            }
        } finally {
            writingBook.set(false);
        }
        return new FinishResult(said, false);
    }

    private static Said unwritten(Claim made) {
        return new Said(false, "\"" + made.name() + "\" was not finished:"
                + " the claim command stopped responding."
                + " Nothing written.");
    }

    private Outcome submitClaim(Supplier<Said> action, Consumer<Said> answer) {
        return submitClaim(action, answer, null);
    }

    private Outcome submitClaim(Supplier<Said> action, Consumer<Said> answer,
                                LongConsumer onReserved) {
        WorkPool pool = commandPool;
        Outcome outcome;
        if (pool == null) {
            Said said = inline(action);
            answer.accept(said);
            outcome = said.ok() ? Outcome.ANSWERED_OK : Outcome.ANSWERED_REFUSAL;
        } else {
            long generation = commandSlot.take();
            if (generation == NO_RESERVATION) {
                answer.accept(commandWaiting());
                outcome = Outcome.ANSWERED_REFUSAL;
            } else {
                if (onReserved != null) {
                    onReserved.accept(generation);
                }
                commandSlot.started();
                outcome = queueReserved(pool, generation, action, answer);
            }
        }
        return outcome;
    }

    private Outcome submitReserved(long generation, Supplier<Said> action,
                                   Consumer<Said> answer) {
        WorkPool pool = commandPool;
        Outcome outcome;
        if (pool == null) {
            Said said = inline(action);
            completeCommand(generation);
            answer.accept(said);
            outcome = said.ok() ? Outcome.ANSWERED_OK : Outcome.ANSWERED_REFUSAL;
        } else {
            outcome = queueReserved(pool, generation, action, answer);
        }
        return outcome;
    }

    private Outcome queueReserved(WorkPool pool, long generation, Supplier<Said> action,
                                  Consumer<Said> answer) {
        AtomicBoolean answered = new AtomicBoolean();
        Consumer<Said> once = said -> {
            if (answered.compareAndSet(false, true)) {
                answer.accept(said);
            }
        };
        Said unavailable = new Said(false, "Claim work could not be completed. Nothing"
                + " changed.");
        boolean queued = pool.submit(action, said -> {
            completeCommand(generation);
            once.accept(said);
        }, () -> {
            completeCommand(generation);
            once.accept(unavailable);
        }, computed -> {
            completeCommand(generation);
            once.accept(computed == null ? unavailable : computed);
        });
        Outcome outcome;
        if (!queued) {
            completeCommand(generation);
            once.accept(unavailable);
            outcome = Outcome.ANSWERED_REFUSAL;
        } else {
            outcome = Outcome.ANSWER_STILL_QUEUED;
        }
        return outcome;
    }

    private static Said inline(Supplier<Said> action) {
        Said said;
        AtomicFileReplace.beginNoWait();
        try {
            said = action.get();
        } finally {
            AtomicFileReplace.endNoWait();
        }
        return said;
    }

    private Said write(ClaimBook book, Path path, long now, String said) {
        return write(book, path, now, said, false);
    }

    // dropped: the save took a shared claim out of the book or out of sharing.
    private Said write(ClaimBook book, Path path, long now, String said, boolean dropped) {
        Said failed;
        try {
            book.save(path, now);
            failed = null;
        } catch (IOException couldNotWrite) {
            saveFailure.failed(couldNotWrite);
            failed = new Said(false, said + " Not saved"
                    + ": " + couldNotWrite.getMessage());
        }
        Said written;
        if (failed != null) {
            written = failed;
        } else {
            readCaches.forgetNames();
            Sent sent = told(path, book);
            written = new Said(true, said + ending(sent, book, dropped) + unsendable(book));
        }
        return written;
    }

    private Sent told(Path path, ClaimBook book) {
        Sent sent = Sent.NOTHING;
        BookSaved listener = afterSave;
        if (listener != null) {
            try {
                sent = listener.saved(path, book);
            } catch (RuntimeException fromListener) {
                sent = Sent.NOT_TOLD;
                listenerFailureNote.accept("A claim listener failed: " + fromListener);
            }
        }
        return sent;
    }

    private static String ending(Sent sent, ClaimBook book, boolean dropped) {
        String end;
        if (sent == Sent.TO_NODE) {
            end = ON_ITS_WAY;
        } else if ((sent == Sent.NOT_TOLD) && (dropped || holdsShared(book))) {
            end = NODE_NOT_TOLD;
        } else if ((sent == Sent.HELD) && (dropped || holdsShared(book))) {
            end = NODE_NOT_TOLD_YET;
        } else {
            end = SAVED;
        }
        return end;
    }

    // A sentence for each shared claim in the book that the collector cannot take, and why.
    private static String unsendable(ClaimBook book) {
        StringBuilder said = null;
        for (Claim claim : book.claims()) {
            if (claim.shared()) {
                Shareable fit = shareable(claim);
                if (fit != Shareable.YES) {
                    if (said == null) {
                        said = new StringBuilder();
                    }
                    said.append(LINE_BREAK).append('"').append(flat(claim.name()))
                            .append("\" cannot be sent to the collector: ").append(fit.wrong).append('.');
                }
            }
        }
        return said == null ? "" : said.toString();
    }

    private static boolean holdsShared(ClaimBook book) {
        List<Claim> claims = book.claims();
        boolean shared = false;
        for (int at = 0; !shared && (at < claims.size()); at++) {
            shared = claims.get(at).shared();
        }
        return shared;
    }

    // The line for what the collector answered about the claim of that name.
    public static Said nodeAnswer(NodeAnswer answer, String name) {
        String shown = flat(name);
        return switch (answer) {
            case SAVED -> new Said(true, "The collector confirmed \"" + shown + "\"." + SAVED);
            case REFUSED -> new Said(false, "The collector refused \"" + shown + "\".");
            case NONE -> new Said(false, "The collector has not confirmed \"" + shown
                    + "\". It waits to be sent.");
            case NOT_SHARING -> new Said(false, "The collector keeps no shared claims. \"" + shown
                    + "\" waits to be sent.");
            case FULL -> new Said(false, "The collector has no room for \"" + shown + "\". It waits to be sent.");
            case NOT_TOLD_TO_DROP -> new Said(false, "The collector was not told to drop \"" + shown + "\".");
        };
    }

    // The record's rules asked of this claim, its owner name and corners first.
    public static Shareable shareable(Claim claim) {
        Shareable fit;
        if (!SharedRecord.isName(claim.owner())) {
            fit = Shareable.OWNER_NAME;
        } else if (!SharedRecord.isCornerCount(claim.corners())) {
            fit = Shareable.CORNER_COUNT;
        } else if (!recordCoordinates(claim.rawXs()) || !recordCoordinates(claim.rawZs())) {
            fit = Shareable.CORNER;
        } else if (!SharedRecord.isId(claim.id())) {
            fit = Shareable.ID;
        } else if (!SharedRecord.isLabel(SharedRecord.labelFor(claim.name()))) {
            fit = Shareable.NAME;
        } else if (!SharedRecord.isWorld(claim.dimension())) {
            fit = Shareable.WORLD;
        } else {
            fit = Shareable.YES;
        }
        return fit;
    }

    private static boolean recordCoordinates(double[] values) {
        boolean whole = true;
        for (int at = 0; whole && (at < values.length); at++) {
            whole = SharedRecord.isCoordinate(values[at]);
        }
        return whole;
    }

    // Whether a claim's entry leaves it shared; entry may be null: it has none.
    private static boolean stillShared(SharedEntry entry) {
        return (entry == null) || (entry.kept() == Kept.SHARED);
    }

    private static Said damaged(ClaimBook book, Path path) {
        return new Said(false, path + " could not be read ("
                + book.unreadable() + ")."
                + " No claim can change until you move that file aside.");
    }

    private static Said nowhere(String doing) {
        return new Said(false, "There is no world to " + doing + " a claim"
                + " in.");
    }

    private static Said commandWaiting() {
        return new Said(false, "A claim command is still working. Wait for its answer.");
    }

    private CommandState expiredCommand() {
        return commandSlot.expire();
    }

    private void completeCommand(long generation) {
        commandSlot.complete(generation);
    }

    private boolean isCurrentGeneration(long generation) {
        return commandSlot.isCurrent(generation);
    }

    private static boolean outsideWorldBorder(Standing here) {
        return !(here.x() >= -Claim.LIMIT && here.x() <= Claim.LIMIT
                && here.z() >= -Claim.LIMIT && here.z() <= Claim.LIMIT);
    }

    private static Said outsideWorldBorder() {
        return OUTSIDE_WORLD_BORDER;
    }

    private static Said invalidBoundary(RuntimeException invalid) {
        return new Said(false, "This boundary cannot be claimed: " + invalid.getMessage()
                + ". Change the corners and try again.");
    }

    private static Said afterExpiredCommand(Said said, boolean expired) {
        if (!expired) {
            return said;
        }
        return new Said(said.ok(), said.text() + LINE_BREAK + "The earlier claim command stopped"
                + " responding. This pen is available again.");
    }

    private static String renameTarget(String wanted) {
        return wanted == null ? "" : wanted.trim();
    }

    private static Said unnamedTarget() {
        return NEEDS_NEW_NAME;
    }

    private static Said noSuchClaim(ClaimBook book, String handle) {
        String typed = handle == null ? "" : handle.trim();
        int size = book.size();
        int shown = Math.min(size, NAMES_LISTED);
        StringBuilder said = new StringBuilder(NO_SUCH_CLAIM_CHARS + CHARS_PER_LISTED_NAME * shown);
        said.append("No claim is called \"").append(flat(typed)).append("\".");
        if (size == 0) {
            said.append(" There are none.");
        } else {
            said.append(" There").append(size == 1 ? " is " : " are ");
            List<Claim> all = book.claims();
            for (int at = 0; at < shown; at++) {
                said.append(at == 0 ? "\"" : ", \"").append(flat(all.get(at).name())).append('"');
            }
            if (size > shown) {
                said.append(" and ").append(size - shown).append(" more");
            }
            said.append('.');
        }
        return new Said(false, said.toString());
    }

    private static Said unknownColour(String wanted) {
        StringBuilder said = new StringBuilder("\"")
                .append(wanted == null ? "" : flat(wanted.trim()))
                .append("\" is not a colour. Choices:");
        for (MarkerColour colour : COLOURS) {
            LoweredColour lowered = LOWERED[colour.ordinal()];
            said.append(LINE_BREAK).append(lowered.name()).append(" - ").append(lowered.meaning()).append('.');
        }
        return new Said(false, said.toString());
    }

    private static MarkerColour colour(String wanted) {
        String name = wanted == null ? "" : wanted.trim();
        MarkerColour[] all = COLOURS;
        int at = 0;
        while (at < all.length && !all[at].name().equalsIgnoreCase(name)) {
            at++;
        }
        return at < all.length ? all[at] : null;
    }

    private record LoweredColour(String name, String label, String meaning) {
    }

    private static LoweredColour[] loweredColours() {
        MarkerColour[] all = COLOURS;
        LoweredColour[] out = new LoweredColour[all.length];
        for (MarkerColour colour : all) {
            out[colour.ordinal()] = new LoweredColour(
                    colour.name().toLowerCase(java.util.Locale.ROOT),
                    colour.label().toLowerCase(java.util.Locale.ROOT),
                    colour.meaning().toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }
}
