package dev.openmap.map;

import dev.openmap.draw.ColourWord;
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
import java.util.function.Supplier;

// Places and edits markers by command, through the same LandmarkStore the
// marker panel uses. A duplicate name is refused, not guessed at.
public final class Markers {

    private static final int LIST_HEADER_CHARS = 20;

    private static final int LIST_CHARS_PER_MARKER = 64;

    private static final SymbolIcon[] ICONS = SymbolIcon.values();

    private static final Affiliation[] AFFILIATIONS = Affiliation.values();

    private static final Map<Enum<?>, String> WORD_CACHE = new HashMap<>();

    // What to say back, and whether it went in.
    public record Said(boolean ok, String text) {
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

    // A refusal when this dimension's file could not be read.
    private Said damaged(LandmarkStore store) {
        String why = store.unreadable();
        if (why.isEmpty()) {
            return null;
        }
        return new Said(false, "The markers for this world could not be read ("
                + why + "). Nothing here will change. The file is"
                + " <game>/geosurvey/<world>/<dimension>"
                + LandmarkStore.EXTENSION + ".");
    }

    // Every marker in this dimension.
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
                built.append(" \"").append(mark.name()).append("\" at ")
                        .append(mark.x()).append(",").append(mark.z())
                        .append(" - ").append(word(mark.icon()))
                        .append(", ").append(word(mark.affiliation()))
                        .append(", ").append(ColourWord.spell(mark.colour())).append('.');
            }
            said = built.toString();
        }
        return new Said(true, said);
    }

    // Place one.
    public Said add(String dimension, int x, int z, String typed) {
        if (blank(dimension)) {
            return nowhere();
        }
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return new Said(false, "A marker wants a name.");
        }
        LandmarkStore store = store(dimension);
        Said broken = damaged(store);
        if (broken != null) {
            return broken;
        }
        Landmark mark = new Landmark(name, x, z, Landmark.DEFAULT_COLOUR);
        store.add(mark);
        // The stored (normalised) name, not what was typed.
        String said = "Marker \"" + mark.name() + "\" at " + x + "," + z + ".";
        int sharing = countMatches(store, mark.name());
        if (sharing > 1) {
            int others = sharing - 1;
            said += " " + others + " other marker"
                    + (others == 1 ? "" : "s") + " here share that name. Commands"
                    + " that name it will not know which you mean. Rename one with"
                    + " /geosurvey marker rename at <x> <z> <name>.";
        }
        return saved(dimension, said);
    }

    // Take one away.
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

    // Give one a different name, by name. See renameAt for two markers
    // sharing a name.
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

    // Give the marker at these coordinates a different name.
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
            return new Said(false, "A marker wants a name.");
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
                said += " " + others + " other marker"
                        + (others == 1 ? "" : "s") + " here share that name. Commands"
                        + " that name it will not know which you mean.";
            }
            result = saved(dimension, said);
        }
        return result;
    }

    // Recolour one, by palette name or by hex.
    public Said colour(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "colour", (store, mark) -> {
            Integer argb = ColourWord.parse(wanted);
            if (argb == null) {
                if (ColourWord.isZeroAlphaHex(wanted)) {
                    return new Said(false, "\"" + shown(wanted) + "\" has a zero"
                            + " alpha byte, and a fully transparent marker cannot"
                            + " be shown. Give a hex code with a non-zero first"
                            + " byte, or leave the alpha off, like #2E9E4F.");
                }
                return new Said(false, "\"" + shown(wanted) + "\" is not a colour."
                        + " Give one of " + ColourWord.names() + ", or a hex code"
                        + " like #2E9E4F.");
            }
            store.setColour(mark, argb);
            return new Said(true, "\"" + mark.name() + "\" is now "
                    + ColourWord.spell(mark.colour()) + ".");
        });
    }

    // Change what one is drawn as.
    public Said icon(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "icon", (store, mark) ->
                pick(store, mark, ICONS, wanted, "a symbol", "drawn as ", LandmarkStore::setIcon));
    }

    // Change whose one is (MIL-STD-2525).
    public Said affiliation(String dimension, String typed, String wanted) {
        return edit(dimension, typed, "affiliation", (store, mark) ->
                pick(store, mark, AFFILIATIONS, wanted, "an affiliation", "", LandmarkStore::setAffiliation));
    }

    // The name of every marker here, for tab completion. Deduplicated.
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
            // Saves only on success.
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
            List<String> names = new ArrayList<>();
            for (Landmark mark : store.all()) {
                names.add("\"" + mark.name() + "\"");
            }
            return new Said(false, "No marker is called \"" + shown(typed) + "\"."
                    + (names.isEmpty() ? " There are none here yet."
                            : " There " + (names.size() == 1 ? "is " : "are ")
                                    + String.join(", ", names) + "."));
        }
        StringBuilder names = new StringBuilder();
        StringBuilder where = new StringBuilder();
        for (Landmark mark : found) {
            names.append(names.length() == 0 ? "" : ", ")
                    .append('"').append(mark.name()).append('"');
            where.append(where.length() == 0 ? "" : ", ")
                    .append(mark.x()).append(",").append(mark.z());
        }
        return new Said(false, found.size() + " markers are called " + names
                + " - at " + where + ". Nothing changed: naming one of them"
                + " does not say which. Rename one with /geosurvey marker rename at"
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
            return new Said(false, said + " The marker file for this world is not"
                    + " open yet. This will not be kept.");
        }
        Said failed = null;
        try {
            held.saveLandmarksInBackground(dimension);
        } catch (IOException | RuntimeException couldNotWrite) {
            String why = couldNotWrite.getMessage();
            failed = new Said(false, said + " The markers for this world could not be"
                    + " written (" + couldNotWrite.getClass().getSimpleName()
                    + (why == null ? "" : ": " + why)
                    + "). A restart may undo this.");
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
        for (Enum<?> value : values) {
            out.append(out.length() == 0 ? "" : ", ").append(word(value));
        }
        return out.toString();
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
        return new Said(false, "Markers belong to a world, and you are not in one.");
    }
}
