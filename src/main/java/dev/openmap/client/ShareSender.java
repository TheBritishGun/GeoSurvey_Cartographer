package dev.openmap.client;

import dev.sandpaper.core.Background;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import dev.sandpaper.core.Scheduler;
import dev.sandpaper.core.WorkPool;
import dev.openmap.LandNav;
import dev.openmap.claim.NodeAddress;
import dev.openmap.claim.ServerConfirmation;
import dev.openmap.config.LandNavConfig;
import dev.openmap.json.NumberGrammar;
import dev.openmap.live.PollSchedule;
import dev.openmap.map.ChunkSample;
import dev.openmap.map.LabelText;
import dev.openmap.map.TabName;
import dev.openmap.share.Attestation;
import dev.openmap.share.Batch;
import dev.openmap.share.Directory;
import dev.openmap.share.Enroller;
import dev.openmap.share.Presence;
import dev.openmap.share.RosterReport;
import dev.openmap.share.SendRate;
import dev.openmap.share.SharedRecord;
import dev.openmap.share.SignedBatch;
import dev.openmap.share.UtcClock;
import dev.openmap.share.WorldAsk;
import dev.openmap.share.WorldPrint;
import dev.openmap.share.WorldProof;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.util.SignatureUpdater;
import net.minecraft.util.Signer;
import net.minecraft.world.entity.player.ProfileKeyPair;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.world.level.GameType;

public final class ShareSender {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final String OBSERVE_PATH = "/observe";

    private static final String PRESENCE_PATH = "/here";

    private static final long PRESENCE_EVERY_MS = PollSchedule.MIN_INTERVAL_MILLIS;

    static final long PRESENCE_HEARTBEAT_MS = 15 * PollSchedule.MIN_INTERVAL_MILLIS;

    static final String ROSTER_PATH = "/roster";

    static final String ENROL_PATH = "/enrol";

    static final long ROSTER_EVERY_MS = 15 * PollSchedule.MIN_INTERVAL_MILLIS;

    static final long ROSTER_URGENT_FLOOR_MS = PollSchedule.MIN_INTERVAL_MILLIS;

    private static final int ROSTER_HOLD_TRIES = 13;

    private static final int FIGURES_HOLD_TRIES = ROSTER_HOLD_TRIES;

    private static final int PLAIN_TREE_NODES = TabName.MAX_FILTER_CHARS + 1;

    private static final Scan UNSETTLED = new Scan(0, true);

    static final String PLAYER_ALGORITHM = Attestation.PLAYER_ALGORITHM;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final int HTTP_STATUS_CLASS = 100;

    private static final int HTTP_SUCCESS_CLASS = 2;

    private static final int HTTP_REDIRECT_CLASS = 3;

    private static final int HTTP_CLIENT_ERROR_CLASS = 4;

    private static final int HTTP_OK = 200;

    private static final int HTTP_UNAUTHORIZED = 401;

    private static final int HTTP_REQUEST_TIMEOUT = 408;

    private static final int HTTP_FORBIDDEN = 403;

    private static final int HTTP_NOT_FOUND = 404;

    private static final int FOLDED_MARKERS_CAPACITY = 4;

    private static final int STARRED_CAPACITY = 4;

    private static final int SPENT_WORLDS_CAPACITY = 4;

    private static final char FIRST_PRINTABLE = 0x20;

    private static final char DELETE_CHAR = 0x7F;

    private static final char LAST_C1_CONTROL = 0x9F;

    private static final int HEAP_ARITY = 2;

    private static final int MIX_SHIFT_FIRST = 30;

    private static final long MIX_MULTIPLIER_FIRST = 0xBF58476D1CE4E5B9L;

    private static final int MIX_SHIFT_SECOND = 27;

    private static final long MIX_MULTIPLIER_SECOND = 0x94D049BB133111EBL;

    private static final int MIX_SHIFT_LAST = 31;

    private static final int KEEPING_CHUNK_BYTES = 512;

    private static final int NAMES_PER_LISTED_PLAYER = 3;

    private static final int MAX_HELD_FOLDS =
            NAMES_PER_LISTED_PLAYER * RosterReport.MAX_PLAYERS + NAMES_PER_LISTED_PLAYER;

    static final int QUEUE_LIMIT = 16384;

    static final long FLUSH_INTERVAL_MILLIS = PollSchedule.MIN_INTERVAL_MILLIS;

    static final long MAX_SPOOL_BYTES = 512L << ShareSpool.MIB_SHIFT;

    private static final long SPOOL_CEILING =
            MAX_SPOOL_BYTES - ShareSpool.RECORD_BYTES;

    private static final double SEND_BURST = 4.0;
    private static final double SEND_PER_SECOND = 1.0;

    private static final long IDLE_MILLIS = 200;

    private static final long SCRIBE_IDLE_MILLIS = 50;

    private static final int SCRIBE_IDLE_BACKOFF_STEPS = 2;

    private static final long MAX_QUIET_SECONDS = PollSchedule.MAX_BACKOFF_MILLIS / MILLIS_PER_SECOND;

    private static final long UNREADABLE_RETRY_AFTER_SECONDS = 60;

    private static final long COMPLAIN_EVERY_MS = 60_000;

    private static final long GROUND_REASK_MILLIS = 60_000L;

    private static final int ENROL_FAILURES_LOGGED = 3;

    private static final long KEY_SETTLE_NANOS = TimeUnit.SECONDS.toNanos(10L);

    private volatile String groundReason = "";

    // Share thread only.
    private int signerFailures;

    private volatile long groundComplainedAt;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final long TIMEOUT_MILLIS = TIMEOUT.toMillis();

    private static final int MAX_REPLY_BYTES = 4096;

    static final class Outbound {

        volatile URI endpoint;
        final String server;

        // Batches carry label; server names the spool folder.
        final String label;
        final String dimension;

        // Null until this world can take ground; tick thread writes.
        volatile ArrayBlockingQueue<ChunkSample> queue;

        volatile ShareSpool spool;
        volatile boolean retired;
        boolean writingBatch = false;

        final PollSchedule postSchedule = new PollSchedule(PollSchedule.MIN_INTERVAL_MILLIS);
        long failedAt = -1L;
        boolean resting;
        long restUntil;
        long triedAt;

        final PollSchedule writeSchedule = new PollSchedule(PollSchedule.MIN_INTERVAL_MILLIS);
        long writeAgainAt;
        long openFailures;
        long openSaidAt;
        long openSaidAbout;

        // Null until claim binds it, and only once.
        private volatile String account;

        Outbound(URI endpoint, String account, String server, String dimension) {
            this(endpoint, account, server, server, dimension, true);
        }

        // takesGroundNow: false for a world adopted from disk.
        Outbound(URI endpoint, String account, String server, String label, String dimension,
                 boolean takesGroundNow) {
            this.endpoint = endpoint;
            this.account = account;
            this.server = server;
            this.label = label;
            this.dimension = dimension;
            if (takesGroundNow) {
                this.queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
            }
        }

        ArrayBlockingQueue<ChunkSample> takeGround() {
            ArrayBlockingQueue<ChunkSample> handoff = queue;
            if (handoff == null) {
                handoff = new ArrayBlockingQueue<>(QUEUE_LIMIT);
                queue = handoff;
            }
            return handoff;
        }

        boolean queueIsEmpty() {
            ArrayBlockingQueue<ChunkSample> handoff = queue;
            return handoff == null || handoff.isEmpty();
        }

        int queued() {
            ArrayBlockingQueue<ChunkSample> handoff = queue;
            return handoff == null ? 0 : handoff.size();
        }

        synchronized String claim(String who) {
            String held = account;
            if (held == null) {
                account = who;
                return who;
            }
            return held;
        }

        String account() {
            return account;
        }

        boolean by(String who) {
            String mine = account;
            return mine != null && mine.equals(who);
        }

        boolean isFor(String otherAccount, String otherServer,
                      String otherDimension) {

            if (retired || !server.equals(otherServer)
                    || !dimension.equals(otherDimension)) {
                return false;
            }
            String held = account;
            return held == null || held.equals(otherAccount);
        }

        boolean mine(String otherAccount) {
            if (retired) {
                return false;
            }
            String held = account;
            return held == null || held.equals(otherAccount);
        }
    }

    record Reply(int status, long retryAfterSeconds) {
    }

    interface Transport {

        Reply post(URI endpoint, byte[] body) throws IOException;

        default Reply post(URI endpoint, byte[] body, int length) throws IOException {
            return post(endpoint, length == body.length ? body : Arrays.copyOf(body, length));
        }

        // POST that reads the reply body.
        default Reply answer(URI endpoint, byte[] body, ByteArrayOutputStream into)
                throws IOException {
            return post(endpoint, body);
        }

        default Reply postReading(URI endpoint, byte[] body, ByteArrayOutputStream into)
                throws IOException {
            return post(endpoint, body);
        }

        // Returns the GET body; null means 404.
        default String fetch(URI endpoint) throws IOException {
            return null;
        }

        default String fetch(URI endpoint, int maxBytes) throws IOException {
            return fetch(endpoint);
        }

        default boolean fetch(URI endpoint, int maxBytes, ByteArrayOutputStream into)
                throws IOException {
            String body = fetch(endpoint, maxBytes);
            into.reset();
            if (body != null) {
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                into.write(bytes, 0, bytes.length);
            }
            return body != null;
        }
    }

    static final class ReplySink extends ByteArrayOutputStream {

        ReplySink() {
            super();
        }

        ReplySink(int size) {
            super(size);
        }

        synchronized void answer(WorldAsk.AnswerHolder into) {
            WorldAsk.answer(buf, count, into);
        }

        synchronized void ask(WorldAsk.AskHolder into) {
            WorldAsk.ask(buf, count, into);
        }
    }

    record Identity(Attestation.Credential credential, Signer signer, String account) {

        Identity(Attestation.Credential credential, Signer signer) {
            this(credential, signer, WorldProof.accountText(credential.player()));
        }
    }

    static Signer reusableSigner(PrivateKey key, String algorithm) {
        ThreadLocal<PerThreadSignature> perThread =
                ThreadLocal.withInitial(() -> new PerThreadSignature(algorithm));
        return updater -> {
            byte[] signed;
            try {
                PerThreadSignature reused = perThread.get();
                reused.signature.initSign(key);
                updater.update(reused);
                signed = reused.signature.sign();
            } catch (GeneralSecurityException cannotSign) {
                throw new IllegalStateException("Failed to sign message", cannotSign);
            }
            return signed;
        };
    }

    private static final class PerThreadSignature implements SignatureUpdater.Output {

        private final Signature signature;

        private PerThreadSignature(String algorithm) {
            try {
                signature = Signature.getInstance(algorithm);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(algorithm, impossible);
            }
        }

        @Override
        public void update(byte[] data) throws SignatureException {
            signature.update(data);
        }

        private void update(byte[] data, int length) throws SignatureException {
            signature.update(data, 0, length);
        }
    }

    static final class SigningBytes implements SignatureUpdater {

        private byte[] bytes;

        private int length;

        void set(byte[] payload) {
            set(payload, payload.length);
        }

        void set(byte[] payload, int payloadLength) {
            bytes = payload;
            length = payloadLength;
        }

        @Override
        public void update(SignatureUpdater.Output output) throws SignatureException {
            if (length == bytes.length) {
                output.update(bytes);
            } else if (output instanceof PerThreadSignature own) {
                own.update(bytes, length);
            } else {
                output.update(Arrays.copyOf(bytes, length));
            }
        }
    }

    interface Keys {

        Identity current();

        default boolean withoutProfileKey() {
            return false;
        }
    }

    // Who the game says is online, filtered. Null means not connected.
    interface Sight {

        List<RosterReport.Entry> listed();

        // The client's tab entry looks vanished.
        boolean hidden();

        // The client is in a world, with a connection and a player.
        default boolean inWorld() {
            return true;
        }

        // The operator's entry, for the probe only; null if none.
        default Self self() {
            return null;
        }

        // The client's tab entry has a staff marker.
        default boolean staff() {
            return false;
        }
    }

    static final String DEFAULT_VANISH_MARKERS = "vanish,[v],(v)";

    static final String DEFAULT_STAFF_MARKERS = "\u2605";

    // Never null or empty. Tick thread only.
    private volatile List<String> vanishMarkers = markersOf(DEFAULT_VANISH_MARKERS);

    // May be empty.
    private volatile List<String> staffMarkers = staffMarkersOf(DEFAULT_STAFF_MARKERS);

    private String vanishFoldedFrom;

    private List<String> vanishFolded;

    private String staffFoldedFrom;

    private List<String> staffFolded;

    // Null when not probing.
    private volatile VanishProbe probe;

    private final VanishProbe.MarkedBy markedBy = new VanishProbe.MarkedBy(this::marked, this::markedProfile);

    private static final String VANISHED =
            "this client is vanished;"
                    + " only queued ground"
                    + " goes up";

    private static final String ENROL_HELD =
            "vanished; the ground handshake waits until visible";

    private static final String GROUND_HELD =
            "geosurvey does not contribute ground: this client is vanished."
                    + " Your map still records everything.";

    static final String PRESENCE_WITHHELD =
            "the position switch is off; turn it on"
                    + " in the settings";

    static final String ROSTER_WITHHELD =
            "the position switch is off;"
                    + " it also covers the player list";

    static final String PRESENCE_WITHHELD_BY_BUILD =
            "this build sends no position;"
                    + " only a shared claim or marker sends your player name";

    static final String ROSTER_WITHHELD_BY_BUILD =
            "this build sends no player list";

    private static final String ROSTER_HELD =
            "this client is vanished;"
                    + " the player list waits until visible";

    static final String PROOF_TURNED_ON =
            "Prove this account to the collector is on;"
                    + " it sends your player name to"
                    + " the collector and to Mojang.";

    private static final String REFUSED_KEPT =
            " a batch refused 3 times is kept locally. It is retried after "
                    + SetAsideSchedule.waitHours(1) + " hours, then " + SetAsideSchedule.waitHours(2)
                    + ", up to " + SetAsideSchedule.longestWaitDays()
                    + " days; past " + (ShareSpool.MAX_REFUSED_BYTES >> ShareSpool.MIB_SHIFT)
                    + " MiB the oldest is deleted.";

    static final String REFUSED_KEY =
            "The collector refused an upload: it does not accept this computer's own key."
                    + " Run /geosurvey share proof on; it sends"
                    + " your player name to the collector"
                    + " and to Mojang.";

    static final String REFUSED_CLOCK =
            "The collector refused an upload;"
                    + " check your clock;" + REFUSED_KEPT;

    static final String REFUSED =
            "The collector refused an upload; run /geosurvey share for more;" + REFUSED_KEPT;

    static final String ENROL_REFUSED = "that collector refused the proof";

    static final String ENROL_UNROUTED = "that collector does not offer the road";

    static final String ENROL_DENIED =
            "The collector refused this computer's own key: Mojang did not"
                    + " confirm this account. GeoSurvey asks again after a"
                    + " restart.";

    static List<String> markersOf(String configured) {
        List<String> asked = fold(configured);
        return asked.isEmpty() ? fold(DEFAULT_VANISH_MARKERS) : asked;
    }

    static List<String> staffMarkersOf(String configured) {
        return fold(configured);
    }

    private List<String> vanishMarkersFor(String configured) {
        if (vanishFolded == null || !Objects.equals(configured, vanishFoldedFrom)) {
            vanishFolded = markersOf(configured);
            vanishFoldedFrom = configured;
        }
        return vanishFolded;
    }

    private List<String> staffMarkersFor(String configured) {
        if (staffFolded == null || !Objects.equals(configured, staffFoldedFrom)) {
            staffFolded = staffMarkersOf(configured);
            staffFoldedFrom = configured;
        }
        return staffFolded;
    }

    private static List<String> fold(String list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        Set<String> out = null;
        int length = list.length();
        int from = 0;
        while (from <= length) {
            int comma = list.indexOf(',', from);
            int end = comma < 0 ? length : comma;
            int start = from;
            while (start < end && list.charAt(start) <= ' ') {
                start++;
            }
            while (end > start && list.charAt(end - 1) <= ' ') {
                end--;
            }
            if (start < end) {
                if (out == null) {
                    out = new LinkedHashSet<>(FOLDED_MARKERS_CAPACITY);
                }
                out.add(list.substring(start, end).toLowerCase(Locale.ROOT));
            }
            from = comma < 0 ? length + 1 : comma + 1;
        }
        return out == null ? List.of() : List.copyOf(out);
    }

    // True for a name too long to read.
    boolean marked(String raw) {
        return marked(heldFold(markedFold, markedFolds, raw));
    }

    boolean markedProfile(String raw) {
        return markedProfile(heldFold(profileFold, profileFolds, raw));
    }

    // False for a name too long to read.
    boolean starred(String raw) {
        return starred(heldFold(starredFold, starredFolds, raw));
    }

    private boolean marked(NameFold name) {
        if (pastReadingOf(name)) {
            return true;
        }
        return matches(name, vanishMarkers, false);
    }

    private boolean markedProfile(NameFold name) {
        if (pastReadingOf(name)) {
            return true;
        }
        return matches(name, vanishMarkers, true);
    }

    private boolean starred(NameFold name) {
        if (pastReadingOf(name)) {
            return false;
        }
        return matches(name, staffMarkers, false);
    }

    private boolean matches(NameFold name, List<String> markers, boolean wholeWord) {
        if (name.raw == null || name.raw.isEmpty() || markers.isEmpty()) {
            return false;
        }
        if (carries(foldedOf(name), markers, wholeWord)) {
            return true;
        }
        String plainFolded = plainFoldedOf(name);
        return plainFolded != null && carries(plainFolded, markers, wholeWord);
    }

    int foldCalls;

    int plainCalls;

    int pastReadCalls;

    private static final class NameFold {

        private String raw;
        private boolean past;
        private boolean pastReady;
        private String folded;
        private boolean foldedReady;
        private String plainFolded;
        private boolean plainReady;

        private NameFold(String raw) {
            this.raw = raw;
        }
    }

    private final NameFold markedFold = new NameFold(null);

    private final NameFold profileFold = new NameFold(null);

    private final NameFold starredFold = new NameFold(null);

    private final NameFold verdictName = new NameFold(null);

    private final NameFold verdictShown = new NameFold(null);

    private final NameFold verdictNearby = new NameFold(null);

    private NameFold nameFold(NameFold into, String raw) {
        into.raw = raw;
        into.past = false;
        into.pastReady = false;
        into.folded = null;
        into.foldedReady = false;
        into.plainFolded = null;
        into.plainReady = false;
        return into;
    }

    private NameFold nameFold(NameFold into, String raw, boolean past) {
        NameFold fold = nameFold(into, raw);
        fold.past = past;
        fold.pastReady = true;
        return fold;
    }

    private NameFold heldFold(NameFold scratch, WalkMemo<String, NameFold> held, String raw) {
        NameFold fold;
        if (raw == null || raw.isEmpty()) {
            fold = nameFold(scratch, raw);
        } else {
            fold = held.get(raw);
            if (fold == null) {
                if (held.askedCount() >= MAX_HELD_FOLDS) {
                    held.clear();
                }
                fold = new NameFold(raw);
                held.put(raw, fold);
            } else {
                fold.past = false;
                fold.pastReady = false;
            }
        }
        return fold;
    }

    private void forgetFoldsNotAsked() {
        markedFolds.endWalk();
        profileFolds.endWalk();
        starredFolds.endWalk();
    }

    private final WalkMemo<String, NameFold> markedFolds = new WalkMemo<>();

    private final WalkMemo<String, NameFold> profileFolds = new WalkMemo<>();

    private final WalkMemo<String, NameFold> starredFolds = new WalkMemo<>();

    private boolean pastReadingOf(NameFold name) {
        if (!name.pastReady) {
            name.past = pastReading(name.raw);
            name.pastReady = true;
        }
        return name.past;
    }

