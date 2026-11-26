package dev.openmap.api;

import java.util.List;

public interface CartographerMarkers {

    // Client thread. Constant time, no allocation, never blocks.
    long revision(String dimensionId);

    // Client thread. The caller owns the unmodifiable copy.
    List<MarkerValue> list(String dimensionId);

    // Client thread. Answers STALE when seenRevision is not current.
    MarkerWrite replace(String dimensionId, List<MarkerValue> markers, long seenRevision);
}
