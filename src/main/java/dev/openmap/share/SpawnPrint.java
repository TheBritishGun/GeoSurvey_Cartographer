package dev.openmap.share;

import dev.openmap.map.ChunkSample;
import dev.openmap.map.ChunkSource;
import dev.openmap.map.LandCover;
import java.util.Arrays;
import java.util.Objects;

public final class SpawnPrint {

    // Chunks a side.
    public static final int SPAN = 3;

    // Columns a side.
    public static final int COLUMNS = SPAN * 16;

    // Cells a side.
    public static final int CELLS = 8;

    // Columns a side in one cell.
    public static final int CELL_COLUMNS = COLUMNS / CELLS;

    // Cells in one panel.
    public static final int PANEL = CELLS * CELLS;

    // Blocks per height step.
    public static final int QUANTUM_BLOCKS = 1;

    public static final int MATCHING_CELLS = 48;

    public static final int NEAR_CELLS = 42;

    public static final int MIN_OFF_PLATEAU = PANEL / 4;

    public static final int MIN_CAPTURE_VERSION = 10;

    // Five bits per cell.
    public static final int MAX_COVER = 31;

    // Bytes written: 2 of base, 32 of heights, 40 of covers.
    public static final int BYTES = 2 + PANEL / 2 + PANEL * 5 / 8;

    private static final int HASH_MULTIPLIER = 31;

    private static final int HEIGHT_STEP_COUNT = 16;

    private static final int UNSIGNED_BYTE_MASK = 0xFF;

    private static final int MEDIAN_INDEX_DIVISOR = 2;

    private static final int BASE_BYTES = 2;

    private static final int HEIGHTS_PER_BYTE = 2;

    private static final int HEIGHT_BITS = 4;

    private static final int BITS_PER_BYTE = 8;

    private static final int MAX_HEIGHT_STEP = 15;

    private static final int HEIGHT_STEP_BIAS = 8;

    private static final int COVERS_PER_PACKED_GROUP = 8;

    private static final int COVER_BITS = 5;

    private static final int COVER_GROUP_BYTES = 5;

    private static final int FIRST_COVER_BYTE_SHIFT = 32;

    private static final int SECOND_COVER_BYTE_SHIFT = 24;

    private static final int THIRD_COVER_BYTE_SHIFT = 16;

    private static final int FOURTH_COVER_BYTE_SHIFT = 8;

    private static final int THIRD_COVER_BYTE_OFFSET = 2;

    private static final int FOURTH_COVER_BYTE_OFFSET = 3;

    private static final int FIFTH_COVER_BYTE_OFFSET = 4;

    private static final int FIRST_COVER_SHIFT = 35;

    private static final int LOW_NIBBLE_MASK = 0x0F;

    public enum Fault {

        NONE(""),

        NOT_SURVEYED("Walk to the spawn point of this server. The map has no record"
                + " of the ground there."),

        TOO_OLD("Walk to the spawn point of this server again. The record of the"
                + " ground there is too old to compare."),

        TOO_FLAT("The ground around this spawn point is flat. Flat ground identifies"
                + " no server.");

        private final String reason;

        Fault(String reason) {
            this.reason = reason;
        }

        // Empty for NONE.
        public String reason() {
            return reason;
        }
    }

    // median and cover are row major, one entry per cell.
    public record Ground(int[] median, byte[] cover, Fault fault) {


        private static final int[] NO_MEDIANS = new int[0];

        private static final byte[] NO_COVERS = new byte[0];

        static Ground failed(Fault fault) {
            return new Ground(NO_MEDIANS, NO_COVERS, fault);
        }

