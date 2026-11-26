package dev.openmap.api;

public interface GroundChunk {

    int chunkX();

    int chunkZ();

    long capturedAt();

    int captureVersion();

    // 256 columns, localZ * 16 + localX. -32768 is unfilled.
    void copyHeights(short[] into);

    // 256 columns, localZ * 16 + localX. Byte: LandCover.code().
    void copyCoverCodes(byte[] into);
}
