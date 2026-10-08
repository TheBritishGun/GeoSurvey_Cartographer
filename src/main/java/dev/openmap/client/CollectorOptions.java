package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.config.LandNavConfig;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.api.settings.OptionSpec;
import dev.sandpaper.api.settings.SettingsContributor;
import dev.sandpaper.api.settings.SettingsGroup;
import dev.sandpaper.api.settings.SettingsPage;
import dev.sandpaper.client.gui.SettingsContributions;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

// Constructed by the sandpaper-settings entrypoint.
public final class CollectorOptions implements SettingsContributor {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    private static final Identifier PAGE =
            Identifier.fromNamespaceAndPath(CollectorMod.MOD_ID, "contributing");

    private static final LandNavConfig DEFAULTS = new LandNavConfig();

    private static final Component PAGE_TITLE =
            Component.translatable("openmap-collect.config.page.title");

    private static final Component CONTRIBUTING_GROUP_TITLE =
            Component.translatable("openmap-collect.config.group.contributing");

    private static final Component SERVERS_GROUP_TITLE =
            Component.translatable("openmap-collect.config.group.servers");

    private static final Component COLLECTOR_LABEL =
            Component.translatable("openmap-collect.option.collector");

    private static final Component COLLECTOR_DESCRIPTION =
            Component.translatable("openmap-collect.option.collector.desc");

    private static final Component ENABLED_LABEL =
            Component.translatable("openmap-collect.option.enabled");

    private static final Component ENABLED_DESCRIPTION =
            Component.translatable("openmap-collect.option.enabled.desc");

    private static final Component SERVERS_LABEL =
            Component.translatable("openmap-collect.option.servers");

    private static final Component SERVERS_DESCRIPTION =
            Component.translatable("openmap-collect.option.servers.desc");

    @FunctionalInterface
    interface Saver {
        void save() throws IOException;
    }

    @FunctionalInterface
    interface Staging {
        LandNav.Write stage();
    }

    private static final class InlineWrite implements LandNav.Write {

        private final Saver saver;

        private InlineWrite(Saver saver) {
            this.saver = saver;
        }

        @Override
        public void write() throws IOException {
            saver.save();
        }

        @Override
        public boolean latest() {
            return true;
        }
    }

    private static final SystemToast.SystemToastId SAVE_FAILED_TOAST =
            new SystemToast.SystemToastId();

    private static final SystemToast.SystemToastId DROPPED_SERVERS_TOAST =
            new SystemToast.SystemToastId();

    static final int STALE_WRITE_CEILING = 3;

    static final String LOST_TO_A_NEWER_WRITE =
            "the settings write kept losing to a newer one";

    private final Supplier<LandNavConfig> live;

    private final Staging staging;

    private final Supplier<WorkPool> pool;

    private final AtomicInteger writesAflight = new AtomicInteger();

    private final AtomicInteger staleRetries = new AtomicInteger();

    private volatile boolean staleWriteSaid;

    private static final java.util.concurrent.atomic.AtomicBoolean SHUTDOWN_HOOK =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static final AtomicReference<LandNav.Write> PENDING_SHUTDOWN =
            new AtomicReference<>();

    // Null until the first pages() call.
    private Staged staged;

    private CollectorAddressCheck addressCheck;

    private static final class Staged {
        private final LandNavConfig live;
        private final CollectorAddressCheck check;
        private String collector;
        private boolean enabled;
        private boolean claimGreetings;
        private String servers;
        private String cleartextOf;
        private boolean cleartext;

        // The address isCleartext() last read, trimmed.
        private String trimmed = "";

        private Staged(LandNavConfig live, CollectorAddressCheck check) {
            this.live = live;
            this.check = check;
        }

        private boolean isCleartext() {
            String address = collector;
            if (!address.equals(cleartextOf)) {
                cleartextOf = address;
                trimmed = address.trim();
                cleartext = ShareCommand.cleartextAwayFromHome(trimmed);
            }
            return cleartext;
        }

        private CollectorSettings.Draft draft() {
            return new CollectorSettings.Draft(collector, enabled, servers, claimGreetings);
        }

        private void turned(boolean on) {
            enabled = on;
            if (on && collector.isBlank()) {
                String known = ChunkCapture.knownCollector(ChunkCapture.joinedServer());
                if (!known.isEmpty()) {
                    collector = known;
                    check.trusted(known);
                }
            }
        }
    }

    public CollectorOptions() {
        this(LandNav::config, () -> new InlineWrite(LandNav::saveOrThrow), Sandpaper::workPool);
    }

    // For tests.
    CollectorOptions(Supplier<LandNavConfig> live, Saver saver) {
        this(live, inline(saver), null);
    }

    CollectorOptions(Supplier<LandNavConfig> live, Staging staging, Supplier<WorkPool> pool) {
        this.live = live;
        this.staging = staging;
        this.pool = pool;
    }

    private static Staging inline(Saver saver) {
        return () -> new InlineWrite(saver);
    }

