package dev.openmap.claim;

import dev.openmap.draw.MarkerColour;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.json.JsonArray;
import dev.openmap.json.JsonBind;
import dev.openmap.json.JsonElement;
import dev.openmap.json.JsonObject;
import dev.openmap.json.JsonParser;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

    private static final String SET = "geosurvey:authored";

    // Ceiling on add() only.
    static final int MAX_CLAIMS = 256;

    private static final int VERSION = 1;

    private static final String RETIRED = "claims-authored.retired";

    private static final String RETIRED_EXTENSION = ".json";

    static final int MAX_RETIRED = 16;

    private static final String REMOVED = "removed";

    static final int MAX_REMOVED = MAX_CLAIMS;

    private static final String PORTED = "claims-authored.ported";

    private static final int DOCUMENT_ENVELOPE = 64;

    private static final int PER_CLAIM = 256;

    private static final int PER_CORNER = 12;

    private static final int CORNER_AXES = 2;

    private static final MarkerColour[] COLOURS = MarkerColour.values();

    private final List<Claim> claims = new ArrayList<>();

    private final List<String> removedIds = new ArrayList<>();

    private final List<Claim> stoppedSharing = new ArrayList<>();

    private String unreadable = "";

    private JsonArray markers = new JsonArray();

    ClaimBook() {
    }

    public static Path bookIn(Path world) {
        return world == null ? null : world.resolve(FILE);
    }

    public List<Claim> claims() {
        return Collections.unmodifiableList(claims);
    }

    public int size() {
        return claims.size();
    }

    // The read failure, or empty.
    public String unreadable() {
        return unreadable;
    }

    boolean full() {
        return claims.size() >= MAX_CLAIMS;
    }

    // Ignores case and padding.
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

    // The first claim, other than the one at except, whose name cleans like this name; -1 for none.
    int indexByCleanName(String name, int except) {
        String wanted = name == null ? "" : cleanName(name);
        int found = -1;
        if (!wanted.isEmpty()) {
            for (int i = 0; i < claims.size(); i++) {
                if (i != except && cleanName(claims.get(i).name()).equalsIgnoreCase(wanted)) {
                    found = i;
                    break;
                }
            }
        }
        return found;
    }

    private static String cleanName(String raw) {
        return Claim.cleaned(raw).trim();
    }

    private static String handleOf(String handle) {
        String wanted = handle == null ? null : handle.trim();
        return wanted == null || wanted.isEmpty() ? null : wanted;
    }

    Claim at(int index) {
        return index < 0 || index >= claims.size() ? null : claims.get(index);
    }

    public boolean add(Claim claim) {
        return insertIfAllowed(claim) == Refusal.NONE;
    }

    enum Refusal {

        NONE,

        NULL,

        DUPLICATE,

        FULL
    }

    Refusal insertIfAllowed(Claim claim) {
        if (claim == null) {
            return Refusal.NULL;
        }
        if (indexByCleanName(claim.name(), -1) >= 0) {
            return Refusal.DUPLICATE;
        }
        if (full()) {
            return Refusal.FULL;
        }
        claims.add(claim);
        return Refusal.NONE;
    }

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

    boolean replaceAt(int index, Claim claim) {
        if (claim == null || index < 0 || index >= claims.size()) {
            return false;
        }
        claims.set(index, claim);
        return true;
    }

    public Claim byId(String id) {
        Claim found = null;
        for (Claim claim : claims) {
            if (claim.id().equals(id)) {
                found = claim;
                break;
            }
        }
        return found;
    }

    public boolean isShared(String id) {
        Claim found = byId(id);
        return found != null && found.shared();
    }

    // The shared claims this copy took out of sharing, by remove or unshare, oldest first.
    public List<Claim> stoppedSharing() {
        return Collections.unmodifiableList(stoppedSharing);
    }

    public boolean setShared(String id, boolean on) {
        boolean found = false;
        for (int at = 0; !found && at < claims.size(); at++) {
            Claim claim = claims.get(at);
            found = claim.id().equals(id);
            if (found && claim.shared() != on) {
                claims.set(at, claim.sharedAs(on));
                if (!on) {
                    stoppedSharing.add(claim);
                }
            }
        }
        return found;
    }

    public boolean removedHere(String id) {
        return removedIds.contains(id);
    }

    public List<String> removedIds() {
        return Collections.unmodifiableList(removedIds);
    }

    public Claim remove(String handle) {
        String wanted = handleOf(handle);
        if (wanted == null) {
            return null;
        }
        Claim removed = null;
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).isCalledTrimmed(wanted)) {
                removed = claims.remove(i);
                keepRemoved(removed.id());
                if (removed.shared()) {
                    stoppedSharing.add(removed);
                }
                break;
            }
        }
        return removed;
    }

    private void keepRemoved(String id) {
        removedIds.remove(id);
        removedIds.add(id);
        if (removedIds.size() > MAX_REMOVED + MAX_REMOVED) {
            trimRemoved(removedIds);
        } else {
            while (removedIds.size() > MAX_REMOVED) {
                removedIds.remove(0);
            }
        }
    }

    private static void trimRemoved(List<String> ids) {
        int excess = ids.size() - MAX_REMOVED;
        for (int at = 0; at < MAX_REMOVED; at++) {
            ids.set(at, ids.get(excess + at));
        }
        while (ids.size() > MAX_REMOVED) {
            ids.remove(ids.size() - 1);
        }
    }

    public static Path retire(Path shared) throws IOException {
        Path retired = null;
        if (shared != null) {
            AtomicFileReplace.recoverStaleAside(shared);
            if (Files.isRegularFile(shared)) {
                retired = freeRetiredName(shared);
                Files.move(shared, retired);
            }
        }
        return retired;
    }

    public static List<Path> retiredCopies(Path shared) {
        List<Path> found = new ArrayList<>();
        if (shared != null) {
            for (int copy = 1; copy <= MAX_RETIRED; copy++) {
                Path candidate = retiredName(shared, copy);
                if (Files.isRegularFile(candidate)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    public static boolean ported(Path book) {
        return Files.exists(book.resolveSibling(PORTED), LinkOption.NOFOLLOW_LINKS);
    }

    public static void markPorted(Path book) throws IOException {
        Path mark = book.resolveSibling(PORTED);
        Path parent = book.getParent();
        if ((parent != null) && !Files.exists(mark, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
            Files.createFile(mark);
        }
    }

    private static Path freeRetiredName(Path shared) throws IOException {
        Path free = null;
        for (int copy = 1; free == null && copy <= MAX_RETIRED; copy++) {
            Path candidate = retiredName(shared, copy);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                free = candidate;
            }
        }
        if (free == null) {
            throw new FileAlreadyExistsException(retiredName(shared, MAX_RETIRED).toString(), null,
                    "every retired name is taken; the shared book stays");
        }
        return free;
    }

    private static Path retiredName(Path shared, int copy) {
        return shared.resolveSibling(copy == 1 ? RETIRED + RETIRED_EXTENSION
                : RETIRED + "-" + copy + RETIRED_EXTENSION);
    }

    // Never throws; an unreadable file gives an empty book.
    public static ClaimBook load(Path file) {
        ClaimBook book = new ClaimBook();
        if (file != null) {
            String body = null;
            boolean absent = false;
            try {
                body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (NoSuchFileException notThere) {
                absent = true;
            } catch (IOException couldNotRead) {
                boolean genuinelyAbsent;
                try {
                    genuinelyAbsent = Files.notExists(file);
                } catch (RuntimeException unknowable) {
                    genuinelyAbsent = false;
                }
                if (!genuinelyAbsent) {
                    book.unreadable = couldNotRead.toString();
                }
            }
            if (absent) {
                try {
                    body = recovered(file);
                } catch (IOException couldNotRecover) {
                    book.unreadable = couldNotRecover.toString();
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

                    if (found != null && !found.isJsonArray()) {
                        throw new IllegalArgumentException(
                                "areas is " + found.getClass().getSimpleName() + ", not an array");
                    }
                    JsonArray areas = found == null ? new JsonArray() : found.getAsJsonArray();
                    List<String> readRemoved = removedIn(root.get(REMOVED));
                    String[] keptNames = keptStoredNames(areas);
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
                                        + index + " share the id " + id);
                            }
                        }
                        Claim made = one(area, index, id);
                        if (keptNames[index] != null) {
                            made = made.storedAs(keptNames[index]);
                        }
                        refuseSharedName(names, made.name(), index);
                        read.add(made);
                        index++;
                    }
                    book.claims.addAll(read);
                    book.removedIds.addAll(readRemoved);
                    book.markers = markers;
                } catch (RuntimeException notWhatWeExpected) {
                    book.claims.clear();
                    book.unreadable = notWhatWeExpected.toString();
                }
            }
        }
        return book;
    }

    // Empty when the file is unreadable.
    static List<String> names(Path file) {
        if (file == null) {
            return List.of();
        }
        List<String> labels = List.of();
        String body = null;
        boolean absent = false;
        try {
            body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException notThere) {
            absent = true;
        } catch (IOException couldNotRead) {
        }
        if (absent) {
            try {
                body = recovered(file);
            } catch (IOException couldNotRecover) {
            }
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
                String[] keptNames = keptStoredNames(areas);
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
                                    + index + " share the id " + id);
                        }
                    }
                    String name = label(area, index, id, corners);
                    if (keptNames[index] != null) {
                        name = keptNames[index];
                    }
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

    private static String recovered(Path file) throws IOException {
        String body = null;
        if (AtomicFileReplace.recoverStaleAside(file)) {
            body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
        return body;
    }

    private static List<String> removedIn(JsonElement found) {
        List<String> ids = new ArrayList<>();
        if (found != null) {
            if (!found.isJsonArray()) {
                throw new IllegalArgumentException(REMOVED + " is " + found.getClass().getSimpleName()
                        + ", not an array");
            }
            for (JsonElement id : found.getAsJsonArray()) {
                if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(REMOVED + " holds " + id + ", not a claim id");
                }
                ids.add(id.getAsString());
            }
        }
        return ids;
    }

    // Each area whose name cleans like another area's name, as stored; null for the rest.
    private static String[] keptStoredNames(JsonArray areas) {
        String[] kept = new String[areas.size()];
        String[] stored = new String[kept.length];
        Map<String, Integer> first = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int at = 0; at < kept.length; at++) {
            stored[at] = storedLabel(areas.get(at));
            String clean = stored[at] == null ? "" : cleanName(stored[at]);
            if (!clean.isEmpty()) {
                Integer earlier = first.putIfAbsent(clean, at);
                if (earlier != null) {
                    kept[at] = stored[at];
                    kept[earlier] = stored[earlier];
                }
            }
        }
        return kept;
    }

    // The name an area is stored by, trimmed; null when load would read no name from it.
    private static String storedLabel(JsonElement area) {
        String stored = null;
        if (area.isJsonObject()) {
            JsonElement label = area.getAsJsonObject().get("label");
            if (label != null && label.isJsonPrimitive()) {
                stored = label.getAsString().trim();
            }
        }
        return stored;
    }

    private static void refuseSharedName(Map<String, Integer> seen, String name,
                                         int index) {
        Integer first = seen.put(name, index);
        if (first != null) {
            throw new IllegalArgumentException("claims " + first + " and " + index
                    + " share the name " + name);
        }
    }

    // Refuses what one() refuses, in the same order.
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
        name = required(Claim.cleaned(name), "name");
        required(world, "dimension");
        if (xs != zs) {
            throw new IllegalArgumentException("boundary has " + xs
                    + " x values but " + zs + " z values");
        }
        if (xs < Claim.MIN_CORNERS) {
            throw new IllegalArgumentException(xs + " corners; the minimum"
                    + " is " + Claim.MIN_CORNERS);
        }
        if (xs > Claim.MAX_CORNERS) {
            throw new IllegalArgumentException(xs + " corners; at most "
                    + Claim.MAX_CORNERS + " are allowed");
        }
        inside(corners[0], xs, "x");
        inside(corners[1], zs, "z");
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
                        + " = " + values[i] + ", outside the"
                        + " world border at " + (long) Claim.LIMIT);
            }
        }
    }

    private static String required(String value, String field) {
        String out = value.trim();
        if (out.isEmpty()) {
            throw new IllegalArgumentException("claim " + field
                    + " is blank");
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
                longAt(area, "revised"), markedShared(area));
    }

    private static boolean markedShared(JsonObject area) {
        JsonElement flag = area.get("shared");
        return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()
                && flag.getAsBoolean();
    }

    // The node's file format.
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
            if (claim.shared()) {
                one.addProperty("shared", true);
            }
            areas.add(one);
        }
        root.add("areas", areas);
        if (!removedIds.isEmpty()) {
            JsonArray removed = new JsonArray(removedIds.size());
            for (String id : removedIds) {
                removed.add(id);
            }
            root.add(REMOVED, removed);
        }
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

    public void save(Path file, long savedAt) throws IOException {
        if (!unreadable.isEmpty()) {
            throw new IOException(file + " could not be read (" + unreadable
                    + ")."
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
        AtomicFileReplace.write(temp, file, written -> {
            try (Writer writer = Files.newBufferedWriter(written, StandardCharsets.UTF_8)) {
                JsonBind.compact().toJson(document(savedAt), writer);
            }
        });
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

    // Throws for an object or array.
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
                    + " array");
        }
        return found.getAsJsonArray();
    }
}
