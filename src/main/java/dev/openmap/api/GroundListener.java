package dev.openmap.api;

public interface GroundListener {

    // Client thread. The chunk is valid only during the call.
    void stored(String dimensionId, GroundChunk chunk);

    // Client thread.
    void regionWritten(String dimensionId, int regionX, int regionZ);
}
