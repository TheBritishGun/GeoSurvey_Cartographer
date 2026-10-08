package dev.openmap.api;

public interface GroundChunk {

    int chunkX();

    int chunkZ();

    long capturedAt();

    int captureVersion();

    // 256 columns at localZ * 16 + localX; -32768 marks an unfilled column.
    void copyHeights(short[] into);

    // 256 columns at localZ * 16 + localX; each byte is LandCover.code().
    void copyCoverCodes(byte[] into);
}
