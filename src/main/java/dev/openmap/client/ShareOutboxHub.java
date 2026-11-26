package dev.openmap.client;

import com.mojang.authlib.GameProfile;
import dev.openmap.claim.Claims;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.Markers;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.Handle;
import dev.sandpaper.core.JobSpec;
import dev.sandpaper.core.Lane;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

// Starts and stops outbox; sole owner of beat, listeners, poster.
final class ShareOutboxHub {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final String FILE = "shared-outbox.json";

    private static final long PERIOD_MILLIS = 1_000L;

    // Max collector answers in chat per tick.
    private static final int MAX_ANSWERS_PER_TICK = 3;

    private static final String LABEL = "geosurvey-share-outbox";

    // Path for shared records, below the address.
    private static final String SHARED_PATH = "/shared";

    private static final AtomicBoolean LEAVING_HEARD = new AtomicBoolean();

    private static volatile ShareOutboxHub running;

    // Share name of the open server, or null; any thread.
    interface Server {

        String name();
    }

    // Entry-point seams; stop closes an AutoCloseable poster.
    // Listener for saved markers; null removes it.
    record Seams(Path dataDir, Supplier<LandNavConfig> settings, ShareOutbox.Poster poster, Server server,
                 Consumer<Markers.Saved> markersHook, ShareOutbox.Vanish vanish) {
    }

    // Read at each save: open server and posting.
    private final class Live implements ShareOutbox.Moment {

        @Override
        public String server() {
            return seams.server().name();
        }

        @Override
        public ShareOutbox.Posting posting() {
            return posting;
        }
    }

    private final Seams seams;

    private volatile ShareOutbox outbox;

    // Set each beat; read by saves on any thread.
    private volatile ShareOutbox.Posting posting = ShareOutbox.Posting.NONE;

    private volatile Handle tick;

    // Client thread only. Address last parsed; null first.
    private String parsedAddress = null;

    // Client thread only. Endpoint for parsedAddress.
    private URI parsedEndpoint = null;

    // Client thread only. Last player id; null before first.
    private UUID lastPlayerId = null;

    // The text of lastPlayerId; the client thread only.
    private String lastPlayerIdText = null;

    private ShareOutboxHub(Seams seams) {
        this.seams = seams;
    }