        public boolean isReadable() {
            return fault == Fault.NONE;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ground that)) {
                return false;
            }
            return fault == that.fault && Arrays.equals(median, that.median)
                    && Arrays.equals(cover, that.cover);
        }

        @Override
        public int hashCode() {
            return (Arrays.hashCode(median) * HASH_MULTIPLIER + Arrays.hashCode(cover))
                    * HASH_MULTIPLIER + Objects.hashCode(fault);
        }
    }

    // levels and covers count agreeing cells.
    public record Agreement(int levels, int covers) {

        public static final Agreement NONE = new Agreement(0, 0);

        public boolean matches() {
            return levels >= MATCHING_CELLS && covers >= MATCHING_CELLS;
        }

        public boolean near() {
            return !matches() && levels >= NEAR_CELLS && covers >= NEAR_CELLS;
        }
    }

    private final int base;

    private final byte[] level;

    private final byte[] cover;

    private final int hash;

    private volatile int offPlateauCache = -1;

    private volatile int plateauStepCache = -1;

    private SpawnPrint(int base, byte[] level, byte[] cover) {
        this.base = base;
        this.level = level;
        this.cover = cover;
        this.hash = (base * HASH_MULTIPLIER + Arrays.hashCode(level)) * HASH_MULTIPLIER
                + Arrays.hashCode(cover);
    }

    // Cell heights are offsets from this.
    public int base() {
        return base;
    }

    public int offPlateau() {
        int cached = offPlateauCache;
        if (cached >= 0) {
            return cached;
        }
        int[] tally = new int[HEIGHT_STEP_COUNT];
        for (byte step : level) {
            tally[step]++;
        }
        int commonest = 0;
        int commonestCount = tally[0];
        for (int i = 1; i < tally.length; i++) {
            if (tally[i] > commonestCount) {
                commonest = i;
                commonestCount = tally[i];
            }
        }
        int result = PANEL - commonestCount;
        plateauStepCache = commonest;
        offPlateauCache = result;
        return result;
    }

    int plateauStep() {
        int cached = plateauStepCache;
        if (cached >= 0) {
            return cached;
        }
        offPlateau();
        return plateauStepCache;
    }

    public boolean identifies() {
        return offPlateau() >= MIN_OFF_PLATEAU;
    }

    // other must be built in this print's frame.
    public Agreement agreementWith(SpawnPrint other) {
        if (other == null) {
            return Agreement.NONE;
        }
        if (other == this) {
            return new Agreement(PANEL, PANEL);
        }
        int levels = 0;
        int covers = 0;
        for (int i = 0; i < PANEL; i++) {
            if (level[i] == other.level[i]) {
                levels++;
            }
            if (cover[i] == other.cover[i]) {
                covers++;
            }
        }
        return new Agreement(levels, covers);
    }

    public static Ground measure(ChunkSource ground, int anchorChunkX, int anchorChunkZ) {
        Fault fault;
        ChunkSample[] nine;
        if (ground == null) {
            fault = Fault.NOT_SURVEYED;
            nine = null;
        } else {
            fault = null;
            nine = new ChunkSample[SPAN * SPAN];
            for (int dz = -1; dz <= 1 && fault == null; dz++) {
                for (int dx = -1; dx <= 1 && fault == null; dx++) {
                    ChunkSample sample = ground.get(anchorChunkX + dx, anchorChunkZ + dz);
                    if (sample == null || !sample.isComplete()) {
                        fault = Fault.NOT_SURVEYED;
                    } else if (sample.captureVersion() < MIN_CAPTURE_VERSION) {
                        fault = Fault.TOO_OLD;
                    } else {
                        nine[(dz + 1) * SPAN + dx + 1] = sample;
                    }
                }
            }
        }
        Ground result;
        if (fault == null) {
            short[] height = new short[COLUMNS * COLUMNS];
            byte[] land = new byte[COLUMNS * COLUMNS];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    ChunkSample sample = nine[(dz + 1) * SPAN + dx + 1];
                    int chunkOrigin = (dx + 1) * ChunkSample.SIZE;
                    int row = ((dz + 1) * ChunkSample.SIZE) * COLUMNS + chunkOrigin;
                    for (int lz = 0; lz < ChunkSample.SIZE; lz++) {
                        int at = row;
                        for (int lx = 0; lx < ChunkSample.SIZE; lx++) {
                            height[at] = sample.height(lx, lz);
                            land[at] = sample.coverOrdinal(lx, lz);
                            at++;
                        }
                        row += COLUMNS;
                    }
                }
            }
            result = reduce(height, land);
        } else {
            result = Ground.failed(fault);
        }
        return result;
    }

    static Ground reduce(short[] height, byte[] land) {
        int[] median = new int[PANEL];
        byte[] cover = new byte[PANEL];
        short[] column = new short[CELL_COLUMNS * CELL_COLUMNS];
        int[] tally = new int[MAX_COVER + 1];
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                Arrays.fill(tally, 0);
                int n = 0;
                int cell = cz * CELLS + cx;
                int rowBase = cz * (CELL_COLUMNS * COLUMNS) + cx * CELL_COLUMNS;
                for (int z = 0; z < CELL_COLUMNS; z++) {
                    int at = rowBase;
                    for (int x = 0; x < CELL_COLUMNS; x++) {
                        column[n++] = height[at];
                        tally[Math.min(MAX_COVER, land[at] & UNSIGNED_BYTE_MASK)]++;
                        at++;
                    }
                    rowBase += COLUMNS;
                }
                median[cell] = selectMedian(column, n);

                int commonest = 0;
                int commonestCount = tally[0];
                for (int c = 1; c < tally.length; c++) {
                    if (tally[c] > commonestCount) {
                        commonest = c;
                        commonestCount = tally[c];
                    }
                }
                cover[cell] = (byte) commonest;
            }
        }
        return new Ground(median, cover, Fault.NONE);
    }

    private static short selectMedian(short[] column, int n) {
        int want = n / MEDIAN_INDEX_DIVISOR;
        int low = 0;
        int high = n - 1;
        short result = 0;
        boolean medianFound = false;
        while (low < high && !medianFound) {
            int mid = low + ((high - low) >>> 1);
            if (column[mid] < column[low]) {
                swap(column, low, mid);
            }
            if (column[high] < column[low]) {
                swap(column, low, high);
            }
            if (column[high] < column[mid]) {
                swap(column, mid, high);
            }
            short pivot = column[mid];
            int below = low;
            int above = high;
            int at = low;
            while (at <= above) {
                short value = column[at];
                if (value < pivot) {
                    swap(column, below++, at++);
                } else if (value > pivot) {
                    swap(column, at, above--);
                } else {
                    at++;
                }
            }
            if (want < below) {
                high = below - 1;
            } else if (want > above) {
                low = above + 1;
            } else {
                result = pivot;
                medianFound = true;
            }
        }
        result = medianFound ? result : column[low];
        return result;
    }

    private static void swap(short[] column, int a, int b) {
        short held = column[a];
        column[a] = column[b];
        column[b] = held;
    }

    // Null when the ground could not be read or has no relief.
    public static SpawnPrint of(Ground ground) {
        if (ground == null || !ground.isReadable()
                || ground.median().length != PANEL || ground.cover().length != PANEL) {
            return null;
        }
        int[] sorted = ground.median().clone();
        Arrays.sort(sorted);
        SpawnPrint print = of(ground, sorted[sorted.length / MEDIAN_INDEX_DIVISOR]);
        return print != null && print.identifies() ? print : null;
    }

    // Null when the ground could not be read.
    public static SpawnPrint of(Ground ground, int base) {
        if (ground == null || !ground.isReadable()
                || ground.median().length != PANEL || ground.cover().length != PANEL) {
            return null;
        }
        byte[] level = new byte[PANEL];
        byte[] cover = new byte[PANEL];
        int[] median = ground.median();
        byte[] sourceCover = ground.cover();
        for (int i = 0; i < PANEL; i++) {
            int step = Math.floorDiv(median[i] - base, QUANTUM_BLOCKS) + HEIGHT_STEP_BIAS;
            level[i] = (byte) Math.max(0, Math.min(MAX_HEIGHT_STEP, step));
            cover[i] = (byte) Math.min(MAX_COVER, sourceCover[i] & UNSIGNED_BYTE_MASK);
        }
        return new SpawnPrint(clampBase(base), level, cover);
    }

    private static int clampBase(int base) {
        return Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, base));
    }

    // Big endian: 2 base bytes, then 64 height nibbles (even cell high), then 64 five-bit covers, MSB first.
    public byte[] toBytes() {
        byte[] out = new byte[BYTES];
        writeTo(out, 0);
        return out;
    }

    // dest needs BYTES of room from at; not checked.
    public void writeTo(byte[] dest, int at) {
        dest[at] = (byte) (base >> BITS_PER_BYTE);
        dest[at + 1] = (byte) base;
        for (int i = 0; i < PANEL; i += HEIGHTS_PER_BYTE) {
            dest[at + BASE_BYTES + i / HEIGHTS_PER_BYTE] =
                    (byte) ((level[i] << HEIGHT_BITS) | level[i + 1]);
        }
        int to = at + BASE_BYTES + PANEL / HEIGHTS_PER_BYTE;
        for (int i = 0; i < PANEL; i += COVERS_PER_PACKED_GROUP) {
            long packed = 0;
            for (int j = 0; j < COVERS_PER_PACKED_GROUP; j++) {
                packed = (packed << COVER_BITS) | cover[i + j];
            }
            dest[to] = (byte) (packed >>> FIRST_COVER_BYTE_SHIFT);
            dest[to + 1] = (byte) (packed >>> SECOND_COVER_BYTE_SHIFT);
            dest[to + THIRD_COVER_BYTE_OFFSET] = (byte) (packed >>> THIRD_COVER_BYTE_SHIFT);
            dest[to + FOURTH_COVER_BYTE_OFFSET] = (byte) (packed >>> FOURTH_COVER_BYTE_SHIFT);
            dest[to + FIFTH_COVER_BYTE_OFFSET] = (byte) packed;
            to += COVER_GROUP_BYTES;
        }
    }

    // Null when data is not a print.
    public static SpawnPrint fromBytes(byte[] data) {
        return data == null ? null : fromBytes(data, 0, data.length);
    }

    // Null when data, at or length do not describe a print.
    public static SpawnPrint fromBytes(byte[] data, int at, int length) {
        if (data == null || length != BYTES || at < 0 || at > data.length - length) {
            return null;
        }
        int base = (short) ((data[at] << BITS_PER_BYTE)
                | (data[at + 1] & UNSIGNED_BYTE_MASK));
        byte[] level = new byte[PANEL];
        int from = at + BASE_BYTES;
        for (int i = 0; i < PANEL; i += HEIGHTS_PER_BYTE) {
            int packed = data[from++] & UNSIGNED_BYTE_MASK;
            level[i] = (byte) (packed >>> HEIGHT_BITS);
            level[i + 1] = (byte) (packed & LOW_NIBBLE_MASK);
        }
        byte[] cover = new byte[PANEL];
        for (int i = 0; i < PANEL; i += COVERS_PER_PACKED_GROUP) {
            long group = ((long) (data[from] & UNSIGNED_BYTE_MASK) << FIRST_COVER_BYTE_SHIFT)
                    | ((long) (data[from + 1] & UNSIGNED_BYTE_MASK) << SECOND_COVER_BYTE_SHIFT)
                    | ((long) (data[from + THIRD_COVER_BYTE_OFFSET] & UNSIGNED_BYTE_MASK)
                    << THIRD_COVER_BYTE_SHIFT)
                    | ((long) (data[from + FOURTH_COVER_BYTE_OFFSET] & UNSIGNED_BYTE_MASK)
                    << FOURTH_COVER_BYTE_SHIFT)
                    | (long) (data[from + FIFTH_COVER_BYTE_OFFSET] & UNSIGNED_BYTE_MASK);
            int shift = FIRST_COVER_SHIFT;
            for (int j = 0; j < COVERS_PER_PACKED_GROUP; j++) {
                cover[i + j] = (byte) ((group >>> shift) & MAX_COVER);
                shift -= COVER_BITS;
            }
            from += COVER_GROUP_BYTES;
        }
        return new SpawnPrint(base, level, cover);
    }

    // Unknown covers read as UNKNOWN, never throw.
    public LandCover coverOf(int cell) {
        return LandCover.byCode(cover[cell]);
    }

    // 0 to 15, measured from base().
    public int levelOf(int cell) {
        return level[cell];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SpawnPrint that)) {
            return false;
        }
        return base == that.base && Arrays.equals(level, that.level)
                && Arrays.equals(cover, that.cover);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