    @Override
    public List<SettingsPage> pages() {
        LandNavConfig now = live.get();
        LandNavConfig defaults = DEFAULTS;

        staged = null;
        CollectorAddressCheck check = newAddressCheck(now);
        Staged editing = new Staged(now, check);
        editing.collector = now.shareCollector == null ? "" : now.shareCollector;
        editing.enabled = now.shareEnabled;
        editing.claimGreetings = now.claimGreetings;
        editing.servers = String.join(", ", ChunkCapture.approvedServers(now));
        check.opensOn(editing.collector);
        addressCheck = check;
        staged = editing;

        OptionSpec status = new OptionSpec.Button(
                CollectorSettings.Setup.now(now, ShareSender.live()).statusLine(),
                CollectorSettings.STATUS_DESCRIPTION,
                CollectorSettings.STATUS_BUTTON,
                CollectorOptions::showSteps);

        OptionSpec collector = new OptionSpec.Text(
                COLLECTOR_LABEL,
                COLLECTOR_DESCRIPTION,
                defaults.shareCollector == null ? "" : defaults.shareCollector,
                editing.collector,
                typed -> {
                    editing.collector = typed;
                    check.typed(typed);
                },
                null,
                typed -> editing.collector = typed,
                () -> editing.collector,
                check::review);

        OptionSpec enabled = new OptionSpec.Toggle(
                ENABLED_LABEL,
                ENABLED_DESCRIPTION,
                defaults.shareEnabled,
                editing.enabled,
                on -> editing.enabled = on,
                // Greyed only where it would arm.
                () -> editing.enabled || (!editing.isCleartext()
                        && !editing.check.awaitsAnswer(editing.live.shareCollector, editing.trimmed)),
                on -> editing.turned(on),
                () -> editing.enabled);

        OptionSpec servers = new OptionSpec.Text(
                SERVERS_LABEL,
                SERVERS_DESCRIPTION,
                "",
                editing.servers,
                typed -> editing.servers = typed,
                null,
                typed -> editing.servers = typed,
                () -> editing.servers);

        OptionSpec claimGreetings = new OptionSpec.Toggle(
                CollectorSettings.CLAIM_GREETINGS_LABEL,
                CollectorSettings.CLAIM_GREETINGS_DESCRIPTION,
                defaults.claimGreetings,
                editing.claimGreetings,
                on -> editing.claimGreetings = on,
                () -> true,
                on -> editing.claimGreetings = on,
                () -> editing.claimGreetings);

        return List.of(new SettingsPage(
                PAGE,
                PAGE_TITLE,
                List.of(
                        new SettingsGroup(
                                CONTRIBUTING_GROUP_TITLE,
                                List.of(status, collector, enabled)),
                        new SettingsGroup(
                                SERVERS_GROUP_TITLE,
                                List.of(servers)),
                        new SettingsGroup(
                                CollectorSettings.CLAIM_GREETINGS_GROUP_TITLE,
                                List.of(claimGreetings)))));
    }

    @Override
    public boolean save() {
        Staged taken = staged;
        if (taken == null) {
            return true;
        }
        staleRetries.set(0);
        staleWriteSaid = false;
        CollectorAddressCheck check = addressCheck;
        if (check == null) {
            return false;
        }
        CollectorSettings.write(live.get(), taken.draft(), check);
        sayDroppedServers(taken.draft());
        LandNav.Write write = stage();
        return write != null && lands(write);
    }

    @Override
    public void screenOpened(SettingsContributor.Rules rules) {
        CollectorAddressCheck check = addressCheck;
        if (check != null) {
            check.opened(rules);
        }
    }

    CollectorAddressCheck addressCheck() {
        return addressCheck;
    }

    // Shows in chat at the next scan; client thread only.
    private static void showSteps() {
        ChunkCapture capture = ChunkCapture.live();
        if (capture != null) {
            capture.showSetup();
        }
    }

    static CollectorAddressCheck newAddressCheck(LandNavConfig config) {
        return new CollectorAddressCheck(config,
                Component.translatable("openmap-collect.status.checking"),
                Component.translatable("openmap-collect.status.no_answer"),
                Component.translatable("openmap-collect.status.cleartext"),
                Component.translatable("openmap-collect.status.not_an_address"));
    }

    // Writes like Done; client thread only.
    static boolean persistLive() {
        CollectorOptions writer =
                new CollectorOptions(LandNav::config,
                        () -> new InlineWrite(LandNav::saveOrThrow), Sandpaper::workPool);
        LandNav.Write write = writer.stage();
        return write != null && writer.lands(write);
    }

