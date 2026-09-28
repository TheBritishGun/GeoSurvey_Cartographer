package dev.openmap.config;

import dev.openmap.json.JsonBind;
import dev.openmap.live.MapBackend;
import dev.openmap.map.Landmark;
import dev.openmap.map.MapStorage.FileReplace;
import dev.openmap.mgrs.Bounds;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BooleanSupplier;

public final class LandNavConfig {

    private static final int DEFAULT_GRID_REFERENCE_X = 4;
    private static final int DEFAULT_GRID_REFERENCE_Y = 4;
    private static final int DEFAULT_WOODLAND_RADIUS = 4;
    private static final int DEFAULT_PROTRACTOR_PERCENT = 70;
    private static final int DEFAULT_CASUALTIES_KEPT = 5;
    private static final int DEFAULT_LIVE_MAP_INTERVAL_MILLIS = 2000;
    private static final int DEFAULT_CAVE_SURFACE_GHOST_PERCENT = 10;
    private static final int DEFAULT_CAVE_DEPTH_BAND = 16;
    private static final int DEFAULT_RADAR_RANGE = 128;
    private static final int DEFAULT_ASSOCIATION_COUNT = 3;
    private static final int DEFAULT_ASSOCIATION_RANGE = 2000;
    private static final int DEFAULT_ASSOCIATION_X = 4;
    private static final int DEFAULT_ASSOCIATION_Y = 26;
    private static final int DEFAULT_COMPASS_SIZE = 110;
    private static final int DEFAULT_COMPASS_X = 8;
    private static final int DEFAULT_COMPASS_Y = 8;
    private static final int DEFAULT_CONTOUR_INTERVAL = 8;
    private static final int DEFAULT_CONTOUR_INDEX_EVERY = 5;
    private static final int DEFAULT_HUD_CADENCE_FRAMES = 2;
    private static final int LIVE_MAP_WORLD_CAPACITY_NUMERATOR = 4;
    private static final int LIVE_MAP_WORLD_CAPACITY_DENOMINATOR = 3;
    private static final int LEGACY_LIVE_MAP_WORLDS_VERSION = 3;
    private static final int WEB_MAP_PORT_MOVE_VERSION = 4;
    private static final int SHARE_PRESENCE_SPLIT_VERSION = 5;
    private static final int APPROVED_SERVER_PORTS_VERSION = 6;
    private static final int ALPHA_SHIFT = 24;

    public int configVersion;

    public static final int CURRENT_VERSION = 7;

    // What version 2 shipped in liveMapWorlds.
    private static final class LegacyWorldsHolder {
        private static final java.util.Map<String, java.util.List<String>> LEGACY_WORLDS =
                java.util.Map.of(
                        "minecraft:overworld", java.util.List.of("world"),
                        "minecraft:the_nether", java.util.List.of("DIM-1"),
                        "minecraft:the_end", java.util.List.of("DIM1"));
    }

    public boolean gridReferenceEnabled = true;
    public boolean gridReferenceDebugOnly = true;

    // What Default resolves to.
    public static final int SHIPPED_GRID_REFERENCE_COLOUR = 0xFFC8E6C9;

    public int gridReferenceColour = SHIPPED_GRID_REFERENCE_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String gridReferenceColourSwatch;

    public static final float DEFAULT_GRID_REFERENCE_SCALE = 1.0f;

    public float gridReferenceScale = DEFAULT_GRID_REFERENCE_SCALE;
    public HudAnchor gridReferenceAnchor = HudAnchor.BOTTOM_LEFT;
    public int gridReferenceX = DEFAULT_GRID_REFERENCE_X;
    public int gridReferenceY = DEFAULT_GRID_REFERENCE_Y;

    public boolean generaliseWoodland = true;
    public int woodlandRadius = DEFAULT_WOODLAND_RADIUS;

    public static final float DEFAULT_WOODLAND_THRESHOLD = 0.30f;

    public float woodlandThreshold = DEFAULT_WOODLAND_THRESHOLD;

    public static final int MIN_WOODLAND_RADIUS = 0;
    public static final int MAX_WOODLAND_RADIUS = 16;

    // Shared by the slider and normalise().
    public static final float MIN_WOODLAND_THRESHOLD = 0.05f;
    public static final float MAX_WOODLAND_THRESHOLD = 1.0f;

    public boolean fillPatterns = true;

    public ChatShareFormat chatShareFormat =
            ChatShareFormat.LANDNAV;

    public boolean chatWaypointsEnabled = true;

    public boolean shareEnabled = false;

    // Whether this client publishes its name, Minecraft UUID and position.
    public boolean sharePresence = false;

    // Whether normalise() just turned publication off for this owner.
    private transient boolean sharePresenceSplit;

    public String shareCollector = "";

    // A seed collector address for "/geosurvey collector find". Ships blank.
    public String shareDirectorySeed = "";

    // Server-published collector cards, pasted here as text.
    public String shareMapCards = "";

    // The third road onto a collector: proves the account via Mojang's session service.
    public boolean shareSessionProof = false;

    // Vanish tab-list markers, comma separated. Blank means the default list, not none.
    public String shareVanishMarkers = "vanish,[v],(v)";

    // Staff tab-list markers, comma separated. Blank means none.
    public String shareStaffMarkers = "\u2605";

    // Logs the vanish filter's decisions to disk, including vanished players' names.
    public boolean shareVanishProbe = false;

    public java.util.List<String> approvedServers =
            new java.util.ArrayList<>();

    // True once the server list has been set, even to empty.
    public boolean approvedServersConfigured = false;

    private transient boolean loadedBeforeApprovedPorts;

    public java.util.List<String> importSearchPaths = new java.util.ArrayList<>();

    public boolean showPortals = false;

    public boolean showCaptureRange = false;

    // Share of the shorter map-window side the protractor plate covers.
    public int protractorPercent = DEFAULT_PROTRACTOR_PERCENT;

    public static final int MIN_PROTRACTOR_PERCENT = 25;
    public static final int MAX_PROTRACTOR_PERCENT = 100;

    public boolean casualtyMarkers = true;

    public int casualtyMarkersKept = DEFAULT_CASUALTIES_KEPT;

    public static final int MIN_CASUALTIES_KEPT = 1;
    public static final int MAX_CASUALTIES_KEPT = 100;

    private static final java.util.List<String> DEFAULT_IMPORT_SERVER_ALIASES =
            java.util.List.of("avience.org", "avn.gg", "jaystechvault.com");

    private static java.util.List<java.util.List<String>> defaultImportServerAliases() {
        java.util.List<java.util.List<String>> outer = new java.util.ArrayList<>(1);
        outer.add(new java.util.ArrayList<>(DEFAULT_IMPORT_SERVER_ALIASES));
        return outer;
    }

    public java.util.List<java.util.List<String>> importServerAliases =
            defaultImportServerAliases();

