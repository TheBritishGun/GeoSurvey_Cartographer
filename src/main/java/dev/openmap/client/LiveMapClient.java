package dev.openmap.client;

import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import dev.sandpaper.core.Priority;
import dev.openmap.LandNav;
import dev.openmap.claim.DynmapSignal;
import dev.openmap.claim.NodeAddress;
import dev.openmap.config.LandNavConfig;
import dev.openmap.live.DynmapParser;
import dev.openmap.live.Greeting;
import dev.openmap.live.LiveSnapshot;
import dev.openmap.live.MapBackend;
import dev.openmap.live.OpenMapParser;
import dev.openmap.live.PollSchedule;
import dev.openmap.live.WorldMapping;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

public final class LiveMapClient {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    










    private static final long FETCH_DEADLINE_NANOS = Duration.ofSeconds(30).toNanos();

    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static final int MAX_REDIRECTS = 3;

    






    private static final long MARKERS_REFRESH_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private volatile LiveSnapshot snapshot = LiveSnapshot.EMPTY;
    private volatile boolean inFlight;
    private volatile String status = "";

    
    private volatile boolean stopped;

    private PollSchedule schedule = new PollSchedule(PollSchedule.clampInterval(0));

    private long configuredIntervalMillis = PollSchedule.clampInterval(0);

    private final PollFaultStreak pollFault = new PollFaultStreak();

    















    private long markersFetchedAtNanos = Long.MIN_VALUE;

    private volatile String connection = "";

    






    private volatile String polledDimension = "";

    





    private volatile String polledWorld = "";

    private String polledBase = "";

    private MapBackend polledBackend = MapBackend.DYNMAP;

    





    private volatile long dynmapLastSuccessAtNanos = Long.MIN_VALUE;

    
    private volatile long dynmapLastFailureAtNanos = Long.MIN_VALUE;

    private volatile List<LiveSnapshot.Area> nodeAreas = List.of();

    private volatile List<Greeting> nodeGreetings = List.of();

    









    private volatile NodeIssued nodeIssued;

    private volatile boolean nodeInFlight;

    







    private long nodeFetchedAtNanos = Long.MIN_VALUE;

    private long nodeAnsweredAtNanos = Long.MIN_VALUE;

    private Handle handle;

    private volatile HttpClient http;

    private final AtomicReference<CompletableFuture<HttpClient>> httpBuild =
            new AtomicReference<>();

    


























    public LiveSnapshot snapshot() {
        return snapshotFor(currentDimension());
    }

    





















    public LiveSnapshot snapshotFor(String drawing) {
        LiveSnapshot held = snapshot;
        if (!currentConnection().equals(connection)
                || !currentDimension().equals(polledDimension)
                || !dev.openmap.live.OpenMapParser.sameWorld(drawing, polledDimension)) {
            return LiveSnapshot.EMPTY;
        }
        return isStale(held, System.nanoTime()) ? LiveSnapshot.EMPTY : held;
    }

    







    private static boolean isStale(LiveSnapshot held, long nowNanos) {
        return !held.hasFreshData(nowNanos);
    }

    










    



    public String polledWorld() {
        return polledWorld;
    }

    











    public boolean dynmapUp(long nowNanos) {
        return DynmapSignal.up(dynmapLastSuccessAtNanos, dynmapLastFailureAtNanos, nowNanos);
    }

    




    public List<LiveSnapshot.Area> nodeClaims() {
        return nodeCopyCurrent() ? nodeAreas : List.of();
    }

    
    public List<Greeting> nodeGreetings() {
        return nodeCopyCurrent() ? nodeGreetings : List.of();
    }

    














    private boolean nodeCopyCurrent() {
        NodeIssued issued = nodeIssued;
        if (issued == null) {
            return false;
        }
        if (!DynmapSignal.nodeCopyFresh(nodeAnsweredAtNanos, System.nanoTime())) {
            return false;
        }
        String base = dev.openmap.config.LandNavConfig.KnownServers.webMapOf(currentConnection());
        return issued.connection().equals(currentConnection())
                && issued.dimension().equals(currentDimension())
                && issued.nodeAddress().equals(NodeAddress.forMapLink(base));
    }

    








