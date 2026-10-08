package dev.openmap.client;

import dev.openmap.api.CartographerMarkers;
import dev.openmap.api.MarkerOutcome;
import dev.openmap.api.MarkerValue;
import dev.openmap.api.MarkerWrite;
import dev.openmap.map.Landmark;
import dev.openmap.map.LandmarkStore;
import dev.openmap.map.MapStorage;
import dev.openmap.symbol.Affiliation;
import dev.openmap.symbol.SymbolIcon;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The markers part of the published cartographer, on the collector's own marker store and its save road.
final class CollectorCartographerMarkers implements CartographerMarkers {

    // The revision of a dimension whose markers are not loaded; a loaded store's revision is above it.
    private static final long NOT_LOADED = 0L;

    private static final int MAX_KEYS = 16;

    private static final Affiliation[] AFFILIATIONS = Affiliation.values();

    private static final SymbolIcon[] ICONS = SymbolIcon.values();

    private static final String NO_LIST = "No marker list was given.";

    private static final String EMPTY_MARKER = "A marker in the list is empty.";

    private static final String NO_NAME = "Give the marker a name.";

    private final MapStorage storage;

    private final CollectorCartographerWorld world;

    // Client thread; a resource id text to its stored dimension key.
    private final Map<String, String> keys = new HashMap<>();

    CollectorCartographerMarkers(MapStorage storage, CollectorCartographerWorld world) {
        this.storage = storage;
        this.world = world;
    }

    @Override
    public long revision(String dimensionId) {
        if (world.worldFolder() == null || blank(dimensionId)) {
            return NOT_LOADED;
        }
        LandmarkStore store = storage.loadedLandmarks(keyOf(dimensionId));
        return store == null ? NOT_LOADED : store.revision();
    }

    @Override
    public List<MarkerValue> list(String dimensionId) {
        if (world.worldFolder() == null || blank(dimensionId)) {
            return List.of();
        }
        List<Landmark> held = storage.landmarks(keyOf(dimensionId)).all();
        List<MarkerValue> out = new ArrayList<>(held.size());
        for (Landmark mark : held) {
            out.add(valueOf(mark));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public MarkerWrite replace(String dimensionId, List<MarkerValue> markers, long seenRevision) {
        if (world.worldFolder() == null || blank(dimensionId)) {
            return new MarkerWrite(MarkerOutcome.NO_WORLD, NOT_LOADED, null);
        }
        String key = keyOf(dimensionId);
        LandmarkStore store = storage.landmarks(key);
        long now = store.revision();
        MarkerWrite answer;
        if (seenRevision != now) {
            answer = new MarkerWrite(MarkerOutcome.STALE, now, null);
        } else {
            answer = taken(key, store, markers);
        }
        return answer;
    }

    private MarkerWrite taken(String key, LandmarkStore store, List<MarkerValue> markers) {
        String refusal = refusal(store, markers);
        if (refusal != null) {
            return new MarkerWrite(MarkerOutcome.REFUSED, store.revision(), refusal);
        }
        List<Landmark> held = new ArrayList<>(store.all());
        long heldRevision = store.revision();
        store.clear();
        for (MarkerValue value : markers) {
            Landmark mark = new Landmark(value.name(), value.x(), value.z(), value.colour(),
                    affiliationOf(value.affiliation()), iconOf(value.icon()));
            store.add(mark);
            if (value.shared() != null) {
                store.setShared(mark, value.shared());
            }
        }
        String failure = saved(key);
        if (failure != null) {
            store.restore(held, heldRevision);
        }
        MarkerOutcome outcome = failure == null ? MarkerOutcome.SAVED : MarkerOutcome.REFUSED;
        return new MarkerWrite(outcome, store.revision(), failure);
    }

    // Null when every marker can be taken.
    private static String refusal(LandmarkStore store, List<MarkerValue> markers) {
        String why = store.unreadable();
        if (!why.isEmpty()) {
            return "Cannot read the markers for this world (" + why + "). The file is"
                    + " <game>/geosurvey/<world>/<dimension>" + LandmarkStore.EXTENSION + ".";
        }
        if (markers == null) {
            return NO_LIST;
        }
        String refusal = null;
        for (int at = 0; refusal == null && at < markers.size(); at++) {
            refusal = refusal(markers.get(at));
        }
        return refusal;
    }

    private static String refusal(MarkerValue value) {
        String refusal;
        if (value == null) {
            refusal = EMPTY_MARKER;
        } else if (blank(value.name())) {
            refusal = NO_NAME;
        } else if (value.affiliation() != null && affiliationOf(value.affiliation()) == null) {
            refusal = "\"" + value.affiliation() + "\" is not an affiliation.";
        } else if (value.icon() != null && iconOf(value.icon()) == null) {
            refusal = "\"" + value.icon() + "\" is not a symbol.";
        } else {
            refusal = null;
        }
        return refusal;
    }

    // Null when the save was handed to the collector's own write road.
    private String saved(String key) {
        String failure = null;
        try {
            storage.saveLandmarksInBackground(key);
        } catch (IOException | RuntimeException couldNotWrite) {
            String why = couldNotWrite.getMessage();
            failure = "The markers were not written (" + couldNotWrite.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why) + "); a restart may undo this.";
        }
        return failure;
    }

    private static MarkerValue valueOf(Landmark mark) {
        return new MarkerValue(mark.name(), mark.x(), mark.z(), mark.colour(), mark.affiliation().name(),
                mark.icon().name(), mark.shared());
    }

    // Null for a name that is no affiliation, and for no name.
    private static Affiliation affiliationOf(String name) {
        Affiliation found = null;
        for (int at = 0; found == null && name != null && at < AFFILIATIONS.length; at++) {
            if (AFFILIATIONS[at].name().equals(name)) {
                found = AFFILIATIONS[at];
            }
        }
        return found;
    }

    // Null for a name that is no symbol, and for no name.
    private static SymbolIcon iconOf(String name) {
        SymbolIcon found = null;
        for (int at = 0; found == null && name != null && at < ICONS.length; at++) {
            if (ICONS[at].name().equals(name)) {
                found = ICONS[at];
            }
        }
        return found;
    }

    private String keyOf(String dimensionId) {
        String key = keys.get(dimensionId);
        if (key == null) {
            if (keys.size() >= MAX_KEYS) {
                keys.clear();
            }
            key = MapStorage.asStored(dimensionId);
            keys.put(dimensionId, key);
        }
        return key;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