    public boolean minimapEnabled = false;

    public static final double DEFAULT_MINIMAP_ZOOM = 0.5;
    public double minimapZoom = DEFAULT_MINIMAP_ZOOM;

    public static final float MIN_MINIMAP_ZOOM = 0.1f;
    public static final float MAX_MINIMAP_ZOOM = 4.0f;

    public boolean minimapRotates = false;

    public MinimapView minimapView = MinimapView.SURFACE;

    public boolean webMapEnabled = false;

    // The port an Open-Map node listens on.
    public static final int NODE_PORT = 8123;

    // Where this mod serves its own map.
    public static final int DEFAULT_WEB_MAP_PORT = 29584;

    public int webMapPort = DEFAULT_WEB_MAP_PORT;

    // Below 1024 needs privileges the game does not have.
    public static final int MIN_WEB_MAP_PORT = 1024;
    public static final int MAX_WEB_MAP_PORT = 65535;

    public boolean webMapAllowLan = false;

    // Whether normalise() just moved this off the old node port.
    private transient boolean webMapPortMoved;

    public boolean liveMapEnabled = false;

    public String liveMapUrl = "";

    public String liveMapServer = "";

    public static final String DEFAULT_LIVE_BACKEND = MapBackend.DYNMAP.label();

    public String liveMapBackend = DEFAULT_LIVE_BACKEND;

    // Set by normalise() when liveMapBackend names no known backend.
    private transient String liveMapBackendProblem = "";

    public boolean liveMapPlayers = false;

    public boolean liveMapMarkers = true;

    public boolean liveMapAreas = true;

    // Whether to draw claims this client authored, regardless of liveMapAreas.
    public boolean showAuthoredClaims = true;

    public java.util.List<String> liveMapLayers = new java.util.ArrayList<>();

    // Per-dimension world overrides; empty defers to MapBackend.defaultWorlds().
    public java.util.Map<String, java.util.List<String>> liveMapWorlds =
            new java.util.LinkedHashMap<>();

    public int liveMapIntervalMillis = DEFAULT_LIVE_MAP_INTERVAL_MILLIS;

    public static final int MIN_LIVE_INTERVAL = 1000;
    public static final int MAX_LIVE_INTERVAL = 60000;

    public boolean caveSurfaceGhost = false;

    public int caveSurfaceGhostPercent = DEFAULT_CAVE_SURFACE_GHOST_PERCENT;

    public int caveDepthBand = DEFAULT_CAVE_DEPTH_BAND;

    public static final int MAX_GHOST_PERCENT = 60;
    public static final int MIN_CAVE_DEPTH_BAND = 4;
    public static final int MAX_CAVE_DEPTH_BAND = 64;

    public boolean radarEnabled = true;

    public int radarRange = DEFAULT_RADAR_RANGE;

    public boolean radarNames = false;

    public static final int MIN_RADAR_RANGE = 16;
    public static final int MAX_RADAR_RANGE = 512;

    public boolean associationEnabled = true;

    public int associationCount = DEFAULT_ASSOCIATION_COUNT;

    public int associationRange = DEFAULT_ASSOCIATION_RANGE;

    public HudAnchor associationAnchor = HudAnchor.TOP_LEFT;
    public int associationX = DEFAULT_ASSOCIATION_X;
    public int associationY = DEFAULT_ASSOCIATION_Y;
    // What Default resolves to.
    public static final int SHIPPED_ASSOCIATION_COLOUR = 0xFFC8E6C9;

    public int associationColour = SHIPPED_ASSOCIATION_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String associationColourSwatch;

    public static final int MIN_ASSOCIATION_COUNT = 1;
    public static final int MAX_ASSOCIATION_COUNT = 8;
    public static final int MAX_ASSOCIATION_RANGE = 20000;

    public boolean compassEnabled = true;

    public int compassSize = DEFAULT_COMPASS_SIZE;

    public HudAnchor compassAnchor = HudAnchor.TOP_RIGHT;
    public int compassX = DEFAULT_COMPASS_X;
    public int compassY = DEFAULT_COMPASS_Y;

    public boolean compassMils = false;

    public boolean compassTritium = true;

    public boolean compassSwing = true;

    // Marks hostile mobs within radarRange on the dial.
    public boolean compassHostileMarks = false;

    // Marks other players on the dial: neutral, or a friend colour.
    public boolean compassPlayerMarks = false;

    public boolean locatorBarEnabled = true;

    // Dimension of the tracked waypoint; empty means none tracked.
    public String compassTrackedDimension = "";

    public int compassTrackedX;

    public int compassTrackedZ;

    // Display only; not part of the tracked identity.
    public String compassTrackedName = "";

    public boolean trackingIn(String dimension) {
        String tracked = compassTrackedDimension;
        return !tracked.isEmpty() && tracked.equals(dimension);
    }

    public boolean tracking(String dimension, int x, int z) {
        return compassTrackedX == x && compassTrackedZ == z && trackingIn(dimension);
    }

    public void track(String dimension, int x, int z, String name) {
        compassTrackedDimension = dimension == null ? "" : dimension;
        compassTrackedX = x;
        compassTrackedZ = z;
        compassTrackedName = name == null ? "" : name;
    }

    // Friend names, lower case, in the order added.
    public java.util.List<String> friends = new FriendList();

    private transient java.util.Set<String> friendLookup = new FriendLookup();

    // Cap on the friends list length.
    public static final int MOST_FRIENDS = 512;

