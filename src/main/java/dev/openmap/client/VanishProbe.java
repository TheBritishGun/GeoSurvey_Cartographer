package dev.openmap.client;

import dev.openmap.json.JsonText;
import dev.openmap.map.LabelText;
import dev.openmap.share.RosterReport;
import dev.sandpaper.core.Background;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.world.level.GameType;

// Tick thread: observe(), armed(). Writer thread: drain(), which owns written,
// faults, faultSaidAt, renderBuffer; due, owed, out, closing, stopped are shared.
class VanishProbe implements AutoCloseable {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final String DIRECTORY = "geosurvey diagnostics";

    static final long HEARTBEAT_MILLIS = 10_000L;

    private static final long HEARTBEAT_NANOS = HEARTBEAT_MILLIS * 1_000_000L;

    private static final long MILLIS_PER_SECOND = 1_000L;

    static final long MAX_BYTES = 4L << 20;

    private static final int MEGABYTE = 1 << 20;

    private static final String CEILING = "\n--- the probe reached its "
            + MAX_BYTES / MEGABYTE + " MB ceiling and stopped recording.\n";

    private static final int QUEUE_LIMIT = 16;

    private static final int RENDER_BUFFER_CHARS = 1024;

    private static final long CLOSE_WAIT_MILLIS = 10_000L;

    private static final long DRAIN_POLL_MILLIS = 13_000L;

    private static final long FAULT_SAY_NANOS = 60L * 1_000_000_000L;