    private static void sayDroppedServers(CollectorSettings.Draft draft) {
        List<String> dropped = CollectorSettings.droppedKeys(draft.servers());
        if (dropped.isEmpty()) {
            return;
        }
        String names = String.join(", ", dropped);
        LOGGER.warn("[openmap-collect] dropped " + names
                + " from the approved-server list: not a server address.");
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui != null) {
            SystemToast.addOrUpdate(client.gui.toastManager(), DROPPED_SERVERS_TOAST,
                    Component.translatableWithFallback(
                            "openmap-collect.toast.dropped_servers.title",
                            "Some approved servers were not added"),
                    Component.translatableWithFallback(
                            "openmap-collect.toast.dropped_servers.body",
                            "%s is not a server address.",
                            names));
        }
    }

    private LandNav.Write stage() {
        LandNav.Write made;
        try {
            made = staging.stage();
        } catch (RuntimeException broken) {
            LOGGER.warn("[openmap-collect] could not stage the config write", broken);
            made = null;
        }
        return made;
    }

    private boolean lands(LandNav.Write write) {
        WorkPool pool = poolOrNull();
        Consumer<Throwable> back = failure -> returned(write, failure);
        writesAflight.incrementAndGet();
        PENDING_SHUTDOWN.set(write);
        armShutdownFlush();
        boolean landed;
        if (pool != null) {
            landed = pool.submit(() -> attempt(write), back, () -> neverRan(), back);
        } else {
            landed = false;
        }
        if (!landed) {
            writesAflight.decrementAndGet();
            landed = writeHere(write);
        }
        return landed;
    }

    private static void armShutdownFlush() {
        if (SHUTDOWN_HOOK.compareAndSet(false, true)) {
            ClientLifecycleEvents.CLIENT_STOPPING.register(
                    client -> flushPendingShutdown());
        }
    }

    static void keepForTheStopFlush(LandNav.Write write) {
        PENDING_SHUTDOWN.set(write);
        armShutdownFlush();
    }

    private static void flushPendingShutdown() {
        LandNav.Write write = PENDING_SHUTDOWN.getAndSet(null);
        if (write == null) {
            return;
        }
        AtomicFileReplace.beginNoWait();
        try {
            write.write();
        } catch (IOException | RuntimeException broken) {
            if (broken instanceof AtomicFileReplace.Refused) {
                PENDING_SHUTDOWN.compareAndSet(null, write);
            }
            LOGGER.warn("[openmap-collect] could not write the settings"
                    + " at shutdown", broken);
        } finally {
            AtomicFileReplace.endNoWait();
        }
    }

    private WorkPool poolOrNull() {
        Supplier<WorkPool> pools = pool;
        if (pools == null) {
            return null;
        }
        WorkPool found;
        try {
            found = pools.get();
        } catch (RuntimeException absent) {
            found = null;
        }
        return found;
    }

    private Throwable attempt(LandNav.Write write) {
        Throwable failure;
        try {
            write.write();
            PENDING_SHUTDOWN.compareAndSet(write, null);
            failure = null;
        } catch (IOException | RuntimeException broken) {
            failure = broken;
        }
        return failure;
    }

    private void neverRan() {
        writesAflight.decrementAndGet();
        saidWith(new IOException(
                "the work pool dropped the settings write unrun"));
    }

    private void returned(LandNav.Write write, Throwable failure) {
        int aflight = writesAflight.decrementAndGet();
        if (failure != null) {
            saidWith(failure);
        } else if (!write.latest()) {
            again(aflight);
        } else {
            PENDING_SHUTDOWN.compareAndSet(write, null);
            staleRetries.set(0);
            staleWriteSaid = false;
        }
    }

    private void again(int aflight) {
        if (aflight > 0) {
            return;
        }
        if (staleRetries.incrementAndGet() > STALE_WRITE_CEILING) {
            if (!staleWriteSaid) {
                staleWriteSaid = true;
                saidWith(new IOException(LOST_TO_A_NEWER_WRITE));
            }
        } else {
            LandNav.Write newest = stage();
            boolean landed;
            if (newest != null) {
                landed = lands(newest);
            } else {
                landed = false;
            }
            if (!landed) {
                if (tellThePlayer()) {
                    SettingsContributions.reportedOwnSaveFailure();
                }
            }
        }
    }

    private void saidWith(Throwable broken) {
        LOGGER.warn("[openmap-collect] could not save config", broken);
        if (tellThePlayer()) {
            SettingsContributions.reportedOwnSaveFailure();
        }
    }

    private static boolean tellThePlayer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return false;
        }
        SystemToast.addOrUpdate(client.gui.toastManager(), SAVE_FAILED_TOAST,
                Component.translatableWithFallback(
                        "openmap-collect.toast.save_failed.title",
                        "GeoSurvey Cartographer settings not saved"),
                Component.translatableWithFallback(
                        "openmap-collect.toast.save_failed.body",
                        "Check the log."));
        return true;
    }

    private boolean writeHere(LandNav.Write write) {
        boolean landed;
        AtomicFileReplace.beginNoWait();
        try {
            write.write();
            landed = true;
        } catch (IOException | RuntimeException broken) {
            LOGGER.warn("[openmap-collect] could not save config", broken);
            landed = false;
        } finally {
            AtomicFileReplace.endNoWait();
        }
        if (landed) {
            PENDING_SHUTDOWN.compareAndSet(write, null);
        }
        return landed;
    }
}
