package dev.openmap.client;

import dev.openmap.LandNav;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.MapStorage;
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

// How this artifact's settings reach the screen sandpaper hosts.
// Constructed by the SettingsContributor entrypoint in fabric.mod.json; no code here
// calls it.
public final class CollectorOptions implements SettingsContributor {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("geosurvey");

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

    private static final Component SESSION_PROOF_LABEL =
            Component.translatable("openmap-collect.option.session_proof");

    private static final Component SESSION_PROOF_DESCRIPTION =
            Component.translatable("openmap-collect.option.session_proof.desc");

    private static final Component GATE_LABEL =
            Component.translatable("openmap-collect.option.gate");

    private static final Component GATE_DESCRIPTION =
            Component.translatable("openmap-collect.option.gate.desc");

    private static final List<Component> GATE_LABELS =
            List.of(Component.translatable("openmap-collect.option.gate.all"),
                    Component.translatable("openmap-collect.option.gate.listed"));

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

    // The five settings while the reader is editing them.
    private static final class Staged {
        private String collector;
        private boolean enabled;
        private boolean sessionProof;
        private boolean gating;
        private String servers;
        private String cleartextOf;
        private boolean cleartext;

        private boolean isCleartext() {
            String address = collector;
            if (!address.equals(cleartextOf)) {
                cleartextOf = address;
                cleartext = ShareCommand.cleartextAwayFromHome(address.trim());
            }
            return cleartext;
        }

        private CollectorSettings.Draft draft() {
            return new CollectorSettings.Draft(
                    collector, enabled, sessionProof, gating, servers);
        }
    }

    // Reads nothing; everything is read when the screen opens.
    public CollectorOptions() {
        this(LandNav::config, LandNav::stageWrite, Sandpaper::workPool);
    }

    // For tests: a config supplier and a write a test can hand it directly.
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
        Staged editing = new Staged();
        editing.collector = now.shareCollector == null ? "" : now.shareCollector;
        editing.enabled = now.shareEnabled;
        editing.sessionProof = now.shareSessionProof;
        editing.gating = ChunkCapture.gating(now);
        editing.servers = String.join(", ", ChunkCapture.approvedServers(now));
        staged = editing;

        OptionSpec collector = new OptionSpec.Text(
                COLLECTOR_LABEL,
                COLLECTOR_DESCRIPTION,
                defaults.shareCollector == null ? "" : defaults.shareCollector,
                editing.collector,
                typed -> editing.collector = typed,
                null,
                typed -> {
                    // Typing an address turns contributing on; going back to blank does
                    // not turn it off.
                    boolean wasBlank = editing.collector.isBlank();
                    editing.collector = typed;
                    if (wasBlank && !typed.isBlank() && !editing.enabled) {
                        if (ShareCommand.looksUsable(typed.trim())
                                && !editing.isCleartext()) {
                            editing.enabled = true;
                        }
                    } else if (editing.enabled && editing.isCleartext()) {
                        editing.enabled = false;
                    } else {
                    }
                },
                () -> editing.collector,
                typed -> {
                    String address = typed.trim();
                    if (address.isEmpty()) {
                        return null;
                    }
                    if (!ShareCommand.looksUsable(address)) {
                        return Component.translatable("openmap-collect.status.not_an_address");
                    }
                    return ShareCommand.cleartextAwayFromHome(address)
                            ? Component.translatable("openmap-collect.status.cleartext")
                            : null;
                });

        OptionSpec enabled = new OptionSpec.Toggle(
                ENABLED_LABEL,
                ENABLED_DESCRIPTION,
                defaults.shareEnabled,
                editing.enabled,
                on -> editing.enabled = on,
                // Greyed only where it would arm, never where it would withdraw.
                () -> editing.enabled || !editing.isCleartext(),
                on -> editing.enabled = on,
                () -> editing.enabled);

