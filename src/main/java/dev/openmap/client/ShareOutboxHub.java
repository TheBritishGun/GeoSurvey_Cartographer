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

// Starts and stops the share outbox: the one owner of its beat, of its listeners and of its poster.
final class ShareOutboxHub {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    static final String FILE = "shared-outbox.json";

    private static final long PERIOD_MILLIS = 1_000L;

    // The most collector answers put in chat in one tick.
    private static final int MAX_ANSWERS_PER_TICK = 3;

    private static final String LABEL = "geosurvey-share-outbox";

    // The path the collector takes shared records on, below its address.
    private static final String SHARED_PATH = "/shared";

    private static final AtomicBoolean LEAVING_HEARD = new AtomicBoolean();

    private static volatile ShareOutboxHub running;

    // The share name of the server whose world is open, or null; any thread.
    interface Server {

        String name();
    }

    // What the entry point hands the hub; stop closes a poster that is AutoCloseable. The markers hook takes
    // the listener of saved markers, or null to remove it.
    record Seams(Path dataDir, Supplier<LandNavConfig> settings, ShareOutbox.Poster poster, Server server,
                 Consumer<Markers.Saved> markersHook, ShareOutbox.Vanish vanish) {
    }

    // Read at each save: the server of the open world, and what the settings post as of the last beat.
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

    // Set by each beat; read by the saves of any thread.
    private volatile ShareOutbox.Posting posting = ShareOutbox.Posting.NONE;

    private volatile Handle tick;

    // The collector address parsedEndpoint was parsed from, null before the first parse; the client thread only.
    private String parsedAddress = null;

    // The endpoint shared records post to at parsedAddress; the client thread only.
    private URI parsedEndpoint = null;

    // The player id last put in text, null before the first; the client thread only.
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

    // A player who leaves the session is told nothing more of what the collector answered in it.
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

    // The claims listener; its source is the one world's book the claims were saved to.
    static Claims.BookSaved claimsSaved(ShareOutbox target) {
        return (path, book) -> target.claimsSaved(path.toString(), book, System.currentTimeMillis());
    }

    static Markers.Saved landmarksSaved(ShareOutbox target) {
        return (source, dimensionId, store) -> target.markersSaved(source, dimensionId, store,
                System.currentTimeMillis());
    }

    // What the collector answered for this world's commands, a few lines a tick; book may be null: no world.
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

    // A one-time claims port held for the first load: read on the pool, each line said on the client.
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

    // Each line of the port's text that is not blank goes to chat alone, in order. Said may be null.
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

    // What the outbox posts under these settings: nothing with no address the sender takes, else all or removals.
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

    // The endpoint shared records post to at this address, parsed again only when the address changes.
    URI sharedEndpoint(String address) {
        if ((parsedAddress == null) || !parsedAddress.equals(address)) {
            parsedEndpoint = ShareSender.endpointOf(address, SHARED_PATH);
            parsedAddress = address;
        }
        return parsedEndpoint;
    }

    // A player id as text, formatted again only when the id changes.
    String playerIdText(UUID id) {
        if (!id.equals(lastPlayerId)) {
            lastPlayerIdText = id.toString();
            lastPlayerId = id;
        }
        return lastPlayerIdText;
    }

    // One claims line in chat, grey or red as the reply it is; the client thread.
    static void sayNow(Claims.Said said) {
        Minecraft client = Minecraft.getInstance();
        Gui gui = (client == null) ? null : client.gui;
        if (gui != null) {
            gui.hud.getChat().addClientSystemMessage(Component.literal(ShareCommand.drawable(said.text()))
                    .withStyle(said.ok() ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
    }
}
