package dev.openmap.map;

import dev.openmap.api.GroundChunk;
import java.util.Arrays;

public final class ChunkSample implements GroundChunk {

    public static final int SIZE = 16;
    public static final int COLUMNS = SIZE * SIZE;

    public static final short NO_HEIGHT = Short.MIN_VALUE;

    private static final int INSTANCE_BYTES = 64;

    private static final int OVERWORLD_FLOOR = -64;

    private static final int NETHER_END_FLOOR = 0;

    private static final byte UNKNOWN_COVER = (byte) LandCover.UNKNOWN.code();

    public int chunkX;
    public int chunkZ;

    private final short[] heights;
    private final byte[] cover;
    private int unfilled;

    // Sample schema version; a bump re-queues revisited chunks.
    public static final int CAPTURE_VERSION = 10;

    // First version with capturedAt() in epoch milliseconds UTC.
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

    public void resetForFullWriter(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        unfilled = 0;
        capturedAt = 0L;
        captureVersion = CAPTURE_VERSION;
    }

    public ChunkSample copyForFullWriter() {
        ChunkSample copy = forFullWriter(chunkX, chunkZ);
        System.arraycopy(heights, 0, copy.heights, 0, COLUMNS);
        System.arraycopy(cover, 0, copy.cover, 0, COLUMNS);
        copy.unfilled = unfilled;
        copy.capturedAt = capturedAt;
        copy.captureVersion = captureVersion;
        return copy;
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
            // Unchanged fill state.
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

    // Writes COLUMNS big-endian heights, then cover ordinals, from offset at.
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

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public void copyHeights(short[] into) {
        System.arraycopy(heights, 0, into, 0, COLUMNS);
    }

    @Override
    public void copyCoverCodes(byte[] into) {
        System.arraycopy(cover, 0, into, 0, COLUMNS);
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

    public boolean holdsGround() {
        boolean holds = false;
        for (int column = 0; column < COLUMNS && !holds; column++) {
            int height = heights[column];
            holds = cover[column] != UNKNOWN_COVER
                    || (height > OVERWORLD_FLOOR && height != NETHER_END_FLOOR);
        }
        return holds;
    }

    // Epoch milliseconds UTC from WALL_CLOCK_VERSION; game ticks below it; 0 or an import's mtime otherwise.
    @Override
    public long capturedAt() {
        return capturedAt;
    }

    // False before WALL_CLOCK_VERSION: rank those as unknown age, never convert.
    public boolean hasWallClockCapture() {
        return isWallClock(captureVersion);
    }

    public static boolean isWallClock(int captureVersion) {
        return captureVersion >= WALL_CLOCK_VERSION;
    }

    // Version 0 is an import.
    public static final int FIRST_SURVEY_VERSION = 1;

    public static boolean isGameTick(int captureVersion) {
        return captureVersion >= FIRST_SURVEY_VERSION && captureVersion < WALL_CLOCK_VERSION;
    }

    @Override
    public int captureVersion() {
        return captureVersion;
    }

    public void setCaptureVersion(int version) {
        this.captureVersion = version;
    }

    public boolean isCurrent() {
        return captureVersion >= CAPTURE_VERSION;
    }

    // Epoch milliseconds UTC.
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
