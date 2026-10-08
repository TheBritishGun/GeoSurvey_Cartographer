package dev.openmap.client;

import dev.openmap.claim.NodeAddress;
import dev.openmap.claim.ServerConfirmation;
import dev.openmap.share.SignedBatch;
import dev.openmap.share.StandingAsk;
import dev.openmap.share.UtcClock;
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

// Walks a collector's regions, one answer at a time.
final class GroundWalk {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final long READ_AGAIN_MILLIS = 30_000L;

    private static final long READ_AGAIN_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(READ_AGAIN_MILLIS);

    private static final int MAX_POST_TRIES = 3;

    private static final int NOT_FOUND_STATUS = 404;

    private static final long POST_BACKOFF_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);

    static final String SAID = "[GeoSurvey] ";

    private static final int MAX_NOTICES = 8;

    private static final int MAX_SAID = 64;

    private static final String REFUSED_ROW =
            "This collector asked for ground this client cannot read."
                    + " Ask the operator.";

    private static final String NOT_TOLD =
            "The collector asks for ground this client was not told about."
                    + " Reconnect to pick it up.";

    private static final String OVER_CAP =
            "This collector asks for too much ground."
                    + " Ask the operator to list at most " + WorldAsk.MAX_REGIONS + " regions.";

    private static final int HTTP_STATUS_CLASS = 100;

    private static final int HTTP_SUCCESS_CLASS = 2;

    private static final int HTTP_SERVER_ERROR_CLASS = 5;

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final int HTTP_UNAUTHORIZED = 401;

    private static final java.lang.invoke.VarHandle TROUBLE = troubleField();

    private final ConcurrentLinkedQueue<String> notices = new ConcurrentLinkedQueue<>();

    private final Set<String> alreadySaid = ConcurrentHashMap.newKeySet();

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

    private final UtcClock utc;

    // Bounded by WorldAsk.MAX_REGIONS.
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> lastRead =
            new ConcurrentHashMap<>();

    // Null when no server is joined.
    private volatile String server;

    private volatile URI collector;

    private final Object walkLock = new Object();

    // Null until the node is asked.
    private volatile List<WorldPrint.Region> asked;

    // Null until the node answers; empty when nothing is left.
    private volatile List<String> remaining;

    private record Owed(List<WorldPrint.Region> asked, List<String> remaining,
                        List<WorldPrint.Region> regions) {
    }

    private final AtomicReference<Owed> owedCache = new AtomicReference<>();

    private record Said(String text, String why, List<WorldPrint.Region> asked,
                        List<WorldPrint.Region> owed, List<String> left, List<String> refused) {
    }

    private final AtomicReference<Said> saidCache = new AtomicReference<>();

    private volatile String trouble;

    // Refused row names; a row with no readable name is "".
    private volatile List<String> refusedRows = List.of();

    private volatile boolean reasked;

    private enum Outcome {
        LISTED, AGAIN, SHUT, UNPROVEN
    }

    // Only the thread that sends a standing ask uses it.
    private Outcome standingOutcome = Outcome.SHUT;

    private volatile boolean standingDue = false;

    private volatile boolean standingShut = false;

    private volatile boolean standingUnproven = false;

    // Tick thread only: reading and every reading* field.
    private WorldHandshake.Progress reading;

    private String readingIn;

    private Level readingLevel;

    private java.net.URI readingVia;

    private String readingFor;

    GroundWalk() {
        this(UtcClock.collector());
    }

    GroundWalk(UtcClock utc) {
        this.utc = utc;
    }

    void joined(String joinedServer) {
        if (!java.util.Objects.equals(joinedServer, server)) {
            synchronized (walkLock) {
                server = joinedServer;
                forget();
                reasked = false;
                standingShut = false;
                standingUnproven = false;
            }
        }
    }

    private void pointedAt(URI atCollector) {
        synchronized (walkLock) {
            URI held = collector;
            if (held == null || !held.equals(atCollector)) {
                forget();
                collector = atCollector;
                reasked = false;
                standingShut = false;
                standingUnproven = false;
            }
        }
    }

    private void forget() {
        asked = null;
        remaining = null;
        trouble = null;
        refusedRows = List.of();
        standingDue = false;
        owedCache.set(null);
        saidCache.set(null);
        dimScan.set(null);
        parked.set(null);
        postRetryAt.set(0L);
        lastRead.clear();
        notices.clear();
        alreadySaid.clear();
    }

    // Any thread.
    void say(String line) {
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

    // Any thread.
    boolean sayAt(URI atCollector, String line) {
        boolean pointed;
        synchronized (walkLock) {
            URI held = collector;
            pointed = held != null && held.equals(atCollector);
            if (pointed) {
                say(line);
            }
        }
        return pointed;
    }

    // Tick thread only; null when no line waits.
    String notice() {
        return notices.poll();
    }

    // Narrows the race around a network call; cannot close it.
    private boolean stillWalking(URI atCollector, String atServer,
                                 java.util.function.Supplier<URI> liveCollector) {
        URI live = liveCollector == null ? null : liveCollector.get();
        URI current = live != null ? live : collector;
        return atCollector != null && atCollector.equals(current)
                && atServer != null && atServer.equals(server);
    }

    boolean worthSaying() {
        return asked != null || trouble != null;
    }

    boolean asked() {
        return asked != null;
    }

    boolean askedNothing() {
        List<WorldPrint.Region> at = asked;
        return at != null && at.isEmpty();
    }

    ServerConfirmation.Ground groundFor(String nodeHost, String forServer) {
        URI with = collector;
        List<WorldPrint.Region> list = asked;
        if (with == null || nodeHost == null
                || !NodeAddress.withoutRootDot(nodeHost)
                        .equalsIgnoreCase(NodeAddress.withoutRootDot(with.getHost()))
                || forServer == null || !forServer.equals(server)) {
            return ServerConfirmation.Ground.ASKING;
        }
        if (list == null) {
            return ServerConfirmation.Ground.ASKING;
        }
        if (list.isEmpty()) {
            return ServerConfirmation.Ground.NO_REGIONS;
        }
        return proven() ? ServerConfirmation.Ground.PROVEN : ServerConfirmation.Ground.OWED;
    }

    boolean mayReask() {
        return !reasked;
    }

    boolean standingDue() {
        return standingDue;
    }

    void reask() {
        synchronized (walkLock) {
            forget();
            reasked = true;
        }
    }

    void keyEnrolled() {
        synchronized (walkLock) {
            if (standingUnproven) {
                standingUnproven = false;
                standingShut = false;
                standingDue = true;
            }
        }
    }

    // Tick thread only.
    boolean readingOpen() {
        return reading != null;
    }

    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer) {
        return ask(transport, atCollector, forServer, () -> this.collector);
    }

    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer,
                java.util.function.Supplier<URI> liveCollector) {
        return ask(transport, atCollector, forServer, liveCollector, null);
    }

    // Ground pool's worker; a null identity skips the standing ask.
    boolean ask(ShareSender.Transport transport, URI atCollector, String forServer,
                java.util.function.Supplier<URI> liveCollector, ShareSender.Identity identity) {
        if (atCollector == null || forServer == null || forServer.isBlank()) {
            return asked != null;
        }
        if (!forServer.equals(server)) {
            return asked != null;
        }
        pointedAt(atCollector);
        if (identity != null && standingDue) {
            return standingAgain(transport, atCollector, forServer, liveCollector, identity);
        }
        boolean outcome;
        ShareSender.ReplySink sink = heldReply(spareAskReply);
        try {
            boolean found = transport.fetch(
                    atCollector.resolve(WorldAsk.PATH.substring(1) + "?server="
                            + java.net.URLEncoder.encode(forServer,
                                    java.nio.charset.StandardCharsets.UTF_8)),
                    WorldAsk.MAX_BODY, sink);
            if (Thread.currentThread().isInterrupted()) {
                outcome = asked != null;
            } else if (!stillWalking(atCollector, forServer, liveCollector)) {
                outcome = asked != null;
            } else if (!found) {
                // 404: a node with no handshake.
                synchronized (walkLock) {
                    if (!stillWalking(atCollector, forServer, liveCollector)) {
                        outcome = asked != null;
                    } else {
                        asked = List.of();
                        remaining = List.of();
                        trouble = null;
                        refusedRows = List.of();
                        standingDue = false;
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
                            refusedRows = List.of();
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
                                refusedRows = List.of();
                                say(REFUSED_ROW);
                                outcome = false;
                            }
                        }
                    } else if (got.regionsCut()) {
                        outcome = cut(atCollector, forServer, liveCollector);
                    } else {
                        outcome = filed(transport, atCollector, forServer, liveCollector,
                                identity, got);
                    }
                }
                spareAsk.set(got);
            }
            spareAskReply.set(sink);
        } catch (IOException | RuntimeException unreachable) {
            synchronized (walkLock) {
                if (stillWalking(atCollector, forServer, liveCollector)) {
                    trouble = "The collector could not be asked which ground to read.";
                }
            }
            outcome = asked != null;
        }
        return outcome;
    }

    private boolean filed(ShareSender.Transport transport, URI atCollector, String forServer,
                          java.util.function.Supplier<URI> liveCollector,
                          ShareSender.Identity identity, WorldAsk.AskHolder got) {
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
                refusedRows = got.refusedNames();
                standingDue = false;
                if (!asked.isEmpty()) {
                    reasked = false;
                }
                standing = identity != null && !asked.isEmpty() && !standingShut;
                if (!standing) {
                    sayOwed();
                }
                outcome = asked != null;
            }
        }
        if (standing) {
            standingAsk(transport, atCollector, forServer, liveCollector, identity);
        }
        return outcome;
    }

    private boolean cut(URI atCollector, String forServer,
                        java.util.function.Supplier<URI> liveCollector) {
        boolean outcome;
        synchronized (walkLock) {
            if (!stillWalking(atCollector, forServer, liveCollector)) {
                outcome = asked != null;
            } else {
                trouble = OVER_CAP;
                say(OVER_CAP);
                outcome = false;
            }
        }
        return outcome;
    }

    // Caller holds walkLock.
    private void sayOwed() {
        List<WorldPrint.Region> wanted = owed();
        if (!wanted.isEmpty()) {
            say(WorldPrint.saying(wanted));
        }
        say(refusedRowReason(asked, remaining, refusedRows));
    }

    // Caller holds walkLock.
    private void sayWalk() {
        List<WorldPrint.Region> wanted = owed();
        List<String> left = remaining;
        say(wanted.isEmpty() && left != null && !left.isEmpty()
                ? unwalkable(asked, left, refusedRows)
                : WorldPrint.saying(wanted));
    }

    private void standingAsk(ShareSender.Transport transport, URI atCollector,
                             String forServer,
                             java.util.function.Supplier<URI> liveCollector,
                             ShareSender.Identity identity) {
        List<String> left = standingReply(transport, atCollector, forServer, identity);
        Outcome said = standingOutcome;
        synchronized (walkLock) {
            if (stillWalking(atCollector, forServer, liveCollector)) {
                fileStanding(said, left);
                sayOwed();
            }
        }
    }

    private boolean standingAgain(ShareSender.Transport transport, URI atCollector,
                                  String forServer,
                                  java.util.function.Supplier<URI> liveCollector,
                                  ShareSender.Identity identity) {
        List<String> left = standingReply(transport, atCollector, forServer, identity);
        Outcome said = standingOutcome;
        boolean outcome;
        synchronized (walkLock) {
            if (standingDue && stillWalking(atCollector, forServer, liveCollector)) {
                List<WorldPrint.Region> before = owed();
                fileStanding(said, left);
                if (!owed().equals(before)) {
                    sayWalk();
                }
            }
            outcome = asked != null;
        }
        return outcome;
    }

    // Caller holds walkLock; left is null unless said is LISTED.
    private void fileStanding(Outcome said, List<String> left) {
        switch (said) {
            case LISTED -> {
                remaining = left;
                standingDue = false;
            }
            case AGAIN -> standingDue = true;
            case SHUT -> {
                standingDue = false;
                standingShut = true;
            }
            case UNPROVEN -> {
                standingDue = false;
                standingShut = true;
                standingUnproven = true;
            }
        }
    }

    // Null unless a 2xx reply carries a list.
    private List<String> standingReply(ShareSender.Transport transport, URI atCollector,
                                       String forServer, ShareSender.Identity identity) {
        ShareSender.ReplySink sink = heldReply(spareStandingReply);
        StandingAsk.Body body = heldBody();
        WorldAsk.AnswerHolder said = heldStandingAnswer();
        List<String> left;
        try {
            StandingAsk.body(forServer, utc.nowMillis(), WorldPrint.nonce(), body);
            standingSigner.set(body.bytes(), body.length());
            byte[] signature = identity.signer().sign(standingSigner);
            byte[] message = SignedBatch.encodeInto(spareStandingMessage.getAndSet(null),
                    identity.credential(), signature, body.bytes(), body.length());
            ShareSender.Reply reply = transport.answer(standingEndpointFor(atCollector), message, sink);

            sink.answer(said);
            spareStandingMessage.set(message);
            spareStandingReply.set(sink);
            standingOutcome = standingOf(reply.status(), said.listed());
            left = standingOutcome == Outcome.LISTED ? said.names() : null;
        } catch (IOException | RuntimeException unreachable) {
            standingOutcome = Outcome.AGAIN;
            left = null;
        }
        spareStandingBody.set(body);
        spareStandingAnswer.set(said);
        return left;
    }

    private static Outcome standingOf(int status, boolean listed) {
        Outcome answer;
        if (successful(status)) {
            answer = listed ? Outcome.LISTED : Outcome.SHUT;
        } else if (sendAgainLater(status)) {
            answer = Outcome.AGAIN;
        } else if (status == HTTP_UNAUTHORIZED) {
            answer = Outcome.UNPROVEN;
        } else {
            answer = Outcome.SHUT;
        }
        return answer;
    }

    private static boolean successful(int status) {
        return status / HTTP_STATUS_CLASS == HTTP_SUCCESS_CLASS;
    }

    private static boolean sendAgainLater(int status) {
        return status / HTTP_STATUS_CLASS == HTTP_SERVER_ERROR_CLASS
                || status == HTTP_TOO_MANY_REQUESTS;
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

    // In the operator's order.
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

    boolean proven() {
        List<WorldPrint.Region> at = asked;
        if (at == null) {
            return false;
        }
        List<String> left = remaining;
        return WorldPrint.owesNothing(at, left) && (left == null || left.isEmpty());
    }

    String saying() {
        String why = trouble;
        List<WorldPrint.Region> at = asked;
        List<WorldPrint.Region> nowOwed = at == null ? List.of() : owed();
        List<String> left = remaining;
        List<String> refused = refusedRows;
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
                                 List<String> refused) {
        String saying;
        if (at == null) {
            saying = why == null ? "Checking what this collector asks for." : why;
        } else if (owed.isEmpty() && left != null && !left.isEmpty()) {
            saying = unwalkable(at, left, refused);
        } else {
            String instruction = flat(WorldPrint.saying(owed));
            String reason = refusedRowReason(at, left, refused);
            if (why == null || owed.isEmpty()) {
                saying = reason == null ? instruction : instruction + "\n" + reason;
            } else {
                saying = reason == null
                        ? instruction + "\n" + why
                        : instruction + "\n" + why + "\n" + reason;
            }
        }
        return saying;
    }

    // A name from a collector stays on its line; only this class puts a line break in a line.
    private static String flat(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static String refusedRowReason(List<WorldPrint.Region> list, List<String> left,
                                           List<String> refused) {
        return refusedStillListed(list, left, refused) ? REFUSED_ROW : null;
    }

    // The line for listed ground with no rectangle to walk.
    private static String unwalkable(List<WorldPrint.Region> list, List<String> left,
                                     List<String> refused) {
        return refusedStillListed(list, left, refused) ? REFUSED_ROW : NOT_TOLD;
    }

    // A null left means no answer.
    private static boolean refusedStillListed(List<WorldPrint.Region> list, List<String> left,
                                              List<String> refused) {
        boolean listed;
        if (refused.isEmpty() || list == null) {
            listed = false;
        } else if (left == null) {
            listed = true;
        } else {
            listed = namesAny(refused, left) || (holdsUnnamed(refused) && unplaced(list, left));
        }
        return listed;
    }

    // Matching ignores case, as in WorldPrint.owing.
    private static boolean namesAny(List<String> refused, List<String> left) {
        boolean found = false;
        int rows = refused.size();
        int names = left.size();
        for (int i = 0; i < rows && !found; i++) {
            String refusedName = refused.get(i);
            for (int j = 0; j < names && !found && !refusedName.isEmpty(); j++) {
                found = refusedName.equalsIgnoreCase(left.get(j));
            }
        }
        return found;
    }

    private static boolean holdsUnnamed(List<String> refused) {
        boolean found = false;
        int rows = refused.size();
        for (int i = 0; i < rows && !found; i++) {
            found = refused.get(i).isEmpty();
        }
        return found;
    }

    private static boolean unplaced(List<WorldPrint.Region> list, List<String> left) {
        boolean missing = false;
        int names = left.size();
        for (int i = 0; i < names && !missing; i++) {
            missing = !carries(list, left.get(i));
        }
        return missing;
    }

    // Matching ignores case, as in WorldPrint.owing.
    private static boolean carries(List<WorldPrint.Region> list, String name) {
        boolean found = false;
        int rows = list.size();
        for (int i = 0; i < rows && !found; i++) {
            found = list.get(i).name().equalsIgnoreCase(name);
        }
        return found;
    }

    // Tick thread only.
    void near(Level level, String atServer, String atDimension, int x, int z) {
        if (level == null || atServer == null || !atServer.equals(server)) {
            return;
        }
        if (parked.get() != null || reading != null) {
            return;
        }
        List<WorldPrint.Region> nearby = owedFor(atDimension);
        int nearbyCount = nearby.size();
        boolean armed = false;
        for (int i = 0; i < nearbyCount && !armed; i++) {
            WorldPrint.Region region = nearby.get(i);
            if (!region.holds(x, z)) {
                continue;
            }
            long since = clock.getAsLong();
            java.util.concurrent.atomic.AtomicLong last = lastRead.get(region.name());
            if (last != null && since >= last.get() && since - last.get() < READ_AGAIN_NANOS) {
                continue;
            }
            if (last == null) {
                lastRead.put(region.name(), new java.util.concurrent.atomic.AtomicLong(since));
            } else {
                last.set(since);
            }
            reading = WorldHandshake.begin(region);
            readingFor = atServer;
            readingIn = atDimension;
            readingVia = collector;
            readingLevel = level;
            armed = true;
        }
    }

    // Tick thread only.
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
                        + " Nothing is lost; the walk retries.",
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
                        String hidden = "This ground cannot be read: this server hides its bedrock. "
                                + "Ask the operator about anti-xray.";
                        readingTrouble(belongsTo, via, hidden, true);
                    } else {
                        String shortRead = "This ground was not all loaded."
                                + " Keep the whole region in view and walk it again.";
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

    // Tick thread only.
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

    // Share thread only.
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
                    answer.signature(), utc.nowMillis());
            built = true;
            proofSigner.set(body);
            byte[] signature = me.signer().sign(proofSigner);
            message = SignedBatch.encodeInto(spareMessage.getAndSet(null), me.credential(),
                    signature, body);
            spareBody.set(body);
        } catch (IOException | RuntimeException notSendable) {
            LOGGER.warn("geosurvey could not build the ground proof for the region"
                    + " {}. Nothing is lost; the walk retries.",
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
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        ShareSender.ReplySink sink = heldReply(spareReply);
        try {
            ShareSender.Reply reply = transport.answer(proofEndpointFor(atCollector),
                    message, sink);
            sink.answer(proofAnswer);
            spareMessage.set(message);
            spareReply.set(sink);

            if (stillWalking(atCollector, answer.server(), liveCollector)) {
                if (successful(reply.status()) && proofAnswer.listed()) {
                    repliedWithList(answer, atCollector, liveCollector, proofAnswer.names());
                } else {
                    repliedWithoutList(answer, atCollector, liveCollector, reply, proofAnswer);
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

    // The caller alone returns the sink to slot.
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
                standingDue = false;

                List<WorldPrint.Region> stillOwed = owed();
                String askedName = answer.region().name();
                int leftCount = left.size();
                boolean stillWanted = false;
                for (int i = 0; i < leftCount && !stillWanted; i++) {
                    stillWanted = left.get(i).equalsIgnoreCase(askedName);
                }
                if (!stillWanted) {
                    trouble = null;
                    if (stillOwed.isEmpty() && !left.isEmpty()) {
                        say(unwalkable(asked, left, refusedRows));
                    } else if (stillOwed.size() > 1) {
                        say(WorldPrint.acceptedAt(answer.region().name()));
                        say(WorldPrint.leftToWalk(stillOwed));
                    } else {
                        say(WorldPrint.accepted(answer.region().name(), stillOwed));
                    }
                } else {
                    String stillOwedLine = "The collector took that reading and still asks for "
                            + ShareCommand.drawable(askedName) + "."
                            + " Ask the operator.";
                    trouble = stillOwedLine;
                    say(stillOwedLine);
                }
            }
        }
    }

    private void repliedWithoutList(Parked answer, URI atCollector,
                                    java.util.function.Supplier<URI> liveCollector,
                                    ShareSender.Reply reply, WorldAsk.AnswerHolder said) {
        int status = reply.status();
        boolean took = successful(status) && said.accepted();
        synchronized (walkLock) {
            if (stillWalking(atCollector, answer.server(), liveCollector)) {
                if (status == NOT_FOUND_STATUS) {
                    parked.compareAndSet(answer, null);
                    asked = null;
                    remaining = null;
                    refusedRows = List.of();
                    standingDue = false;
                    String noRouteLine = "The collector has no ground route (404)."
                            + " This client asks the collector again.";
                    trouble = noRouteLine;
                    say(noRouteLine);
                } else if (took) {
                    parked.compareAndSet(answer, null);
                    String unreadableListLine = "The collector took that reading, but this client cannot read"
                            + " what is still owed. Ask the operator.";
                    trouble = unreadableListLine;
                    say(unreadableListLine);
                } else if (successful(status) && !said.refused()) {
                    parked.compareAndSet(answer, null);
                    String unreadableReplyLine = "This client cannot read the collector's reply ("
                            + status + ").";
                    trouble = unreadableReplyLine;
                    say(unreadableReplyLine);
                } else {
                    String refusalLine = "The collector did not accept that reading ("
                            + status + ").";
                    trouble = refusalLine;
                    say(refusalLine);
                    if (sendAgainLater(status)) {
                        rePark(answer);
                    } else {
                        parked.compareAndSet(answer, null);
                    }
                }
            }
        }
    }
    private void rePark(Parked answer) {
        int nextTries = answer.tries() + 1;
        if (nextTries >= MAX_POST_TRIES) {
            parked.compareAndSet(answer, null);
            say("The proof could not be sent.");
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
