package dev.openmap.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.openmap.LandNav;
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    private ShareCommand() {
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("geosurvey");

    public static void register(Supplier<MapStorage> storage) {
        Markers markers = new Markers(storage);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            CLAIMS.workPool(Sandpaper.workPool());
            CLAIMS.saveFailureSink(problem -> LOGGER.warn(
                    "could not write the claim book; a temporary file may"
                            + " have been left beside it", problem));
            dispatcher.register(tree(LandNav::config, SETTINGS, CLAIMS, markers,
                    ShareCommand::standingOf, Sandpaper::workPool));
        });
    }

    // The path is resolved on use, not here.
    private static final Claims CLAIMS = new Claims(() ->
            LandNav.dataDir().resolve(ClaimBook.FILE));

    private static final class DirectSettings implements Persist {

        @Override
        public void save() throws IOException {
            LandNav.saveOrThrow();
        }

        @Override
        public LandNav.Write staged() {
            return LandNav.stageWrite();
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
                .then(claimTree(claims, where))
                .then(markerTree(markers, where))
                .then(ClientCommands.literal("share")
                        .executes(context -> speak(context, status(settings.get())))
                        .then(ClientCommands.literal("off")
                                .executes(context ->
                                        spoken(context, off(settings.get()),
                                                persist, pools)))
                        .then(ClientCommands.literal("on")
                                .executes(context ->
                                        spoken(context, on(settings.get()),
                                                persist, pools))))
                .then(ClientCommands.literal("collector")
                        .then(ClientCommands.literal("clear")
                                .executes(context -> spoken(context,
                                        forgetCollector(settings.get()), persist,
                                        pools)))
                        // find only offers addresses; naming one is the choice.
                        .then(ClientCommands.literal("find")
                                .executes(context -> CollectorFinder.offer(
                                        context.getSource(), settings.get())))
                        .then(ClientCommands.argument("address",
                                        StringArgumentType.greedyString())
                                // The same addresses collector find prints.
                                .suggests(Suggest.of(any ->
                                        CollectorFinder.seeds(settings.get())))
                                .executes(context -> spoken(context, collector(
                                        settings.get(),
                                        StringArgumentType.getString(context, "address")),
                                        persist, pools))))
                .then(ClientCommands.literal("friend")
                        .executes(context -> speak(context, friends(settings.get())))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("player",
                                                StringArgumentType.greedyString())
                                        // Online players only; a friend may be named while offline.
                                        .suggests(Suggest.filtered(context ->
                                                context.getSource().getOnlinePlayerNames()))
                                        .executes(context -> spoken(context, addFriend(
                                                settings.get(),
                                                StringArgumentType.getString(context, "player")),
                                                persist, pools))))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("player",
                                                StringArgumentType.greedyString())
                                        .suggests(Suggest.cleaned(any ->
                                                settings.get().friends))
                                        .executes(context -> spoken(context, removeFriend(
                                                settings.get(),
                                                StringArgumentType.getString(context, "player")),
                                                persist, pools)))))
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

    // /geosurvey claim: create, edit, list and remove a claim.
    private static LiteralArgumentBuilder<FabricClientCommandSource> claimTree(
            Claims claims,
            Function<CommandContext<FabricClientCommandSource>, Claims.Standing> where) {
        return ClientCommands.literal("claim")
                .executes(context -> say(context, claims.list()))
                .then(ClientCommands.literal("start")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .executes(context -> answered(claims.startAsync(
                                        where.apply(context), arg(context, "name"),
                                        said -> say(context, said))))))
                .then(ClientCommands.literal("corner")
                        .executes(context ->
                                say(context, claims.corner(where.apply(context)))))
                .then(ClientCommands.literal("undo")
                        .executes(context -> say(context, claims.undo())))
                .then(ClientCommands.literal("cancel")
                        .executes(context -> say(context, claims.cancel())))
                .then(ClientCommands.literal("finish")
                        .executes(context -> answered(claims.finishAsync(
                                where.apply(context),
                                said -> say(context, said)))))
                .then(ClientCommands.literal("colour")
                        .then(ClientCommands.argument("colour",
                                        StringArgumentType.word())
                                .suggests(Suggest.of(any -> Suggest.COLOURS()))
                                .executes(context -> say(context,
                                        claims.penColour(arg(context, "colour"))))))
                .then(ClientCommands.literal("rename")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(any -> claims.names()))
                                .then(ClientCommands.argument("newName",
                                                StringArgumentType.greedyString())
                                        .executes(context -> answered(claims.renameAsync(
                                                arg(context, "name"), arg(context, "newName"),
                                                said -> say(context, said)))))))
                .then(ClientCommands.literal("recolour")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.string())
                                .suggests(Suggest.quoting(any -> claims.names()))
                                .then(ClientCommands.argument("colour",
                                                StringArgumentType.word())
                                        .suggests(Suggest.of(any -> Suggest.COLOURS()))
                                        .executes(context -> answered(claims.recolourAsync(
                                                arg(context, "name"), arg(context, "colour"),
                                                said -> say(context, said)))))))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                // Greedy: takes the rest of the line, typed bare.
                                .suggests(Suggest.of(any -> claims.names()))
                                .executes(context -> answered(claims.removeAsync(
                                        arg(context, "name"),
                                        said -> say(context, said))))))
                .then(ClientCommands.literal("where")
                        .executes(context -> say(context, claims.where())));
    }

    // /geosurvey marker: place, list, edit and remove a marker.
    private static LiteralArgumentBuilder<FabricClientCommandSource> markerTree(
            Markers markers,
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
                                })))
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
                                                })))))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("name",
                                        StringArgumentType.greedyString())
                                .suggests(Suggest.of(context ->
                                        markers.names(worldOf(where, context))))
                                .executes(context -> mark(context,
                                        markers.remove(worldOf(where, context),
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
                        // Addresses a marker by position, not name.
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
                                                // word() cannot hold '#', so this uses greedyString.
                                                StringArgumentType.greedyString())
                                        // Named colours are suggested; a hex code is accepted but not suggested.
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

    // The dimension a marker is stored under, or null outside a world.
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
        return speak(context, new Answer(said.ok(), drawable(said.text())));
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

    // Where the player is standing, or null when there is no world.
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
        return speak(context, new Answer(said.ok(), drawable(said.text())));
    }

    private static int answered(Claims.Outcome outcome) {
        return switch (outcome) {
            case ANSWERED_OK, ANSWER_STILL_QUEUED -> 1;
            case ANSWERED_REFUSAL -> 0;
        };
    }


    // /geosurvey friend: who is drawn blue rather than neutral.
    static Answer friends(LandNavConfig config) {
        if (config.friends.isEmpty()) {
            return new Answer(true, "No friends named: every player is drawn neutral."
                    + " Name one with /geosurvey friend add <player> and they turn blue"
                    + " on the map and compass.");
        }
        return new Answer(true, config.friends.size() + " friend"
                + (config.friends.size() == 1 ? "" : "s") + ": "
                + joined(config.friends)
                + ". Remove one with /geosurvey friend remove <player>.");
    }

    static Answer addFriend(LandNavConfig config, String typed, Persist persist) {
        return settle(addFriend(config, typed), persist);
    }

    private static Answer addFriend(LandNavConfig config, String typed) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return new Answer(false, "Give the player's name."
                    + " Ex. /geosurvey friend add Steve.");
        }
        String drawnName = drawable(name);
        if (!drawnName.equals(name)) {
            return new Answer(false, "Nothing was added. \"" + shownDrawable(drawnName)
                    + "\" has a character chat cannot show.");
        }
        String shownName = shownDrawable(drawnName);
        if (config.isFriend(name)) {
            return new Answer(false, shownName + " is already a friend.");
        }
        Answer added;
        if (!config.addFriend(name)) {
            added = new Answer(false, "The friends list is full at "
                    + LandNavConfig.MOST_FRIENDS + ". Remove somebody first.");
        } else {
        // Names the account name, which may differ from a typed nickname.
            added = new Answer(true, "Added " + shownName + " as a friend."
                    + " They are drawn blue wherever their account name is "
                    + shownName.toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return added;
    }

    static Answer removeFriend(LandNavConfig config, String typed, Persist persist) {
        return settle(removeFriend(config, typed), persist);
    }

    private static Answer removeFriend(LandNavConfig config, String typed) {
        String name = typed == null ? "" : typed.trim();
        String seen = drawable(name.toLowerCase(Locale.ROOT));
        boolean removed = config.removeFriend(name);
        for (String friend : new java.util.ArrayList<>(config.friends)) {
            if (!seen.isEmpty() && seen.equals(drawable(friend))) {
                removed |= config.removeFriend(friend);
            }
        }
        Answer answer;
        if (!removed) {
            answer = new Answer(false, "\"" + shown(name) + "\" is not on the friends"
                    + " list. /geosurvey friend shows who is.");
        } else {
            answer = new Answer(true, "Removed " + shown(name) + " from the friends list."
                    + " They are drawn neutral again.");
        }
        return answer;
    }

    static Answer servers(LandNavConfig config) {
        if (!ChunkCapture.gating(config)) {
            return new Answer(true, "Contributing from every server."
                    + " Add a server to filter using: /geosurvey server add"
                    + " <ip>");
        }
        java.util.List<String> list = ChunkCapture.approvedServers(config);
        if (list.isEmpty()) {
            return new Answer(true, "The approved-server list is empty:"
                    + " nothing is collected on any server. Single-player worlds"
                    + " are contributed either way. Add one with /geosurvey server"
                    + " add <ip>.");
        }
        int count = list.size();
        return new Answer(true, "Collecting from " + count + " server"
                + (count == 1 ? "" : "s") + ": " + joinedServers(list)
                + ". Nothing is collected on any other server. Add one with /geosurvey"
                + " server add <ip>, remove one with /geosurvey server remove <ip>.");
    }

    static Answer addServer(LandNavConfig config, String typed, Persist persist) {
        return settle(addServer(config, typed), persist);
    }

    private static Answer addServer(LandNavConfig config, String typed) {
        String key = ChunkCapture.serverKey(typed);
        if (key.isEmpty()) {
            return new Answer(false, "\"" + shown(typed) + "\" is not a server"
                    + " address. Give a host name or IP,"
                    + " with or without a port. Ex. /geosurvey server add"
                    + " avn.gg.");
        }
        String drawnKey = drawable(key);
        if (!drawnKey.equals(key)) {
            return new Answer(false, "Nothing was added. \"" + shownDrawable(drawnKey)
                    + "\" has a character chat cannot show.");
        }
        if (ChunkCapture.gatesOn(config, typed)) {
            return new Answer(false, withAsciiForm(key)
                    + " is already on the approved-server list.");
        }
        boolean arming = !ChunkCapture.gating(config);
        java.util.List<String> list = ChunkCapture.approvedServers(config);
        // Stores the port-aware world key; matching still goes by host.
        list.add(ChunkCapture.serverWorldKey(typed));
        config.approvedServersConfigured = true;
        String named = withAsciiForm(key);
        return new Answer(true, "Added " + named + " to the approved-server list."
                + (arming
                        ? " The list is now in force: GeoSurvey only collects from "
                                + named + " and single-player worlds."
                        : " Now contributing from "
                                + joinedServers(list) + "."));
    }

    static Answer removeServer(LandNavConfig config, String typed, Persist persist) {
        return settle(removeServer(config, typed), persist);
    }

    private static Answer removeServer(LandNavConfig config, String typed) {
        String seen = drawable(keyOrFolded(typed));
        String seenFold = drawable(fold(typed));
        // Used only when the entry has a parseable host.
        String seenJoined = drawable(ChunkCapture.serverWorldKey(typed));
        java.util.List<String> list = ChunkCapture.approvedServers(config);
        if (seen.isEmpty()
                || !list.removeIf(entry -> matchesRemoval(entry, seenFold, seenJoined))) {
            return new Answer(false, "\"" + shown(typed) + "\" is not on the"
                    + " approved-server list. /geosurvey server shows what is.");
        }
        config.approvedServersConfigured = true;
        return new Answer(true, "Removed " + withAsciiForm(seen) + "."
                + " No new ground is contributed from there. The worldmap"
                + " on disk is unchanged."
                + (list.isEmpty()
                        ? " The list is now empty: nothing is contributed from"
                                + " any server. Single-player worlds are unaffected."
                        : " Still contributing from " + joinedServers(list)
                                + "."));
    }

    private static boolean matchesRemoval(String entry, String seenFold, String seenJoined) {
        String entryKey = ChunkCapture.serverKey(entry);
        // Matches by host and port; a bare host matches only bare entries.
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

    // Whether drawable(text) would return text unchanged.
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

    private static final int JOINED_LIST_CAPACITY = 128;

    static String joined(java.util.List<String> entries) {
        if (entries.size() == 1) {
            return drawable(String.valueOf(entries.get(0)));
        }
        StringBuilder out = new StringBuilder(JOINED_LIST_CAPACITY);
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(drawable(String.valueOf(entries.get(i))));
        }
        return out.toString();
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


    // Appended when the ground switch turns publishing on too.
    private static final String ALSO_PUBLISHES =
            " Publishing is already on for ground. This build does not publish your name"
                    + " and your live position, or the players you can see.";

    static Answer off(LandNavConfig config, Persist persist) {
        return settle(off(config), persist);
    }

    private static Answer off(LandNavConfig config) {
        config.shareEnabled = false;
        stopSending(config);
        return new Answer(true, "Contributing is off. Nothing more is uploaded.");
    }

    static Answer on(LandNavConfig config, Persist persist) {
        return settle(on(config), persist);
    }

    private static Answer on(LandNavConfig config) {
        String address = address(config);
        if (address.isEmpty()) {
            return new Answer(false, "No collector address is set. Set one with "
                    + "/geosurvey collector <address>.");
        }
        config.shareEnabled = true;
        URI parsed = uriOf(address);
        return new Answer(true, "Contributing is on. Ground is uploaded to "
                + drawable(address) + ", carrying your Minecraft UUID and not your name. This "
                + "does not put you on a live map. Run /geosurvey share to see which "
                + "key is signing it."
                + (config.sharePresence ? ALSO_PUBLISHES : "")
                // Warned, not refused.
                + unusable(parsed) + inClear(parsed));
    }

    static Answer collector(LandNavConfig config, String typed, Persist persist) {
        return settle(collector(config, typed), persist);
    }

    private static Answer collector(LandNavConfig config, String typed) {
        String address = typed == null ? "" : typed.trim();
        if (address.isEmpty()) {
            return forgetCollector(config);
        }
        if (!drawable(address).equals(address)) {
            return new Answer(false, "Nothing was set. That address has a character"
                    + " chat cannot show.");
        }
        URI parsed = uriOf(address);
        // Refused, and nothing is written: not the address, not the switch.
        if (cleartextAwayFromHome(parsed)) {
            return new Answer(false, "Nothing was set. That address is http:// and off"
                    + " your own network. It would send your Minecraft"
                    + " UUID with every chunk, and, while publishing is"
                    + " on, your name, live position, and the players"
                    + " you can see, all in clear text. Anything on the"
                    + " way can read that, or answer as the collector;"
                    + " this client trusts it, and the ground behind"
                    + " that answer is lost. Use the https:// address of"
                    + " the same collector. One with no https:// can be"
                    + " named in geosurvey.json by hand.");
        }
        config.shareCollector = address;
        String state;
        if (config.shareEnabled) {
            state = " Contributing is on. Ground goes there.";
        } else {
            config.shareEnabled = true;
            state = " Contributing was off and is now on. Stop it with"
                    + " /geosurvey share off."
                    + (config.sharePresence ? ALSO_PUBLISHES : "");
        }
        return new Answer(true,
                "Collector address set to " + address + "." + state + unusable(parsed));
    }

    static Answer forgetCollector(LandNavConfig config, Persist persist) {
        return settle(forgetCollector(config), persist);
    }

    private static Answer forgetCollector(LandNavConfig config) {
        config.shareCollector = "";
        config.shareEnabled = false;
        stopSending(config);
        return new Answer(true, "Collector address cleared. Contributing is off,"
                + " nothing is uploaded.");
    }

    private static void stopSending(LandNavConfig config) {
        ShareSender sender = ShareSender.live();
        if (sender != null) {
            sender.sync(config, null, null);
        }
    }

    // Reports both switches; changes neither.
    static Answer status(LandNavConfig config) {
        String address = address(config);
        if (address.isEmpty()) {
            return new Answer(true, config.shareEnabled
                    ? "Contributing: off. The switch is on but no collector address"
                            + " is set. Set one with /geosurvey collector <address>."
                    : "Contributing: off. No collector address is set.");
        }
        URI parsed = uriOf(address);
        ShareSender sender = ShareSender.live();
        return new Answer(true, config.shareEnabled
                ? ground(sender) + "Contributing: on. Ground is uploaded to " + drawable(address)
                        + ". Stop with /geosurvey share off." + unusable(parsed)
                        + inClear(parsed) + where(config) + doing(sender)
                : "Contributing: off. The collector address is " + drawable(address)
                        + " and the switch is off. Nothing is sent."
                        + unusable(parsed) + inClear(parsed));
    }

    // Empty unless the approved-server list is narrowed or emptied.
    private static String where(LandNavConfig config) {
        java.util.List<String> keys = ChunkCapture.approvedServers(config);
        CollectorSettings.Where whereState =
                CollectorSettings.whereOf(ChunkCapture.gating(config), keys);
        return switch (whereState) {
            case ALL -> "";
            case NONE -> " The approved-server list is empty:"
                    + " nothing is contributed from any server.";
            case LISTED -> " Collecting from " + joinedServers(keys) + ".";
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
        MapStorage.FileReplace.beginNoWait();
        try {
            if (write == null) {
                persist.save();
            } else {
                write.write();
            }
            writeFailure = null;
        } catch (IOException | RuntimeException couldNotWrite) {
            if (write != null && couldNotWrite instanceof MapStorage.FileReplace.Refused) {
                CollectorOptions.keepForTheStopFlush(write);
            }
            writeFailure = couldNotWrite;
        } finally {
            MapStorage.FileReplace.endNoWait();
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
                : new Answer(false, said + " But the settings file could not be "
                        + "written. A restart may undo it: " + couldNotWrite + ".");
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
        return new IOException("the work pool let the settings write go before it ran");
    }

    private static final class Saving {

        private final CommandContext<FabricClientCommandSource> context;

        private final String said;

        private final Persist persist;

        private final Supplier<WorkPool> pools;

        private final AtomicBoolean answered = new AtomicBoolean();

        private final AtomicInteger staleRetries = new AtomicInteger();

        Saving(CommandContext<FabricClientCommandSource> context, String said,
                Persist persist, Supplier<WorkPool> pools) {
            this.context = context;
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
                result = handed ? 1 : say(writeHere(persist, stagedWrite, said));
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
            return answered.compareAndSet(false, true) ? speak(context, answer) : 1;
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
            sent.append(" Last failure: ").append(groundReason).append('.');
        }
        long queued = sender.spooled();
        if (queued > 0) {
            long bytes = sender.spoolBytes();
            sent.append(' ').append(queued).append(" chunks waiting to go, ")
                    .append(bytes < BYTES_PER_MIB ? "under 1" : bytes >> ShareSpool.MIB_SHIFT)
                    .append(" MiB on disk.");
        }
        long aside = sender.spoolRefused();
        if (aside > 0) {
            sent.append(' ').append(aside).append(" set aside after the collector"
                    + " refused them. They do not hold up new ground.");
        }
        // Each section on its own line.
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

    // One section, on its own line, or nothing at all.
    private static void appendLine(StringBuilder out, String said) {
        String only = said.trim();
        if (!only.isEmpty()) {
            out.append('\n').append(only);
        }
    }

    // Said only when the count is not zero.
    private static String held(ShareSender sender) {
        long held = sender.heldForLength();
        if (held == 0) {
            return "";
        }
        return " Held back: " + held + " players had a name too long to search for a"
                + " vanish marker. This client does not report them.";
    }

    // Said only when the count is not zero.
    private static String capped(ShareSender sender) {
        long over = sender.heldForRosterCap();
        if (over == 0) {
            return "";
        }
        return " Roster limit: " + over + " players did not fit. One report carries "
                + RosterReport.MAX_PLAYERS + " players. This client sends the same "
                + RosterReport.MAX_PLAYERS + " every time, ordered by account, and"
                + " skips the rest.";
    }

    // Said only when the count is not zero.
    private static String hidden(ShareSender sender) {
        long held = sender.heldWhileHidden();
        if (held == 0) {
            return "";
        }
        return " Held while hidden: " + held + " surveyed chunks were not contributed"
                + "; this client was vanished. Your own map kept every one of"
                + " them.";
    }

    // Empty when there is no running capture.
    private static String saves() {
        ChunkCapture capture = ChunkCapture.live();
        return capture == null ? "" : saves(capture.savesStarted(),
                capture.savesFinished(), capture.savesFailed());
    }

    private static final int SAVES_LINE_CAPACITY = 96;

    // Said only when work is waiting or something failed.
    static String saves(long started, long finished, long failed) {
        long waiting = started - finished;
        if (waiting <= 0 && failed == 0) {
            return "";
        }
        StringBuilder said = new StringBuilder(SAVES_LINE_CAPACITY).append(" Map saves: ");
        said.append(started).append(" region writes handed to the work pool and ")
                .append(finished).append(" accounted for.");
        if (failed > 0) {
            said.append(' ').append(failed).append(" of those failed. Each one is in"
                    + " the log with its world and its region, and goes back on the"
                    + " list unless that world has already been left.");
        }
        if (waiting > 0) {
            said.append(' ').append(waiting).append(" have not come back. One for a"
                    + " moment is a write still running; one that does not move is a"
                    + " write whose outcome this client cannot confirm.");
        }
        return said.toString();
    }

    private static String identity(ShareSender sender) {
        if (!sender.signing()) {
            return " NOTHING CAN BE SENT: this client has no identity to sign a"
                    + " contribution with yet.";
        }
        if (!sender.localIdentity()) {
            return " Identity: signed by Mojang. Any collector that accepts"
                    + " contributions at all can attribute this to you.";
        }
        return " Identity: a key of this client's own. Mojang does not vouch"
                + " for it. A collector refuses it until its operator has been told to"
                + " trust the key. Give them this fingerprint: "
                + sender.identityFingerprint()
                + ". It is also in the log, where it can be copied.";
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
            said.append(" NONE IS BEING REPORTED: there is no address to post it to."
                    + " The website shows no players, no server clock and no"
                    + " weather. Those three are one feature, not three.");
        } else {
            String stalled = sender.presenceReason();
            if (!stalled.isEmpty()) {
                said.append(" It is not going out: ").append(stalled).append('.');
            } else if (!sender.isSending()) {
                said.append(" None has been reported yet: the sender only starts once"
                        + " there is surveyed ground to send. Nothing has run to"
                        + " report a position yet.");
            }
        // Quiet when nothing is wrong.
            said.append(sender.beatHealth());
        }
        return said.toString();
    }

    // Empty when the walk is finished and nothing was asked.
    private static String ground(ShareSender sender) {
        if (sender == null || sender.groundProven() || !sender.groundWorthSaying()) {
            return "";
        }
        return drawable(sender.groundSaying()) + "\n";
    }

    private static String unusable(String address) {
        return looksUsable(address) ? ""
                : " That is not an http:// or https:// address. Nothing can be sent "
                        + "to it.";
    }

    private static String unusable(URI parsed) {
        return looksUsable(parsed) ? ""
                : " That is not an http:// or https:// address. Nothing can be sent "
                        + "to it.";
    }

    private static boolean looksUsable(URI parsed) {
        return parsed != null;
    }

    // Never fires alongside unusable().
    private static String inClear(String address) {
        return cleartextAwayFromHome(address)
                ? " That address is http:// and is not on your own network. What"
                        + " goes to it crosses the internet in clear."
                : "";
    }

    private static String inClear(URI parsed) {
        return cleartextAwayFromHome(parsed)
                ? " That address is http:// and is not on your own network. What"
                        + " goes to it crosses the internet in clear."
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

    // Package-visible for CollectorSettings.
    static boolean looksUsable(String address) {
        return uriOf(address) != null;
    }

    // Whether posting here would send a signed record of movement in clear.
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

    // The typed address as a URI, or null when it is not one.
    private static URI uriOf(String address) {
        return ShareSender.endpointOf(address, "");
    }

    private static int speak(CommandContext<FabricClientCommandSource> context,
            Answer answer) {
        // One feedback line per line of text; an error stays one line.
        if (!answer.ok()) {
            context.getSource().sendError(Component.literal(answer.text()));
            return 0;
        }
        String text = answer.text();
        int length = text.length();
        int from = 0;
        while (from <= length) {
            int next = text.indexOf('\n', from);
            int end = next < 0 ? length : next;
            String said = text.substring(from, end);
            if (!said.isBlank()) {
                context.getSource().sendFeedback(
                        Component.literal(said.trim()).withStyle(ChatFormatting.GRAY));
            }
            if (next < 0) {
                break;
            }
            from = next + 1;
        }
        return 1;
    }
}
