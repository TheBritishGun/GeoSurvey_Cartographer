package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.claim.ClaimSplash;
import dev.openmap.claim.GreetingLearner;
import dev.openmap.claim.NodeAddress;
import dev.openmap.claim.ServerConfirmation;
import dev.openmap.claim.TitlePairing;
import dev.openmap.live.LiveSnapshot;
import dev.openmap.live.MapBackend;
import dev.openmap.share.UtcClock;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class ClaimSplashHub {

    private static ClaimSplash splash = new ClaimSplash();

    private static GreetingLearner learner = new GreetingLearner();

    private static GreetingStore store = null;

    private static final ShareSender.Keys KEYS = ShareSender.gameKeys();

    private static final ShareSender.Transport TRANSPORT = ShareSender.httpTransport();

    private static final AtomicBoolean TRANSPORT_CLOSED = new AtomicBoolean();

    private static String currentServer = "";

    private static String currentDimension = "";

    private static ClientLevel lastServerLevel = null;

    private static String lastServerAddress = null;

    private static String lastServerKey = "";

    private static TitlePairing.State pairing = TitlePairing.NONE;

    private static long tick = 0L;

    private static final long SERVER_TITLE_GRACE_NANOS =
            TimeUnit.MILLISECONDS.toNanos(ClaimSplash.DEFAULT_CHECK_PERIOD_MILLIS * 2L);

    private static long lastServerTitleAtNanos = Long.MIN_VALUE;

    private static Boolean lastSourceUp = null;

    private static String lastWorld = "";

    private static List<LiveSnapshot.Area> lastClaims = List.of();

    private static Map<String, String> labels = new HashMap<>();

    private static boolean confirmed = false;

    private static boolean confirmedLastCheck = false;

    private static boolean notesLanded = false;

    private ClaimSplashHub() {
    }

    public static void register() {
        store = new GreetingStore(LandNav.dataDir().resolve("claim-greetings.json"));
        final boolean queued;
        if (Sandpaper.isReady()) {
            queued = Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                    () -> {
                        store.loadIfNeeded();
                        return Boolean.TRUE;
                    }, done -> {
                        notesLanded = true;
                    });
        } else {
            queued = false;
        }
        if (!queued) {
            store.loadIfNeeded();
        }
        Sandpaper.scheduler().register(
                JobSpec.everyMillis(Lane.TICK, ClaimSplash.DEFAULT_CHECK_PERIOD_MILLIS)
                        .withOwner(CollectorMod.MOD_ID)
                        .withLabel("geosurvey-claim-splash"),
                t -> check());
        ClientTickEvents.END_CLIENT_TICK.register(client -> endOfTick());
        stopTheTransport();
    }

    private static void stopTheTransport() {
        if (!(TRANSPORT instanceof AutoCloseable closeable)) {
            return;
        }
        StoppableWorkers.close("geosurvey-claim-splash-transport", () -> {
            try {
                closeable.close();
            } catch (Exception refused) {
                throw new IllegalStateException(refused);
            }
            TRANSPORT_CLOSED.set(true);
        }, ignored -> TRANSPORT_CLOSED.get(), () -> TRANSPORT_CLOSED.get());
    }

    static String bareDimension(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    static String walkingName(String dialled) {
        ChunkCapture capture = ChunkCapture.live();
        return capture == null ? null : capture.uploadLabel(dialled);
    }

    static String serverKey(String dialled) {
        return ChunkCapture.serverWorldKey(dialled);
    }

    static String webMapNode(String key) {
        return NodeAddress.forMapLink(
                dev.openmap.config.LandNavConfig.KnownServers.webMapOf(key));
    }

    static boolean confirmsAt(ServerConfirmation.Ground ground, String node, String dialled) {
        if (ground == ServerConfirmation.Ground.NOT_WALKING
                || ground == ServerConfirmation.Ground.NO_REGIONS) {
            return !node.isEmpty();
        }
        return ServerConfirmation.confirmed(ground,
                dev.openmap.config.LandNavConfig.KnownServers.nameOf(dialled), dialled);
    }

    public static void serverTitle(String text) {
        pairing = TitlePairing.title(pairing, tick, text);
        ClaimSplashHud.reset();
    }

    public static void serverSubtitle(String text) {
        pairing = TitlePairing.subtitle(pairing, tick, text);
    }

    private static void endOfTick() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.level != null) {
            String server = currentServer(client);
            String dimension = bareDimension(client.level.dimension());
            if (contextChanged(server, dimension)) {
                resetContext(server, dimension);
                tick++;
                return;
            }
            recordStanding(client);
        }
        TitlePairing.Flushed flushed = flushTitles();
        if ((flushed != null) && flushed.fire() && !currentServer.isEmpty()) {
            long nowNanos = System.nanoTime();
            if (confirmed && client.player != null) {
                crossingsSeenAt(client.player.getX(), client.player.getZ(), nowNanos);
            }
            java.util.Optional<GreetingLearner.Pairing> matched = matchTitle(learner, currentServer,
                    flushed, nowNanos, splash, confirmed);
            lastServerTitleAtNanos = nowNanos;
            ClaimSplashHud.reset();
            String dimensionId = currentDimension;
            observeMatched(matched.orElse(null), store, currentServer, dimensionId, labels, learner);
        }
        tick++;
    }

    private static TitlePairing.Flushed flushTitles() {
        if (!pairing.hasPending()) {
            if (pairing != TitlePairing.NONE) {
                pairing = TitlePairing.NONE;
            }
            return null;
        }
        TitlePairing.Flushed flushed = TitlePairing.flush(pairing, tick);
        TitlePairing.State next = flushed.next();
        if (pairing != next) {
            pairing = next;
        }
        return flushed;
    }

    private static java.util.Optional<GreetingLearner.Pairing> matchTitle(GreetingLearner source,
            String server, TitlePairing.Flushed flushed, long nowNanos,
            GreetingLearner.Standing standing, boolean mayLearn) {
        return mayLearn ? source.serverText(server, flushed.title(), flushed.subtitle(), nowNanos, standing)
                : java.util.Optional.empty();
    }

    private static void observeMatched(GreetingLearner.Pairing pairingResult,
                               GreetingStore notes, String server, String dimensionId,
                               Map<String, String> claimLabels, GreetingLearner source) {
        if (pairingResult == null) {
            return;
        }
        notes.observe(server, pairingResult.world(), pairingResult.claimId(),
                pairingResult.entering(), dimensionId,
                claimLabels.getOrDefault(pairingResult.claimId(), ""), source);
    }

    private static void recordStanding(Minecraft client) {
        if (lastWorld.isEmpty()) {
            return;
        }
        learner.stoodAt(lastWorld, client.player.getX(), client.player.getZ(), lastClaims,
                System.nanoTime());
    }

    private static void crossingsSeenAt(double x, double z, long nowNanos) {
        if (lastWorld.isEmpty()) {
            return;
        }
        String dimensionId = currentDimension;
        List<ClaimSplash.Event> events = splash.check(lastWorld, x, z, lastClaims, true,
                ClaimSplash.NO_NOTES, ClaimSplash.NO_NOTES);
        for (ClaimSplash.Event event : events) {
            learnCrossing(event, currentServer, lastWorld, nowNanos, dimensionId);
        }
    }

    private static void learnCrossing(ClaimSplash.Event event, String server, String world,
                                      long nowNanos, String dimensionId) {
        boolean entering = event.kind() == ClaimSplash.Kind.ENTER;
        if (entering) {
            learner.entered(server, world, event.claimId(), nowNanos);
        } else {
            learner.left(server, world, event.claimId(), nowNanos);
        }
        store.observe(server, world, event.claimId(), entering, dimensionId,
                labels.getOrDefault(event.claimId(), ""), learner);
    }

    private static GreetingStore playerNotesCacheStore = null;

    private static String playerNotesCacheServer = null;

    private static String playerNotesCacheDimensionId = null;

    private static ClaimSplash.Notes playerNotesCacheValue = null;

    static ClaimSplash.Notes cachedPlayerNotes(GreetingStore targetStore, String server,
                                               String dimensionId) {
        if (targetStore != playerNotesCacheStore || !server.equals(playerNotesCacheServer)
                || !dimensionId.equals(playerNotesCacheDimensionId)) {
            playerNotesCacheValue = targetStore.asNotes(server, dimensionId);
            playerNotesCacheStore = targetStore;
            playerNotesCacheServer = server;
            playerNotesCacheDimensionId = dimensionId;
        }
        return playerNotesCacheValue;
    }

    static boolean effectiveDynmapUp(boolean rawUp, long titleAtNanos, long nowNanos) {
        return rawUp
                || (titleAtNanos != Long.MIN_VALUE
                        && nowNanos - titleAtNanos <= SERVER_TITLE_GRACE_NANOS);
    }

    static boolean resyncsWhileUnconfirmed(boolean confirmedNow) {
        return confirmedNow;
    }

    static List<ClaimSplash.Text> drawAndLearn(List<ClaimSplash.Event> events,
                                               boolean effectiveUp, String server, String world,
                                               long nowNanos, String dimensionId) {
        List<ClaimSplash.Text> drawn = null;
        for (ClaimSplash.Event event : events) {
            if (event.draw()) {
                if (drawn == null) {
                    drawn = new ArrayList<>();
                }
                drawn.add(event.text());
            }
            if (effectiveUp) {
                learnCrossing(event, server, world, nowNanos, dimensionId);
            }
        }
        return drawn;
    }

    private static void check() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            confirmed = false;
            confirmedLastCheck = false;
            return;
        }
        dev.openmap.config.LandNavConfig config = LandNav.config();
        if (!config.claimGreetings) {
            confirmed = false;
            confirmedLastCheck = false;
            ClaimSplashHud.reset();
            return;
        }

        String server = currentServer(client);
        String dimension = bareDimension(client.level.dimension());
        String dimensionId = dimension;
        if (contextChanged(server, dimension)) {
            resetContext(server, dimension);
        }

        String node = webMapNode(server);
        boolean confirmedNow = confirmedHere(client, node);
        confirmed = confirmedNow && confirmedLastCheck;
        confirmedLastCheck = confirmedNow;
        if (!confirmed) {
            ClaimSplashHud.reset();
        }

        LiveMapClient live = CollectorMod.live();
        long nowMillis = UtcClock.collector().nowMillis();
        long nowNanos = System.nanoTime();
        boolean rawUp = live.dynmapUp(nowNanos);
        boolean effectiveUp = effectiveDynmapUp(rawUp, lastServerTitleAtNanos, nowNanos);

        String world;
        List<LiveSnapshot.Area> claims;
        LiveSnapshot snapshot = live.snapshot();
        List<LiveSnapshot.Area> snapshotAreas = snapshot.areas();
        boolean polledSource = usesPolledSource(rawUp, snapshotAreas);
        if (polledSource) {
            world = live.polledWorld();
            claims = snapshotAreas;
        } else {
            world = nodeWorld(dimensionId);
            claims = live.nodeClaims();
        }
        if (world != null && !world.isEmpty()) {
            labels = labelsFor(world, claims);
            lastClaims = claims;

            boolean sourceChanged = lastSourceUp != null
                    && (lastSourceUp != polledSource || !world.equals(lastWorld));
            lastSourceUp = polledSource;
            lastWorld = world;
            if (sourceChanged) {
                splash.resyncInside(world, client.player.getX(), client.player.getZ(), claims);
            } else if (!confirmed) {
                if (resyncsWhileUnconfirmed(confirmedNow)) {
                    splash.resyncInside(world, client.player.getX(), client.player.getZ(), claims);
                }
            } else {
                ClaimSplash.Notes playerNotes = cachedPlayerNotes(store, server, dimensionId);
                List<dev.openmap.live.Greeting> nodeGreetings =
                        polledSource ? List.of() : live.nodeGreetings();
                ClaimSplash.Notes nodeNotes = nodeNotes(polledSource, nodeGreetings);

                double playerX = client.player.getX();
                double playerZ = client.player.getZ();
                offerLateNotes(effectiveUp, world, playerX, playerZ,
                        claims, playerNotes, nodeNotes);

                List<ClaimSplash.Event> events = splash.check(world, playerX,
                        playerZ, claims, effectiveUp, playerNotes, nodeNotes);

                List<ClaimSplash.Text> drawn = drawAndLearn(events, effectiveUp, server, world,
                        nowNanos, dimensionId);
                if (drawn != null) {
                    ClaimSplashHud.offer(drawn);
                }

                postDueIfSharing(config.shareEnabled, store, KEYS, TRANSPORT, node,
                        nowMillis);
            }
        }
    }

    static void offerLateNotes(boolean dynmapUp, String world, double x, double z,
                               List<LiveSnapshot.Area> claims, ClaimSplash.Notes playerNotes,
                               ClaimSplash.Notes nodeNotes) {
        if (!notesLanded || dynmapUp) {
            return;
        }
        notesLanded = false;
        List<ClaimSplash.Text> late = splash.refreshInside(world, x, z, claims,
                playerNotes, nodeNotes);
        if (!late.isEmpty()) {
            ClaimSplashHud.offer(late);
        }
    }

    private static boolean usesPolledSource(boolean rawUp, List<LiveSnapshot.Area> areas) {
        return rawUp || !areas.isEmpty();
    }

    private static List<LiveSnapshot.Area> labelsForLastClaims = null;

    private static String labelsForLastWorld = null;

    private static Map<String, String> labelsForLastResult = Map.of();

    private static Map<String, String> labelsFor(String world, List<LiveSnapshot.Area> claims) {
        if (claims == labelsForLastClaims && world.equals(labelsForLastWorld)) {
            return labelsForLastResult;
        }
        Map<String, String> result;
        if (world == null || world.isBlank()) {
            result = Map.of();
        } else {
            result = HashMap.newHashMap(claims.size());
            String baredWorld = dev.openmap.live.OpenMapParser.bareWorld(world);
            for (LiveSnapshot.Area claim : claims) {
                if (sameBaredWorld(claim.world(), baredWorld)) {
                    result.put(claim.id(), claim.label());
                }
            }
        }
        labelsForLastClaims = claims;
        labelsForLastWorld = world;
        labelsForLastResult = result;
        return result;
    }

    private static boolean sameBaredWorld(String stated, String baredWorld) {
        return stated != null && !stated.isBlank()
                && dev.openmap.live.OpenMapParser.bareWorld(stated).equalsIgnoreCase(baredWorld);
    }

    static void postDueIfSharing(boolean shareEnabled, GreetingStore store,
                                 ShareSender.Keys keys, ShareSender.Transport transport,
                                 String node, long nowMillis) {
        if (shareEnabled
                && NodeAddress.NODE_HOST.equalsIgnoreCase(
                        NodeAddress.host(LandNav.config().shareCollector))) {
            store.postDue(keys, transport, node, currentServer, currentDimension, nowMillis);
        }
    }

    private static List<dev.openmap.live.Greeting> nodeNotesIndexedGreetings = null;

    private static ClaimSplash.Notes nodeNotesIndexed = null;

    private static ClaimSplash.Notes nodeNotes(boolean polledSource,
                                               List<dev.openmap.live.Greeting> nodeGreetings) {
        if (polledSource) {
            return ClaimSplash.NO_NOTES;
        }
        if (nodeGreetings != nodeNotesIndexedGreetings) {
            nodeNotesIndexed = nodeNotesIndex(nodeGreetings);
            nodeNotesIndexedGreetings = nodeGreetings;
        }
        return nodeNotesIndexed;
    }

    private static ClaimSplash.Notes nodeNotesIndex(List<dev.openmap.live.Greeting> nodeGreetings) {
        Map<String, ClaimSplash.Text> enterIndex = HashMap.newHashMap(nodeGreetings.size());
        Map<String, ClaimSplash.Text> leaveIndex = HashMap.newHashMap(nodeGreetings.size());
        for (dev.openmap.live.Greeting greeting : nodeGreetings) {
            indexOneDirection(enterIndex, greeting.id(), greeting.greeting(), greeting.greetingSub());
            indexOneDirection(leaveIndex, greeting.id(), greeting.farewell(), greeting.farewellSub());
        }
        return (claimId, entering) -> (entering ? enterIndex : leaveIndex).get(claimId);
    }

    private static void indexOneDirection(Map<String, ClaimSplash.Text> index, String claimId,
                                          String title, String subtitle) {
        if (index.containsKey(claimId) || title.isEmpty()) {
            return;
        }
        index.put(claimId, new ClaimSplash.Text(title, subtitle));
    }

    private static boolean contextChanged(String server, String dimension) {
        return !server.equals(currentServer) || !dimension.equals(currentDimension);
    }

    private static void resetContext(String server, String dimension) {
        splash = new ClaimSplash();
        learner = new GreetingLearner();
        pairing = TitlePairing.NONE;
        lastServerTitleAtNanos = Long.MIN_VALUE;
        lastSourceUp = null;
        lastWorld = "";
        labels = new HashMap<>();
        lastClaims = List.of();
        confirmed = false;
        confirmedLastCheck = false;
        ClaimSplashHud.reset();
        currentServer = server;
        currentDimension = dimension;
    }

    private static boolean confirmedHere(Minecraft client, String node) {
        ShareSender sender = ShareSender.live();
        ServerData serverData = client.getCurrentServer();
        String ip = serverData == null || serverData.ip.isBlank() ? null : serverData.ip;
        String walking = walkingName(ip);
        ServerConfirmation.Ground ground = sender == null || node.isEmpty()
                ? ServerConfirmation.Ground.NOT_WALKING
                : sender.groundFor(NodeAddress.NODE_HOST, walking);
        return confirmsAt(ground, node, ip);
    }

    private static String nodeWorldLastDimension = null;

    private static String nodeWorldLastResult = null;

    private static String nodeWorld(String dimension) {
        if (!dimension.equals(nodeWorldLastDimension)) {
            List<String> known = MapBackend.OPENMAP.defaultWorlds().worldsFor(dimension);
            nodeWorldLastResult = known.isEmpty() ? null : known.get(0);
            nodeWorldLastDimension = dimension;
        }
        return nodeWorldLastResult;
    }

    private static String currentServer(Minecraft client) {
        if (client.level == null) {
            lastServerLevel = null;
            lastServerAddress = null;
            lastServerKey = "";
            return "";
        }
        ServerData serverData = client.getCurrentServer();
        String rawAddress = serverData == null ? null : serverData.ip;
        boolean addressChanged = rawAddress == null
                ? lastServerAddress != null
                : !rawAddress.equals(lastServerAddress);
        if (client.level != lastServerLevel || addressChanged) {
            lastServerLevel = client.level;
            lastServerAddress = rawAddress;
            lastServerKey = rawAddress != null && !rawAddress.isBlank()
                    ? serverKey(rawAddress)
                    : "singleplayer";
        }
        return lastServerKey;
    }
}
