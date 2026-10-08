package dev.openmap.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.openmap.LandNav;
import dev.openmap.json.AtomicFileReplace;
import dev.openmap.claim.ClaimBook;
import dev.openmap.claim.Claims;
import dev.openmap.config.LandNavConfig;
import dev.openmap.map.LabelText;
import dev.openmap.map.MapStorage;
import dev.openmap.map.Markers;
import dev.openmap.share.RosterReport;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.core.WorkPool;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class ShareCommand {

    @FunctionalInterface
    interface Persist {

        void save() throws IOException;

        default LandNav.Write staged() {
            return null;
        }
    }

    record Answer(boolean ok, String text) {
    }

    record CollectorAddress(boolean accepted, String address, String refusal) {
    }

    private ShareCommand() {
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(CollectorMod.MOD_ID);

    public static void register(Supplier<MapStorage> storage) {
        worlds = storage;
        Markers markers = new Markers(storage);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            CLAIMS.workPool(Sandpaper.workPool());
            CLAIMS.saveFailureSink(problem -> LOGGER.warn(
                    "could not write the claim book; a temporary file may"
                            + " remain beside it", problem));
            dispatcher.register(tree(LandNav::config, SETTINGS, CLAIMS, markers,
                    ShareCommand::standingOf, Sandpaper::workPool));
        });
    }

    // Set by register; read on the client thread.
    private static volatile Supplier<MapStorage> worlds = () -> null;

    private static Path sharedBook() {
        return LandNav.dataDir().resolve(ClaimBook.FILE);
    }

    private record Placed(Path world, Path book) {
    }

    private static volatile Placed placed;

    private static final Claims CLAIMS = claims();

    private static Claims claims() {
        Claims claims = new Claims(ShareCommand::currentBook, ShareCommand::sharedBook);
        claims.listenerFailureNote(LOGGER::warn);
        return claims;
    }

    // Client thread only.
    public static Path claimBook() {
        MapStorage storage = worlds.get();
        if (storage == null) {
            return null;
        }
        return claimBook(storage, ShareCommand::sharedBook);
    }

    // Client thread only; null without a world.
    static Path claimBook(MapStorage storage, Supplier<Path> shared) {
        Path world = storage == null ? null : storage.root();
        Placed was = placed;
        Path book;
        if (was != null && Objects.equals(was.world(), world)) {
            book = was.book();
        } else {
            book = ClaimBook.bookIn(world);
            placed = new Placed(world, book);
            if (book != null) {
                CLAIMS.retireAndPortOnceAsync(book, shared.get(), ShareCommand::portNote);
            }
        }
        return book;
    }

    // Any thread; returns the book claimBook last resolved.
    static Path currentBook() {
        Placed was = placed;
        return was == null ? null : was.book();
    }

    private static volatile Consumer<Claims.Said> portNotes = ShareCommand::logPortNote;

    // listener may be null: nobody is told of a saved claims book.
    public static void afterClaimsSaved(Claims.BookSaved listener) {
        CLAIMS.afterSave(listener);
    }

    // lookup may be null: no shared claim's entry is known.
    public static void sharedClaimsFrom(Claims.SharedFrom lookup) {
        CLAIMS.sharedFrom(lookup);
    }

    public static void claimsPortNotes(Consumer<Claims.Said> notes) {
        portNotes = (notes == null) ? ShareCommand::logPortNote : notes;
    }

    private static void logPortNote(Claims.Said said) {
        LOGGER.info("{}", said.text());
    }

    // Each line of a note goes to the sink alone.
    private static void portNote(Claims.Said said) {
        String text = said.text();
        int from = 0;
        boolean more = true;
        while (more) {
            int next = text.indexOf('\n', from);
            more = next >= 0;
            portNotes.accept(new Claims.Said(said.ok(), text.substring(from, more ? next : text.length())));
            from = next + 1;
        }
    }

    private static final class DirectSettings implements Persist {

        @Override
        public void save() throws IOException {
            LandNav.saveOrThrow();
        }

        @Override
        public LandNav.Write staged() {
            return null;
        }
    }

    private static final Persist SETTINGS = new DirectSettings();

    static LiteralArgumentBuilder<FabricClientCommandSource> tree(
            Supplier<LandNavConfig> settings, Persist persist,
            Markers markers) {
        return tree(settings, persist, CLAIMS, markers,
                ShareCommand::standingOf);
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> tree(
            Supplier<LandNavConfig> settings, Persist persist, Claims claims,
            Markers markers,
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where) {
        return tree(settings, persist, claims, markers, where, null);
    }

    static LiteralArgumentBuilder<FabricClientCommandSource> tree(
            Supplier<LandNavConfig> settings, Persist persist, Claims claims,
            Markers markers,
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where,
            Supplier<WorkPool> pools) {
        return ClientCommands.literal("geosurvey")
                .then(claimTree(settings, claims, where))
                .then(markerTree(settings, markers, where))
                .then(ClientCommands.literal("share")
                        .executes(context -> speak(context, status(settings.get())))
                        .then(ClientCommands.literal("off")
                                .executes(context ->
                                        spoken(context, off(settings.get()),
                                                persist, pools)))
                        .then(ClientCommands.literal("on")
                                .executes(context ->
                                        spoken(context, on(settings.get()),
                                                persist, pools)))
                        .then(ClientCommands.literal("proof")
                                .then(ClientCommands.literal("on")
                                        .executes(context ->
                                                spoken(context, proof(settings.get(), true),
                                                        persist, pools)))
                                .then(ClientCommands.literal("off")
                                        .executes(context ->
                                                spoken(context, proof(settings.get(), false),
                                                        persist, pools)))))
                .then(ClientCommands.literal("collector")
                        .then(ClientCommands.literal("clear")
                                .executes(context -> speak(context, new Answer(false,
                                        "Nothing was set. Empty the collector address in the settings."))))
                        .then(ClientCommands.literal("find")
                                .executes(context -> CollectorFinder.offer(
                                        context.getSource(), settings.get())))
                        .then(ClientCommands.argument("address",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(any ->
                                        CollectorFinder.seeds(settings.get())))
                                .executes(context -> CollectorFinder.check(context.getSource(), settings.get(),
                                        StringArgumentType.getString(context, "address"), persist))))
                .then(ClientCommands.literal("server")
                        .executes(context -> speak(context, servers(settings.get())))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("address",
                                                StringArgumentType.greedyString())
                                        .executes(context -> spoken(context, addServer(
                                                settings.get(),
                                                StringArgumentType.getString(context, "address")),
                                                persist, pools))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("address",
                                                StringArgumentType.greedyString())
                                        .suggests(Suggest.cleaned(any ->
                                                ChunkCapture.approvedServers(
                                                        settings.get())))
                                        .executes(context -> spoken(context, removeServer(
                                                settings.get(),
                                                StringArgumentType.getString(context, "address")),
                                                persist, pools)))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> claimTree(
            Supplier<LandNavConfig> settings, Claims claims,
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where) {
        return ClientCommands.literal("claim")
                .executes(context -> {
                    claimBook();
                    return say(context, claims.list());
                })
                .then(ClientCommands.literal("start")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .executes(context -> {
                                    claimBook();
                                    return answered(claims.startAsync(where.apply(context),
                                            arg(context, "name"), said -> say(context, said)));
                                })))
                .then(ClientCommands.literal("corner")
                        .executes(context -> {
                            claimBook();
                            return say(context, claims.corner(where.apply(context)));
                        }))
                .then(ClientCommands.literal("undo")
                        .executes(context -> {
                            claimBook();
                            return say(context, claims.undo());
                        }))
                .then(ClientCommands.literal("cancel")
                        .executes(context -> {
                            claimBook();
                            return say(context, claims.cancel());
                        }))
                .then(ClientCommands.literal("finish")
                        .executes(context -> {
                            claimBook();
                            return answered(claims.finishAsync(where.apply(context),
                                    said -> say(context, said)));
                        }))
                .then(ClientCommands.literal("colour")
                        .then(ClientCommands.argument("colour",
                                        StringArgumentType.word())
                                .suggests(Suggest.of(any -> Suggest.COLOURS()))
                                .executes(context -> {
                                    claimBook();
                                    return say(context, claims.penColour(arg(context, "colour")));
                                })
                                .then(ClientCommands.argument("name",
                                                StringArgumentType.greedyString())
                                        .suggests(Suggest.of(any -> claimNames(claims)))
                                        .executes(context -> {
                                            claimBook();
                                            return answered(claims.recolourAsync(
                                                    arg(context, "name"), arg(context, "colour"),
                                                    said -> say(context, said)));
                                        }))))
                .then(ClientCommands.literal("rename")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(any -> claimNames(claims)))
                                .then(ClientCommands.argument("newName",
                                                StringArgumentType.greedyString())
                                        .executes(context -> {
                                            claimBook();
                                            return answered(claims.renameAsync(
                                                    arg(context, "name"), arg(context, "newName"),
                                                    said -> say(context, said)));
                                        }))))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(any -> claimNames(claims)))
                                .executes(context -> {
                                    claimBook();
                                    Claims.Standing here = where.apply(context);
                                    String playerId = here == null ? "" : here.playerId();
                                    return answered(claims.removeAsync(arg(context, "name"), playerId,
                                            said -> say(context, said)));
                                })))
                .then(ClientCommands.literal("share")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(any -> claimNames(claims)))
                                .executes(context -> {
                                    claimBook();
                                    Claims.Standing here = where.apply(context);
                                    String playerId = here == null ? "" : here.playerId();
                                    return answered(claims.shareAsync(arg(context, "name"),
                                            settings.get().shareCollector, playerId,
                                            said -> say(context, said)));
                                })))
                .then(ClientCommands.literal("unshare")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(any -> claimNames(claims)))
                                .executes(context -> {
                                    claimBook();
                                    Claims.Standing here = where.apply(context);
                                    String playerId = here == null ? "" : here.playerId();
                                    return answered(claims.unshareAsync(arg(context, "name"), playerId,
                                            said -> say(context, said)));
                                })))
                .then(ClientCommands.literal("where")
                        .executes(context -> {
                            claimBook();
                            return say(context, claims.where());
                        }))
                .then(ClientCommands.literal("port")
                        .executes(context -> {
                            claimBook();
                            return answered(claims.portAsync(said -> say(context, said)));
                        }));
    }

    private static java.util.List<String> claimNames(Claims claims) {
        claimBook();
        return claims.names();
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> markerTree(
            Supplier<LandNavConfig> settings, Markers markers,
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where) {
        return ClientCommands.literal("marker")
                .executes(context ->
                        mark(context, markers.list(worldOf(where, context))))
                .then(ClientCommands.literal("add")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .executes(context -> {
                                    Claims.Standing here = where.apply(context);
                                    if (here == null) {
                                        return mark(context, markers.list(null));
                                    }
                                    String name = arg(context, "name");
                                    return markNamed(context, name,
                                            () -> markers.add(
                                                    worldOf(where, context),
                                                    (int) Math.floor(here.x()),
                                                    (int) Math.floor(here.z()),
                                                    name));
                                }))
                        .then(ClientCommands.literal("at")
                                .then(ClientCommands.argument("x",
                                                IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z",
                                                        IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("name",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> {
                                                            String name = arg(context, "name");
                                                            return markNamed(context, name,
                                                                    () -> markers.add(
                                                                            worldOf(where, context),
                                                                            IntegerArgumentType.getInteger(
                                                                                    context, "x"),
                                                                            IntegerArgumentType.getInteger(
                                                                                    context, "z"),
                                                                            name));
                                                        }))))))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(context ->
                                        markers.names(worldOf(where, context))))
                                .executes(context -> mark(context,
                                        markers.remove(worldOf(where, context),
                                                arg(context, "name"))))))
                .then(ClientCommands.literal("share")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(context ->
                                        markers.names(worldOf(where, context))))
                                .executes(context -> {
                                    Claims.Standing here = where.apply(context);
                                    return mark(context, markers.share(worldOf(where, context),
                                            arg(context, "name"), settings.get().shareCollector,
                                            here == null ? "" : here.player()));
                                })))
                .then(ClientCommands.literal("unshare")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(context ->
                                        markers.names(worldOf(where, context))))
                                .executes(context -> mark(context,
                                        markers.unshare(worldOf(where, context),
                                                arg(context, "name"))))))
                .then(ClientCommands.literal("rename")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(context ->
                                        markers.names(worldOf(where, context))))
                                .then(ClientCommands.argument("newName",
                                                StringArgumentType.greedyString())
                                        .executes(context -> markNamed(context,
                                                arg(context, "newName"),
                                                () -> markers.rename(
                                                        worldOf(where, context),
                                                        arg(context, "name"),
                                                        arg(context, "newName"))))))
                        .then(ClientCommands.literal("at")
                                .then(ClientCommands.argument("x",
                                                IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z",
                                                        IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("name",
                                                                StringArgumentType.greedyString())
                                                        .executes(context -> {
                                                            String name = arg(context, "name");
                                                            return markNamed(context, name,
                                                                    () -> markers.renameAt(
                                                                            worldOf(where, context),
                                                                            IntegerArgumentType.getInteger(
                                                                                    context, "x"),
                                                                            IntegerArgumentType.getInteger(
                                                                                    context, "z"),
                                                                            name));
                                                        }))))))
                .then(ClientCommands.literal("colour")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(context ->
                                        markers.names(worldOf(where, context))))
                                .then(ClientCommands.argument("colour",
                                                StringArgumentType.greedyString())
                                        // Also accepts a hex code, not suggested.
                                        .suggests(Suggest.of(any -> Suggest.COLOURS()))
                                        .executes(context -> mark(context,
                                                markers.colour(
                                                        worldOf(where, context),
                                                        arg(context, "name"),
                                                        arg(context, "colour")))))))
                .then(ClientCommands.literal("icon")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(context ->
                                        markers.names(worldOf(where, context))))
                                .then(ClientCommands.argument("icon",
                                                StringArgumentType.word())
                                        .suggests(Suggest.of(any -> Suggest.ICONS()))
                                        .executes(context -> mark(context,
                                                markers.icon(
                                                        worldOf(where, context),
                                                        arg(context, "name"),
                                                        arg(context, "icon")))))))
                .then(ClientCommands.literal("affiliation")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(context ->
                                        markers.names(worldOf(where, context))))
                                .then(ClientCommands.argument("affiliation",
                                                StringArgumentType.word())
                                        .suggests(Suggest.of(any ->
                                                Suggest.AFFILIATIONS()))
                                        .executes(context -> mark(context,
                                                markers.affiliation(
                                                        worldOf(where, context),
                                                        arg(context, "name"),
                                                        arg(context, "affiliation")))))));
    }

    // A marker's dimension, or null outside a world.
    private static String worldOf(
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where,
            CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getPlayer() == null) {
            return null;
        }
        var level = source.getLevel();
        return level == null ? null : level.dimension().toString();
    }

    private static int mark(CommandContext<FabricClientCommandSource> context,
            Markers.Said said) {
        return speak(context, new Answer(said.ok(), drawableLines(said.text())));
    }

    static Answer undrawableName(String typed) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return null;
        }
        String drawnName = drawable(name);
        if (drawnName.equals(name)) {
            return null;
        }
        return new Answer(false, "Nothing was saved. \"" + shownDrawable(drawnName)
                + "\" has a character chat cannot show.");
    }

    private static int markNamed(CommandContext<FabricClientCommandSource> context,
            String typed, Supplier<Markers.Said> command) {
        Answer refused = undrawableName(typed);
        return refused == null ? mark(context, command.get()) : speak(context, refused);
    }

    // Null without a world.
    private static Claims.Standing standingOf(
            CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        LocalPlayer player = source.getPlayer();
        if (player == null) {
            return null;
        }
        var level = source.getLevel();
        if (level == null) {
            return null;
        }
        BlockPos at = player.blockPosition();
        return new Claims.Standing(
                level.dimension().identifier().toString(),
                at.getX(), at.getZ(),
                player.getGameProfile().name(),
                player.getUUID().toString());
    }

    private static String arg(CommandContext<FabricClientCommandSource> context,
            String name) {
        return StringArgumentType.getString(context, name);
    }

    private static int say(CommandContext<FabricClientCommandSource> context,
            Claims.Said said) {
        return speak(context, new Answer(said.ok(), drawableLines(said.text())));
    }

    private static int answered(Claims.Outcome outcome) {
        return switch (outcome) {
            case ANSWERED_OK, ANSWER_STILL_QUEUED -> 1;
            case ANSWERED_REFUSAL -> 0;
        };
    }


    private static final String SERVER_COMMANDS =
            "Add one with /geosurvey server add <ip>; remove one with /geosurvey server remove <ip>.";

    private static final String NO_SERVER_APPROVED =
            "No server is approved; add one with /geosurvey server add <ip>.";

    static Answer servers(LandNavConfig config) {
        if (!ChunkCapture.gating(config)) {
            return new Answer(true, "Contributing from every server."
                    + " Limit it with /geosurvey server add"
                    + " <ip>");
        }
        java.util.List<String> list = shownServers(ChunkCapture.approvedServers(config));
        int count = list.size();
        String said;
        if (count == 0) {
            said = NO_SERVER_APPROVED;
        } else if (count == 1) {
            said = "Contributing from 1 server: " + joinedServers(list) + ". " + SERVER_COMMANDS;
        } else {
            said = serverLines(list, count);
        }
        return new Answer(true, said);
    }

    // The count, 1 line for each server, then how to change the list.
    private static String serverLines(java.util.List<String> entries, int count) {
        StringBuilder out = new StringBuilder(JOINED_SERVERS_CAPACITY)
                .append("Contributing from ").append(count).append(" servers:");
        for (int i = 0; i < count; i++) {
            out.append('\n');
            withAsciiForm(out, drawable(String.valueOf(entries.get(i))));
        }
        return out.append('\n').append(SERVER_COMMANDS).toString();
    }

    // The entries a reply shows: not null, not blank once drawn.
    private static java.util.List<String> shownServers(java.util.List<String> entries) {
        java.util.List<String> shown = new java.util.ArrayList<>(entries.size());
        for (String entry : entries) {
            if (entry != null && !drawable(entry).isBlank()) {
                shown.add(entry);
            }
        }
        return shown;
    }

    // The line that names the servers shown, or says that none is approved.
    private static String fromLine(java.util.List<String> shown) {
        return shown.isEmpty() ? NO_SERVER_APPROVED : "Contributing from " + someServers(shown) + ".";
    }

    static Answer addServer(LandNavConfig config, String typed, Persist persist) {
        return settle(addServer(config, typed), persist);
    }

    private static Answer addServer(LandNavConfig config, String typed) {
        String key = ChunkCapture.serverKey(typed);
        if (key.isEmpty()) {
            return new Answer(false, "\"" + shown(typed) + "\" is not a server"
                    + " address. Use a host name or IP,"
                    + " with or without a"
                    + " port.");
        }
        String drawnKey = drawable(key);
        if (!drawnKey.equals(key)) {
            return new Answer(false, "Nothing was added. \"" + shownDrawable(drawnKey)
                    + "\" has a character chat cannot show.");
        }
        if (ChunkCapture.gatesOn(config, typed)) {
            return new Answer(false, withAsciiForm(key)
                    + " is already approved.");
        }
        boolean arming = !ChunkCapture.gating(config);
        java.util.List<String> list = ChunkCapture.approvedServers(config);
        list.add(ChunkCapture.serverWorldKey(typed));
        config.approvedServersConfigured = true;
        String named = withAsciiForm(key);
        return new Answer(true, "Added " + named + ";"
                + (arming
                        ? " contributing from "
                                + named + " only."
                        : " contributing from "
                                + someServers(shownServers(list)) + "."));
    }

    static Answer removeServer(LandNavConfig config, String typed, Persist persist) {
        return settle(removeServer(config, typed), persist);
    }

    private static Answer removeServer(LandNavConfig config, String typed) {
        String seen = drawable(keyOrFolded(typed));
        String seenFold = drawable(fold(typed));
        String seenJoined = drawable(ChunkCapture.serverWorldKey(typed));
        java.util.List<String> list = ChunkCapture.approvedServers(config);
        if (seen.isEmpty()
                || !list.removeIf(entry -> matchesRemoval(entry, seenFold, seenJoined))) {
            return new Answer(false, "\"" + shown(typed) + "\" is not approved."
                    + " /geosurvey server shows the list.");
        }
        config.approvedServersConfigured = true;
        config.settleServerList();
        String rest;
        if (!list.isEmpty()) {
            rest = "\n" + fromLine(shownServers(list));
        } else if (config.takeEmptyServerListTurnedOff()) {
            stopSending(config);
            rest = "\nContribute ground is off;"
                    + " turn it on with /geosurvey share on"
                    + " for every server.";
        } else {
            rest = "\nThe list is empty;"
                    + " contribute ground is off.";
        }
        return new Answer(true, "Removed " + withAsciiForm(seen) + "." + rest);
    }

    private static boolean matchesRemoval(String entry, String seenFold, String seenJoined) {
        String entryKey = ChunkCapture.serverKey(entry);
        return entryKey.isEmpty() ? seenFold.equals(drawable(fold(entry)))
                : seenJoined.equals(drawable(ChunkCapture.serverWorldKey(entry)));
    }

    private static String keyOrFolded(String address) {
        String key = ChunkCapture.serverKey(address);
        return !key.isEmpty() || address == null ? key : fold(address);
    }

    private static String fold(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }

    private static final int SHOWN_LIMIT = 64;

    private static final String SHOWN_ELLIPSIS = "...";

    private static String shown(String typed) {
        return shownDrawable(drawable(typed == null ? "" : typed.trim(), SHOWN_LIMIT + 1));
    }

    private static String shownDrawable(String text) {
        if (text.length() <= SHOWN_LIMIT) {
            return text;
        }
        int cut = Character.isHighSurrogate(text.charAt(SHOWN_LIMIT - 1))
                ? SHOWN_LIMIT - 1 : SHOWN_LIMIT;
        return new StringBuilder(cut + SHOWN_ELLIPSIS.length()).append(text, 0, cut)
                .append(SHOWN_ELLIPSIS).toString();
    }

    private static final char SECTION_SIGN = LabelText.FORMATTING;

    private static final char DELETE = LabelText.FIRST_NON_PRINTABLE_ASCII;

    static String drawable(String text) {
        return drawable(text, Integer.MAX_VALUE);
    }

    // Each line alone; the line breaks stay.
    static String drawableLines(String text) {
        String raw = text == null ? "" : text;
        StringBuilder out = new StringBuilder(raw.length());
        int from = 0;
        boolean more = true;
        while (more) {
            int next = raw.indexOf('\n', from);
            int end = next < 0 ? raw.length() : next;
            out.append(drawable(raw.substring(from, end)));
            more = next >= 0;
            if (more) {
                out.append('\n');
                from = next + 1;
            }
        }
        return out.toString();
    }

    private static String drawable(String text, int limit) {
        String raw = text == null ? "" : text;
        StringBuilder kept = null;
        int end = raw.length();
        int at = 0;
        while (at < end) {
            if ((kept == null ? at : kept.length()) >= limit) {
                break;
            }
            char letter = raw.charAt(at);
            if (letter == SECTION_SIGN) {
                if (kept == null) {
                    kept = new StringBuilder(raw.length()).append(raw, 0, at);
                }
                at++;
                if (at < raw.length() && Character.isHighSurrogate(raw.charAt(at))
                        && at + 1 < raw.length()
                        && Character.isLowSurrogate(raw.charAt(at + 1))) {
                    at++;
                }
                at++;
            } else if (LabelText.isDirectional(letter)) {
                if (kept == null) {
                    kept = new StringBuilder(raw.length()).append(raw, 0, at);
                }
                at++;
            } else {
                char clean = control(letter) ? ' ' : letter;
                if (clean != letter && kept == null) {
                    kept = new StringBuilder(raw.length()).append(raw, 0, at);
                }
                if (clean < DELETE) {
                    if (kept != null) {
                        kept.append(clean);
                    }
                    at++;
                } else {
                    int glyph = raw.codePointAt(at);
                    int width = Character.charCount(glyph);
                    if (CollectorGuess.drawnAsItself(glyph)) {
                        if (kept != null) {
                            kept.append(raw, at, at + width);
                        }
                    } else {
                        if (kept == null) {
                            kept = new StringBuilder(raw.length()).append(raw, 0, at);
                        }
                    }
                    at += width;
                }
            }
        }
        return kept == null ? raw : kept.toString();
    }

    // drawable(text) would return text unchanged.
    static boolean isDrawable(String text) {
        if (text == null) {
            return false;
        }
        boolean drawn = true;
        int at = 0;
        while (drawn && at < text.length()) {
            char letter = text.charAt(at);
            if (letter == SECTION_SIGN || LabelText.isDirectional(letter) || control(letter)) {
                drawn = false;
            } else if (letter < DELETE) {
                at++;
            } else {
                int glyph = text.codePointAt(at);
                if (!CollectorGuess.drawnAsItself(glyph)) {
                    drawn = false;
                } else {
                    at += Character.charCount(glyph);
                }
            }
        }
        return drawn;
    }

    private static final char SPACE = LabelText.FIRST_PRINTABLE_ASCII;

    private static final char LAST_C1_CONTROL = LabelText.LAST_CONTROL_ASCII;

    private static boolean control(char glyph) {
        return glyph < SPACE || (glyph >= DELETE && glyph <= LAST_C1_CONTROL);
    }

    private static final int JOINED_SERVERS_CAPACITY = 192;

    static String joinedServers(java.util.List<String> entries) {
        if (entries.size() == 1) {
            return withAsciiForm(drawable(String.valueOf(entries.get(0))));
        }
        StringBuilder out = new StringBuilder(JOINED_SERVERS_CAPACITY);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            withAsciiForm(out, drawable(String.valueOf(entries.get(i))));
        }
        return out.toString();
    }

    private static final int SERVERS_LISTED = 8;

    // The first servers, then how many more.
    private static String someServers(java.util.List<String> entries) {
        if (entries.size() <= SERVERS_LISTED) {
            return joinedServers(entries);
        }
        StringBuilder out = new StringBuilder(JOINED_SERVERS_CAPACITY);
        for (int i = 0; i < SERVERS_LISTED; i++) {
            if (i > 0) {
                out.append(", ");
            }
            withAsciiForm(out, drawable(String.valueOf(entries.get(i))));
        }
        return out.append(" and ").append(entries.size() - SERVERS_LISTED).append(" more").toString();
    }

    private static String withAsciiForm(String key) {
        String ascii = CollectorGuess.asciiForm(key);
        if (!ascii.isEmpty()) {
            return key + " (joins as " + ascii + ")";
        }
        return CollectorGuess.asciiRefused(key) ? key + " (will not join)" : key;
    }

    private static void withAsciiForm(StringBuilder out, String key) {
        String ascii = CollectorGuess.asciiForm(key);
        if (!ascii.isEmpty()) {
            out.append(key).append(" (joins as ").append(ascii).append(')');
            return;
        }
        out.append(key);
        if (CollectorGuess.asciiRefused(key)) {
            out.append(" (will not join)");
        }
    }


    private static final String ALSO_PUBLISHES =
            "\nThis build does not publish your live position or player list.";

    static Answer off(LandNavConfig config, Persist persist) {
        return settle(off(config), persist);
    }

    private static Answer off(LandNavConfig config) {
        config.shareEnabled = false;
        stopSending(config);
        return new Answer(true, "Contributing is off.");
    }

    static Answer on(LandNavConfig config, Persist persist) {
        return settle(on(config), persist);
    }

    private static Answer on(LandNavConfig config) {
        String typed = address(config);
        String joined = typed.isEmpty() ? ChunkCapture.joinedServer() : null;
        String found = ChunkCapture.knownCollector(joined);
        if (typed.isEmpty() && found.isEmpty()) {
            return onWithNoCollector(config);
        }
        if (!found.isEmpty()) {
            config.shareCollector = found;
        }
        String address = typed.isEmpty() ? found : typed;
        config.shareEnabled = true;
        URI parsed = uriOf(address);
        return new Answer(true, "Contributing is on; ground goes to "
                + drawable(address) + ", with your Minecraft UUID."
                + " A shared claim or marker sends your player name."
                + (found.isEmpty() ? "" : "\nAddress found for "
                        + ChunkCapture.serverKey(joined) + ".")
                + "\nThis does not put you on a"
                + " live map."
                + (config.sharePresence ? ALSO_PUBLISHES : "")
                + unusable(parsed) + inClear(parsed));
    }

    private static Answer proof(LandNavConfig config, boolean on) {
        config.shareSessionProof = on;
        String said;
        if (on) {
            said = "Prove this account to the collector"
                    + " is on; it sends your player name"
                    + " to the collector and"
                    + " to Mojang.";
        } else {
            config.shareSessionProofAutoEnabled = true;
            said = "Prove this account to the collector is off;"
                    + " a collector that needs it"
                    + " refuses your ground.";
        }
        return new Answer(true, said);
    }

    private static Answer onWithNoCollector(LandNavConfig config) {
        config.shareEnabled = true;
        return new Answer(true, "Contribute ground is on; nothing is uploaded until"
                + " a collector address is set.\n" + setWhenJoined());
    }

    private static String setWhenJoined() {
        return "Set one with /geosurvey collector <address>, or join a known server: "
                + LandNavConfig.KnownServers.names()
                + ".";
    }

    static Answer collector(LandNavConfig config, String typed, Persist persist) {
        return settle(collector(config, typed), persist);
    }

    static CollectorAddress collectorAddress(String typed) {
        String address = typed == null ? "" : typed.trim();
        if (address.isEmpty()) {
            return new CollectorAddress(false, "", "Nothing was set. Set an address with"
                    + " /geosurvey collector <address>, or stop"
                    + " contributing with /geosurvey share off.");
        }
        if (!drawable(address).equals(address)) {
            return new CollectorAddress(false, "", "Nothing was set. That address has a character"
                    + " chat cannot show.");
        }
        if (!looksUsable(address)) {
            return new CollectorAddress(false, "", "Nothing was set. Use"
                    + " an http:// or https:// address.");
        }
        if (cleartextAwayFromHome(address)) {
            return new CollectorAddress(false, "", "Nothing was set: that http:// address"
                    + " is off your network and would send"
                    + " your Minecraft UUID in clear text."
                    + " Use its"
                    + " https:// address,"
                    + " or name"
                    + " this one"
                    + " in"
                    + " geosurvey.json"
                    + " by hand.");
        }
        return new CollectorAddress(true, address, "");
    }

    static Answer collector(LandNavConfig config, String typed) {
        CollectorAddress requested = collectorAddress(typed);
        if (!requested.accepted()) {
            return new Answer(false, requested.refusal());
        }
        String address = requested.address();
        URI parsed = uriOf(address);
        config.shareCollector = address;
        String state = config.shareEnabled
                ? "\nContributing is on."
                : "\nContribute ground is off; turn it on with /geosurvey share"
                        + " on.";
        return new Answer(true,
                "Collector address set to " + address + "." + state + unusable(parsed));
    }

    private static void stopSending(LandNavConfig config) {
        ShareSender sender = ShareSender.live();
        if (sender != null) {
            sender.sync(config, null, null);
        }
    }

    static Answer status(LandNavConfig config) {
        String address = address(config);
        ShareSender sender = ShareSender.live();
        String steps = steps(config, sender);
        if (address.isEmpty()) {
            return new Answer(true, (config.shareEnabled
                    ? "Contributing: off; contribute ground is on but no collector address"
                            + " is set. " + setWhenJoined()
                    : "Contributing: off; no collector address is set.") + steps
                    + proofLine(config));
        }
        URI parsed = uriOf(address);
        return new Answer(true, config.shareEnabled
                ? ground(sender) + "Contributing: on; ground goes to " + drawable(address)
                        + ". Stop with /geosurvey share off." + unusable(parsed)
                        + inClear(parsed) + where(config) + steps + proofLine(config)
                        + doing(sender)
                : "Contributing: off; the collector address is " + drawable(address)
                        + " and contribute ground is off."
                        + unusable(parsed) + inClear(parsed) + steps + proofLine(config));
    }

    private static String proofLine(LandNavConfig config) {
        return config.shareSessionProof
                ? "\nProve this account to the collector: on; it sends your player name to the"
                        + " collector and to Mojang. Turn it off with /geosurvey"
                        + " share proof off."
                : "\nProve this account to the collector: off. Turn it on with /geosurvey"
                        + " share proof on; it sends your player name to the collector"
                        + " and to Mojang.";
    }

    private static final int STEPS_CAPACITY = 512;

    // sender may be null.
    private static String steps(LandNavConfig config, ShareSender sender) {
        StringBuilder out = new StringBuilder(STEPS_CAPACITY);
        for (String line : CollectorSettings.Setup.now(config, sender).lines()) {
            out.append('\n').append(line);
        }
        return out.toString();
    }

    private static String where(LandNavConfig config) {
        java.util.List<String> keys = ChunkCapture.approvedServers(config);
        return switch (CollectorSettings.whereOf(keys)) {
            case ALL -> "";
            case LISTED -> "\n" + fromLine(shownServers(keys));
        };
    }


    private static String address(LandNavConfig config) {
        return config.shareCollector == null ? "" : config.shareCollector.trim();
    }

    private static Answer saved(Persist persist, String said) {
        LandNav.Write write;
        Answer unstaged;
        try {
            write = persist.staged();
            unstaged = null;
        } catch (RuntimeException couldNotStage) {
            write = null;
            unstaged = landed(said, couldNotStage);
        }
        return unstaged == null ? writeHere(persist, write, said) : unstaged;
    }

    private static Answer writeHere(Persist persist, LandNav.Write write, String said) {
        return writeHere(persist, write, said, 0);
    }

    private static Answer writeHere(Persist persist, LandNav.Write write, String said,
            int staleRetries) {
        Answer result;
        Throwable writeFailure;
        AtomicFileReplace.beginNoWait();
        try {
            if (write == null) {
                persist.save();
            } else {
                write.write();
            }
            writeFailure = null;
        } catch (IOException | RuntimeException couldNotWrite) {
            if (write != null && couldNotWrite instanceof AtomicFileReplace.Refused) {
                CollectorOptions.keepForTheStopFlush(write);
            }
            writeFailure = couldNotWrite;
        } finally {
            AtomicFileReplace.endNoWait();
        }
        if (writeFailure == null) {
            if (write == null || write.latest()) {
                result = landed(said, null);
            } else if (staleRetries >= CollectorOptions.STALE_WRITE_CEILING) {
                result = landed(said, new IOException(CollectorOptions.LOST_TO_A_NEWER_WRITE));
            } else {
                LandNav.Write newest;
                RuntimeException stagingFailure;
                try {
                    newest = persist.staged();
                    stagingFailure = null;
                } catch (RuntimeException couldNotStage) {
                    newest = null;
                    stagingFailure = couldNotStage;
                }
                result = stagingFailure == null
                        ? writeHere(persist, newest, said, staleRetries + 1)
                        : landed(said, stagingFailure);
            }
        } else {
            result = landed(said, writeFailure);
        }
        return result;
    }

    private static Answer landed(String said, Throwable couldNotWrite) {
        return couldNotWrite == null
                ? new Answer(true, said + " Saved.")
                : new Answer(false, said + " Not saved;"
                        + " a restart can undo it: " + couldNotWrite + ".");
    }

    private static Answer settle(Answer decided, Persist persist) {
        return decided.ok() ? saved(persist, decided.text()) : decided;
    }

    private static int spoken(CommandContext<FabricClientCommandSource> context,
            Answer decided, Persist persist, Supplier<WorkPool> pools) {
        if (!decided.ok()) {
            return speak(context, decided);
        }
        return new Saving(context, decided.text(), persist, pools).start();
    }

    static int saved(FabricClientCommandSource source, Answer decided, Persist persist,
                     Supplier<WorkPool> pools) {
        if (!decided.ok()) {
            source.sendError(Component.literal(decided.text()));
            return 0;
        }
        return new Saving(source, decided.text(), persist, pools).start();
    }

    private static WorkPool poolOrNull(Supplier<WorkPool> pools) {
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

    private static Throwable attempt(LandNav.Write write) {
        Throwable failure;
        try {
            write.write();
            failure = null;
        } catch (IOException | RuntimeException broken) {
            failure = broken;
        }
        return failure;
    }

    private static IOException neverRan() {
        return new IOException("the work pool dropped the settings write unrun");
    }

    private static final class Saving {

        private final Consumer<Answer> reply;

        private final String said;

        private final Persist persist;

        private final Supplier<WorkPool> pools;

        private final AtomicBoolean answered = new AtomicBoolean();

        private final AtomicInteger staleRetries = new AtomicInteger();

        Saving(CommandContext<FabricClientCommandSource> context, String said,
                Persist persist, Supplier<WorkPool> pools) {
            this(answer -> speak(context, answer), said, persist, pools);
        }

        Saving(FabricClientCommandSource source, String said,
                Persist persist, Supplier<WorkPool> pools) {
            this(answer -> {
                if (answer.ok()) {
                    source.sendFeedback(Component.literal(answer.text()));
                } else {
                    source.sendError(Component.literal(answer.text()));
                }
            }, said, persist, pools);
        }

        private Saving(Consumer<Answer> reply, String said,
                       Persist persist, Supplier<WorkPool> pools) {
            this.reply = reply;
            this.said = said;
            this.persist = persist;
            this.pools = pools;
        }

        int start() {
            LandNav.Write write;
            RuntimeException stagingFailure;
            try {
                write = persist.staged();
                stagingFailure = null;
            } catch (RuntimeException couldNotStage) {
                write = null;
                stagingFailure = couldNotStage;
            }
            int result;
            if (stagingFailure == null) {
                LandNav.Write stagedWrite = write;
                WorkPool pool = stagedWrite == null ? null : poolOrNull(pools);
                boolean handed;
                if (pool == null) {
                    handed = false;
                } else {
                    QueuedWrite queued = new QueuedWrite(stagedWrite);
                    handed = pool.submit(queued::attempt,
                            failure -> landedOn(stagedWrite, failure),
                            () -> say(landed(said, neverRan())),
                            failure -> landedOn(stagedWrite, failure));
                    if (handed) {
                        CollectorOptions.keepForTheStopFlush(queued);
                    }
                }
                if (handed) {
                    result = 1;
                } else {
                    Answer answer = writeHere(persist, stagedWrite, said);
                    say(answer);
                    result = answer.ok() ? 1 : 0;
                }
            } else {
                result = say(landed(said, stagingFailure));
            }
            return result;
        }

        private void landedOn(LandNav.Write write, Throwable failure) {
            if (failure != null) {
                say(landed(said, failure));
                return;
            }
            if (write.latest()) {
                say(landed(said, null));
                return;
            }
            if (staleRetries.incrementAndGet() > CollectorOptions.STALE_WRITE_CEILING) {
                say(landed(said, new IOException(CollectorOptions.LOST_TO_A_NEWER_WRITE)));
                return;
            }
            start();
        }

        private int say(Answer answer) {
            if (answered.compareAndSet(false, true)) {
                reply.accept(answer);
            }
            return 1;
        }
    }

    private static final class QueuedWrite implements LandNav.Write {

        private final LandNav.Write write;

        private volatile boolean landed = false;

        QueuedWrite(LandNav.Write write) {
            this.write = write;
        }

        Throwable attempt() {
            Throwable failure = ShareCommand.attempt(write);
            if (failure == null) {
                landed = true;
            }
            return failure;
        }

        @Override
        public void write() throws IOException {
            if (!landed) {
                write.write();
            }
        }

        @Override
        public boolean latest() {
            return write.latest();
        }
    }

    private static final long BYTES_PER_MIB = 1L << ShareSpool.MIB_SHIFT;

    private static final int STATUS_REPORT_CAPACITY = 256;

    private static String doing(ShareSender sender) {
        if (sender == null) {
            return "";
        }
        StringBuilder sent = new StringBuilder();
        sent.append("Sent ").append(sender.sent()).append(", refused ")
                .append(sender.refused()).append(", failed ").append(sender.failed())
                .append('.');
        int lastStatus = sender.lastStatus();
        if (lastStatus != 0) {
            sent.append(" The collector last answered ").append(lastStatus)
                    .append('.');
        }
        String groundReason = sender.groundReason();
        if (!groundReason.isEmpty()) {
            sent.append("\nLast failure: ").append(groundReason).append('.');
        }
        long queued = sender.spooled();
        if (queued > 0) {
            long bytes = sender.spoolBytes();
            sent.append('\n').append(queued).append(" chunks waiting, ")
                    .append(bytes < BYTES_PER_MIB ? "under 1" : bytes >> ShareSpool.MIB_SHIFT)
                    .append(" MiB on disk.");
        }
        long aside = sender.spoolRefused();
        if (aside > 0) {
            sent.append(queued > 0 ? ' ' : '\n').append(aside).append(" set aside; the collector"
                    + " refused them.");
        }
        StringBuilder out = new StringBuilder(STATUS_REPORT_CAPACITY);
        appendLine(out, sent.toString());
        appendLine(out, identity(sender));
        appendLine(out, reporting(sender));
        appendLine(out, held(sender));
        appendLine(out, capped(sender));
        appendLine(out, hidden(sender));
        appendLine(out, saves());
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String said) {
        String only = said.trim();
        if (!only.isEmpty()) {
            out.append('\n').append(only);
        }
    }

    private static String held(ShareSender sender) {
        long held = sender.heldForLength();
        if (held == 0) {
            return "";
        }
        return " Held back: " + held + " players had a name too long to check for a"
                + " vanish marker.";
    }

    private static String capped(ShareSender sender) {
        long over = sender.heldForRosterCap();
        if (over == 0) {
            return "";
        }
        return " Roster limit: " + over + " players did not fit. A report carries "
                + RosterReport.MAX_PLAYERS + " players: the same "
                + RosterReport.MAX_PLAYERS + " every time,"
                + " ordered by account.";
    }

    private static String hidden(ShareSender sender) {
        long held = sender.heldWhileHidden();
        if (held == 0) {
            return "";
        }
        return " Held while hidden: " + held + " surveyed chunks were not contributed"
                + ". Your map kept"
                + " them.";
    }

    private static String saves() {
        ChunkCapture capture = ChunkCapture.live();
        return capture == null ? "" : saves(capture.savesStarted(),
                capture.savesFinished(), capture.savesFailed());
    }

    private static final int SAVES_LINE_CAPACITY = 96;

    static String saves(long started, long finished, long failed) {
        long waiting = started - finished;
        if (waiting <= 0 && failed == 0) {
            return "";
        }
        StringBuilder said = new StringBuilder(SAVES_LINE_CAPACITY).append(" Map saves: ");
        said.append(started).append(" region writes started and ")
                .append(finished).append(" finished.");
        if (failed > 0) {
            said.append(' ').append(failed).append(" failed; each is in"
                    + " the log and retried"
                    + " unless its world was left.");
        }
        if (waiting > 0) {
            said.append(failed > 0 ? '\n' : ' ').append(waiting).append(" have not finished;"
                    + " a count that does not move"
                    + " means the outcome is unknown.");
        }
        return said.toString();
    }

    private static String identity(ShareSender sender) {
        if (!sender.signing()) {
            return " NOTHING CAN BE SENT: this client has no identity"
                    + " to sign with.";
        }
        if (!sender.localIdentity()) {
            return " Identity: signed by Mojang. Any collector"
                    + " can attribute it to you.";
        }
        return " Identity: a key of this client's own; Mojang does not vouch"
                + " for it. A collector accepts it after you prove this account,"
                + " or its operator can trust"
                + " fingerprint "
                + sender.identityFingerprint()
                + ".";
    }

    private static final int POSITION_SECTION_CAPACITY = 384;

    private static String reporting(ShareSender sender) {
        StringBuilder said = new StringBuilder(POSITION_SECTION_CAPACITY);
        said.append(" Live position: sent ").append(sender.presenceSent())
                .append(", refused ").append(sender.presenceRefused())
                .append(", failed ").append(sender.presenceFailed()).append('.');
        int presenceLastStatus = sender.presenceLastStatus();
        if (presenceLastStatus != 0) {
            said.append(" The collector last answered ")
                    .append(presenceLastStatus).append(" to one.");
        }
        if (!sender.addressed()) {
            said.append("\nWith no address,"
                    + " the website shows no players, server clock"
                    + " or weather.");
        } else {
            String stalled = sender.presenceReason();
            if (!stalled.isEmpty()) {
                said.append("\nNot sent: ").append(stalled).append('.');
            } else if (!sender.isSending()) {
                said.append("\nNone reported: sending starts"
                        + " when there is surveyed ground"
                        + " to send.");
            }
            said.append(beatLines(sender.beatHealth()));
        }
        return said.toString();
    }

    // Each beat on a line of its own.
    private static String beatLines(String health) {
        return health.isEmpty() ? "" : "\n" + health.strip().replace(". ", ".\n");
    }

    private static String ground(ShareSender sender) {
        if (sender == null || sender.groundProven() || !sender.groundWorthSaying()) {
            return "";
        }
        return drawableLines(sender.groundSaying()) + "\n";
    }

    private static String unusable(String address) {
        return looksUsable(address) ? ""
                : "\nNothing is sent; use an http:// or https://"
                        + " address.";
    }

    private static String unusable(URI parsed) {
        return looksUsable(parsed) ? ""
                : "\nNothing is sent; use an http:// or https://"
                        + " address.";
    }

    private static boolean looksUsable(URI parsed) {
        return parsed != null;
    }

    private static String inClear(String address) {
        return cleartextAwayFromHome(address)
                ? "\nThat http:// address is off your network; it is"
                        + " readable on the way."
                : "";
    }

    private static String inClear(URI parsed) {
        return cleartextAwayFromHome(parsed)
                ? "\nThat http:// address is off your network; it is"
                        + " readable on the way."
                : "";
    }

    private static boolean cleartextAwayFromHome(URI parsed) {
        if (parsed == null) {
            return false;
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || !scheme.equalsIgnoreCase("http")
                || host == null || host.isEmpty()) {
            return false;
        }
        return !CollectorGuess.atHome(host.toLowerCase(Locale.ROOT));
    }

    static boolean looksUsable(String address) {
        return uriOf(address) != null;
    }

    public static boolean cleartextAwayFromHome(String address) {
        URI parsed = uriOf(address);
        if (parsed == null) {
            return false;
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || !scheme.equalsIgnoreCase("http")
                || host == null || host.isEmpty()) {
            return false;
        }
        return !CollectorGuess.atHome(host.toLowerCase(Locale.ROOT));
    }

    // Null when the address is not a URI.
    private static URI uriOf(String address) {
        return ShareSender.endpointOf(address, "");
    }

    // Each non-blank line of the text, trimmed, in order.
    static void eachLine(String text, Consumer<String> sink) {
        int length = text.length();
        int from = 0;
        while (from <= length) {
            int next = text.indexOf('\n', from);
            int end = next < 0 ? length : next;
            String said = text.substring(from, end);
            if (!said.isBlank()) {
                sink.accept(said.trim());
            }
            if (next < 0) {
                break;
            }
            from = next + 1;
        }
    }

    private static int speak(CommandContext<FabricClientCommandSource> context,
            Answer answer) {
        FabricClientCommandSource source = context.getSource();
        if (!answer.ok()) {
            eachLine(answer.text(), line -> source.sendError(Component.literal(line)));
            return 0;
        }
        eachLine(answer.text(), line -> source.sendFeedback(
                Component.literal(line).withStyle(ChatFormatting.GRAY)));
        return 1;
    }
}