    private String foldedOf(NameFold name) {
        if (!name.foldedReady) {
            name.folded = toLowerCounted(name.raw);
            name.foldedReady = true;
        }
        return name.folded;
    }

    private String plainFoldedOf(NameFold name) {
        if (!name.plainReady) {
            String plain = plainCounted(name.raw);
            name.plainFolded = plain.equals(name.raw) ? null : toLowerCounted(plain);
            name.plainReady = true;
        }
        return name.plainFolded;
    }

    private String toLowerCounted(String text) {
        foldCalls++;
        return text.toLowerCase(Locale.ROOT);
    }

    private String plainCounted(String raw) {
        plainCalls++;
        return plain(raw);
    }

    private boolean pastReading(String raw) {
        pastReadCalls++;
        return TabName.pastReading(raw);
    }

    static String plain(String raw) {
        StringBuilder withoutControls = null;
        for (int at = 0; at < raw.length(); at++) {
            char glyph = raw.charAt(at);
            if (glyph < FIRST_PRINTABLE || (glyph >= DELETE_CHAR && glyph <= LAST_C1_CONTROL)) {
                if (withoutControls == null) {
                    withoutControls = new StringBuilder(raw.length()).append(raw, 0, at);
                }
            } else if (withoutControls != null) {
                withoutControls.append(glyph);
            }
        }
        return LabelText.clean(withoutControls == null ? raw : withoutControls.toString(),
                LabelText.UNBOUNDED_READ,
                LabelText.UNBOUNDED_READ, false);
    }

    private static boolean carries(String folded, List<String> markers,
                                   boolean wholeWord) {
        boolean found = false;
        int which = 0;
        while (!found && which < markers.size()) {
            String marker = markers.get(which);
            int at = folded.indexOf(marker);
            while (!found && at >= 0) {
                if (!wholeWord || standsAlone(folded, at, marker.length())) {
                    found = true;
                } else {
                    at = folded.indexOf(marker, at + 1);
                }
            }
            which++;
        }
        return found;
    }

    private static boolean standsAlone(String folded, int at, int length) {
        return !wordCharacter(folded, at - 1)
                && !wordCharacter(folded, at + length);
    }

    private static boolean wordCharacter(String folded, int at) {
        return at >= 0 && at < folded.length()
                && Character.isLetterOrDigit(folded.charAt(at));
    }

    // One unfiltered tab-list player; shown is null until named, nearby is null unless loaded.
    static final class Seen {

        private UUID id;

        private String name;

        private String shown;

        private String nearby;

        private boolean spectator;

        private boolean located;

        private int x;

        private int z;

        Seen(UUID id, String name, String shown, String nearby,
             boolean spectator, boolean located, int x, int z) {
            this.id = id;
            this.name = name;
            this.shown = shown;
            this.nearby = nearby;
            this.spectator = spectator;
            this.located = located;
            this.x = x;
            this.z = z;
        }

        void fill(UUID id, String name, String shown, String nearby) {
            this.id = id;
            this.name = name;
            this.shown = shown;
            this.nearby = nearby;
        }

        void place(boolean spectator, boolean located, int x, int z) {
            this.spectator = spectator;
            this.located = located;
            this.x = x;
            this.z = z;
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
    }

    // The client's tab entry for the probe; connected false means nothing else was read.
    record Self(UUID id, String name, String shown, String nearby,
                boolean connected, boolean found, boolean listed, GameType gameMode) {

        static Self adrift() {
            return new Self(null, null, null, null, false, false, false, null);
        }
    }

    interface Sent {

        boolean running();

        long positions();

        long rosters();

        long groundTaken();

        long groundHeld();

        String positionReason();

        String rosterReason();

        long positionAttempts();

        long rosterAttempts();

        Sending settled();
    }

    record Sending(boolean running, long positions, long rosters,
                   long groundTaken, long groundHeld,
                   String positionReason, String rosterReason,
                   long positionAttempts, long rosterAttempts) implements Sent {

        Sending(boolean running, long positions, long rosters,
                long groundTaken, long groundHeld,
                String positionReason, String rosterReason) {
            this(running, positions, rosters, groundTaken, groundHeld, positionReason,
                    rosterReason, positions, rosters);
        }

        @Override
        public Sending settled() {
            return this;
        }
    }

    private static final class SendingScratch implements Sent {

        private boolean running;

        private long positions;

        private long rosters;

        private long groundTaken;

        private long groundHeld;

        private String positionReason;

        private String rosterReason;

        private long positionAttempts;

        private long rosterAttempts;

        private void fillCounts(boolean running, long positions, long rosters,
                                long groundTaken, long groundHeld) {
            this.running = running;
            this.positions = positions;
            this.rosters = rosters;
            this.groundTaken = groundTaken;
            this.groundHeld = groundHeld;
        }

        private void fillBeats(String positionReason, String rosterReason,
                               long positionAttempts, long rosterAttempts) {
            this.positionReason = positionReason;
            this.rosterReason = rosterReason;
            this.positionAttempts = positionAttempts;
            this.rosterAttempts = rosterAttempts;
        }

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public long positions() {
            return positions;
        }

        @Override
        public long rosters() {
            return rosters;
        }

        @Override
        public long groundTaken() {
            return groundTaken;
        }

        @Override
        public long groundHeld() {
            return groundHeld;
        }

        @Override
        public String positionReason() {
            return positionReason;
        }

        @Override
        public String rosterReason() {
            return rosterReason;
        }

        @Override
        public long positionAttempts() {
            return positionAttempts;
        }

        @Override
        public long rosterAttempts() {
            return rosterAttempts;
        }

        @Override
        public Sending settled() {
            return new Sending(running, positions, rosters, groundTaken, groundHeld,
                    positionReason, rosterReason, positionAttempts, rosterAttempts);
        }
    }

    // Excludes players on any suspicion signal.
    List<RosterReport.Entry> reportable(List<Seen> seen) {
        List<RosterReport.Entry> out = rosterOut(seen.size());
        Set<UUID> stars = null;
        forgetFoldsIfMarkersMoved();
        for (int which = 0; which < seen.size(); which++) {
            Seen player = seen.get(which);
            if (player == null || player.id() == null) {
                continue;
            }
            String name = player.name();
            if (name == null
                    || name.length() > RosterReport.MAX_PLAYER_NAME
                    || name.isBlank()
                    || RosterReport.cleanedName(name).isBlank()) {
                continue;
            }
            boolean shownPastReading = pastReading(player.shown());
            boolean nearbyPastReading;
            if (shownPastReading) {
                nearbyPastReading = false;
            } else {
                nearbyPastReading = pastReading(player.nearby());
            }
            if (shownPastReading || nearbyPastReading) {

                if (heldNames.add(player.id())) {
                    heldForLength++;
                }
            } else {
                Verdict carried = verdicts.get(player.id());
                if (carried == null
                        || !carried.forNames(name, player.shown(), player.nearby())) {
                    carried = verdictFor(carried, name, player.shown(), player.nearby(),
                            shownPastReading, nearbyPastReading);
                    verdicts.put(player.id(), carried);
                }
                if (!carried.hidden) {
                    out.add(carried.entryFor(player, name));

                    if (carried.starred) {
                        if (stars == null) {
                            stars = new HashSet<>(STARRED_CAPACITY);
                        }
                        stars.add(player.id());
                    }
                }
            }
        }
        forgetWhoWasNotAsked();
        starredSeen = stars == null
                ? Collections.emptySet() : Collections.unmodifiableSet(stars);
        watch(seen, out);
        return out;
    }

    private static final class Verdict {

        private String name;
        private String shown;
        private String nearby;
        private boolean hidden;
        private boolean starred;
        private RosterReport.Entry entry;

        private Verdict(String name, String shown, String nearby, boolean hidden,
                        boolean starred) {
            this.name = name;
            this.shown = shown;
            this.nearby = nearby;
            this.hidden = hidden;
            this.starred = starred;
            this.entry = null;
        }

        private void reread(String name, String shown, String nearby, boolean hidden,
                            boolean starred) {
            this.name = name;
            this.shown = shown;
            this.nearby = nearby;
            this.hidden = hidden;
            this.starred = starred;
        }

        private boolean forNames(String other, String otherShown, String otherNearby) {
            return name.equals(other) && Objects.equals(shown, otherShown)
                    && Objects.equals(nearby, otherNearby);
        }

        private RosterReport.Entry entryFor(Seen player, String reportedName) {
            RosterReport.Entry last = entry;
            boolean located = player.located();
            if (last == null || last.spectator() != player.spectator()
                    || last.located() != located || !last.name().equals(reportedName)
                    || (located && (last.x() != player.x() || last.z() != player.z()))) {
                last = located
                        ? RosterReport.Entry.at(player.id(), reportedName, player.spectator(),
                                player.x(), player.z())
                        : RosterReport.Entry.somewhere(player.id(), reportedName,
                                player.spectator());
                entry = last;
            }
            return last;
        }
    }

    private Verdict verdictFor(Verdict carried, String name, String shown, String nearby,
                               boolean shownPastReading, boolean nearbyPastReading) {
        NameFold nameFolded = nameFold(verdictName, name);
        NameFold shownFolded = nameFold(verdictShown, shown, shownPastReading);
        NameFold nearbyFolded = nameFold(verdictNearby, nearby, nearbyPastReading);
        boolean profileMarked = markedProfile(nameFolded);
        boolean shownMarked = marked(shownFolded);
        boolean nearbyMarked = marked(nearbyFolded);
        if (profileMarked || shownMarked || nearbyMarked) {
            return verdictInto(carried, name, shown, nearby, true, false);
        }
        boolean nameStarred = starred(nameFolded);
        boolean shownStarred = starred(shownFolded);
        boolean nearbyStarred = starred(nearbyFolded);
        return verdictInto(carried, name, shown, nearby, false,
                nameStarred || shownStarred || nearbyStarred);
    }

    private static Verdict verdictInto(Verdict carried, String name, String shown,
                                       String nearby, boolean hidden, boolean starred) {
        if (carried == null) {
            return new Verdict(name, shown, nearby, hidden, starred);
        }
        carried.reread(name, shown, nearby, hidden, starred);
        return carried;
    }

    private final WalkMemo<UUID, Verdict> verdicts = new WalkMemo<>();

    private static final class WalkMemo<K, V> {

        private Object2ObjectOpenHashMap<K, V> kept = new Object2ObjectOpenHashMap<>();

        private Object2ObjectOpenHashMap<K, V> asked = new Object2ObjectOpenHashMap<>();

        private V get(K key) {
            V carried = asked.get(key);
            if (carried == null) {
                carried = kept.get(key);
                if (carried != null) {
                    asked.put(key, carried);
                }
            }
            return carried;
        }

        private void put(K key, V answer) {
            asked.put(key, answer);
        }

        private int askedCount() {
            return asked.size();
        }

        private void endWalk() {
            Object2ObjectOpenHashMap<K, V> dropped = kept;
            kept = asked;
            asked = dropped;
            dropped.clear();
        }

        private void clear() {
            kept.clear();
            asked.clear();
        }
    }

    private void forgetFoldsIfMarkersMoved() {
        List<String> vanish = vanishMarkers;
        List<String> staff = staffMarkers;
        if (!vanish.equals(carriedVanish) || !staff.equals(carriedStaff)) {
            carriedVanish = vanish;
            carriedStaff = staff;
            verdicts.clear();
            tabPlaces.clear();
        }
    }

    private void forgetWhoWasNotAsked() {
        verdicts.endWalk();
        tabPlaces.endWalk();
    }

    private List<String> carriedVanish;

    private List<String> carriedStaff;

    // Tick thread writes; command thread reads.
    private volatile long heldForLength;

    private final Set<UUID> heldNames = ConcurrentHashMap.newKeySet();

    long heldForLength() {
        return heldForLength;
    }


    private final Set<UUID> rosterCapped = ConcurrentHashMap.newKeySet();

    private UUID[] cutLastScan = new UUID[0];

    // Filled entries of cutLastScan.
    private int cutLastCount;

    private UUID[] cutKeys = new UUID[0];

    private int[] cutOrder = new int[0];

    long heldForRosterCap() {
        return rosterCapped.size();
    }

    // Null without an account.
    private static UUID accountOf(PlayerInfo info) {
        GameProfile profile = info == null ? null : info.getProfile();
        return profile == null ? null : profile.id();
    }

    // Returns online when it fits, else a new list in account order; counts the cut accounts.
    <T> List<T> withinRosterCap(List<T> online, Function<T, UUID> account) {
        return withinRosterCap(online, account, each -> true);
    }

    <T> List<T> withinRosterCap(List<T> online, Function<T, UUID> account,
                                Predicate<T> reported) {
        return withinRosterCap(online, account, reported,
                new ArrayList<>(RosterReport.MAX_PLAYERS));
    }

    <T> List<T> withinRosterCap(List<T> online, Function<T, UUID> account,
                                Predicate<T> reported, List<T> kept) {
        int cap = RosterReport.MAX_PLAYERS;
        int size = online.size();
        if (size <= cap) {
            return online;
        }
        UUID[] keys = cutKeys;
        if (keys.length < size) {
            keys = new UUID[grownTo(size, keys.length)];
            cutKeys = keys;
        }
        int[] order = cutOrder;
        if (order.length < size) {
            order = new int[grownTo(size, order.length)];
            cutOrder = order;
        }
        for (int at = 0; at < size; at++) {
            keys[at] = account.apply(online.get(at));
            order[at] = at;
        }
        for (int at = size / HEAP_ARITY - 1; at >= 0; at--) {
            siftByAccount(keys, order, size, at);
        }
        kept.clear();
        int rest = size;
        while (rest > 0) {
            int next = order[0];
            rest--;
            order[0] = order[rest];
            siftByAccount(keys, order, rest, 0);
            T each = online.get(next);
            if (reported.test(each)) {
                kept.add(each);
                if (kept.size() == cap) {
                    break;
                }
            }
        }
        UUID[] settled = cutLastScan;
        int knownFor = cutLastCount;
        UUID[] keep = settled.length >= rest ? settled : new UUID[grownTo(rest, settled.length)];
        for (int at = 0; at < rest; at++) {
            int index = order[at];
            UUID cut = keys[index];
            UUID lastScan = at < knownFor ? settled[at] : null;

            keep[at] = cut;
            if (cut != null && cut != lastScan && !cut.equals(lastScan)) {
                if (reported.test(online.get(index))) {
                    rosterCapped.add(cut);
                } else {
                    keep[at] = null;
                }
            }
        }
        cutLastScan = keep;
        cutLastCount = rest;
        return kept;
    }

    private static void siftByAccount(UUID[] keys, int[] order, int rest, int root) {
        int at = root;
        int left = at * HEAP_ARITY + 1;
        boolean settled = false;
        while (!settled && left < rest) {
            int child = left;
            int right = left + 1;
            if (right < rest && takesTheEarlierPlace(keys, order[right], order[left])) {
                child = right;
            }
            if (takesTheEarlierPlace(keys, order[child], order[at])) {
                int swap = order[at];
                order[at] = order[child];
                order[child] = swap;
                at = child;
                left = at * HEAP_ARITY + 1;
            } else {
                settled = true;
            }
        }
    }

    private static boolean takesTheEarlierPlace(UUID[] keys, int mine, int other) {
        UUID username = keys[mine];
        UUID theirs = keys[other];
        boolean earlier;
        if (username == null) {
            earlier = theirs == null && mine < other;
        } else if (theirs == null) {
            earlier = true;
        } else {
            int byAccount = username.compareTo(theirs);
            earlier = byAccount != 0 ? byAccount < 0 : mine < other;
        }
        return earlier;
    }

    private static int grownTo(int needed, int held) {
        return Math.max(needed, held + (held >> 1));
    }

    List<RosterReport.Entry> withinRosterByteCap(String server, String dimension, String by,
                                                  long sent,
                                                  List<RosterReport.Entry> players) {
        List<RosterReport.Entry> kept = RosterReport.withinByteCap(server, dimension, by,
                sent, players);
        for (int at = kept.size(); at < players.size(); at++) {
            UUID cut = players.get(at).player();
            rosterCapped.add(cut);
        }
        return kept;
    }

    boolean takesAReportedPlace(UUID id, String name, String shown) {
        if (id == null) {
            return false;
        }

        forgetFoldsIfMarkersMoved();
        TabPlace carried = tabPlaces.get(id);
        boolean takes;
        if (carried != null && carried.forNames(name, shown)) {
            takes = carried.takesAPlace;
        } else {
            boolean profileMarked = markedProfile(name);
            boolean shownMarked = marked(shown);
            takes = name != null
                    && name.length() <= RosterReport.MAX_PLAYER_NAME
                    && !name.isBlank()
                    && !profileMarked && !shownMarked;
            if (carried == null) {
                tabPlaces.put(id, new TabPlace(name, shown, takes));
            } else {
                carried.reread(name, shown, takes);
            }
        }
        return takes;
    }

    private static final class TabPlace {

        private String name;
        private String shown;
        private boolean takesAPlace;

        private TabPlace(String name, String shown, boolean takesAPlace) {
            this.name = name;
            this.shown = shown;
            this.takesAPlace = takesAPlace;
        }

        private void reread(String name, String shown, boolean takesAPlace) {
            this.name = name;
            this.shown = shown;
            this.takesAPlace = takesAPlace;
        }

        private boolean forNames(String other, String otherShown) {
            return Objects.equals(name, other) && Objects.equals(shown, otherShown);
        }
    }

    private final WalkMemo<UUID, TabPlace> tabPlaces = new WalkMemo<>();

    private volatile Set<UUID> starredSeen = Collections.emptySet();

    // From the last roster walk.
    Set<UUID> starredSeen() {
        return starredSeen;
    }

    // From the last scan.
    boolean selfStarred() {
        return selfStarred;
    }

    private final SendingScratch sendingScratch = new SendingScratch();

    private void watch(List<Seen> seen, List<RosterReport.Entry> out) {
        VanishProbe armed = probe;
        if (armed == null) {
            return;
        }
        try {
            sendingScratch.fillCounts(isSending(), presenceSent, rosterSent,
                    offered, heldWhileHidden);
            sendingScratch.fillBeats(presenceReason, rosterReason,
                    presenceAttempted, rosterAttempted);
            armed.observe(seen, out, markedBy, vanishMarkers,
                    selfHidden, selfRead,
                    sendingScratch);
        } catch (Throwable trouble) {
            probe = null;

            armed.stop();
            LOGGER.error("The vanish probe threw and was disarmed. The roster"
                    + " filter still runs.", trouble);
        }
    }

    private enum Posted {
        TAKEN, RETRY, REFUSED
    }

    private final Transport transport;
    private final Keys keys;
    private final Sight sight;
    private volatile Enroller.Session session;
    private final SendRate rate = new SendRate(SEND_BURST, SEND_PER_SECOND);
    private long nextTokenAt;

    private final PollSchedule schedule =
            new PollSchedule(PollSchedule.MIN_INTERVAL_MILLIS);

    private final PollSchedule presenceSchedule =
            new PollSchedule(PRESENCE_EVERY_MS);

    private final PollSchedule rosterSchedule = new PollSchedule(ROSTER_EVERY_MS);

    // Runs on the share thread.
    private final Scheduler shareLane = new Scheduler(System::nanoTime);

    private final AtomicBoolean pumping = new AtomicBoolean();

    private final WorkPool enrolWork = new WorkPool(1, 1);

    private final StoppableWorkers.Registration enrolWorkStop = stopWork("geosurvey-enrol", enrolWork);

    private final AtomicBoolean enrolWorking = new AtomicBoolean();

    private final WorkPool groundWork = new WorkPool(1, 1);

    private final StoppableWorkers.Registration groundWorkStop = stopWork("geosurvey-ground", groundWork);

    private final AtomicBoolean groundAsking = new AtomicBoolean();

    private final WorkPool uploadWork = new WorkPool(1, 1);

    private final StoppableWorkers.Registration uploadWorkStop = stopWork("geosurvey-upload", uploadWork);

