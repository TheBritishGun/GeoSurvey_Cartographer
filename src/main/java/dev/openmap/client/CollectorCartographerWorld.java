package dev.openmap.client;

import dev.openmap.api.CartographerWorld;
import dev.openmap.api.Registration;
import dev.openmap.api.WorldListener;
import dev.openmap.claim.ClaimBook;
import dev.openmap.map.MapStorage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

// The world part of the published cartographer; the capture tells it each change of the world folder.
final class CollectorCartographerWorld implements CartographerWorld {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final WorldListener[] NO_LISTENERS = new WorldListener[0];

    private final MapStorage storage;

    // Null outside a world.
    private volatile Path folder;

    // Client thread; replaced on each change, never changed in place.
    private WorldListener[] listeners = NO_LISTENERS;

    // Client thread.
    private boolean failureSaid = false;

    CollectorCartographerWorld(MapStorage storage) {
        this.storage = storage;
    }

    @Override
    public Path worldFolder() {
        return folder;
    }

    @Override
    public String dimensionFolder(String dimensionId) {
        if (folder == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        Path dir = storage.regionDirFor(MapStorage.asStored(dimensionId));
        return dir == null ? null : dir.getFileName().toString();
    }

    @Override
    public Path claimsBook() {
        return ClaimBook.bookIn(folder);
    }

    @Override
    public Registration onWorldChanged(WorldListener listener) {
        Objects.requireNonNull(listener, "listener");
        WorldListener[] held = listeners;
        WorldListener[] grown = Arrays.copyOf(held, held.length + 1);
        grown[held.length] = listener;
        listeners = grown;
        return new CollectorListenerRegistration(() -> remove(listener));
    }

    // Client thread; after each root the storage takes.
    void followRoot(Path root) {
        if (Objects.equals(root, folder)) {
            return;
        }
        folder = root;
        announce(root);
    }

    // Client thread; when the player leaves the world.
    void left() {
        if (folder == null) {
            return;
        }
        folder = null;
        announce(null);
    }

    private void announce(Path now) {
        for (WorldListener listener : listeners) {
            try {
                listener.worldChanged(now);
            } catch (RuntimeException failed) {
                sayFailure(failed);
            }
        }
    }

    private void sayFailure(RuntimeException failed) {
        if (failureSaid) {
            LOGGER.debug("A world listener failed again.", failed);
        } else {
            failureSaid = true;
            LOGGER.warn("A world listener failed. The collector continues.", failed);
        }
    }

    private void remove(WorldListener listener) {
        WorldListener[] held = listeners;
        int at = held.length - 1;
        while (at >= 0 && held[at] != listener) {
            at--;
        }
        if (at >= 0) {
            WorldListener[] shrunk = new WorldListener[held.length - 1];
            System.arraycopy(held, 0, shrunk, 0, at);
            System.arraycopy(held, at + 1, shrunk, at, held.length - at - 1);
            listeners = shrunk;
        }
    }
}
