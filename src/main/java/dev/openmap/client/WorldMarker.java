package dev.openmap.client;

import dev.openmap.share.Batch;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorldMarker {

    static final String FILE_NAME = "world";

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final long LEFTOVER_MILLIS = 60L * 60L * 1000L;

    private static final int MARKER_FIELDS = 2;

    private static final int MAX_MODIFIED_UTF_BYTES = 3;

    private static final int BUFFER_BYTES = MARKER_FIELDS
            * (Short.BYTES + Batch.MAX_NAME * MAX_MODIFIED_UTF_BYTES);

    private final Path directory;

    private final String server;

    private final String dimension;

    WorldMarker(Path directory, String server, String dimension) {
        this.directory = directory;
        this.server = server;
        this.dimension = dimension;
    }

    static List<ShareSpool.World> worldsUnder(Path root) {
        List<ShareSpool.World> found = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return found;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            collectWorlds(root, entries, found);
        } catch (IOException unreadable) {
            LOGGER.warn("geosurvey could not read the upload spool at {}. Nothing is deleted.",
                    root, unreadable);
        }
        return found;
    }

    private static void collectWorlds(Path root, Iterable<Path> entries, List<ShareSpool.World> found) {
        try {
            for (Path entry : entries) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                ShareSpool.World world = worldOf(entry);
                if (world != null) {
                    found.add(world);
                }
            }
        } catch (DirectoryIteratorException unreadable) {
            LOGGER.warn("geosurvey could not read the upload spool at {}. Nothing is deleted.",
                    root, unreadable.getCause());
        }
    }

    static ShareSpool.World worldOf(Path directory) {
        return worldOf(directory, directory.resolve(FILE_NAME));
    }

    private static ShareSpool.World worldOf(Path directory, Path world) {
        if (!Files.isRegularFile(world)) {
            return null;
        }
        ShareSpool.World found = null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(world), BUFFER_BYTES))) {
            String server = in.readUTF();
            if (!Batch.usableName(server)) {
                LOGGER.warn("geosurvey leaves the spool at {} alone: its world name is not usable.",
                        directory);
            } else {
                String dimension = in.readUTF();
                if (!Batch.usableName(dimension)) {
                    LOGGER.warn("geosurvey leaves the spool at {} alone: its world name is not usable.",
                            directory);
                } else {
                    found = new ShareSpool.World(server, dimension);
                }
            }
        } catch (IOException unreadable) {
            LOGGER.warn("geosurvey leaves the spool at {} alone: its world file is unreadable ({}).",
                    directory, unreadable.toString());
        }
        return found;
    }

    void rememberWorld() throws IOException {
        sweepLeftovers();
        Path world = directory.resolve(FILE_NAME);
        if (Files.isRegularFile(world)) {
            ShareSpool.World remembered = worldOf(directory, world);
            if (remembered == null || !server.equals(remembered.server())
                    || !dimension.equals(remembered.dimension())) {
                throw new IOException("the world marker does not name this spool's world");
            }
            return;
        }
        Path staging = Files.createTempFile(directory, FILE_NAME + ".", ".writing");
        boolean moved = false;
        try {
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(staging)))) {
                out.writeUTF(server);
                out.writeUTF(dimension);
            }
            ShareSpool.move(staging, world);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private void sweepLeftovers() {
        String prefix = FILE_NAME + ".";
        long olderThan = System.currentTimeMillis() - LEFTOVER_MILLIS;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                removeLeftoverIfOld(entry, prefix, olderThan);
            }
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.warn("geosurvey could not search {} for marker leftovers for {} {} ({}).",
                    directory, server, dimension, unreadable.toString());
        }
    }

    private void removeLeftoverIfOld(Path entry, String prefix, long olderThan) {
        String name = entry.getFileName().toString();
        if (!name.startsWith(prefix) || !name.endsWith(".writing")) {
            return;
        }
        try {
            if (Files.isRegularFile(entry)
                    && Files.getLastModifiedTime(entry).toMillis() <= olderThan
                    && Files.deleteIfExists(entry)) {
                LOGGER.info("geosurvey removed marker leftover {} for {} {}.",
                        entry, server, dimension);
            }
        } catch (IOException | RuntimeException leftBehind) {
            LOGGER.warn("geosurvey could not remove marker leftover {} for {} {} ({}).",
                    entry, server, dimension, leftBehind.toString());
        }
    }
}