    private final AtomicBoolean uploadWorking = new AtomicBoolean();

    private final UtcClock utc;

    private final boolean measuresClock;

    private final WorkPool clockWork = new WorkPool(1, 1);

    private final StoppableWorkers.Registration clockWorkStop = stopWork("geosurvey-clock", clockWork);

    private final AtomicBoolean clockMeasuring = new AtomicBoolean();

    private final Supplier<UtcClock> measureClockJob = this::measureClock;

    private final Consumer<UtcClock> measuredClockJob = this::measuredClock;

    // Tick thread only.
    private boolean proofLineOwed;

    // Set on the share thread, taken on the tick thread; null when no refusal waits.
    private final AtomicReference<URI> enrolDeniedAt = new AtomicReference<>();

    private Handle enrolJob;
    private Handle groundJob;
    private Handle presenceJob;
    private Handle rosterJob;
    private Handle uploadJob;
    private boolean laneReady;

    private static final Handle[] NO_BEATS = new Handle[0];

    // Empty until prepared.
    private Handle[] beats = NO_BEATS;

    private final GroundWalk walk;

    // Tick thread writes; share thread reads.
    private volatile String groundServer;

    // Tick thread writes; share thread reads.
    private volatile long worldRolls = 0L;

    // Share thread only; keyMissingSince reads System.nanoTime().
    private boolean keyMissing = false;

    private long keyMissingSince = 0L;

    private final CopyOnWriteArrayList<Outbound> worlds = new CopyOnWriteArrayList<>();

    private final Object worldsLock = new Object();

    private final Consumer<Outbound> matchingElsewhere = this::matchElsewhere;

    private Outbound elsewhereOf = null;

    private boolean elsewhereHeld = false;

    private volatile Outbound current;

    private long landings;

    private long attempts;

    private int failingWorlds;

    private boolean lastLanded = true;

    private volatile Identity identity;

    private static volatile ShareSender live;

    private static volatile LandNavConfig groundOnlyArtifact;

    static void publishesGroundOnly(LandNavConfig config) {
        groundOnlyArtifact = config;
    }

    private static boolean withholdsPresence(LandNavConfig config) {
        return config != null && config == groundOnlyArtifact;
    }

    private volatile Thread worker;

    private volatile Thread outgoing;

    private volatile Thread scribe;

    private int scribeIdlePasses;

    // Null before the first scan or with no world; only standing writes it.
    private volatile Scan scan;

    private final Scan scanFirst = new Scan(RosterReport.MAX_PLAYERS, false);

    private final Scan scanSecond = new Scan(RosterReport.MAX_PLAYERS, false);

    private final Scan scanThird = new Scan(RosterReport.MAX_PLAYERS, false);

    private ArrayList<RosterReport.Entry> rosterLent;

    private volatile Scan scanInHand;

    private volatile boolean selfHidden;

    // Set by each vanish sample, after selfHidden; cleared out of a world.
    private volatile boolean selfSampled = false;

    // Written only by standing.
    private volatile boolean selfStarred;

    // Material behind selfHidden, for the probe; null when not sampled.
    private volatile Self selfRead;

    private URI beatingAt;

    // Share thread only.
    private URI rosterBeatingAt;

    // Null when the last beat failed. Share thread only.
    private Here lastPosted;

    private final Here postedPlace = new Here(null, null, null, 0, 0, 0, false, false);

    // System.nanoTime() at lastPosted. Share thread only.
    private long lastPostedAt;

    private UUID lastPostedFor;

    private final Presence.Parts presenceParts = new Presence.Parts();

    private final SigningBytes presenceSigning = new SigningBytes();

    private final SigningBytes rosterSigning = new SigningBytes();

    private final SigningBytes uploadSigning = new SigningBytes();

    private byte[] presencePayload = new byte[0];

    private byte[] presenceMessage = new byte[0];

    private long rosterDueAt;

    // No early roster before this System.nanoTime(). Share thread only.
    private long rosterUrgentDueAt;

    // Checksum and player count of the last roster taken; count is -1 if none.
    private long rosterPostedKey;

    private int rosterPostedCount = -1;

    private byte[] rosterPayload = new byte[0];

    private byte[] rosterMessage = new byte[0];

    private int rosterMessageLength;

    public static final class Here {

        String server;

        String dimension;

        String name;

        int x;

        int z;

        int ticks;

        boolean storm;

        boolean thunder;

        public Here(String server, String dimension, String name, int x, int z,
                    int ticks, boolean storm, boolean thunder) {
            this.server = server;
            this.dimension = dimension;
            this.name = name;
            this.x = x;
            this.z = z;
            this.ticks = ticks;
            this.storm = storm;
            this.thunder = thunder;
        }

        private void copyFrom(Here other) {
            server = other.server;
            dimension = other.dimension;
            name = other.name;
            x = other.x;
            z = other.z;
            ticks = other.ticks;
            storm = other.storm;
            thunder = other.thunder;
        }
    }

    // where is never null; roster is null when not connected.
    private static final class Scan {

        private final Here where = new Here(null, null, null, 0, 0, 0, false, false);

        private final ArrayList<RosterReport.Entry> ownRoster;

        private List<RosterReport.Entry> roster;

        private boolean hidden;

        private Scan(int capacity, boolean hidden) {
            this.ownRoster = new ArrayList<>(capacity);
            this.hidden = hidden;
        }
    }

    // Tick thread only.
    public void standing(Here where) {
        forgetFoldsNotAsked();

        VanishProbe armed = probe;
        if (where != null) {

            sampleHidden();

            boolean starred = armed == null ? false : sight.staff();
            if (starred != selfStarred) {
                selfStarred = starred;
            }

            selfRead = armed == null ? null : sight.self();

            sayGroundGate();
        }
        if (where == null) {

            scan = null;
        } else {

            Scan next = lendScan();
            next.where.copyFrom(where);
            next.roster = (publishPresence && current != null) || armed != null
                    ? walkRoster(next) : null;
            next.hidden = selfHidden;
            scan = next;
        }
    }

    // Tick thread only; the one sample of whether this client is vanished.
    private void sampleHidden() {
        boolean hidden = sight.hidden();
        if (hidden != selfHidden) {
            selfHidden = hidden;
            wakeOnNextPump(rosterJob);
        }
        if (!selfSampled) {
            selfSampled = true;
        }
    }

    // Tick thread only.
    private void forgetHiddenSample() {
        if (selfSampled) {
            selfSampled = false;
        }
    }

    // Any thread; true while this client is vanished, and before the first vanish sample in a world.
    boolean vanished() {
        return !selfSampled || selfHidden;
    }

    // Any thread; the live sender's answer, and true when no sender exists.
    static boolean liveVanished() {
        ShareSender sender = live;
        return sender == null || sender.vanished();
    }

    private List<RosterReport.Entry> walkRoster(Scan next) {
        rosterLent = next.ownRoster;
        List<RosterReport.Entry> walked;
        try {
            walked = sight.listed();
        } finally {
            rosterLent = null;
        }
        return walked;
    }

    private Scan lendScan() {
        Scan published = scan;
        Scan held = scanInHand;
        Scan free = scanFirst;
        if (free == published || free == held) {
            free = scanSecond;
            if (free == published || free == held) {
                free = scanThird;
            }
        }
        return free;
    }

    private List<RosterReport.Entry> rosterOut(int size) {
        ArrayList<RosterReport.Entry> lent = rosterLent;
        if (lent == null) {
            return new ArrayList<>(size);
        }
        rosterLent = null;
        lent.clear();
        lent.ensureCapacity(size);
        return lent;
    }

    private void sayGroundGate() {
        boolean hidden = selfHidden;
        if (hidden == groundHeldSaid || current == null) {
            return;
        }
        groundHeldSaid = hidden;
        if (hidden) {
            heldWhenVanishBegan = heldWhileHidden;
            LOGGER.info(GROUND_HELD);
        } else {
            LOGGER.info("geosurvey contributes again. It dropped"
                    + " {} surveyed chunks while vanished;"
                    + " your map kept them.",
                    heldWhileHidden - heldWhenVanishBegan);
        }
    }

    private volatile boolean running;

    private long offered;

    // Surveyed chunks not contributed.
    private final AtomicLong dropped = new AtomicLong();
    private volatile long overrun;
    private volatile long refusedAtCeiling;

    private volatile long heldWhileHidden;

    // Tick thread only.
    private boolean groundHeldSaid;

    // Tick thread only.
    private long heldWhenVanishBegan;
    private String parsedFrom = "";
    private volatile URI parsedEndpoint;

    private volatile URI presenceEndpoint;

    private volatile URI rosterEndpoint;

    private volatile URI enrolEndpoint;

    private CollectorWire collectorWire;

    private final Consumer<Outbound> pointingAtCollector = this::pointAtCollector;

    private volatile boolean proveAccount;

    // Tick thread writes; share thread reads.
    private volatile boolean publishPresence;

    private volatile boolean withheldByBuild;

    // Share thread writes; enrol worker and client thread read.
    private volatile String enrolledAt = "";

    private String enrolmentShut = "";

    // Enrol worker thread only.
    private String enrolKey = "";

    private URI enrolKeyFrom;

    private Attestation.Credential enrolKeyFor;

    private final PollSchedule enrolSchedule =
            new PollSchedule(PollSchedule.MIN_INTERVAL_MILLIS);

    private final PollSchedule groundSchedule =
            new PollSchedule(PollSchedule.MIN_INTERVAL_MILLIS);

    // Share thread only.
    private long groundAskAt;

    private long groundReaskAt;

    // Share thread only.
    private final BackoffKey backoffArmedOn = new BackoffKey();

    // Restart count at the last enrolment and ground ask. Share thread only.
    private long enrolAskedUnder = 0L;

    private long groundAskedUnder = 0L;

    private static final class BackoffKey {

        private URI collector = null;

        private String server = null;

        private long rolls = 0L;

        private Attestation.Credential key = null;

        private long restarts = 0L;

        private BackoffKey() {
        }

        // True when an input moved; keeps the new inputs and counts a restart.
        private boolean moved(URI atCollector, String onServer, long atRolls,
                              Attestation.Credential withKey) {
            boolean moved = !Objects.equals(collector, atCollector)
                    || !Objects.equals(server, onServer) || rolls != atRolls || key != withKey;
            if (moved) {
                collector = atCollector;
                server = onServer;
                rolls = atRolls;
                key = withKey;
                restarts++;
            }
            return moved;
        }

        private long restarts() {
            return restarts;
        }
    }

    private final Supplier<URI> currentEndpoint = () -> parsedEndpoint;

    private final AtomicReference<GroundAsk> spareGroundAsk = new AtomicReference<>();

    private volatile String enrolReason = "";

    private final ShareBacklog backlog;


    private static final long STAMP_HOLD_MILLIS = Attestation.GRACE.toMillis() / 8;

    // Share thread only.
    private final RetryStamps retryStamps = new RetryStamps();

    private static final class RetryStamps {

        private static final int CAPACITY = 16;

        private final Object2ObjectOpenHashMap<Path, Stamp> held =
                new Object2ObjectOpenHashMap<>(CAPACITY);

        private final Stamp[] free = new Stamp[CAPACITY];

        private final Stamp[] pooled = new Stamp[CAPACITY];

        private int freeCount;

        private long clock;

        private RetryStamps() {
            for (int i = 0; i < CAPACITY; i++) {
                free[i] = new Stamp(0L, 0L, 0L);
                pooled[i] = free[i];
            }
            freeCount = CAPACITY;
        }

        private Stamp get(Path file) {
            Stamp stamp = held.get(file);
            if (stamp != null) {
                stamp.touchedAt = ++clock;
            }
            return stamp;
        }

        private void put(Path file, long era, long fileBytes, long sent) {
            Stamp stamp = held.get(file);
            if (stamp == null) {
                stamp = freeCount > 0 ? free[--freeCount] : evictOldest();
                held.put(file, stamp);
                stamp.file = file;
            }
            stamp.era = era;
            stamp.fileBytes = fileBytes;
            stamp.sent = sent;
            stamp.touchedAt = ++clock;
        }

        private void remove(Path file) {
            Stamp stamp = held.remove(file);
            if (stamp != null) {
                stamp.file = null;
                free[freeCount++] = stamp;
            }
        }

        private Stamp evictOldest() {
            Stamp oldest = null;
            for (int i = 0; i < CAPACITY; i++) {
                Stamp candidate = pooled[i];
                if (candidate.file != null
                        && (oldest == null || candidate.touchedAt < oldest.touchedAt)) {
                    oldest = candidate;
                }
            }
            held.remove(oldest.file);
            return oldest;
        }
    }

    private static final class Stamp {

        private long era;

        private long fileBytes;

        private long sent;

        private long touchedAt;

        private Path file;

        private Stamp(long era, long fileBytes, long sent) {
            this.era = era;
            this.fileBytes = fileBytes;
            this.sent = sent;
            this.file = null;
        }

        private long era() {
            return era;
        }

        private long fileBytes() {
            return fileBytes;
        }

        private long sent() {
            return sent;
        }
    }

    private volatile long sent;
    private volatile long refused;
    private volatile long failed;
    private volatile int lastStatus;

    private volatile String lastRefusal = "";

    private volatile boolean uploadUnreached;

    private final AtomicReference<byte[]> uploadMessageSpare = new AtomicReference<>();

    private final Batch.Room batchRoom = new Batch.Room();

    private final AtomicReference<Upload> spareUpload = new AtomicReference<>();

    private volatile long presenceSent;
    private volatile long presenceAttempted;
    private volatile long presenceRefused;
    private volatile long presenceFailed;
    private volatile int presenceLastStatus;

    private volatile long rosterSent;
    private volatile long rosterAttempted;
    private volatile long rosterRefused;
    private volatile long rosterFailed;
    private volatile int rosterLastStatus;
    private volatile int rosterPlayers;

    private volatile String rosterReason = "";

    private String rosterAnnounced;

    private volatile String presenceReason = "";

    private String presenceAnnounced;

    // Writer thread only.
    private long spoolHeld;

    private static final long SPOOL_HELD_UNTAKEN = -1L;

    private final Consumer<Outbound> writingOut = this::writeOut;

    private List<ChunkSample> passScratch = null;

    private boolean passIdle = true;

    private long complainedAt;
    private long complainedAbout;

    private long ceilingComplainedAbout;

    // Share and writer threads write.
    private volatile long hardFaultSaidAt;

    ShareSender() {
        this(new HttpTransport(), new GameKeys(), null, null, UtcClock.collector());
    }

    ShareSender(Transport transport, Keys keys) {
        this(transport, keys, null);
    }

    ShareSender(Transport transport, Keys keys, Path spoolRoot) {
        this(transport, keys, spoolRoot, null);
    }

    // sight is null for the running client's own tab list.
    ShareSender(Transport transport, Keys keys, Path spoolRoot, Sight sight) {
        this(transport, keys, spoolRoot, sight, UtcClock.collector(), false);
    }

    // sight is null for the running client's own tab list; the share thread measures utc.
    ShareSender(Transport transport, Keys keys, Path spoolRoot, Sight sight, UtcClock utc) {
        this(transport, keys, spoolRoot, sight, utc, true);
    }

    private ShareSender(Transport transport, Keys keys, Path spoolRoot, Sight sight, UtcClock utc,
                        boolean measuresClock) {
        this.transport = transport;
        this.keys = keys;
        this.sight = sight == null ? new GameSight() : sight;
        this.session = new GameSession();
        this.backlog = new ShareBacklog(spoolRoot, worlds, worldsLock);
        this.utc = utc;
        this.measuresClock = measuresClock;
        this.walk = new GroundWalk(utc);
        live = this;
    }


    // Tick thread only.
    void offer(ChunkSample sample) {

        if (selfHidden) {
            heldWhileHidden++;
            return;
        }
        Outbound live = current;
        if (live == null) {
            return;
        }
        offered++;

        if (live.queue.offer(sample)) {
            if (worker == null) {
                start();
            }
        } else {
            dropped.incrementAndGet();
            overrun++;
        }
    }

    void sync(LandNavConfig config, String server, String dimension) {
        vanishMarkers = vanishMarkersFor(config.shareVanishMarkers);

        staffMarkers = staffMarkersFor(config.shareStaffMarkers);

        probe = VanishProbe.armed(config.shareVanishProbe, probe);

        // With no server, sync takes the vanish sample; out of a world, no sample is current.
        if (server == null) {
            if (sight.inWorld()) {
                sampleHidden();
            } else {
                forgetHiddenSample();
            }
        }

        proveAccount = config.shareSessionProof;

        boolean wasPublishing = publishPresence;
        boolean buildWithholds = withholdsPresence(config);
        boolean nowPublishing = config.sharePresence && !buildWithholds;
        publishPresence = nowPublishing;
        withheldByBuild = buildWithholds;
        if (wasPublishing != nowPublishing) {
            wakeOnNextPump(presenceJob);
            wakeOnNextPump(rosterJob);
        }

        server = Batch.usableServerName(server);
        boolean serverOk = server != null && Batch.usableName(server);
        boolean dimensionOk = dimension != null && Batch.usableName(dimension);
        groundServer = serverOk ? server : null;
        walk.joined(groundServer);
        URI endpoint = endpointFor(config);
        if (endpoint == null || !serverOk || !dimensionOk) {
            shutDown();
        } else {
            worlds.forEach(pointingAtCollector);

            identity = keys.current();
            proveWithoutProfileKey(config, endpoint);
            String account = accountOf(identity);
            Outbound live = current;
            if (live == null || !live.isFor(account, server, dimension)) {
                worldRolls++;
                synchronized (worldsLock) {
                    rollOver(worldFor(endpoint, account, server, dimension));
                }
            }
            if (worker == null) {
                start();
            }
        }
    }

    // Tick thread only.
    private void proveWithoutProfileKey(LandNavConfig config, URI endpoint) {
        Identity who = identity;
        if (!config.shareSessionProof && !config.shareSessionProofAutoEnabled && who != null
                && keys.withoutProfileKey() && LocalKey.isMine(who.credential())) {
            config.shareSessionProof = true;
            config.shareSessionProofAutoEnabled = true;
            proveAccount = true;
            CollectorOptions.persistLive();
            proofLineOwed = true;
            LOGGER.info("geosurvey turned on proving this account to the collector: this client has"
                    + " no Mojang profile key.");
        }
        if (proofLineOwed) {
            proofLineOwed = !walk.sayAt(endpoint, PROOF_TURNED_ON);
        }
    }

    // Null with no identity.
    private static String accountOf(Identity who) {
        return who == null ? null : who.account();
    }

    private void pointAtCollector(Outbound out) {
        out.endpoint = parsedEndpoint;
    }

    private Outbound worldFor(URI endpoint, String account, String server,
                              String dimension) {
        return backlog.worldFor(endpoint, account, server, dimension);
    }

    private void rollOver(Outbound next) {

        if (next != null) {
            next.takeGround();
        }
        current = next;
    }

    void close() {
        if (transport instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception trouble) {
                LOGGER.error("The share transport failed to close.", trouble);
            }
        }
    }

    // Stops posting. Does not touch the backlog.
    private void shutDown() {
        current = null;

        heldNames.clear();
        heldForLength = 0;
        verdicts.clear();
        tabPlaces.clear();
        rosterCapped.clear();

        cutLastCount = 0;
        Thread stopping = worker;
        if (stopping != null) {
            outgoing = stopping;
            worker = null;
            running = false;
            identity = null;
            enrolWork.cancelAllFor(CollectorMod.MOD_ID);
            uploadWork.cancelAllFor(CollectorMod.MOD_ID);
            groundWork.cancelAllFor(CollectorMod.MOD_ID);
            uploadWorking.set(false);
            enrolWorking.set(false);
            groundAsking.set(false);
            stopping.interrupt();
        }
    }

