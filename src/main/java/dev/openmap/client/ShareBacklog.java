package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.config.LandNavConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class ShareBacklog {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final String SPOOL_DIR = "upload";

    private static final String ACCOUNT_DIR = "by-account";

    private final CopyOnWriteArrayList<ShareSender.Outbound> worlds;

    private final Object worldsLock;

    private final SpoolTally spoolTally = new SpoolTally();

    private volatile Path spoolRoot;

    private String adoptedFor;

    private boolean unclaimedSaid;

    ShareBacklog(Path spoolRoot, CopyOnWriteArrayList<ShareSender.Outbound> worlds,
                 Object worldsLock) {
        this.spoolRoot = spoolRoot;
        this.worlds = worlds;
        this.worldsLock = worldsLock;
    }

    ShareSender.Outbound worldFor(URI endpoint, String account, String server, String dimension) {
        ShareSender.Outbound found = null;
        java.util.Iterator<ShareSender.Outbound> walk = worlds.iterator();
        while (found == null && walk.hasNext()) {
            ShareSender.Outbound out = walk.next();
            if (out.isFor(account, server, dimension)) {
                out.endpoint = endpoint;
                out.claim(account);
                found = out;
            }
        }
        if (found == null) {
            found = new ShareSender.Outbound(endpoint, account, server, dimension);
            worlds.add(found);
        }
        return found;
    }

    Path accountRoot(String account) {
        Path root = spoolRoot();
        return root == null || account == null ? null : root.resolve(ACCOUNT_DIR).resolve(account);
    }

    void adoptOnce(ShareSender.Identity identity, URI endpoint) {
        String account = identity == null ? null : identity.account();
        if (account == null) {
            return;
        }
        sayUnclaimed(account);
        if (account.equals(adoptedFor)) {
            return;
        }
        adoptedFor = account;
        Path root = accountRoot(account);
        if (root == null) {
            return;
        }
        List<ShareSpool.World> found = ShareSpool.worldsUnder(root);
        Set<World> held = holding(account);
        List<ShareSender.Outbound> adopted = new ArrayList<>();
        for (ShareSpool.World world : found) {
            if (!held.add(new World(world.server(), world.dimension()))) {
                continue;
            }
            adopted.add(new ShareSender.Outbound(endpoint, account, world.server(),
                    uploadName(world.server()), world.dimension(), false));
        }
        if (!adopted.isEmpty()) {
            synchronized (worldsLock) {
                Set<World> heldNow = holding(account);
                adopted.removeIf(out -> heldNow.contains(new World(out.server, out.dimension)));
                for (ShareSender.Outbound out : adopted) {
                    out.endpoint = endpoint;
                }
                worlds.addAll(adopted);
            }
        }
    }

    long spoolBytesForPass() {
        return spoolBytes(spoolTally);
    }

    long spoolBytes() {
        return spoolBytes(new SpoolTally());
    }

    long spooled() {
        long held = 0L;
        for (ShareSender.Outbound out : worlds) {
            held += out.queued();
            ShareSpool spool = out.spool;
            if (spool != null) {
                held += spool.records();
            } else {
                Path root = accountRoot(out.account());
                if (root != null) {
                    held += ShareSpool.backlogBytes(root, out.server, out.dimension)
                            / ShareSpool.RECORD_BYTES;
                }
            }
        }
        return held;
    }

    long spoolRefused() {
        long held = 0L;
        for (ShareSender.Outbound out : worlds) {
            ShareSpool spool = out.spool;
            if (spool != null) {
                held += spool.refusedRecords();
            } else {
                Path root = accountRoot(out.account());
                if (root != null) {
                    held += ShareSpool.backlogRefusedRecords(root, out.server, out.dimension);
                }
            }
        }
        return held;
    }

    private long spoolBytes(SpoolTally tally) {
        tally.held = 0L;
        worlds.forEach(tally);
        return tally.held;
    }

    private Set<World> holding(String account) {
        Set<World> held = new HashSet<>();
        for (ShareSender.Outbound out : worlds) {
            if (out.mine(account)) {
                held.add(new World(out.server, out.dimension));
            }
        }
        return held;
    }

    private void sayUnclaimed(String account) {
        if (unclaimedSaid) {
            return;
        }
        unclaimedSaid = true;
        Path root = spoolRoot();
        if (root == null) {
            return;
        }
        List<ShareSpool.World> orphaned = ShareSpool.worldsUnder(root);
        long held = 0L;
        for (ShareSpool.World world : orphaned) {
            held += bytesUnder(root.resolve(ShareSpool.folderFor(world.server(), world.dimension())));
        }
        if (held > 0L) {
            LOGGER.warn("geosurvey holds {} bytes of ground under {} for account {}."
                    + " To send it, move it into {}.", held, root,
                    account, accountRoot(account));
        }
    }

    private Path spoolRoot() {
        Path root = spoolRoot;
        if (root == null) {
            root = defaultSpoolRoot();
            spoolRoot = root;
        }
        return root;
    }

    private static Path defaultSpoolRoot() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve(LandNav.DATA_DIR).resolve(SPOOL_DIR);
        } catch (RuntimeException | LinkageError noGame) {
            try {
                return Files.createTempDirectory("geosurvey-upload-");
            } catch (IOException nowhere) {
                return null;
            }
        }
    }

    private static long bytesUnder(Path directory) {
        long held = 0L;
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                BasicFileAttributes attributes = Files.readAttributes(entry,
                        BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    held += attributes.size();
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return held;
        }
        return held;
    }

    private static String uploadName(String recorded) {
        return ChunkCapture.knownCollector(recorded).isEmpty() ? recorded
                : LandNavConfig.KnownServers.nameOf(CollectorGuess.hostOf(recorded));
    }

    private record World(String server, String dimension) {
    }

    private final class SpoolTally implements Consumer<ShareSender.Outbound> {

        private long held;

        @Override
        public void accept(ShareSender.Outbound out) {
            ShareSpool spool = out.spool;
            if (spool != null) {
                held += spool.bytes();
            } else {
                Path root = accountRoot(out.account());
                if (root != null) {
                    held += ShareSpool.backlogBytes(root, out.server, out.dimension);
                }
            }
        }
    }
}
