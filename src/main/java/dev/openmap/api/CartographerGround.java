package dev.openmap.api;

public interface CartographerGround {

    // Client thread.
    Registration onGround(GroundListener listener);

    // Any thread but the client thread. It waits at most timeoutNanos, then answers a failure.
    ImportResult importRegion(String dimensionId, int regionX, int regionZ, GroundBatch batch,
            long timeoutNanos);
}
