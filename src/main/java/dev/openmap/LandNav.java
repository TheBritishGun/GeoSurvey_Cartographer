package dev.openmap;

import dev.openmap.config.LandNavConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

public final class LandNav {

    public static final String MOD_ID = "geosurvey";

    public static final String DATA_DIR = "geosurvey";

    // Client thread only.
    private static LandNavConfig config;

    private static final AtomicLong writesTaken = new AtomicLong();

    private static long normalisedState;

    private static boolean normalisedStateTaken;

    private static Path configPath;

    private static Supplier<Path> configDirLoader =
            () -> FabricLoader.getInstance().getConfigDir();

    private static volatile Path dataDir;

    private static Supplier<Path> gameDirLoader =
            () -> FabricLoader.getInstance().getGameDir();

    private LandNav() {
    }

    public static Path configPath() {
        if (configPath == null) {
            configPath = configDirLoader.get().resolve(MOD_ID + ".json");
        }
        return configPath;
    }

    public static LandNavConfig config() {
        if (config == null) {
            config = LandNavConfig.load(configPath());
            normalisedState = config.stateVersion();
            normalisedStateTaken = true;
            report(config);
        }
        return config;
    }

    private static void report(LandNavConfig settings) {
        String problem = settings.loadProblem();
        if (!problem.isEmpty()) {
            System.err.println("[geosurvey] " + problem);
        }
        String backendProblem = settings.liveMapBackendProblem();
        if (!backendProblem.isEmpty()) {
            System.err.println("[geosurvey] " + backendProblem);
        }
        for (String colourProblem : settings.colourProblems()) {
            System.err.println("[geosurvey] " + colourProblem);
        }
    }

    public static Path dataDir() {
        if (dataDir == null) {
            dataDir = gameDirLoader.get().resolve(DATA_DIR);
        }
        return dataDir;
    }

    // Logs a failure; a caller that can tell the player uses saveOrThrow() instead.
    public static void save() {
        try {
            saveOrThrow();
        } catch (IOException e) {
            System.err.println("[geosurvey] could not save config: " + e.getMessage());
        }
    }

    public static void saveOrThrow() throws IOException {
        stageWrite().write();
    }

    public static Write stageWrite() {
        LandNavConfig settings = config();
        boolean moved = !normalisedStateTaken || settings.stateVersion() != normalisedState;
        if (moved) {
            settings.normalise();
            normalisedState = settings.stateVersion();
            normalisedStateTaken = true;
        }
        long taken = moved ? writesTaken.incrementAndGet() : writesTaken.get();
        LandNavConfig staged = settings.copy();
        configPath();
        return new Taken(staged, taken, normalisedState);
    }

    public interface Write {

        void write() throws IOException;

        boolean latest();
    }

    private static final class Taken implements Write {

        private final LandNavConfig staged;
        private final long taken;
        private final long settledState;

        private Taken(LandNavConfig staged, long taken, long settledState) {
            this.staged = staged;
            this.taken = taken;
            this.settledState = settledState;
        }

        @Override
        public void write() throws IOException {
            if (taken != writesTaken.get()) {
                return;
            }
            staged.save(configPath(), settledState, () -> taken == writesTaken.get());
        }

        @Override
        public boolean latest() {
            return taken == writesTaken.get();
        }
    }
}