    private void say(String problem) {
        if (problem.equals(status)) {
            return;
        }
        status = problem;
        if (!problem.isEmpty()) {
            LOGGER.warn("Live map: {}", problem);
        }
    }

    










    public void register() {
        handle = Sandpaper.scheduler().register(
                JobSpec.everyMillis(Lane.TICK, schedule.intervalMillis())
                        .withOwner(CollectorMod.MOD_ID)
                        .withPriority(Priority.LOW)
                        .withLabel("geosurvey-live-map"),
                tick -> tickPoll());
    }

    













    void unregister() {
        stopped = true;
        if (handle != null) {
            handle.cancel();
            handle = null;
        }
        HttpClient existing = http;
        if (existing != null) {
            existing.close();
        }
    }

    public void reset() {
        snapshot = LiveSnapshot.EMPTY;
        status = "";
        markersFetchedAtNanos = Long.MIN_VALUE;
        polledWorld = "";
        nodeAreas = List.of();
        nodeGreetings = List.of();
        nodeIssued = null;
        nodeFetchedAtNanos = Long.MIN_VALUE;
        nodeAnsweredAtNanos = Long.MIN_VALUE;
        dynmapLastSuccessAtNanos = Long.MIN_VALUE;
        dynmapLastFailureAtNanos = Long.MIN_VALUE;
        configuredIntervalMillis = PollSchedule.clampInterval(0);
        schedule = new PollSchedule(configuredIntervalMillis);
        applyInterval();
    }

    





    private void applyInterval() {
        Handle job = handle;
        if (job != null) {
            job.setPeriodMillis(schedule.intervalMillis());
        }
    }

    















