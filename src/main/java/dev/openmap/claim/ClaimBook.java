package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonBind;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import dev.openmap.map.MapStorage.FileReplace;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ClaimBook {

    public static final String FILE = "claims-authored.json";

    // Marks a claim as authored, not borrowed.
    private static final String SET = "geosurvey:authored";

    // Ceiling on add(), not load() or save(); a quarter of cairn's AREAS_KEPT.
    static final int MAX_CLAIMS = 256;

    private static final int VERSION = 1;

    private static final int DOCUMENT_ENVELOPE = 64;

    private static final int PER_CLAIM = 256;

    private static final int PER_CORNER = 12;

    private static final int CORNER_AXES = 2;

    private static final MarkerColour[] COLOURS = MarkerColour.values();

    private final List<Claim> claims = new ArrayList<>();

    private String unreadable = "";

    // Round-tripped unchanged between load() and document().
    private JsonArray markers = new JsonArray();

    ClaimBook() {
    }

    List<Claim> claims() {
        return Collections.unmodifiableList(claims);
    }

    public int size() {
        return claims.size();
    }

    // Why the file could not be read, or empty when there was nothing wrong.
    public String unreadable() {
        return unreadable;
    }

    boolean full() {
        return claims.size() >= MAX_CLAIMS;
    }

    // The claim of that name, or null. Case- and padding-insensitive.
    public Claim byName(String handle) {
        String wanted = handleOf(handle);
        if (wanted == null) {
            return null;
        }
        Claim found = null;
        for (Claim claim : claims) {
            if (claim.isCalledTrimmed(wanted)) {
                found = claim;
                break;
            }
        }
        return found;
    }

    // The row byName() answers from, or -1 when none.
    int indexByName(String handle) {
        String wanted = handleOf(handle);
        if (wanted == null) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).isCalledTrimmed(wanted)) {
                found = i;
                break;
            }
        }
        return found;
    }

    // The trimmed handle, or null when blank.
    private static String handleOf(String handle) {
        String wanted = handle == null ? null : handle.trim();
        return wanted == null || wanted.isEmpty() ? null : wanted;
    }

    // The claim at that row, or null when the book has no such row.
    Claim at(int index) {
        return index < 0 || index >= claims.size() ? null : claims.get(index);
    }

    // Refuses a duplicate name or a book already at MAX_CLAIMS.
    public boolean add(Claim claim) {
        return insertIfAllowed(claim) == Refusal.NONE;
    }

    // Why insertIfAllowed() refused, or NONE.
    enum Refusal {

        NONE,

        // There was no claim to add.
        NULL,

        // A claim of that name is already in the book.
        DUPLICATE,

        // ClaimBook.MAX_CLAIMS already.
        FULL
    }

    Refusal insertIfAllowed(Claim claim) {
        if (claim == null) {
            return Refusal.NULL;
        }
        if (byName(claim.name()) != null) {
            return Refusal.DUPLICATE;
        }
        if (full()) {
            return Refusal.FULL;
        }
        claims.add(claim);
        return Refusal.NONE;
    }

    // Replace by Claim.id(), keeping its place in the list.
    boolean replace(Claim claim) {
        if (claim == null) {
            return false;
        }
        boolean replaced = false;
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).id().equals(claim.id())) {
                replaced = replaceAt(i, claim);
                break;
            }
        }
        return replaced;
    }

    // Replace the row at index, keeping its place. False when there is no such row.
    boolean replaceAt(int index, Claim claim) {
        if (claim == null || index < 0 || index >= claims.size()) {
            return false;
        }
        claims.set(index, claim);
        return true;
    }

    // Remove by name, answering what was removed, or null.
    Claim remove(String handle) {
        String wanted = handleOf(handle);
        if (wanted == null) {
            return null;
        }
        Claim removed = null;
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).isCalledTrimmed(wanted)) {
                removed = claims.remove(i);
                break;
            }
        }
        return removed;
    }

    // Never throws. An unreadable file comes back as an empty book with unreadable() set.
    public static ClaimBook load(Path file) {
        ClaimBook book = new ClaimBook();
        if (file != null) {
            String body = null;
            try {
                body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (NoSuchFileException absent) {
            } catch (IOException couldNotRead) {
                boolean genuinelyAbsent;
                try {
                    genuinelyAbsent = Files.notExists(file);
                } catch (RuntimeException unknowable) {
                // A provider that cannot even answer an existence check has told us
                // nothing that excuses the empty-book branch below; treat it the same
                // as "exists", which is the side that already sets unreadable.
                    genuinelyAbsent = false;
                }
                if (!genuinelyAbsent) {
                    book.unreadable = couldNotRead.toString();
                }
            }
            if (body != null) {
                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonElement foundMarkers = root.get("markers");
                    if (foundMarkers != null && !foundMarkers.isJsonArray()) {
                        throw new IllegalArgumentException("markers is "
                                + foundMarkers.getClass().getSimpleName() + ", not an array");
                    }
                    JsonArray markers = foundMarkers == null
                            ? new JsonArray() : foundMarkers.getAsJsonArray();
                    JsonElement found = root.get("areas");

                    // A present "areas" that is not an array is a corrupt file; a missing one is an empty book.
                    if (found != null && !found.isJsonArray()) {
                        throw new IllegalArgumentException(
                                "areas is " + found.getClass().getSimpleName() + ", not an array");
                    }
                    JsonArray areas = found == null ? new JsonArray() : found.getAsJsonArray();
                    List<Claim> read = new ArrayList<>(areas.size());
                    Map<String, Integer> ids = new HashMap<>();
                    Map<String, Integer> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    int index = 0;
                    for (JsonElement element : areas) {
                        JsonObject area = element.getAsJsonObject();
                        String id = text(area, "id").trim();
                        if (!id.isEmpty()) {
                            Integer first = ids.put(id, index);
                            if (first != null) {
                                throw new IllegalArgumentException("claims " + first + " and "
                                        + index + " share the id " + id
                                        + ". An edit for one would rewrite the other");
                            }
                        }
                        Claim made = one(area, index, id);
                        refuseSharedName(names, made.name(), index);
                        read.add(made);
                        index++;
                    }
                    // Assigned only once every entry has been read.
                    book.claims.addAll(read);
                    book.markers = markers;
                } catch (RuntimeException notWhatWeExpected) {
                    book.claims.clear();
                    book.unreadable = notWhatWeExpected.toString();
                }
            }
        }
        return book;
    }

    // Labels only, without building Claim objects. Empty exactly where load() sets unreadable().
    static List<String> names(Path file) {
        if (file == null) {
            return List.of();
        }
        List<String> labels = List.of();
        String body = null;
        try {
            body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException couldNotRead) {
        }
        if (body != null) {
            try {
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonElement found = root.get("areas");
                if (found != null && !found.isJsonArray()) {
                    throw new IllegalArgumentException(
                            "areas is " + found.getClass().getSimpleName() + ", not an array");
                }
                JsonArray areas = found == null ? new JsonArray() : found.getAsJsonArray();
                List<String> read = new ArrayList<>(areas.size());
                Map<String, Integer> ids = new HashMap<>();
                Map<String, Integer> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                double[][] corners = new double[CORNER_AXES][];
                int index = 0;
                for (JsonElement element : areas) {
                    JsonObject area = element.getAsJsonObject();
                    String id = member(area, "id").trim();
                    if (!id.isEmpty()) {
                        Integer first = ids.put(id, index);
                        if (first != null) {
                            throw new IllegalArgumentException("claims " + first + " and "
                                    + index + " share the id " + id
                                    + ". An edit for one would rewrite the other");
                        }
                    }
                    String name = label(area, index, id, corners);
                    refuseSharedName(names, name, index);
                    read.add(name);
                    index++;
                }
                labels = Collections.unmodifiableList(read);
            } catch (RuntimeException notWhatWeExpected) {
                labels = List.of();
            }
        }
        return labels;
    }

    private static void refuseSharedName(Map<String, Integer> seen, String name,
                                         int index) {
        Integer first = seen.put(name, index);
        if (first != null) {
            throw new IllegalArgumentException("claims " + first + " and " + index
                    + " share the name " + name
                    + ". An edit for one would only reach the other");
        }
    }

    // Refuses the same reasons as one(), same order. corners is reused across calls.
    private static String label(JsonObject area, int index, String id,
                                double[][] corners) {
        int xs = fill(array(area, "xs", index), corners, 0);
        int zs = fill(array(area, "zs", index), corners, 1);
        String name = member(area, "label");
        member(area, "owner");
        member(area, "ownerId");
        String world = member(area, "world");
        colour(member(area, "colour"));
        longAt(area, "revised");
        required(id, "id");
        name = required(name, "name");
        required(world, "dimension");
        if (xs != zs) {
            throw new IllegalArgumentException("a boundary with " + xs
                    + " x values and " + zs + " z values is not a boundary"
                    + "");
        }
        if (xs < Claim.MIN_CORNERS) {
            throw new IllegalArgumentException(xs + " corners cannot enclose"
                    + " anything. A claim wants at least " + Claim.MIN_CORNERS);
        }
        if (xs > Claim.MAX_CORNERS) {
            throw new IllegalArgumentException(xs + " corners is more than the "
                    + Claim.MAX_CORNERS + " the map viewer will draw");
        }
        inside(corners[0], xs, "x");
        inside(corners[1], zs, "z");
        if (Claim.enclosesNothing(corners[0], corners[1], xs)) {
            throw new IllegalArgumentException("these " + xs
                    + " corners enclose no ground. A claim wants an inside");
        }
        return name;
    }

    private static int fill(JsonArray values, double[][] corners, int axis) {
        int count = values.size();
        double[] into = corners[axis];
        if (into == null || into.length < count) {
            into = new double[count];
            corners[axis] = into;
        }
        for (int i = 0; i < count; i++) {
            into[i] = values.get(i).getAsDouble();
        }
        return count;
    }

    private static void inside(double[] values, int count, String axis) {
        for (int i = 0; i < count; i++) {
            if (!(Math.abs(values[i]) <= Claim.LIMIT)) {
                throw new IllegalArgumentException("corner " + i + " has " + axis
                        + " = " + values[i] + ": not inside the"
                        + " world border at " + (long) Claim.LIMIT);
            }
        }
    }

    private static String required(String value, String field) {
        String out = value.trim();
        if (out.isEmpty()) {
            throw new IllegalArgumentException("a claim needs a " + field
                    + "; a blank one names nothing");
        }
        return out;
    }

    private static Claim one(JsonObject area, int index, String id) {
        double[] xs = doubles(area, "xs", index);
        double[] zs = doubles(area, "zs", index);
        String name = text(area, "label");
        Claim.Identity who = new Claim.Identity(id, text(area, "owner"),
                text(area, "ownerId"), text(area, "world"));
        return Claim.fromOwnedArrays(who, name, xs, zs, colour(text(area, "colour")),
                longAt(area, "revised"));
    }

    // The whole book, as the node will read it.
    private JsonObject document(long savedAt) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("saved", savedAt);
        JsonArray areas = new JsonArray(claims.size());
        for (Claim claim : claims) {
            JsonObject one = new JsonObject();
            one.addProperty("id", claim.id());
            one.addProperty("label", claim.name());
            one.addProperty("set", SET);
            one.addProperty("world", claim.dimension());
            one.addProperty("line", claim.lineColour());
            one.addProperty("fill", claim.fillColour());
            one.add("xs", numbers(claim.rawXs()));
            one.add("zs", numbers(claim.rawZs()));
            one.addProperty("colour", claim.colour().name());
            one.addProperty("owner", claim.owner());
            one.addProperty("ownerId", claim.ownerId());
            one.addProperty("revised", claim.revised());
            areas.add(one);
        }
        root.add("areas", areas);
        // Empty, always.
        root.add("markers", markers);
        return root;
    }

    private int corners() {
        int total = 0;
        for (Claim claim : claims) {
            total += claim.corners();
        }
        return total;
    }

    String toJson(long savedAt) {
        return document(savedAt).toJson(DOCUMENT_ENVELOPE + claims.size() * PER_CLAIM
                + corners() * PER_CORNER);
    }

    // Write the whole book, atomically. Refuses when the book came from an unreadable file.
    public void save(Path file, long savedAt) throws IOException {
        if (!unreadable.isEmpty()) {
            throw new IOException(file + " could not be read (" + unreadable
                    + "). Writing would lose what is in it."
                    + " Move that file aside; the next save starts fresh.");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String staged = file.getFileName() + "." + Thread.currentThread().threadId()
                + ".tmp";
        Path temp = parent == null
                ? file.getFileSystem().getPath(staged)
                : parent.resolve(staged);
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                JsonBind.compact().toJson(document(savedAt), writer);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException undeletable) {
                failed.addSuppressed(undeletable);
            }
            throw failed;
        }
        FileReplace.replace(temp, file, true, FileReplace.ALWAYS);
    }

    private static JsonArray numbers(double[] values) {
        JsonArray out = new JsonArray(values.length);
        for (double value : values) {
            if (Double.compare(value, Math.rint(value)) == 0) {
                out.add((long) Math.rint(value));
            } else {
                out.add(value);
            }
        }
        return out;
    }

    private static String text(JsonObject parent, String name) {
        return member(parent, name);
    }

    // A member as a string; empty when absent or null, throws for an object or array.
    private static String member(JsonObject parent, String name) {
        JsonElement found = parent.get(name);
        return found == null || found.isJsonNull() ? "" : found.getAsString();
    }

    private static MarkerColour colour(String name) {
        if (name.isBlank()) {
            return MarkerColour.BLACK;
        }
        MarkerColour found = null;
        for (MarkerColour candidate : COLOURS) {
            if (candidate.name().equalsIgnoreCase(name)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("unknown claim colour: " + name);
        }
        return found;
    }

    private static long longAt(JsonObject parent, String name) {
        JsonElement found = parent.get(name);
        return found == null || found.isJsonNull() ? 0L : found.getAsLong();
    }

    private static double[] doubles(JsonObject parent, String name, int index) {
        JsonArray values = array(parent, name, index);
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i).getAsDouble();
        }
        return out;
    }

    private static JsonArray array(JsonObject parent, String name, int index) {
        JsonElement found = parent.get(name);
        if (found == null || !found.isJsonArray()) {
            throw new IllegalArgumentException("claim " + index + " has no " + name
                    + " array: no boundary");
        }
        return found.getAsJsonArray();
    }
}
