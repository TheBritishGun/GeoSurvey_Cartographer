package dev.openmap.api;

public interface CartographerGround {

    // Client thread.
    Registration onGround(GroundListener listener);

    // Not the client thread. Waits at most timeoutNanos, then fails.
    ImportResult importRegion(String dimensionId, int regionX, int regionZ, GroundBatch batch,
            long timeoutNanos);
}
