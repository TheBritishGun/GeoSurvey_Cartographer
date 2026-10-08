package dev.openmap.client;

import com.mojang.authlib.GameProfile;
import dev.openmap.map.LabelText;
import dev.openmap.map.TabName;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Unit;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;

public final class TabListName {

    private static final String BADGE = " BOT";

    private static final Component BADGE_COMPONENT =
            Component.literal(BADGE).withStyle(ChatFormatting.DARK_BLUE);

    // Single-entry memo, keyed by identity.
    private static Component damagedMemoShown;
    private static GameProfile damagedMemoProfile;
    private static boolean damagedMemoSpectator;
    private static PlayerTeam damagedMemoTeam;
    private static Component damagedMemoTeamPrefix;
    private static Component damagedMemoTeamSuffix;
    private static Optional<TeamColor> damagedMemoTeamColor;
    private static Component damagedMemoResult;

    private static final Relay RELAY = new Relay();

    private TabListName() {
    }

    public static Component tidied(Component shown, GameProfile profile,
                                   boolean spectator, Supplier<PlayerTeam> team) {
        if (shown == null || profile == null) {
            return shown;
        }
        Component result;
        boolean sameKeyAsMemo = shown == damagedMemoShown && profile == damagedMemoProfile
                && spectator == damagedMemoSpectator;
        PlayerTeam resolvedTeam = sameKeyAsMemo ? team.get() : null;
        if (sameKeyAsMemo && resolvedTeam == damagedMemoTeam
                && damagedMemoTeamCurrent(resolvedTeam)) {
            result = damagedMemoResult;
        } else {
            String flat = shown.getString(TabName.MAX_FILTER_CHARS + 1);
            if (TabName.damaged(flat, profile.id())) {
                if (!sameKeyAsMemo) {
                    resolvedTeam = team.get();
                }
                result = tidiedDamaged(shown, profile, spectator, resolvedTeam);
            } else {
                result = tidiedUndamaged(shown, flat);
            }
        }
        return result;
    }

    public static Component tidied(Component shown, PlayerInfo info, boolean spectator) {
        if (shown == null || info == null) {
            return shown;
        }
        GameProfile profile = info.getProfile();
        Component result;
        boolean sameKeyAsMemo = shown == damagedMemoShown && profile == damagedMemoProfile
                && spectator == damagedMemoSpectator;
        PlayerTeam resolvedTeam = sameKeyAsMemo ? info.getTeam() : null;
        if (sameKeyAsMemo && resolvedTeam == damagedMemoTeam
                && damagedMemoTeamCurrent(resolvedTeam)) {
            result = damagedMemoResult;
        } else {
            String flat = shown.getString(TabName.MAX_FILTER_CHARS + 1);
            if (TabName.damaged(flat, profile.id())) {
                if (!sameKeyAsMemo) {
                    resolvedTeam = info.getTeam();
                }
                result = tidiedDamaged(shown, profile, spectator, resolvedTeam);
            } else {
                result = tidiedUndamaged(shown, flat);
            }
        }
        return result;
    }

    private static Component tidiedDamaged(Component shown, GameProfile profile,
            boolean spectator, PlayerTeam resolvedTeam) {
        MutableComponent plain =
                Component.literal(TabName.bounded(profile.name()));
        Component decorated = decorate(PlayerTeam.formatNameForTeam(resolvedTeam, plain),
                spectator);
        String decoratedString = decorated.getString();
        String bounded = TabName.bounded(decoratedString);
        Component result = bounded.equals(decoratedString)
                ? decorated : Component.literal(bounded);
        rememberDamaged(shown, profile, spectator, resolvedTeam, result);
        return result;
    }

    private static Component tidiedUndamaged(Component shown, String flat) {
        String bounded = TabName.bounded(flat);

        Component result;
        if (flat.equals(bounded)) {
            result = shown;
        } else if (bounded.equals(LabelText.clean(flat, LabelText.UNBOUNDED_READ,
                TabName.MAX_SCAN, false))) {
            result = relaid(shown, bounded);
        } else {
            result = Component.literal(bounded).setStyle(shown.getStyle()
                    .withObfuscated(Boolean.FALSE).withFont(FontDescription.DEFAULT));
        }
        return result;
    }

    public static boolean sameTeamLook(PlayerTeam team, Component prefix, Component suffix,
            Optional<TeamColor> color) {
        return team.getPlayerPrefix() == prefix
                && team.getPlayerSuffix() == suffix
                && team.getColor() == color;
    }

    private static boolean damagedMemoTeamCurrent(PlayerTeam team) {
        return team == null || sameTeamLook(team, damagedMemoTeamPrefix, damagedMemoTeamSuffix,
                damagedMemoTeamColor);
    }

    private static void rememberDamaged(Component shown, GameProfile profile, boolean spectator,
            PlayerTeam team, Component result) {
        damagedMemoShown = shown;
        damagedMemoProfile = profile;
        damagedMemoSpectator = spectator;
        damagedMemoTeam = team;
        damagedMemoTeamPrefix = team == null ? null : team.getPlayerPrefix();
        damagedMemoTeamSuffix = team == null ? null : team.getPlayerSuffix();
        damagedMemoTeamColor = team == null ? null : team.getColor();
        damagedMemoResult = result;
    }

    private static Component relaid(Component shown, String bounded) {
        Style root = shown.getStyle().withObfuscated(Boolean.FALSE)
                .withFont(FontDescription.DEFAULT);
        MutableComponent cleaned = Component.literal("").setStyle(root);
        RELAY.reset(cleaned);
        Optional<Unit> pastTheWindow = shown.visit(RELAY, Style.EMPTY);
        Component result;
        if (pastTheWindow.isPresent() || !bounded.contentEquals(cleaned.getString())) {
            result = Component.literal(bounded).setStyle(root);
        } else {
            result = cleaned;
        }
        return result;
    }

    // Like tidied, for a string; profile can be null.
    public static String readable(String shown, GameProfile profile) {
        if (profile != null && TabName.damaged(shown, profile.id())) {
            return TabName.bounded(profile.name());
        }
        return TabName.bounded(shown);
    }

    private static Component decorate(MutableComponent name, boolean spectator) {
        MutableComponent badged = name.append(BADGE_COMPONENT);
        return spectator ? badged.withStyle(ChatFormatting.ITALIC) : badged;
    }

    private static final class Relay implements FormattedText.StyledContentConsumer<Unit> {

        private MutableComponent cleaned;
        private int read;

        private void reset(MutableComponent target) {
            cleaned = target;
            read = 0;
        }

        @Override
        public Optional<Unit> accept(Style style, String text) {
            if (read > TabName.MAX_SCAN) {
                return FormattedText.STOP_ITERATION;
            }
            read += text.length();
            String kept = LabelText.clean(text, LabelText.UNBOUNDED_READ,
                    LabelText.UNBOUNDED_READ, false);
            if (!kept.isEmpty()) {
                cleaned.append(Component.literal(kept).setStyle(style
                        .withObfuscated(Boolean.FALSE).withFont(FontDescription.DEFAULT)));
            }
            return Optional.empty();
        }
    }
}
