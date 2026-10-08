package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.api.Cartographers;
import dev.openmap.config.LandNavConfig;
import dev.openmap.json.SaveWriter;
import dev.openmap.map.MapStorage;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.WorkPool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class CollectorMod implements ClientModInitializer {

    public static final String MOD_ID = "openmap-collect";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MOD_ID);

    private static final MapStorage MAP = new MapStorage();

    private static final ChunkCapture CAPTURE = new ChunkCapture(MAP);

    private static final LiveMapClient LIVE = new LiveMapClient();

    private static final CollectorCartographer CARTOGRAPHER = new CollectorCartographer(CAPTURE.world(),
            CAPTURE.ground(), new CollectorCartographerMarkers(MAP, CAPTURE.world()));

    private static final Identifier HUD_CLAIM_SPLASH =
            Identifier.fromNamespaceAndPath(MOD_ID, "claim_splash");

    private static final SystemToast.SystemToastId LOAD_PROBLEM_TOAST =
            new SystemToast.SystemToastId();

    private static final long WORKER_STOP_MILLIS = 10_000L;

    @Override
    public void onInitializeClient() {
        sendGroundOnly(LandNav.config());

        SaveWriter.live().runner(task -> Sandpaper.workPool().submit(
                () -> {
                    task.run();
                    return null;
                },
                failure -> LOGGER.warn("could not run the settings writer", failure),
                SaveWriter.live()::droppedUnrun,
                failure -> LOGGER.warn("the settings writer failed", failure)));
        SaveWriter.live().note(message -> LOGGER.warn("{}", message));

        shareTheWritePool(MAP);
        ShareCommand.register(CollectorMod::mapStorage);
        sayClaimsPortNotesInChat();
        CAPTURE.register();
        LIVE.register();
        ClaimSplashHub.register();
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE,
                HUD_CLAIM_SPLASH, ClaimSplashHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SaveWriter.live().beat(System.nanoTime());
            sayLoadProblem(client, LandNav.config());
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Cartographers.withdraw(CARTOGRAPHER);
            CAPTURE.flushNow();
            ShareOutboxHub.stop();
            SaveWriter.live().finish(System.nanoTime(), SaveWriter.HOLD_NANOS);

            CAPTURE.unregister();
            LIVE.unregister();

            StoppableWorkers.stopAll(WORKER_STOP_MILLIS);
        });

        ShareOutboxHub.start(new ShareOutboxHub.Seams(LandNav.dataDir(), LandNav::config,
                ShareSender.outboxPoster(), CAPTURE::rootServer, MAP::afterLandmarksSaved,
                ShareSender::liveVanished));
        Cartographers.publish(CARTOGRAPHER);
    }

    public static ChunkCapture capture() {
        return CAPTURE;
    }

    public static MapStorage mapStorage() {
        return MAP;
    }

    public static LiveMapClient live() {
        return LIVE;
    }

    static void sayClaimsPortNotesInChat() {
        ShareCommand.claimsPortNotes(said -> ShareSender.sayGroundNotice(said.text()));
    }

    static void sendGroundOnly(LandNavConfig config) {
        ShareSender.publishesGroundOnly(config);
        if (config.takeSharePresenceSplit()) {
            LOGGER.info("geosurvey publishes no live position or player list."
                    + " Ground still goes up;"
                    + " a shared claim or marker sends your player name.");
        }
    }

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
                        "This session uses default settings; check the log."
                                + " A setting change saves them over the old file."));
        LOGGER.warn(config.loadProblem());
    }

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
            boolean taken = p.submit(CollectorMod.MOD_ID, task, done -> { }, task.receipt);
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