    // Case-insensitive membership test.
    public boolean isFriend(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (friends instanceof FriendList counted) {
            if (!lookupTracks(counted)) {
                friendLookup = rebuildLookup(friends);
            }
        }
        if (!(friends instanceof FriendList)
                && friendLookup.size() != (friends == null ? 0 : friends.size())) {
            friendLookup = rebuildLookup(friends);
        }
        return friendLookup.contains(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private boolean lookupTracks(FriendList list) {
        return friendLookup instanceof FriendLookup cache
                && cache.source == list
                && cache.stamp == list.changeCount();
    }

    private java.util.Set<String> rebuildLookup(java.util.List<String> list) {
        FriendLookup rebuilt = new FriendLookup();
        if (list != null) {
            for (String entry : list) {
                if (entry != null) {
                    rebuilt.add(entry.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (list instanceof FriendList counted) {
            rebuilt.stamp = counted.changeCount();
            rebuilt.source = counted;
        }
        return rebuilt;
    }

    private void syncStampFromFriends() {
        if (friends instanceof FriendList counted && friendLookup instanceof FriendLookup cache) {
            cache.stamp = counted.changeCount();
            cache.source = counted;
        }
    }

    // Returns whether the list changed.
    public boolean addFriend(String name) {
        if (name == null || name.isBlank() || friends.size() >= MOST_FRIENDS) {
            return false;
        }
        String tidied = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean absent = !friends.contains(tidied);
        if (absent) {
            friends.add(tidied);
            friendLookup.add(tidied);
            syncStampFromFriends();
        }
        return absent;
    }

    public boolean removeFriend(String name) {
        if (name == null) {
            return false;
        }
        String tidied = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean removed = friends.remove(tidied);
        if (removed) {
            friendLookup.remove(tidied);
            syncStampFromFriends();
        }
        return removed;
    }

    public void stopTracking() {
        compassTrackedDimension = "";
        compassTrackedName = "";
        compassTrackedX = 0;
        compassTrackedZ = 0;
    }

    public static final int MIN_COMPASS_SIZE = 90;
    public static final int MAX_COMPASS_SIZE = 200;

    public boolean gridEnabled = true;

    // What Default resolves to.
    public static final int SHIPPED_GRID_COLOUR = 0x66203038;

    public int gridColour = SHIPPED_GRID_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String gridColourSwatch;

    // What Default resolves to.
    public static final int SHIPPED_GRID_SQUARE_COLOUR = 0xAA1A2630;

    public int gridSquareColour = SHIPPED_GRID_SQUARE_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String gridSquareColourSwatch;

    public boolean contoursEnabled = true;
    public int contourInterval = DEFAULT_CONTOUR_INTERVAL;
    public int contourIndexEvery = DEFAULT_CONTOUR_INDEX_EVERY;

    // What Default resolves to.
    public static final int SHIPPED_CONTOUR_COLOUR = 0x996B4A2F;

    public int contourColour = SHIPPED_CONTOUR_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String contourColourSwatch;

    // What Default resolves to.
    public static final int SHIPPED_CONTOUR_INDEX_COLOUR = 0xDD8A5A33;

    public int contourIndexColour = SHIPPED_CONTOUR_INDEX_COLOUR;

    // The colour's preset name, or a custom value; null means not yet chosen.
    public String contourIndexColourSwatch;

    public static final int MIN_CONTOUR_INTERVAL = 1;
    public static final int MAX_CONTOUR_INTERVAL = 64;

    // Shared by the slider and normalise().
    public static final int MIN_CONTOUR_INDEX_EVERY = 1;
    public static final int MAX_CONTOUR_INDEX_EVERY = 20;

    public int hudCadenceFrames = DEFAULT_HUD_CADENCE_FRAMES;

    public static final int MIN_CADENCE = 1;
    public static final int MAX_CADENCE = 20;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 3.0f;

    // Set by load() when the file could not be read; empty otherwise.
    private transient String loadProblem = "";

    // Whether the load-problem toast has already been shown this session.
    private transient boolean loadProblemNoticeShown;

    // Colour names the last normalise() could not read.
    private transient java.util.List<String> colourProblems = new java.util.ArrayList<>();

    public java.util.List<String> colourProblems() {
        return java.util.List.copyOf(colourProblems);
    }

    // Suffix for the copy left beside an unreadable settings file.
    public static final String UNREADABLE_SUFFIX = ".unreadable";

    private static final JsonBind JSON = JsonBind.prettyWithNulls();

    public static final int MAX_FILE_CHARS = 1024 * 1024;

    static final int SET_ASIDE_ATTEMPTS = 3;

    static final int SET_ASIDE_RETRY_MILLIS = 40;

    // A hash of the settable fields, for skipping a redundant normalise() pass.
    public long stateVersion() {
        return StateHash.versionOf(this);
    }

    // Normalises in place, using the in-memory tie-break rule (the number wins).
    public LandNavConfig normalise() {
        return normalise(false);
    }

    // True when reading from disk, where a colour's name is the fresher half.
    private LandNavConfig normalise(boolean namesAreFresher) {
        if (gridReferenceAnchor == null) {
            gridReferenceAnchor = HudAnchor.BOTTOM_LEFT;
        }
        hudCadenceFrames = Bounds.clamp(hudCadenceFrames, MIN_CADENCE, MAX_CADENCE);
        contourInterval = Bounds.clamp(contourInterval, MIN_CONTOUR_INTERVAL, MAX_CONTOUR_INTERVAL);
        woodlandRadius = Bounds.clamp(woodlandRadius,
                MIN_WOODLAND_RADIUS, MAX_WOODLAND_RADIUS);
        woodlandThreshold = Bounds.clamp(
                notANumber(woodlandThreshold, DEFAULT_WOODLAND_THRESHOLD),
                MIN_WOODLAND_THRESHOLD, MAX_WOODLAND_THRESHOLD);
        compassSize = Bounds.clamp(compassSize, MIN_COMPASS_SIZE, MAX_COMPASS_SIZE);
        protractorPercent = Bounds.clamp(protractorPercent,
                MIN_PROTRACTOR_PERCENT, MAX_PROTRACTOR_PERCENT);
        radarRange = Bounds.clamp(radarRange, MIN_RADAR_RANGE, MAX_RADAR_RANGE);
        liveMapIntervalMillis = Bounds.clamp(liveMapIntervalMillis,
                MIN_LIVE_INTERVAL, MAX_LIVE_INTERVAL);
        webMapPort = Bounds.clamp(webMapPort, MIN_WEB_MAP_PORT, MAX_WEB_MAP_PORT);
        if (minimapView == null) {
            minimapView = MinimapView.SURFACE;
        }
        minimapZoom = Bounds.clamp(notANumber(minimapZoom, DEFAULT_MINIMAP_ZOOM),
                MIN_MINIMAP_ZOOM, MAX_MINIMAP_ZOOM);
        if (importServerAliases == null) {
            importServerAliases = new java.util.ArrayList<>();
        }
        if (liveMapUrl == null) {
            liveMapUrl = "";
        }
        shareCollector = shareCollector == null ? "" : shareCollector.trim();
        shareDirectorySeed =
                shareDirectorySeed == null ? "" : shareDirectorySeed.trim();

        shareMapCards = shareMapCards == null ? "" : shareMapCards.trim();
        shareVanishMarkers =
                shareVanishMarkers == null ? "" : shareVanishMarkers.trim();
        shareStaffMarkers =
                shareStaffMarkers == null ? "" : shareStaffMarkers.trim();
        normaliseLiveMap();
        normaliseVersionCarryForwards();
        caveSurfaceGhostPercent = Bounds.clamp(caveSurfaceGhostPercent, 0, MAX_GHOST_PERCENT);
        caveDepthBand = Bounds.clamp(caveDepthBand,
                MIN_CAVE_DEPTH_BAND, MAX_CAVE_DEPTH_BAND);
        associationCount = Bounds.clamp(associationCount,
                MIN_ASSOCIATION_COUNT, MAX_ASSOCIATION_COUNT);
        associationRange = Bounds.clamp(associationRange, 0, MAX_ASSOCIATION_RANGE);
        if (associationAnchor == null) {
            associationAnchor = HudAnchor.TOP_LEFT;
        }
        associationX = Math.max(0, associationX);
        associationY = Math.max(0, associationY);
        if (chatShareFormat == null) {
            chatShareFormat = ChatShareFormat.LANDNAV;
        }
        if (importSearchPaths == null) {
            importSearchPaths = new java.util.ArrayList<>();
        }
        if (approvedServers == null) {
            approvedServers = new java.util.ArrayList<>();
        }
        casualtyMarkersKept = Bounds.clamp(casualtyMarkersKept,
                MIN_CASUALTIES_KEPT, MAX_CASUALTIES_KEPT);
        if (compassAnchor == null) {
            compassAnchor = HudAnchor.TOP_RIGHT;
        }
        compassX = Math.max(0, compassX);
        compassY = Math.max(0, compassY);
        if (compassTrackedDimension == null) {
            compassTrackedDimension = "";
        }
        if (compassTrackedName == null) {
            compassTrackedName = "";
        }
        friends = tidyFriends(friends);
        contourIndexEvery = Bounds.clamp(contourIndexEvery,
                MIN_CONTOUR_INDEX_EVERY, MAX_CONTOUR_INDEX_EVERY);
        colourProblems.clear();
        SwatchReconciler.Reconciled gridRef = SwatchReconciler.reconcileColour(
                gridReferenceColourSwatch, gridReferenceColour, SHIPPED_GRID_REFERENCE_COLOUR,
                "gridReferenceColour", namesAreFresher, colourProblems);
        gridReferenceColour = gridRef.argb();
        gridReferenceColourSwatch = gridRef.text();

        SwatchReconciler.Reconciled association = SwatchReconciler.reconcileColour(
                associationColourSwatch, associationColour, SHIPPED_ASSOCIATION_COLOUR,
                "associationColour", namesAreFresher, colourProblems);
        associationColour = association.argb();
        associationColourSwatch = association.text();

        SwatchReconciler.Reconciled grid = SwatchReconciler.reconcileColour(gridColourSwatch,
                gridColour, SHIPPED_GRID_COLOUR,
                "gridColour", namesAreFresher, colourProblems);
        gridColour = grid.argb();
        gridColourSwatch = grid.text();

        SwatchReconciler.Reconciled gridSquare = SwatchReconciler.reconcileColour(
                gridSquareColourSwatch, gridSquareColour, SHIPPED_GRID_SQUARE_COLOUR,
                "gridSquareColour", namesAreFresher, colourProblems);
        gridSquareColour = gridSquare.argb();
        gridSquareColourSwatch = gridSquare.text();

        SwatchReconciler.Reconciled contour = SwatchReconciler.reconcileColour(
                contourColourSwatch, contourColour, SHIPPED_CONTOUR_COLOUR,
                "contourColour", namesAreFresher, colourProblems);
        contourColour = contour.argb();
        contourColourSwatch = contour.text();

        SwatchReconciler.Reconciled contourIndex = SwatchReconciler.reconcileColour(
                contourIndexColourSwatch, contourIndexColour, SHIPPED_CONTOUR_INDEX_COLOUR,
                "contourIndexColour", namesAreFresher, colourProblems);
        contourIndexColour = contourIndex.argb();
        contourIndexColourSwatch = contourIndex.text();
        gridReferenceScale = Bounds.clamp(
                notANumber(gridReferenceScale, DEFAULT_GRID_REFERENCE_SCALE),
                MIN_SCALE, MAX_SCALE);
        gridReferenceX = Math.max(0, gridReferenceX);
        gridReferenceY = Math.max(0, gridReferenceY);
        configVersion = Math.max(configVersion, CURRENT_VERSION);
        return this;
    }

    private void normaliseLiveMap() {
        if (liveMapServer == null) {
            liveMapServer = "";
        }
        liveMapBackendProblem = "";
        if (liveMapBackend == null || liveMapBackend.isBlank()) {
            liveMapBackend = DEFAULT_LIVE_BACKEND;
        } else if (CANONICAL_LIVE_BACKENDS.contains(liveMapBackend)) {
        } else if (MapBackend.match(liveMapBackend) instanceof MapBackend resolvedLiveBackend) {
            BACKEND_RESOLUTIONS.incrementAndGet();
            liveMapBackend = resolvedLiveBackend.label();
        } else {
            // Kept as typed, not replaced with the default.
            liveMapBackendProblem = "liveMapBackend \"" + clipped(liveMapBackend)
                    + "\" is unknown. Choices: "
                    + choiceList() + ".";
        }
        if (liveMapLayers == null) {
            liveMapLayers = new java.util.ArrayList<>();
        }

        int worldCount = liveMapWorlds == null ? 0 : liveMapWorlds.size();
        java.util.Map<String, java.util.List<String>> worlds =
                new java.util.LinkedHashMap<>((worldCount * LIVE_MAP_WORLD_CAPACITY_NUMERATOR
                        / LIVE_MAP_WORLD_CAPACITY_DENOMINATOR) + 1);
        if (liveMapWorlds != null) {
            for (java.util.Map.Entry<String, java.util.List<String>> entry
                    : liveMapWorlds.entrySet()) {
                java.util.List<String> held = entry.getValue();
                java.util.List<String> names;
                if (held != null) {
                    names = new java.util.ArrayList<>(held.size());
                    for (String name : held) {
                        if (name != null) {
                            names.add(name);
                        }
                    }
                } else {
                    names = new java.util.ArrayList<>();
                }
                worlds.put(entry.getKey(), names);
            }
        }
        liveMapWorlds = worlds;
    }

    private void normaliseVersionCarryForwards() {
        if (configVersion < LEGACY_LIVE_MAP_WORLDS_VERSION
                && liveMapWorlds.equals(LegacyWorldsHolder.LEGACY_WORLDS)) {
            liveMapWorlds.clear();
        }
        if (configVersion < WEB_MAP_PORT_MOVE_VERSION && webMapPort == NODE_PORT) {
            webMapPort = DEFAULT_WEB_MAP_PORT;
            webMapPortMoved = true;
        }

        if (configVersion < SHARE_PRESENCE_SPLIT_VERSION && shareEnabled && !sharePresence) {
            sharePresenceSplit = true;
        }

        if (configVersion < APPROVED_SERVER_PORTS_VERSION
                && approvedServers != null && !approvedServers.isEmpty()) {
            loadedBeforeApprovedPorts = true;
        }
    }

    static final class SwatchReconciler {

        // The text to store and the packed ARGB to draw.
        private record Reconciled(String text, int argb) {}

        private record SwatchProblem(String stored, String message) {}

        private static final java.util.concurrent.ConcurrentHashMap<String, SwatchProblem>
                SWATCH_PROBLEM_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        private static final java.util.concurrent.atomic.AtomicInteger FULL_PATH_RECONCILES =
                new java.util.concurrent.atomic.AtomicInteger();

        static int reconcileColourFullPathCalls() {
            return FULL_PATH_RECONCILES.get();
        }

        // Settles one colour's name against its number, using namesAreFresher as tie-break.
        static Reconciled reconcileColour(String stored, int held, int shipped, String key,
                boolean namesAreFresher, java.util.List<String> problems) {
            if (held == shipped && "Default".equals(stored)) {
                return new Reconciled(stored, held);
            }
            FULL_PATH_RECONCILES.incrementAndGet();
            ColourSwatch named = ColourSwatch.parse(stored, held >>> ALPHA_SHIFT);
            int resolved = named == null ? 0 : named.resolve(shipped);
            Reconciled settled;
            if (named != null && (namesAreFresher || resolved == held)) {
                settled = new Reconciled(named.format(), resolved);
            } else if (named == null && stored != null && !stored.isBlank()) {
                SwatchProblem cached = SWATCH_PROBLEM_CACHE.get(key);
                String message;
                if (cached != null && cached.stored().equals(stored)) {
                    message = cached.message();
                } else {
                    message = key + "Swatch holds \"" + LandNavConfig.clipped(stored)
                            + "\", not a preset or hex colour."
                            + " The colour is unchanged."
                            + " The name is kept.";
                    SWATCH_PROBLEM_CACHE.put(key, new SwatchProblem(stored, message));
                }
                problems.add(message);
                settled = new Reconciled(stored, held);
            } else {
                int drawn = (held >>> ALPHA_SHIFT) == 0 ? held | Landmark.OPAQUE_ALPHA : held;
                settled = new Reconciled(drawn == shipped
                        ? ColourSwatch.DEFAULT.format()
                        : ColourSwatch.formatCustom(drawn), drawn);
            }
            return settled;
        }
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setGridColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, gridColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        gridColour = named.resolve(SHIPPED_GRID_COLOUR);
        gridColourSwatch = named.format();
        return true;
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setGridSquareColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, gridSquareColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        gridSquareColour = named.resolve(SHIPPED_GRID_SQUARE_COLOUR);
        gridSquareColourSwatch = named.format();
        return true;
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setGridReferenceColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, gridReferenceColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        gridReferenceColour = named.resolve(SHIPPED_GRID_REFERENCE_COLOUR);
        gridReferenceColourSwatch = named.format();
        return true;
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setAssociationColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, associationColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        associationColour = named.resolve(SHIPPED_ASSOCIATION_COLOUR);
        associationColourSwatch = named.format();
        return true;
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setContourColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, contourColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        contourColour = named.resolve(SHIPPED_CONTOUR_COLOUR);
        contourColourSwatch = named.format();
        return true;
    }

    // Sets the colour from text; returns false when the text names no known colour.
    public boolean setContourIndexColour(String swatch) {
        ColourSwatch named = ColourSwatch.parse(swatch, contourIndexColour >>> ALPHA_SHIFT);
        if (named == null) {
            return false;
        }
        contourIndexColour = named.resolve(SHIPPED_CONTOUR_INDEX_COLOUR);
        contourIndexColourSwatch = named.format();
        return true;
    }

    // Returns shipped when value is NaN.
    private static float notANumber(float value, float shipped) {
        return Float.isNaN(value) ? shipped : value;
    }

    private static double notANumber(double value, double shipped) {
        return Double.isNaN(value) ? shipped : value;
    }

    // Why liveMapBackend names no backend, or empty.
    public String liveMapBackendProblem() {
        return liveMapBackendProblem;
    }

    // Returns and clears whether this load moved the port.
    public boolean takeWebMapPortMoved() {
        boolean moved = webMapPortMoved;
        webMapPortMoved = false;
        return moved;
    }

    // Returns and clears whether this load turned publication off.
    public boolean takeSharePresenceSplit() {
        boolean split = sharePresenceSplit;
        sharePresenceSplit = false;
        return split;
    }

    public boolean takeLoadedBeforeApprovedPorts() {
        boolean loaded = loadedBeforeApprovedPorts;
        loadedBeforeApprovedPorts = false;
        return loaded;
    }

    // Shared by the settings screen and normalise().
    public static java.util.List<String> liveMapBackendChoices() {
        return BACKEND_CHOICES;
    }

    private static final java.util.concurrent.atomic.AtomicInteger BACKEND_RESOLUTIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    static int liveMapBackendResolutionCalls() {
        return BACKEND_RESOLUTIONS.get();
    }

    private static final java.util.List<String> BACKEND_CHOICES;

    private static final String CHOICE_LIST;

    static {
        MapBackend[] backends = MapBackend.values();
        java.util.List<String> choices = new java.util.ArrayList<>(backends.length);
        int joinedLength = 0;
        for (MapBackend backend : backends) {
            String label = backend.label();
            choices.add(label);
            joinedLength += label.length();
        }
        if (choices.size() > 1) {
            joinedLength += 2 * (choices.size() - 1);
        }
        StringBuilder joined = new StringBuilder(joinedLength);
        for (int i = 0; i < choices.size(); i++) {
            if (i > 0) {
                joined.append(", ");
            }
            joined.append(choices.get(i));
        }
        BACKEND_CHOICES = java.util.List.copyOf(choices);
        CHOICE_LIST = joined.toString();
    }

    private static final java.util.Set<String> CANONICAL_LIVE_BACKENDS = canonicalLiveBackends();

    private static java.util.Set<String> canonicalLiveBackends() {
        java.util.Set<String> canonical = new java.util.HashSet<>();
        for (String label : liveMapBackendChoices()) {
            canonical.add(label);
        }
        return canonical;
    }

    private static String choiceList() {
        return CHOICE_LIST;
    }

    // Max characters of a field's value quoted in a message.
    private static final int PROBLEM_QUOTE_LIMIT = 64;

    private static String clipped(String text) {
        int length = text.length();
        int kept = length <= PROBLEM_QUOTE_LIMIT ? length : PROBLEM_QUOTE_LIMIT;
        int at = 0;
        while (at < kept) {
            char current = text.charAt(at);
            if (current == '\r' || current == '\n') {
                break;
            }
            at++;
        }
        String head;
        if (at == kept) {
            head = kept == length ? text : text.substring(0, kept);
        } else {
            StringBuilder flattened = new StringBuilder(kept).append(text, 0, at);
            for (int i = at; i < kept; i++) {
                char current = text.charAt(i);
                flattened.append(current == '\r' || current == '\n' ? ' ' : current);
            }
            head = flattened.toString();
        }
        return length <= PROBLEM_QUOTE_LIMIT ? head : head + "...";
    }

    // Loads settings from path, or shipped defaults when it cannot be read.
    public static LandNavConfig load(Path path) {
        LandNavConfig result;
        if (path == null) {
            result = new LandNavConfig().normalise();
        } else {
            try (Reader reader = new InputStreamReader(Files.newInputStream(path),
                    StandardCharsets.UTF_8)) {
                LandNavConfig loaded = JSON.fromJson(new Capped(reader), LandNavConfig.class);
                if (loaded != null) {
                    // True: read from disk, so the name is the fresher half.
                    result = loaded.normalise(true);
                } else {
                    result = unreadable(path, "holds no document");
                }
            } catch (NoSuchFileException absent) {
                result = new LandNavConfig().normalise();
            } catch (IOException | RuntimeException notOpened) {
                result = unreadableOrDefaults(path, notOpened);
            }
        }
        return result;
    }

    private static LandNavConfig unreadableOrDefaults(Path path, Exception notOpened) {
        LandNavConfig result;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                result = new LandNavConfig().normalise();
            } else {
                result = unreadable(path, notOpened);
            }
        } catch (NoSuchFileException vanished) {
            result = new LandNavConfig().normalise();
        } catch (IOException attributesUnavailable) {
            result = unreadable(path, attributesUnavailable);
        }
        return result;
    }

    private static LandNavConfig unreadable(Path path, Throwable why) {
        return unreadable(path, String.valueOf(why));
    }

    private static LandNavConfig unreadable(Path path, String why) {
        LandNavConfig shipped = new LandNavConfig();
        shipped.approvedServersConfigured = true;
        shipped.loadProblem = setAside(path, why)
                + " The approved-server list was cleared."
                + " Contributing stays off until you name a server again.";
        return shipped.normalise();
    }

    // Copies the unreadable file beside itself and returns what to tell the player.
    private static String setAside(Path path, String why) {
        String name = String.valueOf(path.getFileName());
        String unreadableName = name + UNREADABLE_SUFFIX;
        String said = name + " could not be read (" + why
                + "). Every setting is at its shipped value for this session.";
        Path copy = path.resolveSibling(unreadableName);
        String result;
        try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            result = said + " The file was copied to " + unreadableName
                    + ". The next save writes over the original.";
        } catch (IOException | RuntimeException firstFailure) {
            Object lastFailure = firstFailure;
            boolean retry = SET_ASIDE_ATTEMPTS > 1;
            if (retry) {
                try {
                    Thread.sleep(SET_ASIDE_RETRY_MILLIS);
                } catch (InterruptedException wake) {
                    Thread.currentThread().interrupt();
                    retry = false;
                }
            }
            for (int attempt = 2; retry && attempt <= SET_ASIDE_ATTEMPTS; attempt++) {
                try {
                    Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                    lastFailure = null;
                    break;
                } catch (IOException | RuntimeException couldNotCopy) {
                    lastFailure = couldNotCopy;
                    if (attempt < SET_ASIDE_ATTEMPTS) {
                        try {
                            Thread.sleep(SET_ASIDE_RETRY_MILLIS);
                        } catch (InterruptedException wake) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (lastFailure == null) {
                result = said + " The file was copied to " + unreadableName
                        + ". The next save writes over the original.";
            } else {
                result = said + " It could not be copied aside either (" + lastFailure
                        + "). The next save writes over it.";
            }
        }
        return result;
    }

    private static final class Capped extends Reader {

        private final Reader in;

        private int left = MAX_FILE_CHARS;

        Capped(Reader in) {
            this.in = in;
        }

        @Override
        public int read(char[] into, int offset, int length) throws IOException {
            int got = in.read(into, offset, Math.min(length, left + 1));
            if (got > 0) {
                left -= got;
                if (left < 0) {
                    throw new IOException("the settings file is longer than "
                            + MAX_FILE_CHARS + " characters");
                }
            }
            return got;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // What stopped the last load, or empty.
    public String loadProblem() {
        return loadProblem;
    }

    // True once per load that has a loadProblem.
    public boolean takeLoadProblemForNotice() {
        if (loadProblem.isEmpty() || loadProblemNoticeShown) {
            return false;
        }
        loadProblemNoticeShown = true;
        return true;
    }

    // Set once ATOMIC_MOVE fails, so a later save does not retry it.
    private static volatile boolean atomicMoveUnsupported;

    private static final java.util.concurrent.atomic.AtomicInteger DIRECTORY_CREATIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    static int createDirectoriesCalls() {
        return DIRECTORY_CREATIONS.get();
    }

    // No lock: callers must not save one config from two threads at once.
    public void save(Path path) throws IOException {
        normalise();
        land(writeFile(path), path, FileReplace.ALWAYS);
    }

    // Same write; skips normalise() when normalisedState still matches stateVersion().
    public void save(Path path, long normalisedState) throws IOException {
        save(path, normalisedState, null);
    }

    public void save(Path path, long normalisedState, BooleanSupplier stillNewest)
            throws IOException {
        if (stillNewest == null || stillNewest.getAsBoolean()) {
            if (normalisedState != stateVersion()) {
                normalise();
            }
            FileReplace.Turn turn = stillNewest == null ? FileReplace.ALWAYS : stillNewest::getAsBoolean;
            land(writeFile(path), path, turn);
        }
    }

    // The write itself.
    private Path writeFile(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            DIRECTORY_CREATIONS.incrementAndGet();
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + "."
                + Thread.currentThread().threadId() + ".tmp");
        try {
            try (Writer writer = new CappedWriter(
                    new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                            Files.newOutputStream(temp), StandardCharsets.UTF_8.newEncoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                                    .onUnmappableCharacter(
                                            java.nio.charset.CodingErrorAction.REPLACE))))) {
                JSON.toJson(this, writer);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException undeletable) {
                failed.addSuppressed(undeletable);
            }
            throw failed;
        }
        return temp;
    }

    private static void land(Path temp, Path path, FileReplace.Turn turn) throws IOException {
        if (FileReplace.replace(temp, path, !atomicMoveUnsupported, turn)
                == FileReplace.Moved.WITHOUT_ATOMIC_MOVE) {
            atomicMoveUnsupported = true;
        }
    }

    private static final class CappedWriter extends Writer {

        private final Writer out;

        private long written;

        CappedWriter(Writer out) {
            this.out = out;
        }

        @Override
        public void write(int character) throws IOException {
            add(1);
            out.write(character);
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            add(length);
            out.write(characters, offset, length);
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            add(length);
            out.write(text, offset, length);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }

        private void add(int count) throws IOException {
            written += count;
            if (written > MAX_FILE_CHARS) {
                throw new IOException("the settings file is longer than "
                        + MAX_FILE_CHARS + " characters");
            }
        }
    }

    public LandNavConfig copy() {
        LandNavConfig copy = JSON.fromJson(JSON.toJsonTree(this), LandNavConfig.class);
        if (copy != null) {
            copy.friendLookup = new java.util.HashSet<>(
                    copy.friends == null ? java.util.List.of() : copy.friends);
        }
        return copy;
    }

    public void copyFrom(LandNavConfig other) {
        this.configVersion = other.configVersion;
        this.gridReferenceEnabled = other.gridReferenceEnabled;
        this.gridReferenceDebugOnly = other.gridReferenceDebugOnly;
        this.gridReferenceColour = other.gridReferenceColour;
        this.gridReferenceColourSwatch = other.gridReferenceColourSwatch;
        this.gridReferenceScale = other.gridReferenceScale;
        this.gridReferenceAnchor = other.gridReferenceAnchor;
        this.gridReferenceX = other.gridReferenceX;
        this.gridReferenceY = other.gridReferenceY;
        this.hudCadenceFrames = other.hudCadenceFrames;
        this.generaliseWoodland = other.generaliseWoodland;
        this.woodlandRadius = other.woodlandRadius;
        this.woodlandThreshold = other.woodlandThreshold;
        this.fillPatterns = other.fillPatterns;
        this.chatShareFormat = other.chatShareFormat;
        this.chatWaypointsEnabled = other.chatWaypointsEnabled;
        this.shareEnabled = other.shareEnabled;

        this.sharePresence = other.sharePresence;
        this.shareCollector = other.shareCollector;

        this.shareDirectorySeed = other.shareDirectorySeed;
        this.shareMapCards = other.shareMapCards;
        this.shareSessionProof = other.shareSessionProof;
        this.shareVanishMarkers = other.shareVanishMarkers;
        this.shareStaffMarkers = other.shareStaffMarkers;
        this.shareVanishProbe = other.shareVanishProbe;
        this.approvedServers = new java.util.ArrayList<>(
                other.approvedServers == null
                        ? java.util.List.of() : other.approvedServers);
        this.approvedServersConfigured = other.approvedServersConfigured;
        this.importSearchPaths = new java.util.ArrayList<>(
                other.importSearchPaths == null
                        ? java.util.List.of() : other.importSearchPaths);
        this.showPortals = other.showPortals;
        this.showCaptureRange = other.showCaptureRange;
        this.protractorPercent = other.protractorPercent;

        // Copied, not shared: importServerAliases holds nested lists too.
        java.util.List<java.util.List<String>> copiedAliases = new java.util.ArrayList<>(
                other.importServerAliases == null ? 0 : other.importServerAliases.size());
        if (other.importServerAliases != null) {
            for (java.util.List<String> group : other.importServerAliases) {
                copiedAliases.add(
                        group == null ? null : new java.util.ArrayList<>(group));
            }
        }
        this.importServerAliases = copiedAliases;
        this.minimapEnabled = other.minimapEnabled;
        this.minimapZoom = other.minimapZoom;
        this.minimapRotates = other.minimapRotates;
        this.minimapView = other.minimapView;
        this.webMapEnabled = other.webMapEnabled;
        this.webMapPort = other.webMapPort;
        this.webMapAllowLan = other.webMapAllowLan;
        this.liveMapEnabled = other.liveMapEnabled;
        this.liveMapUrl = other.liveMapUrl;
        this.liveMapServer = other.liveMapServer;
        this.liveMapBackend = other.liveMapBackend;
        this.liveMapPlayers = other.liveMapPlayers;
        this.liveMapMarkers = other.liveMapMarkers;
        this.liveMapAreas = other.liveMapAreas;
        this.showAuthoredClaims = other.showAuthoredClaims;
        this.liveMapLayers = new java.util.ArrayList<>(
                other.liveMapLayers == null
                        ? java.util.List.of() : other.liveMapLayers);
        this.liveMapWorlds = other.liveMapWorlds == null
                ? java.util.Map.of() : other.liveMapWorlds;
        this.liveMapIntervalMillis = other.liveMapIntervalMillis;
        this.caveSurfaceGhost = other.caveSurfaceGhost;
        this.caveSurfaceGhostPercent = other.caveSurfaceGhostPercent;
        this.caveDepthBand = other.caveDepthBand;
        this.radarEnabled = other.radarEnabled;
        this.radarRange = other.radarRange;
        this.radarNames = other.radarNames;
        this.associationEnabled = other.associationEnabled;
        this.associationCount = other.associationCount;
        this.associationRange = other.associationRange;
        this.associationAnchor = other.associationAnchor;
        this.associationX = other.associationX;
        this.associationY = other.associationY;
        this.associationColour = other.associationColour;
        this.associationColourSwatch = other.associationColourSwatch;
        this.casualtyMarkers = other.casualtyMarkers;
        this.casualtyMarkersKept = other.casualtyMarkersKept;
        this.compassEnabled = other.compassEnabled;
        this.compassSize = other.compassSize;
        this.compassAnchor = other.compassAnchor;
        this.compassX = other.compassX;
        this.compassY = other.compassY;
        this.compassMils = other.compassMils;
        this.compassTritium = other.compassTritium;
        this.compassSwing = other.compassSwing;
        this.compassHostileMarks = other.compassHostileMarks;
        this.compassPlayerMarks = other.compassPlayerMarks;
        this.locatorBarEnabled = other.locatorBarEnabled;
        this.compassTrackedDimension = other.compassTrackedDimension;
        this.compassTrackedX = other.compassTrackedX;
        this.compassTrackedZ = other.compassTrackedZ;
        this.compassTrackedName = other.compassTrackedName;
        this.friends = other.friends == null ? java.util.List.of() : other.friends;
        this.gridEnabled = other.gridEnabled;
        this.gridColour = other.gridColour;
        this.gridColourSwatch = other.gridColourSwatch;
        this.gridSquareColour = other.gridSquareColour;
        this.gridSquareColourSwatch = other.gridSquareColourSwatch;
        this.contoursEnabled = other.contoursEnabled;
        this.contourInterval = other.contourInterval;
        this.contourIndexEvery = other.contourIndexEvery;
        this.contourColour = other.contourColour;
        this.contourColourSwatch = other.contourColourSwatch;
        this.contourIndexColour = other.contourIndexColour;
        this.contourIndexColourSwatch = other.contourIndexColourSwatch;
        normalise();
    }


    // Lower-cases, dedupes and caps a friends list read from a file.
    private java.util.List<String> tidyFriends(java.util.List<String> raw) {
        if (raw == null) {
            friendLookup = new FriendLookup();
            return new FriendList();
        }
        FriendList out = new FriendList(Math.min(raw.size(), MOST_FRIENDS));
        for (String name : raw) {
            if (name == null) {
                continue;
            }
            String tidied = name.trim().toLowerCase(java.util.Locale.ROOT);
            if (!tidied.isEmpty() && !out.contains(tidied) && out.size() < MOST_FRIENDS) {
                out.add(tidied);
            }
        }
        FriendLookup cache = new FriendLookup();
        cache.addAll(out);
        cache.stamp = out.changeCount();
        cache.source = out;
        friendLookup = cache;
        return out;
    }

    // Computes stateVersion() by reflection.
    private static final class StateHash {

        private static final long INITIAL_STAMP = 0xCBF29CE484222325L;
        private static final long STAMP_MULTIPLIER = 1000003L;
        private static final long TRUE_READING = 1231;
        private static final long FALSE_READING = 1237;
        private static final int STRING_HASH_SHIFT = 20;
        private static final long NON_STRING_READING = 0x9E3779B9L;

        private static final byte STATE_OTHER = 0;
        private static final byte STATE_INTEGRAL = 1;
        private static final byte STATE_BOOLEAN = 2;
        private static final byte STATE_FLOAT = 3;
        private static final byte STATE_DOUBLE = 4;
        private static final byte STATE_STRING = 5;
        private static final byte STATE_ENUM = 6;

        private static final java.util.concurrent.atomic.AtomicLong UNREADABLE_STATE =
                new java.util.concurrent.atomic.AtomicLong();

        private static final java.lang.reflect.Field[] STATE = stateFields();

        private static final byte[] STATE_KINDS = stateKinds(STATE);

        private StateHash() {
        }

        static long versionOf(LandNavConfig target) {
            java.lang.reflect.Field[] fields = STATE;
            byte[] kinds = STATE_KINDS;
            long stamp = INITIAL_STAMP;
            long result;
            try {
                for (int at = 0; at < fields.length; at++) {
                    stamp = stamp * STAMP_MULTIPLIER + reading(target, fields[at], kinds[at]);
                }
                result = stamp;
            } catch (IllegalAccessException unreadable) {
                // Unreachable; falls back to a value that always forces normalise().
                result = UNREADABLE_STATE.incrementAndGet();
            }
            return result;
        }

        private static long reading(LandNavConfig target, java.lang.reflect.Field field,
                byte kind) throws IllegalAccessException {
            return switch (kind) {
                case STATE_INTEGRAL -> field.getLong(target);
                case STATE_BOOLEAN -> field.getBoolean(target) ? TRUE_READING : FALSE_READING;
                case STATE_FLOAT -> Float.floatToIntBits(field.getFloat(target));
                case STATE_DOUBLE -> Double.doubleToLongBits(field.getDouble(target));
                case STATE_STRING -> {
                    Object heldValue = field.get(target);
                    if (heldValue instanceof String held) {
                        yield (((long) held.hashCode()) << STRING_HASH_SHIFT) + held.length() + 1;
                    }
                    yield NON_STRING_READING;
                }
                case STATE_ENUM -> {
                    Object held = field.get(target);
                    if (held instanceof Enum<?> named) {
                        yield named.ordinal() + 1;
                    }
                    yield 0;
                }
                // Content hash for List and Map fields.
                default -> java.util.Objects.hashCode(field.get(target));
            };
        }

        // Declared, non-static, non-transient, non-synthetic fields: what JsonBind writes.
        private static java.lang.reflect.Field[] stateFields() {
            java.lang.reflect.Field[] declared = LandNavConfig.class.getDeclaredFields();
            java.util.List<java.lang.reflect.Field> out =
                    new java.util.ArrayList<>(declared.length);
            for (java.lang.reflect.Field field : declared) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || java.lang.reflect.Modifier.isTransient(modifiers)
                        || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                out.add(field);
            }
            return out.toArray(new java.lang.reflect.Field[0]);
        }

        private static byte[] stateKinds(java.lang.reflect.Field[] fields) {
            byte[] kinds = new byte[fields.length];
            for (int at = 0; at < fields.length; at++) {
                Class<?> type = fields[at].getType();
                if (type == boolean.class) {
                    kinds[at] = STATE_BOOLEAN;
                } else if (type == float.class) {
                    kinds[at] = STATE_FLOAT;
                } else if (type == double.class) {
                    kinds[at] = STATE_DOUBLE;
                } else if (type.isPrimitive()) {
                    kinds[at] = STATE_INTEGRAL;
                } else if (type == String.class) {
                    kinds[at] = STATE_STRING;
                } else if (type.isEnum()) {
                    kinds[at] = STATE_ENUM;
                } else {
                    kinds[at] = STATE_OTHER;
                }
            }
            return kinds;
        }
    }

    private static final class FriendList extends java.util.ArrayList<String> {

        private long sets;

        private FriendList() {
            super();
        }

        private FriendList(int initialCapacity) {
            super(initialCapacity);
        }

        @Override
        public String set(int index, String element) {
            sets++;
            return super.set(index, element);
        }

        @Override
        public java.util.List<String> subList(int fromIndex, int toIndex) {
            return new FriendListView(this, fromIndex, toIndex);
        }

        long changeCount() {
            return (long) modCount + sets;
        }

        private int structuralChanges() {
            return modCount;
        }

        private static final class FriendListView extends java.util.AbstractList<String>
                implements java.util.RandomAccess {

            private final FriendList owner;

            private final int offset;

            private int size;

            private int expectedModCount;

            private FriendListView(FriendList owner, int fromIndex, int toIndex) {
                java.util.Objects.checkFromToIndex(fromIndex, toIndex, owner.size());
                this.owner = owner;
                this.offset = fromIndex;
                this.size = toIndex - fromIndex;
                this.expectedModCount = owner.structuralChanges();
            }

            @Override
            public int size() {
                checkForComodification();
                return size;
            }

            @Override
            public String get(int index) {
                checkForComodification();
                java.util.Objects.checkIndex(index, size);
                return owner.get(offset + index);
            }

            @Override
            public String set(int index, String element) {
                checkForComodification();
                java.util.Objects.checkIndex(index, size);
                return owner.set(offset + index, element);
            }

            @Override
            public void add(int index, String element) {
                checkForComodification();
                java.util.Objects.checkFromToIndex(index, index, size);
                owner.add(offset + index, element);
                size++;
                expectedModCount = owner.structuralChanges();
            }

            @Override
            public String remove(int index) {
                checkForComodification();
                java.util.Objects.checkIndex(index, size);
                String out = owner.remove(offset + index);
                size--;
                expectedModCount = owner.structuralChanges();
                return out;
            }

            @Override
            public java.util.List<String> subList(int fromIndex, int toIndex) {
                checkForComodification();
                java.util.Objects.checkFromToIndex(fromIndex, toIndex, size);
                return new FriendListView(owner, offset + fromIndex, offset + toIndex);
            }

            private void checkForComodification() {
                if (owner.structuralChanges() != expectedModCount) {
                    throw new java.util.ConcurrentModificationException();
                }
            }
        }
    }

    private static final class FriendLookup extends java.util.HashSet<String> {

        private long stamp;
        private java.util.List<String> source;
    }
}
