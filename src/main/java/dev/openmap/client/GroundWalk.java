package dev.openmap.client;

import dev.openmap.share.SignedBatch;
import dev.openmap.share.StandingAsk;
import dev.openmap.share.WorldAsk;
import dev.openmap.share.WorldPrint;
import dev.openmap.share.WorldProof;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import dev.sandpaper.core.Step;
import dev.sandpaper.core.Tick;
import net.minecraft.world.level.Level;

// Walking a collector's regions, one answer at a time.
// near() (tick thread) reads and parks an answer; beat() (share thread) sends it.
// Nothing here is persisted; the walk resets on a new connection or a new collector.
final class GroundWalk {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("geosurvey");

    private static final long READ_AGAIN_MILLIS = 30_000L;

    private static final long READ_AGAIN_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(READ_AGAIN_MILLIS);

    private static final int MAX_POST_TRIES = 3;

    private static final int NOT_FOUND_STATUS = 404;

    private static final long POST_BACKOFF_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);

    private static final String SAID = "[GeoSurvey] ";

    // Lines waiting for the tick thread, bounded.
    private static final int MAX_NOTICES = 8;

    // Distinct lines remembered before the memory is dropped and lines repeat.
    private static final int MAX_SAID = 64;

    private static final String REFUSED_ROW =
            "This collector asked for ground this client cannot read."
                    + " Ask the operator.";

    private static final java.lang.invoke.VarHandle TROUBLE = troubleField();

    // Lines the tick thread has not put in chat yet.
    private final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<>();

    // Every line said this session; a repeat says nothing.
    private final Set<String> alreadySaid = ConcurrentHashMap.newKeySet();

    // One answer waiting for the share thread, or null.
    private record Parked(String server, URI collector, WorldPrint.Region region,
                          String signature, int tries) {
    }

    private final AtomicReference<Parked> parked = new AtomicReference<>();

    private final java.util.concurrent.atomic.AtomicLong postRetryAt =
            new java.util.concurrent.atomic.AtomicLong();

    private final AtomicReference<byte[]> spareBody = new AtomicReference<>();

    private final AtomicReference<byte[]> spareMessage = new AtomicReference<>();

    private final AtomicReference<WorldAsk.AskHolder> spareAsk = new AtomicReference<>();

    private final AtomicReference<ShareSender.ReplySink> spareAskReply = new AtomicReference<>();

    private final AtomicReference<ShareSender.ReplySink> spareReply = new AtomicReference<>();

    // The standing ask's own reply-sink slot; kept separate from spareReply.
    private final AtomicReference<ShareSender.ReplySink> spareStandingReply =
            new AtomicReference<>();

    private final AtomicReference<byte[]> spareStandingMessage = new AtomicReference<>();

    private final AtomicReference<StandingAsk.Body> spareStandingBody = new AtomicReference<>();

    private final AtomicReference<WorldAsk.AnswerHolder> spareStandingAnswer =
            new AtomicReference<>();

    private final WorldAsk.AnswerHolder proofAnswer = new WorldAsk.AnswerHolder();

    private record ProofEndpoint(URI collector, URI endpoint) {
    }

    private record StandingEndpoint(URI collector, URI endpoint) {
    }

    private final AtomicReference<ProofEndpoint> proofEndpoint = new AtomicReference<>();

    private final AtomicReference<StandingEndpoint> standingEndpoint = new AtomicReference<>();

    private final ShareSender.SigningBytes standingSigner = new ShareSender.SigningBytes();

    private final ShareSender.SigningBytes proofSigner = new ShareSender.SigningBytes();

    private final java.util.function.LongSupplier clock = System::nanoTime;

    // When each owed region was last read, by name. Bounded by WorldAsk.MAX_REGIONS.
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> lastRead =
            new ConcurrentHashMap<>();

    // The server the current lists belong to; null before the first ask.
    private volatile String server;

    // The collector the current lists came from; null before the first ask.
    private volatile URI collector;

    private final Object walkLock = new Object();

    // Every region this node asks about, or null before it has been asked.
    private volatile List<WorldPrint.Region> asked;

    // What is still owed. Null means the node has not said (not the same as nothing left).
    private volatile List<String> remaining;

    private record Owed(List<WorldPrint.Region> asked, List<String> remaining,
                        List<WorldPrint.Region> regions) {
    }

    private final AtomicReference<Owed> owedCache = new AtomicReference<>();

    private record Said(String text, String why, List<WorldPrint.Region> asked,
                        List<WorldPrint.Region> owed, List<String> left, String refused) {
    }

    private final AtomicReference<Said> saidCache = new AtomicReference<>();

    // The last thing worth telling the player, for the tooltip.
    private volatile String trouble;

    private volatile String refusedRow;

    private volatile boolean reasked;

    // The rectangle being read a chunk at a time, or null. Tick thread only.
    private WorldHandshake.Progress reading;

    // The dimension reading was opened in, or null. Tick thread only.
    private String readingIn;

    private Level readingLevel;

    // The collector reading was opened against, or null. Tick thread only.
    private java.net.URI readingVia;

    // The server reading was opened for, or null. Tick thread only.
    private String readingFor;

    // Forgets a server's lists, so a reconnection starts the walk again.
    void joined(String joinedServer) {
        if (!java.util.Objects.equals(joinedServer, server)) {
            synchronized (walkLock) {
                server = joinedServer;
                forget();
                reasked = false;
            }
        }
    }

    // Forgets a collector's lists when the address moves off it.
    private void pointedAt(URI atCollector) {
        URI held = collector;
        if (held == null || !held.equals(atCollector)) {
            synchronized (walkLock) {
                collector = atCollector;
                forget();
                reasked = false;
            }
        }
    }

    // Everything a walk holds, dropped as a unit.
    // reading is deliberately not cleared here; it belongs to the tick thread.
    private void forget() {
        asked = null;
        remaining = null;
        trouble = null;
        refusedRow = null;
        owedCache.set(null);
        saidCache.set(null);
        dimScan.set(null);
        parked.set(null);
        postRetryAt.set(0L);
        lastRead.clear();
        notices.clear();
        alreadySaid.clear();
    }

    // Queues one line for chat, once. Any thread.
    private void say(String line) {
        if (line != null && !line.isEmpty()) {
            if (alreadySaid.size() >= MAX_SAID) {
                alreadySaid.clear();
            }
            if (alreadySaid.add(line)) {
                notices.add(SAID + line);
                while (notices.size() > MAX_NOTICES) {
                    notices.poll();
                }
            }
        }
    }

    // Takes one line for chat, or null. Tick thread.
    String notice() {
        return notices.poll();
    }

    // Whether the walk is still the one this call started against; narrows the race
    // window around a network call, does not close it.
    private boolean stillWalking(URI atCollector, String atServer,
                                 java.util.function.Supplier<URI> liveCollector) {
        URI live = liveCollector == null ? null : liveCollector.get();
        URI current = live != null ? live : collector;
        return atCollector != null && atCollector.equals(current)
                && atServer != null && atServer.equals(server);
    }

    // Whether the handshake has anything a player can act on yet.
    boolean worthSaying() {
        return asked != null || trouble != null;
    }

    // Whether this node has been asked what it wants yet.
    boolean asked() {
        return asked != null;
    }

    boolean askedNothing() {
        List<WorldPrint.Region> at = asked;
        return at != null && at.isEmpty();
    }

    boolean mayReask() {
        return !reasked;
    }

    void reask() {
        synchronized (walkLock) {
            forget();
            reasked = true;
        }
    }

    // Whether a ground reading is open. Read on the tick thread, which is also where
    // near() writes it.
    boolean readingOpen() {
        return reading != null;
    }

    // Asks a collector which regions it wants read. Share thread.
    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer) {
        return ask(transport, atCollector, forServer, () -> this.collector);
    }

    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer,
                java.util.function.Supplier<URI> liveCollector) {
        return ask(transport, atCollector, forServer, liveCollector, null);
    }

    // Also sends the signed standing ask when identity is given (null skips it).
    // Ground pool's worker.
    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer,
                java.util.function.Supplier<URI> liveCollector, ShareSender.Identity identity) {
        if (atCollector == null || forServer == null || forServer.isBlank()) {
            return asked != null;
        }
        if (!forServer.equals(server)) {
            return asked != null;
        }
        pointedAt(atCollector);
        boolean outcome;
        ShareSender.ReplySink sink = heldReply(spareAskReply);
        try {
            boolean found = transport.fetch(
                    atCollector.resolve(WorldAsk.PATH.substring(1) + "?server="
                            + java.net.URLEncoder.encode(forServer,
                                    java.nio.charset.StandardCharsets.UTF_8)),
                    WorldAsk.MAX_BODY, sink);
            if (!stillWalking(atCollector, forServer, liveCollector)) {
                outcome = asked != null;
            } else if (!found) {
                // 404: a legacy node with no handshake; it asks nothing.
                synchronized (walkLock) {
                    if (!stillWalking(atCollector, forServer, liveCollector)) {
                        outcome = asked != null;
                    } else {
                        asked = List.of();
                        remaining = List.of();
                        trouble = null;
                        refusedRow = null;
                        outcome = true;
                    }
                }
            } else {
                WorldAsk.AskHolder got = heldAsk();
                sink.ask(got);
                if (!got.understood()) {
                    synchronized (walkLock) {
                        if (!stillWalking(atCollector, forServer, liveCollector)) {
                            outcome = asked != null;
                        } else {
                            trouble = "This collector answered something this version cannot read.";
                            refusedRow = null;
                            outcome = false;
                        }
                    }
                } else {
                    boolean refused = got.refusedRow();
                    if ((got.count() == 0) && refused) {
                        synchronized (walkLock) {
                            if (!stillWalking(atCollector, forServer, liveCollector)) {
                                outcome = asked != null;
                            } else {
                                trouble = REFUSED_ROW;
                                refusedRow = null;
                                say(REFUSED_ROW);
                                outcome = false;
                            }
                        }
                    } else {
                        outcome = filed(transport, atCollector, forServer, liveCollector,
                                identity, got, refused);
                    }
                }
                spareAsk.set(got);
            }
            spareAskReply.set(sink);
        } catch (IOException | RuntimeException unreachable) {
            // Left unasked on purpose, so the next beat tries again.
            synchronized (walkLock) {
                if (stillWalking(atCollector, forServer, liveCollector)) {
                    trouble = "The collector could not be asked which ground to read.";
                }
            }
            outcome = asked != null;
        }
        return outcome;
    }

    // Files a readable answer under walkLock and says the walk's line, unless a standing
    // ask is still due. Ground pool's worker, called only from ask().
    private boolean filed(ShareSender.Transport transport, URI atCollector, String forServer,
                          java.util.function.Supplier<URI> liveCollector,
                          ShareSender.Identity identity, WorldAsk.AskHolder got,
                          boolean refused) {
        boolean outcome;
        boolean standing;
        synchronized (walkLock) {
            if (!stillWalking(atCollector, forServer, liveCollector)) {
                outcome = asked != null;
                standing = false;
            } else {
                keepAnswer(got);
                remaining = null;
                trouble = null;
                refusedRow = refused ? REFUSED_ROW : null;
                if (!asked.isEmpty()) {
                    reasked = false;
                }
                standing = identity != null && !asked.isEmpty();
                if (!standing) {
                    sayOwed(refused);
                }
                outcome = asked != null;
            }
        }
        if (standing) {
            standingAsk(transport, atCollector, forServer, liveCollector, identity, refused);
        }
        return outcome;
    }

    // Says the walk's line and the refused-row line. Runs with walkLock held.
    private void sayOwed(boolean refused) {
        List<WorldPrint.Region> wanted = owed();
        if (!wanted.isEmpty()) {
            say(WorldPrint.saying(wanted));
        }
        if (refused) {
            say(refusedRow);
        }
    }

    // Asks what this account still owes, then says the walk's line. Ground pool's worker,
    // called only from filed(); no lock during the post, walkLock for the write after.
    private void standingAsk(ShareSender.Transport transport, URI atCollector,
                             String forServer,
                             java.util.function.Supplier<URI> liveCollector,
                             ShareSender.Identity identity, boolean refused) {
        List<String> left = standingReply(transport, atCollector, forServer, identity);
        synchronized (walkLock) {
            if (stillWalking(atCollector, forServer, liveCollector)) {
                if (left != null) {
                    remaining = left;
                }
                sayOwed(refused);
            }
        }
    }

    // Signs and posts the standing ask; returns the list it names, or null if unreadable.
    // Ground pool's worker, no lock.
    private List<String> standingReply(ShareSender.Transport transport, URI atCollector,
                                       String forServer, ShareSender.Identity identity) {
        ShareSender.ReplySink sink = heldReply(spareStandingReply);
        StandingAsk.Body body = heldBody();
        WorldAsk.AnswerHolder said = heldStandingAnswer();
        List<String> left;
        try {
            StandingAsk.body(forServer, System.currentTimeMillis(), WorldPrint.nonce(), body);
            standingSigner.set(body.bytes(), body.length());
            byte[] signature = identity.signer().sign(standingSigner);
            byte[] message = SignedBatch.encodeInto(spareStandingMessage.getAndSet(null),
                    identity.credential(), signature, body.bytes(), body.length());
            transport.answer(standingEndpointFor(atCollector), message, sink);

            // Returned normally: read the sink, then hand it back.
            sink.answer(said);
            spareStandingMessage.set(message);
            spareStandingReply.set(sink);
            left = said.listed() ? said.names() : null;
        } catch (IOException | RuntimeException unreachable) {
            // On failure the sink is dropped, not reused.
            left = null;
        }
        spareStandingBody.set(body);
        spareStandingAnswer.set(said);
        return left;
    }

    private StandingAsk.Body heldBody() {
        StandingAsk.Body held = spareStandingBody.getAndSet(null);
        return (held != null) ? held : new StandingAsk.Body();
    }

    private WorldAsk.AnswerHolder heldStandingAnswer() {
        WorldAsk.AnswerHolder held = spareStandingAnswer.getAndSet(null);
        return (held != null) ? held : new WorldAsk.AnswerHolder();
    }

    private URI standingEndpointFor(URI atCollector) {
        StandingEndpoint held = standingEndpoint.get();
        if (held == null || !held.collector().equals(atCollector)) {
            held = new StandingEndpoint(atCollector,
                    atCollector.resolve(StandingAsk.PATH.substring(1)));
            standingEndpoint.set(held);
        }
        return held.endpoint();
    }

    // The regions still to walk, in the operator's order.
    List<WorldPrint.Region> owed() {
        List<WorldPrint.Region> list = asked;
        List<String> left = remaining;
        Owed held = owedCache.get();
        if (held != null && held.asked() == list && held.remaining() == left) {
            return held.regions();
        }
        List<WorldPrint.Region> owed =
                list == null ? List.of() : WorldPrint.owing(list, left);
        owedCache.set(new Owed(list, left, owed));
        return owed;
    }

    private record DimScan(List<WorldPrint.Region> asked, List<String> remaining,
                           String at, List<WorldPrint.Region> matching) {
    }

    private final AtomicReference<DimScan> dimScan = new AtomicReference<>();

    List<WorldPrint.Region> owedFor(String atDimension) {
        List<WorldPrint.Region> list = asked;
        List<String> left = remaining;
        DimScan held = dimScan.get();
        if (held != null && held.asked() == list && held.remaining() == left
                && java.util.Objects.equals(held.at(), atDimension)) {
            return held.matching();
        }
        List<WorldPrint.Region> same = new java.util.ArrayList<>();
        for (WorldPrint.Region region : owed()) {
            if (WorldPrint.sameDimension(region.dimension(), atDimension)) {
                same.add(region);
            }
        }
        List<WorldPrint.Region> matching = List.copyOf(same);
        dimScan.set(new DimScan(list, left, atDimension, matching));
        return matching;
    }

    private WorldAsk.AskHolder heldAsk() {
        WorldAsk.AskHolder held = spareAsk.getAndSet(null);
        return (held != null) ? held : new WorldAsk.AskHolder();
    }

    private void keepAnswer(WorldAsk.AskHolder got) {
        List<WorldPrint.Region> held = asked;
        if ((held == null) || !got.holds(held)) {
            asked = got.regions();
        }
    }

    // Whether the walk is finished; both the owed list and remaining must be empty, not
    // just one.
    boolean proven() {
        List<WorldPrint.Region> at = asked;
        if (at == null) {
            return false;
        }
        List<String> left = remaining;
        return WorldPrint.owesNothing(at, left) && (left == null || left.isEmpty());
    }

    // What to show the player. The trouble (why) is appended to the instruction (where),
    // not substituted; a finished walk carries neither.
    String saying() {
        String why = trouble;
        List<WorldPrint.Region> at = asked;
        List<WorldPrint.Region> nowOwed = at == null ? List.of() : owed();
        List<String> left = remaining;
        String refused = refusedRow;
        Said held = saidCache.get();
        if (held != null && held.why() == why && held.asked() == at
                && held.owed() == nowOwed && held.left() == left && held.refused() == refused) {
            return held.text();
        }
        String text = composeSaying(why, at, nowOwed, left, refused);
        saidCache.set(new Said(text, why, at, nowOwed, left, refused));
        return text;
    }

    private String composeSaying(String why, List<WorldPrint.Region> at,
                                 List<WorldPrint.Region> owed, List<String> left,
                                 String refused) {
        String saying;
        if (at == null) {
            saying = why == null ? "Checking what this collector asks for." : why;
        } else if (owed.isEmpty() && left != null && !left.isEmpty()) {
            if (refused != null) {
                saying = refused;
            } else {
                saying = "The collector is asking for ground this client was not told about."
                        + " Reconnect to pick it up.";
            }
        } else {
            String instruction = WorldPrint.saying(owed);
            if (why == null || owed.isEmpty()) {
                saying = refused == null ? instruction : instruction + " " + refused;
            } else {
                saying = refused == null
                        ? instruction + " " + why
                        : instruction + " " + why + " " + refused;
            }
        }
        return saying;
    }

    // Reads an owed region the player is standing in. Tick thread only.
    // An incomplete reading is dropped, not parked: a partial read is a different
    // signature, not a worse one. Throttled separately from the parked-answer check.
    void near(Level level, String atServer, String atDimension, int x, int z) {
        if (level == null || atServer == null || !atServer.equals(server)) {
            return;
        }
        if (parked.get() != null || reading != null) {
            return;
        }
        long since = System.nanoTime();
        List<WorldPrint.Region> nearby = owedFor(atDimension);
        int nearbyCount = nearby.size();
        boolean armed = false;
        for (int i = 0; i < nearbyCount && !armed; i++) {
            WorldPrint.Region region = nearby.get(i);
            if (!region.holds(x, z)) {
                continue;
            }
            java.util.concurrent.atomic.AtomicLong last = lastRead.get(region.name());
            if (last != null && since - last.get() < READ_AGAIN_NANOS) {
                // continue, not return: two owed regions can overlap, so the next one
                // still gets a chance.
                continue;
            }
            if (last == null) {
                lastRead.put(region.name(), new java.util.concurrent.atomic.AtomicLong(since));
            } else {
                last.set(since);
            }
            // Arms a sliced job; the actual read happens in readMore(), a chunk at a time.
            reading = WorldHandshake.begin(region);
            readingFor = atServer;
            readingIn = atDimension;
            readingVia = collector;
            readingLevel = level;
            armed = true;
        }
    }

    // Reads a little more of the armed rectangle, a chunk per call. Tick thread only.
    Step readMore(Level level, Tick tick) {
        WorldHandshake.Progress open = reading;
        if (open == null || level == null) {
            return Step.YIELD;
        }
        Step result = Step.YIELD;

        String belongsTo = readingFor;
        URI via = readingVia;
        if (belongsTo == null || !belongsTo.equals(server)
                || via == null || !via.equals(collector)
                || readingLevel != level) {
            dropReading();
        } else {
            boolean walked;
            boolean stepBroke;
            try {
                walked = WorldHandshake.step(level, open);
                stepBroke = false;
            } catch (Throwable broke) {
                walked = false;
                stepBroke = true;
                dropReading();
                readingTrouble(belongsTo, via, "This ground could not be read (see the log).", false);
                LOGGER.warn("geosurvey could not read the ground for the region {}."
                        + " Nothing lost. The walk retries.",
                        open.region().name(), broke);
            }
            if (!stepBroke) {
                if (!walked) {
                    result = Step.MORE;
                } else {
                    dropReading();
                    WorldPrint.Reading got = open.reading();
                    if (got.complete()) {
                        if (belongsTo.equals(server) && via.equals(collector)) {
                            postRetryAt.set(0L);
                            Parked answer = new Parked(belongsTo, via, open.region(),
                                    got.signature(), 0);
                            parked.set(answer);
                            if (belongsTo.equals(server) && via.equals(collector)) {
                                trouble = null;
                            } else {
                                parked.compareAndSet(answer, null);
                            }
                        }
                    } else if (got.obfuscated() > 0) {
                        String hidden = "This server hides its bedrock. This ground cannot be read. "
                                + "Ask the operator about anti-xray.";
                        readingTrouble(belongsTo, via, hidden, true);
                    } else {
                        String shortRead = "This ground was not all loaded when read: the reading"
                                + " came up short. Keep the whole region in view and walk it again.";
                        readingTrouble(belongsTo, via, shortRead, true);
                    }
                }
            }
        }
        return result;
    }

    private void readingTrouble(String belongsTo, URI via, String line, boolean spoken) {
        if (belongsTo.equals(server) && via.equals(collector)) {
            if (spoken) {
                say(line);
            }
            trouble = line;
            if (!belongsTo.equals(server) || !via.equals(collector)) {
                TROUBLE.compareAndSet(this, line, (String) null);
                if (spoken) {
                    notices.remove(SAID + line);
                    alreadySaid.remove(line);
                }
            }
        }
    }

    // Closes an open reading and every stamp that identified it. Tick thread only.
    private void dropReading() {
        reading = null;
        readingFor = null;
        readingIn = null;
        readingVia = null;
        readingLevel = null;
    }

    void closeReading() {
        dropReading();
    }

    // Posts a parked answer, if there is one. Share thread only.
    void beat(ShareSender.Transport transport, URI atCollector,
              ShareSender.Identity me) {
        beat(transport, atCollector, () -> this.collector, me);
    }

    void beat(ShareSender.Transport transport, URI atCollector,
              java.util.function.Supplier<URI> liveCollector, ShareSender.Identity me) {
        if (atCollector == null || me == null) {
            return;
        }

        pointedAt(atCollector);
        Parked answer = parked.get();
        if (answer == null || !answer.server().equals(server)
                || !answer.collector().equals(atCollector)) {
            return;
        }
        if (clock.getAsLong() < postRetryAt.get()) {
            return;
        }
        byte[] message = proofMessage(answer, atCollector, liveCollector, me);
        if (message != null) {
            postProof(transport, atCollector, liveCollector, answer, message);
        }
    }

    private byte[] proofMessage(Parked answer, URI atCollector,
                                java.util.function.Supplier<URI> liveCollector,
                                ShareSender.Identity me) {
        long nonce = WorldPrint.nonce();
        byte[] message;
        boolean built = false;
        try {
            byte[] body = WorldProof.encodeInto(spareBody.getAndSet(null), answer.server(),
                    answer.region().name(), me.credential().player(), nonce,
                    answer.signature(), System.currentTimeMillis());
            built = true;
            proofSigner.set(body);
            byte[] signature = me.signer().sign(proofSigner);
            message = SignedBatch.encodeInto(spareMessage.getAndSet(null), me.credential(),
                    signature, body);
            spareBody.set(body);
        } catch (IOException | RuntimeException notSendable) {
            LOGGER.warn("geosurvey could not build the ground proof for the region"
                    + " {}. Nothing lost. The walk retries.",
                    answer.region().name(), notSendable);
            synchronized (walkLock) {
                if (stillWalking(atCollector, answer.server(), liveCollector)) {
                    trouble = "The proof could not be built (see the log).";
                    if (built) {
                        rePark(answer);
                    } else {
                        parked.compareAndSet(answer, null);
                    }
                }
            }
            message = null;
        }
        return message;
    }

    private void postProof(ShareSender.Transport transport, URI atCollector,
                           java.util.function.Supplier<URI> liveCollector, Parked answer,
                           byte[] message) {
        ShareSender.ReplySink sink = heldReply(spareReply);
        try {
            ShareSender.Reply reply = transport.answer(proofEndpointFor(atCollector),
                    message, sink);
            sink.answer(proofAnswer);
            spareMessage.set(message);
            spareReply.set(sink);

            if (stillWalking(atCollector, answer.server(), liveCollector)) {
                if (proofAnswer.listed()) {
                    repliedWithList(answer, atCollector, liveCollector, proofAnswer.names());
                } else {
                    repliedWithoutList(answer, atCollector, liveCollector, reply,
                            proofAnswer.accepted());
                }
            }
        } catch (IOException | RuntimeException unreachable) {
            synchronized (walkLock) {
                if (stillWalking(atCollector, answer.server(), liveCollector)) {
                    trouble = "The proof could not be sent.";
                    rePark(answer);
                }
            }
        }
    }

    // Takes slot's sink (or makes one) and resets it. The caller alone sets it back.
    private ShareSender.ReplySink heldReply(AtomicReference<ShareSender.ReplySink> slot) {
        ShareSender.ReplySink held = slot.getAndSet(null);
        if (held == null) {
            held = new ShareSender.ReplySink();
        }
        held.reset();
        return held;
    }

    private URI proofEndpointFor(URI atCollector) {
        ProofEndpoint held = proofEndpoint.get();
        if (held == null || !held.collector().equals(atCollector)) {
            held = new ProofEndpoint(atCollector,
                    atCollector.resolve(WorldAsk.PATH.substring(1)));
            proofEndpoint.set(held);
        }
        return held.endpoint();
    }

    private void repliedWithList(Parked answer, URI atCollector,
                                 java.util.function.Supplier<URI> liveCollector,
                                 List<String> left) {
        synchronized (walkLock) {
            if (stillWalking(atCollector, answer.server(), liveCollector)) {
                parked.compareAndSet(answer, null);
                remaining = left;

                List<WorldPrint.Region> stillOwed = owed();
                String askedName = answer.region().name();
                int leftCount = left.size();
                boolean stillWanted = false;
                for (int i = 0; i < leftCount && !stillWanted; i++) {
                    stillWanted = left.get(i).equalsIgnoreCase(askedName);
                }
                if (!stillWanted) {
                    trouble = null;
                    say(stillOwed.isEmpty() && !left.isEmpty()
                            ? (refusedRow != null ? refusedRow
                                    : "The collector is asking for ground this client was"
                                            + " not told about. Reconnect to pick it up.")
                            : WorldPrint.accepted(answer.region().name(), stillOwed));
                } else {
                    String stillOwedLine = "The collector took that reading and still asks for "
                            + ShareCommand.drawable(askedName) + ". Its records may name that"
                            + " ground twice. Ask the operator.";
                    trouble = stillOwedLine;
                    say(stillOwedLine);
                }
            }
        }
    }

    private void repliedWithoutList(Parked answer, URI atCollector,
                                    java.util.function.Supplier<URI> liveCollector,
                                    ShareSender.Reply reply, boolean took) {
        synchronized (walkLock) {
            if (stillWalking(atCollector, answer.server(), liveCollector)) {
                // 404: a legacy node, not a fault.
                if (reply.status() == NOT_FOUND_STATUS) {
                    parked.compareAndSet(answer, null);
                    asked = null;
                    remaining = null;
                    refusedRow = null;
                    String noRouteLine = "The collector has no ground route (404). It cannot take"
                            + " the reading it asked for. This client is asking it again.";
                    trouble = noRouteLine;
                    say(noRouteLine);
                } else if (took) {
                    parked.compareAndSet(answer, null);
                    String unreadableListLine = "The collector took that reading. This client cannot read its"
                            + " list of what is still owed. Ask the operator.";
                    trouble = unreadableListLine;
                    say(unreadableListLine);
                } else {
                    String refusalLine = "The collector did not accept that reading ("
                            + reply.status() + ").";
                    trouble = refusalLine;
                    say(refusalLine);
                    rePark(answer);
                }
            }
        }
    }
    private void rePark(Parked answer) {
        int nextTries = answer.tries() + 1;
        if (nextTries >= MAX_POST_TRIES) {
            parked.compareAndSet(answer, null);
            return;
        }
        postRetryAt.set(clock.getAsLong() + POST_BACKOFF_NANOS * nextTries);
        parked.compareAndSet(answer, new Parked(answer.server(), answer.collector(),
                answer.region(), answer.signature(), nextTries));
    }

    private static java.lang.invoke.VarHandle troubleField() {
        java.lang.invoke.VarHandle field;
        try {
            field = java.lang.invoke.MethodHandles.lookup()
                    .findVarHandle(GroundWalk.class, "trouble", String.class);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException("GroundWalk.trouble has no VarHandle", unreachable);
        }
        return field;
    }
}
