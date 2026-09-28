package dev.openmap.mixin;

import dev.openmap.client.TabListName;
import java.util.IdentityHashMap;
import java.util.Optional;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.TeamColor;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// Bounds or repairs one unresolvable name so it cannot stretch the tab list.
// Changes only the overlay's own copy; PlayerInfo.getTabListDisplayName, which
// the vanish filter reads, is untouched. Runs at RETURN, after vanilla's own
// decoration.
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Unique
    private static final int landnav$SETTLED_CEILING = 512;

    @Unique
    private final IdentityHashMap<PlayerInfo, Component> landnav$settled =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    // The Language active when the matching entry was cached.
    @Unique
    private final IdentityHashMap<PlayerInfo, Language> landnav$settledLanguage =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, Boolean> landnav$settledSpectator =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, PlayerTeam> landnav$settledTeam =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, Component> landnav$settledPrefix =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, Component> landnav$settledSuffix =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, Optional<TeamColor>> landnav$settledColor =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final IdentityHashMap<PlayerInfo, Component> landnav$settledRepaired =
            new IdentityHashMap<>(landnav$SETTLED_CEILING);

    @Unique
    private final PlayerInfo[] landnav$settledOrder =
            new PlayerInfo[landnav$SETTLED_CEILING];

    @Unique
    private int landnav$settledCursor = 0;

    @Unique
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component landnav$boundTabName(Component shown, PlayerInfo info) {
        Component sent = info.getTabListDisplayName();
        boolean spectator = info.getGameMode() == GameType.SPECTATOR;
        Component prior = sent != null ? landnav$settled.get(info) : null;
        Component repaired = prior != null ? landnav$settledRepaired.get(info) : null;
        Component result;
        if (landnav$cacheCurrent(info, sent, prior, spectator, repaired)) {
            result = repaired != null ? repaired : shown;
        } else {
            Component tidied = TabListName.tidied(shown, info, spectator);
            if (sent != null) {
                landnav$settle(info, shown, sent, prior, spectator, tidied);
            }
            result = tidied;
        }
        return result;
    }

    @Unique
    private boolean landnav$cacheCurrent(PlayerInfo info, Component sent, Component prior,
            boolean spectator, Component repaired) {
        return prior == sent && sent != null
                && landnav$settledLanguage.get(info) == Language.getInstance()
                && landnav$settledSpectator.get(info) == (spectator ? Boolean.TRUE : Boolean.FALSE)
                && (repaired == null || landnav$cacheTeamCurrent(info));
    }

    @Unique
    private boolean landnav$cacheTeamCurrent(PlayerInfo info) {
        PlayerTeam team = info.getTeam();
        return landnav$settledTeam.get(info) == team
                && (team == null
                        || TabListName.sameTeamLook(team, landnav$settledPrefix.get(info),
                                landnav$settledSuffix.get(info), landnav$settledColor.get(info)));
    }

    @Unique
    private void landnav$settle(PlayerInfo info, Component shown, Component sent,
            Component prior, boolean spectator, Component tidied) {
        if (prior == null && landnav$settled.size() >= landnav$SETTLED_CEILING) {
            PlayerInfo evicted = landnav$settledOrder[landnav$settledCursor];
            if (evicted != null) {
                landnav$settled.remove(evicted);
                landnav$settledLanguage.remove(evicted);
                landnav$settledSpectator.remove(evicted);
                landnav$settledTeam.remove(evicted);
                landnav$settledPrefix.remove(evicted);
                landnav$settledSuffix.remove(evicted);
                landnav$settledColor.remove(evicted);
                landnav$settledRepaired.remove(evicted);
            }
        }
        landnav$settled.put(info, sent);
        landnav$settledLanguage.put(info, Language.getInstance());
        landnav$settledSpectator.put(info, spectator ? Boolean.TRUE : Boolean.FALSE);
        if (tidied != shown) {
            PlayerTeam team = info.getTeam();
            landnav$settledTeam.put(info, team);
            if (team == null) {
                landnav$settledPrefix.remove(info);
                landnav$settledSuffix.remove(info);
                landnav$settledColor.remove(info);
            } else {
                landnav$settledPrefix.put(info, team.getPlayerPrefix());
                landnav$settledSuffix.put(info, team.getPlayerSuffix());
                landnav$settledColor.put(info, team.getColor());
            }
            landnav$settledRepaired.put(info, tidied);
        } else {
            landnav$settledTeam.remove(info);
            landnav$settledPrefix.remove(info);
            landnav$settledSuffix.remove(info);
            landnav$settledColor.remove(info);
            landnav$settledRepaired.remove(info);
        }
        if (prior == null) {
            landnav$settledOrder[landnav$settledCursor] = info;
            landnav$settledCursor = (landnav$settledCursor + 1)
                    % landnav$SETTLED_CEILING;
        }
    }
}