        OptionSpec sessionProof = new OptionSpec.Toggle(
                SESSION_PROOF_LABEL,
                SESSION_PROOF_DESCRIPTION,
                defaults.shareSessionProof,
                editing.sessionProof,
                on -> editing.sessionProof = on,
                null,
                on -> editing.sessionProof = on,
                () -> editing.sessionProof);

        OptionSpec gate = new OptionSpec.Choice(
                GATE_LABEL,
                GATE_DESCRIPTION,
                GATE_LABELS,
                // Index 0 is every server; index 1 is only the servers listed.
                defaults.approvedServersConfigured ? 1 : 0,
                editing.gating ? 1 : 0,
                index -> editing.gating = index == 1,
                null,
                index -> {
                    editing.gating = index == 1;
                    if (index == 0) {
                        // Clears the list too, so a leftover entry cannot keep the gate armed.
                        editing.servers = "";
                    }
                },
                () -> editing.gating ? 1 : 0);

        OptionSpec servers = new OptionSpec.Text(
                SERVERS_LABEL,
                SERVERS_DESCRIPTION,
                "",
                editing.servers,
                typed -> editing.servers = typed,
                () -> editing.gating,
                typed -> editing.servers = typed,
                () -> editing.servers);

        return List.of(new SettingsPage(
                PAGE,
                PAGE_TITLE,
                List.of(
                        new SettingsGroup(
                                CONTRIBUTING_GROUP_TITLE,
                                List.of(collector, enabled, sessionProof)),
                        new SettingsGroup(
                                SERVERS_GROUP_TITLE,
                                List.of(gate, servers)))));
    }

    // Apply: hand the staged values to the one rule that writes them, then save.
    @Override
    public boolean save() {
        Staged taken = staged;
        if (taken == null) {
            // True: nothing was shown, so nothing needed saving.
            return true;
        }
        staleRetries.set(0);
        staleWriteSaid = false;
        CollectorSettings.write(live.get(), taken.draft());
        sayDroppedServers(taken.draft());
        LandNav.Write write = stage();
        return write != null && lands(write);
    }

    private static void sayDroppedServers(CollectorSettings.Draft draft) {
        if (!draft.gating()) {
            return;
        }
        List<String> dropped = CollectorSettings.droppedKeys(draft.servers());
        if (dropped.isEmpty()) {
            return;
        }
        String names = String.join(", ", dropped);
        LOGGER.warn("[openmap-collect] dropped " + names
                + " from the approved-server list: not a server address."
                + " The rest was saved.");
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui != null) {
            SystemToast.addOrUpdate(client.gui.toastManager(), DROPPED_SERVERS_TOAST,
                    Component.translatableWithFallback(
                            "openmap-collect.toast.dropped_servers.title",
                            "Some approved servers were not added"),
                    Component.translatableWithFallback(
                            "openmap-collect.toast.dropped_servers.body",
                            "%s is not a server address this mod can read. Not approved."
                                    + " The rest of the list was saved.",
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
        MapStorage.FileReplace.beginNoWait();
        try {
            write.write();
        } catch (IOException | RuntimeException broken) {
            if (broken instanceof MapStorage.FileReplace.Refused) {
                PENDING_SHUTDOWN.compareAndSet(null, write);
            }
            LOGGER.warn("[openmap-collect] could not write the settings the client"
                    + " stopped before saving", broken);
        } finally {
            MapStorage.FileReplace.endNoWait();
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
                "the work pool let the settings write go before it ran"));
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
                        "Not saved. Check the log."));
        return true;
    }

    private boolean writeHere(LandNav.Write write) {
        boolean landed;
        MapStorage.FileReplace.beginNoWait();
        try {
            write.write();
            landed = true;
        } catch (IOException | RuntimeException broken) {
            // Also reported to the player, separately.
            LOGGER.warn("[openmap-collect] could not save config", broken);
            landed = false;
        } finally {
            MapStorage.FileReplace.endNoWait();
        }
        if (landed) {
            PENDING_SHUTDOWN.compareAndSet(write, null);
        }
        return landed;
    }
}
