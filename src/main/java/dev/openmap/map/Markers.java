package dev.openmap.map;

import dev.openmap.draw.ColourWord;
import dev.openmap.share.SharedRecord;
import dev.openmap.symbol.Affiliation;
import dev.openmap.symbol.SymbolIcon;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

// Places and edits markers by command, through the marker panel's LandmarkStore.
// A duplicate name is refused.
public final class Markers {

    private static final int LIST_HEADER_CHARS = 20;

    private static final int LIST_CHARS_PER_MARKER = 64;

    private static final int NAMES_LISTED = 8;

    private static final int DUPLICATES_LISTED = 4;

    private static final String SHARED_ID_PREFIX = "geosurvey-marker-";

    private static final Said NEEDS_COLLECTOR = new Said(false,
            "Set a collector first: /geosurvey collector <address>.");

    private static final SymbolIcon[] ICONS = SymbolIcon.values();

    private static final Affiliation[] AFFILIATIONS = Affiliation.values();

    private static final Map<Enum<?>, String> WORD_CACHE = new HashMap<>();

    // The reply and whether it worked.
    public record Said(boolean ok, String text) {
    }

    // Told, on the client thread, each time a dimension's markers are saved; source names the saved file.
    @FunctionalInterface
    public interface Saved {

        void saved(String source, String dimensionId, LandmarkStore store);
    }

    @FunctionalInterface
    private interface Change {

        Said to(LandmarkStore store, Landmark mark);
    }

    @FunctionalInterface
    private interface Setter<E> {

        void set(LandmarkStore store, Landmark mark, E value);
    }

    private final Supplier<MapStorage> storage;

    public Markers(Supplier<MapStorage> storage) {
        this.storage = storage;
    }

    // A refusal when the file is unreadable.
    private Said damaged(LandmarkStore store) {
        String why = store.unreadable();
        if (why.isEmpty()) {
            return null;
        }
        return new Said(false, "Cannot read the markers for this world ("
                + why + "). The file is"
                + " <game>/geosurvey/<world>/<dimension>"
                + LandmarkStore.EXTENSION + ".");
    }

