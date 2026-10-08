package dev.openmap.client;

import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.Background;
import dev.sandpaper.core.WorkPool;
import dev.openmap.LandNav;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.ChunkSample;
import dev.openmap.map.ChunkSource;
import dev.openmap.map.MapStorage;
import dev.openmap.map.MapStore;
import dev.openmap.share.CardCheck;
import dev.openmap.share.Directory;
import dev.openmap.share.MapCard;
import dev.openmap.share.SpawnPrint;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelData;

public final class CollectorFinder {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final AtomicBoolean ASKING = new AtomicBoolean();

    private static final AtomicReference<Search> RUNNING = new AtomicReference<>();

    private static final long CHECK_TIMEOUT_MILLIS = 3_000L;

    private static final AtomicLong LATEST_CHECK = new AtomicLong();

    private static volatile Consumer<Runnable> completionRunnerForTests;

    private static final int FIXED_SEED_COUNT = 4;

    private static final int LINES_BESIDE_ADDRESSES = 8;

    record Search(WorkPool.Receipt receipt, ClientPacketListener session) {
    }

    record ClientRead(String joined, String spawnNote, Path dataDir, FreshGround fresh, MapCard.Scan cards,
            String collector, String directorySeed) {
    }

    record FreshGround(String storedDimension, Map<Long, ChunkSample> byChunk) {

        static final FreshGround NONE = new FreshGround("", Map.of());

        ChunkSample at(String stored, int chunkX, int chunkZ) {
            if (byChunk.isEmpty() || !stored.equals(storedDimension)) {
                return null;
            }
            return byChunk.get(MapStore.key(chunkX, chunkZ));
        }
    }

    private static final String CUT_SHORT =
            "The search stopped early.";

    private CollectorFinder() {
    }

    // Returns at once; a thread searches.
    public static int offer(FabricClientCommandSource source, LandNavConfig config) {
        ShareSender sender = ShareSender.live();
        int result;
        if (sender == null) {
            say(source, "Join a server first.");
            result = 0;
        } else {
            WorkPool pool = Sandpaper.workPool();
            ClientPacketListener session = currentSession();
            releaseIfDroppedUnrun();
            releaseIfLeft(session);
            if (!ASKING.compareAndSet(false, true)) {
                say(source, "One search runs. Wait for it.");
                result = 0;
            } else {
                ClientRead client = readClient(config);
                WorkPool.Receipt receipt = new WorkPool.Receipt();
                Search mine = new Search(receipt, session);
                RUNNING.set(mine);
                boolean taken = pool.submit(CollectorMod.MOD_ID, () -> {
                    try {
                        run(source, session, client, config, sender, mine);
                    } catch (RuntimeException whatever) {
                        List<String> stopped = stoppedPartWay(whatever);
                        speakLater(source, session, () -> speak(source, stopped));
                    } finally {
                        release(mine);
                    }
                    return Boolean.TRUE;
                }, done -> { }, receipt);
                if (!taken) {
                    release(mine);
                    say(source, "Nothing asked: every worker is busy."
                            + " Try again"
                            + " shortly.");
                    result = 0;
                } else {
                    result = 1;
                }
            }
        }
        return result;
    }

    static int check(FabricClientCommandSource source, LandNavConfig config, String typed,
                     ShareCommand.Persist persist) {
        ShareCommand.CollectorAddress requested = ShareCommand.collectorAddress(typed);
        if (!requested.accepted()) {
            say(source, new ShareCommand.Answer(false, requested.refusal()));
            return 0;
        }
        ShareSender sender = ShareSender.live();
        if (sender == null) {
            say(source, new ShareCommand.Answer(false,
                    "Join a server first."));
            return 0;
        }
        long number = LATEST_CHECK.incrementAndGet();
        say(source, "GeoSurvey checks that address.");
        check(sender, requested.address(), answered -> completeOnClientThread(
                () -> finishCheck(source, config, requested.address(), persist, number, answered)));
        return 1;
    }

    private static void finishCheck(FabricClientCommandSource source, LandNavConfig config,
                                    String address, ShareCommand.Persist persist, long number,
                                    boolean answered) {
        if (number != LATEST_CHECK.get()) {
            return;
        }
        if (!answered) {
            say(source, new ShareCommand.Answer(false,
                    "Nothing was set. That address does not answer as a collector."));
            return;
        }
        ShareCommand.Answer result = ShareCommand.collector(config, address, persist);
        say(source, result);
    }