    private void prepareLane() {
        if (laneReady) {
            return;
        }
        laneReady = true;
        enrolJob = shareLane.register(beatSpec("share-enrol", 1), tick -> queuedEnrolBeat());

        groundJob = shareLane.register(beatSpec("share-ground", 1),
                tick -> groundBeat());
        presenceJob = shareLane.register(beatSpec("share-presence", 1), tick -> beat());
        rosterJob = shareLane.register(beatSpec("share-roster", 1), tick -> rosterBeat());
        uploadJob = shareLane.register(beatSpec("share-upload", 1), tick -> pass());
        beats = new Handle[] {enrolJob, groundJob, presenceJob, rosterJob, uploadJob};
    }

    private static JobSpec beatSpec(String label, long millis) {
        return JobSpec.everyMillis(Lane.TICK, Math.max(1L, millis))
                .mustRun()
                .neverDrop()
                .withOwner(CollectorMod.MOD_ID)
                .withLabel(label);
    }

    // Share thread writes; a command reads.
    private volatile BeatFigures beatsSaid;

    private volatile BeatFigures figuresInHand;

    private final BeatFigures figuresFirst = new BeatFigures();

    private final BeatFigures figuresSecond = new BeatFigures();

    private final BeatFigures figuresThird = new BeatFigures();

    String beatHealth() {
        BeatFigures seen = heldFigures();
        String line;
        try {
            line = seen == null ? "" : lineOf(seen);
        } finally {
            figuresInHand = null;
        }
        return line;
    }

    private BeatFigures heldFigures() {
        BeatFigures seen = beatsSaid;
        figuresInHand = seen;
        BeatFigures again = beatsSaid;
        int settling = 1;
        while (again != seen && settling < FIGURES_HOLD_TRIES) {
            seen = again;
            figuresInHand = seen;
            again = beatsSaid;
            settling++;
        }
        return again == seen ? seen : null;
    }

    private BeatFigures figuresFree() {
        BeatFigures published = beatsSaid;
        BeatFigures held = figuresInHand;
        BeatFigures free = figuresFirst;
        if (free == published || free == held) {
            free = figuresSecond;
            if (free == published || free == held) {
                free = figuresThird;
            }
        }
        return free;
    }

    private static String lineOf(BeatFigures said) {
        if (said.rows == 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (int row = 0; row < said.rows; row++) {
            line.append(" ").append(said.labels[row]);
            long runs = said.runs[row];
            if (runs == 0L) {
                line.append(" has never run.");
            } else {
                line.append(" took ").append(said.peakNanos[row] / NANOS_PER_MILLI)
                        .append("ms at worst, against a ")
                        .append(said.cadenceMillis[row]).append("ms cadence, in ")
                        .append(runs).append(" run(s).");
            }
        }
        return line.toString();
    }

    // Share thread only.
    private void healthOfBeats() {
        Handle[] walked = beats;
        BeatFigures next = figuresFree();
        next.fit(walked.length);
        int said = 0;
        for (Handle job : walked) {
            if (job == null) {
                continue;
            }
            long runs = job.runs();
            if (runs == 0L) {
                next.say(said, job.label(), 0L, 0L, 0L);
                said++;
            } else {

                long cadenceMillis = healthCadenceMillis(job);
                long periodNanos = cadenceMillis * NANOS_PER_MILLI;
                if (periodNanos > 0L && job != enrolJob && job != uploadJob) {
                    long peak = job.peakCostNanos();
                    if (peak > periodNanos) {
                        next.say(said, job.label(), runs, peak, cadenceMillis);
                        said++;
                    }
                }
            }
        }
        next.rows = said;
        beatsSaid = next;
    }

    private static final class BeatFigures {

        private static final String[] NO_LABELS = new String[0];

        private static final long[] NO_FIGURES = new long[0];

        private String[] labels = NO_LABELS;

        private long[] runs = NO_FIGURES;

        private long[] peakNanos = NO_FIGURES;

        private long[] cadenceMillis = NO_FIGURES;

        private int rows;

        private void fit(int wanted) {
            if (runs.length < wanted) {
                labels = new String[wanted];
                runs = new long[wanted];
                peakNanos = new long[wanted];
                cadenceMillis = new long[wanted];
            }
        }

        private void say(int row, String label, long runCount, long peak, long cadence) {
            labels[row] = label;
            runs[row] = runCount;
            peakNanos[row] = peak;
            cadenceMillis[row] = cadence;
        }
    }

    private long healthCadenceMillis(Handle job) {
        long cadence;
        if (job == groundJob) {
            cadence = FLUSH_INTERVAL_MILLIS;
        } else if (job == rosterJob) {
            cadence = ROSTER_EVERY_MS;
        } else {
            cadence = job.periodMillis();
        }
        return cadence;
    }

    private static StoppableWorkers.Registration stopWork(String name, WorkPool work) {
        return StoppableWorkers.close(name, work::close,
                bound -> work.closeWithin(bound, TimeUnit.MILLISECONDS), () -> false);
    }

    private static final long PUMP_BUDGET_NANOS = 0L;

    private void start() {
        running = true;
        prepareLane();
        Thread started = Background.thread(this::run, "geosurvey-share");
        worker = started;
        StoppableWorkers.thread("geosurvey-share", started);
        started.start();
        ensureScribe();
    }

    private void ensureScribe() {
        Thread writing = scribe;
        if (writing != null && writing.isAlive()) {
            return;
        }
        synchronized (this) {
            writing = scribe;
            if (writing == null || !writing.isAlive()) {
                Thread made = Background.thread(this::scribble, "geosurvey-spool");
                scribe = made;
                made.start();
            }
        }
    }

    private URI endpointFor(LandNavConfig config) {
        if (!config.shareEnabled) {
            return forgetAddress();
        }
        String configured = config.shareCollector;
        if (configured == null || configured.isBlank()) {
            return forgetAddress();
        }
        if (!configured.equals(parsedFrom)) {
            parsedFrom = configured;

            String base = baseOf(configured);
            parsedEndpoint = endpointAt(base, OBSERVE_PATH);
            presenceEndpoint = endpointAt(base, PRESENCE_PATH);
            rosterEndpoint = endpointAt(base, ROSTER_PATH);
            enrolEndpoint = endpointAt(base, ENROL_PATH);
        }
        return parsedEndpoint;
    }

    private URI forgetAddress() {
        parsedFrom = "";
        parsedEndpoint = null;
        presenceEndpoint = null;
        rosterEndpoint = null;
        enrolEndpoint = null;
        return null;
    }

    static URI endpointOf(String configured, String path) {
        return endpointAt(baseOf(configured), path);
    }

    // Null when the address normalises to nothing.
    static String baseOf(String configured) {
        String base = Directory.withScheme(configured.trim());

        int question = base.indexOf('?');
        int fragment = base.indexOf('#');
        int cut = question < 0 ? base.length() : question;
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        while (cut > 0 && base.charAt(cut - 1) == '/') {
            cut--;
        }
        base = base.substring(0, cut);
        return base.isEmpty() ? null : base;
    }

    // Null if base is null or base plus path is unusable.
    private static URI endpointAt(String base, String path) {
        if (base == null) {
            return null;
        }
        URI usable;
        try {
            URI endpoint = URI.create(base + path);
            String scheme = endpoint.getScheme();
            String host = endpoint.getHost();
            boolean rebuildHost = host == null || !host.equals(NodeAddress.withoutRootDot(host));
            if (rebuildHost) {
                endpoint = URI.create(withoutHostRootDot(base) + path);
                host = endpoint.getHost();
            }
            if (host == null || scheme == null
                    || (rebuildHost && !host.equals(NodeAddress.withoutRootDot(host)))) {
                usable = null;
            } else if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                usable = null;
            } else {
                usable = endpoint;
            }
        } catch (IllegalArgumentException notAnAddress) {
            usable = null;
        }
        return usable;
    }

