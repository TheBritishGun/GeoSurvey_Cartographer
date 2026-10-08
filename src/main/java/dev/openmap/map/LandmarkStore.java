package dev.openmap.map;

import dev.openmap.json.AtomicFileReplace;
import dev.openmap.json.JsonBind;
import dev.openmap.json.JsonParseException;
import dev.openmap.symbol.Affiliation;
import dev.openmap.symbol.SymbolIcon;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LandmarkStore {

    public static final String EXTENSION = ".landmarks.json";

    private static final int LINEAR_SCAN_MAX = 64;

    private static final JsonBind JSON = JsonBind.pretty();

    private static final DiskCopy NO_DISK_COPY = new DiskCopy(null, -1L);

    // The one sequence that every store's revisions come from.
    private static final java.util.concurrent.atomic.AtomicLong REVISIONS =
            new java.util.concurrent.atomic.AtomicLong();

    private record DiskCopy(Path path, long revision) {
    }

    private final ArrayList<Landmark> landmarks = new ArrayList<>();

    private final Map<Long, ArrayList<Landmark>> grid = new HashMap<>();

    private final Map<String, ArrayList<Landmark>> nameIndex = new HashMap<>();

    private String unreadable = "";

    private Path unreadablePath;

    private volatile long revision = REVISIONS.incrementAndGet();

    private volatile DiskCopy onDisk = NO_DISK_COPY;

    private volatile boolean atomicMoveUnsupported;

    boolean countProbes;

    long probes;

    public List<Landmark> all() {
        return Collections.unmodifiableList(landmarks);
    }

    public int size() {
        return landmarks.size();
    }

    public void add(Landmark landmark) {
        landmark.normalise();
        landmarks.add(landmark);
        gridAdd(landmark);
        nameIndexAdd(landmark);
        revision = REVISIONS.incrementAndGet();
    }

    public boolean remove(Landmark landmark) {
        boolean removed = landmarks.remove(landmark);
        if (removed) {
            gridRemove(landmark);
            nameIndexRemove(landmark);
            revision = REVISIONS.incrementAndGet();
        }
        return removed;
    }

    public void clear() {
        landmarks.clear();
        grid.clear();
        nameIndex.clear();
        revision = REVISIONS.incrementAndGet();
    }

    // Puts back a list and a revision this store held before; each marker keeps its fields.
    public void restore(List<Landmark> held, long heldRevision) {
        List<Landmark> kept = new ArrayList<>(held);
        landmarks.clear();
        grid.clear();
        nameIndex.clear();
        for (Landmark landmark : kept) {
            landmarks.add(landmark);
            gridAdd(landmark);
            nameIndexAdd(landmark);
        }
        revision = heldRevision;
    }

    // Returns the normalised name.
    public String rename(Landmark landmark, String newName) {
        nameIndexRemove(landmark);
        landmark.setName(newName);
        nameIndexAdd(landmark);
        revision = REVISIONS.incrementAndGet();
        return landmark.name();
    }

    // Zero-alpha colours become opaque.
    public void setColour(Landmark landmark, int argb) {
        landmark.setColour(argb);
        revision = REVISIONS.incrementAndGet();
    }

    public void setIcon(Landmark landmark, SymbolIcon icon) {
        landmark.setIcon(icon);
        revision = REVISIONS.incrementAndGet();
    }

    public void setAffiliation(Landmark landmark, Affiliation affiliation) {
        landmark.setAffiliation(affiliation);
        revision = REVISIONS.incrementAndGet();
    }

    // shared may be null: the marker is not shared.
    public void setShared(Landmark landmark, String shared) {
        landmark.setShared(shared);
        revision = REVISIONS.incrementAndGet();
    }

    // A copy; never null.
    public List<Landmark> byName(String typed) {
        ArrayList<Landmark> found = nameIndex.get(nameKey(typed));
        return found == null ? Collections.emptyList() : new ArrayList<>(found);
    }

    public int countByName(String typed) {
        ArrayList<Landmark> found = nameIndex.get(nameKey(typed));
        return found == null ? 0 : found.size();
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private void nameIndexAdd(Landmark landmark) {
        nameIndex.computeIfAbsent(nameKey(landmark.name()), unused -> new ArrayList<>())
                .add(landmark);
    }

    private void nameIndexRemove(Landmark landmark) {
        String key = nameKey(landmark.name());
        ArrayList<Landmark> found = nameIndex.get(key);
        if (found != null) {
            found.remove(landmark);
            if (found.isEmpty()) {
                nameIndex.remove(key);
            }
        }
    }

    private static long cellKeyOf(Landmark landmark) {
        return MapRegion.key(MapRegion.ofBlock(landmark.x()), MapRegion.ofBlock(landmark.z()));
    }

    private void gridAdd(Landmark landmark) {
        grid.computeIfAbsent(cellKeyOf(landmark), unused -> new ArrayList<>()).add(landmark);
    }

    private void gridRemove(Landmark landmark) {
        long key = cellKeyOf(landmark);
        ArrayList<Landmark> cell = grid.get(key);
        if (cell != null) {
            cell.remove(landmark);
            if (cell.isEmpty()) {
                grid.remove(key);
            }
        }
    }

    private static int clampToInt(long value) {
        return value < Integer.MIN_VALUE ? Integer.MIN_VALUE
                : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public Landmark nearest(int x, int z, int maxBlocks) {
        if (countProbes) {
            probes = 0;
        }
        Landmark result;
        if (maxBlocks < 0) {
            result = null;
        } else {
            long limit = (long) maxBlocks * maxBlocks;
            if (landmarks.size() <= LINEAR_SCAN_MAX) {
                result = nearestLinear(x, z, limit);
            } else {
                int cellX0 = MapRegion.ofBlock(clampToInt((long) x - maxBlocks));
                int cellX1 = MapRegion.ofBlock(clampToInt((long) x + maxBlocks));
                int cellZ0 = MapRegion.ofBlock(clampToInt((long) z - maxBlocks));
                int cellZ1 = MapRegion.ofBlock(clampToInt((long) z + maxBlocks));
                Landmark best = null;
                long bestDistance = Long.MAX_VALUE;
                Iterator<Map.Entry<Long, ArrayList<Landmark>>> entries = grid.entrySet().iterator();
                while (bestDistance != 0L && entries.hasNext()) {
                    Map.Entry<Long, ArrayList<Landmark>> entry = entries.next();
                    long key = entry.getKey();
                    int cellX = MapRegion.x(key);
                    int cellZ = MapRegion.z(key);
                    if (cellX < cellX0 || cellX > cellX1 || cellZ < cellZ0 || cellZ > cellZ1) {
                        continue;
                    }
                    ArrayList<Landmark> cell = entry.getValue();
                    int n = cell.size();
                    for (int i = 0; bestDistance != 0L && i < n; i++) {
                        Landmark candidate = cell.get(i);
                        if (countProbes) {
                            probes++;
                        }
                        long d = candidate.distanceSquared(x, z);
                        if (d <= limit && d < bestDistance) {
                            best = candidate;
                            bestDistance = d;
                        }
                    }
                }
                result = best;
            }
        }
        return result;
    }

    private Landmark nearestLinear(int x, int z, long limit) {
        Landmark best = null;
        long bestDistance = Long.MAX_VALUE;
        int n = landmarks.size();
        for (int i = 0; bestDistance != 0L && i < n; i++) {
            Landmark candidate = landmarks.get(i);
            if (countProbes) {
                probes++;
            }
            long d = candidate.distanceSquared(x, z);
            if (d <= limit && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    public List<Landmark> inBounds(int minX, int minZ, int maxX, int maxZ) {
        if (countProbes) {
            probes = 0;
        }
        int x0 = Math.min(minX, maxX);
        int x1 = Math.max(minX, maxX);
        int z0 = Math.min(minZ, maxZ);
        int z1 = Math.max(minZ, maxZ);
        List<Landmark> result;
        if (landmarks.size() <= LINEAR_SCAN_MAX) {
            result = inBoundsLinear(x0, x1, z0, z1);
        } else {
            int cellX0 = MapRegion.ofBlock(x0);
            int cellX1 = MapRegion.ofBlock(x1);
            int cellZ0 = MapRegion.ofBlock(z0);
            int cellZ1 = MapRegion.ofBlock(z1);
            List<Landmark> out = null;
            for (Map.Entry<Long, ArrayList<Landmark>> entry : grid.entrySet()) {
                long key = entry.getKey();
                int cellX = MapRegion.x(key);
                int cellZ = MapRegion.z(key);
                if (cellX < cellX0 || cellX > cellX1 || cellZ < cellZ0 || cellZ > cellZ1) {
                    continue;
                }
                ArrayList<Landmark> cell = entry.getValue();
                int n = cell.size();
                for (int i = 0; i < n; i++) {
                    Landmark l = cell.get(i);
                    if (countProbes) {
                        probes++;
                    }
                    if (l.x() >= x0 && l.x() <= x1 && l.z() >= z0 && l.z() <= z1) {
                        if (out == null) {
                            out = new ArrayList<>();
                        }
                        out.add(l);
                    }
                }
            }
            result = out == null ? Collections.emptyList() : out;
        }
        return result;
    }

    private List<Landmark> inBoundsLinear(int x0, int x1, int z0, int z1) {
        List<Landmark> out = null;
        int n = landmarks.size();
        for (int i = 0; i < n; i++) {
            Landmark l = landmarks.get(i);
            if (countProbes) {
                probes++;
            }
            if (l.x() >= x0 && l.x() <= x1 && l.z() >= z0 && l.z() <= z1) {
                if (out == null) {
                    out = new ArrayList<>();
                }
                out.add(l);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    private static LandmarkStore persisted(LandmarkStore store, Path path) {
        store.onDisk = new DiskCopy(path, store.revision);
        return store;
    }

    public static LandmarkStore load(Path path) {
        LandmarkStore store = new LandmarkStore();
        boolean readable = path != null;
        boolean absent = false;
        if (readable) {
            try {
                readable = isRegularFile(path);
            } catch (NoSuchFileException notThere) {
                readable = false;
                absent = true;
            } catch (IOException e) {
                String message = e.getMessage();
                store.unreadable = e.getClass().getSimpleName()
                        + (message == null ? "" : ": " + message);
                store.unreadablePath = path;
                readable = false;
            }
        }
        if (absent) {
            try {
                readable = AtomicFileReplace.recoverStaleAside(path) && isRegularFile(path);
            } catch (IOException e) {
                String message = e.getMessage();
                store.unreadable = e.getClass().getSimpleName()
                        + (message == null ? "" : ": " + message);
            }
        }
        if (readable) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                List<Landmark> loaded = JSON.fromJsonList(reader, Landmark.class);
                if (loaded != null) {
                    int total = loaded.size();
                    for (Landmark l : loaded) {
                        if (l != null) {
                            store.add(l);
                        }
                    }
                    int kept = store.landmarks.size();
                    if (kept < total) {
                        store.unreadable = (total - kept) + " of " + total
                                + " landmarks unreadable";
                        store.unreadablePath = path;
                    }
                } else {
                    store.unreadable = "no landmark list found";
                    store.unreadablePath = path;
                }
            } catch (IOException | JsonParseException e) {
                store.landmarks.clear();
                store.grid.clear();
                store.nameIndex.clear();
                String message = e.getMessage();
                store.unreadable = e.getClass().getSimpleName()
                        + (message == null ? "" : ": " + message);
                store.unreadablePath = path;
            }
        }
        return persisted(store, path);
    }

    // The read failure, or empty.
    public String unreadable() {
        clearUnreadableWhenMovedAside();
        return unreadable;
    }

    private void clearUnreadableWhenMovedAside() {
        if (!unreadable.isEmpty() && unreadablePath != null) {
            try {
                if (!isRegularFile(unreadablePath)) {
                    clearUnreadable();
                }
            } catch (NoSuchFileException ignored) {
                clearUnreadable();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean isRegularFile(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class).isRegularFile();
    }

    private void clearUnreadable() {
        unreadable = "";
        unreadablePath = null;
    }

    // Ignores Landmark setters called directly.
    public long revision() {
        return revision;
    }

    // A save would change nothing.
    public boolean isPersisted(Path path) {
        DiskCopy held = onDisk;
        return path != null && path.equals(held.path()) && revision == held.revision()
                && Files.exists(path);
    }

    // Refuses after a failed load.
    public void save(Path path) throws IOException {
        save(path, false);
    }

    public void save(Path path, boolean force) throws IOException {
        long written = revision;
        Path aside = writeAside(path, force);
        if (aside != null) {
            moveInto(aside, path, written, AtomicFileReplace.ALWAYS);
        }
    }

    Path writeAside(Path path, boolean force) throws IOException {
        clearUnreadableWhenMovedAside();
        if (!unreadable.isEmpty()) {
            throw new IOException(path + " could not be read (" + unreadable
                    + ")."
                    + " Move that file aside; the next save starts fresh.");
        }
        Path aside;
        if (!force && isPersisted(path)) {
            aside = null;
        } else {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            aside = path.resolveSibling(path.getFileName() + "."
                    + Thread.currentThread().threadId() + ".tmp");
            AtomicFileReplace.writeTemp(aside, written -> {
                try (Writer writer = Files.newBufferedWriter(written, StandardCharsets.UTF_8)) {
                    JSON.toJson(landmarks, writer);
                }
            });
        }
        return aside;
    }

    void moveInto(Path aside, Path path, long written, AtomicFileReplace.Turn turn)
            throws IOException {
        AtomicFileReplace.Moved moved =
                AtomicFileReplace.replace(aside, path, !atomicMoveUnsupported, turn);
        if (moved == AtomicFileReplace.Moved.WITHOUT_ATOMIC_MOVE) {
            atomicMoveUnsupported = true;
        }
        if (moved != AtomicFileReplace.Moved.UNWANTED) {
            onDisk = new DiskCopy(path, written);
        }
    }
}
