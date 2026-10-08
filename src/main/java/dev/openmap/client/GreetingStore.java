package dev.openmap.client;

import dev.openmap.claim.ClaimSplash;
import dev.openmap.claim.GreetingLearner;
import dev.openmap.claim.GreetingPostOutcome;
import dev.openmap.claim.GreetingReportBody;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import dev.openmap.share.Attestation;
import dev.openmap.share.SignedBatch;
import dev.openmap.share.UtcClock;
import dev.sandpaper.Sandpaper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public final class GreetingStore {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final String GREETING_PATH = "/greeting";

    private static final byte[] EMPTY_BYTES = new byte[0];

    private enum PostState { NOT_SENT, SENT, REFUSED }

    private record Key(String server, String dimensionId, String claimId, boolean entering) {
    }

    private static final class Entry {
        final String label;
        final ClaimSplash.Text text;
        PostState state = PostState.NOT_SENT;
        long nextAttemptAtMillis;
        boolean loggedUnreachable;

        boolean posting;

        Entry(String label, ClaimSplash.Text text) {
            this.label = label;
            this.text = text;
        }

        Entry(Entry original) {
            this.label = original.label;
            this.text = original.text;
            this.state = original.state;
            this.nextAttemptAtMillis = original.nextAttemptAtMillis;
        }
    }

    static final long RETRY_AFTER_A_FAILED_SEND_MILLIS = 60_000L;

    private final Path file;

    private final Map<Key, Entry> entries = new HashMap<>();

    private volatile boolean loaded;

    private static volatile Runnable beforeLoadReadForTests;

    private boolean unreadable;

    private long saveSeq;

    private final Object writeLock = new Object();

    private long writtenSeq;

    private final AtomicBoolean stopFlushArmed = new AtomicBoolean();

    volatile java.util.function.LongConsumer beforeWrite = seq -> { };

    volatile java.util.concurrent.Executor testWriteExecutor;

    public GreetingStore(Path file) {
        this.file = file;
    }

    public void loadIfNeeded() {
        synchronized (this) {
            if (loaded) {
                return;
            }
        }
        Map<Key, Entry> read = Map.of();
        boolean failed = false;
        try {
            boolean recovered = AtomicFileReplace.recoverStaleAside(file);
            if (recovered || Files.isRegularFile(file)) {
                Runnable hook = beforeLoadReadForTests;
                if (hook != null) {
                    hook.run();
                }
                JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!root.isJsonArray()) {
                    failed = true;
                    LOGGER.warn("Could not read the learned greetings from {};"
                            + " it stays untouched.", file);
                } else {
                    read = rowsOf(root.getAsJsonArray());
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            failed = true;
            LOGGER.warn("Could not read the learned greetings from {};"
                    + " it stays untouched.", file, unreadable);
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            loaded = true;
            if (failed) {
                unreadable = true;
                return;
            }
            entries.clear();
            entries.putAll(read);
        }
    }

    private static Map<Key, Entry> rowsOf(JsonArray rows) {
        Map<Key, Entry> read = HashMap.newHashMap(rows.size());
        for (JsonElement element : rows) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            Key key = new Key(text(o, "server"), text(o, "dimension"), text(o, "id"),
                    flag(o, "entering"));
            ClaimSplash.Text clean = new ClaimSplash.Text(text(o, "title"),
                    text(o, "subtitle"));
            Entry e = new Entry(text(o, "label"), clean);
            String state = text(o, "state");
            e.state = state.isEmpty() ? PostState.NOT_SENT : PostState.valueOf(state);
            e.nextAttemptAtMillis = (long) number(o, "nextAttempt");
            read.put(key, e);
        }
        return read;
    }

    static void runWithLoadReadHookForTests(Runnable hook, Runnable body) {
        beforeLoadReadForTests = hook;
        try {
            body.run();
        } finally {
            beforeLoadReadForTests = null;
        }
    }

    private static String text(JsonObject o, String name) {
        JsonElement found = o.get(name);
        return found == null || !found.isJsonPrimitive() ? "" : found.getAsString();
    }

    private static boolean flag(JsonObject o, String name) {
        JsonElement found = o.get(name);
        return found != null && found.isJsonPrimitive() && found.getAsBoolean();
    }

    private static double number(JsonObject o, String name) {
        JsonElement found = o.get(name);
        return found == null || !found.isJsonPrimitive() ? 0 : found.getAsDouble();
    }

    public ClaimSplash.Notes asNotes(String server, String dimensionId) {
        return (claimId, entering) -> find(server, dimensionId, claimId, entering);
    }

    public synchronized ClaimSplash.Text find(String server, String dimensionId,
                                              String claimId, boolean entering) {
        Entry entry = entries.get(new Key(server, dimensionId, claimId, entering));
        if (entry == null) {
            return null;
        }
        ClaimSplash.Text text = entry.text;
        return text.title().isEmpty() ? null : text;
    }

    public void observe(String server, String world, String claimId,
                        boolean entering, String dimensionId, String label,
                        GreetingLearner learner) {

        if (!loaded) {
            loadIfNeeded();
        }

        synchronized (this) {
            var note = learner.reportableNote(server, world, claimId, entering);
            if (note.isEmpty()) {
                return;
            }
            String safeLabel = label == null ? "" : label;
            String safeDimensionId = dimensionId == null ? "" : dimensionId;
            Key key = new Key(server, safeDimensionId, claimId, entering);
            GreetingLearner.Note observed = note.get();
            ClaimSplash.Text clean = new ClaimSplash.Text(observed.title(), observed.subtitle());
            Entry existing = entries.get(key);
            if (existing != null && existing.text.title().equals(clean.title())
                    && existing.text.subtitle().equals(clean.subtitle())
                    && existing.label.equals(safeLabel)) {
                return;
            }
            entries.put(key, new Entry(safeLabel, clean));
        }
        saveAsync();
    }

    public synchronized void postDue(ShareSender.Keys keys, ShareSender.Transport transport,
                                     String node, String server, String dimensionId,
                                     long nowMillis) {
        if (node.isEmpty()) {
            return;
        }
        ShareSender.Identity me = keys.current();
        if (me == null || !Attestation.current(me.credential(), UtcClock.collector().nowMillis())) {
            return;
        }
        for (Map.Entry<Key, Entry> candidate : entries.entrySet()) {
            Key key = candidate.getKey();
            Entry entry = candidate.getValue();
            if (entry.posting || entry.state != PostState.NOT_SENT
                    || nowMillis < entry.nextAttemptAtMillis) {
                continue;
            }
            if (!key.server().equals(server) || !key.dimensionId().equals(dimensionId)) {
                continue;
            }

            entry.posting = true;

            final boolean queued;
            if (Sandpaper.isReady()) {
                queued = Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                        () -> {
                            postOne(transport, node, key, entry, me);
                            return Boolean.TRUE;
                        },
                        done -> { }, null, () -> {
                            synchronized (this) {
                                entry.posting = false;
                            }
                        }, done -> { });
            } else {
                queued = false;
            }
            if (!queued) {
                entry.posting = false;
            }
            break;
        }
    }

    private static final ThreadLocal<ShareSender.SigningBytes> SIGNING_BYTES =
            ThreadLocal.withInitial(ShareSender.SigningBytes::new);

    private void postOne(ShareSender.Transport transport, String node, Key key, Entry entry,
                         ShareSender.Identity me) {
        GreetingPostOutcome.Outcome outcome = null;
        try {
            GreetingLearner.Note note = new GreetingLearner.Note(entry.text.title(),
                    entry.text.subtitle());
            final String json;
            if (key.entering()) {
                json = GreetingReportBody.json(key.dimensionId(), key.claimId(), entry.label, note, null);
            } else {
                json = GreetingReportBody.json(key.dimensionId(), key.claimId(), entry.label, null, note);
            }
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ShareSender.SigningBytes updater = SIGNING_BYTES.get();
            updater.set(body);
            final byte[] signature;
            try {
                signature = me.signer().sign(updater);
            } finally {
                updater.set(EMPTY_BYTES);
            }
            byte[] message = SignedBatch.encodeInto(null, me.credential(), signature, body);
            ShareSender.Reply reply = transport.post(greetingEndpoint(node), message);
            outcome = GreetingPostOutcome.decide(reply.status(), reply.retryAfterSeconds());
        } catch (IOException | RuntimeException notSendable) {

        } finally {
            long repliedAtMillis = System.currentTimeMillis();

            final boolean saved;
            synchronized (this) {
                entry.posting = false;
                if (outcome != null) {
                    apply(key, entry, outcome, repliedAtMillis);
                    saved = true;
                } else {
                    entry.nextAttemptAtMillis = repliedAtMillis + RETRY_AFTER_A_FAILED_SEND_MILLIS;
                    saved = false;
                }
            }
            if (saved) {
                saveAsync();
            }
        }
    }

    private static final class GreetingEndpoint {

        private static final URI VALUE = URI.create(dev.openmap.claim.NodeAddress.NODE + GREETING_PATH);

        private GreetingEndpoint() {
        }
    }

    private static URI greetingEndpoint(String node) {
        final URI endpoint;
        if (dev.openmap.claim.NodeAddress.NODE.equals(node)) {
            endpoint = GreetingEndpoint.VALUE;
        } else {
            endpoint = URI.create(node + GREETING_PATH);
        }
        return endpoint;
    }

    synchronized boolean posting() {
        for (Entry entry : entries.values()) {
            if (entry.posting) {
                return true;
            }
        }
        return false;
    }

    private void apply(Key key, Entry entry, GreetingPostOutcome.Outcome outcome,
                       long sentAtMillis) {
        switch (outcome.action()) {
            case SENT -> entry.state = PostState.SENT;
            case REFUSED -> entry.state = PostState.REFUSED;
            case RETRY_AFTER ->
                    entry.nextAttemptAtMillis = sentAtMillis
                            + outcome.retryAfterSeconds() * 1000L;
            case LOG_AND_RETRY_LATER -> {
                if (!entry.loggedUnreachable) {
                    entry.loggedUnreachable = true;
                    LOGGER.warn("The node did not take the greeting for {} ({}); retry"
                            + " in an hour.", key.claimId(), key.entering()
                            ? "greeting" : "farewell");
                }
                entry.nextAttemptAtMillis = sentAtMillis
                        + outcome.retryAfterSeconds() * 1000L;
            }
        }

    }

    private static final int DEFAULT_TABLE_ENTRIES = 12;

    private void saveAsync() {
        final Map<Key, Entry> snapshot;
        long seq;
        synchronized (this) {
            if (!loaded || unreadable) {
                return;
            }
            snapshot = newestSnapshot();
            seq = ++saveSeq;
        }
        java.util.concurrent.Executor testExecutor = testWriteExecutor;
        if (testExecutor != null) {
            testExecutor.execute(() -> writeToDisk(snapshot, seq));
        } else if (Sandpaper.isReady()) {

            boolean queued = Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                    () -> {
                        writeToDisk(snapshot, seq);
                        return Boolean.TRUE;
                    },
                    done -> { }, null, () -> writeToDisk(snapshot, seq), done -> { });
            if (queued) {
                armStopFlush();
            } else {
                writeToDisk(snapshot, seq);
            }
        } else {
            writeToDisk(snapshot, seq);
        }
    }

    private Map<Key, Entry> newestSnapshot() {
        int stored = entries.size();
        Map<Key, Entry> snapshot = stored > DEFAULT_TABLE_ENTRIES
                ? HashMap.newHashMap(stored) : new HashMap<>();
        for (Map.Entry<Key, Entry> entry : entries.entrySet()) {
            snapshot.put(entry.getKey(), new Entry(entry.getValue()));
        }
        return snapshot;
    }

    private void armStopFlush() {
        if (stopFlushArmed.compareAndSet(false, true)) {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> writeNewestAtStop());
        }
    }

    private void writeNewestAtStop() {
        final Map<Key, Entry> snapshot;
        long seq;
        synchronized (this) {
            if (!loaded || unreadable) {
                return;
            }
            snapshot = newestSnapshot();
            seq = ++saveSeq;
        }
        writeToDisk(snapshot, seq);
    }

    private static final java.util.concurrent.atomic.AtomicInteger DIRECTORY_CREATIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    static int createDirectoriesCalls() {
        return DIRECTORY_CREATIONS.get();
    }

    private void writeToDisk(Map<Key, Entry> snapshot, long seq) {

        final Path temp = file.resolveSibling(file.getFileName() + "." + seq + ".tmp");
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                DIRECTORY_CREATIONS.incrementAndGet();
                Files.createDirectories(parent);
            }
            JsonArray root = new JsonArray(snapshot.size());
            for (Map.Entry<Key, Entry> e : snapshot.entrySet()) {
                Key snapshotKey = e.getKey();
                Entry value = e.getValue();
                JsonObject o = new JsonObject();
                o.addProperty("server", snapshotKey.server());
                o.addProperty("dimension", snapshotKey.dimensionId());
                o.addProperty("id", snapshotKey.claimId());
                o.addProperty("entering", snapshotKey.entering());
                o.addProperty("label", value.label);
                o.addProperty("title", value.text.title());
                o.addProperty("subtitle", value.text.subtitle());
                o.addProperty("state", value.state.name());
                o.addProperty("nextAttempt", Long.valueOf(value.nextAttemptAtMillis));
                root.add(o);
            }

            AtomicFileReplace.writeTemp(temp,
                    t -> Files.writeString(t, savedJson(root), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            LOGGER.warn("Could not save the learned greetings to {}.",
                    file, failed);
            return;
        }
        beforeWrite.accept(seq);

        synchronized (writeLock) {
            if (seq < writtenSeq) {

                try {
                    Files.deleteIfExists(temp);
                } catch (IOException | RuntimeException undeletable) {
                    LOGGER.warn("Could not delete the temp file {}.", temp, undeletable);
                }
                return;
            }
            try {

                AtomicFileReplace.replace(temp, file, true, AtomicFileReplace.ALWAYS);
                writtenSeq = seq;
            } catch (IOException | RuntimeException failed) {
                LOGGER.warn("Could not save the learned greetings to {}.",
                        file, failed);
            }
        }
    }

    private static final int NONEMPTY_JSON_CAPACITY = 70;

    private static String savedJson(JsonArray root) {
        if (root.isEmpty()) {
            return "[]";
        }
        return root.toJson(NONEMPTY_JSON_CAPACITY);
    }
}
