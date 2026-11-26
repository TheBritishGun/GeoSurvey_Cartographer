package dev.openmap.client;

import dev.openmap.claim.Claim;
import dev.openmap.claim.ClaimBook;
import dev.openmap.claim.Claims;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import dev.openmap.map.Landmark;
import dev.openmap.map.LandmarkStore;
import dev.openmap.share.Batch;
import dev.openmap.share.SharedRecord;
import dev.openmap.symbol.SymbolIcon;
import dev.sandpaper.Sandpaper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

final class ShareOutbox implements Claims.SharedFrom {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);
    private static final long RETRY_MILLIS = 60_000L;
    private static final long REFUSED_RETRY_MILLIS = 3_600_000L;
    private static final int NO_SHARED_STORE = 404;
    private static final int STORE_FULL = 507;
    private static final int RECORD_KEPT = 204;
    private static final int OLDER_REVISION = 409;
    private static final int BAD_RECORD = 400;
    private static final int NOT_SIGNED_IN = 401;
    private static final int TOO_MANY = 429;
    private static final long MAX_RETRY_AFTER_SECONDS = 3_600L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    // Wait for a claim answer: one retry period.
    private static final long ANSWER_WAIT_NANOS = RETRY_MILLIS * NANOS_PER_MILLI;
    // Source name for claim entries.
    private static final String LEGACY_CLAIMS_SOURCE = "claims";
    private static final int DEFAULT_SNAPSHOT_ENTRIES = 12;
    private static final int OUTBOX_VERSION = 1;
    private static final Moment NAMELESS_POSTING_ALL = new Fixed(null, Posting.ALL);
    private static final Vanish NEVER_VANISHED = () -> false;

    private enum State { TO_SEND, SENT, SETTLED, REFUSED }

    // What the outbox posts: none, removals, or all.
    enum Posting {
        NONE,
        REMOVALS,
        ALL;

        boolean posts(boolean removal) {
            return (this == ALL) || ((this == REMOVALS) && removal);
        }
    }

    // Open server's share name and what is posted.
    interface Moment {

        // Null: no server, or none known.
        String server();

        Posting posting();
    }

    // Posts one record; server may be null.
    interface Poster {

        ShareSender.Reply post(URI endpoint, byte[] body, String server) throws IOException;
    }

    // Whether this client is vanished; posts nothing. Any thread.
    interface Vanish {

        boolean vanished();
    }

    private record Fixed(String server, Posting posting) implements Moment {
    }

    // Answer wait for one claim revision; guarded by outbox.
    private static final class Watch {
        private final Entry entry;
        private final String source;
        private final String name;
        private final long revised;
        private final long since;
        private Claims.NodeAnswer answer = null;

        private Watch(Entry entry, String source, String name, long since) {
            this.entry = entry;
            this.source = source;
            this.name = name;
            this.revised = entry.revised;
            this.since = since;
        }
    }

    private final class Entry implements Runnable {
        private final SharedRecord.Kind kind;
        private final String id;
        private String source;
        // Share name of the server; null from older files.
        private String server;
        // Live-revision server; "" for no server, null for none.
        private String withdrawFrom = null;
        // Whether the entry's server may hold a live revision.
        private boolean serverMayHold = false;
        private String playerId;
        private String ownerId;
        private String owner;
        private SharedRecord.Item item;
        private long revised;
        private State state = State.TO_SEND;
        private long nextTry;
        private boolean posting;
        private boolean logged;
        private boolean postFailureLogged;
        private boolean unrevisableLogged = false;
        // Last logged 404 or 507 answer; 0 before any.
        private int conditionLogged = 0;
        // Removal whose book still held the claim unshared.
        private boolean unshared = false;
        private final Runnable onDrop = this;

        private Entry(SharedRecord.Kind kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        private Entry(Entry other) {
            kind = other.kind;
            id = other.id;
            source = other.source;
            server = other.server;
            withdrawFrom = other.withdrawFrom;
            serverMayHold = other.serverMayHold;
            playerId = other.playerId;
            ownerId = other.ownerId;
            owner = other.owner;
            item = other.item;
            revised = other.revised;
            state = other.state;
            nextTry = other.nextTry;
            unshared = other.unshared;
        }

        @Override
        public void run() {
            clearPosting(this);
        }

        private String key() {
            return ShareOutbox.key(kind, id);
        }
    }

    // Since: save nanoTime. Session: answerSession at hold.
    private record PendingClaims(String source, String server, Posting posting, ClaimBook book, long nowMillis,
            long since, long session) { }

    // Held wait for one shared claim's answer.
    private record HeldWait(String source, String id, String name, long since) { }

    private record PendingMarkers(String source, String server, String dimensionId, LandmarkStore store,
            long nowMillis) { }

    // A world's one-time claims port, by the world's book.
    private record HeldPort(String book, Supplier<Claims.Said> port) { }

    private final Path file;
    // Read at each save, off the lock.
    private final Moment moment;
    // Read off the lock at each beat and post.
    private final Vanish vanish;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, PendingClaims> pendingClaims = new HashMap<>();
    private final Map<String, PendingMarkers> pendingMarkers = new HashMap<>();
    private final List<Watch> watches = new ArrayList<>();
    // Waits of held saves; guarded by outbox.
    private final List<HeldWait> heldWaits = new ArrayList<>();
    // Waits said only if the load fails; guarded by outbox.
    private final List<HeldWait> droppedWaits = new ArrayList<>();
    // Logged refusal cause per claim id; guarded by outbox.
    private final Map<String, Claims.Shareable> refusedClaims = new HashMap<>();
    // Logged refused marker ids; guarded by outbox.
    private final Set<String> refusedMarkers = new HashSet<>();
    private final AtomicLong writtenSeq = new AtomicLong();
    private boolean loaded;
    private boolean loading;
    private boolean unreadable;
    private long saveSeq;
    // Count of forgotten waits; guarded by outbox.
    private long answerSession;
    // Held port until load ends, or null; guarded by outbox.
    private HeldPort heldPort = null;
    // Last walk found nothing to post; guarded by outbox.
    private boolean quiet = false;
    // Last walk's posting, player and retry time; guarded by outbox.
    private Posting quietPosting = null;
    private String quietPlayer = null;
    private long quietUntil = 0L;

    volatile LongConsumer beforeWrite = seq -> { };
    volatile Executor testWriteExecutor;
    volatile Runnable beforeLoad = () -> { };

    ShareOutbox(Path file) {
        this(file, NAMELESS_POSTING_ALL);
    }

    ShareOutbox(Path file, Moment moment) {
        this(file, moment, NEVER_VANISHED);
    }

    ShareOutbox(Path file, Moment moment, Vanish vanish) {
        this.file = file;
        this.moment = moment;
        this.vanish = vanish;
    }

    void loadIfNeeded() {
        synchronized (this) {
            if (loaded || loading) {
                return;
            }
            loading = true;
        }
        Map<String, Entry> read = new HashMap<>();
        boolean failed = false;
        try {
            beforeLoad.run();
            AtomicFileReplace.recoverStaleAside(file);
            if (Files.isRegularFile(file)) {
                read(read, Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException badFile) {
            failed = true;
            LOGGER.warn("Could not read shared outbox {}; leaving it untouched.", file, badFile);
        }
        synchronized (this) {
            loading = false;
            loaded = true;
            if (failed) {
                unreadable = true;
            } else {
                entries.clear();
                entries.putAll(read);
                quiet = false;
            }
        }
    }

    // Source: the one book the claims were saved to.
    Claims.Sent claimsSaved(String source, ClaimBook book, long nowMillis) {
        if (source == null || book == null || !book.unreadable().isEmpty()) {
            return Claims.Sent.NOTHING;
        }
        loadIfNeeded();
        String server = moment.server();
        Posting posting = moment.posting();
        boolean saved;
        Claims.Sent sent;
        synchronized (this) {
            saved = loaded && !unreadable;
            if (saved) {
                dropHeld(source);
                sent = saveClaims(source, server, posting, book, nowMillis, null);
            } else if (unreadable) {
                sent = Claims.Sent.NOT_TOLD;
            } else {
                PendingClaims hold = new PendingClaims(source, server, posting, book, nowMillis, System.nanoTime(),
                        answerSession);
                pendingClaims.put(source, hold);
                holdWaits(hold);
                sent = Claims.Sent.HELD;
            }
        }
        if (saved) {
            saveAsync();
        }
        return sent;
    }

    // TO_NODE: posted. HELD: all held back. NOTHING: none queued.
    private static Claims.Sent toNode(int posted, int heldBack) {
        Claims.Sent sent;
        if (posted > 0) {
            sent = Claims.Sent.TO_NODE;
        } else if (heldBack > 0) {
            sent = Claims.Sent.HELD;
        } else {
            sent = Claims.Sent.NOTHING;
        }
        return sent;
    }

    // Drops the held save and its waits.
    private void dropHeld(String source) {
        pendingClaims.remove(source);
        removeWaitsOf(heldWaits, source);
        removeWaitsOf(droppedWaits, source);
    }

    private static void removeWaitsOf(List<HeldWait> waits, String source) {
        int at = 0;
        while (at < waits.size()) {
            if (waits.get(at).source().equals(source)) {
                waits.remove(at);
            } else {
                at++;
            }
        }
    }

    void markersSaved(String source, String dimensionId, LandmarkStore store, long nowMillis) {
        if (source == null || dimensionId == null || store == null || !store.unreadable().isEmpty()) {
            return;
        }
        loadIfNeeded();
        String server = moment.server();
        boolean saved = false;
        synchronized (this) {
            if (!loaded || unreadable) {
                if (!unreadable) {
                    pendingMarkers.put(source, new PendingMarkers(source, server, dimensionId, store, nowMillis));
                }
            } else {
                pendingMarkers.remove(source);
                saveMarkers(source, server, dimensionId, store, nowMillis);
                saved = true;
            }
        }
        if (saved) {
            saveAsync();
        }
    }

    void postDue(String playerId, String playerName, Poster poster, URI endpoint, boolean shareEnabled,
                 long nowMillis) {
        Entry toPost = null;
        SharedRecord record = null;
        String server = null;
        boolean withdrawing = false;
        Posting posting = shareEnabled ? Posting.ALL : Posting.REMOVALS;
        boolean hidden = vanish.vanished();
        boolean saved;
        boolean readyToPost;
        synchronized (this) {
            saved = savePending();
            readyToPost = !hidden && ready(playerId, playerName, poster, endpoint);
            if (readyToPost && !quietFor(posting, playerId, nowMillis)) {
                long until = Long.MAX_VALUE;
                for (Entry entry : entries.values()) {
                    if (postedOrPosting(entry)) {
                        continue;
                    }
                    if (nowMillis < entry.nextTry) {
                        until = Math.min(until, entry.nextTry);
                        continue;
                    }
                    if (!posting.posts(postsARemoval(entry))) {
                        continue;
                    }
                    if (entry.kind == SharedRecord.Kind.MARKER && entry.playerId == null) {
                        entry.playerId = playerId;
                        entry.owner = playerName;
                        entry.ownerId = playerName.toLowerCase(Locale.ROOT);
                    }
                    if (entry.playerId == null || !entry.playerId.equals(playerId)) {
                        entry.state = State.REFUSED;
                        if (!entry.logged) {
                            entry.logged = true;
                            LOGGER.warn("The shared {} {} belongs to another player.", entry.kind.wire(), entry.id);
                        }
                        continue;
                    }
                    record = nextPost(entry);
                    if (record == null) {
                        continue;
                    }
                    withdrawing = entry.withdrawFrom != null;
                    server = beginPost(entry);
                    toPost = entry;
                    break;
                }
                quiet = (toPost == null);
                quietPosting = posting;
                quietPlayer = playerId;
                quietUntil = until;
            }
        }
        if (saved) {
            saveAsync();
        }
        if (readyToPost && toPost != null) {
            queuePost(poster, endpoint, toPost, record, server, withdrawing);
        }
    }

    // Posts to the work pool; a refusal frees the entry.
    private void queuePost(Poster poster, URI endpoint, Entry posted, SharedRecord record, String server,
                           boolean withdrawing) {
        boolean queued = Sandpaper.isReady() && Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                () -> {
                    postWhileVisible(poster, endpoint, posted, record, server, withdrawing);
                    return Boolean.TRUE;
                }, done -> { }, null, posted.onDrop, done -> { });
        if (!queued) {
            clearPosting(posted);
        }
    }

    // Posts nothing if vanished; the entry waits for a beat.
    private void postWhileVisible(Poster poster, URI endpoint, Entry entry, SharedRecord record, String server,
                                  boolean withdrawing) {
        if (vanish.vanished()) {
            clearPosting(entry);
        } else {
            postOne(poster, endpoint, entry, record, server, withdrawing);
        }
    }

    // Whether the beat has a named player, poster and address.
    private static boolean ready(String playerId, String playerName, Poster poster, URI endpoint) {
        return playerId != null && !playerId.isEmpty() && playerName != null && !playerName.isEmpty()
                && poster != null && endpoint != null;
    }

    // Last walk found nothing to post; no retry due.
    private boolean quietFor(Posting posting, String playerId, long nowMillis) {
        return quiet && (posting == quietPosting) && playerId.equals(quietPlayer) && (nowMillis < quietUntil);
    }

    // Whether a walk skips the entry: posting, sent or settled.
    private static boolean postedOrPosting(Entry entry) {
        return entry.posting || entry.state == State.SENT || entry.state == State.SETTLED;
    }

    // Number of entries this save queued.
    // Null: save made now, not held.
    private Claims.Sent saveClaims(String source, String server, Posting posting, ClaimBook book, long nowMillis,
                                   PendingClaims hold) {
        long since = (hold == null) ? System.nanoTime() : hold.since();
        Map<String, Boolean> seen = new HashMap<>();
        int posted = 0;
        int heldBack = 0;
        for (Claim claim : book.claims()) {
            if (!claim.shared()) {
                continue;
            }
            String key = key(SharedRecord.Kind.CLAIM, claim.id());
            seen.put(key, Boolean.TRUE);
            Entry prior = entries.get(key);
            SharedRecord record = claimRecord(claim, prior);
            if (record == null) {
                continue;
            }
            Entry entry = entry(SharedRecord.Kind.CLAIM, claim.id());
            boolean changed = replace(entry, source, server, claim.ownerId(), record, nowMillis,
                    prior != null && claim.revised() > prior.revised);
            if (changed) {
                if (posting.posts(false)) {
                    if ((hold == null) || stillHeld(source, claim.id())) {
                        watch(entry, source, claim.name(), since);
                    }
                    posted++;
                } else {
                    heldBack++;
                }
            }
        }
        markUnshared(source, seen, book);
        boolean removalsPosted = posting.posts(true);
        int removals = tombstoneMissing(source, seen, nowMillis, since,
                removalsPosted && ((hold == null) || (hold.session() == answerSession)));
        return removalsPosted ? toNode(posted + removals, heldBack) : toNode(posted, heldBack + removals);
    }

    // Claim entries this book no longer shares.
    private void markUnshared(String source, Map<String, Boolean> seen, ClaimBook book) {
        for (Entry entry : entries.values()) {
            if ((entry.kind == SharedRecord.Kind.CLAIM) && source.equals(entry.source)
                    && !seen.containsKey(entry.key())) {
                entry.unshared = (book.byId(entry.id) != null);
            }
        }
    }

    private void saveMarkers(String source, String server, String dimensionId, LandmarkStore store, long nowMillis) {
        Map<String, Boolean> seen = new HashMap<>();
        for (Landmark marker : store.all()) {
            if (marker.shared() == null) {
                continue;
            }
            String key = key(SharedRecord.Kind.MARKER, marker.shared());
            seen.put(key, Boolean.TRUE);
            SharedRecord.Item item = markerItem(marker, dimensionId);
            if (item == null) {
                continue;
            }
            Entry entry = entry(SharedRecord.Kind.MARKER, marker.shared());
            if (!same(entry, item) || !source.equals(entry.source)) {
                if (entry.revised == Long.MAX_VALUE) {
                    if (!entry.unrevisableLogged) {
                        entry.unrevisableLogged = true;
                        LOGGER.warn("The shared marker {} cannot be revised.", marker.name());
                    }
                } else {
                    entry.source = source;
                    moveTo(entry, server);
                    entry.item = item;
                    entry.revised = Math.max(nowMillis, Math.addExact(entry.revised, 1L));
                    entry.state = State.TO_SEND;
                    entry.nextTry = nowMillis;
                    quiet = false;
                }
            }
        }
        tombstoneMissing(source, seen, nowMillis, 0L, false);
    }

    private boolean savePending() {
        boolean saved = false;
        if (loaded && !unreadable) {
            if (!pendingClaims.isEmpty()) {
                for (PendingClaims pending : pendingClaims.values()) {
                    saveClaims(pending.source(), pending.server(), pending.posting(), pending.book(),
                            pending.nowMillis(), pending);
                    saved = true;
                }
                pendingClaims.clear();
            }
            heldWaits.clear();
            droppedWaits.clear();
            if (!pendingMarkers.isEmpty()) {
                for (PendingMarkers pending : pendingMarkers.values()) {
                    saveMarkers(pending.source(), pending.server(), pending.dimensionId(), pending.store(),
                            pending.nowMillis());
                    saved = true;
                }
                pendingMarkers.clear();
            }
        }
        return saved;
    }

    private synchronized void clearPosting(Entry entry) {
        entry.posting = false;
        quiet = false;
    }

    private Entry entry(SharedRecord.Kind kind, String id) {
        String key = key(kind, id);
        Entry existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        Entry added = new Entry(kind, id);
        entries.put(key, added);
        return added;
    }

    private static String key(SharedRecord.Kind kind, String id) {
        return kind.wire() + "/" + id;
    }

    // Null when refused or unrevisable; prior may be null.
    private SharedRecord claimRecord(Claim claim, Entry prior) {
        Claims.Shareable fit = Claims.shareable(claim);
        SharedRecord record;
        if (fit != Claims.Shareable.YES) {
            Claims.Shareable logged = refusedClaims.put(claim.id(), fit);
            if (logged != fit) {
                LOGGER.warn("The shared claim {} cannot be sent: {}.", claim.name(), fit);
            }
            record = null;
        } else if ((prior != null) && (prior.revised == Long.MAX_VALUE)) {
            refusedClaims.remove(claim.id());
            if (!prior.unrevisableLogged) {
                prior.unrevisableLogged = true;
                LOGGER.warn("The shared claim {} cannot be revised.", claim.name());
            }
            record = null;
        } else {
            refusedClaims.remove(claim.id());
            long lastRevised = (prior == null) ? 0L : prior.revised;
            long revised = Math.max(SharedRecord.MIN_REVISED,
                    Math.max(claim.revised(), Math.addExact(lastRevised, 1L)));
            record = new SharedRecord(SharedRecord.Kind.CLAIM, claim.id(), claim.owner().toLowerCase(Locale.ROOT),
                    claim.owner(), revised,
                    new SharedRecord.Area(clean(claim.name()), claim.dimension(), coordinates(claim.xs()),
                            coordinates(claim.zs()), claim.colour().name(), claim.lineColour(), claim.fillColour()));
        }
        return record;
    }

    // Null when the record refuses the marker data.
    private SharedRecord.Item markerItem(Landmark marker, String dimensionId) {
        String label = clean(marker.name());
        SharedRecord.Item item;
        if (SharedRecord.isLabel(label) && SharedRecord.isWorld(dimensionId) && SharedRecord.isCoordinate(marker.x())
                && SharedRecord.isCoordinate(marker.z())) {
            refusedMarkers.remove(marker.shared());
            item = new SharedRecord.Point(label, dimensionId, marker.x(), marker.z(), icon(marker));
        } else {
            boolean first = refusedMarkers.add(marker.shared());
            if (first) {
                LOGGER.warn("The shared marker {} cannot be sent.", marker.name());
            }
            item = null;
        }
        return item;
    }

    // Exact: values are whole and fit an int.
    private static int[] coordinates(double[] values) {
        int[] out = new int[values.length];
        for (int at = 0; at < values.length; at++) {
            out[at] = (int) values[at];
        }
        return out;
    }

    private static String clean(String text) {
        return SharedRecord.labelFor(text);
    }

    private static String icon(Landmark marker) {
        SymbolIcon icon = marker.icon();
        return (icon == null ? SymbolIcon.WAYPOINT : icon).name();
    }

    private static boolean same(Entry entry, SharedRecord.Item item) {
        return entry.item != null && entry.item.equals(item);
    }

    // True when the entry changed; server may be null.
    private boolean replace(Entry entry, String source, String server, String playerId, SharedRecord record,
                            long nowMillis, boolean revisionChanged) {
        boolean unchanged = same(entry, record.item()) && record.ownerId().equals(entry.ownerId)
                && record.owner().equals(entry.owner) && playerId.equals(entry.playerId) && !revisionChanged;
        // Moves the entry to this book's saves.
        entry.source = source;
        if (!unchanged) {
            moveTo(entry, server);
            entry.playerId = playerId;
            entry.ownerId = record.ownerId();
            entry.owner = record.owner();
            entry.item = record.item();
            entry.revised = record.revised();
            entry.state = State.TO_SEND;
            entry.nextTry = nowMillis;
            quiet = false;
        }
        return !unchanged;
    }

    // Sets the entry's server; old scope may need removal.
    private static void moveTo(Entry entry, String server) {
        if (!Objects.equals(entry.server, server)) {
            if (scopeOf(server).equals(entry.withdrawFrom)) {
                entry.withdrawFrom = null;
                entry.serverMayHold = true;
            } else {
                if ((entry.withdrawFrom == null) && entry.serverMayHold) {
                    entry.withdrawFrom = scopeOf(entry.server);
                }
                entry.serverMayHold = false;
            }
            entry.server = server;
        }
    }

    // Scope a post reaches; "" for no server.
    private static String scopeOf(String server) {
        return (server == null) ? "" : server;
    }

    // Server named by scope; null for no server.
    private static String serverOf(String scope) {
        return scope.isEmpty() ? null : scope;
    }

    // Number of tombstones queued.
    private int tombstoneMissing(String source, Map<String, Boolean> seen, long nowMillis, long since,
                                 boolean watched) {
        int queued = 0;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (!source.equals(entry.source) || seen.containsKey(entry.key())
                    || entry.item instanceof SharedRecord.Removed) {
                continue;
            }
            if (entry.playerId == null) {
                iterator.remove();
                continue;
            }
            if (entry.revised == Long.MAX_VALUE) {
                if (!entry.unrevisableLogged) {
                    entry.unrevisableLogged = true;
                    LOGGER.warn("The shared {} {} cannot be revised.", entry.kind.wire(), entry.id);
                }
            } else {
                String name = (entry.item instanceof SharedRecord.Area area) ? area.label() : entry.id;
                entry.item = new SharedRecord.Removed();
                entry.revised = Math.max(nowMillis, Math.addExact(entry.revised, 1L));
                entry.state = State.TO_SEND;
                entry.nextTry = nowMillis;
                quiet = false;
                if (watched) {
                    watch(entry, source, name, since);
                }
                queued++;
            }
        }
        return queued;
    }

    // Waits for the answer to the queued revision.
    private void watch(Entry entry, String source, String name, long since) {
        int at = 0;
        while ((at < watches.size()) && (watches.get(at).entry != entry)) {
            at++;
        }
        if (at < watches.size()) {
            watches.remove(at);
        }
        watches.add(new Watch(entry, source, name, since));
    }

    // Replaces the book's held waits; one per shared claim.
    private void holdWaits(PendingClaims hold) {
        ClaimBook book = hold.book();
        int at = 0;
        while (at < heldWaits.size()) {
            HeldWait wait = heldWaits.get(at);
            if (wait.source().equals(hold.source())) {
                heldWaits.remove(at);
                // Said only if the load fails.
                if (!book.isShared(wait.id())) {
                    droppedWaits.add(wait);
                }
            } else {
                at++;
            }
        }
        // Claims the save unshared; said once.
        for (Claim stopped : book.stoppedSharing()) {
            if (!waitsFor(droppedWaits, hold.source(), stopped.id())) {
                droppedWaits.add(new HeldWait(hold.source(), stopped.id(), stopped.name(), hold.since()));
            }
        }
        int dropped = 0;
        while (dropped < droppedWaits.size()) {
            HeldWait wait = droppedWaits.get(dropped);
            if (wait.source().equals(hold.source()) && book.isShared(wait.id())) {
                droppedWaits.remove(dropped);
            } else {
                dropped++;
            }
        }
        for (Claim claim : book.claims()) {
            if (hold.posting().posts(false) && claim.shared()
                    && (Claims.shareable(claim) == Claims.Shareable.YES)) {
                heldWaits.add(new HeldWait(hold.source(), claim.id(), claim.name(), hold.since()));
            }
        }
    }

    // Whether a held wait is still pending.
    private boolean stillHeld(String source, String id) {
        return waitsFor(heldWaits, source, id);
    }

    private static boolean waitsFor(List<HeldWait> waits, String source, String id) {
        boolean waiting = false;
        for (int at = 0; !waiting && (at < waits.size()); at++) {
            HeldWait wait = waits.get(at);
            waiting = wait.source().equals(source) && wait.id().equals(id);
        }
        return waiting;
    }

    // Marks this revision's wait answered; first answer stands.
    private void answered(Entry entry, long revised, Claims.NodeAnswer answer) {
        for (int at = 0; at < watches.size(); at++) {
            Watch watch = watches.get(at);
            if ((watch.entry == entry) && (watch.revised == revised) && (watch.answer == null)) {
                watch.answer = answer;
            }
        }
    }

    // Client thread. Next awaited line, or null.
    Claims.Said answer(String world, long nowNanos) {
        Claims.Said said = null;
        synchronized (this) {
            int at = 0;
            while ((said == null) && (at < watches.size())) {
                Watch watch = watches.get(at);
                if (!watch.source.equals(world)) {
                    watches.remove(at);
                } else if (watch.answer != null) {
                    watches.remove(at);
                    said = Claims.nodeAnswer(watch.answer, watch.name);
                } else if ((nowNanos - watch.since) >= ANSWER_WAIT_NANOS) {
                    watches.remove(at);
                    said = Claims.nodeAnswer(Claims.NodeAnswer.NONE, watch.name);
                } else {
                    at++;
                }
            }
            if (said == null) {
                said = heldAnswer(world, nowNanos);
            }
        }
        return said;
    }

    // No-answer line of a held save, or null.
    private Claims.Said heldAnswer(String world, long nowNanos) {
        Claims.Said said = dueWait(heldWaits, Claims.NodeAnswer.NONE, world, nowNanos);
        if ((said == null) && unreadable) {
            said = dueWait(droppedWaits, Claims.NodeAnswer.NOT_TOLD_TO_DROP, world, nowNanos);
        }
        return said;
    }

    private static Claims.Said dueWait(List<HeldWait> waits, Claims.NodeAnswer saidAs, String world, long nowNanos) {
        Claims.Said said = null;
        int at = 0;
        while ((said == null) && (at < waits.size())) {
            HeldWait wait = waits.get(at);
            if (!wait.source().equals(world)) {
                waits.remove(at);
            } else if ((nowNanos - wait.since()) >= ANSWER_WAIT_NANOS) {
                waits.remove(at);
                said = Claims.nodeAnswer(saidAs, wait.name());
            } else {
                at++;
            }
        }
        return said;
    }

    // Drops every answer wait; says nothing.
    synchronized void forgetAnswers() {
        watches.clear();
        heldWaits.clear();
        droppedWaits.clear();
        answerSession++;
    }

    // Book the claim was last saved from; null if none.
    @Override
    public Claims.SharedEntry entryOf(String claimId) {
        loadIfNeeded();
        synchronized (this) {
            Entry entry = entries.get(key(SharedRecord.Kind.CLAIM, claimId));
            return ((entry == null) || LEGACY_CLAIMS_SOURCE.equals(entry.source)) ? null
                    : new Claims.SharedEntry(entry.source, kept(entry));
        }
    }

    // Last book action: shared, kept unshared, or removed.
    private static Claims.Kept kept(Entry entry) {
        Claims.Kept kept;
        if (!(entry.item instanceof SharedRecord.Removed)) {
            kept = Claims.Kept.SHARED;
        } else if (entry.unshared) {
            kept = Claims.Kept.UNSHARED;
        } else {
            kept = Claims.Kept.REMOVED;
        }
        return kept;
    }

    // Whether entries can be asked; first load ended.
    @Override
    public boolean ready() {
        loadIfNeeded();
        synchronized (this) {
            return loaded;
        }
    }

    // Holds the world's claims port until first load ends.
    @Override
    public synchronized void holdPort(Path book, Supplier<Claims.Said> port) {
        heldPort = new HeldPort(book.toString(), port);
    }

    // Held port for this world's book, or null.
    synchronized Supplier<Claims.Said> portDue(String world) {
        Supplier<Claims.Said> due = null;
        if ((heldPort != null) && loaded) {
            if (heldPort.book().equals(world)) {
                due = heldPort.port();
            }
            heldPort = null;
        }
        return due;
    }

    // Whether the next post is a removal.
    private static boolean postsARemoval(Entry entry) {
        return (entry.withdrawFrom != null) || (entry.item instanceof SharedRecord.Removed);
    }

    // Owed removal, else entry's record; null if refused.
    private static SharedRecord nextPost(Entry entry) {
        SharedRecord post;
        if (entry.withdrawFrom == null) {
            post = recordFor(entry);
        } else if (recordable(entry)) {
            post = new SharedRecord(entry.kind, entry.id, entry.ownerId, entry.owner, entry.revised,
                    new SharedRecord.Removed());
        } else {
            post = null;
        }
        return post;
    }

    // Marks the entry posting; returns the post's server.
    private static String beginPost(Entry entry) {
        entry.posting = true;
        String server;
        if (entry.withdrawFrom != null) {
            server = serverOf(entry.withdrawFrom);
        } else {
            if (!(entry.item instanceof SharedRecord.Removed)) {
                entry.serverMayHold = true;
            }
            server = entry.server;
        }
        return server;
    }

    // Null when the record refuses the entry.
    private static SharedRecord recordFor(Entry entry) {
        SharedRecord record;
        if (recordable(entry)) {
            record = new SharedRecord(entry.kind, entry.id, entry.ownerId, entry.owner, entry.revised, entry.item);
        } else {
            record = null;
        }
        return record;
    }

    // Whether the entry has id, owner, revision and item.
    private static boolean recordable(Entry entry) {
        return SharedRecord.isId(entry.id) && SharedRecord.isName(entry.owner)
                && lowercaseOwnerMatches(entry.owner, entry.ownerId)
                && (entry.revised >= SharedRecord.MIN_REVISED) && itemOfKind(entry.kind, entry.item);
    }

    // OwnerId may be null.
    private static boolean lowercaseOwnerMatches(String ownerName, String ownerId) {
        boolean matches = (ownerId != null) && (ownerName.length() == ownerId.length());
        if (matches) {
            boolean hasUppercase = false;
            for (int at = 0; !hasUppercase && (at < ownerName.length()); at++) {
                char character = ownerName.charAt(at);
                hasUppercase = (character >= 'A') && (character <= 'Z');
            }
            if (hasUppercase) {
                for (int at = 0; matches && (at < ownerName.length()); at++) {
                    matches = Character.toLowerCase(ownerName.charAt(at)) == ownerId.charAt(at);
                }
            } else {
                matches = ownerName.equals(ownerId);
            }
        }
        return matches;
    }

    // Removal; area for claim, point for marker.
    private static boolean itemOfKind(SharedRecord.Kind kind, SharedRecord.Item item) {
        return (item instanceof SharedRecord.Removed)
                || ((kind == SharedRecord.Kind.CLAIM) && (item instanceof SharedRecord.Area))
                || ((kind == SharedRecord.Kind.MARKER) && (item instanceof SharedRecord.Point));
    }

    // Server for the header; null for no header.
    private void postOne(Poster poster, URI endpoint, Entry entry, SharedRecord record, String server,
                         boolean withdrawing) {
        ShareSender.Reply reply = null;
        try {
            byte[] body = record.json().getBytes(StandardCharsets.UTF_8);
            reply = poster.post(endpoint, body, server);
        } catch (IOException | RuntimeException notSent) {
            boolean first;
            synchronized (this) {
                first = !entry.postFailureLogged;
                entry.postFailureLogged = true;
            }
            if (first) {
                LOGGER.debug("Could not post shared {} {}.", entry.kind.wire(), entry.id, notSent);
            }
        }
        long repliedMillis = System.currentTimeMillis();
        boolean warn;
        synchronized (this) {
            entry.posting = false;
            quiet = false;
            warn = replied(entry, record, reply, repliedMillis, scopeOf(server), withdrawing);
        }
        if (warn) {
            warnOf(entry, reply.status());
        }
        saveAsync();
    }

    // Marks the answer for the entry; true if logged.
    private boolean replied(Entry entry, SharedRecord record, ShareSender.Reply reply, long repliedMillis,
                            String scope, boolean withdrawing) {
        boolean warn = false;
        // Only the posted revision may be marked sent or settled.
        boolean current = entry.revised == record.revised();
        boolean held = landed(reply);
        if (held && (record.item() instanceof SharedRecord.Removed)) {
            removedAt(entry, scope);
        }
        if (withdrawing && held) {
            entry.state = State.TO_SEND;
            entry.nextTry = repliedMillis;
        } else if (reply == null) {
            entry.nextTry = repliedMillis + RETRY_MILLIS;
        } else if (reply.status() == RECORD_KEPT) {
            if (current) {
                entry.state = State.SENT;
                answered(entry, record.revised(), Claims.NodeAnswer.SAVED);
            }
        } else if (reply.status() == OLDER_REVISION) {
            if (current) {
                entry.state = State.SETTLED;
                answered(entry, record.revised(), Claims.NodeAnswer.REFUSED);
            }
        } else if (reply.status() == BAD_RECORD || reply.status() == NOT_SIGNED_IN) {
            if (current) {
                entry.state = State.REFUSED;
                entry.nextTry = repliedMillis + REFUSED_RETRY_MILLIS;
                answered(entry, record.revised(), Claims.NodeAnswer.REFUSED);
                warn = !entry.logged;
                entry.logged = true;
            }
        } else if ((reply.status() == NO_SHARED_STORE) || (reply.status() == STORE_FULL)) {
            if (current) {
                entry.nextTry = repliedMillis + REFUSED_RETRY_MILLIS;
                answered(entry, record.revised(), condition(reply.status()));
                warn = entry.conditionLogged != reply.status();
                entry.conditionLogged = reply.status();
            }
        } else if (reply.status() == TOO_MANY && reply.retryAfterSeconds() > 0L) {
            long retrySeconds = Math.min(reply.retryAfterSeconds(), MAX_RETRY_AFTER_SECONDS);
            entry.nextTry = repliedMillis + retrySeconds * MILLIS_PER_SECOND;
        } else {
            entry.nextTry = repliedMillis + RETRY_MILLIS;
        }
        return warn;
    }

    // True for an answer of 204 or 409.
    private static boolean landed(ShareSender.Reply reply) {
        return (reply != null) && ((reply.status() == RECORD_KEPT) || (reply.status() == OLDER_REVISION));
    }

    // Collector holds a removal for this scope.
    private static void removedAt(Entry entry, String scope) {
        if (scope.equals(entry.withdrawFrom)) {
            entry.withdrawFrom = null;
        }
        if (scope.equals(scopeOf(entry.server))) {
            entry.serverMayHold = false;
        }
    }

    // Logs a refusal, or a 404 or 507 answer.
    private static void warnOf(Entry entry, int status) {
        if ((status == NO_SHARED_STORE) || (status == STORE_FULL)) {
            LOGGER.warn("The collector answered {} for the shared {} {}. It tries again later.",
                    Integer.valueOf(status), entry.kind.wire(), entry.id);
        } else {
            LOGGER.warn("The collector refused the shared {} {}.", entry.kind.wire(), entry.id);
        }
    }

    // What a collector said with 404 or 507.
    private static Claims.NodeAnswer condition(int status) {
        return (status == NO_SHARED_STORE) ? Claims.NodeAnswer.NOT_SHARING : Claims.NodeAnswer.FULL;
    }

    // Writes entries on this thread unless file unread.
    void flush() {
        long seq;
        Map<String, Entry> snapshot = null;
        synchronized (this) {
            seq = saveSeq + 1;
            if (loaded && !unreadable) {
                saveSeq = seq;
                snapshot = copyOfEntries();
            }
        }
        if (snapshot != null) {
            write(snapshot, seq);
        }
    }

    private void saveAsync() {
        Map<String, Entry> snapshot;
        long seq;
        synchronized (this) {
            if (!loaded || unreadable) {
                return;
            }
            seq = ++saveSeq;
            snapshot = copyOfEntries();
        }
        Executor executor = testWriteExecutor;
        if (executor != null) {
            executor.execute(() -> write(snapshot, seq));
        } else {
            boolean queued = Sandpaper.isReady() && Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                    () -> {
                        write(snapshot, seq);
                        return Boolean.TRUE;
                    }, done -> { });
            if (!queued) {
                write(snapshot, seq);
            }
        }
    }

    // Copies each entry so the write reads stable data.
    private Map<String, Entry> copyOfEntries() {
        int size = entries.size();
        Map<String, Entry> snapshot = (size > DEFAULT_SNAPSHOT_ENTRIES) ? HashMap.newHashMap(size) : new HashMap<>();
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            snapshot.put(entry.getKey(), new Entry(entry.getValue()));
        }
        return snapshot;
    }

    // Newest write wins; the file lock decides.
    private final class Newest implements AtomicFileReplace.Turn {

        private final long sequence;

        private Newest(long sequence) {
            this.sequence = sequence;
        }

        @Override
        public boolean stillWanted() {
            return sequence >= writtenSeq.get();
        }

        @Override
        public void landed() {
            writtenSeq.set(sequence);
        }
    }

    private void write(Map<String, Entry> snapshot, long seq) {
        if (seq < writtenSeq.get()) {
            return;
        }
        Path temp = file.resolveSibling(file.getFileName() + "." + seq + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            AtomicFileReplace.writeTemp(temp,
                    path -> Files.writeString(path, json(snapshot), StandardCharsets.UTF_8));
            beforeWrite.accept(seq);
            AtomicFileReplace.replace(temp, file, true, new Newest(seq));
        } catch (IOException | RuntimeException failed) {
            LOGGER.warn("Could not save shared outbox {}.", file, failed);
        }
    }

    private static String json(Map<String, Entry> snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("version", OUTBOX_VERSION);
        JsonArray stored = new JsonArray(snapshot.size());
        for (Entry entry : snapshot.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("kind", entry.kind.wire());
            object.addProperty("id", entry.id);
            object.addProperty("source", entry.source);
            if (entry.server != null) {
                object.addProperty("server", entry.server);
            }
            if (entry.withdrawFrom != null) {
                object.addProperty("withdrawFrom", entry.withdrawFrom);
            }
            if (!entry.serverMayHold) {
                object.addProperty("serverMayHold", false);
            }
            object.addProperty("playerId", entry.playerId);
            object.addProperty("ownerId", entry.ownerId);
            object.addProperty("owner", entry.owner);
            object.addProperty("revised", entry.revised);
            object.addProperty("state", entry.state.name());
            object.addProperty("nextTry", entry.nextTry);
            object.add("item", itemJson(entry.item));
            if (entry.unshared && (entry.item instanceof SharedRecord.Removed)) {
                object.addProperty("unshared", true);
            }
            stored.add(object);
        }
        root.add("entries", stored);
        return root.toString();
    }

    private static JsonElement itemJson(SharedRecord.Item item) {
        if (item instanceof SharedRecord.Removed) {
            return null;
        }
        JsonObject object = new JsonObject();
        if (item instanceof SharedRecord.Area area) {
            object.addProperty("label", area.label());
            object.addProperty("world", area.world());
            object.add("xs", intsJson(area.xs()));
            object.add("zs", intsJson(area.zs()));
            object.addProperty("colour", area.colour());
            object.addProperty("line", area.line());
            object.addProperty("fill", area.fill());
        } else if (item instanceof SharedRecord.Point point) {
            object.addProperty("label", point.label());
            object.addProperty("world", point.world());
            object.addProperty("x", point.x());
            object.addProperty("z", point.z());
            object.addProperty("icon", point.icon());
        }
        return object;
    }

    private static JsonArray intsJson(int[] values) {
        JsonArray array = new JsonArray(values.length);
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private void read(Map<String, Entry> loaded, String text) {
        JsonElement parsed = JsonParser.parseString(text);
        if (!(parsed instanceof JsonObject root) || number(root, "version") != OUTBOX_VERSION) {
            throw new IllegalArgumentException("version is not one");
        }
        JsonElement elements = root.get("entries");
        if (!(elements instanceof JsonArray array)) {
            throw new IllegalArgumentException("entries are not an array");
        }
        int dropped = 0;
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject object)) {
                throw new IllegalArgumentException("entry is not an object");
            }
            try {
                Entry entry = readEntry(object);
                if (loaded.put(entry.key(), entry) != null) {
                    throw new IllegalArgumentException("an entry repeats");
                }
            } catch (IOException | IllegalArgumentException refused) {
                dropped++;
            }
        }
        if (dropped > 0) {
            LOGGER.warn("Dropped {} invalid shared outbox entries.", Integer.valueOf(dropped));
        }
    }

    private Entry readEntry(JsonObject object) throws IOException {
        Entry entry = new Entry(kind(string(object, "kind")), string(object, "id"));
        entry.source = string(object, "source");
        entry.server = server(object);
        entry.withdrawFrom = withdrawal(object);
        entry.playerId = nullableString(object, "playerId");
        entry.ownerId = nullableString(object, "ownerId");
        entry.owner = nullableString(object, "owner");
        entry.revised = number(object, "revised");
        entry.state = State.valueOf(string(object, "state"));
        entry.nextTry = number(object, "nextTry");
        entry.item = item(entry.kind, object.get("item"));
        entry.unshared = flag(object, "unshared");
        entry.serverMayHold = serverMayHold(object, entry);
        return entry;
    }

    // Older files lack this key; entry may have posted.
    private static boolean serverMayHold(JsonObject object, Entry entry) {
        boolean mayHold;
        if (entry.withdrawFrom != null) {
            mayHold = false;
        } else if (object.get("serverMayHold") == null) {
            mayHold = entry.playerId != null;
        } else {
            mayHold = flag(object, "serverMayHold");
        }
        return mayHold;
    }

    // A flag that is false when absent.
    private static boolean flag(JsonObject object, String name) {
        JsonElement element = object.get(name);
        boolean on = false;
        if (element != null) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException(name + " is not true or false");
            }
            on = element.getAsBoolean();
        }
        return on;
    }

    private static SharedRecord.Kind kind(String wire) {
        SharedRecord.Kind found = null;
        for (SharedRecord.Kind candidate : SharedRecord.Kind.values()) {
            if (candidate.wire().equals(wire)) {
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("kind is invalid");
        }
        return found;
    }

    private static SharedRecord.Item item(SharedRecord.Kind kind, JsonElement element) throws IOException {
        if (element == null) {
            throw new IllegalArgumentException("item is missing");
        }
        SharedRecord.Item item;
        if (element.isJsonNull()) {
            item = new SharedRecord.Removed();
        } else if (element instanceof JsonObject object) {
            item = SharedRecord.itemFromStored(kind, object);
        } else {
            throw new IllegalArgumentException("item is not an object");
        }
        return item;
    }

    private static long number(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()
                || element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " is not a whole number");
        }
        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " is not a whole number", invalid);
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " is not a string");
        }
        return element.getAsString();
    }

    private static String nullableString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return (element != null && element.isJsonNull()) ? null : string(object, name);
    }

    // The entry's server; null in older files.
    private static String server(JsonObject object) {
        String server = (object.get("server") == null) ? null : nullableString(object, "server");
        if ((server != null) && !Batch.usableName(server)) {
            throw new IllegalArgumentException("server is not a usable name");
        }
        return server;
    }

    // Scope owed a removal; null if none; "" no server.
    private static String withdrawal(JsonObject object) {
        String scope = (object.get("withdrawFrom") == null) ? null : nullableString(object, "withdrawFrom");
        if ((scope != null) && !scope.isEmpty() && !Batch.usableName(scope)) {
            throw new IllegalArgumentException("withdrawal is not a usable name");
        }
        return scope;
    }
}