    private static final Set<StandardOpenOption> APPENDING =
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.APPEND);

    private static final Object END = new Object();

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final int NIBBLE_MASK = 0xF;

    private static final int FIRST_NIBBLE_SHIFT = 12;

    private static final int SECOND_NIBBLE_SHIFT = 8;

    private static final int THIRD_NIBBLE_SHIFT = 4;

    private static final int SPACE = LabelText.FIRST_PRINTABLE_ASCII;

    private static final int DELETE = LabelText.FIRST_NON_PRINTABLE_ASCII;


    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final int FIRST_SUFFIX = 2;

    private static final int LAST_SUFFIX = 16;

    private final Path file;

    // On the nanoTime scale.
    private final LongSupplier clock;

    private volatile SeekableByteChannel out;

    private final ArrayBlockingQueue<Object> due = new ArrayBlockingQueue<>(QUEUE_LIMIT);

    private final StringBuilder renderBuffer = new StringBuilder(RENDER_BUFFER_CHARS);

    private final List<Read> draftReads = new ArrayList<>();

    private final Set<UUID> reportedIds = new ObjectOpenHashSet<>();

    private final Gate draftGate = new Gate(null, false, false, false, false, false,
            false, false);

    private Thread writer;

    private volatile StoppableWorkers.Registration stopRegistration;

    private volatile boolean closing;

    private long behind;

    // In characters, not encoded bytes.
    private long written;

    private long beats;

    private long records;

    private long suppressed;

    private Picture picture;

    // On the nanoTime scale.
    private long wroteAt;

    private volatile boolean stopped;

    private long faults;

    private long faultSaidAt;

    // -1 before the first record.
    private volatile long sawPositions = -1;

    private volatile long sawRosters = -1;

    // -1 when not hidden.
    private long rostersWhenHidden = -1;

    // -1 before the first record.
    private volatile long sawGroundTaken = -1;

    private volatile long sawGroundHeld = -1;

    private final java.util.concurrent.atomic.AtomicReference<Beat> owed =
            new java.util.concurrent.atomic.AtomicReference<>();

    private long lastOffered;

    // Ground accepted, not landed; -1 when not hidden.
    private long groundWhenHidden = -1;

    // -1 when not hidden.
    private long positionsWhenHidden = -1;

    // Null when off; existing may be null.
    static VanishProbe armed(boolean wanted, VanishProbe existing) {
        if (!wanted) {
            if (existing != null) {
                existing.stop();
            }
            return null;
        }
        return existing != null && (!existing.stopped || existing.writer == null)
                ? existing
                : opened(defaultDirectory());
    }

    // Never returns null and never throws.
    static VanishProbe opened(Path directory) {
        return opened(directory, System::nanoTime);
    }

    static VanishProbe opened(Path directory, LongSupplier clock) {
        String stem = "vanish-probe-" + FILE_STAMP.format(LocalDateTime.now());
        VanishProbe probe = new VanishProbe(directory.resolve(stem + ".txt"), clock);
        boolean free = probe.open();
        for (int suffix = FIRST_SUFFIX; !free && suffix <= LAST_SUFFIX; suffix++) {
            probe = new VanishProbe(directory.resolve(stem + "-" + suffix + ".txt"),
                    clock);
            free = probe.open();
        }
        if (!free) {
            probe.stopped = true;
            LOGGER.error("The vanish probe could not open {}: all names"
                    + " for this second are taken."
                    + " It does not record; nothing else is affected.", probe.file);
        }
        return probe;
    }

    VanishProbe(Path file) {
        this(file, System::nanoTime);
    }

    VanishProbe(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    private boolean open() {
        boolean free = true;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(
                            Set.of(PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE)));
                } catch (UnsupportedOperationException noPosixBits) {
                    Files.createDirectories(parent);
                }
            }
            try {
                try {
                    out = Files.newByteChannel(file, APPENDING,
                            PosixFilePermissions.asFileAttribute(
                                    Set.of(PosixFilePermission.OWNER_READ,
                                            PosixFilePermission.OWNER_WRITE)));
                } catch (UnsupportedOperationException noPosixBits) {
                    out = Files.newByteChannel(file, APPENDING);
                }
            } catch (FileAlreadyExistsException taken) {
                free = false;
            }
            if (free) {
                Thread writing = Background.thread(this::drain, "geosurvey-vanish-probe");
                writer = writing;
                stopRegistration = StoppableWorkers.close("geosurvey-vanish-probe", this::stop,
                        this::close, () -> writing.getState() == Thread.State.TERMINATED);
                writing.start();

                LOGGER.warn("The vanish probe is armed and writes the names of every listed player to {},"
                        + " vanished ones included."
                        + " Nothing is sent;"
                        + " set shareVanishProbe=false to turn it off.", file);
            }
        } catch (IOException | RuntimeException trouble) {
            stopped = true;
            out = null;
            LOGGER.error("The vanish probe could not open {} and does not record."
                    + " Nothing else is affected.", file, trouble);
        }
        return free;
    }

    static Path defaultDirectory() {
        Path directory;
        try {
            directory = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve(DIRECTORY);
        } catch (RuntimeException | LinkageError noGame) {
            try {
                directory = Files.createTempDirectory("geosurvey-vanish-probe-");
            } catch (IOException noScratch) {
                directory = Path.of(DIRECTORY);
            }
        }
        return directory;
    }

    record MarkedBy(Predicate<String> decorated, Predicate<String> profile) {
    }

    // self is null with no reading.
    void observe(List<ShareSender.Seen> seen, List<RosterReport.Entry> reported,
                 Predicate<String> marked, List<String> markers, boolean selfHidden,
                 ShareSender.Self self, ShareSender.Sent sending) {
        observe(seen, reported, new MarkedBy(marked, marked), markers, selfHidden, self,
                sending);
    }

    void observe(List<ShareSender.Seen> seen, List<RosterReport.Entry> reported,
                 MarkedBy markedBy, List<String> markers, boolean selfHidden,
                 ShareSender.Self self, ShareSender.Sent sending) {
        Predicate<String> markedDecorated = markedBy.decorated();
        Predicate<String> markedProfile = markedBy.profile();
        beats++;
        if (stopped || closing || out == null) {
            return;
        }
        try {
            if (due.remainingCapacity() == 0
                    && !(clock.getAsLong() - wroteAt < HEARTBEAT_NANOS)) {
                fellBehind();
            } else {
                read(draftReads, reportedIds, seen, reported, markedDecorated,
                        markedProfile);
                gateOf(draftGate, self, markedDecorated, markedProfile, selfHidden);
                long at = clock.getAsLong();
                boolean changed = picture == null
                        || !picture.shows(draftReads, markers, draftGate);

                if (!changed && at - wroteAt < HEARTBEAT_NANOS) {
                    suppressed++;
                } else {
                    List<Read> reads = hold(draftReads);
                    Gate held = draftGate.copy();
                    Beat lost = owed.getAndSet(null);
                    if (lost != null && lost.number() == lastOffered) {
                        sawPositions = lost.sawPositions();
                        sawRosters = lost.sawRosters();
                        sawGroundTaken = lost.sawGroundTaken();
                        sawGroundHeld = lost.sawGroundHeld();
                    }
                    ShareSender.Sending settled = sending == null ? null : sending.settled();
                    if (due.offer(new Beat(beats, LocalDateTime.now(), suppressed,
                            behind, reads, markers, held, settled, changed, sawPositions,
                            sawRosters, sawGroundTaken, sawGroundHeld, rostersWhenHidden,
                            groundWhenHidden, positionsWhenHidden))) {
                        lastOffered = beats;
                        picture = pictureOf(reads, markers, held);
                        wroteAt = at;
                        suppressed = 0;
                        behind = 0;
                        records++;
                        if (sending != null) {
                            sawPositions = sending.positions();
                            sawRosters = sending.rosters();
                            sawGroundTaken = sending.groundTaken();
                            sawGroundHeld = sending.groundHeld();

                            rostersWhenHidden =
                                    held.answered() ? sending.rosterAttempts() : -1;
                            groundWhenHidden = held.answered() ? sawGroundTaken : -1;
                            positionsWhenHidden =
                                    held.answered() ? sending.positionAttempts() : -1;
                        } else {
                            rostersWhenHidden = -1;
                            groundWhenHidden = -1;
                            positionsWhenHidden = -1;
                        }
                    } else {
                        fellBehind();
                    }
                }
            }
        } catch (RuntimeException trouble) {
            stop();
            stopped = true;
            LOGGER.error("The vanish probe stopped after a failure."
                    + " The filter is unaffected.", trouble);
        }
    }

    private void fellBehind() {
        behind++;
        rostersWhenHidden = -1;
        groundWhenHidden = -1;
        positionsWhenHidden = -1;
    }

    void stop() {
        closing = true;
        if (Thread.currentThread() != writer) {
            boolean woken = due.offer(END);
            if (!woken) {
                LOGGER.debug("The vanish probe's queue was full when told to stop;"
                        + " its writer needs no wake-up.");
            }
        }
    }

    public void close() {
        close(CLOSE_WAIT_MILLIS);
    }

    private boolean close(long waitMillis) {
        stop();
        Thread writing = writer;
        boolean ended;
        if (writing != null && writing.isAlive() && writing != Thread.currentThread()) {
            if (waitMillis > 0L) {
                try {
                    writing.join(waitMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            ended = !writing.isAlive();
        } else {
            SeekableByteChannel open = out;
            out = null;
            if (open != null) {
                closeQuietly(open);
            }
            StoppableWorkers.Registration registration = stopRegistration;
            stopRegistration = null;
            if (registration != null) {
                registration.close();
            }
            ended = true;
        }
        return ended;
    }

    private static void closeQuietly(SeekableByteChannel open) {
        try {
            open.close();
        } catch (IOException ignored) {
        }
    }

    Path file() {
        return file;
    }

    long beats() {
        return beats;
    }

    long records() {
        return records;
    }

    private static final class Read {

        private UUID id;

        private String name;

        private String shown;

        private String nearby;

        private boolean spectator;

        private boolean located;

        private int x;

        private int z;

        private boolean profileMarked;

        private boolean tabMarked;

        private boolean headMarked;

        private String unusable;

        private boolean derived;

        private boolean reported;

        Read(UUID id, String name, String shown, String nearby, boolean spectator,
             boolean located, int x, int z, boolean profileMarked, boolean tabMarked,
             boolean headMarked, String unusable, boolean derived, boolean reported) {
            this.id = id;
            this.name = name;
            this.shown = shown;
            this.nearby = nearby;
            this.spectator = spectator;
            this.located = located;
            this.x = x;
            this.z = z;
            this.profileMarked = profileMarked;
            this.tabMarked = tabMarked;
            this.headMarked = headMarked;
            this.unusable = unusable;
            this.derived = derived;
            this.reported = reported;
        }

        void took(ShareSender.Seen player, String unusable, boolean profileMarked,
                  boolean tabMarked, boolean headMarked, boolean derived,
                  boolean reported) {
            id = player.id();
            name = player.name();
            shown = player.shown();
            nearby = player.nearby();
            spectator = player.spectator();
            located = player.located();
            x = player.x();
            z = player.z();
            this.unusable = unusable;
            this.profileMarked = profileMarked;
            this.tabMarked = tabMarked;
            this.headMarked = headMarked;
            this.derived = derived;
            this.reported = reported;
        }

        Read copy() {
            return new Read(id, name, shown, nearby, spectator, located, x, z,
                    profileMarked, tabMarked, headMarked, unusable, derived, reported);
        }

        UUID id() {
            return id;
        }

        String name() {
            return name;
        }

        String shown() {
            return shown;
        }

        String nearby() {
            return nearby;
        }

        boolean spectator() {
            return spectator;
        }

        boolean located() {
            return located;
        }

        int x() {
            return x;
        }

        int z() {
            return z;
        }

        boolean profileMarked() {
            return profileMarked;
        }

        boolean tabMarked() {
            return tabMarked;
        }

        boolean headMarked() {
            return headMarked;
        }

        String unusable() {
            return unusable;
        }

        boolean derived() {
            return derived;
        }

        boolean reported() {
            return reported;
        }
    }

    // self is null with no reading this beat.
    private static final class Gate {

        private ShareSender.Self self;

        private boolean answered;

        private boolean adrift;

        private boolean noEntry;

        private boolean unlisted;

        private boolean profileMarked;

        private boolean tabMarked;

        private boolean headMarked;

        private boolean signalsSayHidden;

        private boolean modeIsSpectator;

        Gate(ShareSender.Self self, boolean answered, boolean adrift, boolean noEntry,
             boolean unlisted, boolean profileMarked, boolean tabMarked,
             boolean headMarked) {
            this.self = self;
            this.answered = answered;
            this.adrift = adrift;
            this.noEntry = noEntry;
            this.unlisted = unlisted;
            this.profileMarked = profileMarked;
            this.tabMarked = tabMarked;
            this.headMarked = headMarked;
            recompute();
        }

        Gate copy() {
            return new Gate(self, answered, adrift, noEntry, unlisted, profileMarked,
                    tabMarked, headMarked);
        }

        private void recompute() {
            modeIsSpectator = self != null && self.gameMode() == GameType.SPECTATOR;
            signalsSayHidden = adrift || noEntry || unlisted || modeIsSpectator
                    || profileMarked || tabMarked || headMarked;
        }

        ShareSender.Self self() {
            return self;
        }

        boolean answered() {
            return answered;
        }

        boolean adrift() {
            return adrift;
        }

        boolean noEntry() {
            return noEntry;
        }

        boolean unlisted() {
            return unlisted;
        }

        boolean profileMarked() {
            return profileMarked;
        }

        boolean tabMarked() {
            return tabMarked;
        }

        boolean headMarked() {
            return headMarked;
        }

        // The signals' answer, not the gate's.
        boolean derived() {
            return signalsSayHidden;
        }

        boolean spectator() {
            return modeIsSpectator;
        }
    }

    private record Picture(List<Read> reads, List<String> markers, Gate gate) {

        boolean shows(List<Read> now, List<String> nowMarkers, Gate nowGate) {
            if (gate.answered() != nowGate.answered()
                    || reads.size() != now.size()
                    || gate.derived() != nowGate.derived()
                    || !sameSelf(gate.self(), nowGate.self())
                    || !Objects.equals(markers, nowMarkers)) {
                return false;
            }
            boolean same = true;
            for (int i = 0; same && i < now.size(); i++) {
                same = sameSignals(reads.get(i), now.get(i));
            }
            return same;
        }

        private static boolean sameSelf(ShareSender.Self was, ShareSender.Self is) {
            if (was == null || is == null) {
                return was == is;
            }
            return Objects.equals(was.name(), is.name())
                    && Objects.equals(was.shown(), is.shown())
                    && Objects.equals(was.nearby(), is.nearby())
                    && was.connected() == is.connected()
                    && was.found() == is.found()
                    && was.listed() == is.listed()
                    && was.gameMode() == is.gameMode();
        }

        private static boolean sameSignals(Read was, Read is) {
            return was.id().equals(is.id())
                    && Objects.equals(was.name(), is.name())
                    && Objects.equals(was.shown(), is.shown())
                    && Objects.equals(was.nearby(), is.nearby())
                    && was.spectator() == is.spectator()
                    && was.located() == is.located()
                    && was.profileMarked() == is.profileMarked()
                    && was.tabMarked() == is.tabMarked()
                    && was.headMarked() == is.headMarked()
                    && was.derived() == is.derived()
                    && was.reported() == is.reported()
                    && Objects.equals(was.unusable(), is.unusable());
        }
    }

    private record Beat(long number, LocalDateTime at, long suppressed, long behind,
                        List<Read> reads, List<String> markers, Gate gate,
                        ShareSender.Sending sending, boolean changed,
                        long sawPositions, long sawRosters, long sawGroundTaken,
                        long sawGroundHeld, long rostersWhenHidden,
                        long groundWhenHidden, long positionsWhenHidden) {
    }

    private static void gateOf(Gate into, ShareSender.Self self,
                               Predicate<String> markedDecorated,
                               Predicate<String> markedProfile, boolean answered) {
        into.answered = answered;
        into.self = self;
        if (self == null) {
            into.adrift = false;
            into.noEntry = false;
            into.unlisted = false;
            into.profileMarked = false;
            into.tabMarked = false;
            into.headMarked = false;
        } else {
            into.adrift = !self.connected();
            into.noEntry = self.connected() && !self.found();
            into.unlisted = self.connected() && self.found() && !self.listed();
            into.profileMarked = markedProfile.test(self.name());
            into.tabMarked = markedDecorated.test(self.shown());
            into.headMarked = markedDecorated.test(self.nearby());
        }
        into.recompute();
    }

    private static void read(List<Read> into, Set<UUID> got,
                             List<ShareSender.Seen> seen,
                             List<RosterReport.Entry> reported,
                             Predicate<String> markedDecorated,
                             Predicate<String> markedProfile) {
        got.clear();
        for (int at = 0; at < reported.size(); at++) {
            RosterReport.Entry entry = reported.get(at);
            if (entry != null) {
                got.add(entry.player());
            }
        }
        int kept = 0;
        for (int at = 0; at < seen.size(); at++) {
            ShareSender.Seen player = seen.get(at);
            if (player == null || player.id() == null) {
                continue;
            }
            String name = player.name();

            // The same checks reportable makes.
            String unusable = name == null ? "the profile name is null"
                    : name.isBlank() ? "the profile name is blank"
                    : name.length() > RosterReport.MAX_PLAYER_NAME
                            ? "the profile name is longer than "
                                    + RosterReport.MAX_PLAYER_NAME
                            : null;
            boolean profile = markedProfile.test(name);
            boolean tab = markedDecorated.test(player.shown());
            boolean head = markedDecorated.test(player.nearby());
            boolean derived = unusable == null && !profile && !tab && !head;
            if (kept < into.size()) {
                into.get(kept).took(player, unusable, profile, tab, head, derived,
                        got.contains(player.id()));
            } else {
                into.add(new Read(player.id(), name, player.shown(), player.nearby(),
                        player.spectator(), player.located(), player.x(), player.z(),
                        profile, tab, head, unusable, derived,
                        got.contains(player.id())));
            }
            kept++;
        }
        while (into.size() > kept) {
            into.remove(into.size() - 1);
        }
    }

    private static List<Read> hold(List<Read> draft) {
        List<Read> held = new ArrayList<>(draft.size());
        for (int at = 0; at < draft.size(); at++) {
            held.add(draft.get(at).copy());
        }
        return held;
    }

    private static Picture pictureOf(List<Read> reads, List<String> markers,
                                     Gate gate) {
        return new Picture(reads, markers, gate);
    }

    private String render(Beat beat, long room) {
        List<Read> reads = beat.reads();
        int listed = reads.size();
        Gate gate = beat.gate();
        StringBuilder out = renderBuffer;
        out.setLength(0);
        out.append('\n').append("--- beat ").append(beat.number()).append("  ");
        STAMP.formatTo(beat.at(), out);
        out.append("  ")
                .append(beat.changed() ? "CHANGED" : "unchanged, heartbeat");
        if (beat.suppressed() > 0) {
            out.append("  (").append(beat.suppressed())
                    .append(" beats skipped: no signal changed)");
        }
        if (beat.behind() > 0) {
            out.append("  (").append(beat.behind())
                    .append(" beats skipped")
                    .append(": the writer fell behind)");
        }
        out.append('\n');
        out.append("    this client: ").append(gate.answered()
                        ? "HIDDEN"
                        : "not hidden")
                .append('\n');
        int excluded = 0;
        int wrong = 0;
        int reported = 0;
        Read ownRead = null;
        ShareSender.Self own = gate.self();
        UUID ownId = own == null ? null : own.id();
        for (int at = 0; at < listed; at++) {
            Read read = reads.get(at);
            if (ownRead == null && ownId != null && ownId.equals(read.id())) {
                ownRead = read;
            }
            if (!read.derived()) {
                excluded++;
            }
            if (read.derived() != read.reported()) {
                wrong++;
            }
            if (read.reported()) {
                reported++;
            }
        }
        self(out, gate, ownRead);
        out.append("    markers in force: [");
        List<String> markers = beat.markers();
        for (int at = 0; at < markers.size(); at++) {
            out.append(at == 0 ? "" : ", ").append(markers.get(at));
        }
        out.append(']').append('\n');
        out.append("    listed: ").append(listed).append("    reported: ")
                .append(reported).append("    excluded: ")
                .append(excluded).append('\n');
        posting(out, beat, reported);
        if (wrong > 0) {
            out.append("    !! PROBE DISAGREES WITH THE FILTER on ").append(wrong)
                    .append(" player(s). Trust nothing below;")
                    .append(" this is a bug in the probe or in reportable.\n");
        }
        for (int at = 0; at < listed && out.length() <= room; at++) {
            player(out, reads.get(at));
        }
        return out.length() > room ? null : out.toString();
    }

    // inRoster is null with no reading.
    private static void self(StringBuilder out, Gate gate, Read inRoster) {
        ShareSender.Self read = gate.self();
        if (read == null) {
            out.append("        (no self reading was taken.)\n");
            return;
        }
        name(out, "own profile ", read.name(), gate.profileMarked(),
                "its tab entry has no profile name");
        name(out, "own tab     ", read.shown(), gate.tabMarked(),
                "the server sent no UPDATE_DISPLAY_NAME for this client");
        name(out, "own overhead", read.nearby(), gate.headMarked(),
                "this client has no player entity or no display name");
        out.append("        own uuid=").append(read.id())
                .append("  game mode=").append(read.gameMode() == null ? null : read.gameMode().name())
                .append("  getPlayerInfo=")
                .append(read.found() ? "an entry" : "NULL")
                .append("  listed=").append(read.listed())
                .append("  connected=").append(read.connected()).append('\n');
        if (read.id() != null) {
            out.append("        own entry in the roster: ");
            inRoster(out, inRoster);
            out.append('\n');
        }
        boolean derived = gate.derived();
        out.append("        SELF-GATE hidden=").append(gate.answered());
        if (derived) {
            out.append(", by: ");
            conditions(out, gate);
        } else {
            out.append(". No condition fired: this client is connected,"
                    + " its own entry is listed, and no name"
                    + " has a marker");
        }
        out.append('\n');
        if (derived != gate.answered()) {
            out.append("        !! PROBE DISAGREES WITH THE SELF-GATE: the signals"
                            + " say ").append(derived ? "hidden" : "not hidden")
                    .append(" and the gate answered ")
                    .append(gate.answered() ? "hidden" : "not hidden")
                    .append(".\n");
        }
        if (gate.spectator()) {
            out.append(gate.answered()
                    ? "        (game mode SPECTATOR.)\n"
                    : "        !! game mode SPECTATOR and the self-gate answered NOT hidden.\n");
        }
    }

    private static void inRoster(StringBuilder out, Read read) {
        if (read != null) {
            if (!read.reported()) {
                out.append("excluded by the filter, like anybody"
                        + " else");
                return;
            }
            if (!read.located()) {
                out.append("REPORTED, without coordinates");
                return;
            }
            out.append("REPORTED, with coordinates ").append(read.x()).append(',')
                    .append(read.z());
            return;
        }

        out.append("it is not in this walk");
    }

    private static void conditions(StringBuilder out, Gate gate) {
        int at = out.length();
        if (gate.adrift()) {
            out.append("there is no connection or no player");
        }
        if (gate.noEntry()) {
            out.append(out.length() == at ? "" : ", ")
                    .append("getPlayerInfo found no entry for this client");
        }
        if (gate.unlisted()) {
            out.append(out.length() == at ? "" : ", ")
                    .append("its own entry is not listed");
        }
        if (gate.spectator()) {
            out.append(out.length() == at ? "" : ", ").append("game mode SPECTATOR");
        }
        if (gate.profileMarked()) {
            out.append(out.length() == at ? "" : ", ")
                    .append("a marker on its profile name");
        }
        if (gate.tabMarked()) {
            out.append(out.length() == at ? "" : ", ")
                    .append("a marker on its tab display name");
        }
        if (gate.headMarked()) {
            out.append(out.length() == at ? "" : ", ")
                    .append("a marker on its overhead name");
        }
    }

    private static void posting(StringBuilder out, Beat beat, int handedOn) {
        ShareSender.Sending sending = beat.sending();
        if (sending == null) {
            return;
        }
        Gate gate = beat.gate();
        out.append("    posting: ").append(sending.running()
                        ? "running"
                        : "stopped; nothing leaves this client");
        moved(out, "\n        positions accepted: ", sending.positions(),
                beat.sawPositions());
        moved(out, "\n        rosters accepted:   ", sending.rosters(),
                beat.sawRosters());
        moved(out, "\n        ground accepted:    ",
                sending.groundTaken(), beat.sawGroundTaken());
        moved(out, "\n        ground refused:     ",
                sending.groundHeld(), beat.sawGroundHeld());
        out.append('\n');
        said(out, "position beat", sending.positionReason());
        said(out, "roster beat  ", sending.rosterReason());
        if (!gate.answered() && wasHidden(beat)) {
            out.append("    ** THE LAST HIDDEN WINDOW ENDED.\n");
            if (wentOut(beat.rostersWhenHidden(), sending.rosterAttempts())) {
                out.append("    !! A ROSTER WENT OUT IN THE LAST HIDDEN WINDOW.\n");
            }
            if (wentOut(beat.groundWhenHidden(), sending.groundTaken())) {
                out.append("    !! GROUND WAS SENT IN THE LAST HIDDEN WINDOW.\n");
            }
            if (wentOut(beat.positionsWhenHidden(), sending.positionAttempts())) {
                out.append("    !! A POSITION WAS SENT IN THE LAST HIDDEN WINDOW.\n");
            }
        }
        boolean hidden = gate.answered();
        if (hidden) {
            out.append("    ** THIS CLIENT IS HIDDEN: it sends no position,"
                            + " player list, ground or account proof.\n")
                    .append("       A shared claim or marker waits.\n"
                            + "       Held: ")
                    .append(handedOn)
                    .append(" player(s). Dropped: ")
                    .append(sending.groundHeld())
                    .append(" chunk(s) so far; your map kept them.\n");
            if (!sending.running()) {
                out.append("       Nothing is posted; switch contributing on to take a reading.\n");
            }
        }
        if (hidden && wentOut(beat.rostersWhenHidden(), sending.rosterAttempts())) {
            out.append("    !! A ROSTER WENT OUT WHILE THIS CLIENT WAS HIDDEN:\n"
                            + "       the roster count moved by ")
                    .append(sending.rosterAttempts() - beat.rostersWhenHidden())
                    .append(" since the last hidden record.\n");
        }
        if (hidden && wentOut(beat.positionsWhenHidden(), sending.positionAttempts())) {
            out.append("    !! A POSITION WAS SENT WHILE THIS CLIENT WAS HIDDEN:\n"
                            + "       the position count moved by ")
                    .append(sending.positionAttempts() - beat.positionsWhenHidden())
                    .append(" since the last hidden record.\n");
        }

        if (hidden && wentOut(beat.groundWhenHidden(), sending.groundTaken())) {
            out.append("    !! GROUND WAS SENT WHILE THIS CLIENT WAS HIDDEN.\n"
                            + "       The accepted count moved by ")
                    .append(sending.groundTaken() - beat.groundWhenHidden())
                    .append(" since the last hidden record.\n"
                            + "       A PERMANENT record"
                            + " names this account.\n");
        }
    }

    private static boolean wasHidden(Beat beat) {
        return beat.rostersWhenHidden() >= 0 || beat.groundWhenHidden() >= 0
                || beat.positionsWhenHidden() >= 0;
    }

    private static boolean wentOut(long recorded, long now) {
        return recorded >= 0 && now > recorded;
    }

    private static void moved(StringBuilder out, String label, long now, long saw) {
        out.append(label).append(now);
        if (saw < 0) {
            out.append(" (first record: nothing to compare)");
        } else {
            long delta = now - saw;
            out.append("  (").append(delta >= 0 ? "+" : "").append(delta)
                    .append(" since the last record)");
        }
    }

    private static void said(StringBuilder out, String which, String reason) {
        out.append("        ").append(which).append(": ");
        if (reason == null || reason.isEmpty()) {
            out.append("no complaint");
        } else {
            quoted(out, reason);
        }
        out.append('\n');
    }

    private static void player(StringBuilder out, Read read) {
        out.append("    player ").append(read.id()).append('\n');
        name(out, "profile ", read.name(), read.profileMarked(), null);
        name(out, "tab     ", read.shown(), read.tabMarked(),
                "the server sent no UPDATE_DISPLAY_NAME for this player");
        name(out, "overhead", read.nearby(), read.headMarked(),
                "this client has no loaded entity or no display name"
                        + " for this player");
        out.append("        located=").append(read.located())
                .append(" spectator=").append(read.spectator());
        if (read.located()) {
            out.append(" at ").append(read.x()).append(',').append(read.z());
        }
        out.append('\n');
        out.append("        VERDICT ");
        if (read.derived()) {
            out.append("REPORTED: no marker on the three names");
        } else if (read.unusable() != null) {
            out.append("excluded, not by a marker: ").append(read.unusable());
        } else {
            out.append("EXCLUDED by: ");
            signals(out, read);
        }
        if (read.derived() != read.reported()) {
            out.append("   !! the filter actually ")
                    .append(read.reported() ? "REPORTED" : "excluded").append(" it");
        }
        out.append('\n');
    }

    private static void signals(StringBuilder out, Read read) {
        int at = out.length();
        if (read.profileMarked()) {
            out.append("profile name");
        }
        if (read.tabMarked()) {
            out.append(out.length() == at ? "" : " + ")
                    .append("tab display name");
        }
        if (read.headMarked()) {
            out.append(out.length() == at ? "" : " + ")
                    .append("overhead name");
        }
    }

    private static void name(StringBuilder out, String label, String raw,
                             boolean marked, String whyNull) {
        out.append("        ").append(label).append(' ');
        if (raw == null) {
            out.append("null");
            if (whyNull != null) {
                out.append("  (").append(whyNull).append(')');
            }
            out.append('\n');
        } else {
            quoted(out, raw);
            out.append(marked ? "   MARKER" : "   no marker").append('\n');
            String plain = ShareSender.plain(raw);
            if (!plain.equals(raw)) {
                out.append("                 stripped: ");
                quoted(out, plain);
                out.append('\n');
            }
        }
    }

    private static void quoted(StringBuilder out, String raw) {
        out.append('"');
        int length = raw.length();
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"', '\\' -> out.append('\\').append(c);
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean pairedNext = i + 1 < length
                            && Character.isLowSurrogate(raw.charAt(i + 1));
                    boolean pairedPrev = i > 0
                            && Character.isHighSurrogate(raw.charAt(i - 1));
                    boolean lone = (Character.isHighSurrogate(c) && !pairedNext)
                            || (Character.isLowSurrogate(c) && !pairedPrev);
                    if (c < SPACE || c == DELETE || lone
                            || JsonText.breaksALineOrSteers(c)) {
                        out.append('\\').append('u')
                                .append(HEX_DIGITS[c >> FIRST_NIBBLE_SHIFT])
                                .append(HEX_DIGITS[(c >> SECOND_NIBBLE_SHIFT) & NIBBLE_MASK])
                                .append(HEX_DIGITS[(c >> THIRD_NIBBLE_SHIFT) & NIBBLE_MASK])
                                .append(HEX_DIGITS[c & NIBBLE_MASK]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private String header() {
        return "GeoSurvey vanish probe\n"
                + "opened " + STAMP.format(LocalDateTime.now()) + "\n"
                + "\n"
                + "\n"
                + "This file names every listed player, vanished or not, and stays on this machine: do not hand it\n"
                + "round.\n"
                + "\n"
                + "\n"
                + "\n"
                + "The probe reads every survey scan, twice a second, and records when the picture changes, or every\n"
                + HEARTBEAT_MILLIS / MILLIS_PER_SECOND + " seconds."
                + " Recording stops at " + MAX_BYTES / MEGABYTE + " MB.\n"
                + "\n"
                + "Set shareVanishProbe to false to turn it off.\n";
    }

    private void drain() {
        try {
            SeekableByteChannel open = out;
            if (open != null) {
                written = open.size();
            }
            write(header());
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                boolean stopping = closing;
                Object next = stopping ? due.poll()
                        : due.poll(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (stopping && next == null) {
                    break;
                }
                if (next instanceof Beat beat) {
                    wroteOneRecord(beat);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable trouble) {
            stopped = true;
            LOGGER.error("The vanish probe stopped after a failure."
                    + " The filter is unaffected.", trouble);
        } finally {
            due.clear();
            close();
        }
    }

    private void wroteOneRecord(Beat beat) {
        try {
            write(render(beat, MAX_BYTES - CEILING.length() - written));
        } catch (Throwable oneRecord) {
            owed.set(beat);
            faults++;
            long now = clock.getAsLong();
            if (faults == 1 || now - faultSaidAt >= FAULT_SAY_NANOS) {
                faultSaidAt = now;
                LOGGER.warn("The vanish probe could not write a record to {}"
                        + " ({}). It keeps trying;"
                        + " {} records are lost"
                        + " so far.", file, oneRecord.toString(),
                        faults, oneRecord);
            }
        }
    }

    private void write(String text) throws IOException {
        SeekableByteChannel open = out;
        if (open == null) {
            return;
        }
        ByteBuffer bytes = text == null ? null
                : ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        if (bytes != null
                && written + bytes.remaining() + CEILING.length() <= MAX_BYTES) {
            try {
                written += put(open, bytes);
            } catch (IOException partial) {
                try {
                    written = open.size();
                } catch (IOException ignored) {
                }
                throw partial;
            }
            return;
        }
        if (written + CEILING.length() <= MAX_BYTES) {
            try {
                written += put(open, StandardCharsets.UTF_8.encode(CEILING));
            } catch (IOException partial) {
                try {
                    written = open.size();
                } catch (IOException ignored) {
                }
                stopped = true;
                close();
                throw partial;
            }
        }
        stopped = true;
        close();
        LOGGER.warn("The vanish probe reached its size ceiling and stopped."
                + " The file is {}.", file);
    }

    private static long put(SeekableByteChannel open, ByteBuffer bytes)
            throws IOException {
        long wrote = 0;
        while (bytes.hasRemaining()) {
            wrote += open.write(bytes);
        }
        return wrote;
    }
}