    static void start(Seams seams) {
        stop();
        ShareOutboxHub hub = new ShareOutboxHub(seams);
        hub.begin();
        running = hub;
        if (LEAVING_HEARD.compareAndSet(false, true)) {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forgetAnswers());
        }
    }

    static void stop() {
        ShareOutboxHub hub = running;
        running = null;
        if (hub != null) {
            hub.end();
        }
    }

    // Drops answers when a player leaves.
    private static void forgetAnswers() {
        ShareOutboxHub hub = running;
        ShareOutbox target = (hub == null) ? null : hub.outbox;
        if (target != null) {
            target.forgetAnswers();
        }
    }

    private void begin() {
        ShareOutbox target = new ShareOutbox(seams.dataDir().resolve(FILE), new Live(), seams.vanish());
        outbox = target;
        boolean queued = Sandpaper.isReady() && Sandpaper.workPool().submit(CollectorMod.MOD_ID,
                () -> {
                    target.loadIfNeeded();
                    return Boolean.TRUE;
                }, done -> { });
        if (!queued) {
            target.loadIfNeeded();
        }
        ShareCommand.afterClaimsSaved(claimsSaved(target));
        ShareCommand.sharedClaimsFrom(target);
        seams.markersHook().accept(landmarksSaved(target));
        tick = Sandpaper.scheduler().register(JobSpec.everyMillis(Lane.TICK, PERIOD_MILLIS)
                .withLabel(LABEL).withOwner(CollectorMod.MOD_ID), pump -> beat());
    }

    private void end() {
        Handle job = tick;
        tick = null;
        if (job != null) {
            job.cancel();
        }
        ShareCommand.afterClaimsSaved(null);
        ShareCommand.sharedClaimsFrom(null);
        seams.markersHook().accept(null);
        ShareOutbox target = outbox;
        outbox = null;
        if (target != null) {
            target.flush();
        }
        if (seams.poster() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception couldNotClose) {
                LOGGER.warn("Could not close the shared outbox poster.", couldNotClose);
            }
        }
    }

    // Claims listener; source is the saved book.
    static Claims.BookSaved claimsSaved(ShareOutbox target) {
        return (path, book) -> target.claimsSaved(path.toString(), book, System.currentTimeMillis());
    }

    static Markers.Saved landmarksSaved(ShareOutbox target) {
        return (source, dimensionId, store) -> target.markersSaved(source, dimensionId, store,
                System.currentTimeMillis());
    }

    // Collector answers per tick; null book: no world.
    static void sayAnswers(ShareOutbox target, Path book, long nowNanos, Consumer<Claims.Said> chat) {
        if (book == null) {
            return;
        }
        String world = book.toString();
        portHeld(target.portDue(world), world, chat);
        int said = 0;
        Claims.Said line = target.answer(world, nowNanos);
        while (line != null) {
            chat.accept(line);
            said++;
            line = (said < MAX_ANSWERS_PER_TICK) ? target.answer(world, nowNanos) : null;
        }
    }

    // Held port read on the pool; lines said on client.
    private static void portHeld(Supplier<Claims.Said> port, String world, Consumer<Claims.Said> chat) {
        if (port == null) {
            return;
        }
        Supplier<Claims.Said> guarded = () -> heldPortSaid(port, world);
        boolean queued = Sandpaper.isReady() && Sandpaper.workPool().submit(CollectorMod.MOD_ID, guarded,
                said -> sayHeld(said, chat));
        if (!queued) {
            sayHeld(guarded.get(), chat);
        }
    }

    private static Claims.Said heldPortSaid(Supplier<Claims.Said> port, String world) {
        Claims.Said said = null;
        try {
            said = port.get();
        } catch (RuntimeException unported) {
            LOGGER.warn("The claims port for {} failed.", world, unported);
        }
        return said;
    }

    // Non-blank lines to chat in order; said may be null.
    private static void sayHeld(Claims.Said said, Consumer<Claims.Said> chat) {
        String text = (said == null) ? null : said.text();
        if (text != null) {
            int from = 0;
            boolean more = true;
            while (more) {
                int next = text.indexOf('\n', from);
                more = next >= 0;
                String line = text.substring(from, more ? next : text.length());
                if (!line.isBlank()) {
                    chat.accept(new Claims.Said(said.ok(), line));
                }
                from = next + 1;
            }
        }
    }

    private void beat() {
        LandNavConfig config = seams.settings().get();
        posting = postingOf(config);
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = (client == null) ? null : client.player;
        GameProfile profile = (player == null) ? null : player.getGameProfile();
        ShareOutbox target = outbox;
        if ((profile != null) && (target != null)) {
            sayAnswers(target, ShareCommand.claimBook(), System.nanoTime(), ShareOutboxHub::sayNow);
            URI endpoint = sharedEndpoint(config.shareCollector);
            if (endpoint != null) {
                target.postDue(playerIdText(profile.id()), profile.name(), seams.poster(), endpoint,
                        config.shareEnabled, System.currentTimeMillis());
            }
        }
    }

    // Posting under these settings: none, removals, or all.
    ShareOutbox.Posting postingOf(LandNavConfig config) {
        ShareOutbox.Posting result;
        if (sharedEndpoint(config.shareCollector) == null) {
            result = ShareOutbox.Posting.NONE;
        } else if (config.shareEnabled) {
            result = ShareOutbox.Posting.ALL;
        } else {
            result = ShareOutbox.Posting.REMOVALS;
        }
        return result;
    }

    // Endpoint for this address; reparsed on change.
    URI sharedEndpoint(String address) {
        if ((parsedAddress == null) || !parsedAddress.equals(address)) {
            parsedEndpoint = ShareSender.endpointOf(address, SHARED_PATH);
            parsedAddress = address;
        }
        return parsedEndpoint;
    }

    // Player id as text; reformatted on change.
    String playerIdText(UUID id) {
        if (!id.equals(lastPlayerId)) {
            lastPlayerIdText = id.toString();
            lastPlayerId = id;
        }
        return lastPlayerIdText;
    }

    // Client thread. One claims line, grey or red.
    static void sayNow(Claims.Said said) {
        Minecraft client = Minecraft.getInstance();
        Gui gui = (client == null) ? null : client.gui;
        if (gui != null) {
            gui.hud.getChat().addClientSystemMessage(Component.literal(ShareCommand.drawable(said.text()))
                    .withStyle(said.ok() ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
    }
}