    private void tickPoll() {
        if (stopped) {
            return;
        }
        boolean threw = false;
        try {
            String here = currentConnection();
            String dimension = currentDimension();
            LandNavConfig config = LandNav.config();
            String base = dev.openmap.config.LandNavConfig.KnownServers.webMapOf(here);
            MapBackend backend = MapBackend.DYNMAP;
            String world = worldFor(backend, dimension);
            if (identityMoved(here, dimension, base, backend, world)) {
                applyIdentity(here, dimension, base, backend, world);
            }
            
            
            
            
            
            long nowMillis = System.currentTimeMillis();
            long nowNanos = System.nanoTime();
            long intervalMillis = PollSchedule.clampInterval(0);
            if (intervalMillis != configuredIntervalMillis) {
                configuredIntervalMillis = intervalMillis;
                schedule = new PollSchedule(intervalMillis);
                applyInterval();
            }
            
            boolean onConfiguredServer = pollNodeCopyIfNeeded(config, dimension, base, nowNanos);
            boolean markersDue = markersFetchedAtNanos == Long.MIN_VALUE
                    || nowNanos - markersFetchedAtNanos > MARKERS_REFRESH_INTERVAL_NANOS;
            if (!markersDue || !config.claimGreetings || base.isEmpty() || inFlight) {
                return;
            }
            if (!onConfiguredServer) {
                return;
            }
            if (world == null) {
                return;
            }

            inFlight = true;
            Poll poll = new Poll(true);

            Issued issued = new Issued(connection, dimension, world, base, backend);
            boolean queued = Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                    () -> fetch(backend, base, world, poll, nowMillis, nowNanos),
                    result -> applyResult(issued, poll, result));
            if (!queued) {
                inFlight = false;
            }
        } catch (RuntimeException failed) {
            inFlight = false;
            threw = true;
            if (pollFault.startOfStreak()) {
                LOGGER.warn("The live-map poll threw; it tries again next"
                        + " tick. Further throws are not logged until a"
                        + " tick runs"
                        + " clean.", failed);
            }
        } finally {
            if (!threw) {
                pollFault.clear();
            }
        }
    }

    private boolean identityMoved(String here, String dimension, String base,
                                  MapBackend backend, String world) {
        
        
        
        
        return !here.equals(connection) || !dimension.equals(polledDimension)
                || !base.equals(polledBase) || backend != polledBackend
                || (world == null ? !polledWorld.isEmpty() : !world.equals(polledWorld));
    }

    private void applyIdentity(String here, String dimension, String base,
                               MapBackend backend, String world) {
        
        
        
        
        
        reset();
        connection = here;
        polledDimension = dimension;
        polledBase = base;
        polledBackend = backend;
        polledWorld = world == null ? "" : world;
    }

    









    
    private boolean pollNodeCopyIfNeeded(LandNavConfig config, String dimension, String base,
                                         long nowNanos) {
        
        
        
        
        if (!config.claimGreetings || base.isEmpty()) {
            return false;
        }
        if (dynmapUp(nowNanos) || nodeInFlight
                || !DynmapSignal.nodeFetchDue(nodeFetchedAtNanos, nowNanos)) {
            return true;
        }
        String node = NodeAddress.forMapLink(base);
        if (node.isEmpty()) {
            return true;
        }
        List<String> worlds = MapBackend.OPENMAP.defaultWorlds().worldsFor(dimension);
        if (worlds.isEmpty()) {
            return true;
        }
        String nodeWorld = worlds.get(0);
        String url = MapBackend.join(node, MapBackend.LIVE_FILE);
        nodeInFlight = true;
        NodeIssued issued = new NodeIssued(connection, dimension, node);
        
        
        
        boolean queued;
        try {
            queued = Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                    () -> {
                        try {
                            return parseNodeCopy(get(url), nodeWorld);
                        } finally {
                            nodeInFlight = false;
                        }
                    },
                    copy -> applyNodeResult(issued, copy));
        } catch (RuntimeException failed) {
            nodeInFlight = false;
            throw failed;
        }
        if (queued) {
            
            
            
            
            nodeFetchedAtNanos = nowNanos;
        } else {
            nodeInFlight = false;
        }
        return true;
    }

    
    private static OpenMapParser.NodeCopy parseNodeCopy(String body, String nodeWorld) {
        if (body == null) {
            return null;
        }
        return OpenMapParser.nodeCopy(body, nodeWorld);
    }

    private void applyNodeResult(NodeIssued issued, OpenMapParser.NodeCopy copy) {
        if (stopped) {
            return;
        }
        if (copy == null) {
            return;
        }
        
        
        
        
        
        
        
        if (!issued.connection().equals(connection)
                || !issued.dimension().equals(polledDimension)
                || !issued.nodeAddress().equals(NodeAddress.forMapLink(
                        dev.openmap.config.LandNavConfig.KnownServers.webMapOf(connection)))) {
            return;
        }
        nodeAreas = copy.areas();
        nodeGreetings = copy.greetings();
        nodeIssued = issued;
        nodeAnsweredAtNanos = System.nanoTime();
    }

    









    private record Issued(String connection, String dimension, String world, String base,
                          MapBackend backend) {
    }

    






    private record NodeIssued(String connection, String dimension, String nodeAddress) {
    }

    
















    private static final class PollFaultStreak {

        private boolean reported;

        
        boolean startOfStreak() {
            if (reported) {
                return false;
            }
            reported = true;
            return true;
        }

        
        void clear() {
            reported = false;
        }
    }

    






    private static final class Poll {

        final boolean markersRequested;


        volatile boolean markersArrived;

        Poll(boolean markersRequested) {
            this.markersRequested = markersRequested;
        }

        DynmapSignal.PollOutcome outcome() {
            return DynmapSignal.pollOutcome(markersRequested, markersArrived);
        }
    }
    
    
    
    
    private static String currentConnection() {
        Minecraft client = Minecraft.getInstance();
        
        
        
        
        
        
        if (client == null || client.level == null) {
            return connectionName(null, null);
        }
        ServerData server = client.getCurrentServer();
        return connectionName(client.level, server == null ? null : server.ip);
    }

    
    private static Object connectionContext = null;
    private static String connectionAddress = null;
    private static String connectionKey = "";

    
    private static String connectionName(Object context, String rawAddress) {
        if (context == null) {
            connectionContext = null;
            connectionAddress = null;
            connectionKey = "";
            return "";
        }
        boolean addressChanged = rawAddress == null
                ? connectionAddress != null : !rawAddress.equals(connectionAddress);
        if (context != connectionContext || addressChanged) {
            connectionKey = rawAddress != null && !rawAddress.isBlank()
                    ? ChunkCapture.serverWorldKey(rawAddress) : "singleplayer";
            connectionContext = context;
            connectionAddress = rawAddress;
        }
        return connectionKey;
    }

    private static String currentDimension() {
        Minecraft client = Minecraft.getInstance();
        return client == null || client.level == null
                ? "" : client.level.dimension().identifier().toString();
    }

    
    
    
    
    
    private static String worldFor(MapBackend backend,
                                   String dimension) {
        if (dimension.isEmpty()) {
            return null;
        }
        List<String> known = backend.defaultWorlds().worldsFor(dimension);
        return known.isEmpty() ? null : known.get(0);
    }

    
    
    
    
    
    
    
    
    

    private LiveSnapshot fetch(MapBackend backend, String base, String world, Poll poll,
                               long now, long nowNanos) {
        try {
            LiveSnapshot polled = LiveSnapshot.EMPTY;
            if (poll.markersRequested) {
                String url = MapBackend.withCacheBuster(
                        MapBackend.join(base, backend.markersPath(world)), now);
                String body = get(url);
                if (body != null) {
                    polled = DynmapParser.parseMarkers(body, world);
                    poll.markersArrived = true;
                } else {
                    LiveSnapshot held = snapshot;
                    polled = new LiveSnapshot(List.of(), List.of(), held.areas(), 0L);
                }
            } else if (!poll.markersArrived) {
                LiveSnapshot held = snapshot;
                polled = new LiveSnapshot(List.of(), List.of(), held.areas(), 0L);
            }
            LiveSnapshot held = snapshot;
            long markersAt = poll.markersArrived ? nowNanos : held.markersFetchedAtNanos();
            long areasAt = poll.markersArrived ? nowNanos : held.areasFetchedAtNanos();
            return new LiveSnapshot(List.of(), List.of(), polled.areas(), now, nowNanos,
                    held.playersFetchedAtNanos(), markersAt, areasAt);
        } catch (Exception failed) {
            return null;
        } finally {
            
            
            
            
            
            
            
            
            
            inFlight = false;
        }
    }

    private String get(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI target = URI.create(url);
            String host = WorldMapping.normaliseHost(target.getHost());
            long deadline = System.nanoTime() + FETCH_DEADLINE_NANOS;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpRequest request = HttpRequest.newBuilder(target)
                        .timeout(TIMEOUT)
                        .header("User-Agent", "GeoSurvey/" + LandNav.MOD_ID)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = await(request, deadline);
                if (response == null) {
                    return null;
                }
                int code = response.statusCode();
                if (code / 100 == 3) {
                    target = redirectTarget(target, host,
                            response.headers().firstValue("Location").orElse(null));
                    if (target == null) {
                        return null;
                    }
                    continue;
                }
                if (!bodyIsRead(code)) {
                    return null;
                }
                byte[] body = response.body();
                
                
                if (body == null) {
                    return null;
                }
                return new String(body, StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception failed) {
            return null;
        }
    }

    private static final int FIRST_SUCCESS_STATUS = 200;
    private static final int AFTER_SUCCESS_STATUS = 300;

    private static boolean bodyIsRead(int code) {
        return (code >= FIRST_SUCCESS_STATUS) && (code < AFTER_SUCCESS_STATUS);
    }

    





























    private HttpResponse<byte[]> await(HttpRequest request, long deadline) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            
            
            return null;
        }
        CompletableFuture<HttpResponse<byte[]>> pending =
                client().sendAsync(request, bounded());
        try {
            return pending.get(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException cutShort) {
            
            
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException failed) {
            return null;
        } finally {
            pending.cancel(true);
        }
    }

    
    
    
    
    private static HttpResponse.BodyHandler<byte[]> bounded() {
        return anyAnswer -> {
            int code = anyAnswer.statusCode();
            if (!bodyIsRead(code)) {
                return HttpResponse.BodySubscribers.replacing((byte[]) null);
            }
            Cap cap = new Cap();
            return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.ofByteArrayConsumer(cap),
                    ignored -> cap.taken());
        };
    }

    

















    private static final class Cap implements Consumer<Optional<byte[]>> {

        private final ByteArrayOutputStream held = new ByteArrayOutputStream();

        private boolean over;

        @Override
        public void accept(Optional<byte[]> chunk) {
            byte[] bytes = chunk.orElse(null);
            if (bytes == null || over) {
                return;
            }
            if (held.size() + bytes.length > MAX_BODY_BYTES) {
                over = true;
                return;
            }
            held.write(bytes, 0, bytes.length);
        }

        byte[] taken() {
            return over ? null : held.toByteArray();
        }
    }

    private static URI redirectTarget(URI from, String host, String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        URI next;
        try {
            next = from.resolve(location.trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        String scheme = next.getScheme();
        if (scheme == null || next.getHost() == null) {
            return null;
        }
        boolean secure = scheme.equalsIgnoreCase("https");
        if (!secure && !scheme.equalsIgnoreCase("http")) {
            return null;
        }
        if (!secure && "https".equalsIgnoreCase(from.getScheme())) {
            return null;
        }
        return WorldMapping.normaliseHost(next.getHost()).equals(host) ? next : null;
    }

    private HttpClient client() {
        HttpClient existing = http;
        if (existing != null) {
            return existing;
        }
        if (stopped) {
            throw new IllegalStateException("http client stopped");
        }
        CompletableFuture<HttpClient> created = new CompletableFuture<>();
        if (httpBuild.compareAndSet(null, created)) {
            try {
            existing = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            if (stopped) {
                existing.close();
                IllegalStateException failed = new IllegalStateException("http client stopped");
                created.completeExceptionally(failed);
                httpBuild.compareAndSet(created, null);
                throw failed;
            }
            http = existing;
                created.complete(existing);
                return existing;
            } catch (RuntimeException failed) {
                created.completeExceptionally(failed);
                httpBuild.compareAndSet(created, null);
                throw failed;
            }
        }
        CompletableFuture<HttpClient> building = httpBuild.get();
        if (building == null) {
            throw new IllegalStateException("http client build is missing");
        }
        try {
            return building.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException cutShort) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http client build interrupted", cutShort);
        } catch (ExecutionException | TimeoutException failed) {
            httpBuild.compareAndSet(building, null);
            throw new IllegalStateException("http client build failed", failed);
        }
    }


    





    private void applyResult(Issued issued, Poll poll, LiveSnapshot fetched) {
        if (stopped) {
            return;
        }
        
        
        
        
        
        if (!issued.connection().equals(connection)
                || !issued.dimension().equals(polledDimension)
                || !issued.base().equals(
                        dev.openmap.config.LandNavConfig.KnownServers.webMapOf(connection))
                || issued.backend() != MapBackend.DYNMAP) {
            return;
        }
        if (fetched == null) {
            dynmapLastFailureAtNanos = System.nanoTime();
            schedule.failed();
            applyInterval();
            if (schedule.shouldReportFailure()) {
                say("web map unreachable");
            }
            return;
        }
        
        
        
        
        
        
        DynmapSignal.PollOutcome outcome = poll.outcome();
        if (outcome == DynmapSignal.PollOutcome.NOTHING) {
            
            
            
            
            
            
            
            return;
        }
        if (outcome == DynmapSignal.PollOutcome.FAILURE) {
            dynmapLastFailureAtNanos = System.nanoTime();
            schedule.failed();
            applyInterval();
            if (schedule.shouldReportFailure()) {
                say("web map unreachable");
            }
            return;
        }
        long answeredAtNanos = System.nanoTime();
        dynmapLastSuccessAtNanos = answeredAtNanos;
        schedule.succeeded();
        applyInterval();
        snapshot = fetched;
        
        
        
        
        
        
        
        
        if (poll.markersArrived) {
            markersFetchedAtNanos = answeredAtNanos;
        }
        say(poll.markersRequested && !poll.markersArrived
                ? "web map markers unavailable" : "");
    }
}