    private static String withoutHostRootDot(String base) {
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0) {
            return base;
        }
        int start = schemeEnd + 3;
        int authorityEnd = base.indexOf('/', start);
        if (authorityEnd < 0) {
            authorityEnd = base.length();
        }
        int userinfo = base.lastIndexOf('@', authorityEnd - 1);
        if (userinfo >= start) {
            start = userinfo + 1;
        }
        int port = base.indexOf(':', start);
        if (port < 0 || port > authorityEnd) {
            port = authorityEnd;
        }
        String host = NodeAddress.withoutRootDot(base.substring(start, port));
        return host.length() == port - start ? base
                : base.substring(0, start) + host + base.substring(port);
    }

    static Keys gameKeys() {
        return new GameKeys();
    }

    private static final class GameKeys implements Keys {

        private CompletableFuture<Optional<ProfileKeyPair>> request;
        private ProfileKeyPair lastPair;
        private Identity built;

        // Tick thread only.
        private boolean ownKeyOnly;

        @Override
        public Identity current() {
            Minecraft client = Minecraft.getInstance();
            ProfileKeyPairManager manager =
                    client == null ? null : client.getProfileKeyPairManager();
            if (manager == null) {
                return forget();
            }
            if (request == null || manager.shouldRefreshKeyPair()) {
                request = manager.prepareKeyPair();
            }
            Identity held;
            if (request.isDone()) {
                held = fromAnswer(client);
            } else {
                held = built;
            }
            return held;
        }

        private Identity fromAnswer(Minecraft client) {
            Optional<ProfileKeyPair> answer;
            try {
                answer = request.getNow(Optional.empty());
            } catch (RuntimeException noKeys) {
                answer = Optional.empty();
            }
            Identity held;
            if (answer.isEmpty()) {
                held = ownKey(client);
            } else {
                held = fromPair(client, answer.get());
            }
            return held;
        }

        private Identity fromPair(Minecraft client, ProfileKeyPair pair) {
            ownKeyOnly = false;
            Identity held;
            if (pair == lastPair) {
                held = built;
            } else {
                GameProfile profile = client.getGameProfile();
                UUID player = profile == null ? null : profile.id();
                if (player == null) {
                    held = forget();
                } else {
                    ProfilePublicKey.Data data = pair.publicKey().data();
                    lastPair = pair;
                    built = new Identity(
                            new Attestation.Credential(player, data.expiresAt(),
                                    data.key().getEncoded(), data.keySignature()),
                            reusableSigner(pair.privateKey(), PLAYER_ALGORITHM));
                    held = built;
                }
            }
            return held;
        }

        private Identity ownKey(Minecraft client) {
            GameProfile profile = client.getGameProfile();
            UUID player = profile == null ? null : profile.id();
            if (player == null) {
                return forget();
            }
            lastPair = null;
            built = LocalKey.identity(player);
            ownKeyOnly = true;
            return built;
        }

        private Identity forget() {
            lastPair = null;
            built = null;
            ownKeyOnly = false;
            return null;
        }

        @Override
        public boolean withoutProfileKey() {
            return ownKeyOnly;
        }
    }

    private final class GameSight implements Sight {

        private final List<PlayerInfo> online = new ArrayList<>();

        private final Consumer<PlayerInfo> intoOnline = online::add;

        private final List<PlayerInfo> kept = new ArrayList<>(RosterReport.MAX_PLAYERS);

        private final Object2ObjectOpenHashMap<UUID, String> shownThisScan =
                new Object2ObjectOpenHashMap<>();

        private final Predicate<PlayerInfo> capTest = this::reportableTabEntry;

        private final List<Seen> seenScratch = new ArrayList<>(RosterReport.MAX_PLAYERS);

        private final Object2ObjectOpenHashMap<UUID, AbstractClientPlayer> bodies =
                new Object2ObjectOpenHashMap<>();

        private final WalkMemo<UUID, TabText> tabTexts = new WalkMemo<>();

        private final ArrayList<Component> treeScratch = new ArrayList<>();

        List<PlayerInfo> onlineScratch() {
            return online;
        }

        @Override
        public List<RosterReport.Entry> listed() {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener connection =
                    client == null ? null : client.getConnection();
            if (connection == null) {
                return null;
            }

            online.clear();
            shownThisScan.clear();
            tabTexts.endWalk();
            connection.getListedOnlinePlayers().forEach(intoOnline);
            List<PlayerInfo> capped = withinRosterCap(online, ShareSender::accountOf,
                    capTest, kept);
            nearby(client, capped, bodies);
            int filled = 0;
            try {
                for (int at = 0; at < capped.size(); at++) {
                    PlayerInfo info = capped.get(at);
                    GameProfile profile = info == null ? null : info.getProfile();
                    if (profile == null) {
                        continue;
                    }
                    UUID id = profile.id();
                    if (id == null) {
                        continue;
                    }
                    AbstractClientPlayer body = bodies.get(id);
                    BlockPos blockAt = body == null ? null : body.blockPosition();

                    String shownName = shownFor(id, info);
                    String nearbyName = body == null ? null : text(body.getDisplayName());
                    boolean spectating = info.getGameMode() == GameType.SPECTATOR;
                    boolean locatedHere = blockAt != null;
                    int atX = blockAt == null ? 0 : blockAt.getX();
                    int atZ = blockAt == null ? 0 : blockAt.getZ();
                    if (filled < seenScratch.size()) {
                        Seen reused = seenScratch.get(filled);
                        reused.fill(id, profile.name(), shownName, nearbyName);
                        reused.place(spectating, locatedHere, atX, atZ);
                    } else {
                        seenScratch.add(new Seen(id, profile.name(), shownName, nearbyName,
                                spectating, locatedHere, atX, atZ));
                    }
                    filled++;
                }
            } finally {
                bodies.clear();
            }
            while (seenScratch.size() > filled) {
                seenScratch.remove(seenScratch.size() - 1);
            }
            return reportable(seenScratch);
        }

        private String shownFor(UUID id, PlayerInfo info) {
            String carried = shownThisScan.get(id);
            return carried != null ? carried : tabText(id, info.getTabListDisplayName());
        }

        private boolean reportableTabEntry(PlayerInfo info) {
            GameProfile profile = info == null ? null : info.getProfile();
            if (profile == null) {
                return false;
            }
            UUID id = profile.id();
            String shown = tabText(id, info.getTabListDisplayName());

            if (id != null && shown != null) {
                shownThisScan.put(id, shown);
            }
            return takesAReportedPlace(id, profile.name(), shown);
        }

        @Override
        public boolean hidden() {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener connection =
                    client == null ? null : client.getConnection();
            if (connection == null || client.player == null) {
                return true;
            }
            PlayerInfo mine = connection.getPlayerInfo(client.player.getUUID());
            if (mine == null || !connection.getListedOnlinePlayers().contains(mine)) {
                return true;
            }

            boolean vanished = mine.getGameMode() == GameType.SPECTATOR;
            if (!vanished) {
                GameProfile profile = mine.getProfile();
                vanished = markedProfile(profile == null ? null : profile.name());
            }
            if (!vanished) {
                vanished = marked(tabText(client.player.getUUID(), mine.getTabListDisplayName()));
            }
            if (!vanished) {
                vanished = marked(text(client.player.getDisplayName()));
            }
            return vanished;
        }

        @Override
        public boolean inWorld() {
            Minecraft client = Minecraft.getInstance();
            return client != null && client.getConnection() != null && client.player != null;
        }

        @Override
        public Self self() {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener connection =
                    client == null ? null : client.getConnection();
            if (connection == null || client.player == null) {
                return Self.adrift();
            }
            UUID id = client.player.getUUID();
            PlayerInfo mine = connection.getPlayerInfo(id);
            GameType mode = mine == null ? null : mine.getGameMode();
            GameProfile profile = mine == null ? null : mine.getProfile();
            return new Self(id,
                    profile == null ? null : profile.name(),
                    mine == null ? null : text(mine.getTabListDisplayName()),
                    text(client.player.getDisplayName()),
                    true,
                    mine != null,
                    mine != null && connection.getListedOnlinePlayers().contains(mine),
                    mode);
        }

        @Override
        public boolean staff() {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener connection =
                    client == null ? null : client.getConnection();
            if (connection == null || client.player == null) {
                return false;
            }
            PlayerInfo mine = connection.getPlayerInfo(client.player.getUUID());
            if (mine == null) {
                return false;
            }
            GameProfile profile = mine.getProfile();
            boolean carriesStar = starred(profile == null ? null : profile.name());
            if (!carriesStar) {
                carriesStar = starred(text(mine.getTabListDisplayName()));
            }
            if (!carriesStar) {
                carriesStar = starred(text(client.player.getDisplayName()));
            }
            return carriesStar;
        }

        private static String text(Component shown) {
            return shown == null ? null : shown.getString(TabName.MAX_FILTER_CHARS + 1);
        }

        private String tabText(UUID id, Component shown) {
            if (id == null) {
                return text(shown);
            }
            TabText carried = tabTexts.get(id);
            if (carried == null) {
                carried = new TabText();
                tabTexts.put(id, carried);
            }
            if (carried.shown != shown || !carried.plain) {
                carried.read(shown, text(shown), plainTree(shown));
            }
            return carried.text;
        }

        private boolean plainTree(Component shown) {
            if (shown == null) {
                return true;
            }
            ArrayList<Component> pending = treeScratch;
            pending.clear();
            pending.add(shown);
            int room = PLAIN_TREE_NODES - 1;
            boolean plain = true;
            while (plain && !pending.isEmpty()) {
                Component node = pending.remove(pending.size() - 1);
                List<Component> siblings = node.getSiblings();
                room -= siblings.size();
                plain = room >= 0 && node.getContents() instanceof PlainTextContents;
                if (plain) {
                    for (int at = 0; at < siblings.size(); at++) {
                        pending.add(siblings.get(at));
                    }
                }
            }
            pending.clear();
            return plain;
        }

        private static void nearby(Minecraft client, List<PlayerInfo> listed,
                                   Map<UUID, AbstractClientPlayer> found) {
            found.clear();
            ClientLevel level = client.level;
            if (level != null && !listed.isEmpty()) {
                List<AbstractClientPlayer> onLevel = level.players();
                for (int at = 0; at < onLevel.size(); at++) {
                    AbstractClientPlayer player = onLevel.get(at);
                    if (player != null) {
                        found.put(player.getUUID(), player);
                    }
                }
            }
        }
    }

    private static final class TabText {

        private Component shown;

        private String text;

        private boolean plain;

        private void read(Component from, String flattened, boolean selfContained) {
            shown = from;
            text = flattened;
            plain = selfContained;
        }
    }


    private void scribble() {
        Thread self = Thread.currentThread();
        List<ChunkSample> scratch = new ArrayList<>(ShareSpool.SEGMENT_SAMPLES);
        scribeIdlePasses = 0;
        try {
            boolean draining = true;
            while (draining && scribe == self) {
                boolean idle;
                try {
                    idle = spoolPass(scratch);
                } catch (Throwable unexpected) {
                    passFailed("spool", unexpected);
                    idle = true;
                }

                if (idle && !running) {
                    draining = false;
                } else if (idle) {
                    scribeIdlePasses++;
                    Thread.sleep(scribeIdleSleepMillis(scribeIdlePasses));
                } else {
                    scribeIdlePasses = 0;
                }
            }
        } catch (InterruptedException stopping) {
        } finally {
            scratch.clear();
            if (scribe == self) {
                sealEverything();
                scribe = null;
            }
        }
    }

    static long scribeIdleSleepMillis(int idlePasses) {
        int steps = Math.max(0, Math.min(idlePasses - 1, SCRIBE_IDLE_BACKOFF_STEPS));
        return SCRIBE_IDLE_MILLIS << steps;
    }

    private boolean spoolPass(List<ChunkSample> scratch) {
        adoptOnce();

        spoolHeld = SPOOL_HELD_UNTAKEN;
        passScratch = scratch;
        passIdle = true;
        worlds.forEach(writingOut);
        passScratch = null;
        saySomethingIfFull();
        return passIdle;
    }

    private void writeOut(Outbound out) {
        if (out.retired || !holdsGroundToWrite(out)) {
            return;
        }
        passIdle &= write(out, passScratch);
    }

    private boolean holdsGroundToWrite(Outbound out) {
        boolean holds = !out.queueIsEmpty();
        if (!holds) {
            ShareSpool spool = out.spool;
            holds = spool == null || !spool.isEmpty() || out.writeSchedule.isFailing();
        }
        return holds;
    }

    private boolean write(Outbound out, List<ChunkSample> scratch) {
        ShareSpool spool;
        boolean waiting;
        synchronized (worldsLock) {

            waiting = out.retired || (out.writeSchedule.isFailing()
                    && System.nanoTime() - out.writeAgainAt < 0L);
            spool = waiting ? null : out.spool;
        }
        if (!waiting && spool == null) {
            spool = openSpool(out);
        }
        boolean idle;
        if (spool == null) {
            idle = true;
        } else {
            scratch.clear();
            ArrayBlockingQueue<ChunkSample> handoff;
            long counted;
            boolean claimed;

            synchronized (spool) {

                claimed = !out.retired && !out.writingBatch;
                if (claimed) {
                    handoff = out.queue;
                    if (handoff != null) {
                        handoff.drainTo(scratch, ShareSpool.SEGMENT_SAMPLES);
                    }
                    counted = spool.records();
                    out.writingBatch = true;
                } else {
                    handoff = null;
                    counted = 0L;
                }
            }
            if (claimed) {
                try {
                    writeBatch(out, spool, scratch, handoff, counted);
                } finally {
                    synchronized (spool) {
                        out.writingBatch = false;
                    }
                }
                boolean did = !scratch.isEmpty();
                scratch.clear();
                idle = !did;
            } else {
                idle = true;
            }
        }
        return idle;
    }

    private void writeBatch(Outbound out, ShareSpool spool, List<ChunkSample> scratch,
                            ArrayBlockingQueue<ChunkSample> handoff, long counted) {
        int refusedHere = 0;
        int appended = 0;
        try {

            int samples = scratch.size();

            if (samples > 0 && spoolHeld == SPOOL_HELD_UNTAKEN) {
                spoolHeld = backlog.spoolBytesForPass();
            }
            for (int at = 0; at < samples; at++) {
                if (spoolHeld > SPOOL_CEILING) {
                    int remaining = samples - at;
                    dropped.addAndGet(remaining);
                    refusedAtCeiling += remaining;
                    refusedHere += remaining;
                    break;
                }
                spool.append(scratch.get(at));
                spoolHeld += ShareSpool.RECORD_BYTES;
                appended++;
            }

            if (appended > 0) {
                spool.flush();
            }
            spool.sealIfDue(out == current ? FLUSH_INTERVAL_MILLIS : 0);
            if (out.writeSchedule.isFailing()) {
                out.writeSchedule.succeeded();
            }
        } catch (IOException couldNotWrite) {
            long heldBeforeReopen = recordsOf(spool);
            boolean recounted = spool.reopen();
            long landed = recounted ? landedOf(spool, counted, heldBeforeReopen, appended)
                    : (long) appended - ShareSpool.UNDRAINED_RECORDS;
            int end = scratch.size() - refusedHere;
            int from = landed < 0L ? 0 : landed > end ? end : (int) landed;
            int kept = 0;
            for (int i = from; i < end; i++) {
                if (handoff != null) {
                    if (handoff.offer(scratch.get(i))) {
                        kept++;
                    }
                }
            }
            int lost = end - from - kept;
            dropped.addAndGet(lost);
            out.writeSchedule.failed();
            long waitMillis = out.writeSchedule.intervalMillis();
            out.writeAgainAt = System.nanoTime() + waitMillis * NANOS_PER_MILLI;
            LOGGER.warn("spool write failed for {} {}"
                    + " ({}): {} chunks retry in"
                    + " {} seconds; {} lost, spool full."
                    + " Map still holds this ground.",
                    out.server, out.dimension, couldNotWrite.toString(), kept,
                    waitMillis / MILLIS_PER_SECOND, lost);
        }
    }

    private static long recordsOf(ShareSpool spool) {
        synchronized (spool) {
            return spool.records();
        }
    }

    private static long landedOf(ShareSpool spool, long counted, long heldBeforeReopen,
                                 int appended) {
        long heldAfterReopen = recordsOf(spool);
        return Math.max(heldAfterReopen - counted, appended - (heldBeforeReopen - heldAfterReopen));
    }

    // Null until the account is known.
    private ShareSpool openSpool(Outbound out) {
        Path root = accountRoot(out.claim(accountOf(identity)));
        if (root == null) {
            return null;
        }
        ShareSpool made;
        try {
            made = ShareSpool.open(root, out.server, out.dimension);
            out.spool = made;
        } catch (IOException couldNotOpen) {
            made = null;
            out.openFailures++;
            out.writeSchedule.failed();
            long waitMillis = out.writeSchedule.intervalMillis();
            out.writeAgainAt = System.nanoTime() + waitMillis * NANOS_PER_MILLI;
            long now = System.nanoTime();
            long lastOpenSaid = out.openSaidAt;
            if (lastOpenSaid == 0 || now - lastOpenSaid >= COMPLAIN_EVERY_MS * NANOS_PER_MILLI) {
                long since = out.openFailures - out.openSaidAbout;
                out.openSaidAt = now;
                out.openSaidAbout = out.openFailures;
                LOGGER.warn("could not open an upload spool under {} ({})."
                        + " Failed {}"
                        + " times, {} in all;"
                        + " next try in {} seconds.", root,
                        couldNotOpen.toString(), since, out.openFailures,
                        waitMillis / MILLIS_PER_SECOND);
            }
        }
        return made;
    }

    // Null with no root or no account.
    private Path accountRoot(String account) {
        return backlog.accountRoot(account);
    }

    private void adoptOnce() {
        backlog.adoptOnce(identity, parsedEndpoint);
    }

    private void sealEverything() {
        for (Outbound out : worlds) {
            ShareSpool spool = out.spool;
            if (spool != null) {
                spool.close();
                spool.endForGood();
            }
        }
    }

    private void saySomethingIfFull() {
        long said = overrun + refusedAtCeiling;
        if (said == complainedAbout) {
            return;
        }
        long now = System.nanoTime();
        long lastComplainedAt = complainedAt;
        if (lastComplainedAt != 0 && now - lastComplainedAt < COMPLAIN_EVERY_MS * NANOS_PER_MILLI) {
            return;
        }
        complainedAt = now;
        long since = said - complainedAbout;
        complainedAbout = said;

        long ceilingSince = refusedAtCeiling - ceilingComplainedAbout;
        ceilingComplainedAbout = refusedAtCeiling;
        if (ceilingSince > 0) {
            LOGGER.warn("geosurvey refuses ground: backlog at its"
                    + " {} MiB ceiling. {}"
                    + " chunks refused since, {} total; run"
                    + " /geosurvey share.", MAX_SPOOL_BYTES >> ShareSpool.MIB_SHIFT, ceilingSince,
                    refusedAtCeiling);
        } else {
            LOGGER.warn("geosurvey refuses ground: disk too slow."
                    + " {} chunks refused since,"
                    + " {} total.", since, said);
        }
    }


    private void passFailed(String which, Throwable unexpected) {
        if (unexpected instanceof RuntimeException) {
            LOGGER.debug("geosurvey {} pass failed", which, unexpected);
            return;
        }
        long now = System.nanoTime();
        long said = hardFaultSaidAt;
        if (said != 0 && now - said < COMPLAIN_EVERY_MS * NANOS_PER_MILLI) {
            return;
        }
        hardFaultSaidAt = now;
        LOGGER.warn("geosurvey {} pass failed with {}."
                + " Retrying.", which,
                unexpected.toString(), unexpected);
    }

    private void run() {
        Thread self = Thread.currentThread();
        boolean leaving = false;
        try {
            while (!leaving && running && worker == self) {
                try {
                    ensureScribe();
                    if (pumping.compareAndSet(false, true)) {
                        try {
                            enrolWork.drainCompleted(1);
                            groundWork.drainCompleted(1);
                            uploadWork.drainCompleted(1);
                            clockWork.drainCompleted(1);
                            keepClockMeasured();
                            restartBackoffsOnMove();
                            shareLane.pump(Lane.TICK, PUMP_BUDGET_NANOS);

                            healthOfBeats();
                        } finally {
                            pumping.set(false);
                        }
                    }
                    Thread.sleep(IDLE_MILLIS);
                } catch (InterruptedException stopping) {
                    leaving = true;
                } catch (Throwable unexpected) {
                    passFailed("share", unexpected);
                    Thread.sleep(IDLE_MILLIS);
                }
            }
        } catch (InterruptedException stopping) {
        }
    }

    // Share thread only.
    private void keepClockMeasured() {
        if (!measuresClock || !utc.due(System.currentTimeMillis())) {
            return;
        }
        if (clockMeasuring.compareAndSet(false, true)) {
            boolean accepted = clockWork.submit(CollectorMod.MOD_ID, measureClockJob, measuredClockJob);
            if (!accepted) {
                clockMeasuring.set(false);
            }
        }
    }

    // Clock worker only.
    private UtcClock measureClock() {
        try {
            utc.measure();
        } catch (RuntimeException unexpected) {
            LOGGER.debug("geosurvey could not measure the time", unexpected);
        }
        return utc;
    }

    private void measuredClock(UtcClock measured) {
        clockMeasuring.set(false);
    }

    // Test only.
    void proving(Enroller.Session proof) {
        this.session = proof;
    }

    private final Supplier<EnrolResult> enrolDecisionJob = this::enrolDecision;

    private final Consumer<EnrolResult> applyEnrolJob = this::applyEnrol;

    private void queuedEnrolBeat() {
        if (!enrolWorking.compareAndSet(false, true)) {
            return;
        }
        enrolAskedUnder = backoffArmedOn.restarts();
        boolean accepted = enrolWork.submit(CollectorMod.MOD_ID,
                enrolDecisionJob, applyEnrolJob);
        if (!accepted) {
            enrolWorking.set(false);
        }
    }

    void enrolBeat() {
        applyEnrol(enrolDecision());
    }

    private record EnrolResult(Enroller.Outcome outcome, String at, URI endpoint,
                                Attestation.Credential credential, String whatGoes,
                                Throwable failure, long retryAfterSeconds, boolean held,
                                boolean deniedBySession) {
    }

    private static final EnrolResult ENROL_RESULT_HELD =
            new EnrolResult(null, null, null, null, null, null, 0L, true, false);

    private EnrolResult enrolDecision() {

        if (selfHidden) {
            return ENROL_RESULT_HELD;
        }
        if (!proveAccount) {
            return null;
        }
        URI endpoint = enrolEndpoint;
        if (endpoint == null) {
            return null;
        }
        Identity who = identity;
        if (who == null || !LocalKey.isMine(who.credential())) {
            return null;
        }
        EnrolResult result;
        try {
            String at = enrolKey(endpoint, who.credential());
            if (at.equals(enrolledAt) || at.equals(enrolmentShut)) {
                result = null;
            } else {
                result = enrolAt(at, endpoint, who);
            }
        } catch (Throwable anything) {
            result = new EnrolResult(null, null, endpoint, who.credential(), null, anything, 0L,
                    false, false);
        }
        return result;
    }

    private EnrolResult enrolAt(String at, URI endpoint, Identity who) {
        CollectorWire wire = collectorWire;
        if (wire == null) {
            wire = new CollectorWire(endpoint);
            collectorWire = wire;
        } else {
            wire.pointAt(endpoint);
        }
        Enroller.Outcome outcome;
        Throwable down;
        try {
            outcome = Enroller.once(wire, session,
                    who.credential(), who.signer()::sign);
            down = null;
        } catch (IOException | RuntimeException unreachable) {
            outcome = null;
            down = unreachable;
        }
        EnrolResult result;
        if (down == null) {
            result = new EnrolResult(outcome, at, endpoint, who.credential(), whatGoes(), null,
                    wire.retryAfterSeconds(), false, Enroller.deniedBySession(wire.offerReply()));
        } else {
            result = new EnrolResult(null, at, endpoint, who.credential(), null, down, 0L,
                    false, false);
        }
        return result;
    }

    private void applyEnrol(EnrolResult result) {
        try {
            settleEnrol(result);
        } finally {
            enrolWorking.set(false);
        }
    }

    private void settleEnrol(EnrolResult result) {
        if (result == null) {
            return;
        }
        if (result.held()) {
            enrolReason = ENROL_HELD;
            return;
        }
        boolean current = enrolAskedUnder == backoffArmedOn.restarts();
        if (result.failure() != null) {
            if (current) {
                enrolFailed(result.endpoint());
                enrolAgainIn(enrolSchedule.intervalMillis());
            }
            enrolReason = "the collector could not be reached ("
                    + result.failure().getClass().getSimpleName() + ")";
            LOGGER.debug("geosurvey could not prove its account to {}",
                    result.endpoint(), result.failure());
            return;
        }
        Enroller.Outcome outcome = result.outcome();
        switch (outcome) {
            case ENROLLED -> {
                enrolledAt = result.at();
                offerRefused();
                enrolSchedule.succeeded();
                enrolAgainIn(1);
                enrolReason = "";
                walk.keyEnrolled();
                LOGGER.info("geosurvey proved this account to {} through"
                        + " Mojang; it accepts key {}."
                        + " {}", result.endpoint(),
                        Attestation.fingerprint(result.credential()), result.whatGoes());
            }
            case UNROUTED -> {
                enrolmentShut = result.at();
                enrolReason = ENROL_UNROUTED;
                LOGGER.info("geosurvey asked {} to record its key: no such route."
                        + " Nothing else will be asked.",
                        result.endpoint());
            }
            case REFUSED -> {
                enrolmentShut = result.at();
                enrolReason = ENROL_REFUSED;
                if (current && result.deniedBySession()) {
                    enrolDeniedAt.set(parsedEndpoint);
                }

                LOGGER.warn("geosurvey could not prove this account to {}: refused."
                        + " No ground is sent there until the operator"
                        + " trusts {}"
                        + ".", result.endpoint(),
                        Attestation.fingerprint(result.credential()));
            }
            case RETRY -> {
                if (current) {
                    enrolFailed(result.endpoint());
                    long retryAfterSeconds = result.retryAfterSeconds();
                    enrolAgainIn(retryAfterSeconds > 0
                            ? Math.clamp(retryAfterSeconds, 1, MAX_QUIET_SECONDS) * MILLIS_PER_SECOND
                            : enrolSchedule.intervalMillis());
                }
                enrolReason = "waiting to ask again";
            }
        }
    }

    private void enrolFailed(URI endpoint) {
        enrolSchedule.failed();
        if (enrolSchedule.consecutiveFailures() == ENROL_FAILURES_LOGGED) {
            LOGGER.info("geosurvey could not prove this account to {};"
                    + " it keeps asking.", endpoint);
        }
    }

    // Share thread only.
    private void restartBackoffsOnMove() {
        Identity who = identity;
        if (backoffArmedOn.moved(parsedEndpoint, groundServer, worldRolls,
                who == null ? null : who.credential())) {
            enrolSchedule.succeeded();
            enrolAgainIn(1);
            groundSchedule.succeeded();
            groundAskAt = 0L;
            groundReaskAt = 0L;
        }
    }

    private String enrolKey(URI endpoint, Attestation.Credential credential) {
        if (endpoint != enrolKeyFrom || credential != enrolKeyFor) {
            enrolKeyFrom = endpoint;
            enrolKeyFor = credential;
            enrolKey = enrolKeyOf(endpoint, credential);
        }
        return enrolKey;
    }

    private static String enrolKeyOf(URI endpoint, Attestation.Credential credential) {
        return endpoint + " " + Attestation.fingerprint(credential);
    }

    private void enrolAgainIn(long millis) {
        if (enrolJob != null) {
            enrolJob.setPeriodMillis(Math.max(1L, millis));
        }
    }

    private String whatGoes() {
        String goes;
        if (publishPresence) {
            goes = "Ground, position and the player list go"
                    + " to whoever runs it.";
        } else if (withheldByBuild) {
            goes = "Ground goes to whoever runs it; this build"
                    + " sends no position"
                    + " or player list.";
        } else {
            goes = "Ground goes to whoever runs it; the position and the player"
                    + " list do not while the switch"
                    + " is off.";
        }
        return goes;
    }

    String enrolmentReason() {
        return enrolReason;
    }

    // Blocking; never call this from a beat.
    Directory.Found discover(List<String> seeds) {
        return discover(seeds, null);
    }

    Directory.Found discover(List<String> seeds, BooleanSupplier stop) {
        return Directory.from(seeds, address -> {
            URI at = endpointOf(address, "");
            return at == null ? null : transport.fetch(at, Directory.MAX_BODY);
        }, stop);
    }

    private final class CollectorWire implements Enroller.Wire {

        private URI endpoint;

        private final ByteArrayOutputStream replyText = new ByteArrayOutputStream();

        private long retryAfterSeconds;

        private CollectorWire(URI endpoint) {
            this.endpoint = endpoint;
        }

        private void pointAt(URI endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public String challenge() throws IOException {
            return transport.fetch(endpoint);
        }

        @Override
        public int offer(byte[] enrolment) throws IOException {
            replyText.reset();
            Reply reply = transport.postReading(endpoint, enrolment, replyText);
            retryAfterSeconds = reply.retryAfterSeconds();
            return reply.status();
        }

        @Override
        public String offerReply() {
            return replyText.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final class GameSession implements Enroller.Session {

        private static final int MAX_SESSION_CALLS = 2;

        private static final String CLIENT_THREAD_FAILURE = "geosurvey could not read the"
                + " player's session on the client thread."
                + " Account not proved";

        private static final String SESSION_SERVICE_FAILURE = "geosurvey's call to Mojang's"
                + " session service failed. Account not proved";

        private static final AtomicInteger outstanding = new AtomicInteger();

        @Override
        public String name() {
            return onClientThread(() -> {
                User user = user();
                return user == null ? null : user.getName();
            }, null);
        }

        @Override
        public boolean join(String serverId) {
            return inBackground(() -> joinNow(serverId), false);
        }

        private static boolean joinNow(String serverId) {
            User user = user();
            Minecraft client = Minecraft.getInstance();
            if (user == null || client == null) {
                return false;
            }
            boolean joined;
            try {
                client.services().sessionService().joinServer(
                        user.getProfileId(), user.getAccessToken(), serverId);
                joined = true;
            } catch (AuthenticationException | RuntimeException refused) {

                LOGGER.debug("geosurvey could not tell Mojang about a join",
                        refused);
                joined = false;
            }
            return joined;
        }

        private static <T> T onClientThread(Supplier<T> read, T orElse) {
            Minecraft client = Minecraft.getInstance();
            if (client == null) {
                return orElse;
            }
            T answer;
            if (client.isSameThread()) {
                answer = read.get();
            } else {
                try {
                    answer = client.submit(sessionGuarded(read, CLIENT_THREAD_FAILURE))
                            .get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                    answer = orElse;
                } catch (ExecutionException threw) {
                    answer = orElse;
                } catch (TimeoutException noAnswer) {
                    LOGGER.debug("geosurvey could not read the player's session on the"
                            + " client thread", noAnswer);
                    answer = orElse;
                }
            }
            return answer;
        }

        private static <T> T inBackground(Supplier<T> call, T orElse) {
            int already = outstanding.getAndIncrement();
            if (already >= MAX_SESSION_CALLS) {
                outstanding.decrementAndGet();
                LOGGER.debug("geosurvey will not ask Mojang while {} calls run", already);
                return orElse;
            }
            AtomicReference<Thread> worker = new AtomicReference<>();
            CompletableFuture<T> answer = CompletableFuture.supplyAsync(() -> {
                try {
                    return sessionGuarded(call, SESSION_SERVICE_FAILURE).get();
                } finally {
                    outstanding.decrementAndGet();
                }
            }, work -> {
                Thread made = Background.thread(work, "geosurvey-session");
                worker.set(made);
                StoppableWorkers.thread("geosurvey-session", made);
                made.start();
            });
            T got;
            try {
                got = answer.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                giveUp(answer, worker.get());
                got = orElse;
            } catch (ExecutionException threw) {
                giveUp(answer, worker.get());
                got = orElse;
            } catch (TimeoutException noAnswer) {
                giveUp(answer, worker.get());
                LOGGER.debug("geosurvey had no answer from Mojang's session service"
                        + " in time", noAnswer);
                got = orElse;
            }
            return got;
        }

        private static void giveUp(CompletableFuture<?> answer, Thread worker) {
            answer.cancel(true);
            if (worker != null) {
                worker.interrupt();
            }
        }

        private static <T> Supplier<T> sessionGuarded(Supplier<T> task, String failure) {
            return () -> {
                try {
                    return task.get();
                } catch (RuntimeException | Error threw) {
                    LOGGER.warn(failure, threw);
                    throw threw;
                }
            };
        }

        private static User user() {
            Minecraft client = Minecraft.getInstance();
            return client == null ? null : client.getUser();
        }
    }

    private void groundBeat() {

        if (selfHidden) {
            return;
        }
        String server = groundServer;
        if (server == null) {
            return;
        }
        URI collector = parsedEndpoint;
        if (collector == null) {
            return;
        }
        Identity who = identity;
        if (who == null) {

            return;
        }
        if (!walk.asked()) {
            long now = System.nanoTime();
            if (now >= groundAskAt) {
                askGround(collector, server);
            }
        } else if (walk.askedNothing()) {
            long now = System.nanoTime();
            if (now >= groundReaskAt && now >= groundAskAt) {
                askGround(collector, server);
            }
        } else if (walk.standingDue()) {
            long now = System.nanoTime();
            if (now >= groundAskAt) {
                askGround(collector, server);
            }
        }
        if (!groundAsking.get()) {

            walk.beat(transport, collector, currentEndpoint, who);
        }
    }

    private record GroundAskResult(boolean asked, Throwable failure) {
    }

    private static final GroundAskResult GROUND_ASK_RESULT_ASKED =
            new GroundAskResult(true, null);

    private static final GroundAskResult GROUND_ASK_RESULT_NOTHING =
            new GroundAskResult(false, null);

    private static GroundAskResult groundAskResult(boolean asked) {
        return asked ? GROUND_ASK_RESULT_ASKED : GROUND_ASK_RESULT_NOTHING;
    }

    private GroundAskResult computeGroundAsk(URI collector, String server) {
        GroundAskResult result;
        try {

            result = groundAskResult(
                    walk.ask(transport, collector, server, currentEndpoint, identity));
        } catch (Throwable down) {
            result = new GroundAskResult(false, down);
        }
        return result;
    }

    private void askGround(URI collector, String server) {
        if (!groundAsking.compareAndSet(false, true)) {
            return;
        }
        GroundAsk ask = heldGroundAsk();
        ask.collector = collector;
        ask.server = server;
        groundAskedUnder = backoffArmedOn.restarts();
        boolean accepted = groundWork.submit(CollectorMod.MOD_ID, ask.compute, ask.apply);
        if (!accepted) {
            spareGroundAsk.set(ask);
            groundAsking.set(false);
        }
    }

    private GroundAsk heldGroundAsk() {
        GroundAsk held = spareGroundAsk.getAndSet(null);
        if (held == null) {
            held = new GroundAsk();
        }
        return held;
    }

    private final class GroundAsk {

        private URI collector;

        private String server;

        private final Supplier<GroundAskResult> compute = this::asked;

        private final Consumer<GroundAskResult> apply = this::answered;

        private GroundAskResult asked() {
            return computeGroundAsk(collector, server);
        }

        private void answered(GroundAskResult result) {
            spareGroundAsk.set(this);
            applyGroundAsk(result);
        }
    }

    private void applyGroundAsk(GroundAskResult result) {
        try {
            if (result.failure() != null) {
                LOGGER.warn("geosurvey's ground handshake ask crashed."
                        + " It will be asked again", result.failure());
            }
            if (groundAskedUnder != backoffArmedOn.restarts()) {
                groundAskAt = 0L;
            } else if (result.asked() && result.failure() == null && !walk.standingDue()) {
                groundSchedule.succeeded();
                groundAskAt = 0L;
                groundReaskAt = System.nanoTime() + GROUND_REASK_MILLIS * NANOS_PER_MILLI;
            } else {
                groundSchedule.failed();
                groundAskAt = System.nanoTime()
                        + groundSchedule.intervalMillis() * NANOS_PER_MILLI;
            }
        } finally {
            groundAsking.set(false);
        }
    }

    // Tick thread only.
    public void walking(net.minecraft.world.level.Level level, String server,
                        String dimension, int x, int z) {

        if (selfHidden) {
            return;
        }
        walk.near(level, server, dimension, x, z);
    }

    // Tick thread only.
    public boolean groundReadingOpen() {
        return walk.readingOpen();
    }

    public void closeGroundReading() {
        walk.closeReading();
    }

    public dev.sandpaper.core.Step readingGround(net.minecraft.world.level.Level level,
                                                 dev.sandpaper.core.Tick tick) {
        return walk.readMore(level, tick);
    }

    // Tick thread only; null with nothing to say.
    public String groundNotice() {
        String line = enrolDeniedLine();
        return line != null ? line : walk.notice();
    }

    // Tick thread only; null unless a refusal of this computer's own key waits for the current collector.
    private String enrolDeniedLine() {
        if (enrolDeniedAt.get() == null) {
            return null;
        }
        URI denied = enrolDeniedAt.getAndSet(null);
        return denied != null && denied.equals(parsedEndpoint) ? GroundWalk.SAID + ENROL_DENIED : null;
    }

    public boolean groundWorthSaying() {
        return walk.worthSaying();
    }

    public String groundSaying() {
        return walk.saying();
    }

    public boolean groundProven() {
        return walk.proven();
    }

    ServerConfirmation.Ground groundFor(String nodeHost, String server) {
        URI endpoint = parsedEndpoint;
        String walking = groundServer;
        if (endpoint == null || nodeHost == null
                || !NodeAddress.withoutRootDot(nodeHost)
                        .equalsIgnoreCase(NodeAddress.withoutRootDot(endpoint.getHost()))
                || walking == null || !walking.equals(server)) {
            return ServerConfirmation.Ground.NOT_WALKING;
        }
        return walk.groundFor(nodeHost, server);
    }

    boolean groundAsked() {
        return walk.asked();
    }

    boolean groundAskedNothing() {
        return walk.askedNothing();
    }

    boolean groundAskOpen() {
        return groundAsking.get();
    }

    // In the operator's order.
    List<WorldPrint.Region> groundOwed() {
        return walk.owed();
    }

    private static final String GROUND_FIRST =
            "the ground handshake is not finished";

    private void beat() {

        if (!publishPresence) {

            chose(withheldByBuild ? PRESENCE_WITHHELD_BY_BUILD : PRESENCE_WITHHELD);
            return;
        }
        URI endpoint = presenceEndpoint;
        if (endpoint != null && endpoint != beatingAt) {

            beatingAt = endpoint;
            presenceSchedule.succeeded();
            beatAgainIn(1);

            lastPosted = null;
            lastPostedFor = null;
        }
        long now = System.currentTimeMillis();
        beatTo(endpoint, now);
    }

    private void beatTo(URI endpoint, long now) {
        if (endpoint == null) {
            stalled("no collector address");
            return;
        }
        Identity who = identity;
        if (who == null) {
            unidentifiedPresence();
            return;
        }
        keyMissing = false;
        if (!Attestation.current(who.credential(), now)) {
            stalled(LocalKey.isMine(who.credential())
                    ? "this client's own signing key has expired"
                    : "the Mojang profile key has expired");
            return;
        }
        Scan seen = heldScan();
        if (seen == UNSETTLED) {
            return;
        }
        if (seen == null) {
            stalled("the game has not reported a position");
            return;
        }

        if (seen.hidden || selfHidden) {
            stalled(VANISHED);
            return;
        }
        presenceAttempted++;
        beatVisible(endpoint, now, who, seen);
    }

    private void beatVisible(URI endpoint, long now, Identity who, Scan seen) {

        if (!walk.proven()) {
            stalled(GROUND_FIRST);
            return;
        }
        Here where = seen.where;

        long postedAt = System.nanoTime();
        if (postedAt - lastPostedAt < PRESENCE_HEARTBEAT_MS * NANOS_PER_MILLI
                && who.credential().player().equals(lastPostedFor)
                && samePlace(where, lastPosted)) {
            nextBeat(true);
            return;
        }
        byte[] message = signedPresence(who, where, now);
        if (message != null) {
            postPresence(endpoint, message, who, where, postedAt);
        }
    }

    private byte[] signedPresence(Identity who, Here where, long now) {
        byte[] message;
        try {
            describePresence(presenceParts, where, accountOf(who), now);
            presencePayload = Presence.encodeInto(presencePayload, presenceParts);
            presenceSigning.set(presencePayload);
            byte[] signature = who.signer().sign(presenceSigning);
            message = SignedBatch.encodeInto(presenceMessage, who.credential(),
                    signature, presencePayload);
            presenceMessage = null;
        } catch (IOException | RuntimeException notSendable) {
            message = null;
            presenceFailed++;

            stalled("the report could not be built (see the log)", notSendable);
            nextBeat(false);
        }
        return message;
    }

    private void postPresence(URI endpoint, byte[] message, Identity who, Here where,
                              long postedAt) {
        boolean landed;
        try {
            Reply reply = transport.post(endpoint, message);
            presenceMessage = message;
            int status = reply.status();
            presenceLastStatus = status;
            landed = status / HTTP_STATUS_CLASS == HTTP_SUCCESS_CLASS;
            if (landed) {
                presenceSent++;

                postedPlace.copyFrom(where);
                lastPosted = postedPlace;
                lastPostedAt = postedAt;
                lastPostedFor = who.credential().player();
                flowing();
                nextBeat(true);
            } else {
                presenceRefused++;
                stalled("the collector answered " + status);
            }
        } catch (IOException | RuntimeException notSendable) {
            landed = false;
            presenceFailed++;

            stalled("the collector could not be reached (see the log)", notSendable);
        }
        if (!landed) {
            nextBeat(false);
        }
    }

    private static boolean samePlace(Here now, Here last) {
        return last != null
                && now.x == last.x && now.z == last.z
                && now.storm == last.storm
                && now.thunder == last.thunder
                && now.server.equals(last.server)
                && now.dimension.equals(last.dimension)
                && Objects.equals(now.name, last.name);
    }

    private static void describePresence(Presence.Parts parts, Here where, String by,
                                         long sent) {
        parts.server = where.server;
        parts.dimension = where.dimension;
        parts.by = by;
        parts.name = where.name;
        parts.sent = sent;
        parts.x = where.x;
        parts.z = where.z;
        parts.ticks = where.ticks;
        parts.storm = where.storm;
        parts.thunder = where.thunder;
    }

    private void nextBeat(boolean landed) {
        if (landed) {
            presenceSchedule.succeeded();
        } else {
            presenceSchedule.failed();
        }
        beatAgainIn(presenceSchedule.intervalMillis());
    }

    private void beatAgainIn(long millis) {
        if (presenceJob != null) {
            presenceJob.setPeriodMillis(Math.max(1L, millis));
        }
    }

    private void rosterBeat() {

        if (!publishPresence) {

            rosterChose(withheldByBuild ? ROSTER_WITHHELD_BY_BUILD : ROSTER_WITHHELD);
            return;
        }
        long now = System.currentTimeMillis();
        URI endpoint = rosterEndpoint;
        if (endpoint != null && endpoint != rosterBeatingAt) {

            rosterBeatingAt = endpoint;
            rosterSchedule.succeeded();
            rosterDueAt = 0L;

            rosterPostedCount = -1;
            rosterPostedKey = 0L;
        }

        Scan seen = heldScan();
        if (seen != UNSETTLED) {
            rosterBeatSeen(now, endpoint, seen);
        }
    }

    private void rosterBeatSeen(long now, URI endpoint, Scan seen) {
        List<RosterReport.Entry> scanned = rosterOf(seen);
        Identity me = identity;

        if (selfHidden || (seen != null && seen.hidden)) {
            rosterStalled(ROSTER_HELD);
        } else {
            rosterAttempted++;
            rosterBeatShown(now, endpoint, seen, scanned, me);
        }
    }

    private void rosterBeatShown(long now, URI endpoint, Scan seen,
                                 List<RosterReport.Entry> scanned, Identity me) {
        List<RosterReport.Entry> who = scanned;
        if (!walk.proven()) {
            rosterStalled(GROUND_FIRST);
            return;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos < rosterDueAt && !urgent(nowNanos, who)) {
            rosterAgainIn(Math.min((rosterDueAt - nowNanos) / NANOS_PER_MILLI,
                    ROSTER_URGENT_FLOOR_MS));
            return;
        }
        if (endpoint == null) {
            rosterStalled("no collector address");
            return;
        }
        if (me == null || !Attestation.current(me.credential(), now)) {
            rosterStalled("nothing to sign with");
            return;
        }
        if (seen == null || who == null) {

            if (!publishPresence) {

                rosterChose(withheldByBuild ? ROSTER_WITHHELD_BY_BUILD : ROSTER_WITHHELD);
                return;
            }
            rosterStalled("the game has not reported a player list");
            return;
        }
        Here where = seen.where;
        if (who.isEmpty()) {

            rosterPlayers = 0;
            nextRosterBeat(true);
        } else {
            postRosterMessage(endpoint, me, where, who, scanned, now);
        }
    }

    private void postRosterMessage(URI endpoint, Identity me, Here where,
                                   List<RosterReport.Entry> who, List<RosterReport.Entry> scanned,
                                   long now) {
        byte[] message;
        try {
            String by = accountOf(me);
            who = withinRosterByteCap(where.server, where.dimension, by, now, who);
            byte[] payload = rosterRoom();
            int payloadLength = RosterReport.encodeTo(payload, where.server,
                    where.dimension, by, now, who);
            rosterSigning.set(payload, payloadLength);
            byte[] signature = me.signer().sign(rosterSigning);
            message = envelopeRoom(rosterMessage, me.credential(), signature, payloadLength,
                    RosterReport.MAX_BYTES);
            rosterMessageLength = SignedBatch.encodeTo(message, me.credential(),
                    signature, payload, payloadLength);
            rosterMessage = null;
        } catch (IOException | RuntimeException notSendable) {
            message = null;
            rosterFailed++;
            rosterStalled("the roster could not be built ("
                    + notSendable.getClass().getSimpleName() + ")");
            nextRosterBeat(false);
        }
        if (message != null) {
            postSignedRoster(endpoint, message, who, scanned);
        }
    }

    private void postSignedRoster(URI endpoint, byte[] message, List<RosterReport.Entry> who,
                                  List<RosterReport.Entry> scanned) {
        boolean landed;
        try {
            Reply reply = transport.post(endpoint, message, rosterMessageLength);
            rosterMessage = message;
            int status = reply.status();
            rosterLastStatus = status;
            landed = status / HTTP_STATUS_CLASS == HTTP_SUCCESS_CLASS;
            if (landed) {
                rosterSent++;
                rosterPlayers = who.size();

                rosterPostedKey = membership(scanned);
                rosterPostedCount = scanned.size();
                rosterFlowing();
                nextRosterBeat(true);
            } else {
                rosterRefused++;

                rosterStalled(status == HTTP_NOT_FOUND
                        ? "this collector has no /roster route;"
                                + " your position is still"
                                + " reported"
                        : "the collector answered " + status);
            }
        } catch (IOException | RuntimeException notSendable) {
            landed = false;
            rosterFailed++;
            rosterStalled("the collector could not be reached ("
                    + notSendable.getClass().getSimpleName() + ")");
        }
        if (!landed) {
            nextRosterBeat(false);
        }
    }

    private byte[] rosterRoom() {
        if (rosterPayload.length < RosterReport.MAX_BYTES) {
            rosterPayload = new byte[RosterReport.MAX_BYTES];
        }
        return rosterPayload;
    }

    private static byte[] envelopeRoom(byte[] held, Attestation.Credential credential,
                                       byte[] signature, int payloadLength, int payloadRoom) {
        int needed = SignedBatch.encodedLength(credential, signature, payloadLength);
        return held != null && held.length >= needed ? held
                : new byte[SignedBatch.encodedLength(credential, signature, payloadRoom)];
    }

    private void nextRosterBeat(boolean landed) {
        if (landed) {
            rosterSchedule.succeeded();
        } else {
            rosterSchedule.failed();
        }
        long nowNanos = System.nanoTime();
        rosterDueAt = nowNanos
                + Math.max(ROSTER_EVERY_MS, rosterSchedule.intervalMillis()) * NANOS_PER_MILLI;
        rosterUrgentDueAt = nowNanos + ROSTER_URGENT_FLOOR_MS * NANOS_PER_MILLI;
    }

    private void rosterAgainIn(long millis) {
        if (rosterJob != null) {
            rosterJob.setPeriodMillis(Math.max(1L, millis));
        }
    }

    private void wakeOnNextPump(Handle job) {
        if (job != null) {
            job.expedite();
        }
    }

    private boolean urgent(long nowNanos, List<RosterReport.Entry> who) {
        if (who == null || who.isEmpty() || nowNanos < rosterUrgentDueAt
                || rosterSchedule.isFailing()) {
            return false;
        }
        return who.size() != rosterPostedCount || membership(who) != rosterPostedKey;
    }

    private long membership(List<RosterReport.Entry> who) {
        if (who != keyedRoster) {
            keyedRoster = who;
            keyedMembership = membershipOf(who);
        }
        return keyedMembership;
    }

    private List<RosterReport.Entry> keyedRoster;

    private long keyedMembership;

    private Scan heldScan() {
        Scan seen = scan;
        holdScan(seen);
        Scan again = scan;
        int settling = 1;
        while (again != seen && settling < ROSTER_HOLD_TRIES) {
            seen = again;
            holdScan(seen);
            again = scan;
            settling++;
        }
        return again == seen ? seen : UNSETTLED;
    }

    private void holdScan(Scan seen) {
        if (seen != scanInHand) {
            keyedRoster = null;
            scanInHand = seen;
        }
    }

    private static List<RosterReport.Entry> rosterOf(Scan seen) {
        return seen == null ? null : seen.roster;
    }

    private static long membershipOf(List<RosterReport.Entry> who) {
        long key = 0;
        int size = who.size();
        for (int i = 0; i < size; i++) {
            RosterReport.Entry entry = who.get(i);
            UUID id = entry.player();
            key += mixed(id.getMostSignificantBits())
                    ^ mixed(id.getLeastSignificantBits());
        }
        return key;
    }

    // SplitMix64 finalizer.
    private static long mixed(long bits) {
        long value = bits;
        value ^= value >>> MIX_SHIFT_FIRST;
        value *= MIX_MULTIPLIER_FIRST;
        value ^= value >>> MIX_SHIFT_SECOND;
        value *= MIX_MULTIPLIER_SECOND;
        value ^= value >>> MIX_SHIFT_LAST;
        return value;
    }

    private void rosterStalled(String reason) {
        rosterStalled(reason, true);
    }

    private void rosterChose(String reason) {
        rosterStalled(reason, false);
    }

    private void rosterStalled(String reason, boolean fault) {
        rosterReason = reason;
        if (!reason.equals(rosterAnnounced)) {
            rosterAnnounced = reason;
            String said = "geosurvey does not report the player list: {}. Run"
                    + " /geosurvey share to see the state.";
            if (fault) {
                LOGGER.warn(said, reason);
            } else {
                LOGGER.info(said, reason);
            }
        }
    }

    private void rosterFlowing() {
        rosterReason = "";
        if (rosterAnnounced != null) {
            rosterAnnounced = null;
            LOGGER.info("geosurvey reports the player list again.");
        }
    }

    private static String unidentified() {
        String noLocalKey = LocalKey.unusable();
        if (!noLocalKey.isEmpty()) {
            return noLocalKey;
        }
        return "nothing to sign with: no server joined,"
                + " or its key is not made";
    }

    private void stalled(String reason) {
        stalled(reason, null, true);
    }

    private void stalled(String reason, Throwable cause) {
        stalled(reason, cause, true);
    }

    private void chose(String reason) {
        stalled(reason, null, false);
    }

    private void unidentifiedPresence() {
        String reason = unidentified();
        long now = System.nanoTime();
        if (!keyMissing) {
            keyMissing = true;
            keyMissingSince = now;
        }
        if (LocalKey.unusable().isEmpty() && now - keyMissingSince < KEY_SETTLE_NANOS) {
            holdPresence(reason);
        } else {
            stalled(reason);
        }
    }

    private void stalled(String reason, Throwable cause, boolean fault) {
        holdPresence(reason);
        if (!reason.equals(presenceAnnounced)) {
            presenceAnnounced = reason;
            String said = "geosurvey does not report"
                    + " your position: {}. Run /geosurvey share to see the"
                    + " state.";
            if (cause != null) {
                LOGGER.warn(said, reason, cause);
            } else if (fault) {
                LOGGER.warn(said, reason);
            } else {
                LOGGER.info(said, reason);
            }
        }
    }

    private void holdPresence(String reason) {
        beatAgainIn(1);

        lastPosted = null;
        lastPostedFor = null;
        presenceReason = reason;
    }

    private void flowing() {
        presenceReason = "";
        if (presenceAnnounced != null) {
            presenceAnnounced = null;
            LOGGER.info("geosurvey reports your position again.");
        }
    }

    private void pass() {

        if (nextTokenAt != 0L && System.nanoTime() - nextTokenAt < 0L) {
            return;
        }

        Identity who = identity;
        if (who == null) {
            return;
        }

        if (uploadWorking.get()) {
            return;
        }

        if (worlds.isEmpty()) {
            return;
        }

        String account = accountOf(who);
        Outbound out = nextToPost(account);
        if (out == null) {
            return;
        }
        if (!Attestation.current(who.credential(), utc.nowMillis())) {
            return;
        }
        if (walk.asked() && mayOweGround(out.label)) {
            return;
        }

        URI endpoint = out.endpoint;
        ShareSpool spool = out.spool;
        if (endpoint == null || spool == null) {
            return;
        }

        if (!rate.tryConsume()) {
            nextTokenAt = System.nanoTime() + rate.nanosUntilToken();
            return;
        }

        ShareSpool.Loaded loaded;
        boolean read;
        try {
            loaded = spool.take();
            read = true;
        } catch (IOException unreadable) {

            LOGGER.warn("geosurvey could not read a batch from the upload"
                    + " spool for {} {} ({}). It is still there.", out.server,
                    out.dimension, unreadable.toString());
            loaded = null;
            read = false;
        }
        if (read) {
            if (loaded == null) {
                handedNothing(out);
            } else {
                queueUpload(endpoint, out, who, account, spool, loaded);
            }
        }

    }

    private void queueUpload(URI endpoint, Outbound out, Identity who, String account,
                             ShareSpool spool, ShareSpool.Loaded loaded) {
        if (!out.by(account)) {
            settleUpload(spool, out, loaded, Posted.RETRY);
            return;
        }
        boolean built;
        try {
            Batch.encodeTo(batchRoom, out.label, out.dimension, out.account(),
                    stampFor(loaded), loaded.samples());
            built = true;
        } catch (IOException | RuntimeException notSendable) {
            built = false;
            settleUpload(spool, out, loaded, unbuildable(notSendable));
        }
        if (built) {
            byte[] signature;
            boolean signedIt;
            uploadSigning.set(batchRoom.bytes(), batchRoom.length());
            try {
                signature = who.signer().sign(uploadSigning);
                signedIt = true;
            } catch (RuntimeException notSigned) {
                signature = null;
                signedIt = false;
                failed++;
                signerFailures++;
                noteGroundTrouble("signing failed (see the log): "
                        + signerFailures
                        + " in a row; nothing"
                        + " set"
                        + " aside", notSigned);
                backOff();
                settleUpload(spool, out, loaded, Posted.RETRY);
            }
            if (signedIt) {
                signerFailures = 0;
                byte[] message;
                int messageLength;
                try {
                    message = envelopeRoom(uploadMessageSpare.getAndSet(null), who.credential(),
                            signature, batchRoom.length(), batchRoom.bytes().length);
                    messageLength = SignedBatch.encodeTo(message, who.credential(), signature,
                            batchRoom.bytes(), batchRoom.length());
                } catch (RuntimeException notSendable) {
                    message = null;
                    messageLength = 0;
                    settleUpload(spool, out, loaded, unbuildable(notSendable));
                }
                if (message != null) {
                    submitUpload(endpoint, out, spool, loaded, message, messageLength);
                }
            }
        }
    }

    private void submitUpload(URI endpoint, Outbound out, ShareSpool spool,
                              ShareSpool.Loaded loaded, byte[] message, int messageLength) {
        if (!uploadWorking.compareAndSet(false, true)) {
            settleUpload(spool, out, loaded, Posted.RETRY);
            return;
        }
        Upload job = heldUpload();
        job.endpoint = endpoint;
        job.message = message;
        job.messageLength = messageLength;
        job.spool = spool;
        job.out = out;
        job.loaded = loaded;
        boolean accepted = uploadWork.submit(CollectorMod.MOD_ID,
                job.post, job.apply, null,
                job.drop, job.discard);
        if (!accepted) {
            job.release();
            uploadWorking.set(false);
            settleUpload(spool, out, loaded, Posted.RETRY);
        }
    }

    private static final class UploadResult {

        private Reply reply;

        private Throwable failure;
    }

    private final UploadResult uploadOutcome = new UploadResult();

    private Upload heldUpload() {
        Upload held = spareUpload.getAndSet(null);
        if (held == null) {
            held = new Upload();
        }
        return held;
    }

    private final class Upload {

        private URI endpoint;

        private byte[] message;

        private int messageLength;

        private ShareSpool spool;

        private Outbound out;

        private ShareSpool.Loaded loaded;

        private final Supplier<UploadResult> post = this::posted;

        private final Consumer<UploadResult> apply = this::applied;

        private final Runnable drop = this::dropped;

        private final Consumer<UploadResult> discard = this::discarded;

        private UploadResult posted() {
            return postUpload(endpoint, message, messageLength);
        }

        private void applied(UploadResult result) {
            try {
                applyUpload(result, spool, out, loaded);
            } finally {
                release();
            }
        }

        private void dropped() {
            try {
                settleUpload(spool, out, loaded, Posted.RETRY);
            } finally {
                release();
            }
        }

        private void discarded(UploadResult ignored) {
            dropped();
        }

        private void release() {
            endpoint = null;
            message = null;
            messageLength = 0;
            spool = null;
            out = null;
            loaded = null;
            spareUpload.set(this);
        }
    }

    private UploadResult postUpload(URI endpoint, byte[] message, int messageLength) {
        try {
            Reply reply = transport.post(endpoint, message, messageLength);
            uploadMessageSpare.set(message);
            uploadOutcome.reply = reply;
            uploadOutcome.failure = null;
        } catch (Throwable down) {
            uploadOutcome.reply = null;
            uploadOutcome.failure = down;
        }
        return uploadOutcome;
    }

    private void applyUpload(UploadResult result, ShareSpool spool, Outbound out,
                             ShareSpool.Loaded loaded) {
        try {
            Throwable failure = result.failure;
            if (failure != null) {

                failed++;
                uploadUnreached = true;
                noteGroundTrouble("the collector could not be reached (see the log)", failure);
                backOff();
                settleUpload(spool, out, loaded, Posted.RETRY);
            } else {
                settleAnswer(result.reply, spool, out, loaded);
            }
        } finally {
            uploadWorking.set(false);
        }
    }

    private void settleAnswer(Reply reply, ShareSpool spool, Outbound out,
                              ShareSpool.Loaded loaded) {
        int status = reply.status();
        lastStatus = status;
        uploadUnreached = false;
        if (status == HTTP_REQUEST_TIMEOUT) {
            backOff();
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else if (status == Enroller.HTTP_TOO_MANY_REQUESTS) {
            refused++;
            uploadAgainIn(Math.clamp(reply.retryAfterSeconds(), 1, MAX_QUIET_SECONDS)
                    * MILLIS_PER_SECOND);
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else if (status / HTTP_STATUS_CLASS == HTTP_SUCCESS_CLASS) {
            sent++;
            groundReason = "";
            lastRefusal = "";
            schedule.succeeded();
            uploadAgainIn(1);
            settleUpload(spool, out, loaded, Posted.TAKEN);
        } else if (status == HTTP_UNAUTHORIZED && awaitingEnrolment()) {
            String line = refusedLine(status);
            lastRefusal = line;
            walk.sayAt(out.endpoint, line);
            backOff();
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else {
            refused++;
            backOff();
            settleRefusal(status, spool, out, loaded);
        }
    }

    private void settleRefusal(int status, ShareSpool spool, Outbound out,
                               ShareSpool.Loaded loaded) {
        if (status / HTTP_STATUS_CLASS == HTTP_REDIRECT_CLASS) {
            noteGroundTrouble("the collector answered " + status
                    + ", a redirect; set"
                    + " the address"
                    + " it serves");
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else if (status == HTTP_FORBIDDEN && refusalMayOweGround(out.label)) {
            noteGroundTrouble("the collector answered 403 for " + out.label
                    + ": ground not proven;"
                    + " see"
                    + " /geosurvey share");
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else if (status == HTTP_FORBIDDEN && out.label != null
                && out.label.equals(groundServer)
                && walk.askedNothing() && walk.mayReask()) {
            walk.reask();
            noteGroundTrouble("the collector answered 403 for " + out.label
                    + ": it asked for"
                    + " no ground;"
                    + " asking"
                    + " again");
            settleUpload(spool, out, loaded, Posted.RETRY);
        } else if (status / HTTP_STATUS_CLASS == HTTP_CLIENT_ERROR_CLASS) {
            String line = refusedLine(status);
            lastRefusal = line;
            walk.sayAt(out.endpoint, line);
            settleUpload(spool, out, loaded, Posted.REFUSED);
        } else {
            settleUpload(spool, out, loaded, Posted.RETRY);
        }
    }

    // Share thread only.
    private String refusedLine(int status) {
        Identity who = identity;
        String line;
        if (status != HTTP_UNAUTHORIZED) {
            line = REFUSED;
        } else if (who != null && LocalKey.isMine(who.credential())
                && !enrolledAt.equals(enrolKeyOf(enrolEndpoint, who.credential()))) {
            line = REFUSED_KEY;
        } else if (!utc.grounded()) {
            line = REFUSED_CLOCK;
        } else {
            line = REFUSED;
        }
        return line;
    }

    private boolean awaitingEnrolment() {
        Identity who = identity;
        return who != null && LocalKey.isMine(who.credential())
                && !enrolledAt.equals(enrolKeyOf(enrolEndpoint, who.credential()));
    }

    private void offerRefused() {
        for (Outbound out : worlds) {
            ShareSpool spool = out.spool;
            if (spool != null) {
                spool.offerRefused();
            }
        }
    }

    private void settleUpload(ShareSpool spool, Outbound out, ShareSpool.Loaded loaded,
                              Posted verdict) {

        switch (verdict) {
            case TAKEN -> {
                retryStamps.remove(loaded.file());
                spool.done(loaded);
                landed(out);
            }
            case REFUSED -> {
                retryStamps.remove(loaded.file());
                spool.failed(loaded, true);
                notLanded(out);
            }
            case RETRY -> {
                spool.failed(loaded, false);
                notLanded(out);
            }
        }
    }

    long stampFor(ShareSpool.Loaded loaded) {
        long now = utc.nowMillis();
        Stamp held = retryStamps.get(loaded.file());
        long age = held == null ? -1L : now - held.sent();
        if (held != null && held.era() == loaded.era()
                && held.fileBytes() == loaded.fileBytes()
                && age >= 0L && age < STAMP_HOLD_MILLIS) {
            return held.sent();
        }
        retryStamps.put(loaded.file(), loaded.era(), loaded.fileBytes(), now);
        return now;
    }

    private String toPostAccount;

    private Outbound toPostLive;

    private boolean toPostAnyFailing;

    private long toPostNow;

    private Outbound toPostLeft;

    private Outbound toPostMine;

    private Outbound toPostFailing;

    private List<Outbound> toPostSpent;

    private Outbound toPostAnswer;

    private final Consumer<Outbound> toPostWalk = this::nextToPostStep;

    private Outbound nextToPost(String account) {
        toPostAccount = account;
        toPostLive = current;
        toPostAnyFailing = failingWorlds > 0;
        toPostNow = toPostAnyFailing ? System.nanoTime() : 0L;
        toPostLeft = null;
        toPostMine = null;
        toPostFailing = null;
        toPostSpent = null;
        toPostAnswer = null;
        worlds.forEach(toPostWalk);
        dropSpent(toPostSpent);
        Outbound chosen;
        if (toPostAnswer != null) {
            chosen = toPostAnswer;
        } else {
            Outbound healthy = lastLanded
                    ? (toPostLeft != null ? toPostLeft : toPostMine)
                    : (toPostMine != null ? toPostMine : toPostLeft);
            if (healthy == null || (lastLanded && toPostFailing != null
                    && toPostFailing.triedAt < healthy.triedAt)) {
                chosen = toPostFailing;
            } else {
                chosen = healthy;
            }
        }
        return chosen;
    }

    private void nextToPostStep(Outbound out) {
        if (toPostAnswer != null) {
            return;
        }
        ShareSpool spool = out.spool;
        if (out.retired || spool == null || !out.by(toPostAccount)) {
            return;
        }
        if (!spool.hasSealed()) {
            if (out != toPostLive && retireIfSpent(out)) {
                if (toPostSpent == null) {
                    toPostSpent = new ArrayList<>(SPENT_WORLDS_CAPACITY);
                }
                toPostSpent.add(out);
            }
            return;
        }
        if (out.postSchedule.isFailing()) {
            if ((!out.resting || toPostNow - out.restUntil >= 0L)
                    && (toPostFailing == null || out.triedAt < toPostFailing.triedAt)) {
                toPostFailing = out;
            }
            return;
        }
        if (out == toPostLive) {
            toPostMine = out;
        } else if (toPostLeft == null) {
            if (!toPostAnyFailing && lastLanded) {
                toPostAnswer = out;
            } else {
                toPostLeft = out;
            }
        }
    }

    private boolean retireIfSpent(Outbound out) {
        ShareSpool spool = out.spool;
        if (spool == null) {
            return false;
        }
        boolean retired;
        synchronized (spool) {
            synchronized (worldsLock) {
                boolean spent = !out.retired && !out.writingBatch && out.queueIsEmpty()
                        && spool.isEmpty();

                retired = spent && out != current;
                if (retired) {
                    out.retired = true;
                    if (out.postSchedule.isFailing()) {
                        failingWorlds--;
                    }
                }
            }
        }
        if (retired) {
            spool.close();
            if (!heldElsewhere(out)) {
                spool.removeIfEmpty();
            }
            spool.endForGood();
            out.spool = null;
        }
        return retired;
    }

    private void dropSpent(List<Outbound> spent) {
        if (spent == null) {
            return;
        }
        synchronized (worldsLock) {
            worlds.removeAll(spent);
        }
    }

    private boolean heldElsewhere(Outbound out) {
        elsewhereOf = out;
        elsewhereHeld = false;
        worlds.forEach(matchingElsewhere);
        elsewhereOf = null;
        return elsewhereHeld;
    }

    private void matchElsewhere(Outbound other) {
        Outbound out = elsewhereOf;
        if (elsewhereHeld || out == null) {
            return;
        }
        elsewhereHeld = !other.retired && other.server.equals(out.server)
                && other.dimension.equals(out.dimension);
    }

    private void landed(Outbound out) {
        out.triedAt = ++attempts;
        landings++;
        lastLanded = true;
        if (out.postSchedule.isFailing()) {
            failingWorlds--;
        }
        out.failedAt = -1L;
        out.resting = false;
        out.postSchedule.succeeded();
    }

    private void notLanded(Outbound out) {
        out.triedAt = ++attempts;
        lastLanded = false;
        if (!out.postSchedule.isFailing()) {
            failingWorlds++;
        }
        out.resting = out.failedAt >= 0L && out.failedAt != landings;
        out.failedAt = landings;
        out.postSchedule.failed();
        out.restUntil = System.nanoTime() + out.postSchedule.intervalMillis() * NANOS_PER_MILLI;
    }

    private void handedNothing(Outbound out) {
        out.triedAt = ++attempts;
        lastLanded = false;
        if (!out.postSchedule.isFailing()) {
            failingWorlds++;
        }
        out.resting = true;
        out.postSchedule.failed();
        out.restUntil = System.nanoTime() + out.postSchedule.intervalMillis() * NANOS_PER_MILLI;
    }

    private Posted unbuildable(Throwable notSendable) {
        failed++;
        LOGGER.debug("geosurvey share could not build a batch", notSendable);
        return Posted.REFUSED;
    }

    private boolean mayOweGround(String server) {
        return server != null && server.equals(groundServer) && !walk.proven();
    }

    private boolean refusalMayOweGround(String server) {
        if (server == null) {
            return false;
        }
        if (!server.equals(groundServer)) {
            return true;
        }
        return mayOweGround(server);
    }

    private void noteGroundTrouble(String why) {
        noteGroundTrouble(why, null);
    }

    private void noteGroundTrouble(String why, Throwable cause) {
        groundReason = why;
        long now = System.nanoTime();
        long lastComplaint = groundComplainedAt;
        if (lastComplaint == 0 || now - lastComplaint >= COMPLAIN_EVERY_MS * NANOS_PER_MILLI) {
            groundComplainedAt = now;
            String said = "geosurvey does not contribute ground:"
                    + " {}. It keeps trying and loses nothing; run"
                    + " /geosurvey share for the backlog.";
            if (cause == null) {
                LOGGER.warn(said, why);
            } else {
                LOGGER.warn(said, why, cause);
            }
        }
    }

    // "" when nothing is wrong.
    String groundReason() {
        return groundReason;
    }

    private void backOff() {
        schedule.failed();
        uploadAgainIn(schedule.intervalMillis());
    }

    private void uploadAgainIn(long millis) {
        if (uploadJob != null) {
            uploadJob.setPeriodMillis(Math.max(1L, millis));
        }
    }


    boolean isSending() {
        Thread running = worker;
        if (running != null && running.isAlive()) {
            return true;
        }
        Thread stopping = outgoing;
        return stopping != null && stopping.isAlive();
    }

    long offered() {
        return offered;
    }

    long dropped() {
        return dropped.get();
    }

    long heldWhileHidden() {
        return heldWhileHidden;
    }

    long spooled() {
        return backlog.spooled();
    }

    long spoolBytes() {
        return backlog.spoolBytes();
    }

    long spoolRefused() {
        return backlog.spoolRefused();
    }

    long sent() {
        return sent;
    }

    long refused() {
        return refused;
    }

    long failed() {
        return failed;
    }

    static ShareSender live() {
        return live;
    }

    static void sayGroundNotice(String line) {
        ShareSender sender = live;
        if (sender != null) {
            sender.walk.say(line);
        }
    }

    boolean signing() {
        Identity who = identity;
        return who != null && Attestation.current(who.credential(), utc.nowMillis());
    }

    boolean addressed() {
        return presenceEndpoint != null;
    }

    boolean publishing() {
        return publishPresence;
    }

    int lastStatus() {
        return lastStatus;
    }

    // "" unless a refusal followed the last accepted upload.
    String lastRefusal() {
        return lastRefusal;
    }

    boolean uploadUnreached() {
        return uploadUnreached;
    }

    long presenceSent() {
        return presenceSent;
    }

    long presenceRefused() {
        return presenceRefused;
    }

    long presenceFailed() {
        return presenceFailed;
    }

    int presenceLastStatus() {
        return presenceLastStatus;
    }

    String presenceReason() {
        return presenceReason;
    }

    long rosterSent() {
        return rosterSent;
    }

    long rosterRefused() {
        return rosterRefused;
    }

    long rosterFailed() {
        return rosterFailed;
    }

    int rosterLastStatus() {
        return rosterLastStatus;
    }

    int rosterPlayers() {
        return rosterPlayers;
    }

    String rosterReason() {
        return rosterReason;
    }

    boolean localIdentity() {
        Identity who = identity;
        return who != null && LocalKey.isMine(who.credential());
    }

    // Allocates.
    boolean enrolledHere() {
        Identity who = identity;
        URI at = enrolEndpoint;
        return who != null && at != null && enrolledAt.equals(enrolKeyOf(at, who.credential()));
    }

    String identityFingerprint() {
        Identity who = identity;
        return who == null ? ""
                : LocalKey.fingerprintOf(who.credential().publicKey());
    }

    VanishProbe probe() {
        return probe;
    }

    // Test only; sync() overwrites it.
    void probing(VanishProbe armed) {
        this.probe = armed;
    }

    static Transport httpTransport() {
        return new HttpTransport();
    }

    // The outbox's poster, over a transport of its own; closing it closes the transport.
    static ShareOutbox.Poster outboxPoster() {
        return new OutboxPoster(new HttpTransport());
    }

    private static final class OutboxPoster implements ShareOutbox.Poster, AutoCloseable {

        private final HttpTransport transport;

        private OutboxPoster(HttpTransport transport) {
            this.transport = transport;
        }

        @Override
        public Reply post(URI endpoint, byte[] body, String server) throws IOException {
            return transport.postNamed(endpoint, body, server);
        }

        @Override
        public void close() {
            transport.close();
        }
    }

    private static final class HttpTransport implements Transport, AutoCloseable {

        private static final int STATUS_CODES = 1_000;

        private static final int SPARE_READINGS = 8;

        private static final int HELD_REQUESTS = 8;

        private static final String DATE = "Date";

        private static final AtomicReferenceArray<Reply> REPLIES =
                new AtomicReferenceArray<>(STATUS_CODES);

        private record HeldRequest(URI endpoint, byte[] body, int length, HttpRequest request) {
        }

        private record HeldRange(URI endpoint, RangeBody body, HttpRequest request) {
        }

        private volatile HttpClient http;
        private ExecutorService executor;

        private volatile boolean closed;

        private final AtomicReference<ByteArrayOutputStream> spareFetchBody =
                new AtomicReference<>();

        private final AtomicReferenceArray<Reading> spareReadings =
                new AtomicReferenceArray<>(SPARE_READINGS);

        private final AtomicReferenceArray<HeldRequest> heldPosts =
                new AtomicReferenceArray<>(HELD_REQUESTS);

        private final AtomicReferenceArray<HeldRequest> heldGets =
                new AtomicReferenceArray<>(HELD_REQUESTS);

        private final AtomicReferenceArray<HeldRange> heldRanges =
                new AtomicReferenceArray<>(HELD_REQUESTS);

        private final UtcClock utc;

        HttpTransport() {
            this(UtcClock.collector());
        }

        HttpTransport(UtcClock utc) {
            this.utc = utc;
        }

        @Override
        public void close() {
            closed = true;
            HttpClient client;
            ExecutorService pool;
            synchronized (this) {
                client = http;
                pool = executor;
            }
            if (client != null) {
                client.shutdown();
            }
            if (pool != null) {
                pool.shutdown();
            }
        }

        @Override
        public Reply post(URI endpoint, byte[] body) throws IOException {
            HttpRequest request = posting(endpoint, body, body.length);

            HttpResponse<Void> response = exchange(request, MAX_REPLY_BYTES, null,
                    "interrupted while posting");
            return replyTo(response);
        }

        @Override
        public Reply post(URI endpoint, byte[] body, int length) throws IOException {
            Objects.checkFromIndexSize(0, length, body.length);
            int slot = Math.floorMod(endpoint.hashCode(), HELD_REQUESTS);
            HeldRange held = ranged(slot, endpoint, body, length);
            boolean ended = false;
            HttpResponse<Void> response;
            try {
                response = exchange(held.request(), MAX_REPLY_BYTES, null,
                        "interrupted while posting");
                ended = held.body().settled();
            } finally {
                if (!ended) {
                    heldRanges.compareAndSet(slot, held, null);
                }
            }
            return replyTo(response);
        }

        // server is null, or the name of the server whose world the record is from.
        Reply postNamed(URI endpoint, byte[] body, String server) throws IOException {
            if (server == null) {
                return post(endpoint, body);
            }
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "GeoSurvey/" + LandNav.MOD_ID)
                    .header("Content-Type", "application/octet-stream")
                    .header(SharedRecord.serverHeader(), SharedRecord.serverHeaderValue(server))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = exchange(request, MAX_REPLY_BYTES, null,
                    "interrupted while posting");
            return replyTo(response);
        }

        @Override
        public Reply answer(URI endpoint, byte[] body, ByteArrayOutputStream into)
                throws IOException {
            HttpRequest request = posting(endpoint, body, body.length);
            HttpResponse<Void> response = exchange(request, WorldAsk.MAX_BODY, into,
                    "interrupted while answering");
            return replyTo(response);
        }

        @Override
        public Reply postReading(URI endpoint, byte[] body, ByteArrayOutputStream into)
                throws IOException {
            HttpRequest request = posting(endpoint, body, body.length);
            HttpResponse<Void> response = exchange(request, MAX_REPLY_BYTES, into,
                    "interrupted while posting");
            return replyTo(response);
        }

        @Override
        public String fetch(URI endpoint) throws IOException {
            return fetch(endpoint, MAX_REPLY_BYTES);
        }

        @Override
        public String fetch(URI endpoint, int maxBytes) throws IOException {
            HttpRequest request = getting(endpoint);
            ByteArrayOutputStream body = spareFetchBody.getAndSet(null);
            if (body == null) {
                body = new ByteArrayOutputStream(replyCapacity(maxBytes));
            }
            body.reset();
            HttpResponse<Void> response = exchange(request, maxBytes, body,
                    "interrupted while asking", ONLY_OK);
            int status = response.statusCode();
            String read = status == HTTP_OK
                    ? body.toString(java.nio.charset.StandardCharsets.UTF_8).trim() : null;
            spareFetchBody.set(body);
            if (status != HTTP_OK && status != HTTP_NOT_FOUND) {
                throw new IOException(endpoint + " answered " + status);
            }
            return read;
        }

        @Override
        public boolean fetch(URI endpoint, int maxBytes, ByteArrayOutputStream into)
                throws IOException {
            HttpRequest request = getting(endpoint);
            into.reset();
            HttpResponse<Void> response = exchange(request, maxBytes, into,
                    "interrupted while asking", ONLY_OK);
            int status = response.statusCode();
            if (status != HTTP_OK) {
                into.reset();
            }
            if (status != HTTP_OK && status != HTTP_NOT_FOUND) {
                throw new IOException(endpoint + " answered " + status);
            }
            return status == HTTP_OK;
        }

        private HttpRequest posting(URI endpoint, byte[] body, int length) {
            int slot = Math.floorMod(endpoint.hashCode(), HELD_REQUESTS);
            HeldRequest held = heldPosts.get(slot);
            HttpRequest request;
            if (held != null && held.body() == body && held.length() == length
                    && held.endpoint().equals(endpoint)) {
                request = held.request();
            } else {
                request = postRequest(endpoint,
                        HttpRequest.BodyPublishers.ofByteArray(body, 0, length));
                heldPosts.set(slot, new HeldRequest(endpoint, body, length, request));
            }
            return request;
        }

        private HeldRange ranged(int slot, URI endpoint, byte[] body, int length) {
            HeldRange held = heldRanges.get(slot);
            if (held == null || !held.body().covers(body) || !held.endpoint().equals(endpoint)) {
                RangeBody range = new RangeBody(body);
                held = new HeldRange(endpoint, range, postRequest(endpoint, range));
                heldRanges.set(slot, held);
            }
            held.body().cover(length);
            return held;
        }

        private static HttpRequest postRequest(URI endpoint, HttpRequest.BodyPublisher body) {
            return HttpRequest.newBuilder(endpoint)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "GeoSurvey/" + LandNav.MOD_ID)
                    .header("Content-Type", "application/octet-stream")
                    .POST(body)
                    .build();
        }

        private HttpRequest getting(URI endpoint) {
            int slot = Math.floorMod(endpoint.hashCode(), HELD_REQUESTS);
            HeldRequest held = heldGets.get(slot);
            HttpRequest request;
            if (held != null && held.endpoint().equals(endpoint)) {
                request = held.request();
            } else {
                request = HttpRequest.newBuilder(endpoint)
                        .timeout(TIMEOUT)
                        .header("User-Agent", "GeoSurvey/" + LandNav.MOD_ID)
                        .GET()
                        .build();
                heldGets.set(slot, new HeldRequest(endpoint, null, 0, request));
            }
            return request;
        }

        private Reading heldReading() {
            Reading held = null;
            for (int slot = 0; held == null && slot < SPARE_READINGS; slot++) {
                if (spareReadings.get(slot) != null) {
                    held = spareReadings.getAndSet(slot, null);
                }
            }
            return held == null ? new Reading() : held;
        }

        private void spareReading(Reading done) {
            boolean kept = false;
            for (int slot = 0; !kept && slot < SPARE_READINGS; slot++) {
                if (spareReadings.get(slot) == null) {
                    kept = spareReadings.compareAndSet(slot, null, done);
                }
            }
        }

        private HttpResponse<Void> exchange(HttpRequest request, int maxBytes,
                                            ByteArrayOutputStream into,
                                            String interrupted) throws IOException {
            return exchange(request, maxBytes, into, interrupted, ANY_STATUS);
        }

        private HttpResponse<Void> exchange(HttpRequest request, int maxBytes,
                                            ByteArrayOutputStream into,
                                            String interrupted,
                                            IntPredicate keeps) throws IOException {
            if (closed) {
                throw new IOException("the share transport is closed");
            }
            Reading reading = heldReading();
            int cap = Math.max(1, maxBytes);
            reading.expect(into, cap, keeps);
            long startedAt = System.currentTimeMillis();
            CompletableFuture<HttpResponse<Void>> pending = client().sendAsync(request, reading);
            HttpResponse<Void> response;
            try {
                response = pending.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                noteDate(response, startedAt);
                if (reading.ended()) {
                    spareReading(reading);
                }
            } catch (TimeoutException tooSlow) {
                throw new IOException("the collector did not finish answering within "
                        + TIMEOUT.toSeconds() + "s", tooSlow);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                Throwable why = cause == null ? failed : cause;
                if (why instanceof IOException already) {
                    throw already;
                }
                throw new IOException(why.getMessage(), why);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted, stopping);
            } finally {
                pending.cancel(true);
            }
            return response;
        }

        private void noteDate(HttpResponse<Void> response, long startedAt) {
            List<String> dates = response.headers().allValues(DATE);
            if (!dates.isEmpty()) {
                utc.noteHttpDate(dates.get(0), startedAt, System.currentTimeMillis());
            }
        }

        // Null chunk means the body ended.
        private static void keep(ByteArrayOutputStream into, byte[] chunk,
                                 int cap) {
            if (into == null || chunk == null) {
                return;
            }
            int room = cap - into.size();
            if (room > 0) {
                into.write(chunk, 0, Math.min(room, chunk.length));
            }
        }

        private static final IntPredicate ANY_STATUS = null;

        private static final IntPredicate ONLY_OK = status -> status == 200;

        // Null sink discards the body.
        private static HttpResponse.BodySubscriber<Void> replySink(
                ByteArrayOutputStream into, int cap) {
            return into == null
                    ? HttpResponse.BodySubscribers.discarding()
                    : HttpResponse.BodySubscribers.ofByteArrayConsumer(
                            chunk -> keep(into, chunk.orElse(null), cap));
        }

        private static int replyCapacity(int maxBytes) {
            return Math.min(Math.max(1, maxBytes), MAX_REPLY_BYTES);
        }

        private static final class RangeBody implements HttpRequest.BodyPublisher,
                Flow.Subscription {

            private enum Stage {
                SETTLED,
                ARMING,
                ARMED,
                SENDING
            }

            private final byte[] bytes;

            private final ByteBuffer view;

            private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.SETTLED);

            private volatile int length;

            private volatile Flow.Subscriber<? super ByteBuffer> subscriber;

            private RangeBody(byte[] bytes) {
                this.bytes = bytes;
                this.view = ByteBuffer.wrap(bytes);
            }

            private boolean covers(byte[] body) {
                return bytes == body;
            }

            private void cover(int bytesToSend) {
                length = bytesToSend;
            }

            private boolean settled() {
                return stage.get() == Stage.SETTLED;
            }

            @Override
            public long contentLength() {
                return length;
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> to) {
                Objects.requireNonNull(to, "subscriber");
                int sending = length;
                if (stage.compareAndSet(Stage.SETTLED, Stage.ARMING)) {
                    view.clear();
                    view.limit(sending);
                    subscriber = to;
                    stage.set(Stage.ARMED);
                    to.onSubscribe(this);
                } else {
                    HttpRequest.BodyPublishers.ofByteArray(bytes, 0, sending).subscribe(to);
                }
            }

            @Override
            public void request(long wanted) {
                if (stage.compareAndSet(Stage.ARMED, Stage.SENDING)) {
                    Flow.Subscriber<? super ByteBuffer> to = subscriber;
                    if (wanted > 0L) {
                        if (view.hasRemaining()) {
                            to.onNext(view);
                        }
                        stage.set(Stage.SETTLED);
                        to.onComplete();
                    } else {
                        stage.set(Stage.SETTLED);
                        to.onError(new IllegalArgumentException(
                                "a subscriber asked for " + wanted + " buffers"));
                    }
                }
            }

            @Override
            public void cancel() {
                stage.compareAndSet(Stage.ARMED, Stage.SETTLED);
            }
        }

        private static final class Reading implements HttpResponse.BodyHandler<Void> {

            private final Discarding discarding = new Discarding();

            private final Keeping keeping = new Keeping();

            private final CappedBody body =
                    new CappedBody(HttpResponse.BodySubscribers.discarding(), 1);

            private ByteArrayOutputStream into;

            private int cap;

            private IntPredicate keeps;

            private void expect(ByteArrayOutputStream sink, int limit, IntPredicate filter) {
                into = sink;
                cap = limit;
                keeps = filter;
            }

            private boolean ended() {
                return body.ended();
            }

            @Override
            public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo reply) {
                boolean wanted = keeps == ANY_STATUS || keeps.test(reply.statusCode());
                body.arm(wanted && into != null ? keeping.arm(into, cap) : discarding.arm(), cap);
                return body;
            }
        }

        private static final class Discarding implements HttpResponse.BodySubscriber<Void> {

            private CompletableFuture<Void> result;

            private Discarding arm() {
                result = new CompletableFuture<>();
                return this;
            }

            @Override
            public CompletionStage<Void> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                result.complete(null);
            }
        }

        private static final class Keeping implements HttpResponse.BodySubscriber<Void> {

            private final byte[] xfer = new byte[KEEPING_CHUNK_BYTES];

            private ByteArrayOutputStream into;

            private int cap;

            private CompletableFuture<Void> result;

            private Keeping arm(ByteArrayOutputStream sink, int limit) {
                into = sink;
                cap = limit;
                result = new CompletableFuture<>();
                return this;
            }

            @Override
            public CompletionStage<Void> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                int itemSize = item.size();
                for (int i = 0; i < itemSize; i++) {
                    keep(item.get(i));
                }
            }

            private void keep(ByteBuffer chunk) {
                int room = cap - into.size();
                int take = room > 0 ? Math.min(room, chunk.remaining()) : 0;
                int left = take;
                while (left > 0) {
                    int n = Math.min(xfer.length, left);
                    chunk.get(xfer, 0, n);
                    into.write(xfer, 0, n);
                    left -= n;
                }
                if (chunk.hasRemaining()) {
                    chunk.position(chunk.limit());
                }
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                result.complete(null);
            }
        }

        private static final class CappedBody implements HttpResponse.BodySubscriber<Void> {

            private HttpResponse.BodySubscriber<Void> kept;
            private int cap;
            private Flow.Subscription subscription;
            private long read;
            private boolean done;
            private boolean ended;
            private final List<ByteBuffer> cut = new ArrayList<>();

            private CappedBody(HttpResponse.BodySubscriber<Void> kept, int cap) {
                this.kept = kept;
                this.cap = cap;
            }

            private void arm(HttpResponse.BodySubscriber<Void> next, int limit) {
                kept = next;
                cap = limit;
                subscription = null;
                read = 0L;
                done = false;
                ended = false;
            }

            private boolean ended() {
                return ended;
            }

            @Override
            public CompletionStage<Void> getBody() {
                return kept.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription given) {
                if (subscription == null) {
                    subscription = given;
                }
                kept.onSubscribe(given);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                if (done) {
                    return;
                }
                long room = cap - read;
                long incoming = 0L;
                int itemSize = item.size();
                for (int i = 0; i < itemSize; i++) {
                    incoming += item.get(i).remaining();
                }
                if (incoming > room) {
                    done = true;
                    read = cap;
                    kept.onNext(trimmed(item, room));
                    kept.onComplete();
                    subscription.cancel();
                } else {
                    read += incoming;
                    kept.onNext(item);
                }
            }

            @Override
            public void onError(Throwable failure) {
                if (done) {
                    return;
                }
                done = true;
                ended = true;
                kept.onError(failure);
            }

            @Override
            public void onComplete() {
                if (done) {
                    return;
                }
                done = true;
                ended = true;
                kept.onComplete();
            }

            private List<ByteBuffer> trimmed(List<ByteBuffer> item, long room) {
                cut.clear();
                long left = Math.max(0L, room);
                int itemSize = item.size();
                for (int i = 0; i < itemSize; i++) {
                    if (left <= 0L) {
                        break;
                    }
                    ByteBuffer chunk = item.get(i);
                    ByteBuffer view = chunk.duplicate();
                    if (view.remaining() > left) {
                        view.limit(view.position() + (int) left);
                    }
                    left -= view.remaining();
                    cut.add(view);
                }
                return cut;
            }
        }

        private static long retryAfter(HttpResponse<?> response) {
            if (response.statusCode() != Enroller.HTTP_TOO_MANY_REQUESTS) {
                return 0;
            }
            String header = response.headers().firstValue("Retry-After").orElse(null);
            long seconds = header == null ? -1L : delaySeconds(header.trim());
            return seconds < 0L ? UNREADABLE_RETRY_AFTER_SECONDS : seconds;
        }

        private static long delaySeconds(String text) {
            Long seconds = NumberGrammar.decimalLong(text);
            return seconds == null ? -1L : seconds.longValue();
        }

        private static Reply replyTo(HttpResponse<?> response) {
            int status = response.statusCode();
            long retryAfterSeconds = retryAfter(response);
            if (retryAfterSeconds != 0L || status < 0 || status >= STATUS_CODES) {
                return new Reply(status, retryAfterSeconds);
            }
            Reply held = REPLIES.get(status);
            if (held == null) {
                Reply made = new Reply(status, 0L);
                Reply raced = REPLIES.compareAndExchange(status, null, made);
                held = raced == null ? made : raced;
            }
            return held;
        }

        private HttpClient client() {
            HttpClient existing = http;
            if (existing == null) {
                synchronized (this) {
                    existing = http;
                    if (existing == null) {
                        executor = Executors.newCachedThreadPool(
                                Background.factory("geosurvey-http-"));
                        existing = HttpClient.newBuilder()
                                .connectTimeout(TIMEOUT)
                                .version(HttpClient.Version.HTTP_1_1)
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .executor(executor)
                                .build();
                        http = existing;
                    }
                }
            }
            return existing;
        }
    }
}
