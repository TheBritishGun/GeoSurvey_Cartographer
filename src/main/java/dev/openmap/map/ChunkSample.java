package dev.openmap.map;

import java.util.Arrays;

public final class ChunkSample {

    public static final int SIZE = 16;
    public static final int COLUMNS = SIZE * SIZE;

    public static final short NO_HEIGHT = Short.MIN_VALUE;

    private static final int INSTANCE_BYTES = 64;

    public final int chunkX;
    public final int chunkZ;

    private final short[] heights;
    private final byte[] cover;
    private int unfilled;

    // Current sample schema version. A bump re-queues chunks the player
    // revisits: 9 added epoch-millisecond timestamps, 10 the BEDROCK,
    // END_STONE, CRIMSON and SULFUR covers.
    public static final int CAPTURE_VERSION = 10;

    // First version whose capturedAt() is epoch milliseconds UTC; below it,
    // Minecraft game ticks. Use hasWallClockCapture(), not the value's size.
    public static final int WALL_CLOCK_VERSION = 9;

    private long capturedAt;

    private int captureVersion = CAPTURE_VERSION;

    public ChunkSample(int chunkX, int chunkZ) {
        this(chunkX, chunkZ, true);
    }

    private ChunkSample(int chunkX, int chunkZ, boolean prefill) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.heights = new short[COLUMNS];
        this.cover = new byte[COLUMNS];
        this.unfilled = prefill ? COLUMNS : 0;
        if (prefill) {
            Arrays.fill(this.heights, NO_HEIGHT);
        }
    }

    public static ChunkSample forFullWriter(int chunkX, int chunkZ) {
        return new ChunkSample(chunkX, chunkZ, false);
    }

    private static int index(int localX, int localZ) {
        if (((localX | localZ) & ~(SIZE - 1)) != 0) {
            throw new IndexOutOfBoundsException("column " + localX + "," + localZ);
        }
        return localZ * SIZE + localX;
    }

    public void set(int localX, int localZ, int height, LandCover landCover) {
        int i = index(localX, localZ);
        short was = heights[i];
        short now = (short) height;
        if (was == NO_HEIGHT && now != NO_HEIGHT) {
            unfilled--;
        } else if (was != NO_HEIGHT && now == NO_HEIGHT) {
            unfilled++;
        } else {
            // Unchanged fill state: unfilled does not move.
        }
        heights[i] = now;
        cover[i] = (byte) landCover.code();
    }

    void setColumns(short[] columnHeights, byte[] coverOrdinals) {
        System.arraycopy(columnHeights, 0, heights, 0, COLUMNS);
        int missing = 0;
        for (int i = 0; i < COLUMNS; i++) {
            if (heights[i] == NO_HEIGHT) {
                missing++;
            }
            byte ordinal = coverOrdinals[i];
            cover[i] = ordinal < 0 || ordinal >= LandCover.count()
                    ? (byte) LandCover.UNKNOWN.code()
                    : ordinal;
        }
        unfilled = missing;
    }

    // Writes COLUMNS big-endian heights, then COLUMNS cover ordinals, at
    // the given offset.
    void copyColumnsInto(byte[] into, int at) {
        int coverBase = at + COLUMNS * Short.BYTES;
        for (int i = 0; i < COLUMNS; i++) {
            short height = heights[i];
            into[at + i * Short.BYTES] = (byte) (height >>> Byte.SIZE);
            into[at + i * Short.BYTES + 1] = (byte) height;
        }
        System.arraycopy(cover, 0, into, coverBase, COLUMNS);
    }

    void copyColumnsInto(byte[] heightBytes, byte[] coverBytes) {
        for (int i = 0; i < COLUMNS; i++) {
            short height = heights[i];
            heightBytes[i * Short.BYTES] = (byte) (height >>> Byte.SIZE);
            heightBytes[i * Short.BYTES + 1] = (byte) height;
        }
        System.arraycopy(cover, 0, coverBytes, 0, COLUMNS);
    }

    public short height(int localX, int localZ) {
        return heights[index(localX, localZ)];
    }

    public LandCover cover(int localX, int localZ) {
        return LandCover.byCode(cover[index(localX, localZ)]);
    }

    public byte coverOrdinal(int localX, int localZ) {
        return cover[index(localX, localZ)];
    }

    public boolean hasHeight(int localX, int localZ) {
        return heights[index(localX, localZ)] != NO_HEIGHT;
    }

    // Epoch milliseconds UTC at WALL_CLOCK_VERSION and above, game ticks
    // below it, 0 or an import's mtime otherwise. Compare only within a
    // domain; check hasWallClockCapture() first.
    public long capturedAt() {
        return capturedAt;
    }

    // Whether capturedAt() is a comparable wall time. False before
    // WALL_CLOCK_VERSION: rank those as unknown age, never convert.
    public boolean hasWallClockCapture() {
        return isWallClock(captureVersion);
    }

    // hasWallClockCapture() for a version read off the wire.
    public static boolean isWallClock(int captureVersion) {
        return captureVersion >= WALL_CLOCK_VERSION;
    }

    // Lowest version the capture path ever wrote. Below it (0) is an
    // import, not an older survey.
    public static final int FIRST_SURVEY_VERSION = 1;

    // Whether captureVersion means capturedAt() is Minecraft game ticks:
    // the survey range below WALL_CLOCK_VERSION, not an import.
    public static boolean isGameTick(int captureVersion) {
        return captureVersion >= FIRST_SURVEY_VERSION && captureVersion < WALL_CLOCK_VERSION;
    }

    public int captureVersion() {
        return captureVersion;
    }

    public void setCaptureVersion(int version) {
        this.captureVersion = version;
    }

    public boolean isCurrent() {
        return captureVersion >= CAPTURE_VERSION;
    }

    // Epoch milliseconds UTC when this build stamps it; the capture
    // version says the domain when decoding older records.
    public void setCapturedAt(long when) {
        this.capturedAt = when;
    }

    public boolean isComplete() {
        return unfilled == 0;
    }

    public static int approximateBytes() {
        return COLUMNS * (Short.BYTES + Byte.BYTES) + INSTANCE_BYTES;
    }
}
