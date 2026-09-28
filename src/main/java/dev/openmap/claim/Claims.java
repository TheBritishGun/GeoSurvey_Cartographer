package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import dev.openmap.map.MapStorage;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// No Minecraft in this class. The book is read and written per command, never cached.
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

    private static final MarkerColour[] COLOURS = MarkerColour.values();

    private static final LoweredColour[] LOWERED = loweredColours();

    private static final Said STARTED = new Said(true, "");

    private static final Said OUTSIDE_WORLD_BORDER = new Said(false,
            "That corner is outside the world border. It cannot"
                    + " belong to a claim.");

    private static final Said NEEDS_NEW_NAME = new Said(false,
            "Give the new name: /geosurvey claim rename"
                    + " <name> <new name>.");

    private static final Said NEEDS_CLAIM_NAME = new Said(false,
            "A claim needs a name. It is what you type to"
                    + " edit or remove it.");

    private static final Said NOTHING_DRAWN_TO_FINISH = new Said(false,
            "No boundary is being drawn. Start one here"
                    + " with /geosurvey claim start <name>.");

    private static final Said NOTHING_DRAWN_TO_UNDO = new Said(false,
            "No boundary is being drawn. There is no"
                    + " corner to take back.");

    private static final Said NOTHING_DRAWN_TO_CANCEL = new Said(false,
            "No boundary is being drawn.");

    private static final Said NOTHING_DRAWN_TO_RECOLOUR = new Said(false,
            "No boundary is being drawn. To change a"
                    + " finished claim use /geosurvey claim recolour <name>"
                    + " <colour>.");

    private static final Said CORNER_REPEATED = new Said(false,
            "You have not moved since the last"
                    + " corner. Walk to the next turn first.");

    // Where the player is, and who they are, at the moment they typed.
    public record Standing(String dimension, double x, double z,
                           String player, String playerId) {

        public Standing {
            dimension = dimension == null ? "" : dimension.trim();
            player = player == null ? "" : player.trim();
            playerId = playerId == null ? "" : playerId.trim();
        }
    }

    // What to say back, and whether it was a refusal.
    public record Said(boolean ok, String text) {
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

    private final LongSupplier clock;

    private final LongSupplier monotonic;

    private final Supplier<String> ids;

    private final ClaimPen pen = new ClaimPen();

    private final AtomicBoolean writingBook = new AtomicBoolean();

    private final ReadCaches readCaches = new ReadCaches();

    private volatile WorkPool namesPool;

    private volatile WorkPool commandPool;

    private volatile SaveFailureSink saveFailure = problem -> {
    };

    private final AtomicBoolean namesRefreshPending = new AtomicBoolean();

    private final CommandSlot commandSlot;

    public Claims(Supplier<Path> file) {
        this(file, System::currentTimeMillis, Claims::monotonicMillis, Claims::newClaimId);
    }

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
        this(file, clock, clock, ids);
    }

    private Claims(Supplier<Path> file, LongSupplier clock, LongSupplier monotonic,
                   Supplier<String> ids) {
        this.file = file;
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
        // -1 when there is no pool.
        long[] reserved = {-1L};
        return submitClaim(() -> startChecked(wanted), checked -> {
            if (!checked.ok()) {
                answer.accept(checked);
                return;
            }
            if (reserved[0] >= 0 && !isCurrentGeneration(reserved[0])) {
                answer.accept(new Said(false, "\"" + wanted + "\" was not started:"
                        + " this claim command had stopped responding."
                        + " Nothing drawn over."));
                return;
            }
            boolean interrupted = pen.drawing();
            String was = pen.name();
            int had = pen.size();
            pen.begin(wanted, here.dimension(), here.x(), here.z());
            answer.accept(new Said(true, "Started \"" + wanted + "\" at "
                    + (long) Math.floor(here.x()) + "," + (long) Math.floor(here.z()) + " in "
                    + here.dimension() + "."
                    + (interrupted
                            ? " The unfinished \"" + was + "\" and its " + had
                                    + (had == 1 ? " corner was" : " corners were")
                                    + " thrown away."
                            : "")
                    + " Close it with /geosurvey claim finish."));
        }, generation -> reserved[0] = generation);
    }

    public Outcome finishAsync(Standing here, Consumer<Said> answer) {
        if (!pen.drawing()) {
            answer.accept(NOTHING_DRAWN_TO_FINISH);
            return Outcome.ANSWERED_REFUSAL;
        }
        int corners = pen.size();
        if (corners < Claim.MIN_CORNERS) {
            answer.accept(new Said(false, "\"" + pen.name() + "\" has " + corners
                    + (corners == 1 ? " corner" : " corners") + " and a claim"
                    + " wants at least " + Claim.MIN_CORNERS + ". Walk to the next"
                    + " turn and run /geosurvey claim corner."));
            return Outcome.ANSWERED_REFUSAL;
        }
        Outcome outcome;
        if (commandPool == null) {
            outcome = finishUnreserved(here, answer);
        } else {
            outcome = finishReserved(here, answer);
        }
        return outcome;
    }

    private Outcome finishUnreserved(Standing here, Consumer<Said> answer) {
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
            outcome = submitClaim(() -> finishChecked(closed, now, NO_RESERVATION), written -> {
                if (written.ok()) {
                    pen.clear();
                }
                answer.accept(written);
            });
        }
        return outcome;
    }

    private Outcome finishReserved(Standing here, Consumer<Said> answer) {
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
            outcome = submitReserved(generation, () -> finishChecked(closed, now, generation), written -> {
                if (written.ok() && isCurrentGeneration(generation)) {
                    pen.clear();
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

    // Claim names for tab completion. Empty when the book is missing or damaged.
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
        ClaimBook book = readOnlyBook(path);
        if (!book.unreadable().isEmpty()) {
            return damaged(book, path);
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
                said.append(" \"").append(claim.name()).append("\" - ")
                        .append(LOWERED[claim.colour().ordinal()].label())
                        .append(", ").append(claim.corners()).append(" corners in ")
                        .append(claim.dimension()).append(" from ")
                        .append((long) Math.floor(box[BOUNDS_MIN_X])).append(',')
                        .append((long) Math.floor(box[BOUNDS_MIN_Z]))
                        .append(" to ").append((long) Math.floor(box[BOUNDS_MAX_X])).append(',')
                        .append((long) Math.floor(box[BOUNDS_MAX_Z]));
                if (!claim.owner().isEmpty()) {
                    said.append(", claimed by ").append(claim.owner());
                }
                said.append('.');
            }
        }
        if (pen.drawing()) {
            int corners = pen.size();
            said.append(" You are part-way through \"").append(pen.name())
                    .append("\": ").append(corners)
                    .append(corners == 1 ? " corner" : " corners")
                    .append(corners >= Claim.MIN_CORNERS
                            ? pen.closeable() ? ", enough to finish."
                                    : ". Not ready to finish: a claim must enclose ground."
                            : ". Not yet enough: a claim wants at least "
                                    + Claim.MIN_CORNERS + ".");
        }
        said.append(' ').append(publishing());
        return new Said(true, said.toString());
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
        Said checked = startChecked(wanted);
        if (!checked.ok()) {
            return checked;
        }
        boolean interrupted = pen.drawing();
        String was = pen.name();
        int had = pen.size();
        pen.begin(wanted, here.dimension(), here.x(), here.z());
        return new Said(true, "Started \"" + wanted + "\" at " + (long) Math.floor(here.x())
                + "," + (long) Math.floor(here.z()) + " in " + here.dimension() + "."
                + (interrupted
                        ? " The unfinished \"" + was + "\" and its " + had
                                + (had == 1 ? " corner was" : " corners were")
                                + " thrown away."
                        : "")
                + " Close it with /geosurvey claim finish.");
    }

    public Said corner(Standing here) {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else if (here == null) {
            said = afterExpiredCommand(nowhere("add a corner to"), expired);
        } else if (!pen.drawing()) {
            said = afterExpiredCommand(NOTHING_DRAWN_TO_FINISH, expired);
        } else {
            ClaimPen.Refusal why = pen.corner(here.dimension(), here.x(), here.z());
            Said placed = switch (why) {
                case NONE -> {
                    int corners = pen.size();
                    yield new Said(true, "Corner " + corners + " at "
                            + (long) Math.floor(here.x()) + "," + (long) Math.floor(here.z()) + "."
                            + (corners >= Claim.MIN_CORNERS
                                    ? pen.closeable()
                                            ? " That is enough to close: /geosurvey claim finish."
                                            : " A claim must enclose ground before it can close."
                                    : " At least " + Claim.MIN_CORNERS + " are wanted."));
                }
                case ELSEWHERE -> new Said(false, "\"" + pen.name() + "\" is being drawn"
                        + " in " + pen.dimension() + " and you are in " + here.dimension()
                        + ". Go back, or start again here.");
                case REPEATED -> CORNER_REPEATED;
                case FULL -> new Said(false, "\"" + pen.name() + "\" has "
                        + Claim.MAX_CORNERS + " corners, the most a viewer draws. Close"
                        + " it with /geosurvey claim finish.");
                case OUTSIDE_BORDER -> outsideWorldBorder();
            };
            said = afterExpiredCommand(placed, expired);
        }
        return said;
    }

    public Said undo() {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else if (!pen.drawing()) {
            said = afterExpiredCommand(NOTHING_DRAWN_TO_UNDO, expired);
        } else if (!pen.undo()) {
            said = afterExpiredCommand(new Said(false, "\"" + pen.name() + "\" has no corners left."
                    + " Throw it away with /geosurvey claim cancel, or put one down"
                    + " here with /geosurvey claim corner."), expired);
        } else {
            int corners = pen.size();
            said = afterExpiredCommand(new Said(true, "Took back the last corner. " + corners
                    + (corners == 1 ? " corner" : " corners") + " left"
                    + (corners >= Claim.MIN_CORNERS
                            ? pen.closeable() ? ", still enough to finish"
                                    : ". A claim must enclose ground before it can finish"
                            : ", not yet enough to close") + "."), expired);
        }
        return said;
    }

    public Said cancel() {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else if (!pen.drawing()) {
            said = afterExpiredCommand(NOTHING_DRAWN_TO_CANCEL, expired);
        } else {
            String was = pen.name();
            int had = pen.size();
            pen.clear();
            said = afterExpiredCommand(new Said(true, "Threw away \"" + was + "\" and its " + had
                    + (had == 1 ? " corner" : " corners")
                    + ". Nothing on disk touched."), expired);
        }
        return said;
    }

    public Said penColour(String wanted) {
        CommandState command = expiredCommand();
        boolean expired = command == CommandState.EXPIRED;
        Said said;
        if (command == CommandState.WAITING) {
            said = commandWaiting();
        } else if (!pen.drawing()) {
            said = afterExpiredCommand(NOTHING_DRAWN_TO_RECOLOUR, expired);
        } else {
            MarkerColour colour = colour(wanted);
            if (colour == null) {
                said = afterExpiredCommand(unknownColour(wanted), expired);
            } else {
                pen.colour(colour);
                said = afterExpiredCommand(new Said(true, "\"" + pen.name() + "\" will be drawn in "
                        + LOWERED[colour.ordinal()].label() + " - "
                        + LOWERED[colour.ordinal()].meaning() + "."), expired);
            }
        }
        return said;
    }

    public Said finish(Standing here) {
        if (!pen.drawing()) {
            return NOTHING_DRAWN_TO_FINISH;
        }
        int corners = pen.size();
        if (corners < Claim.MIN_CORNERS) {
            return new Said(false, "\"" + pen.name() + "\" has " + corners
                    + (corners == 1 ? " corner" : " corners") + " and a claim"
                    + " wants at least " + Claim.MIN_CORNERS + ". Walk to the next"
                    + " turn and run /geosurvey claim corner.");
        }
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
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
                                + " while you were drawing. Nothing thrown away."
                                + " Remove that one, or rename this one, and finish again.");
                    } else if (refused == ClaimBook.Refusal.FULL) {
                        said = new Said(false, "This installation holds "
                                + ClaimBook.MAX_CLAIMS + " claims. Nothing thrown away;"
                                + " remove one and finish again.");
                    } else {
                        said = write(book, path, now, "Claimed \"" + made.name() + "\": "
                                + made.corners() + " corners in " + made.dimension() + ", drawn in "
                                + LOWERED[made.colour().ordinal()].label() + ".");
                        if (said.ok()) {
                            pen.clear();
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
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                int at = book.indexByName(handle);
                if (at < 0) {
                    said = noSuchClaim(book, handle);
                } else {
                    Claim claim = book.at(at);
                    int clash = book.indexByName(to);
                    if (clash >= 0 && clash != at) {
                        said = new Said(false, "There is a claim called \"" + to + "\".");
                    } else {
                        long now = clock.getAsLong();
                        if (!book.replaceAt(at, claim.named(to, now))) {
                            said = noSuchClaim(book, handle);
                        } else {
                            said = write(book, path, now, "\"" + claim.name() + "\" is now \"" + to
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
                        said = write(book, path, now, "\"" + claim.name() + "\" is now drawn in "
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
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else {
                Claim gone = book.remove(handle);
                if (gone == null) {
                    said = noSuchClaim(book, handle);
                } else {
                    said = write(book, path, clock.getAsLong(), "Removed \"" + gone.name() + "\"."
                            + " A node holding a copy of the file keeps"
                            + " drawing it until given this one.");
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    // Where the file is, which is the whole of how a claim reaches a node.
    public Said where() {
        Path path = file.get();
        ClaimBook book = readOnlyBook(path);
        String unreadable = book.unreadable();
        int count = book.size();
        return new Said(unreadable.isEmpty(), unreadable.isEmpty()
                ? count + (count == 1 ? " claim is" : " claims are")
                        + " written to " + path + ". " + publishing()
                : path + " could not be read (" + unreadable + "). No"
                        + " claim can be added or removed until it is moved aside."
                        + " Nothing written over it.");
    }

    private static String publishing() {
        return "A claim reaches a map by copying its file into the"
                + " node's published site directory, beside claims-archive.json."
                + " It is not signed"
                + " and does not reach"
                + " other nodes.";
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
        ClaimBook book = ClaimBook.load(path);
        if (!book.unreadable().isEmpty()) {
            return damaged(book, path);
        }
        if (book.byName(wanted) != null) {
            return new Said(false, "There is a claim called \"" + wanted
                    + "\". Pick another name, or remove that one with /geosurvey"
                    + " claim remove " + wanted + ".");
        }
        if (book.full()) {
            return new Said(false, "This installation holds "
                    + ClaimBook.MAX_CLAIMS + " claims. Remove one before starting"
                    + " another.");
        }
        return STARTED;
    }

    private Said finishChecked(Claim made, long now, long reservation) {
        if (!writingBook.compareAndSet(false, true)) {
            return commandWaiting();
        }
        Said said;
        try {
            Path path = file.get();
            ClaimBook book = ClaimBook.load(path);
            if (!book.unreadable().isEmpty()) {
                said = damaged(book, path);
            } else if (reservation >= 0 && !isCurrentGeneration(reservation)) {
                said = unwritten(made);
            } else {
                ClaimBook.Refusal refused = book.insertIfAllowed(made);
                if (refused == ClaimBook.Refusal.DUPLICATE) {
                    said = new Said(false, "A claim called \"" + made.name() + "\" appeared"
                            + " while you were drawing. Nothing thrown away."
                            + " Remove that one, or rename this one, and finish again.");
                } else if (refused == ClaimBook.Refusal.FULL) {
                    said = new Said(false, "This installation holds "
                            + ClaimBook.MAX_CLAIMS + " claims. Nothing thrown away;"
                            + " remove one and finish again.");
                } else {
                    said = write(book, path, now, "Claimed \"" + made.name() + "\": "
                            + made.corners() + " corners in " + made.dimension() + ", drawn in "
                            + LOWERED[made.colour().ordinal()].label() + ".");
                }
            }
        } finally {
            writingBook.set(false);
        }
        return said;
    }

    private static Said unwritten(Claim made) {
        return new Said(false, "\"" + made.name() + "\" was not started:"
                + " this claim command had stopped responding."
                + " Nothing written. Nothing on disk touched.");
    }

    private Outcome submitClaim(Supplier<Said> action, Consumer<Said> answer) {
        return submitClaim(action, answer, null);
    }

    // Also hands the generation to onReserved synchronously, before action runs.
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

    // The rest of submitClaim(), for a caller that has already reserved the slot.
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
        MapStorage.FileReplace.beginNoWait();
        try {
            said = action.get();
        } finally {
            MapStorage.FileReplace.endNoWait();
        }
        return said;
    }

    private Said write(ClaimBook book, Path path, long now, String said) {
        Said failed;
        try {
            book.save(path, now);
            failed = null;
        } catch (IOException couldNotWrite) {
            saveFailure.failed(couldNotWrite);
            failed = new Said(false, said + " The file could not be written."
                    + " Nothing kept: " + couldNotWrite.getMessage());
        }
        Said written;
        if (failed != null) {
            written = failed;
        } else {
            readCaches.forgetNames();
            written = new Said(true, said + " Saved.");
        }
        return written;
    }

    private static Said damaged(ClaimBook book, Path path) {
        return new Said(false, path + " could not be read ("
                + book.unreadable() + "). Nothing written over it."
                + " Nothing can be added or removed until moved aside.");
    }

    private static Said nowhere(String doing) {
        return new Said(false, "There is no world to " + doing + " a claim in."
                + " A claim's corners are places you stand.");
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
                + ". Nothing written. Change the corners and try again.");
    }

    private static Said afterExpiredCommand(Said said, boolean expired) {
        if (!expired) {
            return said;
        }
        return new Said(said.ok(), said.text() + " The earlier claim command stopped"
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
        StringBuilder said = new StringBuilder(NO_SUCH_CLAIM_CHARS + CHARS_PER_LISTED_NAME * size);
        said.append("No claim is called \"").append(typed).append("\".");
        if (size == 0) {
            said.append(" There are none yet.");
        } else {
            said.append(" There").append(size == 1 ? " is " : " are ");
            boolean first = true;
            for (Claim claim : book.claims()) {
                if (!first) {
                    said.append(", ");
                }
                said.append('"').append(claim.name()).append('"');
                first = false;
            }
            said.append('.');
        }
        return new Said(false, said.toString());
    }

    private static Said unknownColour(String wanted) {
        MarkerColour[] all = COLOURS;
        MarkerColour last = all[all.length - 1];
        StringBuilder said = new StringBuilder("\"")
                .append(wanted == null ? "" : wanted.trim())
                .append("\" is not one of the colours. They are:");
        for (MarkerColour colour : all) {
            LoweredColour lowered = LOWERED[colour.ordinal()];
            said.append(' ').append(lowered.name())
                    .append(" (").append(lowered.meaning()).append(')').append(
                    colour == last ? '.' : ',');
        }
        return new Said(false, said.toString());
    }

    // The colour of that name, or null.
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
