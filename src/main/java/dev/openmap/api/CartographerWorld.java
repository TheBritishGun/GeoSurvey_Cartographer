package dev.openmap.api;

import java.nio.file.Path;

public interface CartographerWorld {

    // Client thread; null outside a world.
    Path worldFolder();

    // Client thread; null outside a world. The id is the resource id text, such as minecraft:overworld.
    String dimensionFolder(String dimensionId);

    // Client thread; null outside a world.
    Path claimsBook();

    // Client thread.
    Registration onWorldChanged(WorldListener listener);
}
