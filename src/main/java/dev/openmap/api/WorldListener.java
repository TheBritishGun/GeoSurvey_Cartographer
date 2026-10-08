package dev.openmap.api;

import java.nio.file.Path;

public interface WorldListener {

    // Client thread. The folder is null when the player leaves.
    void worldChanged(Path worldFolder);
}
