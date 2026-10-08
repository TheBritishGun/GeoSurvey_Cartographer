package dev.openmap.api;

import java.util.List;

public interface CartographerMarkers {

    // Client thread. It takes constant time, allocates nothing and never blocks.
    long revision(String dimensionId);

    // Client thread. The caller owns the unmodifiable copy.
    List<MarkerValue> list(String dimensionId);

    // Client thread. It answers STALE when seenRevision is not the current revision.
    MarkerWrite replace(String dimensionId, List<MarkerValue> markers, long seenRevision);
}
