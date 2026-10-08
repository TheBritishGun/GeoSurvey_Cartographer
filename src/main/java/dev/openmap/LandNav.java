package dev.openmap;

import dev.openmap.config.LandNavConfig;
import dev.openmap.json.SaveWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

public final class LandNav {

    public static final String MOD_ID = "geosurvey";

    public static final String DATA_DIR = "geosurvey";

    private static final long SAVE_NOW_BOUND_NANOS = 5_000_000_000L;

    private static final class SettingsKeeper implements SaveWriter.Keeper {
        @Override
        public Path file() {
            return configPath();
        }

        @Override
        public SaveWriter.Document document() throws IOException {
            return config().document();
        }

        @Override
        public void write(SaveWriter.Document document) throws IOException {
            LandNavConfig.writeDocument(file(), document);
        }
    }

    private static final class SettingsSaving implements SaveWriter.Saving {
        private IOException failure;

        @Override
        public void landed() {
        }

        @Override
        public void failed(IOException why) {
            failure = why;
        }
    }

    private static final SettingsKeeper SETTINGS_KEEPER = new SettingsKeeper();

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

    public static void saveOrThrow() throws IOException {
        SettingsSaving answer = new SettingsSaving();
        boolean saved = SaveWriter.live().saveNow(SETTINGS_KEEPER, answer, SAVE_NOW_BOUND_NANOS);
        if (!saved) {
            if (answer.failure != null) {
                throw answer.failure;
            }
            throw new IOException("the settings file is still being written");
        }
    }

    public static void saveSoon(SaveWriter.Saving saving) {
        SaveWriter.live().changed(SETTINGS_KEEPER, saving, SaveWriter.Timing.SOON);
    }

    public static void saveSettled(SaveWriter.Saving saving) {
        SaveWriter.live().changed(SETTINGS_KEEPER, saving, SaveWriter.Timing.SETTLED);
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
