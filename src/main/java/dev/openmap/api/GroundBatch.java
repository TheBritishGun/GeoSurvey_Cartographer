package dev.openmap.api;

public interface GroundBatch {

    // The caller's thread, during importRegion.
    int size();

    // The caller's thread, during importRegion.
    GroundChunk chunk(int index);
}