    interface Answered {

        void accept(boolean answered);
    }

    static void check(ShareSender sender, String address, CollectorAddressCheck waiting) {
        check(sender, address, answered -> completeOnClientThread(
                () -> waiting.heard(address, answered)));
    }

    static String askOrigin(String address) {
        URI endpoint = ShareSender.endpointOf(address, "");
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null
                || endpoint.getHost().isEmpty()) {
            return address;
        }
        StringBuilder origin = new StringBuilder();
        origin.append(endpoint.getScheme()).append("://").append(endpoint.getHost());
        if (endpoint.getPort() >= 0) {
            origin.append(':').append(endpoint.getPort());
        }
        return origin.toString();
    }

    static void runWithCompletionRunnerForced(Consumer<Runnable> runner, Runnable work) {
        completionRunnerForTests = runner;
        try {
            work.run();
        } finally {
            completionRunnerForTests = null;
        }
    }

    private static void run(FabricClientCommandSource source, LandNavConfig config,
                            ShareSender sender) {
        run(source, currentSession(), readClient(config), config, sender, null);
    }

    private static void check(ShareSender sender, String address, Answered result) {
        Thread checking = Background.thread(() -> {
            try {
                result.accept(answers(sender, address));
            } catch (RuntimeException failed) {
                LOGGER.warn("collector check stopped", failed);
            }
        }, "geosurvey-collector-check");
        StoppableWorkers.thread("geosurvey-collector-check", checking);
        checking.start();
    }

    private static boolean answers(ShareSender sender, String address) {
        AtomicReference<Directory.Found> found = new AtomicReference<>(Directory.Found.nothing());
        Thread probe = Background.thread(() -> {
            try {
                found.set(sender.discover(List.of(askOrigin(address))));
            } catch (RuntimeException failed) {
                LOGGER.warn("collector check could not read the reply", failed);
            }
        }, "geosurvey-collector-probe");
        StoppableWorkers.thread("geosurvey-collector-probe", probe);
        probe.start();
        boolean overdue;
        boolean answered;
        try {
            probe.join(CHECK_TIMEOUT_MILLIS);
            overdue = probe.isAlive();
            answered = !overdue && !found.get().answered().isEmpty();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            overdue = true;
            answered = false;
        }
        if (overdue) {
            probe.interrupt();
        }
        return answered;
    }

    private static void completeOnClientThread(Runnable completion) {
        Consumer<Runnable> runner = completionRunnerForTests;
        if (runner != null) {
            runner.accept(completion);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(completion);
        }
    }

    private static void run(FabricClientCommandSource source, ClientPacketListener session,
                            ClientRead client, LandNavConfig config, ShareSender sender,
                            Search mine) {
        CardScan scan = scanCards(client);
        List<String> spoken = new ArrayList<>(cardLines(scan));
        String known = ChunkCapture.knownCollector(scan.server());
        if (!known.isEmpty()) {
            spoken.add(0, "This server's known collector: " + known + ".");
        }
        List<String> seeds = seeds(client.collector(), CardCheck.usableOrigins(scan.results()), scan.server(),
                client.directorySeed());
        if (seeds.isEmpty()) {
            spoken.add("There is no address to ask. Paste a map card, or set one"
                    + " with /geosurvey collector <address>.");
            speakLater(source, session, () -> speak(source, spoken));
        } else {
            spoken.add("Asking " + seeds.size() + " addresses which collectors they"
                    + " publish. No position or player list goes with it.");
            speakProgress(source, session, spoken);
            if (!abandoned(mine)) {
                Directory.Found found = sender.discover(seeds, () -> abandoned(mine));
                speakLater(source, session, () -> speakAnswer(source, found, config));
            }
        }
    }

    private static boolean abandoned(Search mine) {
        return mine != null && RUNNING.get() != mine;
    }

    private static void speakProgress(FabricClientCommandSource source,
                                      ClientPacketListener session, List<String> lines) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (currentSession() == session) {
                    speak(source, lines);
                }
            });
        }
    }

    private static void speakLater(FabricClientCommandSource source,
                                   ClientPacketListener session, Runnable speech) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (currentSession() == session) {
                    speech.run();
                    return;
                }
                say(source, "That search was for a world you left. Its answer"
                        + " is not shown.");
            });
        }
    }

    private static void speakAnswer(FabricClientCommandSource source, Directory.Found found,
                                    LandNavConfig config) {
        List<String> said;
        try {
            said = lines(found, config);
        } catch (RuntimeException whatever) {
            said = stoppedPartWay(whatever);
        }
        speak(source, said);
    }

    private static List<String> stoppedPartWay(RuntimeException whatever) {
        LOGGER.warn("collector find failed", whatever);
        return List.of("The search failed."
                + " The client log has the"
                + " detail.");
    }

    private static ClientPacketListener currentSession() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.getConnection();
    }

    private static ClientRead readClient(LandNavConfig config) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return new ClientRead(null, "", null, FreshGround.NONE, MapCard.scan(config.shareMapCards),
                    config.shareCollector, config.shareDirectorySeed);
        }
        File game = client.gameDirectory;
        Path dataDir = game == null ? null : game.toPath().resolve(LandNav.DATA_DIR);
        String joined = ChunkCapture.shareServer(client);
        String spawn = spawnNote(client);
        ChunkCapture live = ChunkCapture.live();
        MapCard.Scan cards = MapCard.scan(config.shareMapCards);
        return new ClientRead(joined, spawn, dataDir, freshGround(live, cards.reads()), cards,
                config.shareCollector, config.shareDirectorySeed);
    }

    private static void releaseIfDroppedUnrun() {
        Search held = RUNNING.get();
        if (held == null || !held.receipt().isDroppedUnrun()) {
            return;
        }
        if (!RUNNING.compareAndSet(held, null)) {
            return;
        }
        ASKING.set(false);
    }

    static void worldLeft() {
        releaseIfLeft(currentSession());
    }

    private static void releaseIfLeft(ClientPacketListener now) {
        Search held = RUNNING.get();
        if (held == null || held.session() == now) {
            return;
        }
        if (!RUNNING.compareAndSet(held, null)) {
            return;
        }
        ASKING.set(false);
    }

    private static void release(Search mine) {
        Search held = RUNNING.get();
        if (held != mine || !RUNNING.compareAndSet(held, null)) {
            return;
        }
        ASKING.set(false);
    }

    static List<String> seeds(LandNavConfig config) {
        return seeds(config, List.of());
    }

    // Most likely first; fromCards: matched cards only.
    static List<String> seeds(LandNavConfig config, List<String> fromCards) {
        Minecraft client = Minecraft.getInstance();
        String server = client == null ? null : ChunkCapture.shareServer(client);
        return seeds(config, fromCards, server);
    }

    static List<String> seeds(LandNavConfig config, List<String> fromCards, String server) {
        return seeds(config.shareCollector, fromCards, server, config.shareDirectorySeed);
    }

    private static List<String> seeds(String collector, List<String> fromCards, String server,
                                      String directorySeed) {
        String[] candidates = new String[FIXED_SEED_COUNT + fromCards.size()];
        candidates[0] = ChunkCapture.knownCollector(server);
        candidates[1] = collector;
        int at = 2;
        for (String card : fromCards) {
            candidates[at++] = card;
        }
        candidates[at++] = CollectorGuess.forServer(server);
        candidates[at] = directorySeed;
        return Directory.seeds(candidates);
    }

    // results has 1 entry per readable card; server and spawnNote are never null.
    record CardScan(List<MapCard.Read> reads, List<CardCheck.Result> results,
            String server, String spawnNote, int entries) {

        CardScan(List<MapCard.Read> reads, List<CardCheck.Result> results,
                String server, String spawnNote) {
            this(reads, results, server, spawnNote, reads.size());
        }

        static CardScan nothing() {
            return new CardScan(List.of(), List.of(), "", "", 0);
        }
    }

    // Off a server every card reads NO_MAP.
    static CardScan scanCards(LandNavConfig config) {
        return scanCards(readClient(config));
    }

    static CardScan scanCards(ClientRead client) {
        String joined = client.joined();
        String server = joined == null ? "" : joined;
        MapCard.Scan parsed = client.cards();
        List<MapCard.Read> reads = parsed.reads();
        CardScan scan;
        if (reads.isEmpty()) {
            scan = new CardScan(List.of(), List.of(), server, "");
        } else {
            boolean onServer = joined != null;
            Function<String, ChunkSource> survey =
                    onServer ? surveyOf(client.dataDir(), server, client.fresh()) : null;
            List<CardCheck.Result> results = new ArrayList<>(reads.size());
            for (MapCard.Read read : reads) {
                if (!read.ok()) {
                    continue;
                }
                if (survey != null) {
                    results.add(CardCheck.of(read.card(), survey.apply(read.card().dimension())));
                } else if (onServer) {
                    results.add(new CardCheck.Result(read.card(), CardCheck.Verdict.NOT_SURVEYED,
                            SpawnPrint.Agreement.NONE));
                } else {
                    results.add(CardCheck.of(read.card(), null));
                }
            }
            scan = new CardScan(reads, results, server, client.spawnNote(), parsed.entries());
        }
        return scan;
    }

    // Null when nothing is saved.
    static Function<String, ChunkSource> surveyOf(Path dataDir, String server) {
        return surveyOf(dataDir, server, FreshGround.NONE);
    }

    static Function<String, ChunkSource> surveyOf(Path dataDir, String server,
                                                  FreshGround fresh) {
        if (dataDir == null) {
            return null;
        }
        Path root = ChunkCapture.serverWorldDir(dataDir, server);
        if (!Files.isDirectory(root)) {
            return null;
        }
        MapStorage storage = new MapStorage();
        storage.drivenByAWorker();
        storage.setLegacyColourConverter(LandCoverClassifier::fromLegacyColour);
        storage.setRoot(root);
        return dimension -> {
            String stored = MapStorage.asStored(dimension);
            MapStore disk = storage.store(stored);
            ChunkSource ground;
            if (fresh.byChunk().isEmpty()) {
                ground = disk;
            } else {
                ground = (chunkX, chunkZ) -> {
                    ChunkSample sample = fresh.at(stored, chunkX, chunkZ);
                    return sample != null ? sample : disk.get(chunkX, chunkZ);
                };
            }
            return ground;
        };
    }

    static FreshGround freshGround(ChunkCapture live, List<MapCard.Read> reads) {
        if (live == null || reads.isEmpty()) {
            return FreshGround.NONE;
        }
        String stored = live.currentDimension();
        if (stored.isEmpty()) {
            return FreshGround.NONE;
        }
        Map<Long, ChunkSample> byChunk = null;
        MapStore resident = null;
        boolean noActiveStore = false;
        Iterator<MapCard.Read> each = reads.iterator();
        while (!noActiveStore && each.hasNext()) {
            MapCard.Read read = each.next();
            if (!read.ok() || !MapStorage.asStored(read.card().dimension()).equals(stored)) {
                continue;
            }
            if (resident == null) {
                resident = live.activeStore();
                noActiveStore = resident == null;
            }
            if (!noActiveStore) {
                MapCard card = read.card();
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        ChunkSample sample = resident.peek(card.anchorX() + dx, card.anchorZ() + dz);
                        if (sample != null) {
                            if (byChunk == null) {
                                byChunk = new HashMap<>();
                            }
                            byChunk.put(MapStore.key(sample.chunkX, sample.chunkZ), sample);
                        }
                    }
                }
            }
        }
        return byChunk == null ? FreshGround.NONE : new FreshGround(stored, byChunk);
    }

    private static String spawnNote(Minecraft client) {
        ClientLevel level = client == null ? null : client.level;
        if (level == null) {
            return "";
        }
        LevelData.RespawnData spawn = level.getRespawnData();
        if (spawn == null || spawn.equals(LevelData.RespawnData.DEFAULT)) {
            return "";
        }
        return "Server spawn: chunk " + (spawn.pos().getX() >> ChunkSource.CHUNK_SHIFT)
                + ", " + (spawn.pos().getZ() >> ChunkSource.CHUNK_SHIFT) + " in "
                + spawn.dimension().identifier() + ".";
    }

    static List<String> cardLines(CardScan scan) {
        if (scan == null || scan.reads().isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        int matched = 0;
        for (CardCheck.Result result : scan.results()) {
            if (result != null && result.usable()) {
                matched++;
            }
        }
        int notRead = scan.entries() - scan.reads().size();
        out.add("Map cards: " + scan.entries() + " pasted, "
                + (notRead > 0 ? notRead + " not read, " : "") + matched
                + " matching the ground here.");
        int at = 0;
        boolean anyMissed = false;
        for (MapCard.Read read : scan.reads()) {
            if (!read.ok()) {
                out.add("  \"" + ShareCommand.drawable(read.text())
                        + "\" is not a map card. " + read.fault().reason());
            } else {
                CardCheck.Result result = at < scan.results().size()
                        ? scan.results().get(at) : null;
                at++;
                if (result != null) {
                    out.add("  " + ShareCommand.drawable(read.card().origin()) + "  "
                            + result.verdict().reason());
                    if (result.verdict() == CardCheck.Verdict.MISMATCHED
                            || result.verdict() == CardCheck.Verdict.NEAR) {
                        anyMissed = true;
                        out.add("    Card anchor: chunk " + read.card().anchorX() + ", "
                                + read.card().anchorZ() + " in "
                                + ShareCommand.drawable(read.card().dimension())
                                + ". Ground agreement: heights "
                                + result.agreement().levels() + " of " + SpawnPrint.PANEL
                                + ", covers " + result.agreement().covers() + " of "
                                + SpawnPrint.PANEL + ".");
                    }
                }
            }
        }
        if (anyMissed && !scan.spawnNote().isEmpty()) {
            out.add("  " + scan.spawnNote());
        }
        if (matched > 0) {
            out.add("A matching card adds its address to the list below."
                    + " It switches nothing on.");
        }
        return out;
    }

    static List<String> lines(Directory.Found found, LandNavConfig config) {
        List<String> out = new ArrayList<>(found.published().size() + found.answered().size()
                + LINES_BESIDE_ADDRESSES);
        if (found.isEmpty()) {
            out.add("No address answered as a collector.");
            if (found.cutShort()) {
                out.add(CUT_SHORT);
            }
            out.add("Ask the server operator for an address, then run"
                    + " /geosurvey collector <address>.");
        } else {
            out.add("Each address below would receive your position,"
                    + " not other players.");
            if (found.cutShort()) {
                out.add(CUT_SHORT);
            }
            String mine = Directory.tidy(config.shareCollector);
            if (!found.published().isEmpty()) {
                out.add("The mesh published " + found.published().size() + ":");
                for (String address : found.published()) {
                    out.add(mark(address, mine));
                }
            }

            if (!found.answered().isEmpty()) {
                List<String> saidNothing = new ArrayList<>();
                List<String> ownList = new ArrayList<>();
                List<String> alreadyAbove = new ArrayList<>();
                for (String address : found.answered()) {
                    if (found.contributedNothing().contains(address)) {
                        saidNothing.add(address);
                    } else if (found.published().contains(address)) {
                        alreadyAbove.add(address);
                    } else {
                        ownList.add(address);
                    }
                }
                if (!saidNothing.isEmpty()) {
                    out.add("These answered as collectors and published no list, "
                            + saidNothing.size() + ":");
                    for (String address : saidNothing) {
                        out.add(mark(address, mine));
                    }
                }
                if (!ownList.isEmpty()) {
                    out.add("These answered as collectors; their list omits"
                            + " them, " + ownList.size() + ":");
                    for (String address : ownList) {
                        out.add(mark(address, mine));
                    }
                }
                if (!alreadyAbove.isEmpty()) {
                    out.add("These answered as collectors and already appear above, "
                            + alreadyAbove.size() + ":");
                    for (String address : alreadyAbove) {
                        out.add(mark(address, mine));
                    }
                }
            }
            out.add("To use one, run /geosurvey collector <address>,"
                    + " then /geosurvey share on.");
            out.add("A collector can refuse you without saying so."
                    + " Nothing here tries another address.");
        }
        return out;
    }

    private static String mark(String address, String mine) {
        return "  " + address + (address.equals(mine) ? "  (current)" : "");
    }

    private static void speak(FabricClientCommandSource source, List<String> lines) {
        for (String line : lines) {
            say(source, line);
        }
    }

    private static void say(FabricClientCommandSource source, String line) {
        source.sendFeedback(
                Component.literal(line).withStyle(ChatFormatting.GRAY));
    }

    private static void say(FabricClientCommandSource source, ShareCommand.Answer answer) {
        String text = answer.ok() ? answer.text() : "ERROR " + answer.text();
        ShareCommand.eachLine(text, line -> say(source, line));
    }
}