    public Said list(String dimension) {
        if (blank(dimension)) {
            return nowhere();
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        List<Landmark> all = store.all();
        String said;
        if (all.isEmpty()) {
            said = "No markers here. Add one with /geosurvey marker"
                    + " add <name>.";
        } else {
            int n = all.size();
            StringBuilder built = new StringBuilder(LIST_HEADER_CHARS + n * LIST_CHARS_PER_MARKER)
                    .append(n).append(n == 1 ? " marker:" : " markers:");
            for (Landmark mark : all) {
                built.append('\n').append('"').append(flat(mark.name())).append("\" at ")
                        .append(mark.x()).append(",").append(mark.z())
                        .append(" - ").append(word(mark.icon()))
                        .append(", ").append(word(mark.affiliation()))
                        .append(", ").append(ColourWord.spell(mark.colour())).append('.');
            }
            said = built.toString();
        }
        return new Said(true, said);
    }

    public Said add(String dimension, int x, int z, String typed) {
        if (blank(dimension)) {
            return nowhere();
        }
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return new Said(false, "Give the marker a name.");
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        Landmark mark = new Landmark(name, x, z, Landmark.DEFAULT_COLOUR);
        store.add(mark);
        // The stored name, not what was typed.
        String said = "Marker \"" + mark.name() + "\" at " + x + "," + z + ".";
        int sharing = countMatches(store, mark.name());
        if (sharing > 1) {
            int others = sharing - 1;
            said += "\n" + others + " other marker"
                    + (others == 1 ? "" : "s") + " here with that name; rename"
                    + " one with"
                    + " /geosurvey marker rename at <x> <z> <name>.";
        }
        return saved(dimension, said);
    }

    public Said remove(String dimension, String typed) {
        if (blank(dimension)) {
            return nowhere();
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        List<Landmark> found = matches(store, typed);
        Said wrong = onlyOne(store, found, typed, "remove");
        if (wrong != null) {
            return wrong;
        }
        Landmark mark = found.get(0);
        store.remove(mark);
        return saved(dimension, "Removed \"" + mark.name() + "\" from " + mark.x() + ","
                + mark.z() + ".");
    }

    // node may be null: no collector is set.
    public Said share(String dimension, String typed, String node, String playerName) {
        String target = node == null ? "" : node.trim();
        Said said;
        if (blank(dimension)) {
            said = nowhere();
        } else if (target.isEmpty()) {
            said = NEEDS_COLLECTOR;
        } else if (!SharedRecord.isName(playerName)) {
            said = new Said(false, "\"" + shown(typed) + "\" cannot be shared: your player name is not 1 to "
                    + SharedRecord.MAX_NAME + " letters, digits or _.");
        } else {
            LandmarkStore store = store(dimension);
            said = damaged(store);
            if (said == null) {
                List<Landmark> found = matches(store, typed);
                said = onlyOne(store, found, typed, "share");
                if (said == null) {
                    said = shareOne(dimension, store, found.get(0), target);
                }
            }
        }
        return said;
    }

    private Said shareOne(String dimension, LandmarkStore store, Landmark mark, String target) {
        Said said;
        if (beyondTheRecord(mark)) {
            said = new Said(false, "\"" + mark.name() + "\" cannot be shared: it is beyond "
                    + SharedRecord.MAX_COORDINATE + " blocks of the origin.");
        } else if (mark.shared() != null) {
            said = new Said(false, "\"" + mark.name() + "\" is already shared.");
        } else {
            setShared(store, mark, SHARED_ID_PREFIX + UUID.randomUUID());
            said = saved(dimension, "Shared \"" + mark.name() + "\" with " + target + ".");
        }
        return said;
    }

    public Said unshare(String dimension, String typed) {
        Said said;
        if (blank(dimension)) {
            said = nowhere();
        } else {
            LandmarkStore store = store(dimension);
            said = damaged(store);
            if (said == null) {
                List<Landmark> found = matches(store, typed);
                said = onlyOne(store, found, typed, "unshare");
                if (said == null) {
                    said = unshareOne(dimension, store, found.get(0));
                }
            }
        }
        return said;
    }

    private Said unshareOne(String dimension, LandmarkStore store, Landmark mark) {
        Said said;
        if (mark.shared() == null) {
            said = new Said(false, "\"" + mark.name() + "\" is not shared.");
        } else {
            setShared(store, mark, null);
            said = saved(dimension, "Stopped sharing \"" + mark.name() + "\".");
        }
        return said;
    }

    private static boolean beyondTheRecord(Landmark mark) {
        return Math.abs((long) mark.x()) > SharedRecord.MAX_COORDINATE
                || Math.abs((long) mark.z()) > SharedRecord.MAX_COORDINATE;
    }

    // The store's revision moves with the mark, so the next save writes it.
    private static void setShared(LandmarkStore store, Landmark mark, String shared) {
        store.setShared(mark, shared);
    }

    // Rename one, by name. For duplicate names, see renameAt.
    public Said rename(String dimension, String typed, String wanted) {
        if (blank(dimension)) {
            return nowhere();
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        List<Landmark> found = matches(store, typed);
        Said wrong = onlyOne(store, found, typed, "rename");
        if (wrong != null) {
            return wrong;
        }
        return renamed(dimension, store, found.get(0), wanted);
    }

    // Rename the marker at these coordinates.
    public Said renameAt(String dimension, int x, int z, String wanted) {
        if (blank(dimension)) {
            return nowhere();
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        List<Landmark> here = new ArrayList<>();
        for (Landmark mark : store.all()) {
            if (mark.x() == x && mark.z() == z) {
                here.add(mark);
            }
        }
        Said result;
        if (here.isEmpty()) {
            result = new Said(false, "No marker is at " + x + "," + z + ".");
        } else if (here.size() > 1) {
            result = new Said(false, here.size() + " markers are at " + x + "," + z
                    + ". Remove one on the map screen.");
        } else {
            result = renamed(dimension, store, here.get(0), wanted);
        }
        return result;
    }

    private Said renamed(String dimension, LandmarkStore store, Landmark mark,
            String wanted) {
        String name = wanted == null ? "" : wanted.trim();
        if (name.isEmpty()) {
            return new Said(false, "Give the marker a name.");
        }
        String was = mark.name();
        store.rename(mark, name);
        Said result;
        if (mark.name().equals(was)) {
            result = new Said(true, "\"" + was + "\" is already called that.");
        } else {
            String said = "\"" + was + "\" is now \"" + mark.name() + "\".";
            int sharing = countMatches(store, mark.name());
            if (sharing > 1) {
                int others = sharing - 1;
                said += "\n" + others + " other marker"
                        + (others == 1 ? "" : "s") + " here with that"
                        + " name.";
            }
            result = saved(dimension, said);
        }
        return result;
    }

    // Recolour one, by name or hex.
    public Said colour(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "colour", (store, mark) -> {
            Integer argb = ColourWord.parse(wanted);
            if (argb == null) {
                if (ColourWord.isZeroAlphaHex(wanted)) {
                    return new Said(false, "\"" + shown(wanted) + "\" has a zero"
                            + " alpha byte. Leave the alpha"
                            + " off, like"
                            + " #2E9E4F.");
                }
                return new Said(false, "\"" + shown(wanted) + "\" is not a colour."
                        + " Use " + ColourWord.names() + ", or a hex code"
                        + " like #2E9E4F.");
            }
            store.setColour(mark, argb);
            return new Said(true, "\"" + mark.name() + "\" is now "
                    + ColourWord.spell(mark.colour()) + ".");
        });
    }

    public Said icon(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "icon", (store, mark) ->
                pick(store, mark, ICONS, wanted, "a symbol", "drawn as ", LandmarkStore::setIcon));
    }

    public Said affiliation(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "affiliation", (store, mark) ->
                pick(store, mark, AFFILIATIONS, wanted, "an affiliation", "", LandmarkStore::setAffiliation));
    }

    // Marker names for tab completion, without duplicates.
    public List<String> names(String dimension) {
        if (blank(dimension)) {
            return List.of();
        }
        LandmarkStore store = store(dimension);
        if (!store.unreadable().isEmpty()) {
            return new ArrayList<>();
        }
        List<Landmark> all = store.all();
        List<String> out = new ArrayList<>(all.size());
        Set<String> seen = new HashSet<>();
        for (Landmark mark : all) {
            if (seen.add(mark.name())) {
                out.add(mark.name());
            }
        }
        return out;
    }

    private Said edit(String dimension, String typed, String what, Change change) {
        if (blank(dimension)) {
            return nowhere();
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        List<Landmark> found = matches(store, typed);
        Said wrong = onlyOne(store, found, typed, what);
        if (wrong != null) {
            return wrong;
        }
        Said said = change.to(store, found.get(0));
        if (said.ok()) {
            said = saved(dimension, said.text());
        }
        return said;
    }

    private static <E extends Enum<E>> Said pick(LandmarkStore store, Landmark mark,
            E[] values, String wanted, String noun, String verb, Setter<E> setter) {
        E value = named(values, wanted);
        if (value == null) {
            return new Said(false, "\"" + shown(wanted) + "\" is not " + noun
                    + ". They are: " + list(values) + ".");
        }
        setter.set(store, mark, value);
        return new Said(true, "\"" + mark.name() + "\" is now " + verb + word(value) + ".");
    }

    private Said onlyOne(LandmarkStore store, List<Landmark> found, String typed,
            String verb) {
        if (found.size() == 1) {
            return null;
        }
        if (found.isEmpty()) {
            List<Landmark> all = store.all();
            int listed = Math.min(all.size(), NAMES_LISTED);
            List<String> names = new ArrayList<>(listed);
            for (int at = 0; at < listed; at++) {
                names.add("\"" + flat(all.get(at).name()) + "\"");
            }
            String more = all.size() > listed ? " and " + (all.size() - listed) + " more" : "";
            return new Said(false, "No marker is called \"" + shown(typed) + "\"."
                    + (names.isEmpty() ? " There are none here."
                            : " There " + (all.size() == 1 ? "is " : "are ")
                                    + String.join(", ", names) + more + "."));
        }
        StringBuilder names = new StringBuilder();
        StringBuilder where = new StringBuilder();
        int listed = Math.min(found.size(), DUPLICATES_LISTED);
        for (int at = 0; at < listed; at++) {
            Landmark mark = found.get(at);
            names.append(names.length() == 0 ? "" : ", ")
                    .append('"').append(flat(mark.name())).append('"');
            where.append(where.length() == 0 ? "" : ", ")
                    .append(mark.x()).append(",").append(mark.z());
        }
        String more = found.size() > listed ? " and " + (found.size() - listed) + " more" : "";
        return new Said(false, found.size() + " markers are called " + names
                + " - at " + where + more + ". Nothing changed; rename one"
                + " with /geosurvey marker rename at"
                + " <x> <z> <name>.");
    }

    private static List<Landmark> matches(LandmarkStore store, String typed) {
        String want = typed == null ? "" : typed.trim();
        if (want.isEmpty()) {
            return List.of();
        }
        List<Landmark> found = new ArrayList<>();
        for (Landmark mark : store.all()) {
            if (want.equalsIgnoreCase(mark.name())) {
                found.add(mark);
            }
        }
        return found;
    }

    private static int countMatches(LandmarkStore store, String typed) {
        return matches(store, typed).size();
    }

    private LandmarkStore store(String dimension) {
        return storage.get().landmarks(dimension);
    }

    private Said saved(String dimension, String said) {
        MapStorage held = storage.get();
        if (held.landmarkFileFor(dimension) == null) {
            return new Said(false, said + " This will not be kept: the marker file"
                    + " is not open yet.");
        }
        Said failed = null;
        try {
            held.saveLandmarksInBackground(dimension);
        } catch (IOException | RuntimeException couldNotWrite) {
            String why = couldNotWrite.getMessage();
            failed = new Said(false, said + " The markers were not"
                    + " written (" + couldNotWrite.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why)
                    + "); a restart may undo this.");
        }
        return failed == null ? new Said(true, said) : failed;
    }

    private static <E extends Enum<E>> E named(E[] values, String wanted) {
        String want = wanted == null ? "" : wanted.trim();
        E found = null;
        for (E value : values) {
            if (value.name().equalsIgnoreCase(want)) {
                found = value;
                break;
            }
        }
        return found;
    }

    private static String list(Enum<?>[] values) {
        StringBuilder out = new StringBuilder();
        int listed = Math.min(values.length, NAMES_LISTED);
        for (int at = 0; at < listed; at++) {
            out.append(out.length() == 0 ? "" : ", ").append(word(values[at]));
        }
        if (values.length > listed) {
            out.append(" and ").append(values.length - listed).append(" more");
        }
        return out.toString();
    }

    // A name read from a file stays on its line.
    private static String flat(String name) {
        return name.replace('\n', ' ').replace('\r', ' ');
    }

    private static String word(Enum<?> value) {
        if (value == null) {
            return "unknown";
        }
        return WORD_CACHE.computeIfAbsent(value, v -> v.name().toLowerCase(Locale.ROOT));
    }

    private static String shown(String typed) {
        return typed == null ? "" : typed.trim();
    }

    private static boolean blank(String dimension) {
        return dimension == null || dimension.isBlank();
    }

    private static Said nowhere() {
        return new Said(false, "Join a world to use markers.");
    }
}
