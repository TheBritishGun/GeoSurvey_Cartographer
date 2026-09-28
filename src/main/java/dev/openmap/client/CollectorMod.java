package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.MapStorage;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.WorkPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

// The collector artifact's entrypoint: capture and share, no rendering.
public final class CollectorMod implements ClientModInitializer {

    public static final String MOD_ID = "openmap-collect";

    // Named "geosurvey", shared with GeoSurvey's own capture class.
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("geosurvey");

    private static final MapStorage MAP = new MapStorage();

    private static final ChunkCapture CAPTURE = new ChunkCapture(MAP);

    // Keeps the load-problem notice to one toast, not a stack.
    private static final SystemToast.SystemToastId LOAD_PROBLEM_TOAST =
            new SystemToast.SystemToastId();

    @Override
    public void onInitializeClient() {
        sendGroundOnly(LandNav.config());

        shareTheWritePool(MAP);
        ShareCommand.register(CollectorMod::mapStorage);
        CAPTURE.register();

        // Checked once a tick, since client.gui is not yet built at init.
        ClientTickEvents.END_CLIENT_TICK.register(client ->
                sayLoadProblem(client, LandNav.config()));

        // Flushes surveyed ground to disk on quit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            CAPTURE.flushNow();

            // Cancels the tick jobs and reverts the published position.
            CAPTURE.unregister();
        });
    }

    public static ChunkCapture capture() {
        return CAPTURE;
    }

    public static MapStorage mapStorage() {
        return MAP;
    }

    static void sendGroundOnly(LandNavConfig config) {
        ShareSender.publishesGroundOnly(config);
        if (config.takeSharePresenceSplit()) {
            LOGGER.info("geosurvey publishes no name, no live position and"
                    + " no player list."
                    + " Nothing contributed"
                    + " is affected."
                    + " Ground still"
                    + " goes up."
                    + "");
        }
    }

    // One-time toast: the last settings load could not read the file.
    static void sayLoadProblem(Minecraft client, LandNavConfig config) {
        if (client == null || !client.isGameLoadFinished()) {
            return;
        }
        if (!config.takeLoadProblemForNotice()) {
            return;
        }
        SystemToast.addOrUpdate(client.gui.toastManager(), LOAD_PROBLEM_TOAST,
                Component.translatableWithFallback(
                        "openmap-collect.toast.settings_unreadable.title",
                        "GeoSurvey Cartographer settings could not be read"),
                Component.translatableWithFallback(
                        "openmap-collect.toast.settings_unreadable.body",
                        "This session uses shipped defaults."
                                + " Check the log for why."
                                + " Changing a setting now saves the defaults over the old file."));
        LOGGER.warn(config.loadProblem());
    }

    // Routes this storage's region writes through the shared work pool.
    private static void shareTheWritePool(MapStorage storage) {
        storage.setLostGroundNote(LOGGER::warn);
        storage.writeThrough(new SharedWrites());
        storage.drivenByTheGameThread();
    }

    private static final class SharedWrites implements MapStorage.Writes {

        private volatile WorkPool pool;

        @Override
        public java.util.function.BooleanSupplier off(Runnable write) {
            WorkPool p = pool;
            if (p == null) {
                p = Sandpaper.workPool();
                pool = p;
            }
            SharedWrite task = new SharedWrite(write);
            boolean taken = p.submit(LandNav.MOD_ID, task, done -> { }, task.receipt);
            return taken ? task : null;
        }
    }

    private static final class SharedWrite implements java.util.function.Supplier<Boolean>,
            java.util.function.BooleanSupplier {

        final WorkPool.Receipt receipt = new WorkPool.Receipt();

        private final Runnable write;

        SharedWrite(Runnable write) {
            this.write = write;
        }

        @Override
        public Boolean get() {
            write.run();
            return Boolean.TRUE;
        }

        @Override
        public boolean getAsBoolean() {
            return receipt.isDroppedUnrun();
        }
    }
}
