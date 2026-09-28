package dev.openmap.draw;

import dev.openmap.json.JsonBind;
import dev.openmap.json.JsonParseException;
import dev.openmap.map.MapStorage.FileReplace;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AnnotationStore {

    public static final String EXTENSION = ".overlay.json";

    public static final int MAX_FILE_CHARS = 64 << 20;

    public static final int MAX_FILE_VALUES = 1 << 21;

    private static final JsonBind JSON = JsonBind.compact();

    private static final int INITIAL_GRID_CAPACITY = 16;

    private static final int INITIAL_CANDIDATE_MARK_CAPACITY = 4;

    private static final int CAPACITY_GROWTH_FACTOR = 2;

    private static final int GRID_LOAD_FACTOR_DENOMINATOR = 2;

    private static final int CELL_KEY_COORDINATE_BITS = 32;

    private static final long CELL_KEY_COORDINATE_MASK = 0xFFFFFFFFL;

    private static final int GRID_HASH_SHIFT = 16;

    private static final int GRID_HASH_MULTIPLIER = 0x7FEB352D;

    private static final int GRID_HASH_SECOND_SHIFT = 15;

    private static final double CELL_SIZE = 128;

    private static final int MAX_CELLS_PER_ANNOTATION = 256;

    private final ArrayList<Annotation> annotations = new ArrayList<>();

    private double[] bounds = new double[Annotation.BOUNDS_PER_ANNOTATION];

    private long[] gridKeys = new long[INITIAL_GRID_CAPACITY];

    private IntList[] gridLists = new IntList[INITIAL_GRID_CAPACITY];

    private int gridUsed;

    private final IntList candidates = new IntList();

    private final IntList globalEntries = new IntList();

    private int[] candidateMarks = new int[INITIAL_CANDIDATE_MARK_CAPACITY];

    private int candidateEpoch;

    private int candidatesExamined;

    private final List<Annotation> unmodifiableAnnotations =
            Collections.unmodifiableList(annotations);

    public List<Annotation> all() {
        return unmodifiableAnnotations;
    }

    public int size() {
        return annotations.size();
    }

    public boolean add(Annotation annotation) {
        return addNormalised(annotation.normalise());
    }

    private boolean addNormalised(Annotation annotation) {
        if (!annotation.isDrawable()) {
            return false;
        }
        int at = annotations.size() * Annotation.BOUNDS_PER_ANNOTATION;
        if (at + Annotation.BOUNDS_PER_ANNOTATION > bounds.length) {
            bounds = Arrays.copyOf(bounds, Math.max(bounds.length * CAPACITY_GROWTH_FACTOR,
                    at + Annotation.BOUNDS_PER_ANNOTATION));
        }
        boundsOf(annotation, bounds, at);
        annotations.add(annotation);
        addToGrid(annotations.size() - 1, cell(bounds[at]),
                cell(bounds[at + Annotation.MAX_X_BOUND_OFFSET]), cell(bounds[at + 1]),
                cell(bounds[at + Annotation.MAX_Z_BOUND_OFFSET]));
        dirty = true;
        return true;
    }

    // Sizes both arrays for a document that held count entries.
    private void reserve(int count) {
        int capacity = Math.min(Math.max(count, 0), MAX_FILE_VALUES);
        annotations.ensureCapacity(capacity);
        int boundCapacity = capacity * Annotation.BOUNDS_PER_ANNOTATION;
        if (boundCapacity != bounds.length) {
            bounds = Arrays.copyOf(bounds, boundCapacity);
        }
    }

    // Drops everything a part-read document put in.
    private void discardLoaded() {
        annotations.clear();
        Arrays.fill(gridLists, null);
        gridUsed = 0;
        globalEntries.clear();
        lost = 0;
        dirty = false;
    }

    private void take(Annotation annotation) {
        if (annotation != null) {
            addNormalised(annotation.normalise());
        }
    }

    public boolean remove(Annotation annotation) {
        int size = annotations.size();
        int index = annotations.lastIndexOf(annotation);
        boolean removed = false;
        if (index >= 0) {
            annotations.remove(index);
            System.arraycopy(bounds, (index + 1) * Annotation.BOUNDS_PER_ANNOTATION, bounds,
                    index * Annotation.BOUNDS_PER_ANNOTATION,
                    (size - index - 1) * Annotation.BOUNDS_PER_ANNOTATION);
            removeFromGrid(index);
            dirty = true;
            removed = true;
        }
        return removed;
    }

    public void clear() {
        annotations.clear();
        Arrays.fill(gridLists, null);
        gridUsed = 0;
        globalEntries.clear();
        dirty = true;
    }

    public Annotation nearest(double x, double z, double radius) {
        candidatesExamined = 0;
        Annotation nearest;
        if (radius < 0 || Double.isNaN(radius) || Double.isNaN(x) || Double.isNaN(z)) {
            nearest = null;
        } else if (!Double.isFinite(radius) || !Double.isFinite(x) || !Double.isFinite(z)) {
            nearest = nearestInAllEntries(x, z, radius);
        } else {
            double minQueryX = x - radius;
            double maxQueryX = x + radius;
            double minQueryZ = z - radius;
            double maxQueryZ = z + radius;
            if (!Double.isFinite(minQueryX) || !Double.isFinite(maxQueryX)
                    || !Double.isFinite(minQueryZ) || !Double.isFinite(maxQueryZ)) {
                nearest = nearestInAllEntries(x, z, radius);
            } else {
                int epoch = nextCandidateEpoch();
                candidates.clear();
                addCandidates(globalEntries, epoch);
                int minX = cell(minQueryX);
                int maxX = cell(maxQueryX);
                int minZ = cell(minQueryZ);
                int maxZ = cell(maxQueryZ);
                if (isSaturatedCell(minX) || isSaturatedCell(maxX) || isSaturatedCell(minZ)
                        || isSaturatedCell(maxZ)) {
                    nearest = nearestInAllEntries(x, z, radius);
                } else if (cellCount(minX, maxX, minZ, maxZ) > MAX_CELLS_PER_ANNOTATION) {
                    nearest = nearestInAllEntries(x, z, radius);
                } else {
                    for (int cellX = minX; cellX <= maxX; cellX++) {
                        for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                            IntList entries = gridList(cellKey(cellX, cellZ));
                            if (entries != null) {
                                addCandidates(entries, epoch);
                            }
                        }
                    }
                    candidates.sort();
                    nearest = nearestInEntries(candidates, x, z, radius);
                }
            }
        }
        return nearest;
    }

    int candidatesExamined() {
        return candidatesExamined;
    }

    private Annotation nearestInEntries(IntList entries, double x, double z,
            double radius) {
        return nearestAmong(entries, entries.size, x, z, radius);
    }

    private Annotation nearestInAllEntries(double x, double z, double radius) {
        return nearestAmong(null, annotations.size(), x, z, radius);
    }

    // Null entries walks every annotation, index by index.
    private Annotation nearestAmong(IntList entries, int count, double x, double z,
            double radius) {
        Annotation nearest = null;
        for (int i = count - 1; i >= 0 && nearest == null; i--) {
            int index = entries == null ? i : entries.values[i];
            int at = index * Annotation.BOUNDS_PER_ANNOTATION;
            if (x < bounds[at] - radius || x > bounds[at + Annotation.MAX_X_BOUND_OFFSET] + radius
                    || z < bounds[at + 1] - radius
                    || z > bounds[at + Annotation.MAX_Z_BOUND_OFFSET] + radius) {
                continue;
            }
            candidatesExamined++;
            Annotation candidate = annotations.get(index);
            if (candidate.isWithin(x, z, radius)) {
                nearest = candidate;
            }
        }
        return nearest;
    }

    private int nextCandidateEpoch() {
        candidateEpoch++;
        if (candidateEpoch == 0) {
            Arrays.fill(candidateMarks, 0);
            candidateEpoch = 1;
        }
        return candidateEpoch;
    }

    private void addCandidates(IntList entries, int epoch) {
        for (int i = 0; i < entries.size; i++) {
            int index = entries.values[i];
            if (candidateMarks[index] != epoch) {
                candidateMarks[index] = epoch;
                candidates.add(index);
            }
        }
    }

    private void addToGrid(int index, int minX, int maxX, int minZ, int maxZ) {
        ensureCandidateMarks(index + 1);
        if (isSaturatedCell(minX) || isSaturatedCell(maxX) || isSaturatedCell(minZ)
                || isSaturatedCell(maxZ)
                || cellCount(minX, maxX, minZ, maxZ) > MAX_CELLS_PER_ANNOTATION) {
            globalEntries.add(index);
        } else {
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    gridListForAdd(cellKey(cellX, cellZ)).add(index);
                }
            }
        }
    }

    private void removeFromGrid(int index) {
        globalEntries.removeAndShift(index);
        for (IntList entries : gridLists) {
            if (entries != null) {
                entries.removeAndShift(index);
            }
        }
    }

    private static long cellCount(int minX, int maxX, int minZ, int maxZ) {
        return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    // Whether cell is the narrowing conversion's own clamp (MIN_VALUE or MAX_VALUE).
    private static boolean isSaturatedCell(int cell) {
        return cell == Integer.MIN_VALUE || cell == Integer.MAX_VALUE;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << CELL_KEY_COORDINATE_BITS) | (z & CELL_KEY_COORDINATE_MASK);
    }

    private IntList gridList(long key) {
        int slot = gridSlot(key);
        return gridLists[slot];
    }

    private IntList gridListForAdd(long key) {
        if ((gridUsed + 1) * GRID_LOAD_FACTOR_DENOMINATOR > gridLists.length) {
            growGrid();
        }
        int slot = gridSlot(key);
        IntList entries = gridLists[slot];
        if (entries == null) {
            entries = new IntList();
            gridKeys[slot] = key;
            gridLists[slot] = entries;
            gridUsed++;
        }
        return entries;
    }

    private int gridSlot(long key) {
        int slot = (int) (key ^ (key >>> CELL_KEY_COORDINATE_BITS));
        slot ^= slot >>> GRID_HASH_SHIFT;
        slot *= GRID_HASH_MULTIPLIER;
        slot ^= slot >>> GRID_HASH_SECOND_SHIFT;
        slot &= gridLists.length - 1;
        while (gridLists[slot] != null && gridKeys[slot] != key) {
            slot = (slot + 1) & (gridLists.length - 1);
        }
        return slot;
    }

    private void growGrid() {
        long[] oldKeys = gridKeys;
        IntList[] oldLists = gridLists;
        gridKeys = new long[oldKeys.length * CAPACITY_GROWTH_FACTOR];
        gridLists = new IntList[oldLists.length * CAPACITY_GROWTH_FACTOR];
        gridUsed = 0;
        for (int i = 0; i < oldLists.length; i++) {
            IntList entries = oldLists[i];
            if (entries != null) {
                int slot = gridSlot(oldKeys[i]);
                gridKeys[slot] = oldKeys[i];
                gridLists[slot] = entries;
                gridUsed++;
            }
        }
    }

    private void ensureCandidateMarks(int size) {
        if (size > candidateMarks.length) {
            candidateMarks = Arrays.copyOf(candidateMarks,
                    Math.max(candidateMarks.length * CAPACITY_GROWTH_FACTOR, size));
        }
    }

    private static void boundsOf(Annotation annotation, double[] target, int at) {
        annotation.writeBoundsTo(target, at);
    }

    public Annotation undo() {
        int last = annotations.size() - 1;
        if (last < 0) {
            return null;
        }
        removeFromGrid(last);
        dirty = true;
        return annotations.remove(last);
    }

    public static AnnotationStore load(Path path) {
        AnnotationStore store = new AnnotationStore();
        boolean loaded = false;
        if (path != null) {
            store.readFrom = path.toAbsolutePath().normalize();
            try (Reader file = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Reader reader = new Capped(file);

            // One entry at a time; never the whole document resident.
                int count = JSON.fromJsonStream(reader, Annotation.class, store::take);
                if (count >= 0) {
                    store.reserve(count);
                    int kept = store.annotations.size();
                    if (kept < count) {
                        int lost = count - kept;
                        store.lost = lost;
                        store.unreadable = lost + " of " + count
                                + " annotations could not be drawn";
                    }
                }
                loaded = true;
            } catch (NoSuchFileException absent) {
                store.discardLoaded();
                store.readFrom = null;
            } catch (IOException bad) {
                loaded = false;
                store.discardLoaded();
                try {
                    if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                        store.readFrom = null;
                    } else {
                        store.unreadable = describe(bad);
                    }
                } catch (NoSuchFileException absent) {
                    store.readFrom = null;
                } catch (IOException attributesUnavailable) {
                    store.unreadable = describe(bad);
                }
            } catch (JsonParseException bad) {
                store.discardLoaded();
                store.unreadable = describe(bad);
            }
        }
        if (loaded) {
            store.dirty = false;

        // Set to readFrom, so a fresh load already counts as a clean save of that path.
            store.lastWrittenTo = store.readFrom;
        }
        return store;
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // Why the file this store came from could not be read, or empty.
    public String unreadable() {
        return unreadable;
    }

    private String unreadable = "";

    public int lost() {
        return lost;
    }

    private int lost;

    private Path readFrom;

    private Path lastWrittenTo;

    private boolean dirty;

    // Writes the whole overlay, atomically, unless the file it came from was unreadable.
    public void save(Path path) throws IOException {
        if (!unreadable.isEmpty() && isTheDocumentItCameFrom(path)
                && Files.isRegularFile(path)) {
            throw new IOException(path + " could not be read (" + unreadable
                    + "). Writing this overlay would delete what is in it."
                    + " Move it aside. The next save starts fresh.");
        }
        if (!dirty && lost == 0 && Files.isRegularFile(path)
                && isLastWrittenDocument(path)) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
        Tally tally = new Tally();
        tally.expectValues(valueCount(annotations));
        tally.refuse("write");
        Path temp = path.resolveSibling(path.getFileName() + "."
                + Thread.currentThread().threadId() + ".tmp");
        try {
            try (Writer writer = new Bounded(
                    Files.newBufferedWriter(temp, StandardCharsets.UTF_8), tally)) {
                JSON.toJson(annotations, writer);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException undeletable) {
                failed.addSuppressed(undeletable);
            }
            throw failed;
        }
        FileReplace.replace(temp, path, true, FileReplace.ALWAYS);
        if (!unreadable.isEmpty() && isTheDocumentItCameFrom(path)) {
            unreadable = "";
        }
        lastWrittenTo = path.toAbsolutePath().normalize();
        dirty = false;
    }

    private boolean isTheDocumentItCameFrom(Path path) {
        return readFrom != null && path != null
                && readFrom.equals(path.toAbsolutePath().normalize());
    }

    private boolean isLastWrittenDocument(Path path) {
        return lastWrittenTo != null && path != null
                && lastWrittenTo.equals(path.toAbsolutePath().normalize());
    }

    private static final class IntList {

        private static final int INITIAL_CAPACITY = 4;

        private int[] values = new int[INITIAL_CAPACITY];

        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * CAPACITY_GROWTH_FACTOR);
            }
            values[size++] = value;
        }

        void clear() {
            size = 0;
        }

        void sort() {
            Arrays.sort(values, 0, size);
        }

        void removeAndShift(int removed) {
            int to = 0;
            for (int from = 0; from < size; from++) {
                int value = values[from];
                if (value != removed) {
                    values[to++] = value > removed ? value - 1 : value;
                }
            }
            size = to;
        }
    }

    private static long valueCount(List<Annotation> annotations) {
        long values = 1;
        int size = annotations.size();
        if (size > 1) {
            values += size - 1;
        }
        for (Annotation annotation : annotations) {
            values += 1;
            int fields = 1;
            if (annotation.tool() != null) {
                fields++;
            }
            if (annotation.colour() != null) {
                fields++;
            }
            double[] path = annotation.points();
            if (path != null) {
                fields++;
            }
            if (annotation.text() != null) {
                fields++;
            }
            values += fields;
            if (fields > 1) {
                values += fields - 1;
            }
            if (path != null) {
                values++;
                if (path.length > 1) {
                    values += path.length - 1;
                }
            }
        }
        return values;
    }

    private static final class Capped extends Reader {

        private final Reader in;

        private final Tally tally = new Tally();

        Capped(Reader in) {
            this.in = in;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset,
                    (int) Math.min(length, MAX_FILE_CHARS + 1L - tally.chars()));
            if (n > 0) {
                tally.take(buffer, offset, n);
                tally.refuse("read");
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class Bounded extends FilterWriter {

        private final Tally tally;

        Bounded(Writer out, Tally tally) {
            super(out);
            this.tally = tally;
        }

        @Override
        public void write(int c) throws IOException {
            tally.take(1);
            tally.refuse("write");
            out.write(c);
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            tally.take(length);
            tally.refuse("write");
            out.write(buffer, offset, length);
        }

        @Override
        public void write(String text, int offset, int length) throws IOException {
            tally.take(length);
            tally.refuse("write");
            out.write(text, offset, length);
        }
    }

    private static final class Tally {

        private static final int ASCII_LIMIT = 128;

        private static final int ESCAPE_CHARACTER_KIND = 2;

        private static final int VALUE_CHARACTER_KIND = 3;

        private static final byte[] CLASS = new byte[128];

        static {
            CLASS['"'] = 1;
            CLASS['\\'] = ESCAPE_CHARACTER_KIND;
            CLASS[','] = VALUE_CHARACTER_KIND;
            CLASS[':'] = VALUE_CHARACTER_KIND;
            CLASS['['] = VALUE_CHARACTER_KIND;
            CLASS['{'] = VALUE_CHARACTER_KIND;
        }

        private long chars;

        private long values;

        private boolean inString;

        private boolean escaped;

        long chars() {
            return chars;
        }

        void expectValues(long count) {
            values = count;
        }

        void take(int length) {
            chars += length;
        }

        void take(char[] buffer, int offset, int length) {
            boolean inString = this.inString;
            boolean escaped = this.escaped;
            long values = this.values;
            int end = offset + length;
            for (int i = offset; i < end; i++) {
                char c = buffer[i];
                int kind = c < ASCII_LIMIT ? CLASS[c] : 0;
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (kind == ESCAPE_CHARACTER_KIND) {
                        escaped = true;
                    } else if (kind == 1) {
                        inString = false;
                    } else {
                    }
                } else {
                    if (kind == 1) {
                        inString = true;
                    } else if (kind == VALUE_CHARACTER_KIND) {
                        if (++values > MAX_FILE_VALUES) {
                            break;
                        }
                    } else {
                    }
                }
            }
            this.inString = inString;
            this.escaped = escaped;
            this.values = values;
            chars += length;
        }

        void refuse(String verb) throws IOException {
            if (chars > MAX_FILE_CHARS) {
                throw new IOException("longer than " + MAX_FILE_CHARS
                        + " characters; refusing to " + verb + " it as an overlay");
            }
            if (values > MAX_FILE_VALUES) {
                throw new IOException("more than " + MAX_FILE_VALUES
                        + " values; refusing to " + verb + " it as an overlay");
            }
        }
    }
}
